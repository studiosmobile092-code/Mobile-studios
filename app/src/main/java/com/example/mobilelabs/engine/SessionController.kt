package com.example.mobilelabs.engine

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.view.Surface
import androidx.core.content.ContextCompat
import com.example.mobilelabs.diagnostics.DiagnosticsExporter
import com.example.mobilelabs.diagnostics.LocalCrashHandler
import com.example.mobilelabs.media.AudioCaptureMixer
import com.example.mobilelabs.media.Camera2SourceController
import com.example.mobilelabs.media.CameraProSettings
import com.example.mobilelabs.media.GlZeroCopyCompositor
import com.example.mobilelabs.media.HardwareMediaPipeline
import com.example.mobilelabs.media.OffscreenBrowserSourceController
import com.example.mobilelabs.model.AlertEventType
import com.example.mobilelabs.model.AppProfile
import com.example.mobilelabs.model.AudioChannelState
import com.example.mobilelabs.model.AudioFilterConfig
import com.example.mobilelabs.model.AutoTuneResult
import com.example.mobilelabs.model.BenchmarkRecord
import com.example.mobilelabs.model.DockPanelId
import com.example.mobilelabs.model.DrawingStroke
import com.example.mobilelabs.model.DualDestinationState
import com.example.mobilelabs.model.GamingScenePreset
import com.example.mobilelabs.model.MobileLabsUiState
import com.example.mobilelabs.model.PresetId
import com.example.mobilelabs.model.PreviewTool
import com.example.mobilelabs.model.RecordingPhase
import com.example.mobilelabs.model.ReplayBufferState
import com.example.mobilelabs.model.RtmpServicePreset
import com.example.mobilelabs.model.SceneCollection
import com.example.mobilelabs.model.SceneModel
import com.example.mobilelabs.model.ScreenFitMode
import com.example.mobilelabs.model.SessionPhase
import com.example.mobilelabs.model.SoundboardPad
import com.example.mobilelabs.model.SourceModel
import com.example.mobilelabs.model.SourceTransform
import com.example.mobilelabs.model.SourceType
import com.example.mobilelabs.model.StreamAlertEvent
import com.example.mobilelabs.model.StreamingPlatform
import com.example.mobilelabs.model.ThermalSummary
import com.example.mobilelabs.model.TransitionType
import com.example.mobilelabs.model.VideoConfig
import com.example.mobilelabs.model.VideoFilterConfig
import com.example.mobilelabs.persistence.ObsJsonImporterExporter
import com.example.mobilelabs.persistence.SceneCollectionRepository
import com.example.mobilelabs.probes.DeviceAndCodecProbe
import com.example.mobilelabs.probes.SamsungOneUiAdvisor
import com.example.mobilelabs.security.KeystoreSecretStore
import com.example.mobilelabs.service.CaptureForegroundService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLSocketFactory

/**
 * Single owner of state for Mobile Labs (Iron Rule R10).
 * Coordinates libobs JNI bridge, multi-layer GLES3 compositor, GPU video filters,
 * zero-allocation Audio DSP chain, Studio Mode + 5 transitions, touch canvas editing,
 * 1-click YouTube & Twitch OAuth/multi-stream/chat, Instant Replay Buffer, Pause/Resume recording,
 * Soundboard/SFX, Drawing/Ink, Timer/Scoreboard widgets, profiles/collections, and thermal telemetry.
 */
