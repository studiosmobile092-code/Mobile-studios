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
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R
import com.example.mobilelabs.model.AudioChannelState
import com.example.mobilelabs.model.AudioFilterConfig
import com.example.mobilelabs.model.DockPanelId
import com.example.mobilelabs.model.MobileLabsUiState
import com.example.mobilelabs.model.PresetId
import com.example.mobilelabs.model.PreviewTool
import com.example.mobilelabs.model.RecordingPhase
import com.example.mobilelabs.model.RtmpServicePreset
import com.example.mobilelabs.model.ScreenFitMode
import com.example.mobilelabs.model.SessionPhase
import com.example.mobilelabs.model.SourceModel
import com.example.mobilelabs.model.SourceType
import com.example.mobilelabs.model.TransitionType
import com.example.mobilelabs.model.VideoConfig
import com.example.mobilelabs.model.VideoFilterConfig
import com.example.ui.theme.ApertureRed
import com.example.ui.theme.ApertureRedHi
import com.example.ui.theme.StatusCritical
import com.example.ui.theme.StatusOk
import com.example.ui.theme.StatusWarn
import com.example.ui.theme.StudioBg
import com.example.ui.theme.StudioDivider
import com.example.ui.theme.StudioText
import com.example.ui.theme.StudioTextDim
import com.example.ui.theme.SurfaceLevel1
import com.example.ui.theme.SurfaceLevel2
import kotlinx.coroutines.delay
import java.util.Locale

