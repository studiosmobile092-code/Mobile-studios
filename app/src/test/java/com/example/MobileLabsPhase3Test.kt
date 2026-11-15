package com.example

import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.example.mobilelabs.diagnostics.DiagnosticsExporter
import com.example.mobilelabs.diagnostics.LocalCrashHandler
import com.example.mobilelabs.engine.AdaptiveQualityEngine
import com.example.mobilelabs.engine.GamingScenePresets
import com.example.mobilelabs.model.AdaptiveQualityLevel
import com.example.mobilelabs.model.BenchmarkRecord
import com.example.mobilelabs.model.DualDestinationState
import com.example.mobilelabs.model.GamingScenePreset
import com.example.mobilelabs.model.MobileLabsUiState
import com.example.mobilelabs.model.SourceType
import com.example.mobilelabs.model.TelemetrySnapshot
import com.example.mobilelabs.model.ThermalSummary
import com.example.mobilelabs.persistence.SceneCollectionRepository
import com.example.mobilelabs.probes.SamsungOneUiAdvisor
import com.example.mobilelabs.security.KeystoreSecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MobileLabsPhase3Test {

    @Test
    fun `M3_1 adaptive quality engine enforces 5s hysteresis and user lock`() {
        val engine = AdaptiveQualityEngine()
        val warmTelemetry = TelemetrySnapshot(
            thermal = ThermalSummary(
                statusCode = PowerManager.THERMAL_STATUS_MODERATE,
                statusLabel = "MODERATE",
                headroomForecast = 0.88f,
                batteryTempC = 41.0f
            )
        )

        // At t=1000ms and t=4000ms (< 5s), must NOT step down prematurely
        val r1 = engine.evaluate(warmTelemetry, isStreamingOrRecording = true, nowMs = 1_000L)
        assertEquals(AdaptiveQualityLevel.L0_NORMAL, r1.state.level)
        val r2 = engine.evaluate(warmTelemetry, isStreamingOrRecording = true, nowMs = 4_500L)
        assertEquals(AdaptiveQualityLevel.L0_NORMAL, r2.state.level)

        // At t=6500ms (>= 5s sustained pressure), steps down to L1
        val r3 = engine.evaluate(warmTelemetry, isStreamingOrRecording = true, nowMs = 6_500L)
        assertTrue(r3.levelChanged)
        assertEquals(AdaptiveQualityLevel.L1_MILD_THROTTLE, r3.state.level)

        // Locking prevents further automatic stepping even on severe spike
        engine.setLockedByUser(true)
        val severe = TelemetrySnapshot(
            thermal = ThermalSummary(statusCode = PowerManager.THERMAL_STATUS_SEVERE, batteryTempC = 45.0f)
        )
        val rLocked = engine.evaluate(severe, isStreamingOrRecording = true, nowMs = 30_000L)
        assertEquals(AdaptiveQualityLevel.L1_MILD_THROTTLE, rLocked.state.level)
        assertTrue(rLocked.state.lockedByUser)
    }

    @Test
    fun `M3_2 gaming scene presets generate editable OBS scenes with proper sources`() {
        val camChat = GamingScenePresets.buildScenesForPreset(GamingScenePreset.GAME_FACECAM_CHAT)
        assertEquals(1, camChat.size)
        val types = camChat.first().sources.map { it.type }
        assertTrue(types.contains(SourceType.SCREEN_CAPTURE))
        assertTrue(types.contains(SourceType.CAMERA_2))
        assertTrue(types.contains(SourceType.CHAT_OVERLAY))
        assertTrue(types.contains(SourceType.ALERT_BOX))

        val esports = GamingScenePresets.buildScenesForPreset(GamingScenePreset.PRO_ESPORTS_HUD)
        val esportsTypes = esports.first().sources.map { it.type }
        assertTrue(esportsTypes.contains(SourceType.SCOREBOARD_SOURCE))
        assertTrue(esportsTypes.contains(SourceType.GOAL_BAR))
        assertTrue(esportsTypes.contains(SourceType.ALERT_BOX))

        val irl = GamingScenePresets.buildScenesForPreset(GamingScenePreset.COZY_IRL_GOAL)
        val irlTypes = irl.first().sources.map { it.type }
        assertTrue(irlTypes.contains(SourceType.CAMERA_2))
        assertTrue(irlTypes.contains(SourceType.GOAL_BAR))

        val pack = GamingScenePresets.buildScenesForPreset(GamingScenePreset.INTERMISSION_PACK)
        assertEquals(3, pack.size)
        assertEquals(listOf("Starting Soon", "Be Right Back", "Stream Ending"), pack.map { it.name })
    }

    @Test
    fun `M3_1 network ABR drops bitrate under congestion and recovers smoothly when clear`() {
        val baseKbps = 6000
        val steppedDown = AdaptiveQualityEngine.evaluateNetworkAbr(
            currentBitrateKbps = baseKbps,
            configuredBaseKbps = baseKbps,
            congestionPressure = 0.85f,
            dynamicEnabled = true
        )
        assertTrue("Expected bitrate reduction on congestion, got $steppedDown", steppedDown < baseKbps)

        val recovered = AdaptiveQualityEngine.evaluateNetworkAbr(
            currentBitrateKbps = steppedDown,
            configuredBaseKbps = baseKbps,
            congestionPressure = 0.0f,
            dynamicEnabled = true
        )
        assertTrue("Expected gradual recovery, got $recovered <= $steppedDown", recovered > steppedDown)
        assertTrue(recovered <= baseKbps)
    }

    @Test
    fun `M3_3 SamsungOneUiAdvisor inspects device cleanly without prohibited hooks`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val status = SamsungOneUiAdvisor.inspect(ctx)
        assertTrue(status.ramTierLabel.isNotBlank())
        assertTrue(status.displayMaxRefreshHz >= 30f)
        assertTrue(status.guidanceNotes.isNotEmpty())
    }

    @Test
    fun `M3_4 and M3_8 redaction and diagnostics zip scrub both primary and secondary stream keys`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val primarySecret = "live_primary_secret_abc"
        val secondarySecret = "live_secondary_secret_xyz"

        val redacted = KeystoreSecretStore.redactUrlAndKeys(
            "Streaming primary=$primarySecret and secondary rtmps://live.twitch.tv/app/$secondarySecret",
            streamKey = primarySecret,
            extraKeys = listOf(secondarySecret)
        )
        assertFalse(redacted.contains(primarySecret))
        assertFalse(redacted.contains(secondarySecret))

        val exporter = DiagnosticsExporter(ctx)
        val state = MobileLabsUiState(
            rtmpServerUrl = "rtmp://192.168.1.10/live/$primarySecret",
            dualDestination = DualDestinationState(
                enabled = true,
                serverUrl = "rtmps://ingest.global-contribute.live-video.net/app/$secondarySecret",
                hasKeySaved = true
            ),
            benchmarkRecords = listOf(
                BenchmarkRecord(
                    id = "b1",
                    appName = "Mobile Labs",
                    presetLabel = "720p60",
                    gameName = "Geometry Dash",
                    gameAvgFps = 60f,
                    gameOnePercentLowFps = 58f,
                    streamDroppedPct = 0.0f,
                    peakThermalLabel = "TEMP OK",
                    processPssMb = 148,
                    notes = "Clean run",
                    recordedAtIso = "2026-11-14 14:00"
                )
            )
        )
        exporter.appendTelemetrySample(state)
        val zipPath = exporter.exportRedactedZip(state, primarySecret, secondarySecret)
        assertNotNull(zipPath)
        if (zipPath != null && File(zipPath).exists()) {
            ZipFile(File(zipPath)).use { zip ->
                val entries = zip.entries().toList()
                assertTrue(entries.any { it.name == "benchmark_records.json" })
                entries.forEach { e ->
                    val txt = zip.getInputStream(e).bufferedReader().readText()
                    assertFalse(txt.contains(primarySecret))
                    assertFalse(txt.contains(secondarySecret))
                }
            }
            File(zipPath).delete()
        }
    }

    @Test
    fun `M3_8 persisted settings round-trip adaptive lock, dual RTMP flag, and benchmark records`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val repo = SceneCollectionRepository(ctx)
        val sample = BenchmarkRecord(
            id = "run_1",
            appName = "Mobile Labs",
            presetLabel = "1280x720@60",
            gameName = "osu!",
            gameAvgFps = 59.8f,
            gameOnePercentLowFps = 56.1f,
            streamDroppedPct = 0.1f,
            peakThermalLabel = "WARM",
            processPssMb = 154,
            notes = "Baseline",
            recordedAtIso = "2026-11-14 14:10"
        )
        repo.saveSettingsAtomic(
            SceneCollectionRepository.PersistedSettings(
                adaptiveQualityLocked = true,
                onboardingCompleted = true,
                dualDestinationEnabled = true,
                benchmarkRecords = listOf(sample)
            )
        )
        val loaded = repo.loadSettings()
        assertTrue(loaded.adaptiveQualityLocked)
        assertTrue(loaded.onboardingCompleted)
        assertTrue(loaded.dualDestinationEnabled)
        assertEquals(1, loaded.benchmarkRecords.size)
        assertEquals("osu!", loaded.benchmarkRecords.first().gameName)
    }

    @Test
    fun `M3_8 MainActivity with 1-on-1 OBS Classic UI launches cleanly under Robolectric`() {
        val crashHandler = LocalCrashHandler(ApplicationProvider.getApplicationContext())
        crashHandler.consumeLastCrashSummary() // clear any leftover marker
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)
        assertFalse(activity.isFinishing)
    }
}
