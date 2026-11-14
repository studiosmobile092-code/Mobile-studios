package com.example.mobilelabs.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.example.ui.theme.StatusOk
import com.example.ui.theme.StatusWarn
import com.example.ui.theme.StudioBg
import com.example.ui.theme.StudioDivider
import com.example.ui.theme.StudioText
import com.example.ui.theme.StudioTextDim
import com.example.ui.theme.SurfaceLevel1
import com.example.ui.theme.SurfaceLevel2
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
    val onOpenDiagnostics: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onExitApp: () -> Unit
)

/**
 * Five classic OBS bottom docks in exact OBS Studio order (§9.1, Phase 2 full CRUD & Dock Swap):
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
        collapsed -> 40.dp
        expanded != null -> 360.dp
        else -> 236.dp
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp)
            .background(SurfaceLevel1)
            .border(1.dp, StudioDivider),
        horizontalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        if (expanded == null || expanded == DockPanelId.SCENES) {
            ScenesDock(
                state = state,
                activeSceneId = activeSceneId,
                collapsed = collapsed,
                isExpanded = expanded == DockPanelId.SCENES,
                callbacks = callbacks,
                modifier = Modifier.weight(if (expanded == DockPanelId.SCENES) 1f else 0.17f)
            )
        }
        if (expanded == null || expanded == DockPanelId.SOURCES) {
            SourcesDock(
                state = state,
                sources = activeScene?.sources.orEmpty(),
                collapsed = collapsed,
                isExpanded = expanded == DockPanelId.SOURCES,
                callbacks = callbacks,
                modifier = Modifier.weight(if (expanded == DockPanelId.SOURCES) 1f else 0.24f)
            )
        }
        if (expanded == null || expanded == DockPanelId.AUDIO_MIXER) {
            AudioMixerDock(
                state = state,
                collapsed = collapsed,
                isExpanded = expanded == DockPanelId.AUDIO_MIXER,
                callbacks = callbacks,
                modifier = Modifier.weight(if (expanded == DockPanelId.AUDIO_MIXER) 1f else 0.27f)
            )
        }
        if (expanded == null || expanded == DockPanelId.TRANSITIONS) {
            TransitionsDock(
                state = state,
                collapsed = collapsed,
                isExpanded = expanded == DockPanelId.TRANSITIONS,
                callbacks = callbacks,
                modifier = Modifier.weight(if (expanded == DockPanelId.TRANSITIONS) 1f else 0.14f)
            )
        }
        if (expanded == null || expanded == DockPanelId.CONTROLS) {
            ControlsDock(
                state = state,
                collapsed = collapsed,
                isExpanded = expanded == DockPanelId.CONTROLS,
                callbacks = callbacks,
                modifier = Modifier.weight(if (expanded == DockPanelId.CONTROLS) 1f else 0.18f)
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
        title = if (state.studioModeEnabled) "Scenes (Preview)" else "Scenes",
        collapsed = collapsed,
        isExpanded = isExpanded,
        onHeaderClick = callbacks.onToggleCollapse,
        onExpandClick = { callbacks.onToggleExpandDock(DockPanelId.SCENES) },
        modifier = modifier
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(state.sceneCollection.scenes, key = { it.id }) { scene ->
                val selected = scene.id == activeSceneId
                val isProgram = state.studioModeEnabled && scene.id == state.programSceneId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .background(
                            if (selected) ApertureRed.copy(alpha = 0.45f) else SurfaceLevel2,
                            RoundedCornerShape(4.dp)
                        )
                        .border(
                            width = if (selected || isProgram) 1.dp else 0.dp,
                            color = when {
                                selected -> ApertureRedHi
                                isProgram -> StatusOk
                                else -> StudioDivider
                            },
                            shape = RoundedCornerShape(4.dp)
                        )
                        .clickable { callbacks.onSelectScene(scene.id) }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .testTag("scene_item_${scene.id}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isProgram) "● ${scene.name}" else scene.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isExpanded && selected) {
                        IconButton(onClick = { callbacks.onRenameScene(scene.id) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Edit, contentDescription = "Rename scene", tint = StudioTextDim, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
        DockFooterBar {
            IconButton(onClick = callbacks.onAddScene, modifier = Modifier.size(32.dp).testTag("add_scene_button")) {
                Icon(Icons.Default.Add, contentDescription = "Add Scene", tint = StudioText, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = { callbacks.onDuplicateScene(activeSceneId) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate Scene", tint = StudioTextDim, modifier = Modifier.size(15.dp))
            }
            IconButton(onClick = { callbacks.onMoveScene(activeSceneId, -1) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "Move Scene Up", tint = StudioTextDim, modifier = Modifier.size(15.dp))
            }
            IconButton(onClick = { callbacks.onMoveScene(activeSceneId, 1) }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.ArrowDownward, contentDescription = "Move Scene Down", tint = StudioTextDim, modifier = Modifier.size(15.dp))
            }
            if (state.sceneCollection.scenes.size > 1) {
                IconButton(onClick = { callbacks.onDeleteScene(activeSceneId) }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Scene", tint = StudioTextDim, modifier = Modifier.size(15.dp))
                }
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
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(sources, key = { it.id }) { src ->
                val selected = src.id == state.selectedSourceId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (selected) ApertureRed.copy(alpha = 0.35f) else SurfaceLevel2,
                            RoundedCornerShape(4.dp)
                        )
                        .border(
                            width = if (selected) 1.dp else 0.dp,
                            color = if (selected) ApertureRedHi else StudioDivider,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .clickable { callbacks.onSelectSource(src.id) }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .testTag("source_item_${src.id}"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = src.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (src.erroredReason != null) StatusWarn else StudioText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val sub = src.erroredReason ?: src.videoFilters.activeTags().takeIf { it.isNotEmpty() }?.joinToString("·")
                        if (sub != null) {
                            Text(
                                text = sub,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (src.erroredReason != null) StatusWarn else StatusOk,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (src.type == SourceType.CAMERA_2) {
                        IconButton(onClick = { callbacks.onSwitchCamera(src.id) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Cameraswitch, contentDescription = "Switch camera", tint = StudioTextDim, modifier = Modifier.size(15.dp))
                        }
                    }
                    if (src.type.isVisual) {
                        IconButton(onClick = { callbacks.onOpenVideoFilters(src) }, modifier = Modifier.size(28.dp).testTag("video_filter_btn_${src.id}")) {
                            Icon(
                                Icons.Default.AutoFixHigh,
                                contentDescription = "Video filters",
                                tint = if (src.videoFilters.anyActive()) ApertureRedHi else StudioTextDim,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                    IconButton(onClick = { callbacks.onToggleSourceVisible(src.id) }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            if (src.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Toggle visibility",
                            tint = if (src.visible) StudioText else StudioTextDim,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    IconButton(onClick = { callbacks.onToggleSourceLock(src.id) }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            if (src.locked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = "Toggle lock",
                            tint = if (src.locked) StatusWarn else StudioTextDim,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
        DockFooterBar {
            IconButton(onClick = callbacks.onOpenAddSource, modifier = Modifier.size(32.dp).testTag("add_source_button")) {
                Icon(Icons.Default.Add, contentDescription = "Add Source", tint = StudioText, modifier = Modifier.size(16.dp))
            }
            IconButton(
                onClick = { state.selectedSourceId?.let(callbacks.onRemoveSource) },
                modifier = Modifier.size(32.dp).testTag("remove_source_button")
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Remove Source", tint = StudioTextDim, modifier = Modifier.size(16.dp))
            }
            IconButton(
                onClick = { selectedSrc?.let(callbacks.onOpenSourceProperties) ?: callbacks.onOpenScreenFit() },
                modifier = Modifier.size(32.dp).testTag("source_properties_button")
            ) {
                Icon(Icons.Default.Edit, contentDescription = "Source Properties", tint = StudioText, modifier = Modifier.size(15.dp))
            }
            IconButton(
                onClick = { state.selectedSourceId?.let(callbacks.onDuplicateSource) },
                modifier = Modifier.size(30.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate Source", tint = StudioTextDim, modifier = Modifier.size(15.dp))
            }
            IconButton(
                onClick = { state.selectedSourceId?.let { callbacks.onMoveSource(it, -1) } },
                modifier = Modifier.size(30.dp)
            ) {
                Icon(Icons.Default.ArrowUpward, contentDescription = "Move Source Up", tint = StudioTextDim, modifier = Modifier.size(15.dp))
            }
            IconButton(
                onClick = { state.selectedSourceId?.let { callbacks.onMoveSource(it, 1) } },
                modifier = Modifier.size(30.dp)
            ) {
                Icon(Icons.Default.ArrowDownward, contentDescription = "Move Source Down", tint = StudioTextDim, modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable
private fun AudioMixerDock(
    state: MobileLabsUiState,
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
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            state.audioChannels.forEach { ch ->
                AudioChannelRow(
                    channel = ch,
                    masterMuted = state.masterMuted,
                    onVolumeChange = { callbacks.onAudioVolumeChange(ch.id, it) },
                    onToggleMute = { callbacks.onAudioMuteToggle(ch.id) },
                    onToggleLock = { callbacks.onAudioLockToggle(ch.id) },
                    onOpenFilters = { callbacks.onOpenAudioFilters(ch) }
                )
            }
            if (state.speakerMicEchoDetected) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = "Echo warning", tint = StatusWarn, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Speaker + mic active: use headphones to avoid echo",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusWarn,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        DockFooterBar {
            OutlinedButton(
                onClick = callbacks.onMasterMuteToggle,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                border = BorderStroke(1.dp, if (state.masterMuted) MeterRed else StudioDivider),
                modifier = Modifier.height(28.dp).testTag("master_mute_button")
            ) {
                Text(
                    text = if (state.masterMuted) "MASTER MUTED" else "Master: Active",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.masterMuted) MeterRed else StudioText
                )
            }
        }
    }
}

@Composable
private fun TransitionsDock(
    state: MobileLabsUiState,
    collapsed: Boolean,
    isExpanded: Boolean,
    callbacks: DockCallbacks,
    modifier: Modifier
) {
    DockFrame(
        title = "Scene Transitions",
        collapsed = collapsed,
        isExpanded = isExpanded,
        onHeaderClick = callbacks.onToggleCollapse,
        onExpandClick = { callbacks.onToggleExpandDock(DockPanelId.TRANSITIONS) },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            val entries = TransitionType.entries
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
                entries.take(3).forEach { tType ->
                    TransitionChip(tType, state, callbacks.onTransitionChange, Modifier.weight(1f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.fillMaxWidth()) {
                entries.drop(3).forEach { tType ->
                    TransitionChip(tType, state, callbacks.onTransitionChange, Modifier.weight(1f))
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = {
                        callbacks.onTransitionChange(
                            state.sceneCollection.transitionType,
                            state.sceneCollection.transitionDurationMs - 50
                        )
                    },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(28.dp)
                ) { Text("-", color = StudioText) }
                Text("${state.sceneCollection.transitionDurationMs} ms", style = MaterialTheme.typography.labelSmall, color = StudioText)
                OutlinedButton(
                    onClick = {
                        callbacks.onTransitionChange(
                            state.sceneCollection.transitionType,
                            state.sceneCollection.transitionDurationMs + 50
                        )
                    },
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(28.dp)
                ) { Text("+", color = StudioText) }
            }
            if (state.studioModeEnabled) {
                Button(
                    onClick = callbacks.onStudioTake,
                    colors = ButtonDefaults.buttonColors(containerColor = ApertureRed),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.fillMaxWidth().height(32.dp).testTag("studio_take_button")
                ) {
                    Text("TAKE → LIVE", style = MaterialTheme.typography.labelSmall, color = StudioText)
                }
            }
        }
    }
}

@Composable
private fun TransitionChip(
    tType: TransitionType,
    state: MobileLabsUiState,
    onTransitionChange: (TransitionType, Int) -> Unit,
    modifier: Modifier
) {
    val active = state.sceneCollection.transitionType == tType
    Surface(
        color = if (active) ApertureRed else SurfaceLevel2,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, if (active) ApertureRedHi else StudioDivider),
        modifier = modifier
            .height(28.dp)
            .clickable { onTransitionChange(tType, state.sceneCollection.transitionDurationMs) }
            .testTag("transition_${tType.name.lowercase()}")
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(tType.label, style = MaterialTheme.typography.labelSmall, color = StudioText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ControlsDock(
    state: MobileLabsUiState,
    collapsed: Boolean,
    isExpanded: Boolean,
    callbacks: DockCallbacks,
    modifier: Modifier
) {
    val isArmedOrBeyond = state.sessionPhase != SessionPhase.IDLE && state.sessionPhase != SessionPhase.ERROR
    val isStreamBusy = state.sessionPhase == SessionPhase.LIVE ||
        state.sessionPhase == SessionPhase.CONNECTING ||
        state.sessionPhase == SessionPhase.RECONNECTING
    val isRecording = state.recordingPhase == RecordingPhase.RECORDING

    DockFrame(
        title = "Controls",
        collapsed = collapsed,
        isExpanded = isExpanded,
        onHeaderClick = callbacks.onToggleCollapse,
        onExpandClick = { callbacks.onToggleExpandDock(DockPanelId.CONTROLS) },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Button(
                onClick = callbacks.onArmOrDisarm,
                colors = ButtonDefaults.buttonColors(containerColor = if (isArmedOrBeyond) SurfaceLevel2 else ApertureRed, contentColor = StudioText),
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                modifier = Modifier.fillMaxWidth().height(30.dp).testTag("arm_capture_button")
            ) {
                Text(if (isArmedOrBeyond) "Disarm Capture" else "Arm Capture", style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
            Button(
                onClick = callbacks.onToggleStream,
                enabled = isArmedOrBeyond,
                colors = ButtonDefaults.buttonColors(containerColor = if (isStreamBusy) ApertureRedHi else SurfaceLevel2, contentColor = StudioText),
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                modifier = Modifier.fillMaxWidth().height(30.dp).testTag("stream_toggle_button")
            ) {
                Text(
                    when (state.sessionPhase) {
                        SessionPhase.LIVE -> "Stop Streaming"
                        SessionPhase.CONNECTING -> "Connecting..."
                        SessionPhase.RECONNECTING -> "Cancel Reconnect"
                        else -> "Start Streaming"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
            }
            Button(
                onClick = callbacks.onToggleRecord,
                enabled = isArmedOrBeyond,
                colors = ButtonDefaults.buttonColors(containerColor = if (isRecording) ApertureRed else SurfaceLevel2, contentColor = StudioText),
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                modifier = Modifier.fillMaxWidth().height(30.dp).testTag("record_toggle_button")
            ) {
                Text(if (isRecording) "Stop Recording" else "Start Recording", style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
            OutlinedButton(
                onClick = callbacks.onToggleStudioMode,
                border = BorderStroke(1.dp, if (state.studioModeEnabled) ApertureRedHi else StudioDivider),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (state.studioModeEnabled) ApertureRed.copy(alpha = 0.35f) else SurfaceLevel2
                ),
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                modifier = Modifier.fillMaxWidth().height(28.dp).testTag("studio_mode_toggle_button")
            ) {
                Text(if (state.studioModeEnabled) "Studio Mode: ON" else "Studio Mode", style = MaterialTheme.typography.labelSmall, color = StudioText, maxLines = 1)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = callbacks.onOpenDiagnostics,
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    modifier = Modifier.weight(1f).height(28.dp).testTag("open_diagnostics_button")
                ) { Text("Probes", style = MaterialTheme.typography.labelSmall, color = StudioText, maxLines = 1) }
                OutlinedButton(
                    onClick = callbacks.onOpenSettings,
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    modifier = Modifier.weight(1f).height(28.dp).testTag("open_settings_button")
                ) { Text("Settings", style = MaterialTheme.typography.labelSmall, color = StudioText, maxLines = 1) }
                OutlinedButton(
                    onClick = callbacks.onExitApp,
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    modifier = Modifier.weight(0.7f).height(28.dp).testTag("exit_button")
                ) { Text("Exit", style = MaterialTheme.typography.labelSmall, color = StudioTextDim, maxLines = 1) }
            }
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
            .background(SurfaceLevel1)
            .border(0.5.dp, StudioDivider)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(SurfaceLevel2)
                .clickable { onHeaderClick() }
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = StudioText,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onExpandClick, modifier = Modifier.size(24.dp)) {
                Icon(
                    if (isExpanded) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
                    contentDescription = "Expand $title dock",
                    tint = StudioTextDim,
                    modifier = Modifier.size(13.dp)
                )
            }
            Text(
                text = if (collapsed) "▲" else "▼",
                style = MaterialTheme.typography.labelSmall,
                color = StudioTextDim
            )
        }
        if (!collapsed) {
            content()
        }
    }
}

@Composable
private fun DockFooterBar(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(StudioBg)
            .border(0.5.dp, StudioDivider)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        content = content
    )
}

@Composable
private fun AudioChannelRow(
    channel: AudioChannelState,
    masterMuted: Boolean,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onToggleLock: () -> Unit,
    onOpenFilters: () -> Unit
) {
    val dbFormatted = String.format(Locale.US, "%.1f dB", channel.peakDb)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceLevel2, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${channel.label} · ${channel.activeRouteLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = StudioText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (channel.isClipping) "CLIP" else dbFormatted,
                style = MaterialTheme.typography.labelSmall,
                color = if (channel.isClipping) MeterRed else StudioTextDim
            )
        }
        if (channel.unavailableReason != null) {
            Text(
                text = channel.unavailableReason,
                style = MaterialTheme.typography.labelSmall,
                color = StatusWarn,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else if (channel.filters.anyActive()) {
            Text(
                text = "${channel.filters.activeSummary()} (${channel.measuredDspMicros} µs)",
                style = MaterialTheme.typography.labelSmall,
                color = StatusOk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        ObsSegmentedMeter(
            peakDb = if (channel.muted || masterMuted) -60f else channel.peakDb,
            modifier = Modifier.fillMaxWidth().height(7.dp).padding(vertical = 1.dp)
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = channel.volume,
                onValueChange = { if (!channel.locked) onVolumeChange(it) },
                valueRange = 0f..1.5f,
                enabled = !channel.locked,
                colors = SliderDefaults.colors(
                    thumbColor = StudioText,
                    activeTrackColor = ApertureRedHi,
                    inactiveTrackColor = MeterTrack
                ),
                modifier = Modifier.weight(1f).height(24.dp)
            )
            IconButton(
                onClick = onOpenFilters,
                modifier = Modifier.size(28.dp).testTag("audio_filter_btn_${channel.id}")
            ) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = "Audio filters for ${channel.label}",
                    tint = if (channel.filters.anyActive()) ApertureRedHi else StudioTextDim,
                    modifier = Modifier.size(15.dp)
                )
            }
            IconButton(onClick = onToggleMute, modifier = Modifier.size(28.dp)) {
                Icon(
                    if (channel.muted || masterMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Mute ${channel.label}",
                    tint = if (channel.muted || masterMuted) MeterRed else StudioText,
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(onClick = onToggleLock, modifier = Modifier.size(28.dp)) {
                Icon(
                    if (channel.locked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = "Lock ${channel.label} fader",
                    tint = if (channel.locked) StatusWarn else StudioTextDim,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

@Composable
private fun ObsSegmentedMeter(peakDb: Float, modifier: Modifier = Modifier) {
    val norm = ((peakDb + 60f) / 60f).coerceIn(0f, 1f)
    Canvas(modifier = modifier.background(MeterTrack)) {
        val w = size.width
        val h = size.height
        val activeW = w * norm
        val greenEnd = w * 0.72f
        val yellowEnd = w * 0.90f

        if (activeW > 0f) {
            drawRect(color = MeterGreen, topLeft = Offset.Zero, size = Size(minOf(activeW, greenEnd), h))
        }
        if (activeW > greenEnd) {
            drawRect(color = MeterYellow, topLeft = Offset(greenEnd, 0f), size = Size(minOf(activeW, yellowEnd) - greenEnd, h))
        }
        if (activeW > yellowEnd) {
            drawRect(color = MeterRed, topLeft = Offset(yellowEnd, 0f), size = Size(activeW - yellowEnd, h))
        }
    }
}
