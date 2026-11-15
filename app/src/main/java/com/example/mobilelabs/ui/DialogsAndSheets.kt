package com.example.mobilelabs.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.mobilelabs.model.DownscaleFilter
import com.example.mobilelabs.model.MobileLabsUiState
import com.example.mobilelabs.model.PresetId
import com.example.mobilelabs.model.RecordingFormat
import com.example.mobilelabs.model.RtmpServicePreset
import com.example.mobilelabs.model.ScreenFitMode
import com.example.mobilelabs.model.SessionPhase
import com.example.mobilelabs.model.VideoConfig
import com.example.ui.theme.ApertureRed
import com.example.ui.theme.ApertureRedHi
import com.example.ui.theme.ObsBorder
import com.example.ui.theme.ObsHeaderBg
import com.example.ui.theme.ObsSelectionBlue
import com.example.ui.theme.ObsWellBg
import com.example.ui.theme.ObsWindowBg
import com.example.ui.theme.StatusOk
import com.example.ui.theme.StatusWarn
import com.example.ui.theme.StudioBg
import com.example.ui.theme.StudioDivider
import com.example.ui.theme.StudioText
import com.example.ui.theme.StudioTextDim
import com.example.ui.theme.SurfaceLevel1
import com.example.ui.theme.SurfaceLevel2

data class SettingsDialogCallbacks(
    val onDismiss: () -> Unit,
    val onApplyPreset: (PresetId) -> Unit,
    val onUpdateVideoConfig: (VideoConfig) -> Unit,
    val onUpdateScreenFit: (ScreenFitMode, Float) -> Unit,
    val onSaveStreamCredentials: (RtmpServicePreset, String, String?) -> Unit,
    val onSaveSecondaryCredentials: (Boolean, RtmpServicePreset, String, String?) -> Unit,
    val onToggleAdaptiveLock: (Boolean) -> Unit,
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
    val onOpenAboutLicenses: () -> Unit,
    val onAudioSyncOffset: (String, Int) -> Unit,
    val onSetMultiTrackAudioEnabled: (Boolean) -> Unit,
    val onSetSkipGoLivePreFlight: (Boolean) -> Unit
)

