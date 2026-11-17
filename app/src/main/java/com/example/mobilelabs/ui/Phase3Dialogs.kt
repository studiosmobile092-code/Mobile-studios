package com.example.mobilelabs.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.mobilelabs.model.AdaptiveQualityLevel
import com.example.mobilelabs.model.GamingScenePreset
import com.example.mobilelabs.model.MobileLabsUiState
import com.example.mobilelabs.probes.SamsungOneUiAdvisor
import com.example.ui.theme.ApertureRed
import com.example.ui.theme.ObsBorder
import com.example.ui.theme.ObsControlBtn
import com.example.ui.theme.ObsSelectionBlue
import com.example.ui.theme.ObsWellBg
import com.example.ui.theme.ObsWindowBg
import com.example.ui.theme.StatusOk
import com.example.ui.theme.StatusWarn
import com.example.ui.theme.StudioText
import com.example.ui.theme.StudioTextDim
import java.util.Locale

@Composable
fun GamingScenePresetsDialog(
    onDismiss: () -> Unit,
    onApplyPreset: (GamingScenePreset) -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = ObsWindowBg,
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(1.dp, ObsBorder),
            modifier = Modifier.fillMaxWidth(0.76f).fillMaxHeight(0.84f)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("One-Tap Gaming & Creator Scene Packs", style = MaterialTheme.typography.titleMedium, color = StudioText)
                    TextButton(onClick = onDismiss) { Text("Close", color = StudioText) }
                }
                Text(
                    "Each preset generates standard editable OBS scenes and sources you can customize immediately.",
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioTextDim
                )
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GamingScenePreset.entries.forEach { preset ->
                        Surface(
                            color = ObsWellBg,
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(1.dp, ObsBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(preset.label, style = MaterialTheme.typography.titleSmall, color = StudioText)
                                    Text(preset.description, style = MaterialTheme.typography.bodySmall, color = StudioTextDim)
                                }
                                Button(
                                    onClick = {
                                        onApplyPreset(preset)
                                        onDismiss()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = ObsSelectionBlue),
                                    modifier = Modifier.testTag("apply_gaming_preset_${preset.name.lowercase()}")
                                ) { Text("Apply", color = StudioText) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SamsungAndAdaptiveDialog(
    state: MobileLabsUiState,
    onDismiss: () -> Unit,
    onToggleAdaptiveLock: (Boolean) -> Unit,
    onResetAdaptive: () -> Unit,
    onRefreshSamsung: () -> Unit
) {
    val context = LocalContext.current
    val sam = state.samsungStatus
    val aq = state.adaptiveQuality

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = ObsWindowBg,
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(1.dp, ObsBorder),
            modifier = Modifier.fillMaxWidth(0.84f).fillMaxHeight(0.86f)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Adaptive Quality Engine (M3.1) & One UI Advisor (M3.3)", style = MaterialTheme.typography.titleMedium, color = StudioText)
                    TextButton(onClick = onDismiss) { Text("Close", color = StudioText) }
                }

                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(color = ObsWellBg, shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, ObsBorder), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Adaptive Level: ${aq.level.shortTag} — ${aq.level.title}", style = MaterialTheme.typography.titleSmall, color = if (aq.level == AdaptiveQualityLevel.L0_NORMAL) StatusOk else StatusWarn)
                                OutlinedButton(onClick = onResetAdaptive) { Text("Reset to L0", color = StudioText) }
                            }
                            Text("Status: ${aq.lastChangeReason}", style = MaterialTheme.typography.bodySmall, color = StudioTextDim)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Don't adapt (lock current preset & bitrate)", style = MaterialTheme.typography.bodySmall, color = StudioText)
                                Switch(
                                    checked = aq.lockedByUser,
                                    onCheckedChange = onToggleAdaptiveLock,
                                    modifier = Modifier.testTag("adaptive_lock_switch")
                                )
                            }
                            if (aq.decisionHistory.isNotEmpty()) {
                                HorizontalDivider(color = ObsBorder)
                                Text("Recent Hysteresis Transitions:", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                                aq.decisionHistory.takeLast(5).forEach { h ->
                                    Text("• $h", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                                }
                            }
                        }
                    }

                    Surface(color = ObsWellBg, shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, ObsBorder), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Device: ${sam.deviceDisplayModel} (${sam.ramTierLabel})", style = MaterialTheme.typography.titleSmall, color = StudioText)
                                OutlinedButton(onClick = onRefreshSamsung) { Text("Refresh", color = StudioText) }
                            }
                            Text(
                                text = "Battery Optimization Exempt: ${if (sam.isBatteryOptimizationExempt) "YES (Unrestricted)" else "NO (May throttle background stream)"} · Display Max: ${sam.displayMaxRefreshHz.toInt()} Hz",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (sam.isBatteryOptimizationExempt) StatusOk else StatusWarn
                            )
                            sam.guidanceNotes.forEach { note ->
                                Text("• $note", style = MaterialTheme.typography.bodySmall, color = StudioTextDim)
                            }
                            if (!sam.isBatteryOptimizationExempt) {
                                Button(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(SamsungOneUiAdvisor.buildBatteryOptimizationSettingsIntent())
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = ObsSelectionBlue)
                                ) {
                                    Text("Open Battery Optimization Settings", color = StudioText)
                                }
                            }
                        }
                    }

                    Surface(color = ObsWellBg, shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, ObsBorder), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Graphics Backend Decision (V3 Formal Close)", style = MaterialTheme.typography.titleSmall, color = StudioText)
                            Text(
                                "Production Backend: ${state.vulkanDecisionLabel}\n" +
                                    "GLES3/EGL zero-copy OES textures remain production default; Vulkan stays gated until tablet hardware passes A/B benchmarks with zero regressions.",
                                style = MaterialTheme.typography.bodySmall,
                                color = StudioTextDim
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OnboardingAndAutoTuneDialog(
    state: MobileLabsUiState,
    onDismiss: () -> Unit,
    onRunAutoTune: () -> Unit,
    onTestRtmp: (String, (Boolean, String) -> Unit) -> Unit,
    onOpenPresets: () -> Unit,
    onOpenStreamSettings: () -> Unit,
    onArmCapture: () -> Unit,
    onCompleteOnboarding: () -> Unit
) {
    var rtmpTestStatus by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = ObsWindowBg,
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(1.dp, ObsBorder),
            modifier = Modifier.fillMaxWidth(0.80f).fillMaxHeight(0.84f)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("OBSmobile Quick-Start & Auto-Tune Wizard", style = MaterialTheme.typography.titleMedium, color = StudioText, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onCompleteOnboarding(); onDismiss() }) { Text("Skip to OBS Studio", color = StudioTextDim) }
                }

                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Get live under 5 minutes cleanly on Samsung Galaxy Tab A9+:",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextDim
                    )

                    Surface(color = ObsWellBg, shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, ObsBorder), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("1. Hardware Auto-Tune Probe", style = MaterialTheme.typography.titleSmall, color = StudioText)
                            Text(
                                state.autoTuneResult?.summary ?: "Probes hardware H.264 MediaCodec encoders, RAM tier, and thermal headroom to pick Balanced 720p60 or Low-Overhead 720p30.",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.autoTuneResult != null) StatusOk else StudioTextDim
                            )
                            Button(
                                onClick = onRunAutoTune,
                                colors = ButtonDefaults.buttonColors(containerColor = ObsSelectionBlue),
                                modifier = Modifier.testTag("run_autotune_button")
                            ) { Text("Run Auto-Tune & Apply Recommended", color = StudioText) }
                        }
                    }

                    Surface(color = ObsWellBg, shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, ObsBorder), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("2. RTMP(S) Destination & Reachability Test", style = MaterialTheme.typography.titleSmall, color = StudioText)
                            Text("Server: ${state.rtmpServerUrl} · Key saved: ${if (state.hasStreamKeySaved) "YES" else "NO"}", style = MaterialTheme.typography.bodySmall, color = StudioTextDim)
                            if (rtmpTestStatus != null) {
                                Text(rtmpTestStatus.orEmpty(), style = MaterialTheme.typography.labelMedium, color = if (rtmpTestStatus?.contains("OK") == true) StatusOk else StatusWarn)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onOpenStreamSettings) { Text("Configure URL & Key", color = StudioText) }
                                Button(
                                    enabled = !testing,
                                    onClick = {
                                        testing = true
                                        rtmpTestStatus = "Testing handshake..."
                                        onTestRtmp(state.rtmpServerUrl) { _, msg ->
                                            testing = false
                                            rtmpTestStatus = msg
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = ObsControlBtn)
                                ) { Text("Test RTMP Connection", color = StudioText) }
                            }
                        }
                    }

                    Surface(color = ObsWellBg, shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, ObsBorder), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("3. Gaming Scene Preset & Capture Consent", style = MaterialTheme.typography.titleSmall, color = StudioText)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onOpenPresets) { Text("Pick Gaming Scene Preset", color = StudioText) }
                                Button(
                                    onClick = { onCompleteOnboarding(); onDismiss(); onArmCapture() },
                                    colors = ButtonDefaults.buttonColors(containerColor = ApertureRed)
                                ) { Text("Arm Full-Screen Capture", color = StudioText) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BenchmarkKitDialog(
    state: MobileLabsUiState,
    onDismiss: () -> Unit,
    onLogBenchmark: (appName: String, gameName: String, avgFps: Float, onePctLow: Float, notes: String) -> Unit,
    onDeleteBenchmark: (String) -> Unit,
    onExportDiagnosticsZip: () -> Unit
) {
    var appName by remember { mutableStateOf("OBSmobile") }
    var gameName by remember { mutableStateOf("Geometry Dash") }
    var avgFpsText by remember { mutableStateOf("60.0") }
    var onePctLowText by remember { mutableStateOf("57.0") }
    var notes by remember { mutableStateOf("720p60 Balanced · 30 min test") }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = ObsWindowBg,
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(1.dp, ObsBorder),
            modifier = Modifier.fillMaxWidth(0.86f).fillMaxHeight(0.88f)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("OBSmobile Benchmark Kit (§14.4)", style = MaterialTheme.typography.titleMedium, color = StudioText, modifier = Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onExportDiagnosticsZip) { Text("Export ZIP + Benchmarks", color = StudioText) }
                        TextButton(onClick = onDismiss) { Text("Close", color = StudioText) }
                    }
                }

                Text(
                    "Live Telemetry Right Now -> Compositor: ${String.format(Locale.US, "%.1f", state.telemetry.compositorFps)} fps · PSS: ${state.telemetry.processPssMb} MB · Thermal: ${state.telemetry.thermal.statusLabel}",
                    style = MaterialTheme.typography.labelMedium,
                    color = StatusOk
                )

                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    if (maxWidth >= 680.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(value = appName, onValueChange = { appName = it }, label = { Text("App (OBSmobile / Prism / Streamlabs)") }, singleLine = true, modifier = Modifier.weight(1f))
                            OutlinedTextField(value = gameName, onValueChange = { gameName = it }, label = { Text("Game") }, singleLine = true, modifier = Modifier.weight(0.7f))
                            OutlinedTextField(value = avgFpsText, onValueChange = { avgFpsText = it }, label = { Text("Avg FPS") }, singleLine = true, modifier = Modifier.weight(0.45f))
                            OutlinedTextField(value = onePctLowText, onValueChange = { onePctLowText = it }, label = { Text("1% Low") }, singleLine = true, modifier = Modifier.weight(0.45f))
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(value = appName, onValueChange = { appName = it }, label = { Text("App (OBSmobile / Prism / Streamlabs)") }, singleLine = true, modifier = Modifier.weight(1f))
                                OutlinedTextField(value = gameName, onValueChange = { gameName = it }, label = { Text("Game") }, singleLine = true, modifier = Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(value = avgFpsText, onValueChange = { avgFpsText = it }, label = { Text("Avg FPS") }, singleLine = true, modifier = Modifier.weight(1f))
                                OutlinedTextField(value = onePctLowText, onValueChange = { onePctLowText = it }, label = { Text("1% Low") }, singleLine = true, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Run Notes") }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(
                        onClick = {
                            onLogBenchmark(
                                appName,
                                gameName,
                                avgFpsText.toFloatOrNull() ?: 0f,
                                onePctLowText.toFloatOrNull() ?: 0f,
                                notes
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ObsSelectionBlue)
                    ) { Text("Record Run", color = StudioText) }
                }

                HorizontalDivider(color = ObsBorder)
                Text("Logged Runs (${state.benchmarkRecords.size}) — Unmeasured competitors remain UNTESTED until entered on Tab A9+", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)

                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(state.benchmarkRecords, key = { it.id }) { r ->
                        Surface(color = ObsWellBg, shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, ObsBorder), modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${r.appName} · ${r.gameName} (${r.presetLabel}) — ${r.recordedAtIso}", style = MaterialTheme.typography.labelMedium, color = StudioText, fontWeight = FontWeight.SemiBold)
                                    Text("Avg: ${r.gameAvgFps} fps · 1% Low: ${r.gameOnePercentLowFps} fps · Drops: ${String.format(Locale.US, "%.2f", r.streamDroppedPct)}% · Thermal: ${r.peakThermalLabel} · PSS: ${r.processPssMb} MB", style = MaterialTheme.typography.labelSmall, color = StatusOk)
                                    if (r.notes.isNotBlank()) {
                                        Text(r.notes, style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                                    }
                                }
                                TextButton(onClick = { onDeleteBenchmark(r.id) }) { Text("Delete", color = StatusWarn) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LicensesAndAboutDialog(
    state: MobileLabsUiState,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = ObsWindowBg,
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(1.dp, ObsBorder),
            modifier = Modifier.fillMaxWidth(0.76f).fillMaxHeight(0.82f)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("About OBSmobile · SBOM & GPL-2.0-or-later Source Offer", style = MaterialTheme.typography.titleMedium, color = StudioText, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Close", color = StudioText) }
                }
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Version: ${state.appVersionLabel} · Core Engine: ${state.libObsVersion} · ${state.engineBackend.label}", style = MaterialTheme.typography.labelMedium, color = StatusOk)
                    HorizontalDivider(color = ObsBorder)
                    Text("GPL-2.0-or-later Compliance & Written Source Offer", style = MaterialTheme.typography.titleSmall, color = StudioText)
                    Text(
                        "OBSmobile incorporates libobs from OBS Studio (pinned upstream commit 57bfcf10a, version 32.2.1) licensed under GNU General Public License v2.0 or later (GPL-2.0-or-later). All C/C++ JNI glue, MediaCodec encoder bindings, and build scripts (scripts/fetch_and_build_obs.sh) are included in the public source repository alongside full notice and attribution.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextDim
                    )
                    HorizontalDivider(color = ObsBorder)
                    Text("Software Bill of Materials (SBOM)", style = MaterialTheme.typography.titleSmall, color = StudioText)
                    Text(
                        "• OBS Studio libobs (32.2.1 · GPL-2.0-or-later)\n" +
                            "• AndroidX Jetpack Compose & Material 3 (Apache 2.0)\n" +
                            "• Kotlin Coroutines & DataStore Preferences (Apache 2.0)\n" +
                            "• Zero advertising SDKs · Zero third-party analytics or crash trackers\n" +
                            "• Stream credentials stored exclusively in Android Keystore AES-256-GCM",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioTextDim
                    )
                }
            }
        }
    }
}
