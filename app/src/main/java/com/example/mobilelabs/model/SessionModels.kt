package com.example.mobilelabs.model

import android.os.PowerManager
import androidx.compose.runtime.Immutable

enum class EngineBackendStatus(val label: String, val isLibObsLoaded: Boolean) {
    LIBOBS_NATIVE("libobs JNI + GLES3/MediaCodec", true),
    ANDROID_HARDWARE_PIPELINE("Android GLES3/MediaCodec (libobs .so unbuilt in this APK)", false)
}

enum class SessionPhase(val displayLabel: String) {
    IDLE("IDLE"),
    ARMING("ARMING"),
    ARMED("ARMED"),
    CONNECTING("CONNECTING"),
    LIVE("LIVE"),
    RECONNECTING("RECONNECTING"),
    STOPPING("STOPPING"),
    ERROR("ERROR")
}

enum class RecordingPhase(val displayLabel: String) {
    IDLE("IDLE"),
    RECORDING("REC"),
    PAUSED("PAUSED"),
    FINALIZING("FINALIZING"),
    ERROR("REC ERROR")
}

enum class EncoderFallbackLevel(val hudTag: String, val description: String) {
    OPTIMAL_HW_SURFACE("OPTIMAL", "Hardware H.264 Surface (zero-copy)"),
    DEGRADED_HW_BYTEBUFFER("DEGRADED", "Hardware H.264 ByteBuffer (CPU copy)"),
    EMERGENCY_X264("EMERGENCY", "Software x264 720p30 (CPU heavy: not for gaming)")
}

enum class ScreenFitMode(val label: String, val detail: String) {
    CROP_TO_FILL("Crop to fill (16:9)", "Hides ~10% top/bottom of 16:10 display"),
    FIT_WITH_BARS("Fit with bars", "Letterboxes 16:10 inside 16:9 canvas"),
    STRETCH("Stretch (discouraged)", "Distorts 16:10 into 16:9")
}

enum class TransitionType(val label: String) {
    CUT("Cut"),
    FADE("Fade"),
    SLIDE("Slide"),
    SWIPE("Swipe"),
    FADE_TO_COLOR("Fade Color")
}

enum class SourceType(val label: String, val isVisual: Boolean, val category: String = "Core") {
    SCREEN_CAPTURE("Screen Capture", true, "Capture & Camera"),
    SCREEN_REGION("Cropped Screen Region", true, "Capture & Camera"),
    CAMERA_2("Camera (Camera2)", true, "Capture & Camera"),
    IMAGE_SOURCE("Image", true, "Media & Graphics"),
    GIF_STICKER("Animated GIF / Sticker", true, "Media & Graphics"),
    MEDIA_SOURCE("Media (Video/Audio)", true, "Media & Graphics"),
    BROWSER_SOURCE("Browser / Alert URL", true, "Widgets & Chat"),
    CHAT_OVERLAY("Live Chat Box Overlay", true, "Widgets & Chat"),
    TEXT_SOURCE("Text (FreeType/GPU)", true, "Media & Graphics"),
    TIMER_SOURCE("Timer / Stopwatch", true, "Widgets & Chat"),
    SCOREBOARD_SOURCE("Scoreboard Counter", true, "Widgets & Chat"),
    DRAWING_LAYER("Screen Drawing / Ink Layer", true, "Widgets & Chat"),
    COLOR_SOURCE("Color Source", true, "Media & Graphics"),
    SOUNDBOARD_SOURCE("Soundboard / SFX Pads", false, "Audio & SFX"),
    MIC_AUDIO("Mic/Aux", false, "Audio & SFX"),
    SYSTEM_AUDIO("System / Game Audio", false, "Audio & SFX")
}

enum class PreviewTool(val label: String) {
    SELECT_MOVE("Select"),
    CROP("Crop"),
    FIT_TO_SCREEN("Fit"),
    LOCK("Lock")
}

