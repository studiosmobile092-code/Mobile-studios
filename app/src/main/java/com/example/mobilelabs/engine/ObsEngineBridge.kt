package com.example.mobilelabs.engine

import android.content.Context
import com.example.mobilelabs.model.AudioFilterConfig
import com.example.mobilelabs.model.EngineBackendStatus
import com.example.mobilelabs.model.SourceModel
import com.example.mobilelabs.model.TransitionType
import com.example.mobilelabs.model.VideoConfig
import java.io.File

/**
 * Direct JNI binding matching app/src/main/cpp/mobilelabs_jni.cpp.
 * Loaded only when libmobilelabs_obs.so + libobs.so are compiled into the APK (GitHub Actions / NDK build).
 */
object ObsNativeJni {
    val isLibraryLoaded: Boolean by lazy {
        if (!com.example.BuildConfig.LIBOBS_NATIVE_ENABLED) return@lazy false
        try {
            System.loadLibrary("obs")
            System.loadLibrary("mobilelabs_obs")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    external fun nativeStartup(locale: String, configDir: String): Boolean
    external fun nativeShutdown()
    external fun nativeConfigureVideo(
        canvasWidth: Int,
        canvasHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        fps: Int
    ): Boolean

    external fun nativeConfigureAudio(sampleRateHz: Int): Boolean
    external fun nativeGetVersion(): String
    external fun nativeGetRenderSkippedFrames(): Long
    external fun nativeGetTotalFrames(): Long

    // Phase 2 scene, source, transform, transition, and filter sync hooks
    external fun nativeSyncSceneItemTransform(
        sceneName: String,
        sourceName: String,
        sourceObsId: String,
        xFrac: Float,
        yFrac: Float,
        wFrac: Float,
        hFrac: Float,
        rotationDeg: Float,
        visible: Boolean,
        locked: Boolean
    ): Boolean

    external fun nativeSetTransition(transitionObsId: String, durationMs: Int): Boolean
    external fun nativeSyncAudioFilterState(channelId: String, activeMask: Int, gainDb: Float): Boolean
}

/**
 * Facade managing libobs engine lifecycle cleanly without inventing fake libobs state when
 * native .so binaries are absent in non-NDK builds (Iron Rules R1, R4).
 */
class ObsEngineBridge(private val context: Context) {

    var backendStatus: EngineBackendStatus = if (ObsNativeJni.isLibraryLoaded) {
        EngineBackendStatus.LIBOBS_NATIVE
    } else {
        EngineBackendStatus.ANDROID_HARDWARE_PIPELINE
    }
        private set

    private var nativeStarted = false

    fun startup(videoConfig: VideoConfig): Boolean {
        if (!ObsNativeJni.isLibraryLoaded) {
            backendStatus = EngineBackendStatus.ANDROID_HARDWARE_PIPELINE
            return true
        }
        val cfgDir = File(context.filesDir, "obs_config").apply { mkdirs() }.absolutePath
        val ok = ObsNativeJni.nativeStartup("en-US", cfgDir)
        if (ok) {
            ObsNativeJni.nativeConfigureVideo(
                videoConfig.canvasWidth,
                videoConfig.canvasHeight,
                videoConfig.outputWidth,
                videoConfig.outputHeight,
                videoConfig.fps
            )
            ObsNativeJni.nativeConfigureAudio(48000)
            nativeStarted = true
            backendStatus = EngineBackendStatus.LIBOBS_NATIVE
        }
        return ok
    }

    fun reconfigureVideo(videoConfig: VideoConfig): Boolean {
        if (!nativeStarted || !ObsNativeJni.isLibraryLoaded) return true
        return ObsNativeJni.nativeConfigureVideo(
            videoConfig.canvasWidth,
            videoConfig.canvasHeight,
            videoConfig.outputWidth,
            videoConfig.outputHeight,
            videoConfig.fps
        )
    }

    fun syncSourceTransform(sceneName: String, source: SourceModel) {
        if (!nativeStarted || !ObsNativeJni.isLibraryLoaded) return
        val obsId = when (source.type.name) {
            "IMAGE_SOURCE", "GIF_STICKER" -> "image_source"
            "MEDIA_SOURCE" -> "ffmpeg_source"
            "TEXT_SOURCE", "TIMER_SOURCE", "SCOREBOARD_SOURCE" -> "text_ft2_source_v2"
            "COLOR_SOURCE" -> "color_source_v2"
            "CAMERA_2" -> "android_camera2"
            "BROWSER_SOURCE" -> "browser_source"
            "CHAT_OVERLAY" -> "chat_overlay_source"
            "ALERT_BOX" -> "alert_box_source"
            "GOAL_BAR" -> "goal_bar_source"
            "DRAWING_LAYER" -> "drawing_overlay_source"
            "SCREEN_REGION" -> "android_screen_region"
            "SOUNDBOARD_SOURCE" -> "soundboard_sfx"
            "MIC_AUDIO" -> "android_mic_audio"
            "SYSTEM_AUDIO" -> "android_system_audio"
            else -> "android_screen_capture"
        }
        runCatching {
            ObsNativeJni.nativeSyncSceneItemTransform(
                sceneName = sceneName,
                sourceName = source.name,
                sourceObsId = obsId,
                xFrac = source.transform.xFraction,
                yFrac = source.transform.yFraction,
                wFrac = source.transform.widthFraction,
                hFrac = source.transform.heightFraction,
                rotationDeg = source.transform.rotationDegrees,
                visible = source.visible,
                locked = source.locked
            )
        }
    }

    fun syncTransition(type: TransitionType, durationMs: Int) {
        if (!nativeStarted || !ObsNativeJni.isLibraryLoaded) return
        val obsId = when (type) {
            TransitionType.CUT -> "cut_transition"
            TransitionType.FADE -> "fade_transition"
            TransitionType.SLIDE -> "slide_transition"
            TransitionType.SWIPE -> "swipe_transition"
            TransitionType.FADE_TO_COLOR -> "fade_to_color_transition"
        }
        runCatching { ObsNativeJni.nativeSetTransition(obsId, durationMs) }
    }

    fun syncAudioFilter(channelId: String, filters: AudioFilterConfig) {
        if (!nativeStarted || !ObsNativeJni.isLibraryLoaded) return
        var mask = 0
        if (filters.gainEnabled) mask = mask or 1
        if (filters.noiseSuppressionEnabled) mask = mask or 2
        if (filters.noiseGateEnabled) mask = mask or 4
        if (filters.compressorEnabled) mask = mask or 8
        if (filters.limiterEnabled) mask = mask or 16
        if (filters.autoDuckingEnabled) mask = mask or 32
        if (filters.pushToTalkEnabled) mask = mask or 64
        runCatching { ObsNativeJni.nativeSyncAudioFilterState(channelId, mask, filters.gainDb) }
    }

    fun shutdown() {
        if (nativeStarted && ObsNativeJni.isLibraryLoaded) {
            ObsNativeJni.nativeShutdown()
            nativeStarted = false
        }
    }

    fun getVersionString(): String {
        return if (nativeStarted && ObsNativeJni.isLibraryLoaded) {
            runCatching { ObsNativeJni.nativeGetVersion() }.getOrDefault("libobs native")
        } else {
            "Android-Native Pipeline (MediaProjection + GLES3 + MediaCodec)"
        }
    }

    fun getRenderSkippedFrames(): Long {
        return if (nativeStarted && ObsNativeJni.isLibraryLoaded) {
            runCatching { ObsNativeJni.nativeGetRenderSkippedFrames() }.getOrDefault(0L)
        } else {
            0L
        }
    }
}
