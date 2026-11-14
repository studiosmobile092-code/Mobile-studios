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
    val onRequestEnableOverlay: (Boolean) -> Unit
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
    var newProfileName by remember { mutableStateOf("") }
    var newCollectionName by remember { mutableStateOf("") }
    var showOverlayRationale by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = callbacks.onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = SurfaceLevel1,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, StudioDivider),
            modifier = Modifier.fillMaxWidth(0.88f).fillMaxHeight(0.90f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Mobile Labs Settings", style = MaterialTheme.typography.titleMedium, color = StudioText)
                    TextButton(onClick = callbacks.onDismiss) { Text("Close", color = StudioText) }
                }

                TabRow(selectedTabIndex = tabIndex, containerColor = SurfaceLevel2, contentColor = StudioText) {
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
                            Text("RTMP / RTMPS Service Preset", style = MaterialTheme.typography.titleSmall, color = StudioText)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RtmpServicePreset.entries.forEach { preset ->
                                    val active = selectedPreset == preset
                                    OutlinedButton(
                                        onClick = {
                                            selectedPreset = preset
                                            urlText = preset.defaultUrl
                                        },
                                        border = BorderStroke(1.dp, if (active) ApertureRedHi else StudioDivider),
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            containerColor = if (active) ApertureRed.copy(alpha = 0.4f) else SurfaceLevel2
                                        )
                                    ) { Text(preset.label, color = StudioText) }
                                }
                            }
                            OutlinedTextField(
                                value = urlText,
                                onValueChange = { urlText = it },
                                label = { Text("RTMP / RTMPS Server URL") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("rtmp_url_input")
                            )
                            OutlinedTextField(
                                value = streamKeyInput,
                                onValueChange = { streamKeyInput = it },
                                label = { Text("Stream Key (stored in Android Keystore, never exported)") },
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
                                colors = ButtonDefaults.buttonColors(containerColor = ApertureRed),
                                modifier = Modifier.testTag("save_stream_settings_button")
                            ) { Text("Save Encrypted Credentials", color = StudioText) }
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
                                    color = if (selected) ApertureRed.copy(alpha = 0.35f) else SurfaceLevel2,
                                    shape = RoundedCornerShape(6.dp),
                                    border = BorderStroke(1.dp, if (selected) ApertureRedHi else StudioDivider),
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
                            HorizontalDivider(color = StudioDivider)
                            Text("Recording Container & Output Folder (SAF)", style = MaterialTheme.typography.titleSmall, color = StudioText)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                RecordingFormat.entries.forEach { fmt ->
                                    val active = state.videoConfig.recordingFormat == fmt
                                    OutlinedButton(
                                        onClick = { callbacks.onUpdateVideoConfig(state.videoConfig.copy(recordingFormat = fmt)) },
                                        border = BorderStroke(1.dp, if (active) ApertureRedHi else StudioDivider)
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
                        }
                        2 -> {
                            Text("Audio Engine Configuration", style = MaterialTheme.typography.titleSmall, color = StudioText)
                            Text("Sample Rate: 48,000 Hz Stereo (Fixed for OBS/MediaCodec compatibility)", style = MaterialTheme.typography.bodySmall, color = StudioTextDim)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(96, 128, 160, 192).forEach { kbps ->
                                    val active = state.videoConfig.audioBitrateKbps == kbps
                                    OutlinedButton(
                                        onClick = { callbacks.onUpdateVideoConfig(state.videoConfig.copy(audioBitrateKbps = kbps)) },
                                        border = BorderStroke(1.dp, if (active) ApertureRedHi else StudioDivider)
                                    ) { Text("$kbps kbps", color = StudioText) }
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
                                        border = BorderStroke(1.dp, if (active) ApertureRedHi else StudioDivider)
                                    ) { Text(df.label, color = StudioText) }
                                }
                            }
                            HorizontalDivider(color = StudioDivider)
                            Text("16:10 Tablet Display → 16:9 Stream Fit (§6.2)", style = MaterialTheme.typography.titleSmall, color = StudioText)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ScreenFitMode.entries.forEach { mode ->
                                    val active = state.videoConfig.screenFitMode == mode
                                    OutlinedButton(
                                        onClick = { callbacks.onUpdateScreenFit(mode, state.videoConfig.screenCropOffsetYFraction) },
                                        border = BorderStroke(1.dp, if (active) ApertureRedHi else StudioDivider)
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
                            // Profiles, Scene Collections, OBS JSON Import/Export, Overlay & Licenses
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
                                    colors = ButtonDefaults.buttonColors(containerColor = ApertureRed)
                                ) { Text("Save Profile", color = StudioText) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                state.profiles.forEach { prof ->
                                    val active = prof.id == state.activeProfileId
                                    OutlinedButton(
                                        onClick = { callbacks.onSelectProfile(prof.id) },
                                        border = BorderStroke(1.dp, if (active) ApertureRedHi else StudioDivider)
                                    ) { Text(prof.name, color = StudioText) }
                                    if (state.profiles.size > 1 && !active) {
                                        TextButton(onClick = { callbacks.onDeleteProfile(prof.id) }) { Text("✕", color = StatusWarn) }
                                    }
                                }
                            }

                            HorizontalDivider(color = StudioDivider)
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
                                        border = BorderStroke(1.dp, if (active) ApertureRedHi else StudioDivider)
                                    ) { Text(colName, color = StudioText) }
                                    if (state.savedCollectionNames.size > 1 && !active) {
                                        TextButton(onClick = { callbacks.onDeleteCollection(colName) }) { Text("✕", color = StatusWarn) }
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = callbacks.onExportObsJson,
                                    colors = ButtonDefaults.buttonColors(containerColor = ApertureRed),
                                    modifier = Modifier.testTag("export_obs_json_button")
                                ) { Text("Export OBS JSON", color = StudioText) }
                                OutlinedButton(
                                    onClick = callbacks.onImportObsJson,
                                    modifier = Modifier.testTag("import_obs_json_button")
                                ) { Text("Import OBS JSON", color = StudioText) }
                            }

                            HorizontalDivider(color = StudioDivider)
                            Row(
                                modifier = Modifier.fillMaxWidth().background(SurfaceLevel2, RoundedCornerShape(6.dp)).padding(10.dp),
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
                                modifier = Modifier.fillMaxWidth().background(SurfaceLevel2, RoundedCornerShape(6.dp)).padding(10.dp),
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

                            Surface(color = SurfaceLevel2, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "Mobile Labs integrates the OBS Studio libobs engine (GPL-2.0-or-later, baseline 32.2.1 commit 57bfcf10a) with Android hardware MediaCodec/GLES3 modules.\n" +
                                        "Zero analytics SDKs · Zero ad SDKs · Stream keys encrypted locally in Android Keystore.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StudioTextDim,
                                    modifier = Modifier.padding(10.dp)
                                )
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
    onExportZip: () -> Unit
) {
    var tabIndex by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = SurfaceLevel1,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, StudioDivider),
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
                        text = "Last export: ${state.lastDiagnosticsExportPath} (stream keys stripped)",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusOk
                    )
                }
                TabRow(selectedTabIndex = tabIndex, containerColor = SurfaceLevel2, contentColor = StudioText) {
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
                                Surface(color = SurfaceLevel2, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
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
                                    Surface(color = SurfaceLevel2, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text("${inc.fileName} · ${inc.statusNote}", style = MaterialTheme.typography.labelMedium, color = StatusWarn)
                                            Text("Size: ${inc.bytesOnDisk / 1024} KB · ${inc.filePath}", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                                        }
                                    }
                                }
                            }
                            item { Text("Finalized Recordings", style = MaterialTheme.typography.titleSmall, color = StudioText) }
                            items(state.recentRecordings) { rec ->
                                Surface(color = SurfaceLevel2, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
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
                            modifier = Modifier.weight(1f).fillMaxWidth().background(StudioBg, RoundedCornerShape(6.dp)).padding(8.dp),
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
