package com.example.mobilelabs.engine

import com.example.mobilelabs.model.SceneCollection
import com.example.mobilelabs.model.SceneModel
import com.example.mobilelabs.model.SourceModel
import com.example.mobilelabs.model.SourceTransform
import com.example.mobilelabs.model.SourceType
import com.example.mobilelabs.model.VideoFilterConfig

/**
 * Pure deterministic scene & source transformation helpers (M2.1, M2.2, M2.4).
 * Separated from SessionController to keep files modular and unit-testable.
 */
object SceneEditingHelper {

    fun createDefaultSource(type: SourceType, pathOrUri: String? = null): SourceModel {
        val ts = System.currentTimeMillis()
        return when (type) {
            SourceType.SCREEN_CAPTURE -> SourceModel(
                id = "src_screen_$ts",
                name = "Tablet Screen",
                type = SourceType.SCREEN_CAPTURE
            )
            SourceType.SCREEN_REGION -> SourceModel(
                id = "src_region_$ts",
                name = "Screen Region",
                type = SourceType.SCREEN_REGION,
                transform = SourceTransform(
                    xFraction = 0.06f,
                    yFraction = 0.08f,
                    widthFraction = 0.42f,
                    heightFraction = 0.42f,
                    cropLeft = 0.10f,
                    cropTop = 0.10f,
                    cropRight = 0.10f,
                    cropBottom = 0.10f
                )
            )
            SourceType.CAMERA_2 -> SourceModel(
                id = "src_cam_$ts",
                name = "Facecam (Camera2)",
                type = SourceType.CAMERA_2,
                useFrontCamera = true,
                transform = SourceTransform(0.72f, 0.68f, 0.24f, 0.26f)
            )
            SourceType.IMAGE_SOURCE -> SourceModel(
                id = "src_img_$ts",
                name = "Image Overlay",
                type = SourceType.IMAGE_SOURCE,
                mediaPathOrUri = pathOrUri,
                transform = SourceTransform(0.05f, 0.06f, 0.28f, 0.28f)
            )
            SourceType.GIF_STICKER -> SourceModel(
                id = "src_gif_$ts",
                name = "GIF Sticker",
                type = SourceType.GIF_STICKER,
                mediaPathOrUri = pathOrUri,
                mediaLoop = true,
                mediaPlaying = true,
                transform = SourceTransform(0.70f, 0.08f, 0.24f, 0.24f)
            )
            SourceType.MEDIA_SOURCE -> SourceModel(
                id = "src_media_$ts",
                name = "Media Video",
                type = SourceType.MEDIA_SOURCE,
                mediaPathOrUri = pathOrUri,
                mediaLoop = true,
                mediaPlaying = true,
                transform = SourceTransform(0.10f, 0.12f, 0.42f, 0.42f)
            )
            SourceType.BROWSER_SOURCE -> SourceModel(
                id = "src_browser_$ts",
                name = "Browser / Alert URL",
                type = SourceType.BROWSER_SOURCE,
                browserUrl = pathOrUri ?: "https://streamlabs.com/widgets/alert-box/v3/demo",
                browserFpsCap = 20,
                browserTransparentBg = true,
                browserWidthPx = 800,
                browserHeightPx = 600,
                transform = SourceTransform(0.20f, 0.08f, 0.60f, 0.45f)
            )
            SourceType.CHAT_OVERLAY -> SourceModel(
                id = "src_chat_$ts",
                name = "Live Chat Box",
                type = SourceType.CHAT_OVERLAY,
                chatOverlayOpacity = 0.72f,
                chatOverlayMaxMessages = 7,
                transform = SourceTransform(0.03f, 0.44f, 0.32f, 0.48f)
            )
            SourceType.ALERT_BOX -> SourceModel(
                id = "src_alert_$ts",
                name = "Stream Alert Box",
                type = SourceType.ALERT_BOX,
                alertShowChime = true,
                transform = SourceTransform(0.28f, 0.04f, 0.44f, 0.14f)
            )
            SourceType.GOAL_BAR -> SourceModel(
                id = "src_goal_$ts",
                name = "Follower Goal Bar",
                type = SourceType.GOAL_BAR,
                goalTitle = "FOLLOWER GOAL",
                goalCurrent = 42,
                goalTarget = 100,
                goalAccentColorArgb = 0xFF9146FFL,
                transform = SourceTransform(0.58f, 0.85f, 0.38f, 0.11f)
            )
            SourceType.TEXT_SOURCE -> SourceModel(
                id = "src_text_$ts",
                name = "Text Banner",
                type = SourceType.TEXT_SOURCE,
                textContent = "LIVE · MOBILE LABS",
                textSizeSp = 44f,
                transform = SourceTransform(0.04f, 0.82f, 0.42f, 0.12f)
            )
            SourceType.TIMER_SOURCE -> SourceModel(
                id = "src_timer_$ts",
                name = "Stream Timer",
                type = SourceType.TIMER_SOURCE,
                timerTargetSec = 300,
                timerCountDown = true,
                timerRunning = false,
                textColorArgb = 0xFFFFFFFFL,
                textBgColorArgb = 0xAA141418L,
                transform = SourceTransform(0.36f, 0.05f, 0.28f, 0.12f)
            )
            SourceType.SCOREBOARD_SOURCE -> SourceModel(
                id = "src_score_$ts",
                name = "Scoreboard",
                type = SourceType.SCOREBOARD_SOURCE,
                scoreHomeName = "P1",
                scoreAwayName = "P2",
                scoreHome = 0,
                scoreAway = 0,
                transform = SourceTransform(0.30f, 0.04f, 0.40f, 0.12f)
            )
            SourceType.DRAWING_LAYER -> SourceModel(
                id = "src_draw_$ts",
                name = "Screen Ink Layer",
                type = SourceType.DRAWING_LAYER,
                drawingBrushColorArgb = 0xFFFF3B30L,
                drawingBrushSizePx = 10f,
                transform = SourceTransform(0f, 0f, 1f, 1f)
            )
            SourceType.COLOR_SOURCE -> SourceModel(
                id = "src_color_$ts",
                name = "Color Backdrop",
                type = SourceType.COLOR_SOURCE,
                colorArgb = 0xFF720000L,
                transform = SourceTransform(0f, 0f, 1f, 1f)
            )
            SourceType.SOUNDBOARD_SOURCE -> SourceModel(
                id = "src_sfx_$ts",
                name = "Soundboard SFX",
                type = SourceType.SOUNDBOARD_SOURCE
            )
            SourceType.MIC_AUDIO -> SourceModel(
                id = "src_mic_$ts",
                name = "Mic/Aux",
                type = SourceType.MIC_AUDIO
            )
            SourceType.SYSTEM_AUDIO -> SourceModel(
                id = "src_sysaudio_$ts",
                name = "Game / System Audio",
                type = SourceType.SYSTEM_AUDIO
            )
        }
    }

