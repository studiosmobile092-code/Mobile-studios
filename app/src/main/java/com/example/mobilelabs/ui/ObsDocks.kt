package com.example.mobilelabs.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.example.mobilelabs.model.AudioChannelState
import com.example.mobilelabs.model.DockPanelId
import com.example.mobilelabs.model.MobileLabsUiState
import com.example.mobilelabs.model.RecordingPhase
import com.example.mobilelabs.model.SessionPhase
import com.example.mobilelabs.model.SourceModel
import com.example.mobilelabs.model.SourceType
import com.example.mobilelabs.model.TransitionType
import com.example.ui.theme.ApertureRed
import com.example.ui.theme.ApertureRedHi
import com.example.ui.theme.MeterGreen
import com.example.ui.theme.MeterRed
import com.example.ui.theme.MeterTrack
import com.example.ui.theme.MeterYellow
import com.example.ui.theme.ObsBorder
import com.example.ui.theme.ObsControlBtn
import com.example.ui.theme.ObsHeaderBg
import com.example.ui.theme.ObsSelectionBlue
import com.example.ui.theme.ObsSliderBlue
import com.example.ui.theme.ObsWellBg
import com.example.ui.theme.ObsWindowBg
import com.example.ui.theme.StatusOk
import com.example.ui.theme.StatusWarn
import com.example.ui.theme.StudioText
import com.example.ui.theme.StudioTextDim
import java.util.Locale

data class DockCallbacks(
    val onToggleCollapse: () -> Unit,
    val onToggleExpandDock: (DockPanelId) -> Unit,
    val onSelectScene: (String) -> Unit,
    val onAddScene: () -> Unit,
    val onRenameScene: (String) -> Unit,
    val onDuplicateScene: (String) -> Unit,
    val onDeleteScene: (String) -> Unit,
    val onMoveScene: (String, Int) -> Unit,
    val onSelectSource: (String) -> Unit,
    val onToggleSourceVisible: (String) -> Unit,
    val onToggleSourceLock: (String) -> Unit,
    val onSwitchCamera: (String) -> Unit,
    val onMoveSource: (String, Int) -> Unit,
    val onDuplicateSource: (String) -> Unit,
    val onRemoveSource: (String) -> Unit,
    val onOpenAddSource: () -> Unit,
    val onOpenSourceProperties: (SourceModel) -> Unit,
    val onOpenVideoFilters: (SourceModel) -> Unit,
    val onOpenScreenFit: () -> Unit,
    val onAudioVolumeChange: (String, Float) -> Unit,
    val onAudioMuteToggle: (String) -> Unit,
    val onAudioLockToggle: (String) -> Unit,
    val onOpenAudioFilters: (AudioChannelState) -> Unit,
    val onMasterMuteToggle: () -> Unit,
    val onTransitionChange: (TransitionType, Int) -> Unit,
    val onStudioTake: () -> Unit,
    val onToggleStudioMode: () -> Unit,
    val onArmOrDisarm: () -> Unit,
    val onToggleStream: () -> Unit,
    val onToggleRecord: () -> Unit,
    val onPauseResumeRecord: () -> Unit,
    val onSaveReplay: () -> Unit,
    val onOpenConnections: () -> Unit,
    val onOpenDiagnostics: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onExitApp: () -> Unit
)

/**
 * 1:1 Classic OBS Studio Dark five-dock layout:
 * Scenes · Sources · Audio Mixer · Scene Transitions · Controls.
 */
