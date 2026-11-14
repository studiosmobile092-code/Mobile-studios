package com.example.mobilelabs.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.mobilelabs.model.AudioChannelState
import com.example.mobilelabs.model.AudioFilterConfig
import com.example.mobilelabs.model.LutPreset
import com.example.mobilelabs.model.ScaleFilterType
import com.example.mobilelabs.model.SourceModel
import com.example.mobilelabs.model.SourceType
import com.example.mobilelabs.model.VideoFilterConfig
import com.example.ui.theme.ApertureRed
import com.example.ui.theme.ApertureRedHi
import com.example.ui.theme.StatusOk
import com.example.ui.theme.StatusWarn
import com.example.ui.theme.StudioDivider
import com.example.ui.theme.StudioText
import com.example.ui.theme.StudioTextDim
import com.example.ui.theme.SurfaceLevel1
import com.example.ui.theme.SurfaceLevel2
import java.util.Locale

@Composable
fun AddSourcePhase2Dialog(
    onDismiss: () -> Unit,
    onAddStandardSource: (SourceType) -> Unit,
    onPickImageFile: () -> Unit,
    onPickMediaFile: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceLevel1,
        title = { Text("Add Source", color = StudioText) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SourceType.entries.forEach { type ->
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            when (type) {
                                SourceType.IMAGE_SOURCE -> onPickImageFile()
                                SourceType.MEDIA_SOURCE -> onPickMediaFile()
                                else -> onAddStandardSource(type)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_source_type_${type.name.lowercase()}")
                    ) {
                        Text(type.label, color = StudioText)
                    }
                }
                HorizontalDivider(color = StudioDivider)
                Text(
                    text = "Browser Source deferred in Phase 2 (WebView CPU copy overhead would degrade Geometry Dash/osu! 60fps; see docs).",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioTextDim
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = StudioTextDim) }
        }
    )
}