@Composable
fun SettingsDialog(
    state: MobileLabsUiState,
    callbacks: SettingsDialogCallbacks
) {
    var tabIndex by remember { mutableIntStateOf(0) }
    var selectedPreset by remember { mutableStateOf(state.rtmpServicePreset) }
    var urlText by remember { mutableStateOf(state.rtmpServerUrl) }
    var streamKeyInput by remember { mutableStateOf("") }

    var dualEnabled by remember { mutableStateOf(state.dualDestination.enabled) }
    var secPreset by remember { mutableStateOf(state.dualDestination.preset) }
    var secUrlText by remember { mutableStateOf(state.dualDestination.serverUrl) }
    var secKeyInput by remember { mutableStateOf("") }

    var newProfileName by remember { mutableStateOf("") }
    var newCollectionName by remember { mutableStateOf("") }
    var showOverlayRationale by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = callbacks.onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = ObsWindowBg,
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(1.dp, ObsBorder),
            modifier = Modifier.fillMaxWidth(0.88f).fillMaxHeight(0.90f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Settings", style = MaterialTheme.typography.titleMedium, color = StudioText)
                    TextButton(onClick = callbacks.onDismiss) { Text("Close", color = StudioText) }
                }

                TabRow(selectedTabIndex = tabIndex, containerColor = ObsHeaderBg, contentColor = StudioText) {
                    Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("Stream") })
                    Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("Output") })
                    Tab(selected = tabIndex == 2, onClick = { tabIndex = 2 }, text = { Text("Audio") })
                    Tab(selected = tabIndex == 3, onClick = { tabIndex = 3 }, text = { Text("Video") })
                    Tab(selected = tabIndex == 4, onClick = { tabIndex = 4 }, text = { Text("Advanced") })
                }

                Spacer(Modifier.height(10.dp))

                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when (tabIndex) {
                        0 -> {
                            Text("Primary RTMP / RTMPS Service Preset", style = MaterialTheme.typography.titleSmall, color = StudioText)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RtmpServicePreset.entries.forEach { preset ->
                                    val active = selectedPreset == preset
                                    OutlinedButton(
                                        onClick = {
                                            selectedPreset = preset
                                            urlText = preset.defaultUrl
                                        },
                                        border = BorderStroke(1.dp, if (active) ObsSelectionBlue else ObsBorder),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = if (active) ObsSelectionBlue.copy(alpha = 0.4f) else ObsWellBg
                                        )
                                    ) { Text(preset.label, color = StudioText) }
                                }
                            }
                            OutlinedTextField(
                                value = urlText,
                                onValueChange = { urlText = it },
                                label = { Text("Primary RTMP / RTMPS Server URL") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("rtmp_url_input")
                            )
                            OutlinedTextField(
                                value = streamKeyInput,
                                onValueChange = { streamKeyInput = it },
                                label = { Text("Primary Stream Key (Android Keystore AES-256-GCM)") },
                                placeholder = { Text("Saved: ${state.maskedStreamKeyPreview}") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("stream_key_input")
                            )
                            Button(
                                onClick = {
                                    callbacks.onSaveStreamCredentials(selectedPreset, urlText, streamKeyInput.ifBlank { null })
                                    streamKeyInput = ""
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ObsSelectionBlue),
                                modifier = Modifier.testTag("save_stream_settings_button")
                            ) { Text("Save Primary Credentials", color = StudioText) }

                            HorizontalDivider(color = ObsBorder)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Dual RTMP(S) Destination (M3.4 · Shared HW Encode)", style = MaterialTheme.typography.titleSmall, color = StudioText)
                                    Text(
                                        "Streams the single shared hardware H.264/AAC encode to a second destination without a second encoder.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StudioTextDim
                                    )
                                }
                                Switch(
                                    checked = dualEnabled,
                                    onCheckedChange = { dualEnabled = it },
                                    modifier = Modifier.testTag("dual_rtmp_switch")
                                )
                            }
                            if (dualEnabled) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    RtmpServicePreset.entries.forEach { p ->
                                        val active = secPreset == p
                                        OutlinedButton(
                                            onClick = {
                                                secPreset = p
                                                secUrlText = p.defaultUrl
                                            },
                                            border = BorderStroke(1.dp, if (active) ObsSelectionBlue else ObsBorder)
                                        ) { Text(p.label, color = StudioText) }
                                    }
                                }
                                OutlinedTextField(
                                    value = secUrlText,
                                    onValueChange = { secUrlText = it },
                                    label = { Text("Secondary RTMP / RTMPS Server URL") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = secKeyInput,
                                    onValueChange = { secKeyInput = it },
                                    label = { Text("Secondary Stream Key (Keystore Encrypted)") },
                                    placeholder = { Text("Saved: ${state.dualDestination.maskedKeyPreview}") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    callbacks.onSaveSecondaryCredentials(dualEnabled, secPreset, secUrlText, secKeyInput.ifBlank { null })
                                    secKeyInput = ""
                                },
                                modifier = Modifier.testTag("save_dual_destination_button")
                            ) { Text("Save Dual Destination", color = StudioText) }
                        }
                        1 -> {
                            Text("Encoder Preset Ladder (§8.4)", style = MaterialTheme.typography.titleSmall, color = StudioText)
                            PresetId.entries.forEach { preset ->
                                val selected = state.videoConfig.presetId == preset
                                val locked1080p60 = preset.requires1080p60Gate &&
                                    state.deviceReport?.encoders?.none {
                                        it.isHardwareAccelerated && it.supportsSurfaceInput && it.supports1080p60
                                    } != false
                                Surface(
                                    color = if (selected) ObsSelectionBlue.copy(alpha = 0.45f) else ObsWellBg,
                                    shape = RoundedCornerShape(4.dp),
                                    border = BorderStroke(1.dp, if (selected) ObsSelectionBlue else ObsBorder),
                                    modifier = Modifier.fillMaxWidth().clickable(enabled = !locked1080p60) { callbacks.onApplyPreset(preset) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(preset.title, style = MaterialTheme.typography.bodyMedium, color = StudioText, fontWeight = FontWeight.SemiBold)
                                            Text(preset.note, style = MaterialTheme.typography.bodySmall, color = StudioTextDim)
                                        }
                                        Text(
                                            text = "${preset.outputWidth}x${preset.outputHeight} @${preset.fps} · ${preset.videoBitrateKbps}k",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (locked1080p60) StatusWarn else StatusOk
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(color = ObsBorder)
                            Text("Recording Container & Output Folder (SAF)", style = MaterialTheme.typography.titleSmall, color = StudioText)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RecordingFormat.entries.forEach { fmt ->
                                    val active = state.videoConfig.recordingFormat == fmt
                                    OutlinedButton(
                                        onClick = { callbacks.onUpdateVideoConfig(state.videoConfig.copy(recordingFormat = fmt)) },
                                        border = BorderStroke(1.dp, if (active) ObsSelectionBlue else ObsBorder)
                                    ) { Text(fmt.label, color = StudioText) }
                                }
                            }
                            Text(
                                "Folder: ${state.customRecordingFolderUri ?: "Movies/Mobile Labs (Default MediaStore)"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = StudioTextDim
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = callbacks.onPickRecordingFolder) { Text("Pick Recording Folder (SAF)", color = StudioText) }
                                if (state.customRecordingFolderUri != null) {
                                    TextButton(onClick = callbacks.onResetRecordingFolder) { Text("Reset Default", color = StudioTextDim) }
                                }
                            }
                            HorizontalDivider(color = ObsBorder)
                            Row(
                                modifier = Modifier.fillMaxWidth().background(ObsWellBg, RoundedCornerShape(4.dp)).padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Multi-Track MP4 Recording (3 Audio Buses)", style = MaterialTheme.typography.bodyMedium, color = StudioText)
                                    Text(
                                        "Records Track 1 (Master Mix) + Track 2 (Isolated Mic) + Track 3 (Isolated Game Audio) for DaVinci Resolve / Premiere Pro.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StudioTextDim
                                    )
                                }
                                Switch(
                                    checked = state.videoConfig.multiTrackAudioEnabled,
                                    onCheckedChange = callbacks.onSetMultiTrackAudioEnabled,
                                    enabled = state.recordingPhase == com.example.mobilelabs.model.RecordingPhase.IDLE,
                                    modifier = Modifier.testTag("multitrack_audio_switch")
                                )
                            }
                        }
                        2 -> {
                            Text("Audio Engine Configuration", style = MaterialTheme.typography.titleSmall, color = StudioText)
                            Text("Sample Rate: 48,000 Hz Stereo (Fixed for OBS/MediaCodec compatibility)", style = MaterialTheme.typography.bodySmall, color = StudioTextDim)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(96, 128, 160, 192).forEach { kbps ->
                                    val active = state.videoConfig.audioBitrateKbps == kbps
                                    OutlinedButton(
                                        onClick = { callbacks.onUpdateVideoConfig(state.videoConfig.copy(audioBitrateKbps = kbps)) },
                                        border = BorderStroke(1.dp, if (active) ObsSelectionBlue else ObsBorder)
                                    ) { Text("$kbps kbps", color = StudioText) }
                                }
                            }
                            HorizontalDivider(color = ObsBorder)
                            Text("Per-Channel Audio Sync Offset (-500 to +500 ms)", style = MaterialTheme.typography.titleSmall, color = StudioText)
                            Text(
                                "Delay Game Audio or Mic/Aux to eliminate lip-sync or game-rhythm offset.",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioTextDim
                            )
                            state.audioChannels.forEach { ch ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(ObsWellBg, RoundedCornerShape(4.dp))
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("${ch.label}: ${ch.syncOffsetMs} ms", style = MaterialTheme.typography.labelMedium, color = StudioText)
                                        TextButton(onClick = { callbacks.onAudioSyncOffset(ch.id, 0) }) {
                                            Text("0 ms", color = StudioTextDim)
                                        }
                                    }
                                    Slider(
                                        value = ch.syncOffsetMs.toFloat(),
                                        onValueChange = { callbacks.onAudioSyncOffset(ch.id, it.toInt()) },
                                        valueRange = -500f..500f,
                                        modifier = Modifier.testTag("audio_sync_slider_${ch.id}")
                                    )
                                }
                            }
                            Text(
                                "Per-channel Noise Suppression, Gain, Noise Gate, Compressor, and Limiter are available via the Gear icon inside the Audio Mixer dock.",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioTextDim
                            )
                        }
                        3 -> {
                            val isIdle = state.sessionPhase == SessionPhase.IDLE
                            Text("Independent Canvas & Output Scaling (Rule R7)", style = MaterialTheme.typography.titleSmall, color = StudioText)
                            Text(
                                "Canvas: ${state.videoConfig.canvasWidth}x${state.videoConfig.canvasHeight} → Output: ${state.videoConfig.outputWidth}x${state.videoConfig.outputHeight} @${state.videoConfig.fps} fps",
                                style = MaterialTheme.typography.labelMedium,
                                color = StudioText
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { callbacks.onUpdateVideoConfig(state.videoConfig.copy(canvasWidth = 1920, canvasHeight = 1080, outputWidth = 1280, outputHeight = 720)) },
                                    enabled = isIdle,
                                    modifier = Modifier.testTag("test_canvas_1080_output_720_button")
                                ) { Text("Canvas 1080p → Out 720p", color = StudioText) }
                                OutlinedButton(
                                    onClick = { callbacks.onUpdateVideoConfig(state.videoConfig.copy(canvasWidth = 1280, canvasHeight = 720, outputWidth = 1280, outputHeight = 720, fps = 60)) },
                                    enabled = isIdle
                                ) { Text("720p60 Native", color = StudioText) }
                            }
                            if (!isIdle) {
                                Text("Disarm session to change canvas/output dimensions.", style = MaterialTheme.typography.labelSmall, color = StatusWarn)
                            }
                            Text("Downscale Filter", style = MaterialTheme.typography.titleSmall, color = StudioText)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DownscaleFilter.entries.forEach { df ->
                                    val active = state.videoConfig.downscaleFilter == df
                                    OutlinedButton(
                                        onClick = { callbacks.onUpdateVideoConfig(state.videoConfig.copy(downscaleFilter = df)) },
                                        border = BorderStroke(1.dp, if (active) ObsSelectionBlue else ObsBorder)
                                    ) { Text(df.label, color = StudioText) }
                                }
                            }
                            HorizontalDivider(color = ObsBorder)
                            Text("16:10 Tablet Display → 16:9 Stream Fit (§6.2)", style = MaterialTheme.typography.titleSmall, color = StudioText)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ScreenFitMode.entries.forEach { mode ->
                                    val active = state.videoConfig.screenFitMode == mode
                                    OutlinedButton(
                                        onClick = { callbacks.onUpdateScreenFit(mode, state.videoConfig.screenCropOffsetYFraction) },
                                        border = BorderStroke(1.dp, if (active) ObsSelectionBlue else ObsBorder)
                                    ) { Text(mode.label, color = StudioText) }
                                }
                            }
                            if (state.videoConfig.screenFitMode == ScreenFitMode.CROP_TO_FILL) {
                                Text("Vertical Crop Window Position", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                                Slider(
                                    value = state.videoConfig.screenCropOffsetYFraction,
                                    onValueChange = { callbacks.onUpdateScreenFit(ScreenFitMode.CROP_TO_FILL, it) }
                                )
                            }
                        }
                        4 -> {
                            Text("Profiles & Scene Collections (M2.5)", style = MaterialTheme.typography.titleSmall, color = StudioText)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = newProfileName,
                                    onValueChange = { newProfileName = it },
                                    label = { Text("New Profile Name") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = {
                                        callbacks.onCreateProfile(newProfileName)
                                        newProfileName = ""
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = ObsSelectionBlue)
                                ) { Text("Save Profile", color = StudioText) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                state.profiles.forEach { prof ->
                                    val active = prof.id == state.activeProfileId
                                    OutlinedButton(
                                        onClick = { callbacks.onSelectProfile(prof.id) },
                                        border = BorderStroke(1.dp, if (active) ObsSelectionBlue else ObsBorder)
                                    ) { Text(prof.name, color = StudioText) }
                                    if (state.profiles.size > 1 && !active) {
                                        TextButton(onClick = { callbacks.onDeleteProfile(prof.id) }) { Text("✕", color = StatusWarn) }
                                    }
                                }
                            }

                            HorizontalDivider(color = ObsBorder)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = newCollectionName,
                                    onValueChange = { newCollectionName = it },
                                    label = { Text("Scene Collection Name") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedButton(onClick = {
                                    callbacks.onCreateOrDupCollection(newCollectionName, true)
                                    newCollectionName = ""
                                }) { Text("Duplicate", color = StudioText) }
                                OutlinedButton(onClick = {
                                    callbacks.onCreateOrDupCollection(newCollectionName, false)
                                    newCollectionName = ""
                                }) { Text("New", color = StudioText) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                state.savedCollectionNames.forEach { colName ->
                                    val active = colName == state.sceneCollection.name
                                    OutlinedButton(
                                        onClick = { callbacks.onSwitchCollection(colName) },
                                        border = BorderStroke(1.dp, if (active) ObsSelectionBlue else ObsBorder)
                                    ) { Text(colName, color = StudioText) }
                                    if (state.savedCollectionNames.size > 1 && !active) {
                                        TextButton(onClick = { callbacks.onDeleteCollection(colName) }) { Text("✕", color = StatusWarn) }
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = callbacks.onExportObsJson,
                                    colors = ButtonDefaults.buttonColors(containerColor = ObsSelectionBlue),
                                    modifier = Modifier.testTag("export_obs_json_button")
                                ) { Text("Export OBS JSON", color = StudioText) }
                                OutlinedButton(
                                    onClick = callbacks.onImportObsJson,
                                    modifier = Modifier.testTag("import_obs_json_button")
                                ) { Text("Import OBS JSON", color = StudioText) }
                            }

                            HorizontalDivider(color = ObsBorder)
                            Row(
                                modifier = Modifier.fillMaxWidth().background(ObsWellBg, RoundedCornerShape(4.dp)).padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.overlay_permission_title), style = MaterialTheme.typography.bodyMedium, color = StudioText)
                                    Text(
                                        if (state.hasOverlayPermission) "Permission granted · Quick control bar v2" else "Requires SYSTEM_ALERT_WINDOW · Disabled by default",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = StudioTextDim
                                    )
                                }
                                Switch(
                                    checked = state.floatingOverlayEnabled,
                                    onCheckedChange = { enable ->
                                        if (enable && !state.hasOverlayPermission) showOverlayRationale = true
                                        else callbacks.onRequestEnableOverlay(enable)
                                    },
                                    modifier = Modifier.testTag("overlay_toggle_switch")
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth().background(ObsWellBg, RoundedCornerShape(4.dp)).padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Dynamic Network Bitrate & Low Latency Mode", style = MaterialTheme.typography.bodySmall, color = StudioText)
                                Switch(
                                    checked = state.videoConfig.dynamicBitrateEnabled,
                                    onCheckedChange = {
                                        callbacks.onUpdateVideoConfig(state.videoConfig.copy(dynamicBitrateEnabled = it))
                                    }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth().background(ObsWellBg, RoundedCornerShape(4.dp)).padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Lock Adaptive Quality (Don't adapt during stream)", style = MaterialTheme.typography.bodySmall, color = StudioText)
                                Switch(
                                    checked = state.adaptiveQuality.lockedByUser,
                                    onCheckedChange = callbacks.onToggleAdaptiveLock
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth().background(ObsWellBg, RoundedCornerShape(4.dp)).padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Skip Go-Live Pre-Flight Checklist (Start Immediately)", style = MaterialTheme.typography.bodySmall, color = StudioText)
                                Switch(
                                    checked = state.videoConfig.skipGoLivePreFlight,
                                    onCheckedChange = callbacks.onSetSkipGoLivePreFlight,
                                    modifier = Modifier.testTag("skip_preflight_switch")
                                )
                            }

                            OutlinedButton(onClick = callbacks.onOpenAboutLicenses) {
                                Text("About Mobile Labs · SBOM & GPL-2.0 Source Offer", color = StudioText)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showOverlayRationale) {
        AlertDialog(
            onDismissRequest = { showOverlayRationale = false },
            containerColor = SurfaceLevel1,
            title = { Text(stringResource(R.string.overlay_permission_title), color = StudioText) },
            text = { Text(stringResource(R.string.overlay_permission_rationale), color = StudioTextDim) },
            confirmButton = {
                Button(
                    onClick = {
                        showOverlayRationale = false
                        callbacks.onRequestEnableOverlay(true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ApertureRed)
                ) { Text("Grant in Settings", color = StudioText) }
            },
            dismissButton = {
                TextButton(onClick = { showOverlayRationale = false }) { Text("Not Now", color = StudioTextDim) }
            }
        )
    }
}

@Composable
fun DiagnosticsAndProbesDialog(
    state: MobileLabsUiState,
    onDismiss: () -> Unit,
    onRunProbe: () -> Unit,
    onExportZip: () -> Unit,
    onRecoverIncomplete: (String) -> Unit,
    onDeleteIncomplete: (String) -> Unit
) {
    var tabIndex by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = ObsWindowBg,
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(1.dp, ObsBorder),
            modifier = Modifier.fillMaxWidth(0.88f).fillMaxHeight(0.88f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Diagnostics, Codec Probe & Recordings", style = MaterialTheme.typography.titleMedium, color = StudioText)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onExportZip,
                            colors = ButtonDefaults.buttonColors(containerColor = ApertureRed),
                            modifier = Modifier.testTag("export_diagnostics_button")
                        ) { Text("Export Redacted ZIP", color = StudioText) }
                        TextButton(onClick = onDismiss) { Text("Close", color = StudioText) }
                    }
                }
                if (state.lastDiagnosticsExportPath != null) {
                    Text(
                        text = "Last export: ${state.lastDiagnosticsExportPath} (primary & secondary keys stripped)",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusOk
                    )
                }
                TabRow(selectedTabIndex = tabIndex, containerColor = ObsHeaderBg, contentColor = StudioText) {
                    Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("Device & Codec Probe") })
                    Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("Recordings (${state.incompleteRecordings.size} incomplete)") })
                    Tab(selected = tabIndex == 2, onClick = { tabIndex = 2 }, text = { Text("Event Log") })
                }
                Spacer(Modifier.height(8.dp))
                when (tabIndex) {
                    0 -> {
                        val rep = state.deviceReport
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = rep?.let {
                                    "${it.manufacturer} ${it.model} · Android ${it.androidVersion} · ${it.totalRamMb} MB RAM · ${it.displayWidthPx}x${it.displayHeightPx} @${it.displayRefreshHz.toInt()} Hz"
                                } ?: "Probe not run yet",
                                style = MaterialTheme.typography.labelMedium,
                                color = StudioText
                            )
                            OutlinedButton(onClick = onRunProbe, modifier = Modifier.testTag("run_codec_probe_button")) {
                                Text("Run Concurrency Probe", color = StudioText)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(rep?.encoders.orEmpty()) { enc ->
                                Surface(color = ObsWellBg, shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, ObsBorder), modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            text = "${enc.name} (${enc.mimeType}) · ${if (enc.isHardwareAccelerated) "HARDWARE" else "SOFTWARE"}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (enc.isHardwareAccelerated) StatusOk else StatusWarn
                                        )
                                        Text(
                                            text = "Surface: ${enc.supportsSurfaceInput} · 720p30:${enc.supports720p30} 720p60:${enc.supports720p60} 1080p30:${enc.supports1080p30} 1080p60:${enc.supports1080p60} · Max: ${enc.maxSupportedInstances}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = StudioTextDim
                                        )
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (state.incompleteRecordings.isNotEmpty()) {
                                item { Text("Interrupted / Incomplete Recordings", style = MaterialTheme.typography.titleSmall, color = StatusWarn) }
                                items(state.incompleteRecordings) { inc ->
                                    Surface(color = ObsWellBg, shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, ObsBorder), modifier = Modifier.fillMaxWidth()) {
                                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text("${inc.fileName} · ${inc.statusNote}", style = MaterialTheme.typography.labelMedium, color = StatusWarn)
                                            Text("Size: ${inc.bytesOnDisk / 1024} KB · ${inc.filePath}", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedButton(
                                                    onClick = { onRecoverIncomplete(inc.filePath) },
                                                    enabled = inc.verifiedPlayable,
                                                    modifier = Modifier.testTag("recover_recording_btn")
                                                ) { Text("Recover to Library", color = if (inc.verifiedPlayable) StatusOk else StudioTextDim) }
                                                TextButton(
                                                    onClick = { onDeleteIncomplete(inc.filePath) },
                                                    modifier = Modifier.testTag("delete_recording_btn")
                                                ) { Text("Delete", color = ApertureRedHi) }
                                            }
                                        }
                                    }
                                }
                            }
                            item { Text("Finalized Recordings", style = MaterialTheme.typography.titleSmall, color = StudioText) }
                            items(state.recentRecordings) { rec ->
                                Surface(color = ObsWellBg, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text("${rec.fileName} · ${rec.durationSec}s · ${rec.bytesSize / 1024} KB", style = MaterialTheme.typography.labelMedium, color = StatusOk)
                                        Text(rec.mediaStoreUri, style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                                    }
                                }
                            }
                        }
                    }
                    2 -> {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth().background(ObsWellBg, RoundedCornerShape(4.dp)).padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            items(state.eventLog.asReversed()) { line ->
                                Text(line, style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GoLivePreFlightDialog(
    state: MobileLabsUiState,
    onDismiss: () -> Unit,
    onConfirmGoLive: () -> Unit,
    onSetSkipPreFlight: (Boolean) -> Unit,
    onOpenConnections: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val cfg = state.videoConfig
    val ytReady = state.youtubeConnection.connected && state.youtubeConnection.enabledForMultiStream
    val twReady = state.twitchConnection.connected && state.twitchConnection.enabledForMultiStream
    val primaryRtmpReady = state.hasStreamKeySaved && state.rtmpServerUrl.isNotBlank()
    val dualRtmpReady = state.dualDestination.enabled && state.dualDestination.hasKeySaved && state.dualDestination.serverUrl.isNotBlank()
    val hasAnyDestination = ytReady || twReady || primaryRtmpReady || dualRtmpReady

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = ObsWindowBg,
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(1.dp, ObsBorder),
            modifier = Modifier.fillMaxWidth(0.76f)
        ) {
            Column(
                modifier = Modifier.padding(18.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Go-Live Pre-Flight Checklist", style = MaterialTheme.typography.titleMedium, color = StudioText, fontWeight = FontWeight.Bold)
                        Text("Verify destinations, encoder headroom, and audio before going live.", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                    }
                    TextButton(onClick = onDismiss) { Text("Cancel", color = StudioTextDim) }
                }

                HorizontalDivider(color = ObsBorder)

                Surface(color = ObsWellBg, shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, ObsBorder), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("1. Stream Destinations", style = MaterialTheme.typography.titleSmall, color = StudioText)
                        if (ytReady) Text("✓ YouTube Live: ${state.youtubeConnection.streamTitle} (${state.youtubeConnection.privacyStatus})", style = MaterialTheme.typography.bodySmall, color = StatusOk)
                        if (twReady) Text("✓ Twitch: ${state.twitchConnection.streamTitle} (${state.twitchConnection.categoryName})", style = MaterialTheme.typography.bodySmall, color = StatusOk)
                        if (primaryRtmpReady) Text("✓ Primary RTMP: ${state.rtmpServerUrl}", style = MaterialTheme.typography.bodySmall, color = StatusOk)
                        if (dualRtmpReady) Text("✓ Dual RTMP: ${state.dualDestination.serverUrl}", style = MaterialTheme.typography.bodySmall, color = StatusOk)
                        if (!hasAnyDestination) {
                            Text(
                                "No stream destination configured yet. Connect YouTube / Twitch or configure Custom RTMP first.",
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusWarn
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onDismiss(); onOpenConnections() }) { Text("Connect YT / Twitch", color = StudioText) }
                                OutlinedButton(onClick = { onDismiss(); onOpenSettings() }) { Text("RTMP Settings", color = StudioText) }
                            }
                        }
                    }
                }

                Surface(color = ObsWellBg, shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, ObsBorder), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("2. Video, Bitrate ABR & Thermal", style = MaterialTheme.typography.titleSmall, color = StudioText)
                        Text(
                            "Output: ${cfg.outputWidth}x${cfg.outputHeight} @${cfg.fps} fps · ${cfg.videoBitrateKbps} kbps · ABR: ${if (cfg.dynamicBitrateEnabled) "ON" else "OFF"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextDim
                        )
                        Text(
                            "Thermal: ${state.telemetry.thermal.statusLabel} · Multi-Track Audio: ${if (cfg.multiTrackAudioEnabled) "3-Bus MP4 ON" else "Standard Stereo"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.telemetry.thermal.shouldWarnBanner) StatusWarn else StatusOk
                        )
                    }
                }

                Surface(color = ObsWellBg, shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, ObsBorder), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("3. Audio Mixer Check", style = MaterialTheme.typography.titleSmall, color = StudioText)
                        if (state.masterMuted) {
                            Text("⚠ MASTER AUDIO IS MUTED! Viewers will hear silence.", style = MaterialTheme.typography.bodySmall, color = ApertureRedHi, fontWeight = FontWeight.Bold)
                        } else {
                            state.audioChannels.forEach { ch ->
                                val status = if (ch.muted) "MUTED" else "${(ch.volume * 100).toInt()}% (${ch.filters.activeSummary()})"
                                Text("• ${ch.label}: $status", style = MaterialTheme.typography.bodySmall, color = if (ch.muted) StatusWarn else StudioTextDim)
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(
                            checked = cfg.skipGoLivePreFlight,
                            onCheckedChange = onSetSkipPreFlight
                        )
                        Text("Don't show pre-flight check again", style = MaterialTheme.typography.bodySmall, color = StudioTextDim)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onDismiss) { Text("Back", color = StudioText) }
                        Button(
                            onClick = onConfirmGoLive,
                            enabled = hasAnyDestination,
                            colors = ButtonDefaults.buttonColors(containerColor = ApertureRed),
                            modifier = Modifier.height(48.dp).testTag("confirm_go_live_preflight_btn")
                        ) { Text("Start Streaming Now", color = StudioText, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}