@Composable
fun ObsBottomDocksRow(
    state: MobileLabsUiState,
    collapsed: Boolean,
    callbacks: DockCallbacks,
    modifier: Modifier = Modifier
) {
    val activeSceneId = if (state.studioModeEnabled) state.previewSceneId else state.sceneCollection.activeSceneId
    val activeScene = state.sceneCollection.scenes.firstOrNull { it.id == activeSceneId }
    val expanded = state.expandedDock

    val heightDp = when {
        collapsed -> 30.dp
        expanded != null -> 340.dp
        else -> 212.dp
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp)
            .background(ObsWindowBg)
            .border(1.dp, ObsBorder)
            .padding(horizontal = 3.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (expanded == null || expanded == DockPanelId.SCENES) {
            ScenesDock(
                state = state,
                activeSceneId = activeSceneId,
                collapsed = collapsed,
                isExpanded = expanded == DockPanelId.SCENES,
                callbacks = callbacks,
                modifier = Modifier.weight(if (expanded == DockPanelId.SCENES) 1f else 0.165f)
            )
        }
        if (expanded == null || expanded == DockPanelId.SOURCES) {
            SourcesDock(
                state = state,
                sources = activeScene?.sources.orEmpty(),
                collapsed = collapsed,
                isExpanded = expanded == DockPanelId.SOURCES,
                callbacks = callbacks,
                modifier = Modifier.weight(if (expanded == DockPanelId.SOURCES) 1f else 0.215f)
            )
        }
        if (expanded == null || expanded == DockPanelId.AUDIO_MIXER) {
            AudioMixerDock(
                channels = state.audioChannels,
                masterMuted = state.masterMuted,
                speakerMicEchoDetected = state.speakerMicEchoDetected,
                collapsed = collapsed,
                isExpanded = expanded == DockPanelId.AUDIO_MIXER,
                callbacks = callbacks,
                modifier = Modifier.weight(if (expanded == DockPanelId.AUDIO_MIXER) 1f else 0.305f)
            )
        }
        if (expanded == null || expanded == DockPanelId.TRANSITIONS) {
            TransitionsDock(
                transitionType = state.sceneCollection.transitionType,
                transitionDurationMs = state.sceneCollection.transitionDurationMs,
                studioModeEnabled = state.studioModeEnabled,
                collapsed = collapsed,
                isExpanded = expanded == DockPanelId.TRANSITIONS,
                callbacks = callbacks,
                modifier = Modifier.weight(if (expanded == DockPanelId.TRANSITIONS) 1f else 0.145f)
            )
        }
        if (expanded == null || expanded == DockPanelId.CONTROLS) {
            ControlsDock(
                sessionPhase = state.sessionPhase,
                recordingPhase = state.recordingPhase,
                studioModeEnabled = state.studioModeEnabled,
                replayEnabled = state.replayBuffer.enabled,
                platformsConnectedCount = listOf(state.youtubeConnection.connected, state.twitchConnection.connected).count { it },
                collapsed = collapsed,
                isExpanded = expanded == DockPanelId.CONTROLS,
                callbacks = callbacks,
                modifier = Modifier.weight(if (expanded == DockPanelId.CONTROLS) 1f else 0.17f)
            )
        }
    }
}