@Composable
fun SourcePropertiesDialog(
    source: SourceModel,
    onDismiss: () -> Unit,
    onUpdateSource: ((SourceModel) -> SourceModel) -> Unit,
    onRelinkImage: (String) -> Unit,
    onRelinkMedia: (String) -> Unit
) {
    var nameInput by remember(source.id) { mutableStateOf(source.name) }
    var textInput by remember(source.id) { mutableStateOf(source.textContent) }
    var textSize by remember(source.id) { mutableStateOf(source.textSizeSp) }
    var textColor by remember(source.id) { mutableStateOf(source.textColorArgb) }
    var textBgColor by remember(source.id) { mutableStateOf(source.textBgColorArgb) }
    var colorArgb by remember(source.id) { mutableStateOf(source.colorArgb) }
    var mediaLoop by remember(source.id) { mutableStateOf(source.mediaLoop) }
    var mediaMuted by remember(source.id) { mutableStateOf(source.mediaMuted) }
    var mediaPlaying by remember(source.id) { mutableStateOf(source.mediaPlaying) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceLevel1,
        title = { Text("${source.type.label} Properties", color = StudioText) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Source Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                when (source.type) {
                    SourceType.TEXT_SOURCE -> {
                        OutlinedTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            label = { Text("Text Content") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Font Size: ${textSize.toInt()} sp", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        Slider(value = textSize, onValueChange = { textSize = it }, valueRange = 18f..96f)
                        Text("Text Color", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        ColorSwatchRow(
                            selected = textColor,
                            options = listOf(0xFFFFFFFFL, 0xFFF5A524L, 0xFFFF4D4FL, 0xFF43B581L, 0xFF56B4E9L),
                            onSelect = { textColor = it }
                        )
                        Text("Backdrop Color", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        ColorSwatchRow(
                            selected = textBgColor,
                            options = listOf(0x00000000L, 0x9916161AL, 0xCC720000L, 0xCC0D233AL),
                            onSelect = { textBgColor = it }
                        )
                    }
                    SourceType.COLOR_SOURCE -> {
                        Text("Solid Fill Color", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        ColorSwatchRow(
                            selected = colorArgb,
                            options = listOf(0xFF720000L, 0xFF16161AL, 0xFF00B140L, 0xFF0F3D5EL, 0xFF2B2D42L, 0xFFF5A524L),
                            onSelect = { colorArgb = it }
                        )
                    }
                    SourceType.IMAGE_SOURCE -> {
                        Text("File: ${source.mediaPathOrUri?.substringAfterLast('/') ?: "None"}", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        if (source.erroredReason != null) {
                            Text(source.erroredReason, style = MaterialTheme.typography.labelSmall, color = StatusWarn)
                        }
                        OutlinedButton(onClick = { onRelinkImage(source.id) }) {
                            Text("Choose / Relink Image", color = StudioText)
                        }
                    }
                    SourceType.MEDIA_SOURCE -> {
                        Text("File: ${source.mediaPathOrUri?.substringAfterLast('/') ?: "None"}", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        if (source.erroredReason != null) {
                            Text(source.erroredReason, style = MaterialTheme.typography.labelSmall, color = StatusWarn)
                        }
                        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("Playing", color = StudioText)
                            Switch(checked = mediaPlaying, onCheckedChange = { mediaPlaying = it })
                        }
                        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("Loop", color = StudioText)
                            Switch(checked = mediaLoop, onCheckedChange = { mediaLoop = it })
                        }
                        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("Mute Media Audio", color = StudioText)
                            Switch(checked = mediaMuted, onCheckedChange = { mediaMuted = it })
                        }
                        OutlinedButton(onClick = { onRelinkMedia(source.id) }) {
                            Text("Choose / Relink Video", color = StudioText)
                        }
                    }
                    else -> {
                        Text(
                            "Transform: ${(source.transform.widthFraction * 100).toInt()}% x ${(source.transform.heightFraction * 100).toInt()}% · Rot ${source.transform.rotationDegrees.toInt()}°",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextDim
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onUpdateSource { cur ->
                        cur.copy(
                            name = nameInput.ifBlank { cur.name },
                            textContent = textInput,
                            textSizeSp = textSize,
                            textColorArgb = textColor,
                            textBgColorArgb = textBgColor,
                            colorArgb = colorArgb,
                            mediaLoop = mediaLoop,
                            mediaMuted = mediaMuted,
                            mediaPlaying = mediaPlaying
                        )
                    }
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = ApertureRed)
            ) { Text("Apply", color = StudioText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = StudioTextDim) }
        }
    )
}

@Composable
fun AudioFiltersDialog(
    channel: AudioChannelState,
    onDismiss: () -> Unit,
    onSave: (AudioFilterConfig) -> Unit
) {
    var cfg by remember(channel.id) { mutableStateOf(channel.filters) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = SurfaceLevel1,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, StudioDivider),
            modifier = Modifier.fillMaxWidth(0.76f).fillMaxHeight(0.84f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Audio DSP Filters · ${channel.label}", style = MaterialTheme.typography.titleMedium, color = StudioText)
                        Text(
                            "All OFF by default · Zero-alloc DSP pass: ${channel.measuredDspMicros} µs",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusOk
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("Close", color = StudioText) }
                }
                Spacer(Modifier.height(8.dp))
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterToggleCard("Gain Filter", cfg.gainEnabled, { cfg = cfg.copy(gainEnabled = it) }) {
                        Text("Gain: ${String.format(Locale.US, "%+.1f dB", cfg.gainDb)}", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        Slider(value = cfg.gainDb, onValueChange = { cfg = cfg.copy(gainDb = it) }, valueRange = -24f..24f)
                    }
                    FilterToggleCard("Noise Suppression (Android NS + High-Pass)", cfg.noiseSuppressionEnabled, { cfg = cfg.copy(noiseSuppressionEnabled = it) }) {}
                    FilterToggleCard("Noise Gate", cfg.noiseGateEnabled, { cfg = cfg.copy(noiseGateEnabled = it) }) {
                        Text("Close: ${cfg.gateCloseDb.toInt()} dB · Open: ${cfg.gateOpenDb.toInt()} dB", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        Slider(value = cfg.gateCloseDb, onValueChange = { cfg = cfg.copy(gateCloseDb = it, gateOpenDb = (it + 6f).coerceAtMost(-10f)) }, valueRange = -70f..-20f)
                    }
                    FilterToggleCard("Compressor", cfg.compressorEnabled, { cfg = cfg.copy(compressorEnabled = it) }) {
                        Text("Threshold: ${cfg.compressorThresholdDb.toInt()} dB · Ratio ${cfg.compressorRatio.toInt()}:1 · Makeup +${cfg.compressorMakeupDb.toInt()} dB", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        Slider(value = cfg.compressorThresholdDb, onValueChange = { cfg = cfg.copy(compressorThresholdDb = it) }, valueRange = -40f..-6f)
                        Slider(value = cfg.compressorRatio, onValueChange = { cfg = cfg.copy(compressorRatio = it) }, valueRange = 2f..12f)
                    }
                    FilterToggleCard("Limiter", cfg.limiterEnabled, { cfg = cfg.copy(limiterEnabled = it) }) {
                        Text("Ceiling: ${String.format(Locale.US, "%.1f dB", cfg.limiterCeilingDb)}", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        Slider(value = cfg.limiterCeilingDb, onValueChange = { cfg = cfg.copy(limiterCeilingDb = it) }, valueRange = -12f..-0.2f)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    OutlinedButton(onClick = { cfg = AudioFilterConfig() }) { Text("Reset All Off", color = StudioTextDim) }
                    Button(
                        onClick = {
                            onSave(cfg)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ApertureRed),
                        modifier = Modifier.testTag("save_audio_filters_button")
                    ) { Text("Apply DSP Filters", color = StudioText) }
                }
            }
        }
    }
}

@Composable
fun VideoFiltersDialog(
    source: SourceModel,
    compositorMicros: Long,
    onDismiss: () -> Unit,
    onSave: (VideoFilterConfig) -> Unit
) {
    var cfg by remember(source.id) { mutableStateOf(source.videoFilters) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = SurfaceLevel1,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, StudioDivider),
            modifier = Modifier.fillMaxWidth(0.78f).fillMaxHeight(0.86f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("GPU Video Filters · ${source.name}", style = MaterialTheme.typography.titleMedium, color = StudioText)
                        Text(
                            "Runs 100% in GLES3 Fragment Shader · Pass: $compositorMicros µs",
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusOk
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("Close", color = StudioText) }
                }
                Spacer(Modifier.height(8.dp))
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterToggleCard("Color Correction (Brightness / Contrast / Sat / Gamma / Opacity)", cfg.colorCorrectionEnabled, { cfg = cfg.copy(colorCorrectionEnabled = it) }) {
                        Text("Brightness ${String.format(Locale.US, "%+.2f", cfg.brightness)} · Contrast ${String.format(Locale.US, "%.2f", cfg.contrast)}", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        Slider(value = cfg.brightness, onValueChange = { cfg = cfg.copy(brightness = it) }, valueRange = -0.45f..0.45f)
                        Slider(value = cfg.contrast, onValueChange = { cfg = cfg.copy(contrast = it) }, valueRange = 0.4f..1.8f)
                        Text("Saturation ${String.format(Locale.US, "%.2f", cfg.saturation)} · Opacity ${(cfg.opacity * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        Slider(value = cfg.saturation, onValueChange = { cfg = cfg.copy(saturation = it) }, valueRange = 0f..2f)
                        Slider(value = cfg.opacity, onValueChange = { cfg = cfg.copy(opacity = it) }, valueRange = 0.1f..1f)
                    }
                    FilterToggleCard("Chroma Key (GPU Keying + Spill Suppression)", cfg.chromaKeyEnabled, { cfg = cfg.copy(chromaKeyEnabled = it) }) {
                        Text("Similarity ${String.format(Locale.US, "%.2f", cfg.chromaSimilarity)} · Smoothness ${String.format(Locale.US, "%.2f", cfg.chromaSmoothness)}", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        ColorSwatchRow(
                            selected = cfg.chromaKeyColorArgb,
                            options = listOf(0xFF00B140L, 0xFF0047BBL, 0xFFFF00FFL),
                            onSelect = { cfg = cfg.copy(chromaKeyColorArgb = it) }
                        )
                        Slider(value = cfg.chromaSimilarity, onValueChange = { cfg = cfg.copy(chromaSimilarity = it) }, valueRange = 0.05f..0.6f)
                        Slider(value = cfg.chromaSmoothness, onValueChange = { cfg = cfg.copy(chromaSmoothness = it) }, valueRange = 0.01f..0.3f)
                    }
                    FilterToggleCard("Sharpen (GPU 3x3 Unsharp Mask)", cfg.sharpenEnabled, { cfg = cfg.copy(sharpenEnabled = it) }) {
                        Text("Amount: ${(cfg.sharpenAmount * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        Slider(value = cfg.sharpenAmount, onValueChange = { cfg = cfg.copy(sharpenAmount = it) }, valueRange = 0.05f..1f)
                    }
                    FilterToggleCard("Color LUT Preset", cfg.lutEnabled, { cfg = cfg.copy(lutEnabled = it) }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LutPreset.entries.filter { it != LutPreset.NONE }.forEach { preset ->
                                val active = cfg.lutPreset == preset
                                OutlinedButton(
                                    onClick = { cfg = cfg.copy(lutPreset = preset) },
                                    border = BorderStroke(1.dp, if (active) ApertureRedHi else StudioDivider)
                                ) { Text(preset.label, style = MaterialTheme.typography.labelSmall, color = StudioText) }
                            }
                        }
                        Slider(value = cfg.lutIntensity, onValueChange = { cfg = cfg.copy(lutIntensity = it) }, valueRange = 0.1f..1f)
                    }
                    FilterToggleCard("Scaling / Aspect Mode", cfg.scaleAspectEnabled, { cfg = cfg.copy(scaleAspectEnabled = it) }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ScaleFilterType.entries.forEach { sf ->
                                val active = cfg.scaleFilter == sf
                                OutlinedButton(
                                    onClick = { cfg = cfg.copy(scaleFilter = sf) },
                                    border = BorderStroke(1.dp, if (active) ApertureRedHi else StudioDivider)
                                ) { Text(sf.label, style = MaterialTheme.typography.labelSmall, color = StudioText) }
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    OutlinedButton(onClick = { cfg = VideoFilterConfig() }) { Text("Reset All Off", color = StudioTextDim) }
                    Button(
                        onClick = {
                            onSave(cfg)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ApertureRed),
                        modifier = Modifier.testTag("save_video_filters_button")
                    ) { Text("Apply GPU Filters", color = StudioText) }
                }
            }
        }
    }
}

@Composable
private fun FilterToggleCard(
    title: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    body: @Composable () -> Unit
) {
    Surface(
        color = SurfaceLevel2,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, if (enabled) ApertureRedHi else StudioDivider),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = StudioText, fontWeight = FontWeight.Medium)
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            if (enabled) body()
        }
    }
}

@Composable
private fun ColorSwatchRow(
    selected: Long,
    options: List<Long>,
    onSelect: (Long) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { argb ->
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(Color(argb.toInt()), CircleShape)
                    .border(if (selected == argb) 2.5.dp else 1.dp, if (selected == argb) Color.White else StudioDivider, CircleShape)
                    .clickable { onSelect(argb) }
            )
        }
    }
}