enum class PresetId(
    val title: String,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val fps: Int,
    val videoBitrateKbps: Int,
    val audioBitrateKbps: Int,
    val note: String,
    val requires1080p60Gate: Boolean = false
) {
    LOW_OVERHEAD(
        title = "Low-Overhead",
        canvasWidth = 1280,
        canvasHeight = 720,
        outputWidth = 1280,
        outputHeight = 720,
        fps = 30,
        videoBitrateKbps = 3500,
        audioBitrateKbps = 128,
        note = "Safe fallback for hot device or weak network"
    ),
    BALANCED(
        title = "Balanced (Default)",
        canvasWidth = 1280,
        canvasHeight = 720,
        outputWidth = 1280,
        outputHeight = 720,
        fps = 60,
        videoBitrateKbps = 5000,
        audioBitrateKbps = 128,
        note = "Default everyday gaming stream (Geometry Dash / osu!)"
    ),
    QUALITY_1080P30(
        title = "Quality 1080p30",
        canvasWidth = 1920,
        canvasHeight = 1080,
        outputWidth = 1920,
        outputHeight = 1080,
        fps = 30,
        videoBitrateKbps = 6000,
        audioBitrateKbps = 160,
        note = "Higher detail, even 60/90 Hz cadence"
    ),
    QUALITY_1080P60(
        title = "1080p60 (Probe Gated)",
        canvasWidth = 1920,
        canvasHeight = 1080,
        outputWidth = 1920,
        outputHeight = 1080,
        fps = 60,
        videoBitrateKbps = 8000,
        audioBitrateKbps = 160,
        note = "Unlocked only when MediaCodec probe confirms 1080p60",
        requires1080p60Gate = true
    ),
    RECORD_FOCUSED(
        title = "Record-focused",
        canvasWidth = 1920,
        canvasHeight = 1080,
        outputWidth = 1920,
        outputHeight = 1080,
        fps = 30,
        videoBitrateKbps = 14000,
        audioBitrateKbps = 160,
        note = "High-bitrate local MP4 recording"
    )
}

enum class RtmpServicePreset(val label: String, val defaultUrl: String) {
    CUSTOM("Custom RTMP(S)", "rtmp://192.168.1.100:1935/live"),
    TWITCH("Twitch", "rtmps://ingest.global-contribute.live-video.net:443/app"),
    YOUTUBE("YouTube Live", "rtmps://a.rtmps.youtube.com:443/live2")
}

// Phase 3: Adaptive Quality Ladder (§12.3 M3.1)
enum class AdaptiveQualityLevel(val shortTag: String, val title: String, val bitrateScale: Float) {
    L0_NORMAL("L0", "Normal (Full Preset)", 1.0f),
    L1_MILD_THROTTLE("L1", "Mild Load (-20% Bitrate, Pause HUD)", 0.80f),
    L2_SUSTAINED_LOAD("L2", "Sustained Load (-40% Bitrate, Suspend Heavy FX)", 0.60f),
    L3_SEVERE_THERMAL("L3", "Severe Thermal (Low-Overhead 720p30)", 0.55f),
    L4_PROLONGED_SEVERE("L4", "Prolonged Severe (Camera Off, Screen Only)", 0.50f),
    L5_CRITICAL_STOP("L5", "Critical Thermal (Finalize & Stop Stream)", 0.0f)
}

data class AdaptiveQualityState(
    val level: AdaptiveQualityLevel = AdaptiveQualityLevel.L0_NORMAL,
    val lockedByUser: Boolean = false,
    val lastChangeReason: String = "Nominal headroom",
    val activeActions: List<String> = emptyList(),
    val decisionHistory: List<String> = emptyList()
)

// Phase 3: Dual RTMP(S) Destination sharing the single MediaCodec encode (M3.4)
data class DualDestinationState(
    val enabled: Boolean = false,
    val preset: RtmpServicePreset = RtmpServicePreset.TWITCH,
    val serverUrl: String = RtmpServicePreset.TWITCH.defaultUrl,
    val hasKeySaved: Boolean = false,
    val maskedKeyPreview: String = "Not set",
    val phase: SessionPhase = SessionPhase.IDLE,
    val errorReason: String? = null,
    val bufferedBytes: Long = 0L,
    val droppedFrames: Long = 0L
)

// Phase 3: Gaming Scene Presets (M3.2)
enum class GamingScenePreset(val label: String, val description: String) {
    GAME_ONLY("Game Only", "Full 16:9 screen capture with audio mixer"),
    GAME_FACECAM("Game + Facecam", "16:9 screen capture + corner Camera2 PiP"),
    GAME_FACECAM_CHAT("Game + Facecam + Chat Box", "Screen + Facecam + styled Chat/Alert container placeholder"),
    INTERMISSION_PACK("Starting Soon / BRB / Ending Pack", "Adds 3 editable intermission scenes with brand backdrop + titles")
}

