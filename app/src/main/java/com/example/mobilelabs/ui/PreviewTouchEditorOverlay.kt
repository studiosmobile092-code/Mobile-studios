package com.example.mobilelabs.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.mobilelabs.model.PreviewTool
import com.example.mobilelabs.model.SceneModel
import com.example.mobilelabs.model.SourceModel
import com.example.ui.theme.ApertureRed
import com.example.ui.theme.ApertureRedHi
import com.example.ui.theme.StatusWarn
import com.example.ui.theme.StudioDivider
import com.example.ui.theme.StudioText
import com.example.ui.theme.SurfaceLevel2
import kotlin.math.roundToInt

/**
 * Interactive 16:9 Canvas Touch Editor (M2.2).
 * Renders visual source bounding boxes, handles drag/resize/rotate/crop gestures,
 * magnetic center/edge snap guides, and respects lock & live-safety protection.
 */
@Composable
fun PreviewTouchEditorOverlay(
    scene: SceneModel?,
    selectedSourceId: String?,
    multiSelectedIds: Set<String>,
    activeTool: PreviewTool,
    isTouchBlockedLive: Boolean,
    onSelectSource: (String) -> Unit,
    onUnlockLiveTouch: () -> Unit,
    onDragSourceTransform: (
        sourceId: String,
        dx: Float,
        dy: Float,
        dW: Float,
        dH: Float,
        dRotDeg: Float,
        recordUndo: Boolean
    ) -> Unit,
    onUpdateCrop: (sourceId: String, left: Float, top: Float, right: Float, bottom: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val visualSources = scene?.sources?.filter { it.type.isVisual && it.visible }.orEmpty()
    val selectedSrc = visualSources.firstOrNull { it.id == selectedSourceId }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val canvasWpx = with(density) { maxWidth.toPx().coerceAtLeast(1f) }
        val canvasHpx = with(density) { maxHeight.toPx().coerceAtLeast(1f) }

        // Background tap picks topmost source under finger
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(visualSources) {
                    detectTapGestures { offset ->
                        val nx = offset.x / canvasWpx
                        val ny = offset.y / canvasHpx
                        val hit = visualSources.lastOrNull { src ->
                            val t = src.transform
                            nx in t.xFraction..(t.xFraction + t.widthFraction) &&
                                ny in t.yFraction..(t.yFraction + t.heightFraction)
                        }
                        if (hit != null) onSelectSource(hit.id)
                    }
                }
        )

        // Snap guide crosshairs when selected source sits near center
        if (selectedSrc != null && !selectedSrc.locked && !isTouchBlockedLive) {
            val t = selectedSrc.transform
            val cx = t.xFraction + t.widthFraction * 0.5f
            val cy = t.yFraction + t.heightFraction * 0.5f
            if (kotlin.math.abs(cx - 0.5f) < 0.015f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(width = 1.dp, height = maxHeight)
                        .background(ApertureRedHi.copy(alpha = 0.65f))
                )
            }
            if (kotlin.math.abs(cy - 0.5f) < 0.015f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(width = maxWidth, height = 1.dp)
                        .background(ApertureRedHi.copy(alpha = 0.65f))
                )
            }
        }

        // Multi-selected & primary selection outlines
        visualSources.filter { it.id == selectedSourceId || it.id in multiSelectedIds }.forEach { src ->
            val t = src.transform
            val leftPx = (t.xFraction * canvasWpx).roundToInt()
            val topPx = (t.yFraction * canvasHpx).roundToInt()
            val wDp = with(density) { (t.widthFraction * canvasWpx).toDp() }
            val hDp = with(density) { (t.heightFraction * canvasHpx).toDp() }

            val isPrimary = src.id == selectedSourceId
            val borderColor = when {
                src.locked -> StatusWarn
                isPrimary -> ApertureRedHi
                else -> Color(0xFF56B4E9)
            }

            Box(
                modifier = Modifier
                    .offset { IntOffset(leftPx, topPx) }
                    .size(wDp, hDp)
                    .border(if (isPrimary) 2.dp else 1.dp, borderColor)
                    .then(
                        if (isPrimary && !src.locked && !isTouchBlockedLive && activeTool == PreviewTool.SELECT_MOVE) {
                            Modifier.pointerInput(src.id, canvasWpx, canvasHpx) {
                                var firstDelta = true
                                detectDragGestures(
                                    onDragStart = { firstDelta = true },
                                    onDrag = { change, drag ->
                                        change.consume()
                                        onDragSourceTransform(
                                            src.id,
                                            drag.x / canvasWpx,
                                            drag.y / canvasHpx,
                                            0f,
                                            0f,
                                            0f,
                                            firstDelta
                                        )
                                        firstDelta = false
                                    }
                                )
                            }
                        } else Modifier
                    )
            ) {
                if (src.locked) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "Source locked",
                        tint = StatusWarn,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .size(16.dp)
                    )
                }

                if (isPrimary && !src.locked && !isTouchBlockedLive) {
                    if (activeTool == PreviewTool.CROP) {
                        // Top/Bottom/Left/Right crop sliders
                        HandleKnob(
                            color = Color(0xFF43B581),
                            modifier = Modifier.align(Alignment.CenterStart),
                            onDrag = { dx, _ ->
                                onUpdateCrop(src.id, t.cropLeft + dx / canvasWpx, t.cropTop, t.cropRight, t.cropBottom)
                            }
                        )
                        HandleKnob(
                            color = Color(0xFF43B581),
                            modifier = Modifier.align(Alignment.CenterEnd),
                            onDrag = { dx, _ ->
                                onUpdateCrop(src.id, t.cropLeft, t.cropTop, t.cropRight - dx / canvasWpx, t.cropBottom)
                            }
                        )
                        HandleKnob(
                            color = Color(0xFF43B581),
                            modifier = Modifier.align(Alignment.TopCenter),
                            onDrag = { _, dy ->
                                onUpdateCrop(src.id, t.cropLeft, t.cropTop + dy / canvasHpx, t.cropRight, t.cropBottom)
                            }
                        )
                        HandleKnob(
                            color = Color(0xFF43B581),
                            modifier = Modifier.align(Alignment.BottomCenter),
                            onDrag = { _, dy ->
                                onUpdateCrop(src.id, t.cropLeft, t.cropTop, t.cropRight, t.cropBottom - dy / canvasHpx)
                            }
                        )
                    } else {
                        // Bottom-Right resize handle
                        HandleKnob(
                            color = ApertureRedHi,
                            modifier = Modifier.align(Alignment.BottomEnd),
                            onDrag = { dx, dy ->
                                onDragSourceTransform(src.id, 0f, 0f, dx / canvasWpx, dy / canvasHpx, 0f, false)
                            }
                        )
                        // Top-Center rotation handle
                        Surface(
                            color = SurfaceLevel2,
                            shape = CircleShape,
                            border = BorderStroke(1.dp, ApertureRedHi),
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .size(22.dp)
                                .pointerInput(src.id) {
                                    detectDragGestures { change, drag ->
                                        change.consume()
                                        onDragSourceTransform(src.id, 0f, 0f, 0f, 0f, drag.x * 0.4f, false)
                                    }
                                }
                        ) {
                            Icon(
                                Icons.Default.RotateRight,
                                contentDescription = "Rotate source",
                                tint = StudioText,
                                modifier = Modifier.padding(3.dp)
                            )
                        }
                    }
                }
            }
        }

        if (isTouchBlockedLive) {
            Surface(
                color = Color(0xCC16161A),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, StatusWarn),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .testTag("live_touch_lock_chip")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = StatusWarn, modifier = Modifier.size(14.dp))
                    Text("Live Preview Locked", style = MaterialTheme.typography.labelSmall, color = StatusWarn)
                    TextButton(onClick = onUnlockLiveTouch) {
                        Text("Unlock", style = MaterialTheme.typography.labelSmall, color = StudioText)
                    }
                }
            }
        }
    }
}