@Composable
private fun ScenesDock(
    state: MobileLabsUiState,
    activeSceneId: String,
    collapsed: Boolean,
    isExpanded: Boolean,
    callbacks: DockCallbacks,
    modifier: Modifier
) {
    DockFrame(
        title = if (state.studioModeEnabled) "Scenes (Prev)" else "Scenes",
        collapsed = collapsed,
        isExpanded = isExpanded,
        onHeaderClick = callbacks.onToggleCollapse,
        onExpandClick = { callbacks.onToggleExpandDock(DockPanelId.SCENES) },
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(ObsWellBg)
                .border(1.dp, ObsBorder)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(2.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                items(state.sceneCollection.scenes, key = { it.id }) { scene ->
                    val selected = scene.id == activeSceneId
                    val isProgram = state.studioModeEnabled && scene.id == state.programSceneId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(26.dp)
                            .background(if (selected) ObsSelectionBlue else Color.Transparent)
                            .clickable { callbacks.onSelectScene(scene.id) }
                            .padding(horizontal = 6.dp)
                            .testTag("scene_item_${scene.id}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isProgram) "● ${scene.name}" else scene.name,
                            fontSize = 12.sp,
                            color = if (isProgram && !selected) StatusOk else StudioText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (isExpanded && selected) {
                            IconButton(onClick = { callbacks.onRenameScene(scene.id) }, modifier = Modifier.size(22.dp)) {
                                Icon(Icons.Default.Edit, contentDescription = "Rename scene", tint = StudioText, modifier = Modifier.size(13.dp))
                            }
                        }
                    }
                }
            }
        }
        ObsToolbarRow {
            ObsSmallToolIcon(Icons.Default.Add, "Add Scene", "add_scene_button", onClick = callbacks.onAddScene)
            ObsSmallToolIcon(
                Icons.Default.Remove,
                "Delete Scene",
                enabled = state.sceneCollection.scenes.size > 1,
                onClick = { callbacks.onDeleteScene(activeSceneId) }
            )
            ObsSmallToolIcon(Icons.Default.KeyboardArrowUp, "Move Scene Up") { callbacks.onMoveScene(activeSceneId, -1) }
            ObsSmallToolIcon(Icons.Default.KeyboardArrowDown, "Move Scene Down") { callbacks.onMoveScene(activeSceneId, 1) }
            if (isExpanded) {
                ObsSmallToolIcon(Icons.Default.ContentCopy, "Duplicate Scene") { callbacks.onDuplicateScene(activeSceneId) }
            }
        }
    }
}