// Phase 3: Samsung / One UI Awareness (M3.3)
data class SamsungOneUiStatus(
    val isSamsungDevice: Boolean = false,
    val deviceDisplayModel: String = "",
    val ramTierLabel: String = "Unknown RAM",
    val isBatteryOptimizationExempt: Boolean = false,
    val displayMaxRefreshHz: Float = 60f,
    val guidanceNotes: List<String> = emptyList()
)

// Phase 3: Auto-Tune Probe Result (M3.6)
data class AutoTuneResult(
    val completedAtIso: String,
    val recommendedPreset: PresetId,
    val hwAvcEncodersFound: Int,
    val supports1080p60: Boolean,
    val ramMb: Long,
    val thermalHeadroom: Float,
    val summary: String
)

// Phase 3: Benchmark Kit Record (§14.4 M3.7)
data class BenchmarkRecord(
    val id: String,
    val appName: String,
    val presetLabel: String,
    val gameName: String,
    val gameAvgFps: Float,
    val gameOnePercentLowFps: Float,
    val streamDroppedPct: Float,
    val peakThermalLabel: String,
    val processPssMb: Int,
    val notes: String,
    val recordedAtIso: String
)

@Immutable
data class VideoConfig(
    val presetId: PresetId = PresetId.BALANCED,
    val canvasWidth: Int = 1280,
    val canvasHeight: Int = 720,
    val outputWidth: Int = 1280,
    val outputHeight: Int = 720,
    val fps: Int = 60,
    val videoBitrateKbps: Int = 5000,
    val audioBitrateKbps: Int = 128,
    val keyframeIntervalSec: Int = 2,
    val downscaleFilter: DownscaleFilter = DownscaleFilter.BILINEAR,
    val screenFitMode: ScreenFitMode = ScreenFitMode.CROP_TO_FILL,
    val screenCropOffsetYFraction: Float = 0.5f,
    val recordingFormat: RecordingFormat = RecordingFormat.FMP4,
    val dynamicBitrateEnabled: Boolean = true,
    val lowLatencyNetworkMode: Boolean = true
)

@Immutable
data class SourceTransform(
    val xFraction: Float = 0f,
    val yFraction: Float = 0f,
    val widthFraction: Float = 1f,
    val heightFraction: Float = 1f,
    val rotationDegrees: Float = 0f,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 0f,
    val cropBottom: Float = 0f
)

@Immutable
data class SourceModel(
    val id: String,
    val name: String,
    val type: SourceType,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val erroredReason: String? = null,
    val useFrontCamera: Boolean = true,
    val facecamShape: FacecamShape = FacecamShape.RECTANGLE,
    val borderWidthPx: Float = 0f,
    val borderColorArgb: Long = 0xFFCC1B1BL,
    val mirrorHorizontal: Boolean = false,
    val mediaPathOrUri: String? = null,
    val mediaLoop: Boolean = true,
    val mediaMuted: Boolean = false,
    val mediaPlaying: Boolean = true,
    val textContent: String = "Mobile Labs Stream",
    val textColorArgb: Long = 0xFFFFFFFFL,
    val textBgColorArgb: Long = 0x9916161AL,
    val textSizeSp: Float = 44f,
    val colorArgb: Long = 0xFF720000L,
    val browserUrl: String = "https://streamlabs.com/widgets/alert-box/v3/demo",
    val browserFpsCap: Int = 20,
    val browserTransparentBg: Boolean = true,
    val browserWidthPx: Int = 800,
    val browserHeightPx: Int = 600,
    val timerTargetSec: Int = 300,
    val timerCountDown: Boolean = true,
    val timerRunning: Boolean = false,
    val timerStartedAtMs: Long = 0L,
    val timerElapsedBeforeMs: Long = 0L,
    val scoreHomeName: String = "P1",
    val scoreAwayName: String = "P2",
    val scoreHome: Int = 0,
    val scoreAway: Int = 0,
    val drawingBrushColorArgb: Long = 0xFFFF3B30L,
    val drawingBrushSizePx: Float = 10f,
    val chatOverlayOpacity: Float = 0.72f,
    val chatOverlayMaxMessages: Int = 7,
    val transform: SourceTransform = SourceTransform(),
    val videoFilters: VideoFilterConfig = VideoFilterConfig()
)

