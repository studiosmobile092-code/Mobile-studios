package com.example.mobilelabs.ui

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R
import com.example.mobilelabs.model.AdaptiveQualityLevel
import com.example.mobilelabs.model.AudioChannelState
import com.example.mobilelabs.model.AudioFilterConfig
import com.example.mobilelabs.model.DockPanelId
import com.example.mobilelabs.model.DrawingStroke
import com.example.mobilelabs.model.GamingScenePreset
import com.example.mobilelabs.model.MobileLabsUiState
import com.example.mobilelabs.model.PresetId
import com.example.mobilelabs.model.PreviewTool
import com.example.mobilelabs.model.RecordingPhase
import com.example.mobilelabs.model.RtmpServicePreset
import com.example.mobilelabs.model.ScreenFitMode
import com.example.mobilelabs.model.SessionPhase
import com.example.mobilelabs.model.SourceModel
import com.example.mobilelabs.model.SourceType
import com.example.mobilelabs.model.StreamingPlatform
import com.example.mobilelabs.model.TransitionType
import com.example.mobilelabs.model.VideoConfig
import com.example.mobilelabs.model.VideoFilterConfig
import com.example.ui.theme.ApertureRed
import com.example.ui.theme.ApertureRedHi
import com.example.ui.theme.ObsBorder
import com.example.ui.theme.ObsHeaderBg
import com.example.ui.theme.ObsSelectionBlue
import com.example.ui.theme.ObsWellBg
import com.example.ui.theme.ObsWindowBg
import com.example.ui.theme.StatusCritical
import com.example.ui.theme.StatusOk
import com.example.ui.theme.StatusWarn
import com.example.ui.theme.StudioText
import com.example.ui.theme.StudioTextDim
import com.example.ui.theme.SurfaceLevel1
import kotlinx.coroutines.delay
import java.util.Locale

data class WorkspaceCallbacks(
    val onAttachPreviewSurface: (Surface?, Int, Int) -> Unit,
    val onArmOrDisarm: () -> Unit,
    val onToggleStream: () -> Unit,
    val onRequestGoLive: () -> Unit,
    val onDismissPreFlightSheet: () -> Unit,
    val onConfirmGoLiveFromPreFlight: () -> Unit,
    val onSetSkipGoLivePreFlight: (Boolean) -> Unit,
    val onSetMultiTrackAudioEnabled: (Boolean) -> Unit,
    val onToggleRecord: () -> Unit,
    val onPauseResumeRecord: () -> Unit,
    val onSelectScene: (String) -> Unit,
    val onCreateScene: (String) -> Unit,
    val onRenameScene: (String, String) -> Unit,
    val onDuplicateScene: (String) -> Unit,
    val onDeleteScene: (String) -> Unit,
    val onMoveScene: (String, Int) -> Unit,
    val onSelectSource: (String) -> Unit,
    val onAddSource: (SourceType) -> Unit,
    val onPickImageForNewSource: () -> Unit,
    val onPickGifForNewSource: () -> Unit,
    val onPickMediaForNewSource: () -> Unit,
    val onRelinkImageSource: (String) -> Unit,
    val onRelinkGifSource: (String) -> Unit,
    val onRelinkMediaSource: (String) -> Unit,
    val onUpdateSourceProperties: (String, (SourceModel) -> SourceModel) -> Unit,
    val onUpdateSourceVideoFilters: (String, VideoFilterConfig) -> Unit,
    val onRemoveSource: (String) -> Unit,
    val onDuplicateSource: (String) -> Unit,
    val onMoveSourceOrder: (String, Int) -> Unit,
    val onToggleSourceVisible: (String) -> Unit,
    val onToggleSourceLock: (String) -> Unit,
    val onSwitchCamera: (String) -> Unit,
    val onDragSourceTransform: (String, Float, Float, Float, Float, Float, Boolean) -> Unit,
    val onUpdateSourceCrop: (String, Float, Float, Float, Float) -> Unit,
    val onNudgeSource: (Float, Float, Float, Float) -> Unit,
    val onFitSelectedToScreen: () -> Unit,
    val onAlignSelectedCenter: () -> Unit,
    val onUnlockLiveTouch: () -> Unit,
    val onSetActiveTool: (PreviewTool) -> Unit,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onAudioVolume: (String, Float) -> Unit,
    val onAudioMute: (String) -> Unit,
    val onAudioLock: (String) -> Unit,
    val onUpdateAudioFilters: (String, AudioFilterConfig) -> Unit,
    val onAudioSyncOffset: (String, Int) -> Unit,
    val onMasterMute: () -> Unit,
    val onTransitionChange: (TransitionType, Int) -> Unit,
    val onToggleStudioMode: () -> Unit,
    val onStudioTake: () -> Unit,
    val onToggleExpandDock: (DockPanelId) -> Unit,
    val onApplyPreset: (PresetId) -> Unit,
    val onUpdateVideoConfig: (VideoConfig) -> Unit,
    val onUpdateScreenFit: (ScreenFitMode, Float) -> Unit,
    val onSaveStreamCredentials: (RtmpServicePreset, String, String?) -> Unit,
    val onSaveSecondaryCredentials: (Boolean, RtmpServicePreset, String, String?) -> Unit,
    val onApplyGamingScenePreset: (GamingScenePreset) -> Unit,
    val onToggleAdaptiveLock: (Boolean) -> Unit,
    val onResetAdaptiveQuality: () -> Unit,
    val onRefreshSamsungAdvisor: () -> Unit,
    val onRunAutoTune: () -> Unit,
    val onTestRtmpReachability: (String, (Boolean, String) -> Unit) -> Unit,
    val onCompleteOnboarding: () -> Unit,
    val onDismissCrashBanner: () -> Unit,
    val onAddBenchmarkRecord: (String, String, Float, Float, String) -> Unit,
    val onDeleteBenchmarkRecord: (String) -> Unit,
    val onPickRecordingFolder: () -> Unit,
    val onResetRecordingFolder: () -> Unit,
    val onRecoverIncompleteRecording: (String) -> Unit,
    val onDeleteIncompleteRecording: (String) -> Unit,
    val onCreateProfile: (String) -> Unit,
    val onSelectProfile: (String) -> Unit,
    val onDeleteProfile: (String) -> Unit,
    val onCreateOrDupCollection: (String, Boolean) -> Unit,
    val onSwitchCollection: (String) -> Unit,
    val onDeleteCollection: (String) -> Unit,
    val onExportObsJson: () -> Unit,
    val onImportObsJson: () -> Unit,
    val onRequestEnableOverlay: (Boolean) -> Unit,
    val onToggleHud: () -> Unit,
    val onRunProbe: () -> Unit,
    val onExportDiagnostics: () -> Unit,
    val onStartPlatformOAuth: (StreamingPlatform) -> Unit,
    val onDisconnectPlatform: (StreamingPlatform) -> Unit,
    val onTogglePlatformMultiStream: (StreamingPlatform, Boolean) -> Unit,
    val onUpdatePlatformBroadcastMeta: (StreamingPlatform, String, String, String, String) -> Unit,
    val onSearchTwitchCategories: (String) -> Unit,
    val onSaveCustomClientId: (StreamingPlatform, String) -> Unit,
    val onStartTwitchDeviceAuth: () -> Unit,
    val onCancelTwitchDeviceAuth: () -> Unit,
    val onOpenExternalUrl: (String) -> Unit,
    val onToggleChatDrawer: () -> Unit,
    val onSendChatMessage: (String, StreamingPlatform?) -> Unit,
    val onClearChatMessages: () -> Unit,
    val onToggleReplayBuffer: (Boolean, Int) -> Unit,
    val onSaveReplayClip: () -> Unit,
    val onCaptureScreenshot: () -> Unit,
    val onReloadBrowserSource: (String) -> Unit,
    val onControlTimer: (String, String) -> Unit,
    val onAdjustScoreboard: (String, Boolean, Int) -> Unit,
    val onResetScoreboard: (String) -> Unit,
    val onTriggerTestAlert: (com.example.mobilelabs.model.AlertEventType) -> Unit,
    val onAdjustGoalProgress: (String, Int) -> Unit,
    val onSetDrawingMode: (Boolean) -> Unit,
    val onSetDrawingBrush: (Long, Float) -> Unit,
    val onAddDrawingStroke: (DrawingStroke) -> Unit,
    val onUndoDrawingStroke: () -> Unit,
    val onClearDrawingStrokes: () -> Unit,
    val onTriggerSoundboardPad: (String) -> Unit,
    val onSetSoundboardPadVolume: (String, Float) -> Unit,
    val onPickSoundboardAudio: (String) -> Unit,
    val onSetPushToTalkHeld: (Boolean) -> Unit,
    val onExitApp: () -> Unit
)