data class WorkspaceCallbacks(
    val onAttachPreviewSurface: (Surface?, Int, Int) -> Unit,
    val onArmOrDisarm: () -> Unit,
    val onToggleStream: () -> Unit,
    val onToggleRecord: () -> Unit,
    val onSelectScene: (String) -> Unit,
    val onCreateScene: (String) -> Unit,
    val onRenameScene: (String, String) -> Unit,
    val onDuplicateScene: (String) -> Unit,
    val onDeleteScene: (String) -> Unit,
    val onMoveScene: (String, Int) -> Unit,
    val onSelectSource: (String) -> Unit,
    val onAddSource: (SourceType) -> Unit,
    val onPickImageForNewSource: () -> Unit,
    val onPickMediaForNewSource: () -> Unit,
    val onRelinkImageSource: (String) -> Unit,
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
    val onMasterMute: () -> Unit,
    val onTransitionChange: (TransitionType, Int) -> Unit,
    val onToggleStudioMode: () -> Unit,
    val onStudioTake: () -> Unit,
    val onToggleExpandDock: (DockPanelId) -> Unit,
    val onApplyPreset: (PresetId) -> Unit,
    val onUpdateVideoConfig: (VideoConfig) -> Unit,
    val onUpdateScreenFit: (ScreenFitMode, Float) -> Unit,
    val onSaveStreamCredentials: (RtmpServicePreset, String, String?) -> Unit,
    val onPickRecordingFolder: () -> Unit,
    val onResetRecordingFolder: () -> Unit,
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

    LaunchedEffect(Unit) {
        delay(420L)
        showSplash = false
    }

    val liveOrRec = state.sessionPhase == SessionPhase.LIVE || state.recordingPhase == RecordingPhase.RECORDING
    val isTouchBlockedLive = liveOrRec && !state.studioModeEnabled && state.lockPreviewTouchWhileLive && !state.unlockLiveTouchOverride

    val editableSceneId = if (state.studioModeEnabled) state.previewSceneId else state.sceneCollection.activeSceneId
    val editableScene = state.sceneCollection.scenes.firstOrNull { it.id == editableSceneId }
    val selectedSrc = editableScene?.sources?.firstOrNull { it.id == state.selectedSourceId }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioBg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        val isPortraitOrNarrow = maxWidth < maxHeight || maxWidth < 620.dp

        if (isPortraitOrNarrow) {
            PortraitRotatePanel(state = state)
        } else {
            Column(modifier = Modifier.fillMaxSize().background(StudioBg)) {
                TopStatusStrip(
                    state = state,
                    onToggleHud = callbacks.onToggleHud,
                    onOpenSettings = { showSettings = true }
                )

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
                        text = "Capture was stopped from Android system UI.",
                        actionLabel = "Re-Arm Capture",
                        isCritical = true,
                        onAction = callbacks.onArmOrDisarm
                    )
                }

                // Middle Workspace: Left Tool Rail + Preview Canvas (+ Studio Mode split)
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LeftToolRail(
                        activeTool = state.activeTool,
                        canUndo = state.canUndo,
                        canRedo = state.canRedo,
                        onSelectTool = callbacks.onSetActiveTool,
                        onAlignCenter = callbacks.onAlignSelectedCenter,
                        onLockSelected = { state.selectedSourceId?.let(callbacks.onToggleSourceLock) },
                        onUndo = callbacks.onUndo,
                        onRedo = callbacks.onRedo,
                        onAddSource = { showAddSource = true }
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(Color(0xFF070709), RoundedCornerShape(4.dp))
                            .border(1.dp, StudioDivider, RoundedCornerShape(4.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        val isCaptureLive = state.sessionPhase != SessionPhase.IDLE &&
                            state.sessionPhase != SessionPhase.ARMING &&
                            state.sessionPhase != SessionPhase.ERROR

                        val canvasAspect = if (state.studioModeEnabled) 32f / 9f else 16f / 9f
                        Box(
                            modifier = Modifier
                                .fillMaxHeight(0.95f)
                                .aspectRatio(canvasAspect, matchHeightConstraintsFirst = true)
                                .background(Color.Black)
                                .border(1.dp, Color(0xFF2D2D35))
                        ) {
                            if (isCaptureLive) {
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
                            } else {
                                Row(modifier = Modifier.fillMaxSize()) {
                                    Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(
                                                text = if (state.studioModeEnabled) "PREVIEW: ${editableScene?.name ?: "Scene"}" else "PREVIEW (${state.videoConfig.canvasWidth}x${state.videoConfig.canvasHeight})",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = StudioTextDim
                                            )
                                            Text(
                                                text = state.statusMessage,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (state.errorReason != null) StatusCritical else StudioTextDim
                                            )
                                            Text(
                                                text = "Engine: ${state.engineBackend.label}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = StudioTextDim
                                            )
                                        }
                                    }
                                    if (state.studioModeEnabled) {
                                        val progScene = state.sceneCollection.scenes.firstOrNull { it.id == state.programSceneId }
                                        Box(
                                            modifier = Modifier.weight(1f).fillMaxHeight().border(0.5.dp, ApertureRedHi),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "PROGRAM (LIVE): ${progScene?.name ?: "Scene"}",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = ApertureRedHi
                                            )
                                        }
                                    }
                                }
                            }

                            // Interactive Touch Editor Overlay spans Preview side (left half in Studio Mode, full canvas otherwise)
                            Row(modifier = Modifier.fillMaxSize()) {
                                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    PreviewTouchEditorOverlay(
                                        scene = editableScene,
                                        selectedSourceId = state.selectedSourceId,
                                        multiSelectedIds = state.multiSelectedSourceIds,
                                        activeTool = state.activeTool,
                                        isTouchBlockedLive = isTouchBlockedLive,
                                        onSelectSource = callbacks.onSelectSource,
                                        onUnlockLiveTouch = callbacks.onUnlockLiveTouch,
                                        onDragSourceTransform = callbacks.onDragSourceTransform,
                                        onUpdateCrop = callbacks.onUpdateSourceCrop
                                    )
                                }
                                if (state.studioModeEnabled) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .border(1.dp, ApertureRedHi.copy(alpha = 0.7f))
                                    )
                                }
                            }

                            // Screen Fit / Studio Mode status pill
                            Row(
                                modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(color = Color(0xAA16161A), shape = RoundedCornerShape(4.dp)) {
                                    Text(
                                        text = if (state.studioModeEnabled) "STUDIO PREVIEW (STAGED)" else "${state.videoConfig.screenFitMode.label} · ${state.videoConfig.canvasWidth}x${state.videoConfig.canvasHeight}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StudioTextDim,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            if (state.studioModeEnabled) {
                                Surface(
                                    color = ApertureRed.copy(alpha = 0.85f),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                                ) {
                                    Text(
                                        text = "PROGRAM",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = StudioText,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        if (selectedSrc != null && selectedSrc.type.isVisual) {
                            SelectedSourceNudgeBar(
                                source = selectedSrc,
                                blockedLive = isTouchBlockedLive,
                                onNudge = callbacks.onNudgeSource,
                                onFitToScreen = callbacks.onFitSelectedToScreen,
                                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                            )
                        }

                        if (state.hudExpanded) {
                            DiagnosticsHudCard(
                                state = state,
                                onExport = callbacks.onExportDiagnostics,
                                onOpenFullProbes = { showDiagnostics = true },
                                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                            )
                        }
                    }
                }

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
                            if (busy) confirmStopStream = true else callbacks.onToggleStream()
                        },
                        onToggleRecord = {
                            if (state.recordingPhase == RecordingPhase.RECORDING) confirmStopRecord = true else callbacks.onToggleRecord()
                        },
                        onOpenDiagnostics = { showDiagnostics = true },
                        onOpenSettings = { showSettings = true },
                        onExitApp = callbacks.onExitApp
                    )
                )
            }
        }

        AnimatedVisibility(visible = showSplash, exit = fadeOut(animationSpec = tween(durationMillis = 150))) {
            Box(modifier = Modifier.fillMaxSize().background(ApertureRed), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(painter = painterResource(R.drawable.mobile_labs_logo), contentDescription = "Mobile Labs logo", modifier = Modifier.size(128.dp))
                    Spacer(Modifier.height(12.dp))
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
                onRequestEnableOverlay = callbacks.onRequestEnableOverlay
            )
        )
    }

    if (showDiagnostics) {
        DiagnosticsAndProbesDialog(
            state = state,
            onDismiss = { showDiagnostics = false },
            onRunProbe = callbacks.onRunProbe,
            onExportZip = callbacks.onExportDiagnostics
        )
    }

    if (showAddSource) {
        AddSourcePhase2Dialog(
            onDismiss = { showAddSource = false },
            onAddStandardSource = callbacks.onAddSource,
            onPickImageFile = callbacks.onPickImageForNewSource,
            onPickMediaFile = callbacks.onPickMediaForNewSource
        )
    }

    editingSourceProps?.let { src ->
        SourcePropertiesDialog(
            source = src,
            onDismiss = { editingSourceProps = null },
            onUpdateSource = { fn -> callbacks.onUpdateSourceProperties(src.id, fn) },
            onRelinkImage = { callbacks.onRelinkImageSource(it); editingSourceProps = null },
            onRelinkMedia = { callbacks.onRelinkMediaSource(it); editingSourceProps = null }
        )
    }

    editingVideoFilterSrc?.let { src ->
        VideoFiltersDialog(
            source = src,
            compositorMicros = state.telemetry.compositorPassMicros,
            onDismiss = { editingVideoFilterSrc = null },
            onSave = { f -> callbacks.onUpdateSourceVideoFilters(src.id, f) }
        )
    }

    editingAudioFilterCh?.let { ch ->
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
                    colors = ButtonDefaults.buttonColors(containerColor = ApertureRed)
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
                    colors = ButtonDefaults.buttonColors(containerColor = ApertureRed)
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
private fun TopStatusStrip(
    state: MobileLabsUiState,
    onToggleHud: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val t = state.telemetry
    val isLive = state.sessionPhase == SessionPhase.LIVE
    val isRec = state.recordingPhase == RecordingPhase.RECORDING
    val totalDrops = t.renderSkippedFrames + t.encoderDroppedFrames + t.networkDroppedFrames

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(SurfaceLevel1)
            .border(0.5.dp, StudioDivider)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Image(painter = painterResource(R.drawable.mobile_labs_logo), contentDescription = "Mobile Labs", modifier = Modifier.size(26.dp))
        Text(text = state.sceneCollection.name, style = MaterialTheme.typography.titleSmall, color = StudioText, maxLines = 1, overflow = TextOverflow.Ellipsis)

        StatusPill(
            dotColor = when (state.sessionPhase) {
                SessionPhase.LIVE -> StatusCritical
                SessionPhase.RECONNECTING, SessionPhase.CONNECTING -> StatusWarn
                SessionPhase.ARMED -> StatusOk
                else -> StudioTextDim
            },
            label = when (state.sessionPhase) {
                SessionPhase.LIVE -> "LIVE ${formatSeconds(t.streamDurationSec)}"
                SessionPhase.RECONNECTING -> "RECONNECT ${state.reconnectCountdownSec}s"
                SessionPhase.CONNECTING -> "CONNECTING"
                SessionPhase.ARMED -> "ARMED"
                else -> "OFFLINE"
            },
            highlighted = isLive,
            testTag = "status_live_badge"
        )

        StatusPill(
            dotColor = if (isRec) ApertureRedHi else StudioTextDim,
            label = if (isRec) "REC ${formatSeconds(t.recordDurationSec)}" else "REC OFF",
            highlighted = isRec,
            testTag = "status_rec_badge"
        )

        Spacer(Modifier.weight(1f))

        Text(
            text = "OUT ${t.outputWidth}x${t.outputHeight} @${t.configuredFps} · ${String.format(Locale.US, "%.1f", t.actualBitrateKbps / 1000f)} Mbps · ${t.encoderMode.hudTag}",
            style = MaterialTheme.typography.labelMedium,
            color = StudioText,
            modifier = Modifier.testTag("hud_real_output_text")
        )
        Text(text = "DROP $totalDrops", style = MaterialTheme.typography.labelMedium, color = if (totalDrops > 0) StatusWarn else StudioTextDim)
        Text(text = t.thermal.statusLabel, style = MaterialTheme.typography.labelMedium, color = if (t.thermal.shouldWarnBanner) StatusWarn else StatusOk)
        Text(text = "MEM ${t.processPssMb} MB", style = MaterialTheme.typography.labelMedium, color = StudioTextDim)

        IconButton(onClick = onToggleHud, modifier = Modifier.size(38.dp).testTag("toggle_hud_button")) {
            Icon(Icons.Default.Speed, contentDescription = "Toggle Diagnostics HUD", tint = if (state.hudExpanded) ApertureRedHi else StudioText)
        }
        IconButton(onClick = onOpenSettings, modifier = Modifier.size(38.dp).testTag("top_settings_button")) {
            Icon(Icons.Default.Settings, contentDescription = "Open Settings", tint = StudioText)
        }
    }
}