@Immutable
data class SceneModel(
    val id: String,
    val name: String,
    val sources: List<SourceModel>
)

@Immutable
data class SceneCollection(
    val id: String = "col_default",
    val name: String = "Tab A9+ Gaming",
    val activeSceneId: String,
    val scenes: List<SceneModel>,
    val transitionType: TransitionType = TransitionType.FADE,
    val transitionDurationMs: Int = 300,
    val transitionColorArgb: Long = 0xFF000000L
)

data class AppProfile(
    val id: String = "prof_default",
    val name: String = "Tab A9+ Balanced 720p60",
    val videoConfig: VideoConfig = VideoConfig(),
    val rtmpServicePreset: RtmpServicePreset = RtmpServicePreset.CUSTOM,
    val confirmStopActions: Boolean = true,
    val lockPreviewTouchWhileLive: Boolean = true,
    val swapPreviewProgramOnTake: Boolean = true
)

@Immutable
data class AudioChannelState(
    val id: String,
    val label: String,
    val volume: Float = 1.0f,
    val muted: Boolean = false,
    val locked: Boolean = false,
    val peakDb: Float = -60f,
    val peakHoldDb: Float = -60f,
    val syncOffsetMs: Int = 0,
    val isClipping: Boolean = false,
    val activeRouteLabel: String = "Default",
    val unavailableReason: String? = null,
    val filters: AudioFilterConfig = AudioFilterConfig(),
    val measuredDspMicros: Long = 0L
)

data class ThermalSummary(
    val statusCode: Int = PowerManager.THERMAL_STATUS_NONE,
    val statusLabel: String = "TEMP OK",
    val headroomForecast: Float = -1f,
    val batteryTempC: Float = 0f,
    val batteryPercent: Int = 100,
    val isCharging: Boolean = false,
    val shouldWarnBanner: Boolean = false
)

data class TelemetrySnapshot(
    val timestampMs: Long = System.currentTimeMillis(),
    val captureCadenceFps: Float = 0f,
    val compositorFps: Float = 0f,
    val encoderFps: Float = 0f,
    val compositorPassMicros: Long = 0L,
    val audioDspMicros: Long = 0L,
    val outputWidth: Int = 1280,
    val outputHeight: Int = 720,
    val configuredFps: Int = 60,
    val actualBitrateKbps: Int = 0,
    val keyframeIntervalSec: Int = 2,
    val renderSkippedFrames: Long = 0,
    val encoderDroppedFrames: Long = 0,
    val networkDroppedFrames: Long = 0,
    val captureGapCount: Long = 0,
    val encoderQueueDepth: Int = 0,
    val rtmpBufferedBytes: Long = 0,
    val reconnectCount: Int = 0,
    val processPssMb: Int = 0,
    val nativeHeapMb: Int = 0,
    val freeRamMb: Int = 0,
    val freeStorageMb: Long = 0,
    val thermal: ThermalSummary = ThermalSummary(),
    val encoderMode: EncoderFallbackLevel = EncoderFallbackLevel.OPTIMAL_HW_SURFACE,
    val isUiVisible: Boolean = true,
    val isOverlayShown: Boolean = false,
    val streamDurationSec: Long = 0,
    val recordDurationSec: Long = 0,
    val recordingBytesWritten: Long = 0
)

data class IncompleteRecording(
    val fileName: String,
    val filePath: String,
    val startedAtMs: Long,
    val bytesOnDisk: Long,
    val verifiedPlayable: Boolean = false,
    val statusNote: String = "INCOMPLETE (interrupted session)"
)

data class PublishedRecording(
    val fileName: String,
    val mediaStoreUri: String,
    val durationSec: Long,
    val bytesSize: Long,
    val createdAtMs: Long
)