@Composable
private fun SourcesDock(
    state: MobileLabsUiState,
    sources: List<SourceModel>,
    collapsed: Boolean,
    isExpanded: Boolean,
    callbacks: DockCallbacks,
    modifier: Modifier
) {
    val selectedSrc = sources.firstOrNull { it.id == state.selectedSourceId }
    DockFrame(
        title = "Sources",
        collapsed = collapsed,
        isExpanded = isExpanded,
        onHeaderClick = callbacks.onToggleCollapse,
        onExpandClick = { callbacks.onToggleExpandDock(DockPanelId.SOURCES) },
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(ObsWellBg)
                .border(1.dp, ObsBorder)
        ) {
            if (sources.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(8.dp).align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "You don't have any sources.\nClick the + button below to add one.",
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = StudioTextDim,
                        textAlign = TextAlign.Center
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Image, contentDescription = null, tint = StudioText, modifier = Modifier.size(18.dp).clickable { callbacks.onOpenAddSource() })
                        Icon(Icons.Default.Monitor, contentDescription = null, tint = StudioText, modifier = Modifier.size(18.dp).clickable { callbacks.onOpenAddSource() })
                        Icon(Icons.Default.Videocam, contentDescription = null, tint = StudioText, modifier = Modifier.size(18.dp).clickable { callbacks.onOpenAddSource() })
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(2.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    items(sources, key = { it.id }) { src ->
                        val selected = src.id == state.selectedSourceId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(26.dp)
                                .background(if (selected) ObsSelectionBlue else Color.Transparent)
                                .clickable { callbacks.onSelectSource(src.id) }
                                .padding(horizontal = 4.dp)
                                .testTag("source_item_${src.id}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = src.name,
                                fontSize = 12.sp,
                                color = if (src.erroredReason != null) StatusWarn else StudioText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (src.type == SourceType.CAMERA_2) {
                                IconButton(onClick = { callbacks.onSwitchCamera(src.id) }, modifier = Modifier.size(22.dp)) {
                                    Icon(Icons.Default.Cameraswitch, contentDescription = "Switch camera", tint = StudioTextDim, modifier = Modifier.size(13.dp))
                                }
                            }
                            if (src.type.isVisual) {
                                IconButton(
                                    onClick = { callbacks.onOpenVideoFilters(src) },
                                    modifier = Modifier.size(22.dp).testTag("video_filter_btn_${src.id}")
                                ) {
                                    Icon(
                                        Icons.Default.AutoFixHigh,
                                        contentDescription = "Video filters",
                                        tint = if (src.videoFilters.anyActive()) StatusOk else StudioTextDim,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                            IconButton(onClick = { callbacks.onToggleSourceVisible(src.id) }, modifier = Modifier.size(22.dp)) {
                                Icon(
                                    if (src.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = "Toggle visibility",
                                    tint = if (src.visible) StudioText else StudioTextDim,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                            IconButton(onClick = { callbacks.onToggleSourceLock(src.id) }, modifier = Modifier.size(22.dp)) {
                                Icon(
                                    if (src.locked) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = "Toggle lock",
                                    tint = if (src.locked) StatusWarn else StudioTextDim,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        ObsToolbarRow {
            ObsSmallToolIcon(Icons.Default.Add, "Add Source", "add_source_button", onClick = callbacks.onOpenAddSource)
            ObsSmallToolIcon(
                Icons.Default.Remove,
                "Remove Source",
                "remove_source_button",
                enabled = state.selectedSourceId != null,
                onClick = { state.selectedSourceId?.let(callbacks.onRemoveSource) }
            )
            ObsSmallToolIcon(
                Icons.Default.Settings,
                "Source Properties",
                "source_properties_button",
                onClick = { selectedSrc?.let(callbacks.onOpenSourceProperties) ?: callbacks.onOpenScreenFit() }
            )
            ObsSmallToolIcon(Icons.Default.KeyboardArrowUp, "Move Source Up") {
                state.selectedSourceId?.let { callbacks.onMoveSource(it, -1) }
            }
            ObsSmallToolIcon(Icons.Default.KeyboardArrowDown, "Move Source Down") {
                state.selectedSourceId?.let { callbacks.onMoveSource(it, 1) }
            }
            if (isExpanded) {
                ObsSmallToolIcon(Icons.Default.ContentCopy, "Duplicate Source") {
                    state.selectedSourceId?.let(callbacks.onDuplicateSource)
                }
            }
        }
    }
}

@Composable
private fun AudioMixerDock(
    channels: List<AudioChannelState>,
    masterMuted: Boolean,
    speakerMicEchoDetected: Boolean,
    collapsed: Boolean,
    isExpanded: Boolean,
    callbacks: DockCallbacks,
    modifier: Modifier
) {
    DockFrame(
        title = "Audio Mixer",
        collapsed = collapsed,
        isExpanded = isExpanded,
        onHeaderClick = callbacks.onToggleCollapse,
        onExpandClick = { callbacks.onToggleExpandDock(DockPanelId.AUDIO_MIXER) },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(ObsWellBg)
                .border(1.dp, ObsBorder)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            channels.forEach { ch ->
                ClassicObsAudioChannelRow(
                    channel = ch,
                    masterMuted = masterMuted,
                    onVolumeChange = { callbacks.onAudioVolumeChange(ch.id, it) },
                    onToggleMute = { callbacks.onAudioMuteToggle(ch.id) },
                    onOpenFilters = { callbacks.onOpenAudioFilters(ch) }
                )
            }
            if (speakerMicEchoDetected) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = "Echo warning", tint = StatusWarn, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Use headphones to prevent speaker/mic echo", fontSize = 10.sp, color = StatusWarn, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        ObsToolbarRow {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (masterMuted) "MASTER MUTED" else "48 kHz Stereo",
                    fontSize = 10.sp,
                    color = if (masterMuted) MeterRed else StudioTextDim
                )
                Text(
                    text = if (masterMuted) "Unmute Master" else "Mute Master",
                    fontSize = 10.sp,
                    color = if (masterMuted) MeterRed else StudioText,
                    modifier = Modifier
                        .background(ObsControlBtn, RoundedCornerShape(2.dp))
                        .border(1.dp, ObsBorder, RoundedCornerShape(2.dp))
                        .clickable { callbacks.onMasterMuteToggle() }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .testTag("master_mute_button")
                )
            }
        }
    }
}

@Composable
private fun TransitionsDock(
    transitionType: TransitionType,
    transitionDurationMs: Int,
    studioModeEnabled: Boolean,
    collapsed: Boolean,
    isExpanded: Boolean,
    callbacks: DockCallbacks,
    modifier: Modifier
) {
    var dropdownOpen by remember { mutableStateOf(false) }

    DockFrame(
        title = "Scene Transitions",
        collapsed = collapsed,
        isExpanded = isExpanded,
        onHeaderClick = callbacks.onToggleCollapse,
        onExpandClick = { callbacks.onToggleExpandDock(DockPanelId.TRANSITIONS) },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(ObsWindowBg)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .background(ObsControlBtn, RoundedCornerShape(3.dp))
                        .border(1.dp, ObsBorder, RoundedCornerShape(3.dp))
                        .clickable { dropdownOpen = true }
                        .padding(horizontal = 8.dp)
                        .testTag("transition_dropdown_button"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(transitionType.label, fontSize = 11.sp, color = StudioText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Transition menu", tint = StudioText, modifier = Modifier.size(16.dp))
                }
                DropdownMenu(
                    expanded = dropdownOpen,
                    onDismissRequest = { dropdownOpen = false },
                    modifier = Modifier.background(ObsHeaderBg)
                ) {
                    TransitionType.entries.forEach { tType ->
                        DropdownMenuItem(
                            text = { Text(tType.label, fontSize = 12.sp, color = StudioText) },
                            onClick = {
                                dropdownOpen = false
                                callbacks.onTransitionChange(tType, transitionDurationMs)
                            },
                            modifier = Modifier.testTag("transition_${tType.name.lowercase()}")
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ObsSmallToolIcon(Icons.Default.Add, "Quick Fade") { callbacks.onTransitionChange(TransitionType.FADE, transitionDurationMs) }
                ObsSmallToolIcon(Icons.Default.Remove, "Quick Cut") { callbacks.onTransitionChange(TransitionType.CUT, transitionDurationMs) }
                ObsSmallToolIcon(Icons.Default.Settings, "Settings") { callbacks.onOpenSettings() }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Duration", fontSize = 10.sp, color = StudioTextDim, maxLines = 1)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(24.dp)
                        .background(ObsWellBg, RoundedCornerShape(2.dp))
                        .border(1.dp, ObsBorder, RoundedCornerShape(2.dp))
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "−",
                        fontSize = 12.sp,
                        color = StudioTextDim,
                        modifier = Modifier.clickable { callbacks.onTransitionChange(transitionType, transitionDurationMs - 50) }.padding(horizontal = 3.dp)
                    )
                    Text("${transitionDurationMs} ms", fontSize = 10.sp, color = StudioText, maxLines = 1)
                    Text(
                        text = "+",
                        fontSize = 12.sp,
                        color = StudioTextDim,
                        modifier = Modifier.clickable { callbacks.onTransitionChange(transitionType, transitionDurationMs + 50) }.padding(horizontal = 3.dp)
                    )
                }
            }

            if (studioModeEnabled) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .background(ApertureRed, RoundedCornerShape(3.dp))
                        .border(1.dp, ApertureRedHi, RoundedCornerShape(3.dp))
                        .clickable { callbacks.onStudioTake() }
                        .testTag("studio_take_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text("TAKE → LIVE", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = StudioText)
                }
            }
        }
    }
}

@Composable
private fun ControlsDock(
    sessionPhase: SessionPhase,
    recordingPhase: RecordingPhase,
    studioModeEnabled: Boolean,
    replayEnabled: Boolean,
    platformsConnectedCount: Int,
    collapsed: Boolean,
    isExpanded: Boolean,
    callbacks: DockCallbacks,
    modifier: Modifier
) {
    val isArmedOrBeyond = sessionPhase != SessionPhase.IDLE && sessionPhase != SessionPhase.ERROR
    val isStreamBusy = sessionPhase == SessionPhase.LIVE ||
        sessionPhase == SessionPhase.CONNECTING ||
        sessionPhase == SessionPhase.RECONNECTING
    val isRecording = recordingPhase == RecordingPhase.RECORDING
    val isPaused = recordingPhase == RecordingPhase.PAUSED
    val isFinalizing = recordingPhase == RecordingPhase.FINALIZING
    val isRecActive = isRecording || isPaused

    DockFrame(
        title = "Controls",
        collapsed = collapsed,
        isExpanded = isExpanded,
        onHeaderClick = callbacks.onToggleCollapse,
        onExpandClick = { callbacks.onToggleExpandDock(DockPanelId.CONTROLS) },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 3.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            ObsControlButton(
                label = if (isArmedOrBeyond) "Disarm Capture" else "Arm Capture",
                dotColor = if (isArmedOrBeyond) StatusOk else ApertureRedHi,
                bgColor = if (isArmedOrBeyond) ObsControlBtn else ApertureRed.copy(alpha = 0.72f),
                testTag = "arm_capture_button",
                onClick = callbacks.onArmOrDisarm,
                modifier = Modifier.weight(1f)
            )
            ObsControlButton(
                label = when (sessionPhase) {
                    SessionPhase.LIVE -> "Stop Streaming"
                    SessionPhase.CONNECTING -> "Connecting..."
                    SessionPhase.RECONNECTING -> "Cancel Reconnect"
                    else -> "Start Streaming"
                },
                enabled = isArmedOrBeyond,
                bgColor = if (isStreamBusy) ApertureRedHi else ObsControlBtn,
                testTag = "stream_toggle_button",
                onClick = callbacks.onToggleStream,
                modifier = Modifier.weight(1f)
            )
            if (isRecActive) {
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    ObsControlButton(
                        label = "Stop Rec",
                        enabled = true,
                        bgColor = ApertureRed,
                        testTag = "record_toggle_button",
                        onClick = callbacks.onToggleRecord,
                        modifier = Modifier.weight(0.55f).fillMaxHeight()
                    )
                    ObsControlButton(
                        label = if (isPaused) "Resume" else "Pause",
                        enabled = true,
                        bgColor = if (isPaused) StatusWarn.copy(alpha = 0.7f) else ObsSelectionBlue,
                        testTag = "pause_resume_record_button",
                        onClick = callbacks.onPauseResumeRecord,
                        modifier = Modifier.weight(0.45f).fillMaxHeight()
                    )
                }
            } else {
                ObsControlButton(
                    label = if (isFinalizing) "Finalizing..." else "Start Recording",
                    enabled = isArmedOrBeyond && !isFinalizing,
                    bgColor = if (isFinalizing) StatusWarn.copy(alpha = 0.45f) else ObsControlBtn,
                    testTag = "record_toggle_button",
                    onClick = callbacks.onToggleRecord,
                    modifier = Modifier.weight(1f)
                )
            }
            if (replayEnabled && isArmedOrBeyond) {
                ObsControlButton(
                    label = "Save Instant Replay",
                    dotColor = StatusOk,
                    bgColor = ObsSelectionBlue,
                    testTag = "dock_save_replay_button",
                    onClick = callbacks.onSaveReplay,
                    modifier = Modifier.weight(1f)
                )
            } else {
                ObsControlButton(
                    label = if (platformsConnectedCount > 0) "YT/TW ($platformsConnectedCount Ready)" else "Connect YT / Twitch",
                    dotColor = if (platformsConnectedCount > 0) StatusOk else null,
                    bgColor = ObsControlBtn,
                    testTag = "dock_connections_button",
                    onClick = callbacks.onOpenConnections,
                    modifier = Modifier.weight(1f)
                )
            }
            ObsControlButton(
                label = if (studioModeEnabled) "Studio Mode: ON" else "Studio Mode",
                bgColor = if (studioModeEnabled) ObsSelectionBlue else ObsControlBtn,
                testTag = "studio_mode_toggle_button",
                onClick = callbacks.onToggleStudioMode,
                modifier = Modifier.weight(1f)
            )
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                ObsControlButton(
                    label = "Settings",
                    testTag = "open_settings_button",
                    onClick = callbacks.onOpenSettings,
                    modifier = Modifier.weight(0.62f).fillMaxHeight()
                )
                ObsControlButton(
                    label = "Exit",
                    testTag = "exit_button",
                    onClick = callbacks.onExitApp,
                    modifier = Modifier.weight(0.38f).fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun ObsControlButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dotColor: Color? = null,
    bgColor: Color = ObsControlBtn,
    testTag: String = "",
    onClick: () -> Unit
) {
    Surface(
        color = if (enabled) bgColor else Color(0xFF313138),
        shape = RoundedCornerShape(3.dp),
        border = BorderStroke(1.dp, if (enabled) ObsBorder else Color(0xFF393942)),
        modifier = modifier
            .fillMaxWidth()
            .then(if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (dotColor != null) {
                Box(modifier = Modifier.size(6.dp).background(dotColor, CircleShape))
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = label,
                fontSize = 11.sp,
                color = if (enabled) StudioText else StudioTextDim.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DockFrame(
    title: String,
    collapsed: Boolean,
    isExpanded: Boolean,
    onHeaderClick: () -> Unit,
    onExpandClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(ObsWindowBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .background(ObsHeaderBg)
                .border(0.5.dp, ObsBorder)
                .clickable { onHeaderClick() }
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                fontSize = 11.sp,
                color = StudioText,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onExpandClick, modifier = Modifier.size(20.dp)) {
                Icon(
                    if (isExpanded) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
                    contentDescription = "Expand $title dock",
                    tint = StudioTextDim,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
        if (!collapsed) {
            content()
        }
    }
}

@Composable
private fun ObsToolbarRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(ObsWindowBg)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        content = content
    )
}

@Composable
private fun ObsSmallToolIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    testTag: String = "",
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(24.dp)
            .then(if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier)
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) StudioText else StudioTextDim.copy(alpha = 0.4f),
            modifier = Modifier.size(14.dp)
        )
    }
}

@Composable
private fun ClassicObsAudioChannelRow(
    channel: AudioChannelState,
    masterMuted: Boolean,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onOpenFilters: () -> Unit
) {
    val dbFormatted = String.format(Locale.US, "%.1f dB", channel.peakDb)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = channel.label,
                fontSize = 11.sp,
                color = StudioText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (channel.isClipping) "CLIP" else dbFormatted,
                fontSize = 10.sp,
                color = if (channel.isClipping) MeterRed else StudioTextDim
            )
        }
        ObsSegmentedMeterWithDbScale(
            peakDb = if (channel.muted || masterMuted) -60f else channel.peakDb,
            peakHoldDb = if (channel.muted || masterMuted) -60f else channel.peakHoldDb,
            modifier = Modifier.fillMaxWidth().height(17.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().height(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ObsHorizontalFader(
                value = channel.volume,
                enabled = !channel.locked,
                onValueChange = onVolumeChange,
                modifier = Modifier.weight(1f).padding(end = 6.dp)
            )
            IconButton(onClick = onToggleMute, modifier = Modifier.size(24.dp)) {
                Icon(
                    if (channel.muted || masterMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Mute ${channel.label}",
                    tint = if (channel.muted || masterMuted) MeterRed else StudioText,
                    modifier = Modifier.size(15.dp)
                )
            }
            IconButton(
                onClick = onOpenFilters,
                modifier = Modifier.size(24.dp).testTag("audio_filter_btn_${channel.id}")
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Audio filters for ${channel.label}",
                    tint = if (channel.filters.anyActive()) StatusOk else StudioText,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        if (channel.unavailableReason != null) {
            Text(channel.unavailableReason, fontSize = 9.sp, color = StatusWarn, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ObsHorizontalFader(
    value: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val maxVal = 1.5f
    val clampedFraction = (value / maxVal).coerceIn(0f, 1f)
    BoxWithConstraints(
        modifier = modifier
            .height(24.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..maxVal)
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    val frac = (offset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                    onValueChange(frac * maxVal)
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    val frac = (change.position.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                    onValueChange(frac * maxVal)
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        val trackWpx = with(density) { maxWidth.toPx().coerceAtLeast(1f) }
        val knobW = 10.dp
        val knobWpx = with(density) { knobW.toPx() }
        val usableWpx = (trackWpx - knobWpx).coerceAtLeast(1f)
        val knobX = (clampedFraction * usableWpx).roundToInt()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(Color(0xFF42424C), RoundedCornerShape(2.dp))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(clampedFraction)
                .height(4.dp)
                .background(
                    if (enabled) ObsSliderBlue else ObsSliderBlue.copy(alpha = 0.45f),
                    RoundedCornerShape(2.dp)
                )
        )
        Box(
            modifier = Modifier
                .offset { IntOffset(knobX, 0) }
                .size(width = knobW, height = 14.dp)
                .background(if (enabled) StudioText else StudioTextDim, RoundedCornerShape(2.dp))
                .border(1.dp, ObsBorder, RoundedCornerShape(2.dp))
        )
    }
}

@Composable
private fun ObsSegmentedMeterWithDbScale(
    peakDb: Float,
    peakHoldDb: Float,
    modifier: Modifier = Modifier
) {
    val norm = ((peakDb + 60f) / 60f).coerceIn(0f, 1f)
    val holdNorm = ((peakHoldDb + 60f) / 60f).coerceIn(0f, 1f)
    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().height(6.dp).background(MeterTrack)) {
            val w = size.width
            val h = size.height
            val activeW = w * norm
            val greenEnd = w * 0.68f
            val yellowEnd = w * 0.86f

            // Background faint zone guide
            drawRect(color = MeterGreen.copy(alpha = 0.14f), topLeft = Offset.Zero, size = Size(greenEnd, h))
            drawRect(color = MeterYellow.copy(alpha = 0.14f), topLeft = Offset(greenEnd, 0f), size = Size(yellowEnd - greenEnd, h))
            drawRect(color = MeterRed.copy(alpha = 0.14f), topLeft = Offset(yellowEnd, 0f), size = Size(w - yellowEnd, h))

            if (activeW > 0f) {
                drawRect(color = MeterGreen, topLeft = Offset.Zero, size = Size(minOf(activeW, greenEnd), h))
            }
            if (activeW > greenEnd) {
                drawRect(color = MeterYellow, topLeft = Offset(greenEnd, 0f), size = Size(minOf(activeW, yellowEnd) - greenEnd, h))
            }
            if (activeW > yellowEnd) {
                drawRect(color = MeterRed, topLeft = Offset(yellowEnd, 0f), size = Size(activeW - yellowEnd, h))
            }
            if (peakHoldDb > -59f) {
                val holdX = (holdNorm * (w - 2.dp.toPx())).coerceIn(0f, (w - 2.dp.toPx()).coerceAtLeast(0f))
                val holdColor = when {
                    holdNorm > 0.86f -> MeterRed
                    holdNorm > 0.68f -> MeterYellow
                    else -> Color.White
                }
                drawRect(color = holdColor, topLeft = Offset(holdX, 0f), size = Size(2.dp.toPx(), h))
            }
        }
        // Classic OBS dB scale tick marks (-60 -50 -40 -30 -20 -15 -10 -5 0)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            listOf("-60", "-50", "-40", "-30", "-20", "-15", "-10", "-5", "0").forEach { tick ->
                Text(text = tick, fontSize = 7.sp, lineHeight = 8.sp, color = StudioTextDim)
            }
        }
    }
}