@Composable
fun WorkspaceScreen(
    state: MobileLabsUiState,
    callbacks: WorkspaceCallbacks
) {
    var showSplash by remember { mutableStateOf(true) }
    var docksCollapsed by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showAddSource by remember { mutableStateOf(false) }
    var showAddScenePrompt by remember { mutableStateOf(false) }
    var renameSceneId by remember { mutableStateOf<String?>(null) }
    var editingSourceProps by remember { mutableStateOf<SourceModel?>(null) }
    var editingVideoFilterSrc by remember { mutableStateOf<SourceModel?>(null) }
    var editingAudioFilterCh by remember { mutableStateOf<AudioChannelState?>(null) }
    var confirmStopStream by remember { mutableStateOf(false) }
    var confirmStopRecord by remember { mutableStateOf(false) }

    // Phase 3 & Pro Studio Dialog States
    var showGamingPresets by remember { mutableStateOf(false) }
    var showSamsungAdaptive by remember { mutableStateOf(false) }
    var showOnboardingWizard by remember { mutableStateOf(false) }
    var showBenchmarkKit by remember { mutableStateOf(false) }
    var showAboutLicenses by remember { mutableStateOf(false) }
    var showPrecisionNudge by remember { mutableStateOf(false) }
    var showConnections by remember { mutableStateOf(false) }
    var showProTools by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(350L)
        showSplash = false
    }

    val liveOrRec = state.sessionPhase == SessionPhase.LIVE ||
        state.recordingPhase == RecordingPhase.RECORDING ||
        state.recordingPhase == RecordingPhase.PAUSED
    val isTouchBlockedLive = liveOrRec && !state.studioModeEnabled && state.lockPreviewTouchWhileLive && !state.unlockLiveTouchOverride

    val editableSceneId = if (state.studioModeEnabled) state.previewSceneId else state.sceneCollection.activeSceneId
    val editableScene = state.sceneCollection.scenes.firstOrNull { it.id == editableSceneId }
    val selectedSrc = editableScene?.sources?.firstOrNull { it.id == state.selectedSourceId }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsWindowBg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        val isPortraitOrNarrow = maxWidth < maxHeight || maxWidth < 620.dp

        if (isPortraitOrNarrow) {
            PortraitRotatePanel(state = state)
        } else {
            Column(modifier = Modifier.fillMaxSize().background(ObsWindowBg)) {
                // 1:1 OBS Classic Top Menu Bar + Pro Quick Pills
                ObsTopMenuBar(
                    state = state,
                    callbacks = callbacks,
                    onOpenSettings = { showSettings = true },
                    onOpenDiagnostics = { showDiagnostics = true },
                    onOpenPresets = { showGamingPresets = true },
                    onOpenSamsungAdaptive = { callbacks.onRefreshSamsungAdvisor(); showSamsungAdaptive = true },
                    onOpenWizard = { showOnboardingWizard = true },
                    onOpenBenchmarks = { showBenchmarkKit = true },
                    onOpenAbout = { showAboutLicenses = true },
                    onOpenSourceProps = { selectedSrc?.let { editingSourceProps = it } },
                    onToggleDocksCollapse = { docksCollapsed = !docksCollapsed },
                    onTogglePrecisionNudge = { showPrecisionNudge = !showPrecisionNudge },
                    onOpenConnections = { showConnections = true },
                    onOpenProTools = { showProTools = true }
                )

                // Non-intrusive conditional safety / recovery alerts
                if (state.lastCrashSummary != null) {
                    BannerStrip(
                        text = "Previous session ended unexpectedly: ${state.lastCrashSummary}",
                        actionLabel = "Dismiss",
                        isCritical = true,
                        onAction = callbacks.onDismissCrashBanner
                    )
                }
                if (state.telemetry.thermal.shouldWarnBanner && state.videoConfig.presetId != PresetId.LOW_OVERHEAD) {
                    BannerStrip(
                        text = "Device thermal ${state.telemetry.thermal.statusLabel} (${String.format(Locale.US, "%.1f", state.telemetry.thermal.batteryTempC)}°C). Switch to Low-Overhead.",
                        actionLabel = "Low-Overhead",
                        isCritical = false,
                        onAction = { callbacks.onApplyPreset(PresetId.LOW_OVERHEAD) }
                    )
                }
                if (state.captureStoppedBySystem) {
                    BannerStrip(
                        text = "Capture stopped by Android system UI.",
                        actionLabel = "Re-Arm Capture",
                        isCritical = true,
                        onAction = callbacks.onArmOrDisarm
                    )
                }

                // 1:1 OBS Full-Width Center Preview Bezel (no cluttered left rail eating space!)
                ObsPreviewStage(
                    isCaptureLive = state.sessionPhase != SessionPhase.IDLE &&
                        state.sessionPhase != SessionPhase.ARMING &&
                        state.sessionPhase != SessionPhase.ERROR,
                    studioModeEnabled = state.studioModeEnabled,
                    editableScene = editableScene,
                    programSceneName = state.sceneCollection.scenes.firstOrNull { it.id == state.programSceneId }?.name,
                    selectedSourceId = state.selectedSourceId,
                    multiSelectedSourceIds = state.multiSelectedSourceIds,
                    activeTool = state.activeTool,
                    isTouchBlockedLive = isTouchBlockedLive,
                    selectedSrc = selectedSrc,
                    showPrecisionNudge = showPrecisionNudge,
                    hudExpanded = state.hudExpanded,
                    state = state,
                    callbacks = callbacks,
                    onOpenDiagnostics = { showDiagnostics = true },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )

                // 1:1 OBS Five Classic Bottom Docks: Scenes · Sources · Audio Mixer · Scene Transitions · Controls
                ObsBottomDocksRow(
                    state = state,
                    collapsed = docksCollapsed,
                    callbacks = DockCallbacks(
                        onToggleCollapse = { docksCollapsed = !docksCollapsed },
                        onToggleExpandDock = callbacks.onToggleExpandDock,
                        onSelectScene = callbacks.onSelectScene,
                        onAddScene = { showAddScenePrompt = true },
                        onRenameScene = { renameSceneId = it },
                        onDuplicateScene = callbacks.onDuplicateScene,
                        onDeleteScene = callbacks.onDeleteScene,
                        onMoveScene = callbacks.onMoveScene,
                        onSelectSource = callbacks.onSelectSource,
                        onToggleSourceVisible = callbacks.onToggleSourceVisible,
                        onToggleSourceLock = callbacks.onToggleSourceLock,
                        onSwitchCamera = callbacks.onSwitchCamera,
                        onMoveSource = callbacks.onMoveSourceOrder,
                        onDuplicateSource = callbacks.onDuplicateSource,
                        onRemoveSource = callbacks.onRemoveSource,
                        onOpenAddSource = { showAddSource = true },
                        onOpenSourceProperties = { editingSourceProps = it },
                        onOpenVideoFilters = { editingVideoFilterSrc = it },
                        onOpenScreenFit = { showSettings = true },
                        onAudioVolumeChange = callbacks.onAudioVolume,
                        onAudioMuteToggle = callbacks.onAudioMute,
                        onAudioLockToggle = callbacks.onAudioLock,
                        onOpenAudioFilters = { editingAudioFilterCh = it },
                        onMasterMuteToggle = callbacks.onMasterMute,
                        onTransitionChange = callbacks.onTransitionChange,
                        onStudioTake = callbacks.onStudioTake,
                        onToggleStudioMode = callbacks.onToggleStudioMode,
                        onArmOrDisarm = callbacks.onArmOrDisarm,
                        onToggleStream = {
                            val busy = state.sessionPhase == SessionPhase.LIVE ||
                                state.sessionPhase == SessionPhase.CONNECTING ||
                                state.sessionPhase == SessionPhase.RECONNECTING
                            if (busy) confirmStopStream = true else callbacks.onRequestGoLive()
                        },
                        onToggleRecord = {
                            if (state.recordingPhase == RecordingPhase.RECORDING || state.recordingPhase == RecordingPhase.PAUSED) {
                                confirmStopRecord = true
                            } else {
                                callbacks.onToggleRecord()
                            }
                        },
                        onPauseResumeRecord = callbacks.onPauseResumeRecord,
                        onSaveReplay = callbacks.onSaveReplayClip,
                        onOpenConnections = { showConnections = true },
                        onOpenDiagnostics = { showDiagnostics = true },
                        onOpenSettings = { showSettings = true },
                        onExitApp = callbacks.onExitApp
                    )
                )

                // 1:1 OBS Classic Bottom Status Bar (isolated leaf composable for zero-lag updates)
                ObsBottomStatusBar(
                    state = state,
                    onToggleHud = callbacks.onToggleHud,
                    onOpenSamsungAdaptive = { callbacks.onRefreshSamsungAdvisor(); showSamsungAdaptive = true }
                )
            }
        }

        if (state.chatDrawerOpen) {
            LiveChatOverlayDrawer(
                state = state,
                onClose = callbacks.onToggleChatDrawer,
                onSendChat = callbacks.onSendChatMessage,
                onClearChat = callbacks.onClearChatMessages,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }

        AnimatedVisibility(visible = showSplash, exit = fadeOut(animationSpec = tween(durationMillis = 140))) {
            Box(modifier = Modifier.fillMaxSize().background(ApertureRed), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(painter = painterResource(R.drawable.mobile_labs_logo), contentDescription = "Mobile Labs logo", modifier = Modifier.size(120.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("MOBILE LABS", style = MaterialTheme.typography.titleMedium, color = StudioText)
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            state = state,
            callbacks = SettingsDialogCallbacks(
                onDismiss = { showSettings = false },
                onApplyPreset = callbacks.onApplyPreset,
                onUpdateVideoConfig = callbacks.onUpdateVideoConfig,
                onUpdateScreenFit = callbacks.onUpdateScreenFit,
                onSaveStreamCredentials = callbacks.onSaveStreamCredentials,
                onSaveSecondaryCredentials = callbacks.onSaveSecondaryCredentials,
                onToggleAdaptiveLock = callbacks.onToggleAdaptiveLock,
                onPickRecordingFolder = callbacks.onPickRecordingFolder,
                onResetRecordingFolder = callbacks.onResetRecordingFolder,
                onCreateProfile = callbacks.onCreateProfile,
                onSelectProfile = callbacks.onSelectProfile,
                onDeleteProfile = callbacks.onDeleteProfile,
                onCreateOrDupCollection = callbacks.onCreateOrDupCollection,
                onSwitchCollection = callbacks.onSwitchCollection,
                onDeleteCollection = callbacks.onDeleteCollection,
                onExportObsJson = callbacks.onExportObsJson,
                onImportObsJson = callbacks.onImportObsJson,
                onRequestEnableOverlay = callbacks.onRequestEnableOverlay,
                onOpenAboutLicenses = { showSettings = false; showAboutLicenses = true },
                onAudioSyncOffset = callbacks.onAudioSyncOffset,
                onSetMultiTrackAudioEnabled = callbacks.onSetMultiTrackAudioEnabled,
                onSetSkipGoLivePreFlight = callbacks.onSetSkipGoLivePreFlight
            )
        )
    }

    if (showDiagnostics) {
        DiagnosticsAndProbesDialog(
            state = state,
            onDismiss = { showDiagnostics = false },
            onRunProbe = callbacks.onRunProbe,
            onExportZip = callbacks.onExportDiagnostics,
            onRecoverIncomplete = callbacks.onRecoverIncompleteRecording,
            onDeleteIncomplete = callbacks.onDeleteIncompleteRecording
        )
    }

    if (showAddSource) {
        AddSourcePhase2Dialog(
            onDismiss = { showAddSource = false },
            onAddStandardSource = callbacks.onAddSource,
            onPickImageFile = callbacks.onPickImageForNewSource,
            onPickGifFile = callbacks.onPickGifForNewSource,
            onPickMediaFile = callbacks.onPickMediaForNewSource
        )
    }

    if (showConnections) {
        ConnectionsAndMultiStreamDialog(
            state = state,
            onDismiss = { showConnections = false },
            onStartOAuth = callbacks.onStartPlatformOAuth,
            onDisconnect = callbacks.onDisconnectPlatform,
            onToggleMultiStream = callbacks.onTogglePlatformMultiStream,
            onUpdateBroadcastMeta = callbacks.onUpdatePlatformBroadcastMeta,
            onSearchTwitchCategories = callbacks.onSearchTwitchCategories,
            onSaveCustomClientId = callbacks.onSaveCustomClientId,
            onStartTwitchDeviceAuth = callbacks.onStartTwitchDeviceAuth,
            onCancelTwitchDeviceAuth = callbacks.onCancelTwitchDeviceAuth,
            onOpenExternalUrl = callbacks.onOpenExternalUrl,
            onOpenCustomRtmpSettings = { showSettings = true }
        )
    }

    if (showProTools) {
        ProToolsDialog(
            state = state,
            onDismiss = { showProTools = false },
            onToggleReplayBuffer = callbacks.onToggleReplayBuffer,
            onSaveReplayClip = callbacks.onSaveReplayClip,
            onCaptureScreenshot = callbacks.onCaptureScreenshot,
            onTriggerSoundboardPad = callbacks.onTriggerSoundboardPad,
            onSetSoundboardPadVolume = callbacks.onSetSoundboardPadVolume,
            onPickSoundboardAudio = callbacks.onPickSoundboardAudio,
            onSetPushToTalkHeld = callbacks.onSetPushToTalkHeld,
            onSetDrawingMode = callbacks.onSetDrawingMode,
            onSetDrawingBrush = callbacks.onSetDrawingBrush,
            onUndoDrawingStroke = callbacks.onUndoDrawingStroke,
            onClearDrawingStrokes = callbacks.onClearDrawingStrokes,
            onControlTimer = callbacks.onControlTimer,
            onAdjustScoreboard = callbacks.onAdjustScoreboard,
            onResetScoreboard = callbacks.onResetScoreboard,
            onTriggerTestAlert = callbacks.onTriggerTestAlert,
            onAdjustGoalProgress = callbacks.onAdjustGoalProgress
        )
    }

    if (state.showPreFlightSheet) {
        GoLivePreFlightDialog(
            state = state,
            onDismiss = callbacks.onDismissPreFlightSheet,
            onConfirmGoLive = callbacks.onConfirmGoLiveFromPreFlight,
            onSetSkipPreFlight = callbacks.onSetSkipGoLivePreFlight,
            onOpenConnections = { showConnections = true },
            onOpenSettings = { showSettings = true }
        )
    }

    if (showGamingPresets) {
        GamingScenePresetsDialog(
            onDismiss = { showGamingPresets = false },
            onApplyPreset = callbacks.onApplyGamingScenePreset
        )
    }

    if (showSamsungAdaptive) {
        SamsungAndAdaptiveDialog(
            state = state,
            onDismiss = { showSamsungAdaptive = false },
            onToggleAdaptiveLock = callbacks.onToggleAdaptiveLock,
            onResetAdaptive = callbacks.onResetAdaptiveQuality,
            onRefreshSamsung = callbacks.onRefreshSamsungAdvisor
        )
    }

    if (showOnboardingWizard) {
        OnboardingAndAutoTuneDialog(
            state = state,
            onDismiss = { showOnboardingWizard = false },
            onRunAutoTune = callbacks.onRunAutoTune,
            onTestRtmp = callbacks.onTestRtmpReachability,
            onOpenPresets = { showOnboardingWizard = false; showGamingPresets = true },
            onOpenStreamSettings = { showOnboardingWizard = false; showSettings = true },
            onArmCapture = callbacks.onArmOrDisarm,
            onCompleteOnboarding = callbacks.onCompleteOnboarding
        )
    }

    if (showBenchmarkKit) {
        BenchmarkKitDialog(
            state = state,
            onDismiss = { showBenchmarkKit = false },
            onLogBenchmark = callbacks.onAddBenchmarkRecord,
            onDeleteBenchmark = callbacks.onDeleteBenchmarkRecord,
            onExportDiagnosticsZip = callbacks.onExportDiagnostics
        )
    }

    if (showAboutLicenses) {
        LicensesAndAboutDialog(
            state = state,
            onDismiss = { showAboutLicenses = false }
        )
    }

    editingSourceProps?.let { snap ->
        val src = editableScene?.sources?.firstOrNull { it.id == snap.id } ?: snap
        SourcePropertiesDialog(
            source = src,
            onDismiss = { editingSourceProps = null },
            onUpdateSource = { fn -> callbacks.onUpdateSourceProperties(src.id, fn) },
            onRelinkImage = { callbacks.onRelinkImageSource(it); editingSourceProps = null },
            onRelinkGif = { callbacks.onRelinkGifSource(it); editingSourceProps = null },
            onRelinkMedia = { callbacks.onRelinkMediaSource(it); editingSourceProps = null },
            onReloadBrowserSource = callbacks.onReloadBrowserSource,
            onControlTimer = callbacks.onControlTimer,
            onAdjustScoreboard = callbacks.onAdjustScoreboard,
            onResetScoreboard = callbacks.onResetScoreboard,
            onAdjustGoalProgress = callbacks.onAdjustGoalProgress,
            onTriggerTestAlert = callbacks.onTriggerTestAlert,
            onOpenLiveChat = callbacks.onToggleChatDrawer,
            onClearDrawing = callbacks.onClearDrawingStrokes
        )
    }

    editingVideoFilterSrc?.let { snap ->
        val src = editableScene?.sources?.firstOrNull { it.id == snap.id } ?: snap
        VideoFiltersDialog(
            source = src,
            compositorMicros = state.telemetry.compositorPassMicros,
            onDismiss = { editingVideoFilterSrc = null },
            onSave = { f -> callbacks.onUpdateSourceVideoFilters(src.id, f) }
        )
    }

    editingAudioFilterCh?.let { snap ->
        val ch = state.audioChannels.firstOrNull { it.id == snap.id } ?: snap
        AudioFiltersDialog(
            channel = ch,
            onDismiss = { editingAudioFilterCh = null },
            onSave = { f -> callbacks.onUpdateAudioFilters(ch.id, f) }
        )
    }

    if (showAddScenePrompt) {
        val nextNum = state.sceneCollection.scenes.size + 1
        var sceneName by remember { mutableStateOf("Scene $nextNum") }
        AlertDialog(
            onDismissRequest = { showAddScenePrompt = false },
            containerColor = SurfaceLevel1,
            title = { Text("Create New Scene", color = StudioText) },
            text = {
                OutlinedTextField(
                    value = sceneName,
                    onValueChange = { sceneName = it },
                    label = { Text("Scene Name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        callbacks.onCreateScene(sceneName)
                        showAddScenePrompt = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ObsSelectionBlue)
                ) { Text("Create", color = StudioText) }
            },
            dismissButton = { TextButton(onClick = { showAddScenePrompt = false }) { Text("Cancel", color = StudioTextDim) } }
        )
    }

    renameSceneId?.let { scId ->
        val existing = state.sceneCollection.scenes.firstOrNull { it.id == scId }?.name.orEmpty()
        var renameText by remember(scId) { mutableStateOf(existing) }
        AlertDialog(
            onDismissRequest = { renameSceneId = null },
            containerColor = SurfaceLevel1,
            title = { Text("Rename Scene", color = StudioText) },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it }, label = { Text("Scene Name") }, singleLine = true)
            },
            confirmButton = {
                Button(
                    onClick = {
                        callbacks.onRenameScene(scId, renameText)
                        renameSceneId = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ObsSelectionBlue)
                ) { Text("Rename", color = StudioText) }
            },
            dismissButton = { TextButton(onClick = { renameSceneId = null }) { Text("Cancel", color = StudioTextDim) } }
        )
    }

    if (confirmStopStream) {
        AlertDialog(
            onDismissRequest = { confirmStopStream = false },
            containerColor = SurfaceLevel1,
            title = { Text("Stop Streaming?", color = StudioText) },
            text = { Text("End the live RTMP stream now? Any active local recording continues unaffected.", color = StudioTextDim) },
            confirmButton = {
                Button(
                    onClick = { confirmStopStream = false; callbacks.onToggleStream() },
                    colors = ButtonDefaults.buttonColors(containerColor = ApertureRedHi)
                ) { Text("Stop Stream", color = StudioText) }
            },
            dismissButton = { TextButton(onClick = { confirmStopStream = false }) { Text("Keep Live", color = StudioTextDim) } }
        )
    }

    if (confirmStopRecord) {
        AlertDialog(
            onDismissRequest = { confirmStopRecord = false },
            containerColor = SurfaceLevel1,
            title = { Text("Stop Recording?", color = StudioText) },
            text = { Text("Finalize the current recording and publish it to Movies/Mobile Labs?", color = StudioTextDim) },
            confirmButton = {
                Button(
                    onClick = { confirmStopRecord = false; callbacks.onToggleRecord() },
                    colors = ButtonDefaults.buttonColors(containerColor = ApertureRed)
                ) { Text("Stop & Save", color = StudioText) }
            },
            dismissButton = { TextButton(onClick = { confirmStopRecord = false }) { Text("Continue", color = StudioTextDim) } }
        )
    }
}