data class CodecProbeEntry(
    val name: String,
    val mimeType: String,
    val isHardwareAccelerated: Boolean,
    val supportsSurfaceInput: Boolean,
    val supports720p30: Boolean,
    val supports720p60: Boolean,
    val supports1080p30: Boolean,
    val supports1080p60: Boolean,
    val bitrateRangeKbps: String,
    val supportsCbr: Boolean,
    val supportsVbr: Boolean,
    val supportsCq: Boolean,
    val supportsLowLatency: Boolean,
    val maxSupportedInstances: Int
)

data class DeviceAndCodecReport(
    val generatedAtIso: String,
    val manufacturer: String,
    val model: String,
    val device: String,
    val androidVersion: String,
    val sdkInt: Int,
    val securityPatch: String,
    val buildId: String,
    val abis: String,
    val totalRamMb: Long,
    val availableStorageMb: Long,
    val displayWidthPx: Int,
    val displayHeightPx: Int,
    val displayRefreshHz: Float,
    val supportedRefreshRates: List<Float>,
    val thermalApiSupported: Boolean,
    val thermalHeadroomSupported: Boolean,
    val performanceHintSupported: Boolean,
    val maxConcurrentAvcHardwareInstances: Int,
    val encoders: List<CodecProbeEntry>,
    val rawJson: String
)

@Immutable
data class MobileLabsUiState(
    val engineBackend: EngineBackendStatus = EngineBackendStatus.ANDROID_HARDWARE_PIPELINE,
    val libObsVersion: String = "32.2.1 (commit 57bfcf10a)",
    val appVersionLabel: String = "1.0.0-rc1",
    val sessionPhase: SessionPhase = SessionPhase.IDLE,
    val recordingPhase: RecordingPhase = RecordingPhase.IDLE,
    val statusMessage: String = "Ready — Tap Arm Capture to authorize MediaProjection",
    val errorReason: String? = null,
    val reconnectCountdownSec: Int = 0,
    val captureStoppedBySystem: Boolean = false,
    val speakerMicEchoDetected: Boolean = false,
    val videoConfig: VideoConfig = VideoConfig(),
    val sceneCollection: SceneCollection = defaultSceneCollection(),
    val savedCollectionNames: List<String> = listOf("Tab A9+ Gaming"),
    val profiles: List<AppProfile> = listOf(AppProfile()),
    val activeProfileId: String = "prof_default",
    val studioModeEnabled: Boolean = false,
    val previewSceneId: String = "scene_game",
    val programSceneId: String = "scene_game",
    val swapPreviewProgramOnTake: Boolean = true,
    val lockPreviewTouchWhileLive: Boolean = true,
    val unlockLiveTouchOverride: Boolean = false,
    val selectedSourceId: String? = "src_screen",
    val multiSelectedSourceIds: Set<String> = emptySet(),
    val activeTool: PreviewTool = PreviewTool.SELECT_MOVE,
    val expandedDock: DockPanelId? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val audioChannels: List<AudioChannelState> = defaultAudioChannels(),
    val masterMuted: Boolean = false,
    val rtmpServicePreset: RtmpServicePreset = RtmpServicePreset.CUSTOM,
    val rtmpServerUrl: String = RtmpServicePreset.CUSTOM.defaultUrl,
    val hasStreamKeySaved: Boolean = false,
    val maskedStreamKeyPreview: String = "Not set",
    val dualDestination: DualDestinationState = DualDestinationState(),
    val adaptiveQuality: AdaptiveQualityState = AdaptiveQualityState(),
    val samsungStatus: SamsungOneUiStatus = SamsungOneUiStatus(),
    val autoTuneResult: AutoTuneResult? = null,
    val onboardingCompleted: Boolean = true,
    val lastCrashSummary: String? = null,
    val benchmarkRecords: List<BenchmarkRecord> = emptyList(),
    val vulkanDecisionLabel: String = "V3 Closed -> GLES3 Production Default",
    val customRecordingFolderUri: String? = null,
    val floatingOverlayEnabled: Boolean = false,
    val hasOverlayPermission: Boolean = false,
    val hasMicPermission: Boolean = false,
    val hasCameraPermission: Boolean = false,
    val hasNotificationPermission: Boolean = false,
    val isPreviewActive: Boolean = false,
    val hudExpanded: Boolean = false,
    val telemetry: TelemetrySnapshot = TelemetrySnapshot(),
    val deviceReport: DeviceAndCodecReport? = null,
    val incompleteRecordings: List<IncompleteRecording> = emptyList(),
    val recentRecordings: List<PublishedRecording> = emptyList(),
    val eventLog: List<String> = emptyList(),
    val lastDiagnosticsExportPath: String? = null,
    val youtubeConnection: PlatformConnectionState = PlatformConnectionState(StreamingPlatform.YOUTUBE),
    val twitchConnection: PlatformConnectionState = PlatformConnectionState(StreamingPlatform.TWITCH),
    val chatMessages: List<PlatformChatMessage> = emptyList(),
    val chatDrawerOpen: Boolean = false,
    val twitchCategories: List<TwitchCategoryItem> = emptyList(),
    val replayBuffer: ReplayBufferState = ReplayBufferState(),
    val soundboardPads: List<SoundboardPad> = defaultSoundboardPads(),
    val drawingModeActive: Boolean = false,
    val drawingColorArgb: Long = 0xFFFF3B30L,
    val drawingBrushWidthFraction: Float = 0.008f,
    val drawingStrokes: List<DrawingStroke> = emptyList(),
    val pushToTalkHeld: Boolean = false,
    val lastScreenshotPath: String? = null,
    val customYoutubeClientIdSaved: Boolean = false,
    val customTwitchClientIdSaved: Boolean = false
)