@Composable
private fun StatusPill(dotColor: Color, label: String, highlighted: Boolean, testTag: String) {
    Surface(
        color = if (highlighted) ApertureRed.copy(alpha = 0.55f) else SurfaceLevel2,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, if (highlighted) ApertureRedHi else StudioDivider),
        modifier = Modifier.testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(modifier = Modifier.size(7.dp).background(dotColor, CircleShape))
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = StudioText)
        }
    }
}

@Composable
private fun LeftToolRail(
    activeTool: PreviewTool,
    canUndo: Boolean,
    canRedo: Boolean,
    onSelectTool: (PreviewTool) -> Unit,
    onAlignCenter: () -> Unit,
    onLockSelected: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onAddSource: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(56.dp)
            .fillMaxHeight()
            .background(SurfaceLevel1, RoundedCornerShape(4.dp))
            .border(1.dp, StudioDivider, RoundedCornerShape(4.dp))
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ToolRailButton(Icons.Default.OpenWith, "Select / Move", activeTool == PreviewTool.SELECT_MOVE, true) {
            onSelectTool(PreviewTool.SELECT_MOVE)
        }
        ToolRailButton(Icons.Default.Crop, "Crop", activeTool == PreviewTool.CROP, true) {
            onSelectTool(PreviewTool.CROP)
        }
        ToolRailButton(Icons.Default.FitScreen, "Fit to Screen", activeTool == PreviewTool.FIT_TO_SCREEN, true) {
            onSelectTool(PreviewTool.FIT_TO_SCREEN)
        }
        ToolRailButton(Icons.Default.CenterFocusStrong, "Align Center", false, true, onAlignCenter)
        ToolRailButton(Icons.Default.Lock, "Lock Selected", false, true, onLockSelected)
        ToolRailButton(Icons.AutoMirrored.Filled.Undo, "Undo", false, canUndo, onUndo)
        ToolRailButton(Icons.AutoMirrored.Filled.Redo, "Redo", false, canRedo, onRedo)
        ToolRailButton(Icons.Default.Add, "Add Source", false, true, onAddSource)
    }
}

