package com.example.mobilelabs.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.example.mobilelabs.model.AlertEventType
import com.example.mobilelabs.model.AudioChannelState
import com.example.mobilelabs.model.AudioFilterConfig
import com.example.mobilelabs.model.AudioMonitoringMode
import com.example.mobilelabs.model.CameraWhiteBalanceMode
import com.example.mobilelabs.model.FacecamShape
import com.example.mobilelabs.model.LutPreset
import com.example.mobilelabs.model.ScaleFilterType
import com.example.mobilelabs.model.SourceModel
import com.example.mobilelabs.model.SourceType
import com.example.mobilelabs.model.VideoFilterConfig
import com.example.mobilelabs.model.VoiceEffectPreset
import com.example.ui.theme.ApertureRed
import com.example.ui.theme.ApertureRedHi
import com.example.ui.theme.ObsSelectionBlue
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
    onPickGifFile: () -> Unit,
    onPickMediaFile: () -> Unit
) {
    val grouped = remember { SourceType.entries.groupBy { it.category } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceLevel1,
        title = { Text("Add Source (OBS + Pro Studio)", color = StudioText) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                grouped.forEach { (category, types) ->
                    Text(
                        text = category.uppercase(Locale.US),
                        style = MaterialTheme.typography.labelSmall,
                        color = ApertureRedHi,
                        fontWeight = FontWeight.Bold
                    )
                    types.forEach { type ->
                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                when (type) {
                                    SourceType.IMAGE_SOURCE -> onPickImageFile()
                                    SourceType.GIF_STICKER -> onPickGifFile()
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
                }
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
    onRelinkGif: (String) -> Unit,
    onRelinkMedia: (String) -> Unit,
    onReloadBrowserSource: (String) -> Unit,
    onControlTimer: (String, String) -> Unit,
    onAdjustScoreboard: (String, Boolean, Int) -> Unit,
    onResetScoreboard: (String) -> Unit,
    onAdjustGoalProgress: (String, Int) -> Unit,
    onTriggerTestAlert: (AlertEventType) -> Unit,
    onOpenLiveChat: () -> Unit,
    onClearDrawing: () -> Unit
) {
    var nameInput by remember(source.id) { mutableStateOf(source.name) }
    var textInput by remember(source.id) { mutableStateOf(source.textContent) }
    var textSize by remember(source.id) { mutableFloatStateOf(source.textSizeSp) }
    var textColor by remember(source.id) { mutableStateOf(source.textColorArgb) }
    var textBgColor by remember(source.id) { mutableStateOf(source.textBgColorArgb) }
    var colorArgb by remember(source.id) { mutableStateOf(source.colorArgb) }
    var mediaLoop by remember(source.id) { mutableStateOf(source.mediaLoop) }
    var mediaMuted by remember(source.id) { mutableStateOf(source.mediaMuted) }
    var mediaPlaying by remember(source.id) { mutableStateOf(source.mediaPlaying) }

    // Camera facecam mask + border + Camera2 Pro controls
    var facecamShape by remember(source.id) { mutableStateOf(source.facecamShape) }
    var borderWidthPx by remember(source.id) { mutableFloatStateOf(source.borderWidthPx) }
    var borderColorArgb by remember(source.id) { mutableStateOf(source.borderColorArgb) }
    var mirrorHorizontal by remember(source.id) { mutableStateOf(source.mirrorHorizontal) }
    var useFrontCam by remember(source.id) { mutableStateOf(source.useFrontCamera) }
    var camExposureEv by remember(source.id) { mutableIntStateOf(source.camExposureEv) }
    var camIso by remember(source.id) { mutableIntStateOf(source.camIso) }
    var camFocusDistance by remember(source.id) { mutableFloatStateOf(source.camFocusDistance) }
    var camWhiteBalance by remember(source.id) { mutableStateOf(source.camWhiteBalance) }
    var camZoomRatio by remember(source.id) { mutableFloatStateOf(source.camZoomRatio) }
    var camTorchEnabled by remember(source.id) { mutableStateOf(source.camTorchEnabled) }

    // Browser source
    var browserUrl by remember(source.id) { mutableStateOf(source.browserUrl) }
    var browserFpsCap by remember(source.id) { mutableIntStateOf(source.browserFpsCap) }
    var browserTransparentBg by remember(source.id) { mutableStateOf(source.browserTransparentBg) }

    // Timer source
    var timerCountDown by remember(source.id) { mutableStateOf(source.timerCountDown) }
    var timerTargetSec by remember(source.id) { mutableIntStateOf(source.timerTargetSec) }

    // Scoreboard source
    var scoreHomeName by remember(source.id) { mutableStateOf(source.scoreHomeName) }
    var scoreAwayName by remember(source.id) { mutableStateOf(source.scoreAwayName) }

    // Chat overlay
    var chatOpacity by remember(source.id) { mutableFloatStateOf(source.chatOverlayOpacity) }
    var chatMaxMessages by remember(source.id) { mutableIntStateOf(source.chatOverlayMaxMessages) }

    // Goal Bar & Alert Box
    var goalTitle by remember(source.id) { mutableStateOf(source.goalTitle) }
    var goalTargetText by remember(source.id) { mutableStateOf(source.goalTarget.toString()) }
    var goalAccentColorArgb by remember(source.id) { mutableStateOf(source.goalAccentColorArgb) }
    var alertShowChime by remember(source.id) { mutableStateOf(source.alertShowChime) }

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
                    SourceType.CAMERA_2 -> {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("Front Camera", color = StudioText)
                            Switch(checked = useFrontCam, onCheckedChange = { useFrontCam = it })
                        }
                        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("Mirror Horizontal", color = StudioText)
                            Switch(checked = mirrorHorizontal, onCheckedChange = { mirrorHorizontal = it })
                        }
                        Text("Facecam Mask Shape (GPU SDF)", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FacecamShape.entries.forEach { shape ->
                                val active = facecamShape == shape
                                OutlinedButton(
                                    onClick = { facecamShape = shape },
                                    border = BorderStroke(1.dp, if (active) ApertureRedHi else StudioDivider),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(34.dp)
                                ) { Text(shape.label, fontSize = 11.sp, color = StudioText) }
                            }
                        }
                        Text("Frame Border Width: ${borderWidthPx.toInt()} px", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        Slider(value = borderWidthPx, onValueChange = { borderWidthPx = it }, valueRange = 0f..14f)
                        ColorSwatchRow(
                            selected = borderColorArgb,
                            options = listOf(0xFFCC1B1BL, 0xFFFFFFFFL, 0xFF9146FFL, 0xFF43B581L, 0xFFF5A524L),
                            onSelect = { borderColorArgb = it }
                        )
                        HorizontalDivider(color = StudioDivider)
                        Text("Camera2 Pro Controls (Manual Sensor)", style = MaterialTheme.typography.labelMedium, color = ApertureRedHi, fontWeight = FontWeight.Bold)
                        Text("Exposure Compensation (EV): ${if (camExposureEv >= 0) "+$camExposureEv" else "$camExposureEv"}", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        Slider(
                            value = camExposureEv.toFloat(),
                            onValueChange = { camExposureEv = it.toInt() },
                            valueRange = -4f..4f,
                            steps = 7
                        )
                        Text("ISO Sensitivity: ${if (camIso == 0) "Auto" else camIso.toString()}", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(0, 100, 200, 400, 800, 1600).forEach { iso ->
                                val active = camIso == iso
                                OutlinedButton(
                                    onClick = { camIso = iso },
                                    border = BorderStroke(1.dp, if (active) ObsSelectionBlue else StudioDivider),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(32.dp)
                                ) { Text(if (iso == 0) "Auto" else "$iso", fontSize = 11.sp, color = StudioText) }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("Manual Focus (off = Auto AF)", color = StudioText)
                            Switch(
                                checked = camFocusDistance >= 0f,
                                onCheckedChange = { manual -> camFocusDistance = if (manual) 2.5f else -1f }
                            )
                        }
                        if (camFocusDistance >= 0f) {
                            Text("Focus Distance: ${String.format(Locale.US, "%.1f", camFocusDistance)} diopters", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                            Slider(value = camFocusDistance, onValueChange = { camFocusDistance = it }, valueRange = 0f..10f)
                        }
                        Text("White Balance", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CameraWhiteBalanceMode.entries.forEach { wb ->
                                val active = camWhiteBalance == wb
                                OutlinedButton(
                                    onClick = { camWhiteBalance = wb },
                                    border = BorderStroke(1.dp, if (active) ApertureRedHi else StudioDivider),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(32.dp)
                                ) { Text(wb.label, fontSize = 11.sp, color = StudioText) }
                            }
                        }
                        Text("Optical / Digital Zoom: ${String.format(Locale.US, "%.1fx", camZoomRatio)}", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        Slider(value = camZoomRatio, onValueChange = { camZoomRatio = it }, valueRange = 1.0f..4.0f)
                        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("Camera Torch / Flash (Rear Only)", color = StudioText)
                            Switch(checked = camTorchEnabled, onCheckedChange = { camTorchEnabled = it })
                        }
                    }
                    SourceType.BROWSER_SOURCE -> {
                        OutlinedTextField(
                            value = browserUrl,
                            onValueChange = { browserUrl = it },
                            label = { Text("Browser / Alert Box URL (https://...)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("browser_url_input")
                        )
                        Text("Render FPS Cap (saves game FPS): $browserFpsCap fps", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(10, 15, 20, 30).forEach { cap ->
                                val active = browserFpsCap == cap
                                OutlinedButton(
                                    onClick = { browserFpsCap = cap },
                                    border = BorderStroke(1.dp, if (active) ObsSelectionBlue else StudioDivider),
                                    modifier = Modifier.height(34.dp)
                                ) { Text("${cap} fps", fontSize = 11.sp, color = StudioText) }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("Transparent Background", color = StudioText)
                            Switch(checked = browserTransparentBg, onCheckedChange = { browserTransparentBg = it })
                        }
                        OutlinedButton(onClick = { onReloadBrowserSource(source.id) }) {
                            Text("Reload Browser Source", color = StudioText)
                        }
                    }
                    SourceType.TIMER_SOURCE -> {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("Countdown Mode (off = Stopwatch)", color = StudioText)
                            Switch(checked = timerCountDown, onCheckedChange = { timerCountDown = it })
                        }
                        if (timerCountDown) {
                            Text("Target Duration: ${timerTargetSec}s", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(60, 180, 300, 600).forEach { sec ->
                                    val active = timerTargetSec == sec
                                    OutlinedButton(
                                        onClick = { timerTargetSec = sec },
                                        border = BorderStroke(1.dp, if (active) ApertureRedHi else StudioDivider),
                                        modifier = Modifier.height(34.dp)
                                    ) { Text("${sec / 60}m", fontSize = 11.sp, color = StudioText) }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onControlTimer(source.id, if (source.timerRunning) "pause" else "start") },
                                colors = ButtonDefaults.buttonColors(containerColor = ObsSelectionBlue)
                            ) { Text(if (source.timerRunning) "Pause" else "Start", color = StudioText) }
                            OutlinedButton(onClick = { onControlTimer(source.id, "reset") }) { Text("Reset", color = StudioText) }
                        }
                        ColorSwatchRow(selected = textColor, options = listOf(0xFFFFFFFFL, 0xFFF5A524L, 0xFFFF4D4FL, 0xFF43B581L), onSelect = { textColor = it })
                        ColorSwatchRow(selected = textBgColor, options = listOf(0x00000000L, 0x9916161AL, 0xCC720000L), onSelect = { textBgColor = it })
                    }
                    SourceType.SCOREBOARD_SOURCE -> {
                        OutlinedTextField(
                            value = scoreHomeName,
                            onValueChange = { scoreHomeName = it },
                            label = { Text("Home / Player 1 Label") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = scoreAwayName,
                            onValueChange = { scoreAwayName = it },
                            label = { Text("Away / Player 2 Label") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Score: $scoreHomeName ${source.scoreHome} : ${source.scoreAway} $scoreAwayName", color = StudioText)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { onAdjustScoreboard(source.id, true, 1) }) { Text("+1 P1", color = StudioText) }
                            OutlinedButton(onClick = { onAdjustScoreboard(source.id, false, 1) }) { Text("+1 P2", color = StudioText) }
                            TextButton(onClick = { onResetScoreboard(source.id) }) { Text("Reset", color = StatusWarn) }
                        }
                    }
                    SourceType.CHAT_OVERLAY -> {
                        Text("Box Opacity: ${(chatOpacity * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        Slider(value = chatOpacity, onValueChange = { chatOpacity = it }, valueRange = 0.1f..1f)
                        Text("Max Lines: $chatMaxMessages", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(4, 7, 10, 14).forEach { count ->
                                OutlinedButton(
                                    onClick = { chatMaxMessages = count },
                                    border = BorderStroke(1.dp, if (chatMaxMessages == count) ObsSelectionBlue else StudioDivider),
                                    modifier = Modifier.height(32.dp)
                                ) { Text("$count", fontSize = 11.sp, color = StudioText) }
                            }
                        }
                        OutlinedButton(onClick = { onDismiss(); onOpenLiveChat() }) {
                            Text("Open Live Chat Drawer", color = StudioText)
                        }
                    }
                    SourceType.GOAL_BAR -> {
                        OutlinedTextField(
                            value = goalTitle,
                            onValueChange = { goalTitle = it },
                            label = { Text("Goal Bar Title") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("goal_title_input")
                        )
                        OutlinedTextField(
                            value = goalTargetText,
                            onValueChange = { txt -> goalTargetText = txt.filter { it.isDigit() } },
                            label = { Text("Target Count") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("goal_target_input")
                        )
                        Text(
                            "Progress: ${source.goalCurrent} / ${goalTargetText.toIntOrNull()?.coerceAtLeast(1) ?: source.goalTarget}",
                            color = StudioText,
                            fontWeight = FontWeight.Medium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { onAdjustGoalProgress(source.id, -1) }) { Text("-1", color = StudioText) }
                            OutlinedButton(onClick = { onAdjustGoalProgress(source.id, 1) }) { Text("+1", color = StatusOk) }
                            OutlinedButton(onClick = { onAdjustGoalProgress(source.id, 5) }) { Text("+5", color = StatusOk) }
                        }
                        Text("Goal Accent Color", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        ColorSwatchRow(
                            selected = goalAccentColorArgb,
                            options = listOf(0xFF9146FFL, 0xFFE62117L, 0xFF00C853L, 0xFFFFB300L, 0xFF29B6F6L),
                            onSelect = { goalAccentColorArgb = it }
                        )
                    }
                    SourceType.ALERT_BOX -> {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("Play Broadcast Chime SFX", color = StudioText)
                            Switch(checked = alertShowChime, onCheckedChange = { alertShowChime = it })
                        }
                        Text("Trigger Test Stream Alert", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            AlertEventType.entries.forEach { evType ->
                                OutlinedButton(
                                    onClick = { onTriggerTestAlert(evType) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(34.dp)
                                ) { Text(evType.label, fontSize = 10.sp, color = StudioText) }
                            }
                        }
                    }
                    SourceType.DRAWING_LAYER -> {
                        Text("Draw directly over the stage using the Ink toggle on the top bar.", style = MaterialTheme.typography.bodySmall, color = StudioTextDim)
                        OutlinedButton(onClick = onClearDrawing) {
                            Text("Clear All Ink Strokes", color = ApertureRedHi)
                        }
                    }
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
                    SourceType.IMAGE_SOURCE, SourceType.GIF_STICKER -> {
                        Text("File: ${source.mediaPathOrUri?.substringAfterLast('/') ?: "None"}", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        if (source.erroredReason != null) {
                            Text(source.erroredReason, style = MaterialTheme.typography.labelSmall, color = StatusWarn)
                        }
                        OutlinedButton(onClick = {
                            if (source.type == SourceType.GIF_STICKER) onRelinkGif(source.id) else onRelinkImage(source.id)
                        }) {
                            Text(if (source.type == SourceType.GIF_STICKER) "Choose / Relink GIF" else "Choose / Relink Image", color = StudioText)
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
                    SourceType.SCREEN_REGION -> {
                        Text(
                            "Cropped Screen Region: select the 'Crop' tool in Edit menu or drag green edge handles on the canvas to isolate a game HUD, radar, or sub-window.",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioTextDim
                        )
                        Text(
                            "Crop L:${(source.transform.cropLeft * 100).toInt()}% T:${(source.transform.cropTop * 100).toInt()}% R:${(source.transform.cropRight * 100).toInt()}% B:${(source.transform.cropBottom * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioText
                        )
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
                            mediaPlaying = mediaPlaying,
                            useFrontCamera = useFrontCam,
                            facecamShape = facecamShape,
                            borderWidthPx = borderWidthPx,
                            borderColorArgb = borderColorArgb,
                            mirrorHorizontal = mirrorHorizontal,
                            camExposureEv = camExposureEv,
                            camIso = camIso,
                            camFocusDistance = camFocusDistance,
                            camWhiteBalance = camWhiteBalance,
                            camZoomRatio = camZoomRatio,
                            camTorchEnabled = camTorchEnabled,
                            browserUrl = browserUrl.trim().ifBlank { cur.browserUrl },
                            browserFpsCap = browserFpsCap,
                            browserTransparentBg = browserTransparentBg,
                            timerCountDown = timerCountDown,
                            timerTargetSec = timerTargetSec,
                            scoreHomeName = scoreHomeName.ifBlank { cur.scoreHomeName },
                            scoreAwayName = scoreAwayName.ifBlank { cur.scoreAwayName },
                            chatOverlayOpacity = chatOpacity,
                            chatOverlayMaxMessages = chatMaxMessages,
                            goalTitle = goalTitle.ifBlank { cur.goalTitle },
                            goalTarget = goalTargetText.toIntOrNull()?.coerceAtLeast(1) ?: cur.goalTarget,
                            goalAccentColorArgb = goalAccentColorArgb,
                            alertShowChime = alertShowChime
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
            modifier = Modifier.fillMaxWidth(0.78f).fillMaxHeight(0.86f)
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
                    FilterToggleCard("3-Band Studio EQ (Biquad Low / Mid / High)", cfg.eqEnabled, { cfg = cfg.copy(eqEnabled = it) }) {
                        Text(
                            "Low ${String.format(Locale.US, "%+.1f", cfg.eqLowDb)} dB · Mid ${String.format(Locale.US, "%+.1f", cfg.eqMidDb)} dB · High ${String.format(Locale.US, "%+.1f", cfg.eqHighDb)} dB",
                            style = MaterialTheme.typography.labelSmall,
                            color = StudioTextDim
                        )
                        Slider(value = cfg.eqLowDb, onValueChange = { cfg = cfg.copy(eqLowDb = it) }, valueRange = -12f..12f)
                        Slider(value = cfg.eqMidDb, onValueChange = { cfg = cfg.copy(eqMidDb = it) }, valueRange = -12f..12f)
                        Slider(value = cfg.eqHighDb, onValueChange = { cfg = cfg.copy(eqHighDb = it) }, valueRange = -12f..12f)
                    }
                    Surface(
                        color = SurfaceLevel2,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, if (cfg.voiceEffect != VoiceEffectPreset.NONE) ApertureRedHi else StudioDivider),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Real-Time Voice FX & Studio Reverb", style = MaterialTheme.typography.bodyMedium, color = StudioText, fontWeight = FontWeight.Medium)
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                VoiceEffectPreset.entries.forEach { preset ->
                                    val active = cfg.voiceEffect == preset
                                    OutlinedButton(
                                        onClick = { cfg = cfg.copy(voiceEffect = preset) },
                                        border = BorderStroke(1.dp, if (active) ApertureRedHi else StudioDivider),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(34.dp).testTag("voice_fx_${preset.name.lowercase()}")
                                    ) { Text(preset.label, fontSize = 11.sp, color = StudioText) }
                                }
                            }
                            if (cfg.voiceEffect == VoiceEffectPreset.STUDIO_REVERB) {
                                Text("Reverb Wet Mix: ${(cfg.reverbMix * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                                Slider(value = cfg.reverbMix, onValueChange = { cfg = cfg.copy(reverbMix = it) }, valueRange = 0.05f..0.75f)
                            }
                        }
                    }
                    Surface(
                        color = SurfaceLevel2,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, if (cfg.monitoringMode != AudioMonitoringMode.OFF) ObsSelectionBlue else StudioDivider),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Ear Monitoring Mode (Low-Latency AudioTrack)", style = MaterialTheme.typography.bodyMedium, color = StudioText, fontWeight = FontWeight.Medium)
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                AudioMonitoringMode.entries.forEach { mode ->
                                    val active = cfg.monitoringMode == mode
                                    OutlinedButton(
                                        onClick = { cfg = cfg.copy(monitoringMode = mode) },
                                        border = BorderStroke(1.dp, if (active) ObsSelectionBlue else StudioDivider),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(34.dp).testTag("audio_monitor_${mode.name.lowercase()}")
                                    ) { Text(mode.label, fontSize = 11.sp, color = StudioText) }
                                }
                            }
                        }
                    }
                    FilterToggleCard("Sidechain Auto-Ducking (Lowers Game when Speaking)", cfg.autoDuckingEnabled, { cfg = cfg.copy(autoDuckingEnabled = it) }) {
                        Text("Duck Amount: ${cfg.duckAmountDb.toInt()} dB", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        Slider(value = cfg.duckAmountDb, onValueChange = { cfg = cfg.copy(duckAmountDb = it) }, valueRange = -24f..-2f)
                    }
                    FilterToggleCard("Push-to-Talk Mode (Mic Muted Unless Holding PTT)", cfg.pushToTalkEnabled, { cfg = cfg.copy(pushToTalkEnabled = it) }) {}
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
                    FilterToggleCard("Beauty Skin Softening (GPU Bilateral Smooth)", cfg.beautyEnabled, { cfg = cfg.copy(beautyEnabled = it) }) {
                        Text("Softening Strength: ${(cfg.beautyStrength * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        Slider(value = cfg.beautyStrength, onValueChange = { cfg = cfg.copy(beautyStrength = it) }, valueRange = 0.1f..1.0f)
                    }
                    FilterToggleCard("Sharpen (GPU 3x3 Unsharp Mask)", cfg.sharpenEnabled, { cfg = cfg.copy(sharpenEnabled = it) }) {
                        Text("Amount: ${(cfg.sharpenAmount * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = StudioTextDim)
                        Slider(value = cfg.sharpenAmount, onValueChange = { cfg = cfg.copy(sharpenAmount = it) }, valueRange = 0.05f..1f)
                    }
                    FilterToggleCard("Color LUT Preset (7 Built-In Studio Looks)", cfg.lutEnabled, { cfg = cfg.copy(lutEnabled = it) }) {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            LutPreset.entries.filter { it != LutPreset.NONE }.forEach { preset ->
                                val active = cfg.lutPreset == preset
                                OutlinedButton(
                                    onClick = { cfg = cfg.copy(lutPreset = preset) },
                                    border = BorderStroke(1.dp, if (active) ApertureRedHi else StudioDivider),
                                    modifier = Modifier.testTag("lut_preset_${preset.name.lowercase()}")
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
