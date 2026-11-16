package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.mobilelabs.diagnostics.DiagnosticsExporter
import com.example.mobilelabs.model.MobileLabsUiState
import com.example.mobilelabs.model.PresetId
import com.example.mobilelabs.model.ScreenFitMode
import com.example.mobilelabs.model.VideoConfig
import com.example.mobilelabs.model.defaultSceneCollection
import com.example.mobilelabs.persistence.SceneCollectionRepository
import com.example.mobilelabs.security.KeystoreSecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MobileLabsPhase1Test {

    @Test
    fun `redaction removes stream key and RTMP secret tokens`() {
        val secretKey = "live_super_secret_12345"
        val rawLog = "Connecting to rtmp://ingest.example.com/live/$secretKey?auth=tok999"
        val redacted = KeystoreSecretStore.redactUrlAndKeys(rawLog, secretKey)

        assertFalse(redacted.contains(secretKey))
        assertFalse(redacted.contains("tok999"))
        assertTrue(redacted.contains("[REDACTED"))
    }

    @Test
    fun `atomic scene collection persistence round-trips cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = SceneCollectionRepository(context)
        val original = defaultSceneCollection().copy(name = "Geometry Dash 720p60")

        repo.saveSceneCollectionAtomic(original)
        val restored = repo.loadSceneCollection()

        assertEquals("Geometry Dash 720p60", restored.name)
        assertEquals(original.scenes.size, restored.scenes.size)
        assertEquals(original.activeSceneId, restored.activeSceneId)
    }

    @Test
    fun `incomplete recording marker detects interrupted mp4 after crash`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = SceneCollectionRepository(context)
        val unfinished = File(repo.recordingsDir, "MobileLabs_test_incomplete.mp4").apply {
            writeBytes(ByteArray(4096) { 1 })
        }
        repo.markActiveRecording(unfinished, 1700000000000L)

        val detected = repo.detectIncompleteRecordings()
        assertTrue(detected.any { it.fileName == "MobileLabs_test_incomplete.mp4" })

        repo.clearActiveRecordingMarker()
        unfinished.delete()
    }

    @Test
    fun `canvas and output sizes remain independent`() {
        val cfg = VideoConfig(
            presetId = PresetId.BALANCED,
            canvasWidth = 1920,
            canvasHeight = 1080,
            outputWidth = 1280,
            outputHeight = 720,
            fps = 60,
            screenFitMode = ScreenFitMode.CROP_TO_FILL
        )
        assertEquals(1920, cfg.canvasWidth)
        assertEquals(1280, cfg.outputWidth)
        assertEquals(720, cfg.outputHeight)
    }

    @Test
    fun `diagnostics zip export omits stream key completely`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val exporter = DiagnosticsExporter(context)
        val secret = "live_secret_token_98765"
        val state = MobileLabsUiState(
            rtmpServerUrl = "rtmp://192.168.1.50:1935/live/$secret",
            eventLog = listOf("[12:00:00] Connecting to rtmp://192.168.1.50/live/$secret")
        )
        exporter.appendTelemetrySample(state)
        val path = exporter.exportRedactedZip(state, secret)
        assertTrue(path != null)
        if (path != null && File(path).exists()) {
            ZipFile(File(path)).use { zip ->
                val entries = zip.entries().toList()
                assertTrue(entries.any { it.name == "telemetry_1hz.csv" })
                assertTrue(entries.any { it.name == "settings_redacted.json" })
                entries.forEach { entry ->
                    val content = zip.getInputStream(entry).bufferedReader().readText()
                    assertFalse("Stream key leaked in ${entry.name}", content.contains(secret))
                }
            }
            File(path).delete()
        }
    }
}
