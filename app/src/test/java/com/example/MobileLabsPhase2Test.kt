package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.mobilelabs.engine.SceneEditingHelper
import com.example.mobilelabs.engine.SessionController
import com.example.mobilelabs.media.AudioDspProcessor
import com.example.mobilelabs.model.AudioFilterConfig
import com.example.mobilelabs.model.PresetId
import com.example.mobilelabs.model.SourceModel
import com.example.mobilelabs.model.SourceTransform
import com.example.mobilelabs.model.SourceType
import com.example.mobilelabs.model.TransitionType
import com.example.mobilelabs.model.VideoFilterConfig
import com.example.mobilelabs.model.defaultSceneCollection
import com.example.mobilelabs.persistence.ObsJsonImporterExporter
import com.example.mobilelabs.persistence.SceneCollectionRepository
import com.example.mobilelabs.probes.DeviceAndCodecProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MobileLabsPhase2Test {

    @Test
    fun `M2_0 DeviceAndCodecProbe runs cleanly on non-visual Application context`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val probe = DeviceAndCodecProbe(appContext)
        val report = probe.runProbe(testConcurrency = false)
        assertTrue(report.sdkInt > 0)
        assertTrue(report.displayWidthPx > 0)
        assertTrue(report.displayHeightPx > 0)
    }

    @Test
    fun `M2_0 MainActivity launches cleanly under Robolectric without startup crash`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun `M2_2 transform snapping locks onto center and 90-degree rotation`() {
        val start = SourceTransform(xFraction = 0.395f, yFraction = 0.396f, widthFraction = 0.20f, heightFraction = 0.20f, rotationDegrees = 88.5f)
        val snapped = SceneEditingHelper.applyTransformWithSnap(
            current = start,
            dx = 0f,
            dy = 0f,
            dW = 0f,
            dH = 0f,
            dRotDeg = 0f,
            enableSnap = true
        )
        assertEquals(0.40f, snapped.xFraction, 0.002f)
        assertEquals(0.40f, snapped.yFraction, 0.002f)
        assertEquals(90f, snapped.rotationDegrees, 0.01f)
    }

    @Test
    fun `M2_3 audio and video filters are strictly OFF by default`() {
        val af = AudioFilterConfig()
        val vf = VideoFilterConfig()
        assertFalse(af.anyActive())
        assertFalse(vf.anyActive())
    }

    @Test
    fun `M2_3 AudioDspProcessor bypasses when off and enforces gain and limiter when enabled`() {
        val dsp = AudioDspProcessor(48000)
        val raw = floatArrayOf(0.25f, -0.25f, 0.50f, -0.50f)
        val bypassCopy = raw.copyOf()
        dsp.processStereoInPlace(bypassCopy, bypassCopy.size, AudioFilterConfig())
        assertTrue(raw.contentEquals(bypassCopy))

        // Gain +6dB roughly doubles 0.25 -> ~0.50; limiter at -6dB (~0.501 linear) clamps huge signal
        val boosted = floatArrayOf(0.25f, -0.25f, 0.90f, -0.90f)
        dsp.processStereoInPlace(
            boosted,
            boosted.size,
            AudioFilterConfig(gainEnabled = true, gainDb = 6f, limiterEnabled = true, limiterCeilingDb = -6f)
        )
        assertTrue(abs(boosted[0]) > 0.45f)
        assertTrue(abs(boosted[2]) <= 0.56f)
    }

    @Test
    fun `M2_4 Studio Mode isolates Preview edits from Program until Take`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val controller = SessionController(appContext)
        controller.initialize()

        val initialProgram = controller.uiState.value.programSceneId
        controller.toggleStudioMode()
        assertTrue(controller.uiState.value.studioModeEnabled)

        val secondScene = controller.uiState.value.sceneCollection.scenes.first { it.id != initialProgram }
        controller.selectScene(secondScene.id)

        // Preview changed, Program MUST stay on initial scene until Take
        assertEquals(secondScene.id, controller.uiState.value.previewSceneId)
        assertEquals(initialProgram, controller.uiState.value.programSceneId)

        controller.setTransition(TransitionType.SLIDE, 450)
        controller.triggerStudioTake()
        assertEquals(secondScene.id, controller.uiState.value.programSceneId)
    }

    @Test
    fun `M2_5 OBS JSON export and import round-trips cleanly and flags missing media safely`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val base = defaultSceneCollection().let { col ->
            val withMedia = col.scenes.first().copy(
                sources = col.scenes.first().sources + SourceModel(
                    id = "src_missing_img",
                    name = "Banner Logo",
                    type = SourceType.IMAGE_SOURCE,
                    mediaPathOrUri = "/nonexistent/path/banner_logo.png"
                )
            )
            col.copy(scenes = listOf(withMedia) + col.scenes.drop(1))
        }

        val exportedJson = ObsJsonImporterExporter.exportToObsJson(base)
        assertFalse(exportedJson.contains("stream_key"))

        val imported = ObsJsonImporterExporter.importFromObsJson(appContext, exportedJson)
        assertEquals(base.name, imported.collection.name)
        assertTrue(imported.missingAssets.any { it.contains("banner_logo.png") })

        val badResult = runCatching {
            ObsJsonImporterExporter.importFromObsJson(appContext, "{not valid json")
        }
        assertTrue(badResult.isFailure)
    }

    @Test
    fun `M2_5 profiles and audio filter settings persist atomically across reloads`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val repo = SceneCollectionRepository(appContext)
        val settings = SceneCollectionRepository.PersistedSettings(
            audioFilters = mapOf("audio_mic" to AudioFilterConfig(noiseSuppressionEnabled = true, compressorEnabled = true)),
            studioModeEnabled = true,
            activeProfileId = "prof_test"
        )
        repo.saveSettingsAtomic(settings)
        val restored = repo.loadSettings()

        assertTrue(restored.studioModeEnabled)
        assertEquals("prof_test", restored.activeProfileId)
        assertTrue(restored.audioFilters["audio_mic"]?.noiseSuppressionEnabled == true)
        assertTrue(restored.audioFilters["audio_mic"]?.compressorEnabled == true)
        assertEquals(PresetId.BALANCED, restored.videoConfig.presetId)
    }
}
