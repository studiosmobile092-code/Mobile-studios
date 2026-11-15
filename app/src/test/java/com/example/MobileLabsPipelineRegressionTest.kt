package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.mobilelabs.engine.SceneEditingHelper
import com.example.mobilelabs.media.AudioDelayRingBuffer
import com.example.mobilelabs.media.AudioDspProcessor
import com.example.mobilelabs.media.FlvH264Helper
import com.example.mobilelabs.media.RecordingPtsRebaser
import com.example.mobilelabs.media.ReplayGopRingBuffer
import com.example.mobilelabs.media.RtmpProtocolParser
import com.example.mobilelabs.model.AudioFilterConfig
import com.example.mobilelabs.model.FacecamShape
import com.example.mobilelabs.model.RecordingFormat
import com.example.mobilelabs.model.SceneCollection
import com.example.mobilelabs.model.SceneModel
import com.example.mobilelabs.model.SourceType
import com.example.mobilelabs.model.StreamingPlatform
import com.example.mobilelabs.model.VideoConfig
import com.example.mobilelabs.model.VoiceEffectPreset
import com.example.mobilelabs.persistence.ObsJsonImporterExporter
import com.example.mobilelabs.persistence.SceneCollectionRepository
import com.example.mobilelabs.platform.OAuthPkceManager
import com.example.mobilelabs.platform.TwitchIrcChatClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MobileLabsPipelineRegressionTest {

    @Test
    fun `FlvH264Helper builds valid AVCDecoderConfigurationRecord from Annex-B SPS and PPS`() {
        // Synthetic SPS (Baseline profile 0x42, compat 0xC0, level 0x1F) + PPS
        val annexBCsd = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0xC0.toByte(), 0x1F, 0xDA.toByte(), 0x01,
            0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte()
        )
        val avcc = FlvH264Helper.buildAvcDecoderConfigFromAnnexB(annexBCsd)
        assertNotNull(avcc)
        val bytes = avcc!!
        assertEquals(0x01, bytes[0].toInt() and 0xFF) // configurationVersion
        assertEquals(0x42, bytes[1].toInt() and 0xFF) // AVCProfileIndication
        assertEquals(0xC0, bytes[2].toInt() and 0xFF) // profile_compatibility
        assertEquals(0x1F, bytes[3].toInt() and 0xFF) // AVCLevelIndication
        assertEquals(0xFF, bytes[4].toInt() and 0xFF) // lengthSizeMinusOne = 3
        assertEquals(0xE1, bytes[5].toInt() and 0xFF) // 1 SPS
        val spsLen = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        assertEquals(6, spsLen)
    }

    @Test
    fun `FlvH264Helper converts Annex-B IDR frame to 4-byte length-prefixed AVCC and strips AUD and SPS`() {
        val annexBFrame = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, 0x09, 0x10, // AUD (type 9) -> stripped
            0x00, 0x00, 0x01, 0x67, 0x42, 0xC0.toByte(), 0x1F, // SPS (type 7) -> stripped
            0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte(), 0x84.toByte(), 0x21 // IDR slice (type 5, 4 bytes)
        )
        val avcc = FlvH264Helper.annexBFrameToAvcc(annexBFrame)
        val expected = byteArrayOf(
            0x00, 0x00, 0x00, 0x04, // 4-byte BE NALU length
            0x65, 0x88.toByte(), 0x84.toByte(), 0x21
        )
        assertArrayEquals(expected, avcc)
    }

    @Test
    fun `AudioDelayRingBuffer delays stereo PCM cleanly by requested milliseconds`() {
        val ring = AudioDelayRingBuffer()
        // 10 ms at 48000 Hz stereo = 960 interleaved samples
        val chunk = FloatArray(1920) { idx -> (idx + 1).toFloat() / 2000f }
        val copy = chunk.copyOf()
        ring.applyDelayInPlace(copy, copy.size, delayMs = 10, sampleRateHz = 48000)

        // First 960 samples come from initial silence in the ring buffer
        for (i in 0 until 960) {
            assertEquals(0f, copy[i], 0.0001f)
        }
        // Sample 960 equals input sample 0
        assertEquals(chunk[0], copy[960], 0.0001f)
        assertEquals(chunk[959], copy[1919], 0.0001f)
    }

    @Test
    fun `SceneCollectionRepository persists audioSyncOffsetsMs and segmented recording format`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val repo = SceneCollectionRepository(ctx)
        repo.saveSettingsAtomic(
            SceneCollectionRepository.PersistedSettings(
                videoConfig = VideoConfig(recordingFormat = RecordingFormat.SEGMENTED_MP4),
                audioSyncOffsetsMs = mapOf("audio_game" to -120, "audio_mic" to 85)
            )
        )
        val loaded = repo.loadSettings()
        assertEquals(RecordingFormat.SEGMENTED_MP4, loaded.videoConfig.recordingFormat)
        assertEquals(-120, loaded.audioSyncOffsetsMs["audio_game"])
        assertEquals(85, loaded.audioSyncOffsetsMs["audio_mic"])
    }

    @Test
    fun `Incomplete recording marker detects orphaned file and handles cleanup cleanly`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val repo = SceneCollectionRepository(ctx)
        val tempFile = File(ctx.filesDir, "orphaned_test.mp4")
        tempFile.writeBytes(ByteArray(4096) { 0x1A })

        repo.markActiveRecording(tempFile, System.currentTimeMillis())
        val detected = repo.detectIncompleteRecordings()
        assertTrue(detected.any { it.filePath == tempFile.absolutePath })

        tempFile.delete()
        repo.clearActiveRecordingMarker()
    }

    @Test
    fun `RtmpProtocolParser parses AMF0 onStatus NetStream Publish Start payload`() {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        fun writeAmfStr(s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            dos.writeByte(0x02)
            dos.writeShort(bytes.size)
            dos.write(bytes)
        }
        fun writeObjKey(s: String) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            dos.writeShort(bytes.size)
            dos.write(bytes)
        }
        writeAmfStr("onStatus")
        dos.writeByte(0x00)
        dos.writeDouble(0.0)
        dos.writeByte(0x05) // null
        dos.writeByte(0x03) // object
        writeObjKey("level")
        writeAmfStr("status")
        writeObjKey("code")
        writeAmfStr("NetStream.Publish.Start")
        writeObjKey("description")
        writeAmfStr("Stream is now published.")
        dos.writeShort(0)
        dos.writeByte(0x09) // object end

        val parsed = RtmpProtocolParser.parseAmf0Command(baos.toByteArray())
        assertEquals("onStatus", parsed.commandName)
        assertEquals("NetStream.Publish.Start", parsed.statusCode)
        assertTrue(parsed.description.contains("published"))
    }

    @Test
    fun `ReplayGopRingBuffer trims cleanly to IDR keyframe alignment`() {
        val raw = listOf(
            ReplayGopRingBuffer.EncodedSample(isVideo = false, isKey = false, ptsUs = 100L, flags = 0, data = byteArrayOf(1)),
            ReplayGopRingBuffer.EncodedSample(isVideo = true, isKey = false, ptsUs = 200L, flags = 0, data = byteArrayOf(2)),
            ReplayGopRingBuffer.EncodedSample(isVideo = true, isKey = true, ptsUs = 500L, flags = 1, data = byteArrayOf(3)),
            ReplayGopRingBuffer.EncodedSample(isVideo = false, isKey = false, ptsUs = 520L, flags = 0, data = byteArrayOf(4)),
            ReplayGopRingBuffer.EncodedSample(isVideo = true, isKey = false, ptsUs = 533L, flags = 0, data = byteArrayOf(5))
        )
        val aligned = ReplayGopRingBuffer.trimToKeyframeAlignedList(raw)
        assertEquals(3, aligned.size)
        assertTrue(aligned.first().isVideo && aligned.first().isKey)
        assertEquals(500L, aligned.first().ptsUs)
    }

    @Test
    fun `RecordingPtsRebaser produces monotonic PTS across pause and resume`() {
        val rebaser = RecordingPtsRebaser()
        // Pre-IDR video packet rejected until first IDR
        assertEquals(-1L, rebaser.filterAndRebase(isVideo = true, isKey = false, rawPtsUs = 900L))
        assertEquals(0L, rebaser.filterAndRebase(isVideo = true, isKey = true, rawPtsUs = 1_000L))
        assertEquals(16_667L, rebaser.filterAndRebase(isVideo = true, isKey = false, rawPtsUs = 17_667L))

        rebaser.pause(currentRawPtsUs = 20_000L)
        // Paused frames skipped
        assertEquals(-1L, rebaser.filterAndRebase(isVideo = true, isKey = true, rawPtsUs = 100_000L))

        rebaser.resume(currentRawPtsUs = 1_020_000L) // paused for 1,000,000 us
        // Non-keyframe right after resume skipped until IDR arrives
        assertEquals(-1L, rebaser.filterAndRebase(isVideo = true, isKey = false, rawPtsUs = 1_030_000L))
        val resumedPts = rebaser.filterAndRebase(isVideo = true, isKey = true, rawPtsUs = 1_036_667L)
        assertEquals(35_667L, resumedPts)
    }

    @Test
    fun `AudioDspProcessor auto-ducking attenuates when voice exceeds threshold and passes 1f when off`() {
        assertEquals(1f, AudioDspProcessor.computeDuckingGainStep(0.5f, 0.8f, -12f, enabled = false), 0.0001f)
        val ducked = AudioDspProcessor.computeDuckingGainStep(1.0f, 0.25f, -12f, enabled = true)
        assertTrue(ducked < 1.0f && ducked > 0.25f)
    }

    @Test
    fun `AudioDspProcessor executes 3-band EQ and all VoiceEffectPresets cleanly without NaN`() {
        val dsp = AudioDspProcessor(48000)
        VoiceEffectPreset.entries.forEach { preset ->
            val buf = FloatArray(1920) { i ->
                kotlin.math.sin(2.0 * kotlin.math.PI * 220.0 * (i / 2) / 48000.0).toFloat() * 0.4f
            }
            val cfg = AudioFilterConfig(
                eqEnabled = true,
                eqLowDb = 4f,
                eqMidDb = -2f,
                eqHighDb = 3f,
                voiceEffect = preset,
                reverbMix = 0.3f
            )
            dsp.processStereoInPlace(buf, buf.size, cfg)
            assertTrue("Preset $preset produced NaN/Inf", buf.all { it.isFinite() })
        }
    }

    @Test
    fun `OAuthPkceManager computes RFC 7636 S256 challenge and builds YouTube and Twitch auth URIs`() {
        // RFC 7636 Appendix B test vector
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        assertEquals(expectedChallenge, OAuthPkceManager.computeS256Challenge(verifier))

        val ytUri = OAuthPkceManager.buildAuthorizationUri(
            StreamingPlatform.YOUTUBE, "yt-client-123", "com.mobilelabs.studio:/oauth2redirect", "st1", expectedChallenge
        )
        assertEquals("S256", ytUri.getQueryParameter("code_challenge_method"))
        assertEquals("yt-client-123", ytUri.getQueryParameter("client_id"))

        val twUri = OAuthPkceManager.buildAuthorizationUri(
            StreamingPlatform.TWITCH, "tw-client-456", "mobilelabs://oauth2redirect", "st2", expectedChallenge
        )
        assertTrue(twUri.getQueryParameter("scope").orEmpty().contains("channel:read:stream_key"))
    }

    @Test
    fun `TwitchIrcChatClient parses IRCv3 PRIVMSG tags badges colors and message text`() {
        val raw = "@badge-info=;badges=moderator/1;color=#1E90FF;display-name=TabGamer;id=msg-99 :tabgamer!tabgamer@tabgamer.tmi.twitch.tv PRIVMSG #streamer :Let's go 60fps!"
        val parsed = TwitchIrcChatClient.parseIrcPrivMsg(raw)
        assertNotNull(parsed)
        assertEquals("TabGamer", parsed!!.author)
        assertEquals("MOD", parsed.badgeLabel)
        assertEquals(0xFF1E90FFL, parsed.colorArgb)
        assertEquals("Let's go 60fps!", parsed.text)
    }

    @Test
    fun `SceneEditingHelper creates valid default sources for all 18 SourceTypes and OBS JSON round-trips`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val allSources = SourceType.entries.map { type ->
            val created = SceneEditingHelper.createDefaultSource(type)
            if (type == SourceType.CAMERA_2) created.copy(facecamShape = FacecamShape.CIRCLE, borderWidthPx = 4f)
            else created
        }
        assertEquals(18, allSources.size)

        val collection = SceneCollection(
            id = "col_all",
            name = "Pro Studio Test",
            activeSceneId = "scene_1",
            scenes = listOf(SceneModel("scene_1", "Main", allSources))
        )
        val json = ObsJsonImporterExporter.exportToObsJson(collection, 1280, 720)
        val imported = ObsJsonImporterExporter.importFromObsJson(ctx, json, 1280, 720)
        val importedTypes = imported.collection.scenes.first().sources.map { it.type }.toSet()
        assertTrue(importedTypes.containsAll(listOf(
            SourceType.SCREEN_CAPTURE,
            SourceType.CAMERA_2,
            SourceType.BROWSER_SOURCE,
            SourceType.ALERT_BOX,
            SourceType.GOAL_BAR,
            SourceType.TIMER_SOURCE,
            SourceType.SCOREBOARD_SOURCE,
            SourceType.CHAT_OVERLAY,
            SourceType.DRAWING_LAYER
        )))
    }
}