    fun applyTransformWithSnap(
        current: SourceTransform,
        dx: Float,
        dy: Float,
        dW: Float,
        dH: Float,
        dRotDeg: Float,
        enableSnap: Boolean
    ): SourceTransform {
        var nextW = (current.widthFraction + dW).coerceIn(0.08f, 1f)
        var nextH = (current.heightFraction + dH).coerceIn(0.08f, 1f)
        var nextX = (current.xFraction + dx).coerceIn(0f, (1f - nextW).coerceAtLeast(0f))
        var nextY = (current.yFraction + dy).coerceIn(0f, (1f - nextH).coerceAtLeast(0f))
        var nextRot = (current.rotationDegrees + dRotDeg) % 360f
        if (nextRot < 0f) nextRot += 360f

        if (enableSnap) {
            val snap = 0.018f
            // Edge snap
            if (nextX < snap) nextX = 0f
            if (nextY < snap) nextY = 0f
            if (kotlin.math.abs((nextX + nextW) - 1f) < snap) nextX = (1f - nextW).coerceAtLeast(0f)
            if (kotlin.math.abs((nextY + nextH) - 1f) < snap) nextY = (1f - nextH).coerceAtLeast(0f)
            // Center snap
            val cx = nextX + nextW * 0.5f
            val cy = nextY + nextH * 0.5f
            if (kotlin.math.abs(cx - 0.5f) < snap) nextX = (0.5f - nextW * 0.5f).coerceIn(0f, 1f - nextW)
            if (kotlin.math.abs(cy - 0.5f) < snap) nextY = (0.5f - nextH * 0.5f).coerceIn(0f, 1f - nextH)
            // 90-degree rotation snaps
            for (angle in listOf(0f, 90f, 180f, 270f, 360f)) {
                if (kotlin.math.abs(nextRot - angle) < 4f) {
                    nextRot = angle % 360f
                    break
                }
            }
        }
        return current.copy(
            xFraction = nextX,
            yFraction = nextY,
            widthFraction = nextW,
            heightFraction = nextH,
            rotationDegrees = nextRot
        )
    }

    fun moveItemInList(items: List<SourceModel>, sourceId: String, delta: Int): List<SourceModel> {
        val idx = items.indexOfFirst { it.id == sourceId }
        if (idx < 0) return items
        val target = (idx + delta).coerceIn(0, items.lastIndex)
        if (idx == target) return items
        val mutable = items.toMutableList()
        val item = mutable.removeAt(idx)
        mutable.add(target, item)
        return mutable
    }

    fun moveSceneInList(scenes: List<SceneModel>, sceneId: String, delta: Int): List<SceneModel> {
        val idx = scenes.indexOfFirst { it.id == sceneId }
        if (idx < 0) return scenes
        val target = (idx + delta).coerceIn(0, scenes.lastIndex)
        if (idx == target) return scenes
        val mutable = scenes.toMutableList()
        val item = mutable.removeAt(idx)
        mutable.add(target, item)
        return mutable
    }

    fun updateSourceInScene(
        collection: SceneCollection,
        targetSceneId: String,
        sourceId: String,
        fn: (SourceModel) -> SourceModel
    ): SceneCollection {
        return collection.copy(
            scenes = collection.scenes.map { scene ->
                if (scene.id == targetSceneId) {
                    scene.copy(sources = scene.sources.map { if (it.id == sourceId) fn(it) else it })
                } else scene
            }
        )
    }

    fun updateFiltersForSource(
        collection: SceneCollection,
        targetSceneId: String,
        sourceId: String,
        filters: VideoFilterConfig
    ): SceneCollection = updateSourceInScene(collection, targetSceneId, sourceId) {
        it.copy(videoFilters = filters)
    }
}