@Composable
private fun ToolRailButton(
    icon: ImageVector,
    description: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(44.dp)
            .background(if (active) ApertureRed.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(6.dp))
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = when {
                !enabled -> StudioDivider
                active -> StudioText
                else -> StudioTextDim
            },
            modifier = Modifier.size(20.dp)
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
        color = Color(0xD916161A),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, StudioDivider),
        modifier = modifier.width(292.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("DIAGNOSTICS HUD", style = MaterialTheme.typography.labelMedium, color = StudioText, fontWeight = FontWeight.Bold)
            Text("Capture FPS: ${String.format(Locale.US, "%.1f", t.captureCadenceFps)} · GLES: ${t.compositorPassMicros} µs · DSP: ${t.audioDspMicros} µs", style = MaterialTheme.typography.labelSmall, color = StudioText)
            Text("Compositor / Enc FPS: ${String.format(Locale.US, "%.1f", t.compositorFps)}", style = MaterialTheme.typography.labelSmall, color = StudioText)
            Text("Real Output: ${t.outputWidth}x${t.outputHeight} @${t.configuredFps} · ${t.actualBitrateKbps} kbps", style = MaterialTheme.typography.labelSmall, color = StatusOk)
            Text("Encoder Mode: ${t.encoderMode.description}", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
            Text("Drops (R/E/N/Gap): ${t.renderSkippedFrames}/${t.encoderDroppedFrames}/${t.networkDroppedFrames}/${t.captureGapCount}", style = MaterialTheme.typography.labelSmall, color = StudioText)
            Text("Queue: ${t.encoderQueueDepth} · RTMP buf: ${t.rtmpBufferedBytes} B · Reconnects: ${t.reconnectCount}", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
            Text("PSS: ${t.processPssMb} MB · Native: ${t.nativeHeapMb} MB · Free RAM: ${t.freeRamMb} MB", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
            Text("Thermal: ${t.thermal.statusLabel} (headroom ${String.format(Locale.US, "%.2f", t.thermal.headroomForecast)}) · ${String.format(Locale.US, "%.1f", t.thermal.batteryTempC)}°C", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                Button(
                    onClick = onExport,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ApertureRed),
                    modifier = Modifier.height(28.dp)
                ) { Text("Export ZIP", style = MaterialTheme.typography.labelSmall, color = StudioText) }
                OutlinedButton(
                    onClick = onOpenFullProbes,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
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
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = if (isCritical) StudioText else StatusWarn, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = StudioText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(containerColor = ApertureRed),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(28.dp)
        ) { Text(actionLabel, style = MaterialTheme.typography.labelSmall, color = StudioText) }
    }
}

@Composable
private fun PortraitRotatePanel(state: MobileLabsUiState) {
    Column(
        modifier = Modifier.fillMaxSize().background(StudioBg).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.ScreenRotation, contentDescription = "Rotate to landscape", tint = StudioText, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.rotate_to_landscape_title), style = MaterialTheme.typography.titleMedium, color = StudioText)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.rotate_to_landscape_body), style = MaterialTheme.typography.bodyMedium, color = StudioTextDim)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(
                dotColor = if (state.sessionPhase == SessionPhase.LIVE) StatusCritical else StatusOk,
                label = state.sessionPhase.displayLabel,
                highlighted = state.sessionPhase == SessionPhase.LIVE,
                testTag = "portrait_live_pill"
            )
            StatusPill(
                dotColor = if (state.recordingPhase == RecordingPhase.RECORDING) ApertureRedHi else StudioTextDim,
                label = state.recordingPhase.displayLabel,
                highlighted = state.recordingPhase == RecordingPhase.RECORDING,
                testTag = "portrait_rec_pill"
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
