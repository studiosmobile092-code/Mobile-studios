package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.mobilelabs.media.AudioDelayRingBuffer
import com.example.mobilelabs.media.FlvH264Helper
import com.example.mobilelabs.model.RecordingFormat
import com.example.mobilelabs.model.VideoConfig
import com.example.mobilelabs.persistence.SceneCollectionRepository
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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
}