@Composable
private fun ObsTopMenuBar(
    state: MobileLabsUiState,
    callbacks: WorkspaceCallbacks,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenSamsungAdaptive: () -> Unit,
    onOpenWizard: () -> Unit,
    onOpenBenchmarks: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenSourceProps: () -> Unit,
    onToggleDocksCollapse: () -> Unit,
    onTogglePrecisionNudge: () -> Unit,
    onOpenConnections: () -> Unit,
    onOpenProTools: () -> Unit
) {
    var openMenu by remember { mutableStateOf<String?>(null) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(ObsWindowBg)
            .border(0.5.dp, ObsBorder)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        ObsMenuButton("File", openMenu == "File", { openMenu = "File" }, { openMenu = null }) {
            DropdownMenuItem(text = { Text("1-Click Connect (YouTube / Twitch)...", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; onOpenConnections() })
            DropdownMenuItem(text = { Text("Snap Canvas PNG Screenshot", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; callbacks.onCaptureScreenshot() })
            DropdownMenuItem(text = { Text("Show Recordings & Probes", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; onOpenDiagnostics() })
            DropdownMenuItem(text = { Text("Settings", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; onOpenSettings() })
            HorizontalDivider(color = ObsBorder)
            DropdownMenuItem(text = { Text("Exit", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; callbacks.onExitApp() })
        }

        ObsMenuButton("Edit", openMenu == "Edit", { openMenu = "Edit" }, { openMenu = null }) {
            DropdownMenuItem(text = { Text("Undo", fontSize = 12.sp, color = if (state.canUndo) StudioText else StudioTextDim) }, enabled = state.canUndo, onClick = { openMenu = null; callbacks.onUndo() })
            DropdownMenuItem(text = { Text("Redo", fontSize = 12.sp, color = if (state.canRedo) StudioText else StudioTextDim) }, enabled = state.canRedo, onClick = { openMenu = null; callbacks.onRedo() })
            HorizontalDivider(color = ObsBorder)
            DropdownMenuItem(text = { Text("Tool: Select / Move", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; callbacks.onSetActiveTool(PreviewTool.SELECT_MOVE) })
            DropdownMenuItem(text = { Text("Tool: Crop", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; callbacks.onSetActiveTool(PreviewTool.CROP) })
            DropdownMenuItem(text = { Text("Fit Source to Screen", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; callbacks.onFitSelectedToScreen() })
            DropdownMenuItem(text = { Text("Center Selected Source", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; callbacks.onAlignSelectedCenter() })
            DropdownMenuItem(text = { Text("Toggle Precision Nudge Bar", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; onTogglePrecisionNudge() })
            DropdownMenuItem(text = { Text("Source Properties", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; onOpenSourceProps() })
        }

        ObsMenuButton("View", openMenu == "View", { openMenu = "View" }, { openMenu = null }) {
            DropdownMenuItem(text = { Text(if (state.studioModeEnabled) "✓ Studio Mode" else "Studio Mode", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; callbacks.onToggleStudioMode() })
            DropdownMenuItem(text = { Text(if (state.chatDrawerOpen) "✓ Live Chat Drawer" else "Live Chat Drawer", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; callbacks.onToggleChatDrawer() })
            DropdownMenuItem(text = { Text(if (state.drawingModeActive) "✓ Screen Ink Drawing" else "Screen Ink Drawing", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; callbacks.onSetDrawingMode(!state.drawingModeActive) })
            DropdownMenuItem(text = { Text(if (state.hudExpanded) "✓ Diagnostics HUD" else "Diagnostics HUD", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; callbacks.onToggleHud() })
            DropdownMenuItem(text = { Text("Collapse / Expand Bottom Docks", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; onToggleDocksCollapse() })
            DropdownMenuItem(text = { Text(if (state.floatingOverlayEnabled) "✓ Floating Aperture Control" else "Floating Aperture Control", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; callbacks.onRequestEnableOverlay(!state.floatingOverlayEnabled) })
        }

        ObsMenuButton("Profile", openMenu == "Profile", { openMenu = "Profile" }, { openMenu = null }) {
            state.profiles.forEach { prof ->
                val mark = if (prof.id == state.activeProfileId) "✓ " else ""
                DropdownMenuItem(text = { Text("$mark${prof.name}", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; callbacks.onSelectProfile(prof.id) })
            }
            HorizontalDivider(color = ObsBorder)
            DropdownMenuItem(text = { Text("Manage Profiles...", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; onOpenSettings() })
        }

        ObsMenuButton("Scene Collection", openMenu == "Scene Collection", { openMenu = "Scene Collection" }, { openMenu = null }) {
            state.savedCollectionNames.forEach { name ->
                val mark = if (name == state.sceneCollection.name) "✓ " else ""
                DropdownMenuItem(text = { Text("$mark$name", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; callbacks.onSwitchCollection(name) })
            }
            HorizontalDivider(color = ObsBorder)
            DropdownMenuItem(text = { Text("Gaming Scene Presets (M3.2)...", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; onOpenPresets() })
            DropdownMenuItem(text = { Text("Export OBS JSON...", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; callbacks.onExportObsJson() })
            DropdownMenuItem(text = { Text("Import OBS JSON...", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; callbacks.onImportObsJson() })
        }

        ObsMenuButton("Tools", openMenu == "Tools", { openMenu = "Tools" }, { openMenu = null }) {
            DropdownMenuItem(text = { Text("Pro Creator Tools (Replay / SFX / Ink)...", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; onOpenProTools() })
            DropdownMenuItem(text = { Text("Auto-Tune & Quick Setup Wizard", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; onOpenWizard() })
            DropdownMenuItem(text = { Text("Adaptive Quality & Samsung One UI Advisor", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; onOpenSamsungAdaptive() })
            DropdownMenuItem(text = { Text("Device & MediaCodec Probe", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; onOpenDiagnostics() })
            DropdownMenuItem(text = { Text("Mobile Labs Benchmark Kit (§14.4)", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; onOpenBenchmarks() })
        }

        ObsMenuButton("Help", openMenu == "Help", { openMenu = "Help" }, { openMenu = null }) {
            DropdownMenuItem(text = { Text("Export Redacted Diagnostics ZIP", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; callbacks.onExportDiagnostics() })
            DropdownMenuItem(text = { Text("About Mobile Labs & GPL-2.0 Source Offer", fontSize = 12.sp, color = StudioText) }, onClick = { openMenu = null; onOpenAbout() })
        }

        Spacer(Modifier.weight(1f))

        // Compact Pro Quick Action Pills (OBS Header Bar)
        val connectedCount = listOf(state.youtubeConnection.connected, state.twitchConnection.connected).count { it }
        TopBarPill(
            text = if (connectedCount > 0) "YT/TW ($connectedCount)" else "Connect YT/TW",
            active = connectedCount > 0,
            activeColor = StatusOk.copy(alpha = 0.25f),
            testTag = "topbar_connections_pill",
            onClick = onOpenConnections
        )
        Spacer(Modifier.width(4.dp))
        TopBarPill(
            text = if (state.chatMessages.isEmpty()) "Chat" else "Chat (${state.chatMessages.size})",
            active = state.chatDrawerOpen,
            activeColor = ObsSelectionBlue,
            testTag = "topbar_chat_pill",
            onClick = callbacks.onToggleChatDrawer
        )
        Spacer(Modifier.width(4.dp))
        TopBarPill(
            text = "Pro / SFX",
            active = state.replayBuffer.enabled,
            activeColor = ApertureRed.copy(alpha = 0.65f),
            testTag = "topbar_pro_tools_pill",
            onClick = onOpenProTools
        )
        Spacer(Modifier.width(4.dp))
        TopBarPill(
            text = if (state.drawingModeActive) "Ink: ON" else "Ink",
            active = state.drawingModeActive,
            activeColor = StatusWarn.copy(alpha = 0.45f),
            testTag = "topbar_ink_pill",
            onClick = { callbacks.onSetDrawingMode(!state.drawingModeActive) }
        )
        if (state.sessionPhase != SessionPhase.IDLE && state.sessionPhase != SessionPhase.ERROR) {
            Spacer(Modifier.width(4.dp))
            TopBarPill(
                text = "Shot",
                active = false,
                activeColor = ObsSelectionBlue,
                testTag = "topbar_screenshot_pill",
                onClick = callbacks.onCaptureScreenshot
            )
        }

        if (!state.onboardingCompleted) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = "Wizard",
                fontSize = 10.sp,
                color = StudioText,
                modifier = Modifier
                    .background(ObsSelectionBlue, RoundedCornerShape(2.dp))
                    .clickable { onOpenWizard() }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag("quick_setup_pill")
            )
        }

        Spacer(Modifier.width(6.dp))
        Text(
            text = "Mobile Labs",
            fontSize = 11.sp,
            color = StudioTextDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TopBarPill(
    text: String,
    active: Boolean,
    activeColor: Color,
    testTag: String,
    onClick: () -> Unit
) {
    Text(
        text = text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        color = StudioText,
        modifier = Modifier
            .background(if (active) activeColor else ObsWellBg, RoundedCornerShape(3.dp))
            .border(0.5.dp, ObsBorder, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 2.dp)
            .testTag(testTag)
    )
}

@Composable
private fun ObsMenuButton(
    label: String,
    expanded: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    items: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Box {
        Text(
            text = label,
            fontSize = 12.sp,
            color = StudioText,
            modifier = Modifier
                .background(if (expanded) ObsSelectionBlue else Color.Transparent, RoundedCornerShape(2.dp))
                .clickable(onClick = onOpen)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            modifier = Modifier.background(ObsHeaderBg),
            content = items
        )
    }
}

@Composable
private fun ObsPreviewStage(
    isCaptureLive: Boolean,
    studioModeEnabled: Boolean,
    editableScene: com.example.mobilelabs.model.SceneModel?,
    programSceneName: String?,
    selectedSourceId: String?,
    multiSelectedSourceIds: Set<String>,
    activeTool: PreviewTool,
    isTouchBlockedLive: Boolean,
    selectedSrc: SourceModel?,
    showPrecisionNudge: Boolean,
    hudExpanded: Boolean,
    state: MobileLabsUiState,
    callbacks: WorkspaceCallbacks,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(ObsWindowBg)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        val canvasAspect = if (studioModeEnabled) 32f / 9f else 16f / 9f
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(canvasAspect, matchHeightConstraintsFirst = true)
                .background(Color.Black)
                .border(1.dp, ObsBorder)
        ) {
            if (isCaptureLive) {
                // Isolated SurfaceView key guarantees 1 Hz telemetry ticks never rebuild or lag the GL preview!
                key(isCaptureLive) {
                    AndroidView(
                        factory = { ctx ->
                            SurfaceView(ctx).apply {
                                holder.addCallback(object : SurfaceHolder.Callback {
                                    override fun surfaceCreated(holder: SurfaceHolder) {
                                        callbacks.onAttachPreviewSurface(holder.surface, width, height)
                                    }
                                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                                        callbacks.onAttachPreviewSurface(holder.surface, w, h)
                                    }
                                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                                        callbacks.onAttachPreviewSurface(null, 0, 0)
                                    }
                                })
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                        if (studioModeEnabled) {
                            Text(
                                text = "PREVIEW: ${editableScene?.name ?: "Scene"}",
                                fontSize = 11.sp,
                                color = StudioTextDim.copy(alpha = 0.65f),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                    }
                    if (studioModeEnabled) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxHeight().border(0.5.dp, ApertureRedHi),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Text(
                                text = "PROGRAM: ${programSceneName ?: "Scene"}",
                                fontSize = 11.sp,
                                color = ApertureRedHi.copy(alpha = 0.85f),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                    }
                }
            }

            // Touch canvas interaction layer
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    PreviewTouchEditorOverlay(
                        scene = editableScene,
                        selectedSourceId = selectedSourceId,
                        multiSelectedIds = multiSelectedSourceIds,
                        activeTool = activeTool,
                        isTouchBlockedLive = isTouchBlockedLive,
                        screenFitMode = state.videoConfig.screenFitMode,
                        drawingModeActive = state.drawingModeActive,
                        drawingColorArgb = state.drawingColorArgb,
                        drawingBrushWidthFraction = state.drawingBrushWidthFraction,
                        onAddDrawingStroke = callbacks.onAddDrawingStroke,
                        onSelectSource = callbacks.onSelectSource,
                        onUnlockLiveTouch = callbacks.onUnlockLiveTouch,
                        onDragSourceTransform = callbacks.onDragSourceTransform,
                        onUpdateCrop = callbacks.onUpdateSourceCrop
                    )
                }
                if (studioModeEnabled) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .border(1.dp, ApertureRedHi.copy(alpha = 0.7f))
                    )
                }
            }

            if (studioModeEnabled) {
                Surface(
                    color = ApertureRed.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(2.dp),
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                ) {
                    Text("PROGRAM", fontSize = 10.sp, color = StudioText, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }

        val showNudgeOverlay = !state.drawingModeActive && (showPrecisionNudge ||
            (selectedSrc != null && selectedSrc.type.isVisual && selectedSrc.type != SourceType.SCREEN_CAPTURE && !selectedSrc.locked))
        if (showNudgeOverlay && selectedSrc != null && selectedSrc.type.isVisual) {
            SelectedSourceNudgeBar(
                source = selectedSrc,
                blockedLive = isTouchBlockedLive,
                onNudge = callbacks.onNudgeSource,
                onFitToScreen = callbacks.onFitSelectedToScreen,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
            )
        }

        if (state.drawingModeActive) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(8.dp)
                    .background(Color(0xE616161A), RoundedCornerShape(6.dp))
                    .border(1.dp, StatusWarn, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("INK MODE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = StatusWarn)
                listOf(0xFFFF3B30L, 0xFFF5A524L, 0xFF43B581L, 0xFF56B4E9L, 0xFFFFFFFFL).forEach { col ->
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(Color(col.toInt()), CircleShape)
                            .border(if (state.drawingColorArgb == col) 2.dp else 0.5.dp, Color.White, CircleShape)
                            .clickable { callbacks.onSetDrawingBrush(col, state.drawingBrushWidthFraction) }
                    )
                }
                TextButton(onClick = callbacks.onUndoDrawingStroke) { Text("Undo", fontSize = 11.sp, color = StudioText) }
                TextButton(onClick = callbacks.onClearDrawingStrokes) { Text("Clear", fontSize = 11.sp, color = ApertureRedHi) }
                Button(
                    onClick = { callbacks.onSetDrawingMode(false) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ObsSelectionBlue),
                    modifier = Modifier.height(26.dp)
                ) { Text("Done", fontSize = 11.sp, color = StudioText) }
            }
        }

        state.activeAlertEvent?.let { ev ->
            Surface(
                color = Color(ev.type.accentArgb.toInt()).copy(alpha = 0.90f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
            ) {
                Text(
                    text = "${ev.type.label}: ${ev.senderName}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        if (hudExpanded) {
            DiagnosticsHudCard(
                state = state,
                onExport = callbacks.onExportDiagnostics,
                onOpenFullProbes = onOpenDiagnostics,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            )
        }
    }
}

/**
 * 1:1 OBS Studio bottom telemetry status bar:
 * Keeps 1 Hz telemetry isolated inside a tiny flat footer bar so the main workspace never lags.
 */
@Composable
private fun ObsBottomStatusBar(
    state: MobileLabsUiState,
    onToggleHud: () -> Unit,
    onOpenSamsungAdaptive: () -> Unit
) {
    val t = state.telemetry
    val totalDrops = t.renderSkippedFrames + t.encoderDroppedFrames + t.networkDroppedFrames
    val isLive = state.sessionPhase == SessionPhase.LIVE
    val isRec = state.recordingPhase == RecordingPhase.RECORDING
    val isPaused = state.recordingPhase == RecordingPhase.PAUSED

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(ObsWindowBg)
            .border(0.5.dp, ObsBorder)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = state.statusMessage,
            fontSize = 11.sp,
            color = if (state.errorReason != null) StatusCritical else StudioTextDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        // Adaptive Quality badge (L0-L5)
        Text(
            text = "${state.adaptiveQuality.level.shortTag}${if (state.adaptiveQuality.lockedByUser) " 🔒" else ""}",
            fontSize = 11.sp,
            color = if (state.adaptiveQuality.level == AdaptiveQualityLevel.L0_NORMAL) StatusOk else StatusWarn,
            modifier = Modifier.clickable { onOpenSamsungAdaptive() }
        )

        if (state.youtubeConnection.outputPhase == SessionPhase.LIVE || state.twitchConnection.outputPhase == SessionPhase.LIVE) {
            val activePlatforms = buildList {
                if (state.youtubeConnection.outputPhase == SessionPhase.LIVE) add("YT")
                if (state.twitchConnection.outputPhase == SessionPhase.LIVE) add("TW")
            }
            Text(
                text = "MULTI: ${activePlatforms.joinToString("+")}",
                fontSize = 11.sp,
                color = StatusOk,
                fontWeight = FontWeight.Bold
            )
        } else if (state.dualDestination.enabled) {
            Text(
                text = "DUAL: ${state.dualDestination.phase.displayLabel}",
                fontSize = 11.sp,
                color = if (state.dualDestination.phase == SessionPhase.LIVE) StatusOk else StudioTextDim
            )
        }

        if (state.replayBuffer.enabled) {
            Text(
                text = "REPLAY: ${String.format(Locale.US, "%.0fs", state.replayBuffer.bufferedSeconds)}",
                fontSize = 11.sp,
                color = StatusOk
            )
        }

        val bitrateText = if (isLive && state.videoConfig.dynamicBitrateEnabled && state.abrTargetBitrateKbps > 0) {
            "${t.actualBitrateKbps}k (ABR ${state.abrTargetBitrateKbps}k)"
        } else {
            "${t.actualBitrateKbps}k"
        }
        Text(
            text = "OUT: ${t.outputWidth}x${t.outputHeight}@${t.configuredFps} · $bitrateText",
            fontSize = 11.sp,
            color = StudioTextDim,
            modifier = Modifier.testTag("hud_real_output_text")
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.testTag("status_live_badge")
        ) {
            Box(
                modifier = Modifier.size(6.dp).background(
                    when (state.sessionPhase) {
                        SessionPhase.LIVE -> StatusCritical
                        SessionPhase.ARMED -> StatusOk
                        SessionPhase.CONNECTING, SessionPhase.RECONNECTING -> StatusWarn
                        else -> StudioTextDim
                    },
                    CircleShape
                )
            )
            Text(
                text = if (isLive) "LIVE: ${formatSeconds(t.streamDurationSec)}" else "LIVE: 00:00:00",
                fontSize = 11.sp,
                color = if (isLive) StudioText else StudioTextDim
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.testTag("status_rec_badge")
        ) {
            Box(
                modifier = Modifier.size(6.dp).background(
                    when {
                        isRec -> ApertureRedHi
                        isPaused -> StatusWarn
                        else -> StudioTextDim
                    },
                    CircleShape
                )
            )
            Text(
                text = when {
                    isRec -> "REC: ${formatSeconds(t.recordDurationSec)}"
                    isPaused -> "PAUSED: ${formatSeconds(t.recordDurationSec)}"
                    else -> "REC: 00:00:00"
                },
                fontSize = 11.sp,
                color = if (isRec || isPaused) StudioText else StudioTextDim
            )
        }

        Text(
            text = "DROP: $totalDrops",
            fontSize = 11.sp,
            color = if (totalDrops > 0) StatusWarn else StudioTextDim
        )

        Text(
            text = "PSS: ${t.processPssMb} MB, ${String.format(Locale.US, "%.1f", t.compositorFps)} fps · ${t.thermal.statusLabel}",
            fontSize = 11.sp,
            color = if (t.thermal.shouldWarnBanner) StatusWarn else StudioTextDim
        )

        Icon(
            Icons.Default.Speed,
            contentDescription = "Toggle Diagnostics HUD",
            tint = if (state.hudExpanded) ApertureRedHi else StudioTextDim,
            modifier = Modifier.size(16.dp).clickable { onToggleHud() }.testTag("toggle_hud_button")
        )
    }
}

@Composable
private fun DiagnosticsHudCard(
    state: MobileLabsUiState,
    onExport: () -> Unit,
    onOpenFullProbes: () -> Unit,
    modifier: Modifier = Modifier
) {
    val t = state.telemetry
    Surface(
        color = Color(0xE61D1D22),
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, ObsBorder),
        modifier = modifier.width(300.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("DIAGNOSTICS HUD", style = MaterialTheme.typography.labelMedium, color = StudioText, fontWeight = FontWeight.Bold)
            Text("Capture FPS: ${String.format(Locale.US, "%.1f", t.captureCadenceFps)} · GLES: ${t.compositorPassMicros} µs · DSP: ${t.audioDspMicros} µs", style = MaterialTheme.typography.labelSmall, color = StudioText)
            Text("Compositor / Enc FPS: ${String.format(Locale.US, "%.1f", t.compositorFps)}", style = MaterialTheme.typography.labelSmall, color = StudioText)
            Text("Real Output: ${t.outputWidth}x${t.outputHeight} @${t.configuredFps} · ${t.actualBitrateKbps} kbps", style = MaterialTheme.typography.labelSmall, color = StatusOk)
            Text("Adaptive: ${state.adaptiveQuality.level.shortTag} (${state.adaptiveQuality.lastChangeReason})", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
            Text("Drops (R/E/N/Gap): ${t.renderSkippedFrames}/${t.encoderDroppedFrames}/${t.networkDroppedFrames}/${t.captureGapCount}", style = MaterialTheme.typography.labelSmall, color = StudioText)
            Text("Queue: ${t.encoderQueueDepth} · RTMP buf: ${t.rtmpBufferedBytes} B · Reconnects: ${t.reconnectCount}", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
            Text("PSS: ${t.processPssMb} MB · Native: ${t.nativeHeapMb} MB · Free RAM: ${t.freeRamMb} MB", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
            Text("Thermal: ${t.thermal.statusLabel} (headroom ${String.format(Locale.US, "%.2f", t.thermal.headroomForecast)}) · ${String.format(Locale.US, "%.1f", t.thermal.batteryTempC)}°C", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                Button(
                    onClick = onExport,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ApertureRed),
                    modifier = Modifier.height(26.dp)
                ) { Text("Export ZIP", style = MaterialTheme.typography.labelSmall, color = StudioText) }
                OutlinedButton(
                    onClick = onOpenFullProbes,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(26.dp)
                ) { Text("Codec Probe", style = MaterialTheme.typography.labelSmall, color = StudioText) }
            }
        }
    }
}

@Composable
private fun BannerStrip(text: String, actionLabel: String, isCritical: Boolean, onAction: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isCritical) ApertureRed.copy(alpha = 0.65f) else StatusWarn.copy(alpha = 0.22f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = if (isCritical) StudioText else StatusWarn, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, fontSize = 11.sp, color = StudioText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(containerColor = ObsSelectionBlue),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(24.dp)
        ) { Text(actionLabel, fontSize = 11.sp, color = StudioText) }
    }
}

@Composable
private fun PortraitRotatePanel(state: MobileLabsUiState) {
    Column(
        modifier = Modifier.fillMaxSize().background(ObsWindowBg).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.ScreenRotation, contentDescription = "Rotate to landscape", tint = StudioText, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.rotate_to_landscape_title), style = MaterialTheme.typography.titleMedium, color = StudioText)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.rotate_to_landscape_body), style = MaterialTheme.typography.bodyMedium, color = StudioTextDim)
        Spacer(Modifier.height(16.dp))
        Surface(color = ObsWellBg, shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, ObsBorder)) {
            Text(
                text = "Stream: ${state.sessionPhase.displayLabel} · Rec: ${state.recordingPhase.displayLabel}",
                style = MaterialTheme.typography.labelMedium,
                color = StudioText,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

private fun formatSeconds(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}