fun defaultSoundboardPads(): List<SoundboardPad> = listOf(
    SoundboardPad(id = "sfx_horn", label = "Airhorn", builtInTone = "HORN"),
    SoundboardPad(id = "sfx_chime", label = "Sub Chime", builtInTone = "CHIME"),
    SoundboardPad(id = "sfx_rimshot", label = "Rimshot", builtInTone = "RIMSHOT"),
    SoundboardPad(id = "sfx_applause", label = "Hype Boom", builtInTone = "BOOM")
)

fun defaultSceneCollection(): SceneCollection {
    val gameScene = SceneModel(
        id = "scene_game",
        name = "Scene",
        sources = listOf(
            SourceModel(
                id = "src_screen",
                name = "Tablet Screen (16:9)",
                type = SourceType.SCREEN_CAPTURE,
                visible = true,
                locked = false
            )
        )
    )
    val desktopScene = SceneModel(
        id = "scene_desktop",
        name = "Game + Facecam",
        sources = listOf(
            SourceModel(
                id = "src_screen_gf",
                name = "Tablet Screen (16:9)",
                type = SourceType.SCREEN_CAPTURE,
                visible = true,
                locked = false
            ),
            SourceModel(
                id = "src_camera_gf",
                name = "Front Facecam",
                type = SourceType.CAMERA_2,
                visible = true,
                locked = false,
                useFrontCamera = true,
                transform = SourceTransform(
                    xFraction = 0.72f,
                    yFraction = 0.68f,
                    widthFraction = 0.24f,
                    heightFraction = 0.26f
                )
            )
        )
    )
    val brbScene = SceneModel(
        id = "scene_intermission",
        name = "Intermission",
        sources = listOf(
            SourceModel(
                id = "src_brb_bg",
                name = "Aperture Crimson Backdrop",
                type = SourceType.COLOR_SOURCE,
                visible = true,
                colorArgb = 0xFF720000L
            ),
            SourceModel(
                id = "src_brb_text",
                name = "Intermission Title",
                type = SourceType.TEXT_SOURCE,
                visible = true,
                textContent = "BE RIGHT BACK · MOBILE LABS",
                textSizeSp = 48f,
                transform = SourceTransform(0.18f, 0.40f, 0.64f, 0.18f)
            )
        )
    )
    return SceneCollection(
        id = "col_default",
        name = "Tab A9+ Gaming",
        activeSceneId = gameScene.id,
        scenes = listOf(gameScene, desktopScene, brbScene)
    )
}

fun defaultAudioChannels(): List<AudioChannelState> = listOf(
    AudioChannelState(
        id = "audio_game",
        label = "Game Audio",
        volume = 1.0f,
        activeRouteLabel = "AudioPlaybackCapture"
    ),
    AudioChannelState(
        id = "audio_mic",
        label = "Mic/Aux",
        volume = 1.0f,
        activeRouteLabel = "Built-in Mic"
    )
)