class SessionController(private val context: Context) {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        logEvent("Background error caught: ${throwable.message ?: throwable.javaClass.simpleName}")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + exceptionHandler)

    private val secretStore = KeystoreSecretStore(context)
    private val repository = SceneCollectionRepository(context)
    private val probeTool = DeviceAndCodecProbe(context)
    private val obsBridge = ObsEngineBridge(context)
    private val diagnosticsExporter = DiagnosticsExporter(context)
    private val compositor = GlZeroCopyCompositor(context)
    private val browserController = OffscreenBrowserSourceController(context)
    private val adaptiveEngine = AdaptiveQualityEngine()

    private val _uiState = MutableStateFlow(MobileLabsUiState())
    val uiState: StateFlow<MobileLabsUiState> = _uiState.asStateFlow()

    private val platformCoordinator = PlatformConnectionCoordinator(
        context = context,
        secretStore = secretStore,
        scope = scope,
        uiState = _uiState,
        onChatUpdated = { pushDynamicOverlaysToCompositor() },
        logEvent = ::logEvent
    )

    private var pipeline: HardwareMediaPipeline? = null
    private var audioMixer: AudioCaptureMixer? = null
    private var cameraController: Camera2SourceController? = null

    private var telemetryJob: Job? = null
    private var reconnectJob: Job? = null
    private var alertClearJob: Job? = null
    private var streamStartedAtMs = 0L
    private var recordStartedAtMs = 0L
    private var recordTotalPausedMs = 0L
    private var recordPausedAtMs = 0L
    private var chatVersionCounter = 0L
    private var drawingVersionCounter = 0L
    private var reconnectCount = 0
    private var lastNetworkDropSeen = 0L
    private var dynamicTargetBitrateKbps = 0
    private var overlayLandscapePos = Pair(24, 180)
    private var overlayPortraitPos = Pair(24, 240)
    private var cameraSurfaceRef: Surface? = null
    private var cachedBatteryTempC = 0f
    private var cachedBatteryPct = 100
    private var cachedCharging = false

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private val undoStack = ArrayDeque<SceneCollection>(25)
    private val redoStack = ArrayDeque<SceneCollection>(25)

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        updateThermalStatus(status)
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                cachedBatteryTempC = tempTenths / 10f
                cachedBatteryPct = (level * 100) / scale
                cachedCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            }
        }
    }

    fun initialize() {
        val loadedScenes = repository.loadSceneCollection()
        val loadedSettings = repository.loadSettings()
        overlayLandscapePos = loadedSettings.overlayLandscapeX to loadedSettings.overlayLandscapeY
        overlayPortraitPos = loadedSettings.overlayPortraitX to loadedSettings.overlayPortraitY

        val incomplete = repository.detectIncompleteRecordings()
        val lastCrash = LocalCrashHandler(context).consumeLastCrashSummary()
        val samsungStatus = SamsungOneUiAdvisor.inspect(context)
        obsBridge.startup(loadedSettings.videoConfig)
        val initialAdaptive = adaptiveEngine.setLockedByUser(loadedSettings.adaptiveQualityLocked)

        _uiState.update { cur ->
            val hydratedChannels = cur.audioChannels.map { ch ->
                val f = loadedSettings.audioFilters[ch.id] ?: ch.filters
                val sync = loadedSettings.audioSyncOffsetsMs[ch.id] ?: ch.syncOffsetMs
                ch.copy(filters = f, syncOffsetMs = sync)
            }
            cur.copy(
                engineBackend = obsBridge.backendStatus,
                libObsVersion = obsBridge.getVersionString(),
                videoConfig = loadedSettings.videoConfig,
                sceneCollection = loadedScenes,
                savedCollectionNames = loadedSettings.savedCollections,
                profiles = loadedSettings.profiles,
                activeProfileId = loadedSettings.activeProfileId,
                studioModeEnabled = loadedSettings.studioModeEnabled,
                previewSceneId = loadedScenes.activeSceneId,
                programSceneId = loadedScenes.activeSceneId,
                swapPreviewProgramOnTake = loadedSettings.swapPreviewProgramOnTake,
                lockPreviewTouchWhileLive = loadedSettings.lockPreviewTouchWhileLive,
                customRecordingFolderUri = loadedSettings.customRecordingFolderUri,
                audioChannels = hydratedChannels,
                floatingOverlayEnabled = loadedSettings.floatingOverlayEnabled,
                incompleteRecordings = incomplete,
                adaptiveQuality = initialAdaptive,
                samsungStatus = samsungStatus,
                onboardingCompleted = loadedSettings.onboardingCompleted,
                lastCrashSummary = lastCrash,
                benchmarkRecords = loadedSettings.benchmarkRecords,
                dualDestination = cur.dualDestination.copy(enabled = loadedSettings.dualDestinationEnabled),
                replayBuffer = ReplayBufferState(
                    enabled = loadedSettings.replayBufferEnabled,
                    durationSec = loadedSettings.replayDurationSec
                ),
                soundboardPads = loadedSettings.soundboardPads,
                telemetry = cur.telemetry.copy(
                    outputWidth = loadedSettings.videoConfig.outputWidth,
                    outputHeight = loadedSettings.videoConfig.outputHeight,
                    configuredFps = loadedSettings.videoConfig.fps
                )
            )
        }

        compositor.setBrowserController(browserController)
        compositor.setMediaAudioCallback { pcm, count -> audioMixer?.pushMediaAudioPcm(pcm, count) }
        syncCompositorScenes()
        refreshPermissionStates()
        registerSystemMonitors()
        platformCoordinator.hydrateSavedConnections()

        scope.launch(Dispatchers.IO) {
            val savedUrl = runCatching { secretStore.loadServerUrl() }.getOrDefault("")
            val savedPreset = runCatching { secretStore.loadPresetName() }.getOrNull()?.let { name ->
                runCatching { RtmpServicePreset.valueOf(name) }.getOrNull()
            } ?: RtmpServicePreset.CUSTOM
            val hasKey = runCatching { secretStore.hasStreamKey() }.getOrDefault(false)
            val masked = runCatching { secretStore.getMaskedPreview() }.getOrDefault("Not set")

            val secUrl = runCatching { secretStore.loadSecondaryServerUrl() }.getOrDefault("")
            val secPreset = runCatching { secretStore.loadSecondaryPresetName() }.getOrNull()?.let { name ->
                runCatching { RtmpServicePreset.valueOf(name) }.getOrNull()
            } ?: RtmpServicePreset.TWITCH
            val secHasKey = runCatching { secretStore.hasSecondaryStreamKey() }.getOrDefault(false)
            val secMasked = runCatching { secretStore.getSecondaryMaskedPreview() }.getOrDefault("Not set")

            val report = runCatching { probeTool.runProbe(testConcurrency = false) }.getOrNull()

            _uiState.update { cur ->
                cur.copy(
                    rtmpServicePreset = savedPreset,
                    rtmpServerUrl = savedUrl.ifBlank { savedPreset.defaultUrl },
                    hasStreamKeySaved = hasKey,
                    maskedStreamKeyPreview = masked,
                    dualDestination = cur.dualDestination.copy(
                        preset = secPreset,
                        serverUrl = secUrl.ifBlank { secPreset.defaultUrl },
                        hasKeySaved = secHasKey,
                        maskedKeyPreview = secMasked
                    ),
                    deviceReport = report
                )
            }
        }

        startTelemetryLoop()
        if (lastCrash != null) {
            logEvent("Previous crash detected: $lastCrash")
        }
        logEvent("SessionController initialized (${obsBridge.backendStatus.label})")
    }

    fun refreshPermissionStates() {
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val hasCam = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
        val hasOverlay = Settings.canDrawOverlays(context)

        _uiState.update { cur ->
            val disableOverlayIfRevoked = cur.floatingOverlayEnabled && !hasOverlay
            cur.copy(
                hasMicPermission = hasMic,
                hasCameraPermission = hasCam,
                hasNotificationPermission = hasNotif,
                hasOverlayPermission = hasOverlay,
                floatingOverlayEnabled = if (disableOverlayIfRevoked) false else cur.floatingOverlayEnabled
            )
        }
    }

    fun setUiVisible(visible: Boolean) {
        compositor.setPreviewEnabled(visible)
        _uiState.update { cur ->
            cur.copy(
                isPreviewActive = visible && (cur.sessionPhase == SessionPhase.ARMED ||
                    cur.sessionPhase == SessionPhase.LIVE ||
                    cur.sessionPhase == SessionPhase.CONNECTING ||
                    cur.sessionPhase == SessionPhase.RECONNECTING),
                telemetry = cur.telemetry.copy(isUiVisible = visible)
            )
        }
    }

    fun attachPreviewSurface(surface: Surface?, width: Int, height: Int) {
        compositor.attachPreviewSurface(surface, width, height)
    }

    fun beginArming() {
        if (_uiState.value.sessionPhase != SessionPhase.IDLE && _uiState.value.sessionPhase != SessionPhase.ERROR) return
        _uiState.update {
            it.copy(
                sessionPhase = SessionPhase.ARMING,
                captureStoppedBySystem = false,
                errorReason = null,
                statusMessage = "Requesting full-screen capture consent..."
            )
        }
    }

    fun onArmFailed(reason: String) {
        _uiState.update {
            it.copy(sessionPhase = SessionPhase.IDLE, errorReason = reason, statusMessage = reason)
        }
        logEvent("ARM failed: $reason")
    }

    fun attachArmedProjection(
        mediaProjection: MediaProjection,
        displayWidthPx: Int,
        displayHeightPx: Int,
        createVirtualDisplay: (Surface, Int, Int) -> Unit
    ) {
        val cfg = _uiState.value.videoConfig
        obsBridge.reconfigureVideo(cfg)

        val pipe = HardwareMediaPipeline(
            context = context,
            repository = repository,
            onEncoderSurfaceReady = { encSurface -> compositor.attachEncoderSurface(encSurface) },
            onStreamConnected = {
                reconnectCount = 0
                streamStartedAtMs = System.currentTimeMillis()
                _uiState.update {
                    it.copy(
                        sessionPhase = SessionPhase.LIVE,
                        reconnectCountdownSec = 0,
                        errorReason = null,
                        statusMessage = "LIVE · Streaming to ${KeystoreSecretStore.redactUrlAndKeys(it.rtmpServerUrl)}"
                    )
                }
                logEvent("RTMP output started (LIVE)")
            },
            onStreamError = { reason -> handleStreamDisconnect(reason) },
            onRecordingFinalized = { published ->
                recordStartedAtMs = 0L
                recordTotalPausedMs = 0L
                recordPausedAtMs = 0L
                _uiState.update { cur ->
                    cur.copy(
                        recordingPhase = RecordingPhase.IDLE,
                        recentRecordings = (listOf(published) + cur.recentRecordings).take(20),
                        statusMessage = "Saved ${published.fileName} to Movies/Mobile Labs"
                    )
                }
                logEvent("Recording finalized: ${published.fileName} (${published.bytesSize / 1024} KB)")
            },
            onRecordingError = { reason ->
                recordStartedAtMs = 0L
                recordTotalPausedMs = 0L
                recordPausedAtMs = 0L
                _uiState.update { it.copy(recordingPhase = RecordingPhase.ERROR, statusMessage = reason) }
                logEvent("Recording error: $reason")
            }
        )
        pipeline = pipe
        pipe.customRecordingFolderUri = _uiState.value.customRecordingFolderUri
        val st = _uiState.value
        pipe.configureReplayBuffer(st.replayBuffer.enabled, st.replayBuffer.durationSec)
        pipe.startEncoders(cfg)
        dynamicTargetBitrateKbps = cfg.videoBitrateKbps
        lastNetworkDropSeen = 0L

        val mixer = AudioCaptureMixer(
            context = context,
            onEncodedPcmChunk = { pcm, count, ptsUs ->
                pipe.pushAudioPcm(pcm, count, ptsUs)
            },
            onIsolatedAudioTracksPcm = { micPcm, gamePcm, count, ptsUs ->
                pipe.pushIsolatedAudioPcm(micPcm, gamePcm, count, ptsUs)
            }
        ).apply {
            val gCh = st.audioChannels.firstOrNull { it.id == "audio_game" }
            val mCh = st.audioChannels.firstOrNull { it.id == "audio_mic" }
            if (gCh != null) {
                gameVolume = gCh.volume
                gameMuted = gCh.muted
                gameSyncOffsetMs = gCh.syncOffsetMs
                gameFilters = gCh.filters
            }
            if (mCh != null) {
                micVolume = mCh.volume
                micMuted = mCh.muted
                micSyncOffsetMs = mCh.syncOffsetMs
                micFilters = mCh.filters
            }
            masterMuted = st.masterMuted
            pushToTalkHeld = st.pushToTalkHeld
        }
        audioMixer = mixer
        mixer.start(mediaProjection, st.hasMicPermission)

        cameraController = Camera2SourceController(context) { camErr ->
            logEvent("Camera2 warning: $camErr")
            markSourceErrored(SourceType.CAMERA_2, null, camErr)
        }

        browserController.start()
        compositor.setBrowserController(browserController)
        compositor.setMediaAudioCallback { pcm, count -> mixer.pushMediaAudioPcm(pcm, count) }
        pushDynamicOverlaysToCompositor()

        compositor.start(
            videoConfig = cfg,
            displayWidthPx = displayWidthPx,
            displayHeightPx = displayHeightPx,
            onMediaError = { sourceId, reason ->
                scope.launch { markSourceErrored(null, sourceId, reason) }
            }
        ) { screenSurface, camSurface ->
            cameraSurfaceRef = camSurface
            createVirtualDisplay(screenSurface, cfg.canvasWidth, cfg.canvasHeight)
            syncCompositorScenes()
            syncActiveCameraSource()
        }

        _uiState.update {
            it.copy(
                sessionPhase = SessionPhase.ARMED,
                isPreviewActive = it.telemetry.isUiVisible,
                captureStoppedBySystem = false,
                statusMessage = "Capture ARMED · Ready to Go Live or Record"
            )
        }
        logEvent("Capture armed (${cfg.canvasWidth}x${cfg.canvasHeight} -> ${cfg.outputWidth}x${cfg.outputHeight} @${cfg.fps})")
    }

    fun requestGoLive() {
        val st = _uiState.value
        if (st.sessionPhase != SessionPhase.ARMED && st.sessionPhase != SessionPhase.RECONNECTING) return
        if (st.videoConfig.skipGoLivePreFlight) {
            startStreaming()
        } else {
            _uiState.update { it.copy(showPreFlightSheet = true) }
        }
    }

    fun dismissPreFlightSheet() {
        _uiState.update { it.copy(showPreFlightSheet = false) }
    }

    fun confirmGoLiveFromPreFlight() {
        _uiState.update { it.copy(showPreFlightSheet = false) }
        startStreaming()
    }

    fun setSkipGoLivePreFlight(skip: Boolean) {
        updateVideoConfig(_uiState.value.videoConfig.copy(skipGoLivePreFlight = skip))
    }

    fun setMultiTrackAudioEnabled(enabled: Boolean) {
        val cur = _uiState.value
        val sessionActive = cur.sessionPhase != SessionPhase.IDLE && cur.sessionPhase != SessionPhase.ERROR
        if (sessionActive) {
            _uiState.update {
                it.copy(statusMessage = "Stop capture before toggling multi-track audio")
            }
            return
        }
        updateVideoConfig(cur.videoConfig.copy(multiTrackAudioEnabled = enabled))
    }

    fun startStreaming() {
        val st = _uiState.value
        if (st.sessionPhase != SessionPhase.ARMED && st.sessionPhase != SessionPhase.RECONNECTING) return

        scope.launch(Dispatchers.IO) {
            val url = secretStore.loadServerUrl().ifBlank { st.rtmpServerUrl }
            val key = secretStore.loadStreamKey()
            val hasPrimaryRtmp = url.isNotBlank() && key.isNotBlank()
            val hasPlatformMulti = (st.youtubeConnection.connected && st.youtubeConnection.enabledForMultiStream) ||
                (st.twitchConnection.connected && st.twitchConnection.enabledForMultiStream)
            val secUrl = if (st.dualDestination.enabled) {
                secretStore.loadSecondaryServerUrl().ifBlank { st.dualDestination.serverUrl }
            } else ""
            val secKey = if (st.dualDestination.enabled) secretStore.loadSecondaryStreamKey() else ""
            val hasDualRtmp = st.dualDestination.enabled && secUrl.isNotBlank() && secKey.isNotBlank()

            if (!hasPrimaryRtmp && !hasPlatformMulti && !hasDualRtmp) {
                _uiState.update {
                    it.copy(
                        errorReason = "Connect YouTube/Twitch or enter RTMP URL + Key first",
                        statusMessage = "No active stream destination configured"
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    sessionPhase = SessionPhase.CONNECTING,
                    errorReason = null,
                    statusMessage = "Connecting to live stream destinations..."
                )
            }

            if (hasPrimaryRtmp) {
                pipeline?.startStreaming(url, key, st.videoConfig)
            }

            // 1-Click Connected Platforms (YouTube Live + Twitch sharing single HW encode)
            platformCoordinator.startEnabledPlatformStreams { slotId, ingestUrl, streamKey, onSlotConnected, onSlotError ->
                pipeline?.startNamedStream(
                    slotId = slotId,
                    rtmpBaseUrl = ingestUrl,
                    streamKey = streamKey,
                    videoConfig = st.videoConfig,
                    onConnected = {
                        onSlotConnected()
                        markSessionLiveFromAnyDestination(
                            if (slotId == "youtube") "LIVE on YouTube" else "LIVE on Twitch"
                        )
                    },
                    onError = { err ->
                        onSlotError(err)
                        val cur = _uiState.value
                        val anyOtherAlive = (cur.hasStreamKeySaved && cur.rtmpServerUrl.isNotBlank()) ||
                            cur.dualDestination.phase == SessionPhase.LIVE ||
                            cur.youtubeConnection.outputPhase == SessionPhase.LIVE ||
                            cur.youtubeConnection.outputPhase == SessionPhase.CONNECTING ||
                            cur.twitchConnection.outputPhase == SessionPhase.LIVE ||
                            cur.twitchConnection.outputPhase == SessionPhase.CONNECTING
                        if (!anyOtherAlive && cur.sessionPhase != SessionPhase.ARMED) {
                            handleStreamDisconnect(err)
                        }
                    }
                )
            }

            // Dual Custom Destination (shares single HW H.264/AAC encode; failures isolated)
            if (st.dualDestination.enabled) {
                if (hasDualRtmp) {
                    _uiState.update { cur ->
                        cur.copy(
                            dualDestination = cur.dualDestination.copy(
                                phase = SessionPhase.CONNECTING,
                                errorReason = null
                            )
                        )
                    }
                    pipeline?.startSecondaryStreaming(
                        rtmpBaseUrl = secUrl,
                        streamKey = secKey,
                        videoConfig = st.videoConfig,
                        onConnected = {
                            _uiState.update { cur ->
                                cur.copy(
                                    dualDestination = cur.dualDestination.copy(
                                        phase = SessionPhase.LIVE,
                                        errorReason = null
                                    )
                                )
                            }
                            markSessionLiveFromAnyDestination("LIVE on Dual RTMP")
                            logEvent("Secondary RTMP destination LIVE")
                        },
                        onError = { err ->
                            _uiState.update { cur ->
                                cur.copy(
                                    dualDestination = cur.dualDestination.copy(
                                        phase = SessionPhase.ERROR,
                                        errorReason = KeystoreSecretStore.redactUrlAndKeys(err, secKey)
                                    )
                                )
                            }
                            logEvent("Secondary RTMP warning (primary unaffected): ${KeystoreSecretStore.redactUrlAndKeys(err, secKey)}")
                        }
                    )
                } else {
                    _uiState.update { cur ->
                        cur.copy(
                            dualDestination = cur.dualDestination.copy(
                                phase = SessionPhase.ERROR,
                                errorReason = "Secondary URL/Key missing"
                            )
                        )
                    }
                }
            }
        }
    }

    private fun markSessionLiveFromAnyDestination(summaryLabel: String) {
        reconnectCount = 0
        if (streamStartedAtMs == 0L) {
            streamStartedAtMs = System.currentTimeMillis()
        }
        _uiState.update { cur ->
            val liveTags = buildList {
                if (cur.youtubeConnection.outputPhase == SessionPhase.LIVE) add("YouTube")
                if (cur.twitchConnection.outputPhase == SessionPhase.LIVE) add("Twitch")
                if (cur.dualDestination.phase == SessionPhase.LIVE) add("Dual")
                if (cur.hasStreamKeySaved && cur.rtmpServerUrl.isNotBlank() && isEmpty()) add("RTMP")
            }
            cur.copy(
                sessionPhase = SessionPhase.LIVE,
                reconnectCountdownSec = 0,
                errorReason = null,
                statusMessage = if (liveTags.isNotEmpty()) "LIVE · ${liveTags.joinToString(" + ")}" else summaryLabel
            )
        }
    }

    fun stopStreaming() {
        reconnectJob?.cancel()
        reconnectJob = null
        streamStartedAtMs = 0L
        pipeline?.stopAllStreams()
        platformCoordinator.onStreamingStopped()
        compositor.setHeavyFiltersSuspended(false)
        _uiState.update { cur ->
            val nextPhase = if (cur.sessionPhase == SessionPhase.IDLE) SessionPhase.IDLE else SessionPhase.ARMED
            cur.copy(
                sessionPhase = nextPhase,
                reconnectCountdownSec = 0,
                unlockLiveTouchOverride = false,
                dualDestination = cur.dualDestination.copy(phase = SessionPhase.IDLE, errorReason = null, bufferedBytes = 0L),
                statusMessage = if (nextPhase == SessionPhase.ARMED) "Stream stopped · Capture still armed" else "Stopped"
            )
        }
        logEvent("Streaming stopped by user")
    }

    fun startRecording() {
        val st = _uiState.value
        if (st.sessionPhase == SessionPhase.IDLE || st.sessionPhase == SessionPhase.ARMING) return
        val freeMb = getAvailableStorageMb()
        if (freeMb < 250L) {
            _uiState.update {
                it.copy(recordingPhase = RecordingPhase.ERROR, statusMessage = "Low storage ($freeMb MB free). Recording blocked.")
            }
            return
        }
        val startedFile = pipeline?.startRecording()
        if (startedFile != null) {
            recordStartedAtMs = System.currentTimeMillis()
            recordTotalPausedMs = 0L
            recordPausedAtMs = 0L
            _uiState.update {
                it.copy(recordingPhase = RecordingPhase.RECORDING, statusMessage = "Recording ${startedFile.name}")
            }
            logEvent("Recording started: ${startedFile.name}")
        }
    }

    fun pauseRecording() {
        if (_uiState.value.recordingPhase != RecordingPhase.RECORDING) return
        pipeline?.pauseRecording()
        recordPausedAtMs = System.currentTimeMillis()
        _uiState.update {
            it.copy(recordingPhase = RecordingPhase.PAUSED, statusMessage = "Recording PAUSED · Tap Resume to continue")
        }
        logEvent("Recording paused")
    }

    fun resumeRecording() {
        if (_uiState.value.recordingPhase != RecordingPhase.PAUSED) return
        if (recordPausedAtMs > 0L) {
            recordTotalPausedMs += (System.currentTimeMillis() - recordPausedAtMs).coerceAtLeast(0L)
            recordPausedAtMs = 0L
        }
        pipeline?.resumeRecording()
        _uiState.update {
            it.copy(recordingPhase = RecordingPhase.RECORDING, statusMessage = "Recording RESUMED")
        }
        logEvent("Recording resumed (PTS rebased cleanly)")
    }

    fun togglePauseRecord() {
        when (_uiState.value.recordingPhase) {
            RecordingPhase.RECORDING -> pauseRecording()
            RecordingPhase.PAUSED -> resumeRecording()
            else -> Unit
        }
    }

    fun stopRecording() {
        val phase = _uiState.value.recordingPhase
        if (phase != RecordingPhase.RECORDING && phase != RecordingPhase.PAUSED) return
        recordPausedAtMs = 0L
        _uiState.update { it.copy(recordingPhase = RecordingPhase.FINALIZING) }
        scope.launch(Dispatchers.IO) { pipeline?.stopRecording() }
    }

    fun toggleStreamFromClient() {
        val phase = _uiState.value.sessionPhase
        if (phase == SessionPhase.LIVE || phase == SessionPhase.CONNECTING || phase == SessionPhase.RECONNECTING) {
            stopStreaming()
        } else if (phase == SessionPhase.ARMED) {
            startStreaming()
        }
    }

    fun toggleRecordFromClient() {
        val phase = _uiState.value.recordingPhase
        if (phase == RecordingPhase.RECORDING || phase == RecordingPhase.PAUSED) stopRecording() else startRecording()
    }

    fun setReplayBufferEnabled(enabled: Boolean, durationSec: Int = _uiState.value.replayBuffer.durationSec) {
        val clamped = durationSec.coerceIn(10, 120)
        pipeline?.configureReplayBuffer(enabled, clamped)
        _uiState.update { cur ->
            cur.copy(
                replayBuffer = cur.replayBuffer.copy(
                    enabled = enabled,
                    durationSec = clamped,
                    bufferedSeconds = if (enabled) cur.replayBuffer.bufferedSeconds else 0f,
                    bufferedKb = if (enabled) cur.replayBuffer.bufferedKb else 0L
                ),
                statusMessage = if (enabled) "Instant Replay Buffer ON (${clamped}s ring)" else "Replay Buffer OFF"
            )
        }
        persistCurrentSettings()
        logEvent("Replay Buffer ${if (enabled) "enabled (${clamped}s)" else "disabled"}")
    }

    fun saveReplayClip() {
        val pipe = pipeline
        val rb = _uiState.value.replayBuffer
        if (pipe == null || !rb.enabled) {
            _uiState.update { it.copy(statusMessage = "Enable Replay Buffer while Armed to save instant clips") }
            return
        }
        _uiState.update { it.copy(statusMessage = "Saving ${rb.durationSec}s Instant Replay clip...") }
        scope.launch(Dispatchers.IO) {
            val saved = pipe.saveReplayBufferClip()
            scope.launch(Dispatchers.Main.immediate) {
                if (saved != null) {
                    _uiState.update { cur ->
                        cur.copy(
                            replayBuffer = cur.replayBuffer.copy(
                                clipsSavedCount = cur.replayBuffer.clipsSavedCount + 1,
                                lastSavedClipPath = saved.fileName
                            ),
                            recentRecordings = (listOf(saved) + cur.recentRecordings).take(20),
                            statusMessage = "Instant Replay saved: ${saved.fileName} (${saved.durationSec}s)"
                        )
                    }
                    logEvent("Saved Instant Replay: ${saved.fileName}")
                } else {
                    _uiState.update {
                        it.copy(statusMessage = "Replay buffer needs >=1 keyframe before saving")
                    }
                }
            }
        }
    }

    fun captureCanvasScreenshot() {
        val pipe = pipeline
        if (_uiState.value.sessionPhase == SessionPhase.IDLE || pipe == null) {
            _uiState.update { it.copy(statusMessage = "Arm Capture before snapping a canvas screenshot") }
            return
        }
        compositor.captureScreenshot { bmp ->
            if (bmp == null) {
                scope.launch {
                    _uiState.update { it.copy(statusMessage = "Screenshot capture timed out") }
                }
                return@captureScreenshot
            }
            scope.launch(Dispatchers.IO) {
                val uri = pipe.publishBitmapScreenshot(bmp)
                bmp.recycle()
                scope.launch(Dispatchers.Main.immediate) {
                    if (uri != null) {
                        _uiState.update {
                            it.copy(
                                lastScreenshotPath = uri,
                                statusMessage = "Canvas PNG saved to Pictures/Mobile Labs"
                            )
                        }
                        logEvent("Canvas screenshot saved: $uri")
                    } else {
                        _uiState.update { it.copy(statusMessage = "Failed to save screenshot") }
                    }
                }
            }
        }
    }

    fun disarmSession() {
        reconnectJob?.cancel()
        reconnectJob = null
        streamStartedAtMs = 0L
        recordStartedAtMs = 0L
        recordTotalPausedMs = 0L
        recordPausedAtMs = 0L
        platformCoordinator.onStreamingStopped()
        browserController.releaseAll()
        cameraController?.stop()
        cameraController = null
        audioMixer?.stop()
        audioMixer = null
        pipeline?.stopEncoders()
        pipeline = null
        compositor.stop()
        cameraSurfaceRef = null
        _uiState.update {
            it.copy(
                sessionPhase = SessionPhase.IDLE,
                recordingPhase = RecordingPhase.IDLE,
                isPreviewActive = false,
                reconnectCountdownSec = 0,
                unlockLiveTouchOverride = false,
                drawingModeActive = false,
                statusMessage = "Disarmed · Tap Arm Capture to start"
            )
        }
        logEvent("Session disarmed")
    }

    fun onMediaProjectionStoppedBySystem() {
        disarmSession()
        _uiState.update {
            it.copy(captureStoppedBySystem = true, statusMessage = "Capture stopped by system · Tap Re-Arm Capture")
        }
        logEvent("MediaProjection stopped from system UI")
    }

    fun onScreenLocked() {
        val phase = _uiState.value.recordingPhase
        if (phase == RecordingPhase.RECORDING || phase == RecordingPhase.PAUSED) stopRecording()
        logEvent("Device screen locked; recording finalized if active")
    }

    fun onCapturedContentResized(width: Int, height: Int) {
        compositor.updateSourceDisplaySize(width, height)
        logEvent("Captured display resized to ${width}x$height")
    }

    // Presets & Video Settings
    fun applyPreset(presetId: PresetId) {
        val current = _uiState.value
        if (presetId.requires1080p60Gate) {
            val probePassed = current.deviceReport?.encoders?.any {
                it.isHardwareAccelerated && it.supportsSurfaceInput && it.supports1080p60
            } == true
            if (!probePassed) {
                _uiState.update {
                    it.copy(statusMessage = "1080p60 locked: MediaCodec probe did not verify 1080p60 HW encode")
                }
                return
            }
        }
        val newCfg = current.videoConfig.copy(
            presetId = presetId,
            canvasWidth = presetId.canvasWidth,
            canvasHeight = presetId.canvasHeight,
            outputWidth = presetId.outputWidth,
            outputHeight = presetId.outputHeight,
            fps = presetId.fps,
            videoBitrateKbps = presetId.videoBitrateKbps,
            audioBitrateKbps = presetId.audioBitrateKbps
        )
        updateVideoConfig(newCfg)
        logEvent("Preset applied: ${presetId.title}")
    }

    fun updateVideoConfig(newConfig: VideoConfig) {
        val cur = _uiState.value
        val sessionActive = cur.sessionPhase != SessionPhase.IDLE && cur.sessionPhase != SessionPhase.ERROR
        // Lock encoder dimensions/fps/format while live or armed so MediaCodec isn't mismatched mid-stream
        val safeConfig = if (sessionActive) {
            newConfig.copy(
                canvasWidth = cur.videoConfig.canvasWidth,
                canvasHeight = cur.videoConfig.canvasHeight,
                outputWidth = cur.videoConfig.outputWidth,
                outputHeight = cur.videoConfig.outputHeight
            )
        } else newConfig

        compositor.updateVideoConfig(safeConfig)
        obsBridge.reconfigureVideo(safeConfig)
        pipeline?.recordingFormat = safeConfig.recordingFormat
        dynamicTargetBitrateKbps = safeConfig.videoBitrateKbps
        pipeline?.updateLiveBitrate(safeConfig.videoBitrateKbps)
        _uiState.update {
            it.copy(
                videoConfig = safeConfig,
                telemetry = it.telemetry.copy(
                    outputWidth = safeConfig.outputWidth,
                    outputHeight = safeConfig.outputHeight,
                    configuredFps = safeConfig.fps
                ),
                statusMessage = if (sessionActive && (newConfig.outputWidth != cur.videoConfig.outputWidth || newConfig.canvasWidth != cur.videoConfig.canvasWidth)) {
                    "Resolution locked while capture is active (bitrate & fit updated)"
                } else it.statusMessage
            )
        }
        persistCurrentSettings()
    }

    fun updateScreenFitMode(mode: ScreenFitMode, cropOffsetY: Float) {
        updateVideoConfig(
            _uiState.value.videoConfig.copy(
                screenFitMode = mode,
                screenCropOffsetYFraction = cropOffsetY.coerceIn(0f, 1f)
            )
        )
    }

    fun saveStreamCredentials(preset: RtmpServicePreset, serverUrl: String, newStreamKey: String?) {
        scope.launch(Dispatchers.IO) {
            secretStore.saveStreamConfig(preset.name, serverUrl, newStreamKey)
            val hasKey = secretStore.hasStreamKey()
            val masked = secretStore.getMaskedPreview()
            _uiState.update {
                it.copy(
                    rtmpServicePreset = preset,
                    rtmpServerUrl = serverUrl.trim(),
                    hasStreamKeySaved = hasKey,
                    maskedStreamKeyPreview = masked,
                    statusMessage = "Stream settings encrypted in Android Keystore"
                )
            }
            logEvent("Stream credentials updated for preset ${preset.label}")
        }
    }

    fun saveSecondaryStreamCredentials(
        enabled: Boolean,
        preset: RtmpServicePreset,
        serverUrl: String,
        newStreamKey: String?
    ) {
        scope.launch(Dispatchers.IO) {
            secretStore.saveSecondaryStreamConfig(preset.name, serverUrl, newStreamKey)
            val hasKey = secretStore.hasSecondaryStreamKey()
            val masked = secretStore.getSecondaryMaskedPreview()
            _uiState.update { cur ->
                cur.copy(
                    dualDestination = cur.dualDestination.copy(
                        enabled = enabled,
                        preset = preset,
                        serverUrl = serverUrl.trim(),
                        hasKeySaved = hasKey,
                        maskedKeyPreview = masked
                    ),
                    statusMessage = if (enabled) "Dual RTMP destination saved (shared HW encode)" else "Dual RTMP disabled"
                )
            }
            persistCurrentSettings()
            logEvent("Secondary stream destination updated (enabled=$enabled, preset=${preset.label})")
        }
    }

    fun setDualDestinationEnabled(enabled: Boolean) {
        _uiState.update { cur ->
            cur.copy(dualDestination = cur.dualDestination.copy(enabled = enabled))
        }
        persistCurrentSettings()
    }

    fun setCustomRecordingFolderUri(uri: String?) {
        pipeline?.customRecordingFolderUri = uri
        _uiState.update {
            it.copy(
                customRecordingFolderUri = uri,
                statusMessage = if (uri != null) "Custom recording folder set (SAF)" else "Recording folder reset to Movies/Mobile Labs"
            )
        }
        persistCurrentSettings()
    }

    fun recoverIncompleteRecording(filePath: String) {
        scope.launch(Dispatchers.IO) {
            val file = java.io.File(filePath)
            val ok = file.exists() && repository.verifyMediaPlayable(file)
            if (ok) {
                val uri = pipeline?.publishExistingPlayableFile(file) ?: run {
                    // Fallback copy to MediaStore if pipeline isn't currently armed
                    val tempPipe = HardwareMediaPipeline(
                        context = context,
                        repository = repository,
                        onEncoderSurfaceReady = {},
                        onStreamConnected = {},
                        onStreamError = {},
                        onRecordingFinalized = {},
                        onRecordingError = {}
                    ).apply { customRecordingFolderUri = _uiState.value.customRecordingFolderUri }
                    tempPipe.publishExistingPlayableFile(file)
                }
                repository.clearActiveRecordingMarker()
                val remaining = repository.detectIncompleteRecordings()
                _uiState.update { cur ->
                    val pub = com.example.mobilelabs.model.PublishedRecording(
                        fileName = file.name,
                        mediaStoreUri = uri ?: file.absolutePath,
                        durationSec = 1L,
                        bytesSize = if (file.exists()) file.length() else 0L,
                        createdAtMs = System.currentTimeMillis()
                    )
                    cur.copy(
                        incompleteRecordings = remaining,
                        recentRecordings = (listOf(pub) + cur.recentRecordings).take(20),
                        statusMessage = "Recovered ${file.name} to library"
                    )
                }
                logEvent("Recovered playable incomplete recording: ${file.name}")
            } else {
                _uiState.update { it.copy(statusMessage = "Recording file is unplayable / truncated; delete to free space") }
            }
        }
    }

    fun deleteIncompleteRecording(filePath: String) {
        scope.launch(Dispatchers.IO) {
            val f = java.io.File(filePath)
            if (f.exists()) f.delete()
            repository.clearActiveRecordingMarker()
            val remaining = repository.detectIncompleteRecordings()
            _uiState.update {
                it.copy(
                    incompleteRecordings = remaining,
                    statusMessage = "Deleted incomplete recording ${f.name}"
                )
            }
            logEvent("Deleted incomplete recording: ${f.name}")
        }
    }

    fun setFloatingOverlayEnabled(enabled: Boolean) {
        _uiState.update { it.copy(floatingOverlayEnabled = enabled) }
        persistCurrentSettings()
        if (_uiState.value.hasOverlayPermission) {
            val svcIntent = Intent(context, CaptureForegroundService::class.java)
                .setAction(CaptureForegroundService.ACTION_SYNC_OVERLAY)
            runCatching { context.startService(svcIntent) }
        }
    }

    fun setOverlayCurrentlyShown(shown: Boolean) {
        _uiState.update { it.copy(telemetry = it.telemetry.copy(isOverlayShown = shown)) }
    }

    fun saveOverlayPosition(isLandscape: Boolean, x: Int, y: Int) {
        if (isLandscape) overlayLandscapePos = x to y else overlayPortraitPos = x to y
        persistCurrentSettings()
    }

    fun getOverlayInitialPosition(isLandscape: Boolean): Pair<Int, Int> {
        return if (isLandscape) overlayLandscapePos else overlayPortraitPos
    }

    fun runFullDeviceAndCodecProbe() {
        scope.launch(Dispatchers.IO) {
            val report = probeTool.runProbe(testConcurrency = true)
            _uiState.update {
                it.copy(
                    deviceReport = report,
                    statusMessage = "Probe complete: ${report.encoders.count { e -> e.isHardwareAccelerated }} HW codecs found"
                )
            }
            logEvent("Codec probe ran (max concurrent H.264 HW: ${report.maxConcurrentAvcHardwareInstances})")
        }
    }

    fun exportDiagnosticsZip() {
        scope.launch(Dispatchers.IO) {
            val key = secretStore.loadStreamKey()
            val secKey = secretStore.loadSecondaryStreamKey()
            val path = diagnosticsExporter.exportRedactedZip(_uiState.value, key, secKey)
            _uiState.update {
                it.copy(
                    lastDiagnosticsExportPath = path,
                    statusMessage = if (path != null) "Diagnostics exported to $path" else "Export failed"
                )
            }
            logEvent("Diagnostics exported to ${path ?: "failed"}")
        }
    }

    // Phase 3: Adaptive Quality, Gaming Presets, Samsung Advisor, Auto-Tune, Benchmark Kit, Onboarding
    fun setAdaptiveQualityLocked(locked: Boolean) {
        val next = adaptiveEngine.setLockedByUser(locked)
        _uiState.update { it.copy(adaptiveQuality = next) }
        persistCurrentSettings()
        logEvent(if (locked) "Adaptive quality LOCKED by user" else "Adaptive quality unlocked")
    }

    fun resetAdaptiveQuality() {
        compositor.setHeavyFiltersSuspended(false)
        val next = adaptiveEngine.resetToNormal("Reset manually by user")
        _uiState.update { it.copy(adaptiveQuality = next) }
        logEvent("Adaptive quality reset to L0")
    }

    fun applyGamingScenePreset(preset: GamingScenePreset) {
        pushUndo()
        val newScenes = GamingScenePresets.buildScenesForPreset(preset)
        val col = _uiState.value.sceneCollection
        val updated = if (preset == GamingScenePreset.INTERMISSION_PACK) {
            col.copy(scenes = col.scenes + newScenes, activeSceneId = newScenes.first().id)
        } else {
            val replaced = col.scenes.filterNot { ns -> newScenes.any { it.name == ns.name } } + newScenes
            col.copy(scenes = replaced, activeSceneId = newScenes.first().id)
        }
        val selId = newScenes.first().sources.firstOrNull()?.id
        _uiState.update {
            it.copy(
                sceneCollection = updated,
                previewSceneId = updated.activeSceneId,
                programSceneId = if (it.studioModeEnabled) it.programSceneId else updated.activeSceneId,
                selectedSourceId = selId,
                statusMessage = "Applied Gaming Preset: ${preset.label}"
            )
        }
        repository.saveSceneCollectionAtomic(updated)
        syncCompositorScenes()
        syncActiveCameraSource()
        logEvent("Applied Gaming Scene Preset: ${preset.label}")
    }

    fun refreshSamsungAdvisor() {
        val status = SamsungOneUiAdvisor.inspect(context)
        _uiState.update { it.copy(samsungStatus = status) }
    }

    fun runAutoTuneProbe(applyRecommended: Boolean = true) {
        scope.launch(Dispatchers.IO) {
            val report = probeTool.runProbe(testConcurrency = true)
            val hwAvcCount = report.encoders.count { it.isHardwareAccelerated && it.mimeType == "video/avc" }
            val supports1080p60 = report.encoders.any { it.isHardwareAccelerated && it.supportsSurfaceInput && it.supports1080p60 }
            val supports720p60 = report.encoders.any { it.isHardwareAccelerated && it.supportsSurfaceInput && it.supports720p60 }
            val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { powerManager.getThermalHeadroom(10) }.getOrDefault(-1f)
            } else -1f

            val recommended = when {
                report.totalRamMb < 4800L || !supports720p60 || headroom >= 0.85f -> PresetId.LOW_OVERHEAD
                else -> PresetId.BALANCED
            }
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            val summary = "Found $hwAvcCount HW AVC encoders · RAM ${report.totalRamMb} MB · Thermal headroom ${
                if (headroom >= 0f) String.format(Locale.US, "%.2f", headroom) else "UNMEASURED"
            } · Recommended: ${recommended.title}"

            val tune = AutoTuneResult(
                completedAtIso = stamp,
                recommendedPreset = recommended,
                hwAvcEncodersFound = hwAvcCount,
                supports1080p60 = supports1080p60,
                ramMb = report.totalRamMb,
                thermalHeadroom = headroom,
                summary = summary
            )
            _uiState.update { cur ->
                cur.copy(
                    deviceReport = report,
                    autoTuneResult = tune,
                    statusMessage = "Auto-Tune complete: ${recommended.title}"
                )
            }
            if (applyRecommended) {
                scope.launch(Dispatchers.Main.immediate) {
                    applyPreset(recommended)
                }
            }
            logEvent("Auto-Tune Wizard completed: $summary")
        }
    }

    fun testRtmpReachability(url: String, onResult: (Boolean, String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val trimmed = url.trim()
                val isTls = trimmed.startsWith("rtmps://", ignoreCase = true)
                val clean = trimmed.removePrefix("rtmps://").removePrefix("rtmp://")
                val hostPort = clean.substringBefore('/')
                val host = hostPort.substringBefore(':').ifBlank { error("Invalid host") }
                val port = hostPort.substringAfter(':', "").toIntOrNull() ?: if (isTls) 443 else 1935

                val startMs = System.currentTimeMillis()
                val sock = if (isTls) SSLSocketFactory.getDefault().createSocket() else Socket()
                sock.use { s ->
                    s.connect(InetSocketAddress(host, port), 4000)
                    s.soTimeout = 4000
                    val out = s.getOutputStream()
                    out.write(0x03)
                    out.write(ByteArray(1536))
                    out.flush()
                    val s0 = s.getInputStream().read()
                    val rtt = System.currentTimeMillis() - startMs
                    if (s0 == 0x03) {
                        true to "RTMP handshake OK (${rtt} ms to $host:$port)"
                    } else {
                        false to "Connected to $host:$port (${rtt} ms), unexpected S0=$s0"
                    }
                }
            }.getOrElse { err ->
                false to "Unreachable: ${err.message ?: "connection refused"}"
            }
            scope.launch(Dispatchers.Main.immediate) {
                onResult(result.first, result.second)
            }
        }
    }

    fun completeOnboarding() {
        _uiState.update { it.copy(onboardingCompleted = true) }
        persistCurrentSettings()
    }

    fun dismissCrashBanner() {
        _uiState.update { it.copy(lastCrashSummary = null) }
    }

    fun addBenchmarkRecord(
        appName: String,
        gameName: String,
        avgFps: Float,
        onePercentLowFps: Float,
        notes: String
    ) {
        val st = _uiState.value
        val dropTotal = st.telemetry.renderSkippedFrames + st.telemetry.encoderDroppedFrames + st.telemetry.networkDroppedFrames
        val estPct = if (st.telemetry.streamDurationSec > 0 && st.videoConfig.fps > 0) {
            ((dropTotal.toFloat() / (st.telemetry.streamDurationSec * st.videoConfig.fps).coerceAtLeast(1)) * 100f)
        } else 0f
        val rec = BenchmarkRecord(
            id = "bench_${System.currentTimeMillis()}",
            appName = appName.ifBlank { "Mobile Labs" },
            presetLabel = "${st.videoConfig.outputWidth}x${st.videoConfig.outputHeight}@${st.videoConfig.fps}",
            gameName = gameName.ifBlank { "Geometry Dash" },
            gameAvgFps = avgFps,
            gameOnePercentLowFps = onePercentLowFps,
            streamDroppedPct = estPct,
            peakThermalLabel = st.telemetry.thermal.statusLabel,
            processPssMb = st.telemetry.processPssMb,
            notes = notes,
            recordedAtIso = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        )
        _uiState.update { cur ->
            cur.copy(
                benchmarkRecords = (listOf(rec) + cur.benchmarkRecords).take(30),
                statusMessage = "Recorded benchmark run for ${rec.appName} (${rec.gameName})"
            )
        }
        persistCurrentSettings()
        logEvent("Benchmark logged: ${rec.appName} · ${rec.gameName} (${rec.gameAvgFps} fps)")
    }

    fun deleteBenchmarkRecord(id: String) {
        _uiState.update { cur ->
            cur.copy(benchmarkRecords = cur.benchmarkRecords.filterNot { it.id == id })
        }
        persistCurrentSettings()
    }

    fun toggleHudExpanded() {
        _uiState.update { it.copy(hudExpanded = !it.hudExpanded) }
    }

    fun setExpandedDock(dock: DockPanelId?) {
        _uiState.update { it.copy(expandedDock = if (it.expandedDock == dock) null else dock) }
    }

    // Studio Mode & Scene Management (M2.1, M2.4)
    private fun editableSceneId(st: MobileLabsUiState = _uiState.value): String =
        if (st.studioModeEnabled) st.previewSceneId else st.sceneCollection.activeSceneId

    fun toggleStudioMode() {
        _uiState.update { st ->
            val next = !st.studioModeEnabled
            st.copy(
                studioModeEnabled = next,
                previewSceneId = st.sceneCollection.activeSceneId,
                programSceneId = st.programSceneId.ifBlank { st.sceneCollection.activeSceneId },
                statusMessage = if (next) "Studio Mode ON · Edits stage in Preview until Take" else "Studio Mode OFF"
            )
        }
        persistCurrentSettings()
        syncCompositorScenes()
    }

    fun selectScene(sceneId: String) {
        val st = _uiState.value
        val targetScene = st.sceneCollection.scenes.firstOrNull { it.id == sceneId } ?: return
        if (st.studioModeEnabled) {
            // In Studio Mode, selecting a scene loads it strictly into PREVIEW; PROGRAM stays untouched until Take
            _uiState.update { cur ->
                cur.copy(
                    previewSceneId = sceneId,
                    sceneCollection = cur.sceneCollection.copy(activeSceneId = sceneId),
                    selectedSourceId = targetScene.sources.firstOrNull()?.id,
                    multiSelectedSourceIds = emptySet()
                )
            }
            syncCompositorScenes()
            syncActiveCameraSource()
            return
        }

        val prevScene = st.sceneCollection.scenes.firstOrNull { it.id == st.programSceneId }
        val updated = st.sceneCollection.copy(activeSceneId = sceneId)
        compositor.triggerTransition(
            fromScene = prevScene,
            type = updated.transitionType,
            durationMs = updated.transitionDurationMs,
            colorArgb = updated.transitionColorArgb
        )
        _uiState.update {
            it.copy(
                sceneCollection = updated,
                previewSceneId = sceneId,
                programSceneId = sceneId,
                selectedSourceId = targetScene.sources.firstOrNull()?.id,
                multiSelectedSourceIds = emptySet()
            )
        }
        repository.saveSceneCollectionAtomic(updated)
        syncCompositorScenes()
        syncActiveCameraSource()
    }

    fun triggerStudioTake() {
        val st = _uiState.value
        if (!st.studioModeEnabled) return
        val prevProgram = st.sceneCollection.scenes.firstOrNull { it.id == st.programSceneId }
        val nextProgramId = st.previewSceneId
        val nextPreviewId = if (st.swapPreviewProgramOnTake) st.programSceneId else st.previewSceneId

        compositor.triggerTransition(
            fromScene = prevProgram,
            type = st.sceneCollection.transitionType,
            durationMs = st.sceneCollection.transitionDurationMs,
            colorArgb = st.sceneCollection.transitionColorArgb
        )

        val updatedCol = st.sceneCollection.copy(activeSceneId = nextPreviewId)
        _uiState.update {
            it.copy(
                programSceneId = nextProgramId,
                previewSceneId = nextPreviewId,
                sceneCollection = updatedCol,
                statusMessage = "Transitioned Program (${st.sceneCollection.transitionType.label})"
            )
        }
        repository.saveSceneCollectionAtomic(updatedCol)
        syncCompositorScenes()
        syncActiveCameraSource()
    }

    fun cycleNextSceneForOverlay() {
        val st = _uiState.value
        val scenes = st.sceneCollection.scenes
        if (scenes.size <= 1) return
        val idx = scenes.indexOfFirst { it.id == st.programSceneId }
        val next = scenes[(idx + 1) % scenes.size]
        if (st.studioModeEnabled) {
            _uiState.update { it.copy(previewSceneId = next.id) }
            triggerStudioTake()
        } else {
            selectScene(next.id)
        }
    }

    fun toggleQuickPresetForOverlay() {
        val cur = _uiState.value.videoConfig.presetId
        val next = if (cur == PresetId.LOW_OVERHEAD) PresetId.BALANCED else PresetId.LOW_OVERHEAD
        applyPreset(next)
    }

    fun createScene(name: String) {
        if (name.isBlank()) return
        pushUndo()
        val newScene = SceneModel(
            id = "scene_${System.currentTimeMillis()}",
            name = name.trim(),
            sources = listOf(SceneEditingHelper.createDefaultSource(SourceType.SCREEN_CAPTURE))
        )
        val col = _uiState.value.sceneCollection
        val updated = col.copy(scenes = col.scenes + newScene, activeSceneId = newScene.id)
        _uiState.update {
            it.copy(
                sceneCollection = updated,
                previewSceneId = newScene.id,
                programSceneId = if (it.studioModeEnabled) it.programSceneId else newScene.id
            )
        }
        repository.saveSceneCollectionAtomic(updated)
        syncCompositorScenes()
    }

    fun renameScene(sceneId: String, newName: String) {
        if (newName.isBlank()) return
        pushUndo()
        val col = _uiState.value.sceneCollection
        val updated = col.copy(
            scenes = col.scenes.map { if (it.id == sceneId) it.copy(name = newName.trim()) else it }
        )
        commitSceneCollection(updated)
    }

    fun duplicateScene(sceneId: String) {
        val col = _uiState.value.sceneCollection
        val orig = col.scenes.firstOrNull { it.id == sceneId } ?: return
        pushUndo()
        val ts = System.currentTimeMillis()
        val copy = orig.copy(
            id = "scene_$ts",
            name = "${orig.name} Copy",
            sources = orig.sources.mapIndexed { idx, s -> s.copy(id = "src_${ts}_$idx") }
        )
        val updated = col.copy(scenes = col.scenes + copy, activeSceneId = copy.id)
        commitSceneCollection(updated)
    }

    fun deleteScene(sceneId: String) {
        val col = _uiState.value.sceneCollection
        if (col.scenes.size <= 1) return
        pushUndo()
        val remaining = col.scenes.filterNot { it.id == sceneId }
        val fallbackId = remaining.first().id
        val updated = col.copy(
            scenes = remaining,
            activeSceneId = if (col.activeSceneId == sceneId) fallbackId else col.activeSceneId
        )
        _uiState.update {
            it.copy(
                sceneCollection = updated,
                previewSceneId = if (it.previewSceneId == sceneId) fallbackId else it.previewSceneId,
                programSceneId = if (it.programSceneId == sceneId) fallbackId else it.programSceneId
            )
        }
        repository.saveSceneCollectionAtomic(updated)
        syncCompositorScenes()
    }

    fun moveScene(sceneId: String, delta: Int) {
        pushUndo()
        val col = _uiState.value.sceneCollection
        val reordered = SceneEditingHelper.moveSceneInList(col.scenes, sceneId, delta)
        commitSceneCollection(col.copy(scenes = reordered))
    }

    // Source CRUD & Canvas Editing (M2.1, M2.2)
    fun addSourceToActiveScene(type: SourceType, pathOrUri: String? = null) {
        pushUndo()
        val targetId = editableSceneId()
        val newSrc = SceneEditingHelper.createDefaultSource(type, pathOrUri)
        val col = _uiState.value.sceneCollection
        val updated = col.copy(
            scenes = col.scenes.map { if (it.id == targetId) it.copy(sources = it.sources + newSrc) else it }
        )
        _uiState.update { it.copy(sceneCollection = updated, selectedSourceId = newSrc.id) }
        repository.saveSceneCollectionAtomic(updated)
        syncCompositorScenes()
        syncActiveCameraSource()
    }

    fun importMediaSourceFromUri(type: SourceType, uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val prefix = if (type == SourceType.IMAGE_SOURCE) "img" else "vid"
            val (localPath, err) = repository.copySafMediaAsset(uri, prefix)
            scope.launch(Dispatchers.Main.immediate) {
                if (localPath != null) {
                    addSourceToActiveScene(type, localPath)
                    logEvent("Imported ${type.label}: ${localPath.substringAfterLast('/')}")
                } else {
                    _uiState.update { it.copy(statusMessage = err ?: "Failed to import media") }
                }
            }
        }
    }

    fun relinkSourceFromUri(sourceId: String, type: SourceType, uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val prefix = if (type == SourceType.IMAGE_SOURCE) "img" else "vid"
            val (localPath, err) = repository.copySafMediaAsset(uri, prefix)
            scope.launch(Dispatchers.Main.immediate) {
                if (localPath != null) {
                    mutateEditableSource(sourceId) {
                        it.copy(mediaPathOrUri = localPath, erroredReason = null)
                    }
                    logEvent("Relinked media asset: ${localPath.substringAfterLast('/')}")
                } else {
                    _uiState.update { it.copy(statusMessage = err ?: "Relink failed") }
                }
            }
        }
    }

    fun selectSource(sourceId: String) {
        _uiState.update { it.copy(selectedSourceId = sourceId, multiSelectedSourceIds = setOf(sourceId)) }
    }

    fun toggleSourceMultiSelect(sourceId: String) {
        _uiState.update { cur ->
            val nextSet = if (sourceId in cur.multiSelectedSourceIds) {
                cur.multiSelectedSourceIds - sourceId
            } else {
                cur.multiSelectedSourceIds + sourceId
            }
            cur.copy(selectedSourceId = sourceId, multiSelectedSourceIds = nextSet)
        }
    }

    fun alignMultiSelectedCenter() {
        val st = _uiState.value
        val targets = st.multiSelectedSourceIds.ifEmpty { setOfNotNull(st.selectedSourceId) }
        if (targets.isEmpty()) return
        pushUndo()
        val sceneId = editableSceneId(st)
        var col = st.sceneCollection
        for (id in targets) {
            col = SceneEditingHelper.updateSourceInScene(col, sceneId, id) { src ->
                if (src.locked) src else {
                    val t = src.transform
                    src.copy(
                        transform = t.copy(
                            xFraction = ((1f - t.widthFraction) * 0.5f).coerceIn(0f, 1f),
                            yFraction = ((1f - t.heightFraction) * 0.5f).coerceIn(0f, 1f)
                        )
                    )
                }
            }
        }
        commitSceneCollection(col)
    }

    fun toggleSourceVisibility(sourceId: String) {
        mutateEditableSource(sourceId) { it.copy(visible = !it.visible) }
        syncActiveCameraSource()
    }

    fun toggleSourceLock(sourceId: String) {
        mutateEditableSource(sourceId) { it.copy(locked = !it.locked) }
    }

    fun switchCameraLens(sourceId: String) {
        mutateEditableSource(sourceId) { it.copy(useFrontCamera = !it.useFrontCamera) }
        syncActiveCameraSource()
    }

    fun renameSource(sourceId: String, newName: String) {
        if (newName.isBlank()) return
        mutateEditableSource(sourceId) { it.copy(name = newName.trim()) }
    }

    fun duplicateSource(sourceId: String) {
        val st = _uiState.value
        val targetId = editableSceneId(st)
        val scene = st.sceneCollection.scenes.firstOrNull { it.id == targetId } ?: return
        val orig = scene.sources.firstOrNull { it.id == sourceId } ?: return
        pushUndo()
        val copy = orig.copy(
            id = "src_${System.currentTimeMillis()}",
            name = "${orig.name} Copy",
            transform = orig.transform.copy(
                xFraction = (orig.transform.xFraction + 0.04f).coerceAtMost(0.85f),
                yFraction = (orig.transform.yFraction + 0.04f).coerceAtMost(0.85f)
            )
        )
        val updated = st.sceneCollection.copy(
            scenes = st.sceneCollection.scenes.map { if (it.id == targetId) it.copy(sources = it.sources + copy) else it }
        )
        _uiState.update { it.copy(sceneCollection = updated, selectedSourceId = copy.id) }
        repository.saveSceneCollectionAtomic(updated)
        syncCompositorScenes()
    }

    fun moveSourceOrder(sourceId: String, delta: Int) {
        val st = _uiState.value
        val targetId = editableSceneId(st)
        pushUndo()
        val updated = st.sceneCollection.copy(
            scenes = st.sceneCollection.scenes.map { s ->
                if (s.id == targetId) s.copy(sources = SceneEditingHelper.moveItemInList(s.sources, sourceId, delta)) else s
            }
        )
        commitSceneCollection(updated)
    }

    fun updateSourceProperties(sourceId: String, updater: (SourceModel) -> SourceModel) {
        mutateEditableSource(sourceId, updater)
        val st = _uiState.value
        val activeScene = st.sceneCollection.scenes.firstOrNull { it.id == editableSceneId(st) }
        val updatedSrc = activeScene?.sources?.firstOrNull { it.id == sourceId }
        if (updatedSrc?.type == SourceType.CAMERA_2 && updatedSrc.visible) {
            cameraController?.updateProSettings(cameraProFromSource(updatedSrc))
        }
    }

    fun updateSourceVideoFilters(sourceId: String, filters: VideoFilterConfig) {
        mutateEditableSource(sourceId) { it.copy(videoFilters = filters) }
    }

    fun removeSource(sourceId: String) {
        pushUndo()
        val st = _uiState.value
        val targetId = editableSceneId(st)
        val updated = st.sceneCollection.copy(
            scenes = st.sceneCollection.scenes.map { scene ->
                if (scene.id == targetId) scene.copy(sources = scene.sources.filterNot { it.id == sourceId }) else scene
            }
        )
        commitSceneCollection(updated)
        syncActiveCameraSource()
    }

    fun isCanvasTouchBlocked(): Boolean {
        val st = _uiState.value
        val liveOrRec = st.sessionPhase == SessionPhase.LIVE || st.recordingPhase == RecordingPhase.RECORDING
        return liveOrRec && !st.studioModeEnabled && st.lockPreviewTouchWhileLive && !st.unlockLiveTouchOverride
    }

    fun setUnlockLiveTouchOverride(unlock: Boolean) {
        _uiState.update { it.copy(unlockLiveTouchOverride = unlock) }
    }

    fun nudgeSelectedSourceTransform(dx: Float, dy: Float, dScale: Float = 0f, dRotDeg: Float = 0f) {
        if (isCanvasTouchBlocked()) return
        val selId = _uiState.value.selectedSourceId ?: return
        mutateEditableSource(selId) { src ->
            if (src.locked) return@mutateEditableSource src
            src.copy(
                transform = SceneEditingHelper.applyTransformWithSnap(
                    src.transform, dx, dy, dScale, dScale, dRotDeg, enableSnap = true
                )
            )
        }
    }

    fun dragSourceTransform(
        sourceId: String,
        dx: Float,
        dy: Float,
        dW: Float,
        dH: Float,
        dRotDeg: Float,
        recordUndo: Boolean = false
    ) {
        if (isCanvasTouchBlocked()) return
        val st = _uiState.value
        val targetId = editableSceneId(st)
        if (recordUndo) pushUndo()
        val updated = SceneEditingHelper.updateSourceInScene(st.sceneCollection, targetId, sourceId) { src ->
            if (src.locked) return@updateSourceInScene src
            src.copy(
                transform = SceneEditingHelper.applyTransformWithSnap(
                    src.transform, dx, dy, dW, dH, dRotDeg, enableSnap = true
                )
            )
        }
        _uiState.update { it.copy(sceneCollection = updated) }
        repository.saveSceneCollectionAtomic(updated)
        syncCompositorScenes()
    }

    fun updateSourceCrop(sourceId: String, left: Float, top: Float, right: Float, bottom: Float) {
        if (isCanvasTouchBlocked()) return
        mutateEditableSource(sourceId) { src ->
            if (src.locked) return@mutateEditableSource src
            src.copy(
                transform = src.transform.copy(
                    cropLeft = left.coerceIn(0f, 0.45f),
                    cropTop = top.coerceIn(0f, 0.45f),
                    cropRight = right.coerceIn(0f, 0.45f),
                    cropBottom = bottom.coerceIn(0f, 0.45f)
                )
            )
        }
    }

    fun fitSelectedSourceToScreen() {
        if (isCanvasTouchBlocked()) return
        val selId = _uiState.value.selectedSourceId ?: return
        mutateEditableSource(selId) { src ->
            if (src.locked) return@mutateEditableSource src
            src.copy(
                transform = SourceTransform(0f, 0f, 1f, 1f, rotationDegrees = 0f, cropLeft = 0f, cropTop = 0f, cropRight = 0f, cropBottom = 0f)
            )
        }
    }

    fun setActiveTool(tool: PreviewTool) {
        if (tool == PreviewTool.FIT_TO_SCREEN) {
            fitSelectedSourceToScreen()
        }
        _uiState.update { it.copy(activeTool = tool) }
    }

    fun undoSceneEdit() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_uiState.value.sceneCollection)
        _uiState.update {
            it.copy(
                sceneCollection = prev,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
        }
        repository.saveSceneCollectionAtomic(prev)
        syncCompositorScenes()
        syncActiveCameraSource()
    }

    fun redoSceneEdit() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_uiState.value.sceneCollection)
        _uiState.update {
            it.copy(
                sceneCollection = next,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
        }
        repository.saveSceneCollectionAtomic(next)
        syncCompositorScenes()
        syncActiveCameraSource()
    }

    fun setTransition(type: TransitionType, durationMs: Int, colorArgb: Long = _uiState.value.sceneCollection.transitionColorArgb) {
        val updated = _uiState.value.sceneCollection.copy(
            transitionType = type,
            transitionDurationMs = durationMs.coerceIn(50, 2000),
            transitionColorArgb = colorArgb
        )
        _uiState.update { it.copy(sceneCollection = updated) }
        repository.saveSceneCollectionAtomic(updated)
        obsBridge.syncTransition(type, updated.transitionDurationMs)
    }

    // Audio Mixer & Filters Actions (M2.3)
    fun setAudioVolume(channelId: String, volume: Float) {
        val v = volume.coerceIn(0f, 1.5f)
        if (channelId == "audio_game") audioMixer?.gameVolume = v
        if (channelId == "audio_mic") audioMixer?.micVolume = v
        updateAudioChannel(channelId) { it.copy(volume = v) }
    }

    fun toggleAudioMute(channelId: String) {
        val nextMuted = _uiState.value.audioChannels.firstOrNull { it.id == channelId }?.muted?.not() ?: false
        if (channelId == "audio_game") audioMixer?.gameMuted = nextMuted
        if (channelId == "audio_mic") audioMixer?.micMuted = nextMuted
        updateAudioChannel(channelId) { it.copy(muted = nextMuted) }
    }

    fun toggleAudioLock(channelId: String) {
        updateAudioChannel(channelId) { it.copy(locked = !it.locked) }
    }

    fun updateAudioFilters(channelId: String, filters: AudioFilterConfig) {
        if (channelId == "audio_game") audioMixer?.gameFilters = filters
        if (channelId == "audio_mic") audioMixer?.micFilters = filters
        obsBridge.syncAudioFilter(channelId, filters)
        updateAudioChannel(channelId) { it.copy(filters = filters) }
        persistCurrentSettings()
    }

    fun setAudioSyncOffset(channelId: String, offsetMs: Int) {
        val clamped = offsetMs.coerceIn(-500, 500)
        if (channelId == "audio_game") audioMixer?.gameSyncOffsetMs = clamped
        if (channelId == "audio_mic") audioMixer?.micSyncOffsetMs = clamped
        updateAudioChannel(channelId) { it.copy(syncOffsetMs = clamped) }
        persistCurrentSettings()
    }

    fun toggleMasterMute() {
        val next = !_uiState.value.masterMuted
        audioMixer?.masterMuted = next
        _uiState.update { it.copy(masterMuted = next) }
    }

    // Profiles & Scene Collections & OBS JSON Import/Export (M2.5)
    fun createProfile(name: String) {
        if (name.isBlank()) return
        val cur = _uiState.value
        val newProf = AppProfile(
            id = "prof_${System.currentTimeMillis()}",
            name = name.trim(),
            videoConfig = cur.videoConfig,
            rtmpServicePreset = cur.rtmpServicePreset,
            lockPreviewTouchWhileLive = cur.lockPreviewTouchWhileLive,
            swapPreviewProgramOnTake = cur.swapPreviewProgramOnTake
        )
        _uiState.update {
            it.copy(profiles = it.profiles + newProf, activeProfileId = newProf.id, statusMessage = "Created profile ${newProf.name}")
        }
        persistCurrentSettings()
    }

    fun selectProfile(profileId: String) {
        val prof = _uiState.value.profiles.firstOrNull { it.id == profileId } ?: return
        _uiState.update {
            it.copy(
                activeProfileId = prof.id,
                videoConfig = prof.videoConfig,
                rtmpServicePreset = prof.rtmpServicePreset,
                lockPreviewTouchWhileLive = prof.lockPreviewTouchWhileLive,
                swapPreviewProgramOnTake = prof.swapPreviewProgramOnTake,
                statusMessage = "Switched profile to ${prof.name}"
            )
        }
        compositor.updateVideoConfig(prof.videoConfig)
        obsBridge.reconfigureVideo(prof.videoConfig)
        persistCurrentSettings()
    }

    fun deleteProfile(profileId: String) {
        val cur = _uiState.value
        if (cur.profiles.size <= 1) return
        val rem = cur.profiles.filterNot { it.id == profileId }
        val nextId = if (cur.activeProfileId == profileId) rem.first().id else cur.activeProfileId
        _uiState.update { it.copy(profiles = rem, activeProfileId = nextId) }
        persistCurrentSettings()
    }

    fun createOrDuplicateCollection(name: String, duplicateCurrent: Boolean) {
        if (name.isBlank()) return
        val trimmed = name.trim()
        val cur = _uiState.value.sceneCollection
        val nextCol = if (duplicateCurrent) {
            cur.copy(id = "col_${System.currentTimeMillis()}", name = trimmed)
        } else {
            val baseScene = SceneModel(
                id = "scene_${System.currentTimeMillis()}",
                name = "Main Scene",
                sources = listOf(SceneEditingHelper.createDefaultSource(SourceType.SCREEN_CAPTURE))
            )
            SceneCollection(
                id = "col_${System.currentTimeMillis()}",
                name = trimmed,
                activeSceneId = baseScene.id,
                scenes = listOf(baseScene)
            )
        }
        applyLoadedCollection(nextCol, "Loaded collection $trimmed")
    }

    fun switchNamedCollection(name: String) {
        val loaded = repository.loadNamedCollection(name) ?: return
        applyLoadedCollection(loaded, "Switched scene collection to ${loaded.name}")
    }

    fun deleteNamedCollection(name: String) {
        val names = _uiState.value.savedCollectionNames
        if (names.size <= 1) return
        repository.deleteNamedCollection(name)
        val refreshed = repository.listSavedCollectionNames()
        _uiState.update { it.copy(savedCollectionNames = refreshed) }
    }

    fun exportObsJsonToUri(uri: Uri, onResult: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val res = runCatching {
                val st = _uiState.value
                val json = ObsJsonImporterExporter.exportToObsJson(
                    collection = st.sceneCollection,
                    canvasWidth = st.videoConfig.canvasWidth,
                    canvasHeight = st.videoConfig.canvasHeight
                )
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                    out.flush()
                } ?: error("Cannot open output stream")
                "Exported OBS JSON safely (no secrets included)"
            }.getOrElse { "Export failed: ${it.message}" }
            scope.launch(Dispatchers.Main.immediate) {
                _uiState.update { it.copy(statusMessage = res) }
                logEvent(res)
                onResult(res)
            }
        }
    }

    fun importObsJsonFromUri(uri: Uri, onResult: (Boolean, List<String>) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val raw = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (raw.isNullOrBlank()) {
                scope.launch(Dispatchers.Main.immediate) { onResult(false, listOf("Empty or unreadable JSON file")) }
                return@launch
            }
            val cfg = _uiState.value.videoConfig
            val parsedRes = runCatching {
                ObsJsonImporterExporter.importFromObsJson(context, raw, cfg.canvasWidth, cfg.canvasHeight)
            }
            scope.launch(Dispatchers.Main.immediate) {
                parsedRes.onSuccess { imported ->
                    applyLoadedCollection(imported.collection, "Imported OBS collection '${imported.collection.name}'")
                    onResult(true, imported.missingAssets + imported.warnings)
                }.onFailure { err ->
                    val msg = err.message ?: "Invalid OBS JSON format"
                    _uiState.update { it.copy(statusMessage = msg) }
                    onResult(false, listOf(msg))
                }
            }
        }
    }

    private fun applyLoadedCollection(col: SceneCollection, status: String) {
        repository.saveSceneCollectionAtomic(col)
        val names = repository.listSavedCollectionNames()
        _uiState.update {
            it.copy(
                sceneCollection = col,
                savedCollectionNames = names,
                previewSceneId = col.activeSceneId,
                programSceneId = col.activeSceneId,
                selectedSourceId = col.scenes.firstOrNull { s -> s.id == col.activeSceneId }?.sources?.firstOrNull()?.id,
                statusMessage = status
            )
        }
        syncCompositorScenes()
        syncActiveCameraSource()
    }

    // 1-Click OAuth Platform Connections, Twitch Device Code, Unified Live Chat, Alerts, Goals
    fun startPlatformOAuth(platform: StreamingPlatform) = platformCoordinator.startPlatformOAuth(platform)
    fun startTwitchDeviceAuth() = platformCoordinator.startTwitchDeviceAuth()
    fun cancelTwitchDeviceAuth() = platformCoordinator.cancelTwitchDeviceAuth()
    fun openExternalUrl(url: String) = platformCoordinator.openExternalUrl(url)
    fun handleOAuthRedirect(uri: Uri) = platformCoordinator.handleOAuthRedirect(uri)
    fun disconnectPlatform(platform: StreamingPlatform) = platformCoordinator.disconnectPlatform(platform)
    fun saveCustomClientId(platform: StreamingPlatform, clientId: String) =
        platformCoordinator.saveCustomClientId(platform, clientId)
    fun setPlatformMultiStreamEnabled(platform: StreamingPlatform, enabled: Boolean) =
        platformCoordinator.setPlatformMultiStreamEnabled(platform, enabled)
    fun searchTwitchCategories(query: String) = platformCoordinator.searchTwitchCategories(query)
    fun updatePlatformBroadcastMeta(
        platform: StreamingPlatform,
        title: String,
        categoryId: String,
        categoryName: String,
        privacyStatus: String
    ) = platformCoordinator.updatePlatformBroadcastMeta(platform, title, categoryId, categoryName, privacyStatus)

    fun triggerStreamAlert(
        type: AlertEventType,
        platform: StreamingPlatform = StreamingPlatform.TWITCH,
        senderName: String,
        detailText: String = ""
    ) {
        val ev = StreamAlertEvent(
            id = "alert_${System.nanoTime()}",
            type = type,
            platform = platform,
            senderName = senderName.ifBlank { "Anonymous Supporter" },
            detailText = detailText.ifBlank { type.label }
        )
        compositor.updateAlertEvent(ev)
        _uiState.update { cur ->
            val updatedCol = if (type == AlertEventType.FOLLOWER || type == AlertEventType.SUBSCRIBER) {
                cur.sceneCollection.copy(
                    scenes = cur.sceneCollection.scenes.map { sc ->
                        sc.copy(
                            sources = sc.sources.map { src ->
                                if (src.type == SourceType.GOAL_BAR) src.copy(goalCurrent = src.goalCurrent + 1) else src
                            }
                        )
                    }
                )
            } else cur.sceneCollection
            val alertHasChime = updatedCol.scenes
                .flatMap { it.sources }
                .any { it.type == SourceType.ALERT_BOX && it.visible && it.alertShowChime }
            if (alertHasChime) {
                audioMixer?.triggerSoundboardPad(SoundboardPad("alert_chime", "Alert Chime", builtInTone = "CHIME", volume = 0.7f))
            }
            cur.copy(
                sceneCollection = updatedCol,
                activeAlertEvent = ev,
                alertHistory = (listOf(ev) + cur.alertHistory).take(15),
                statusMessage = "Alert: ${ev.type.label} · ${ev.senderName}"
            )
        }
        syncCompositorScenes()
        logEvent("Stream Alert: ${ev.type.label} (${ev.senderName})")

        alertClearJob?.cancel()
        alertClearJob = scope.launch {
            delay(ev.durationMs)
            if (_uiState.value.activeAlertEvent?.id == ev.id) {
                compositor.updateAlertEvent(null)
                _uiState.update { it.copy(activeAlertEvent = null) }
            }
        }
    }

    fun triggerTestAlert(type: AlertEventType = AlertEventType.FOLLOWER) {
        val sampleName = when (type) {
            AlertEventType.FOLLOWER -> "GalaxyGamer99"
            AlertEventType.SUBSCRIBER -> "ProCreator_VIP"
            AlertEventType.DONATION -> "$10.00 Super Chat"
            AlertEventType.RAID -> "StreamSquad (+45)"
            AlertEventType.MILESTONE -> "100 Followers Reached!"
        }
        val samplePlat = if (type == AlertEventType.DONATION) StreamingPlatform.YOUTUBE else StreamingPlatform.TWITCH
        triggerStreamAlert(type, samplePlat, sampleName, "Test Broadcast Alert · Mobile Labs")
    }

    fun adjustGoalProgress(sourceId: String, delta: Int) {
        mutateEditableSource(sourceId) { src ->
            src.copy(goalCurrent = (src.goalCurrent + delta).coerceAtLeast(0))
        }
    }

    fun toggleChatDrawer() {
        _uiState.update { it.copy(chatDrawerOpen = !it.chatDrawerOpen) }
    }

    fun sendLiveChatMessage(text: String, targetPlatform: StreamingPlatform? = null) =
        platformCoordinator.sendLiveChatMessage(text, targetPlatform)

    fun clearChatMessages() {
        _uiState.update { it.copy(chatMessages = emptyList()) }
        pushDynamicOverlaysToCompositor()
    }

    fun reloadBrowserSource(sourceId: String) {
        browserController.reloadSource(sourceId)
    }

    fun controlTimer(sourceId: String, action: String) {
        val now = System.currentTimeMillis()
        mutateEditableSource(sourceId) { src ->
            when (action) {
                "start" -> if (!src.timerRunning) {
                    src.copy(timerRunning = true, timerStartedAtMs = now)
                } else src
                "pause" -> if (src.timerRunning) {
                    val elapsed = src.timerElapsedBeforeMs + (now - src.timerStartedAtMs).coerceAtLeast(0L)
                    src.copy(timerRunning = false, timerElapsedBeforeMs = elapsed)
                } else src
                "reset" -> src.copy(timerRunning = false, timerStartedAtMs = 0L, timerElapsedBeforeMs = 0L)
                else -> src
            }
        }
    }

    fun adjustScoreboard(sourceId: String, isHome: Boolean, delta: Int) {
        mutateEditableSource(sourceId) { src ->
            if (isHome) {
                src.copy(scoreHome = (src.scoreHome + delta).coerceAtLeast(0))
            } else {
                src.copy(scoreAway = (src.scoreAway + delta).coerceAtLeast(0))
            }
        }
    }

    fun resetScoreboard(sourceId: String) {
        mutateEditableSource(sourceId) { src -> src.copy(scoreHome = 0, scoreAway = 0) }
    }

    fun setDrawingMode(active: Boolean) {
        _uiState.update { it.copy(drawingModeActive = active) }
        if (active) {
            val st = _uiState.value
            val scene = st.sceneCollection.scenes.firstOrNull { it.id == editableSceneId(st) }
            if (scene != null && scene.sources.none { it.type == SourceType.DRAWING_LAYER }) {
                addSourceToActiveScene(SourceType.DRAWING_LAYER)
            }
        }
    }

    fun setDrawingBrush(colorArgb: Long, widthFraction: Float) {
        _uiState.update {
            it.copy(
                drawingColorArgb = colorArgb,
                drawingBrushWidthFraction = widthFraction.coerceIn(0.002f, 0.03f)
            )
        }
    }

    fun addDrawingStroke(stroke: DrawingStroke) {
        if (stroke.points.isEmpty()) return
        _uiState.update { cur ->
            val updated = (cur.drawingStrokes + stroke).takeLast(120)
            cur.copy(drawingStrokes = updated)
        }
        pushDynamicOverlaysToCompositor()
    }

    fun undoLastDrawingStroke() {
        _uiState.update { cur ->
            cur.copy(drawingStrokes = cur.drawingStrokes.dropLast(1))
        }
        pushDynamicOverlaysToCompositor()
    }

    fun clearDrawingStrokes() {
        _uiState.update { it.copy(drawingStrokes = emptyList()) }
        pushDynamicOverlaysToCompositor()
    }

    fun triggerSoundboardPad(padId: String) {
        val pad = _uiState.value.soundboardPads.firstOrNull { it.id == padId } ?: return
        val mixer = audioMixer
        if (mixer != null) {
            mixer.triggerSoundboardPad(pad)
            logEvent("SFX triggered: ${pad.label}")
        } else {
            _uiState.update { it.copy(statusMessage = "Arm Capture to route Soundboard SFX into stream audio") }
        }
    }

    fun setSoundboardPadVolume(padId: String, volume: Float) {
        val v = volume.coerceIn(0f, 1.5f)
        _uiState.update { cur ->
            cur.copy(soundboardPads = cur.soundboardPads.map { if (it.id == padId) it.copy(volume = v) else it })
        }
        persistCurrentSettings()
    }

    fun renameSoundboardPad(padId: String, newLabel: String) {
        if (newLabel.isBlank()) return
        _uiState.update { cur ->
            cur.copy(soundboardPads = cur.soundboardPads.map { if (it.id == padId) it.copy(label = newLabel.trim()) else it })
        }
        persistCurrentSettings()
    }

    fun importSoundboardAudio(padId: String, uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val (localPath, err) = repository.copySafMediaAsset(uri, "sfx")
            scope.launch(Dispatchers.Main.immediate) {
                if (localPath != null) {
                    _uiState.update { cur ->
                        cur.copy(
                            soundboardPads = cur.soundboardPads.map {
                                if (it.id == padId) it.copy(filePath = localPath, builtInTone = null) else it
                            },
                            statusMessage = "Assigned custom SFX clip to pad"
                        )
                    }
                    persistCurrentSettings()
                } else {
                    _uiState.update { it.copy(statusMessage = err ?: "Failed to load audio clip") }
                }
            }
        }
    }

    fun setPushToTalkHeld(held: Boolean) {
        audioMixer?.pushToTalkHeld = held
        _uiState.update { it.copy(pushToTalkHeld = held) }
    }

    private fun pushDynamicOverlaysToCompositor() {
        val st = _uiState.value
        chatVersionCounter++
        drawingVersionCounter++
        compositor.updateDynamicOverlays(
            chatMessages = st.chatMessages,
            chatVersion = chatVersionCounter,
            drawingStrokes = st.drawingStrokes,
            drawingVersion = drawingVersionCounter
        )
    }

    fun onSystemTrimMemory(level: Int) {
        logEvent("onTrimMemory level=$level")
    }

    fun shutdown() {
        telemetryJob?.cancel()
        platformCoordinator.stopChatClients()
        disarmSession()
        obsBridge.shutdown()
        runCatching { powerManager.removeThermalStatusListener(thermalListener) }
        runCatching { context.unregisterReceiver(batteryReceiver) }
    }

    private fun commitSceneCollection(updated: SceneCollection) {
        _uiState.update {
            it.copy(
                sceneCollection = updated,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty()
            )
        }
        repository.saveSceneCollectionAtomic(updated)
        syncCompositorScenes()
    }

    private fun mutateEditableSource(sourceId: String, transformFn: (SourceModel) -> SourceModel) {
        pushUndo()
        val st = _uiState.value
        val targetId = editableSceneId(st)
        val updated = SceneEditingHelper.updateSourceInScene(st.sceneCollection, targetId, sourceId, transformFn)
        val targetScene = updated.scenes.firstOrNull { it.id == targetId }
        val modSrc = targetScene?.sources?.firstOrNull { it.id == sourceId }
        if (targetScene != null && modSrc != null) {
            obsBridge.syncSourceTransform(targetScene.name, modSrc)
        }
        commitSceneCollection(updated)
    }

    private fun syncCompositorScenes() {
        val st = _uiState.value
        val progScene = st.sceneCollection.scenes.firstOrNull { it.id == st.programSceneId }
            ?: st.sceneCollection.scenes.firstOrNull { it.id == st.sceneCollection.activeSceneId }
        val prevScene = st.sceneCollection.scenes.firstOrNull { it.id == st.previewSceneId } ?: progScene
        compositor.updateScenesForRender(progScene, prevScene, st.studioModeEnabled)
    }

    private fun handleStreamDisconnect(reason: String) {
        if (reconnectCount >= 5) {
            _uiState.update {
                it.copy(
                    sessionPhase = SessionPhase.ERROR,
                    reconnectCountdownSec = 0,
                    errorReason = "$reason (max reconnect attempts reached)",
                    statusMessage = "Stream failed: $reason"
                )
            }
            logEvent("Stream error (gave up): $reason")
            return
        }

        reconnectCount++
        val delaySec = listOf(2, 4, 8, 15, 30)[(reconnectCount - 1).coerceIn(0, 4)]
        logEvent("Stream lost ($reason); reconnecting #$reconnectCount in ${delaySec}s")

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            for (rem in delaySec downTo 1) {
                _uiState.update {
                    it.copy(
                        sessionPhase = SessionPhase.RECONNECTING,
                        reconnectCountdownSec = rem,
                        errorReason = reason,
                        statusMessage = "Reconnecting ($reconnectCount/5) in ${rem}s..."
                    )
                }
                delay(1000L)
            }
            startStreaming()
        }
    }

    private fun cameraProFromSource(src: SourceModel): CameraProSettings = CameraProSettings(
        exposureEv = src.camExposureEv,
        iso = src.camIso,
        focusDistance = src.camFocusDistance,
        whiteBalance = src.camWhiteBalance,
        zoomRatio = src.camZoomRatio,
        torchEnabled = src.camTorchEnabled
    )

    private fun syncActiveCameraSource() {
        val st = _uiState.value
        val prog = st.sceneCollection.scenes.firstOrNull { it.id == st.programSceneId }
        val prev = st.sceneCollection.scenes.firstOrNull { it.id == st.previewSceneId }
        val camSrc = prog?.sources?.firstOrNull { it.type == SourceType.CAMERA_2 && it.visible }
            ?: if (st.studioModeEnabled) prev?.sources?.firstOrNull { it.type == SourceType.CAMERA_2 && it.visible } else null
        if (camSrc == null) {
            cameraController?.stop()
        } else {
            val surf = cameraSurfaceRef
            if (surf != null && st.hasCameraPermission) {
                cameraController?.start(camSrc.useFrontCamera, surf, cameraProFromSource(camSrc))
            }
        }
    }

    private fun markSourceErrored(typeFilter: SourceType?, sourceIdOrPath: String?, err: String) {
        val col = _uiState.value.sceneCollection
        val updatedScenes = col.scenes.map { scene ->
            scene.copy(
                sources = scene.sources.map { src ->
                    val matchesType = typeFilter != null && src.type == typeFilter
                    val matchesId = sourceIdOrPath != null && (src.id == sourceIdOrPath || src.mediaPathOrUri == sourceIdOrPath)
                    if (matchesType || matchesId) src.copy(erroredReason = err) else src
                }
            )
        }
        _uiState.update { it.copy(sceneCollection = col.copy(scenes = updatedScenes)) }
    }

    private fun pushUndo() {
        if (undoStack.size >= 25) undoStack.removeFirst()
        undoStack.addLast(_uiState.value.sceneCollection)
        redoStack.clear()
        _uiState.update { it.copy(canUndo = true, canRedo = false) }
    }

    private fun updateAudioChannel(channelId: String, fn: (AudioChannelState) -> AudioChannelState) {
        _uiState.update { cur ->
            cur.copy(audioChannels = cur.audioChannels.map { if (it.id == channelId) fn(it) else it })
        }
    }

    private fun registerSystemMonitors() {
        runCatching { powerManager.addThermalStatusListener(thermalListener) }
        runCatching { context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
        runCatching { updateThermalStatus(powerManager.currentThermalStatus) }
    }

    private fun updateThermalStatus(status: Int) {
        val label = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "TEMP OK"
            PowerManager.THERMAL_STATUS_LIGHT -> "WARM"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            else -> "SHUTDOWN"
        }
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { powerManager.getThermalHeadroom(10) }.getOrDefault(-1f)
        } else -1f

        val shouldWarn = status >= PowerManager.THERMAL_STATUS_MODERATE || headroom >= 0.85f
        _uiState.update { cur ->
            cur.copy(
                telemetry = cur.telemetry.copy(
                    thermal = ThermalSummary(
                        statusCode = status,
                        statusLabel = label,
                        headroomForecast = headroom,
                        batteryTempC = cachedBatteryTempC,
                        batteryPercent = cachedBatteryPct,
                        isCharging = cachedCharging,
                        shouldWarnBanner = shouldWarn
                    )
                )
            )
        }
    }

    private fun startTelemetryLoop() {
        telemetryJob?.cancel()
        telemetryJob = scope.launch {
            var tick = 0
            while (true) {
                delay(1000L)
                tick++
                sampleTelemetry1Hz(checkDisk = (tick % 10 == 0))
            }
        }
    }

    private fun sampleTelemetry1Hz(checkDisk: Boolean) {
        val cur = _uiState.value
        val compStats = compositor.snapshotStats()
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val pssMb = Debug.getPss().toInt() / 1024
        val nativeHeapMb = (Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)).toInt()
        val freeRamMb = (memInfo.availMem / (1024L * 1024L)).toInt()
        val freeStorageMb = if (checkDisk || cur.telemetry.freeStorageMb == 0L) {
            getAvailableStorageMb()
        } else cur.telemetry.freeStorageMb

        if (checkDisk && cur.recordingPhase == RecordingPhase.RECORDING && freeStorageMb < 200L) {
            logEvent("Free storage low ($freeStorageMb MB); stopping recording safely")
            stopRecording()
        }

        val streamSec = if (cur.sessionPhase == SessionPhase.LIVE && streamStartedAtMs > 0L) {
            (System.currentTimeMillis() - streamStartedAtMs) / 1000L
        } else 0L
        val recSec = if ((cur.recordingPhase == RecordingPhase.RECORDING || cur.recordingPhase == RecordingPhase.PAUSED) && recordStartedAtMs > 0L) {
            val activePauseMs = if (cur.recordingPhase == RecordingPhase.PAUSED && recordPausedAtMs > 0L) {
                System.currentTimeMillis() - recordPausedAtMs
            } else 0L
            ((System.currentTimeMillis() - recordStartedAtMs - recordTotalPausedMs - activePauseMs).coerceAtLeast(0L)) / 1000L
        } else 0L

        val pipe = pipeline
        val mixer = audioMixer
        val liveChannels = if (mixer != null) {
            val polled = mixer.snapshotChannelStates()
            cur.audioChannels.map { prev ->
                val fresh = polled.firstOrNull { it.id == prev.id } ?: prev
                prev.copy(
                    peakDb = fresh.peakDb,
                    peakHoldDb = fresh.peakHoldDb,
                    isClipping = fresh.isClipping,
                    activeRouteLabel = fresh.activeRouteLabel,
                    unavailableReason = fresh.unavailableReason,
                    measuredDspMicros = fresh.measuredDspMicros
                )
            }
        } else cur.audioChannels

        val snapshot = cur.telemetry.copy(
            timestampMs = System.currentTimeMillis(),
            captureCadenceFps = compStats.captureCadenceFps,
            compositorFps = compStats.compositorFps,
            encoderFps = compStats.compositorFps,
            compositorPassMicros = compStats.compositorPassMicros,
            audioDspMicros = mixer?.lastAudioDspMicros ?: 0L,
            outputWidth = cur.videoConfig.outputWidth,
            outputHeight = cur.videoConfig.outputHeight,
            configuredFps = cur.videoConfig.fps,
            actualBitrateKbps = pipe?.getActualBitrateKbps() ?: 0,
            keyframeIntervalSec = cur.videoConfig.keyframeIntervalSec,
            renderSkippedFrames = compStats.renderSkippedFrames + obsBridge.getRenderSkippedFrames(),
            encoderDroppedFrames = pipe?.getEncoderDroppedFrames() ?: 0L,
            networkDroppedFrames = pipe?.getNetworkDroppedFrames() ?: 0L,
            captureGapCount = compStats.captureGapCount,
            encoderQueueDepth = pipe?.getEncoderQueueDepth() ?: 0,
            rtmpBufferedBytes = pipe?.getRtmpBufferedBytes() ?: 0L,
            reconnectCount = reconnectCount,
            processPssMb = pssMb,
            nativeHeapMb = nativeHeapMb,
            freeRamMb = freeRamMb,
            freeStorageMb = freeStorageMb,
            encoderMode = pipe?.encoderMode ?: cur.telemetry.encoderMode,
            streamDurationSec = streamSec,
            recordDurationSec = recSec,
            recordingBytesWritten = pipe?.getRecordingBytesWritten() ?: 0L
        )

        val isActiveOutput = cur.sessionPhase == SessionPhase.LIVE || cur.recordingPhase == RecordingPhase.RECORDING
        val interimSnapshot = snapshot.copy(thermal = cur.telemetry.thermal)
        val adaptiveOutcome = adaptiveEngine.evaluate(interimSnapshot, isActiveOutput)
        compositor.setHeavyFiltersSuspended(adaptiveOutcome.shouldSuspendFilters)

        if (adaptiveOutcome.levelChanged) {
            logEvent("Adaptive Quality -> ${adaptiveOutcome.state.level.shortTag}: ${adaptiveOutcome.state.lastChangeReason}")
            val scaledBitrate = (cur.videoConfig.videoBitrateKbps * adaptiveOutcome.recommendedBitrateScale).toInt().coerceAtLeast(1500)
            if (adaptiveOutcome.recommendedBitrateScale > 0f && adaptiveOutcome.recommendedBitrateScale < 1f) {
                dynamicTargetBitrateKbps = scaledBitrate
                pipe?.updateLiveBitrate(scaledBitrate)
            } else if (adaptiveOutcome.recommendedBitrateScale == 1f) {
                dynamicTargetBitrateKbps = cur.videoConfig.videoBitrateKbps
                pipe?.updateLiveBitrate(cur.videoConfig.videoBitrateKbps)
            }
            if (adaptiveOutcome.shouldSwitchLowOverhead && cur.videoConfig.presetId != PresetId.LOW_OVERHEAD) {
                applyPreset(PresetId.LOW_OVERHEAD)
            }
            if (adaptiveOutcome.shouldDropCamera) {
                cameraController?.stop()
            }
            if (adaptiveOutcome.shouldEmergencyStopStream && cur.sessionPhase == SessionPhase.LIVE) {
                logEvent("CRITICAL THERMAL (L5): Finalizing recording & stopping stream for safety")
                stopStreaming()
                if (cur.recordingPhase == RecordingPhase.RECORDING || cur.recordingPhase == RecordingPhase.PAUSED) {
                    stopRecording()
                }
            }
        } else if (cur.sessionPhase == SessionPhase.LIVE && cur.videoConfig.dynamicBitrateEnabled && pipe != null) {
            val ceilingBitrate = (cur.videoConfig.videoBitrateKbps * adaptiveOutcome.recommendedBitrateScale)
                .toInt().coerceAtLeast(1500)
            val dropsDelta = snapshot.networkDroppedFrames - lastNetworkDropSeen
            lastNetworkDropSeen = snapshot.networkDroppedFrames
            val pressure = pipe.getMaxCongestionPressure()
            val prevAbr = dynamicTargetBitrateKbps.takeIf { it > 0 } ?: ceilingBitrate
            val nextAbr = AdaptiveQualityEngine.evaluateNetworkAbr(
                baseBitrateKbps = ceilingBitrate,
                currentTargetKbps = prevAbr,
                congestionPressure = pressure,
                queueDepth = snapshot.encoderQueueDepth,
                recentDropDelta = dropsDelta
            )
            if (nextAbr != prevAbr) {
                dynamicTargetBitrateKbps = nextAbr
                pipe.updateLiveBitrate(nextAbr)
                val dir = if (nextAbr < prevAbr) "step-down" else "recovery"
                logEvent("Dynamic Bitrate $dir -> $nextAbr kbps (queue=${snapshot.encoderQueueDepth}, pressure=${"%.2f".format(pressure)})")
            }
        }

        val updatedDual = cur.dualDestination.copy(
            bufferedBytes = pipe?.getSecondaryBufferedBytes() ?: 0L,
            droppedFrames = pipe?.getSecondaryDroppedFrames() ?: 0L
        )
        val replayStats = pipe?.replayRingBuffer?.stats()

        platformCoordinator.updatePlatformTelemetry(
            ytBuffered = pipe?.getNamedBufferedBytes("youtube") ?: 0L,
            ytDropped = pipe?.getNamedDroppedFrames("youtube") ?: 0L,
            twBuffered = pipe?.getNamedBufferedBytes("twitch") ?: 0L,
            twDropped = pipe?.getNamedDroppedFrames("twitch") ?: 0L
        )

        val nextReplayBufferedSec = replayStats?.bufferedSeconds ?: 0f
        val nextReplayBufferedKb = replayStats?.bufferedKb ?: 0L
        val nextAbrBitrate = dynamicTargetBitrateKbps.takeIf { it > 0 } ?: cur.videoConfig.videoBitrateKbps
        val echoRisk = mixer?.isSpeakerMicFeedbackRisk() == true

        var committedState = _uiState.value
        _uiState.update { latest ->
            latest.copy(
                telemetry = snapshot,
                audioChannels = liveChannels,
                adaptiveQuality = adaptiveOutcome.state,
                dualDestination = updatedDual,
                abrTargetBitrateKbps = nextAbrBitrate,
                replayBuffer = latest.replayBuffer.copy(
                    bufferedSeconds = nextReplayBufferedSec,
                    bufferedKb = nextReplayBufferedKb
                ),
                speakerMicEchoDetected = echoRisk
            ).also { committedState = it }
        }
        diagnosticsExporter.appendTelemetrySample(committedState)
    }

    private fun persistCurrentSettings() {
        val cur = _uiState.value
        val filterMap = cur.audioChannels.associate { it.id to it.filters }
        val syncMap = cur.audioChannels.associate { it.id to it.syncOffsetMs }
        val updatedProfiles = cur.profiles.map { prof ->
            if (prof.id == cur.activeProfileId) {
                prof.copy(
                    videoConfig = cur.videoConfig,
                    rtmpServicePreset = cur.rtmpServicePreset,
                    lockPreviewTouchWhileLive = cur.lockPreviewTouchWhileLive,
                    swapPreviewProgramOnTake = cur.swapPreviewProgramOnTake
                )
            } else prof
        }
        repository.saveSettingsAtomic(
            SceneCollectionRepository.PersistedSettings(
                videoConfig = cur.videoConfig,
                floatingOverlayEnabled = cur.floatingOverlayEnabled,
                overlayPortraitX = overlayPortraitPos.first,
                overlayPortraitY = overlayPortraitPos.second,
                overlayLandscapeX = overlayLandscapePos.first,
                overlayLandscapeY = overlayLandscapePos.second,
                customRecordingFolderUri = cur.customRecordingFolderUri,
                studioModeEnabled = cur.studioModeEnabled,
                swapPreviewProgramOnTake = cur.swapPreviewProgramOnTake,
                lockPreviewTouchWhileLive = cur.lockPreviewTouchWhileLive,
                audioFilters = filterMap,
                audioSyncOffsetsMs = syncMap,
                profiles = updatedProfiles,
                activeProfileId = cur.activeProfileId,
                savedCollections = cur.savedCollectionNames,
                adaptiveQualityLocked = cur.adaptiveQuality.lockedByUser,
                onboardingCompleted = cur.onboardingCompleted,
                dualDestinationEnabled = cur.dualDestination.enabled,
                replayBufferEnabled = cur.replayBuffer.enabled,
                replayDurationSec = cur.replayBuffer.durationSec,
                soundboardPads = cur.soundboardPads,
                benchmarkRecords = cur.benchmarkRecords
            )
        )
    }

    private fun getAvailableStorageMb(): Long {
        return runCatching {
            val stat = StatFs(Environment.getDataDirectory().path)
            (stat.availableBlocksLong * stat.blockSizeLong) / (1024L * 1024L)
        }.getOrDefault(1024L)
    }

    private fun logEvent(message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "[$stamp] $message"
        _uiState.update { cur ->
            val trimmed = (cur.eventLog + line).takeLast(250)
            cur.copy(eventLog = trimmed)
        }
    }
}