@Composable
private fun HandleKnob(
    color: Color,
    modifier: Modifier = Modifier,
    onDrag: (dxPx: Float, dyPx: Float) -> Unit
) {
    Box(
        modifier = modifier
            .size(20.dp)
            .background(color, CircleShape)
            .border(1.5.dp, Color.White, CircleShape)
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onDrag(drag.x, drag.y)
                }
            }
    )
}

@Composable
fun SelectedSourceNudgeBar(
    source: SourceModel,
    blockedLive: Boolean,
    onNudge: (dx: Float, dy: Float, dScale: Float, dRotDeg: Float) -> Unit,
    onFitToScreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (source.locked || blockedLive) return
    Row(
        modifier = modifier
            .background(Color(0xD916161A), RoundedCornerShape(6.dp))
            .border(1.dp, StudioDivider, RoundedCornerShape(6.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = { onNudge(-0.03f, 0f, 0f, 0f) }) { Text("◀", color = StudioText) }
        TextButton(onClick = { onNudge(0.03f, 0f, 0f, 0f) }) { Text("▶", color = StudioText) }
        TextButton(onClick = { onNudge(0f, -0.03f, 0f, 0f) }) { Text("▲", color = StudioText) }
        TextButton(onClick = { onNudge(0f, 0.03f, 0f, 0f) }) { Text("▼", color = StudioText) }
        TextButton(onClick = { onNudge(0f, 0f, 0.04f, 0f) }) { Text("+Sz", color = StudioText) }
        TextButton(onClick = { onNudge(0f, 0f, -0.04f, 0f) }) { Text("-Sz", color = StudioText) }
        TextButton(onClick = { onNudge(0f, 0f, 0f, 90f) }) { Text("90°", color = StudioText) }
        TextButton(onClick = onFitToScreen) { Text("Fit", color = ApertureRedHi) }
    }
}
