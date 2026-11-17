package com.example.mobilelabs.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import com.example.mobilelabs.model.DrawingStroke
import com.example.mobilelabs.model.PlatformChatMessage
import com.example.mobilelabs.model.SourceModel
import com.example.mobilelabs.model.StreamAlertEvent
import com.example.mobilelabs.model.StreamingPlatform
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Generates crisp offscreen ARGB_8888 Bitmaps for dynamic OBS / PRISM Live / Streamlabs sources:
 * - Timer / Stopwatch
 * - Scoreboard Counter
 * - Live Chat Box Overlay (YouTube + Twitch merged feed)
 * - Screen Drawing / Ink Layer
 * - Animated GIF / WebP Sticker playback frames
 */
object DynamicSourceRenderer {

    private val renderLock = Any()
    private val defaultBoldTypeface: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val defaultNormalTypeface: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    private val monoBoldTypeface: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaintShared = Paint(Paint.ANTI_ALIAS_FLAG)
    private val layoutTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        typeface = defaultNormalTypeface
    }
    private val inkPath = Path()

    fun computeTimerDisplaySeconds(src: SourceModel, nowMs: Long = System.currentTimeMillis()): Int {
        val activeElapsed = if (src.timerRunning && src.timerStartedAtMs > 0L) {
            src.timerElapsedBeforeMs + (nowMs - src.timerStartedAtMs).coerceAtLeast(0L)
        } else {
            src.timerElapsedBeforeMs
        }
        val elapsedSec = (activeElapsed / 1000L).toInt()
        return if (src.timerCountDown) {
            (src.timerTargetSec - elapsedSec).coerceAtLeast(0)
        } else {
            elapsedSec.coerceAtLeast(0)
        }
    }

    fun computeTimerDisplayString(src: SourceModel, nowMs: Long = System.currentTimeMillis()): String {
        val displaySec = computeTimerDisplaySeconds(src, nowMs)
        val hrs = displaySec / 3600
        val mins = (displaySec % 3600) / 60
        val secs = displaySec % 60
        return if (hrs > 0) "%d:%02d:%02d".format(hrs, mins, secs) else "%02d:%02d".format(mins, secs)
    }

    fun renderTimerBitmap(src: SourceModel, nowMs: Long = System.currentTimeMillis()): Bitmap = synchronized(renderLock) {
        val text = computeTimerDisplayString(src, nowMs)
        val bmp = createBitmap(420, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        fillPaint.color = src.textBgColorArgb.toInt()
        strokePaint.strokeWidth = 3f
        strokePaint.color = if (src.timerRunning) 0xFF2ECC71.toInt() else 0xFFFFB020.toInt()
        val rect = RectF(6f, 6f, 414f, 122f)
        canvas.drawRoundRect(rect, 22f, 22f, fillPaint)
        canvas.drawRoundRect(rect, 22f, 22f, strokePaint)

        textPaintShared.color = 0xFFAEB4C2.toInt()
        textPaintShared.textSize = 20f
        textPaintShared.typeface = defaultBoldTypeface
        textPaintShared.textAlign = Paint.Align.CENTER
        val modeLabel = if (src.timerCountDown) "COUNTDOWN" else "STOPWATCH"
        canvas.drawText(modeLabel, 210f, 34f, textPaintShared)

        textPaintShared.color = src.textColorArgb.toInt()
        textPaintShared.textSize = 62f
        textPaintShared.typeface = monoBoldTypeface
        textPaintShared.textAlign = Paint.Align.CENTER
        canvas.drawText(text, 210f, 102f, textPaintShared)
        return bmp
    }

    fun renderScoreboardBitmap(src: SourceModel): Bitmap = synchronized(renderLock) {
        val bmp = createBitmap(640, 116, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val outer = RectF(4f, 4f, 636f, 112f)
        fillPaint.color = 0xDC11141B.toInt()
        canvas.drawRoundRect(outer, 18f, 18f, fillPaint)
        fillPaint.color = 0xFFCC1B1B.toInt()
        canvas.drawRoundRect(RectF(8f, 8f, 270f, 108f), 14f, 14f, fillPaint)
        fillPaint.color = 0xFF2563EB.toInt()
        canvas.drawRoundRect(RectF(370f, 8f, 632f, 108f), 14f, 14f, fillPaint)
        strokePaint.strokeWidth = 3f
        strokePaint.color = 0xFF394154.toInt()
        canvas.drawRoundRect(outer, 18f, 18f, strokePaint)

        textPaintShared.color = Color.WHITE
        textPaintShared.textSize = 26f
        textPaintShared.typeface = defaultBoldTypeface
        textPaintShared.textAlign = Paint.Align.LEFT
        canvas.drawText(src.scoreHomeName.take(10), 22f, 66f, textPaintShared)

        textPaintShared.textAlign = Paint.Align.RIGHT
        canvas.drawText(src.scoreAwayName.take(10), 616f, 66f, textPaintShared)

        textPaintShared.textSize = 46f
        textPaintShared.typeface = monoBoldTypeface
        textPaintShared.textAlign = Paint.Align.CENTER
        canvas.drawText(src.scoreHome.toString(), 224f, 74f, textPaintShared)
        canvas.drawText(src.scoreAway.toString(), 416f, 74f, textPaintShared)

        textPaintShared.color = 0xFFCBD5E1.toInt()
        textPaintShared.textSize = 24f
        textPaintShared.typeface = defaultBoldTypeface
        textPaintShared.textAlign = Paint.Align.CENTER
        canvas.drawText("VS", 320f, 66f, textPaintShared)
        return bmp
    }

    fun renderChatOverlayBitmap(
        messages: List<PlatformChatMessage>,
        src: SourceModel
    ): Bitmap = synchronized(renderLock) {
        val width = 560
        val height = 440
        val bmp = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val alphaInt = (src.chatOverlayOpacity.coerceIn(0.1f, 1f) * 255).toInt()
        fillPaint.color = Color.argb(alphaInt, 14, 17, 23)
        strokePaint.strokeWidth = 2f
        strokePaint.color = Color.argb(alphaInt, 60, 72, 92)
        canvas.drawRoundRect(RectF(2f, 2f, width - 2f, height - 2f), 16f, 16f, fillPaint)
        canvas.drawRoundRect(RectF(2f, 2f, width - 2f, height - 2f), 16f, 16f, strokePaint)

        textPaintShared.color = 0xFFE2E8F0.toInt()
        textPaintShared.textSize = 19f
        textPaintShared.typeface = defaultBoldTypeface
        textPaintShared.textAlign = Paint.Align.LEFT
        canvas.drawText("LIVE CHAT", 16f, 28f, textPaintShared)

        val recent = messages.takeLast(src.chatOverlayMaxMessages.coerceIn(2, 12))
        if (recent.isEmpty()) {
            textPaintShared.color = 0xFF94A3B8.toInt()
            textPaintShared.textSize = 20f
            textPaintShared.typeface = defaultNormalTypeface
            canvas.drawText("Waiting for YouTube / Twitch chat…", 18f, 72f, textPaintShared)
            return bmp
        }

        textPaintShared.textSize = 21f
        textPaintShared.typeface = defaultBoldTypeface
        textPaintShared.textAlign = Paint.Align.LEFT

        var yCursor = 52f
        val maxY = height - 14f
        for (msg in recent) {
            if (yCursor >= maxY) break
            val platTag = if (msg.platform == StreamingPlatform.YOUTUBE) "[YT]" else "[TW]"
            textPaintShared.color = msg.colorArgb.toInt()
            val prefix = buildString {
                append(platTag)
                if (msg.badgeLabel.isNotBlank()) append(" ${msg.badgeLabel}")
                append(" ${msg.author}: ")
            }
            val fullLine = prefix + msg.text
            val layout = StaticLayout.Builder
                .obtain(fullLine, 0, fullLine.length, layoutTextPaint, width - 32)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(2)
                .build()
            if (yCursor + layout.height > maxY) break
            canvas.save()
            canvas.translate(16f, yCursor)
            layout.draw(canvas)
            canvas.restore()
            canvas.drawText(prefix, 16f, yCursor + 22f, textPaintShared)
            yCursor += layout.height + 10f
        }
        return bmp
    }

    fun renderAlertBoxBitmap(event: StreamAlertEvent?): Bitmap = synchronized(renderLock) {
        val width = 640
        val height = 160
        val bmp = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (event == null) {
            return bmp
        }
        val canvas = Canvas(bmp)
        val accent = event.type.accentArgb.toInt()
        val outer = RectF(6f, 6f, width - 6f, height - 6f)
        fillPaint.color = 0xEB10141F.toInt()
        canvas.drawRoundRect(outer, 24f, 24f, fillPaint)
        fillPaint.color = accent
        canvas.drawRoundRect(RectF(6f, 6f, 22f, height - 6f), 12f, 12f, fillPaint)
        strokePaint.strokeWidth = 4f
        strokePaint.color = accent
        canvas.drawRoundRect(outer, 24f, 24f, strokePaint)

        textPaintShared.textAlign = Paint.Align.LEFT
        textPaintShared.color = accent
        textPaintShared.textSize = 20f
        textPaintShared.typeface = defaultBoldTypeface
        val platTag = if (event.platform == StreamingPlatform.YOUTUBE) "YOUTUBE" else "TWITCH"
        canvas.drawText("$platTag · ${event.type.label}", 42f, 44f, textPaintShared)

        textPaintShared.color = Color.WHITE
        textPaintShared.textSize = 40f
        canvas.drawText(event.senderName.take(24), 42f, 94f, textPaintShared)

        if (event.detailText.isNotBlank()) {
            textPaintShared.color = 0xFFD5DCE8.toInt()
            textPaintShared.textSize = 24f
            textPaintShared.typeface = defaultNormalTypeface
            canvas.drawText(event.detailText.take(44), 42f, 132f, textPaintShared)
        }
        return bmp
    }

    fun renderGoalBarBitmap(src: SourceModel): Bitmap = synchronized(renderLock) {
        val width = 620
        val height = 108
        val bmp = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val target = src.goalTarget.coerceAtLeast(1)
        val current = src.goalCurrent.coerceAtLeast(0)
        val fraction = (current.toFloat() / target.toFloat()).coerceIn(0f, 1f)
        val pct = ((current.toFloat() / target.toFloat()) * 100f).toInt().coerceAtMost(999)

        val outer = RectF(4f, 4f, width - 4f, height - 4f)
        fillPaint.color = 0xE011141C.toInt()
        canvas.drawRoundRect(outer, 20f, 20f, fillPaint)
        strokePaint.strokeWidth = 2.5f
        strokePaint.color = 0xFF3E4961.toInt()
        canvas.drawRoundRect(outer, 20f, 20f, strokePaint)

        textPaintShared.color = Color.WHITE
        textPaintShared.textSize = 23f
        textPaintShared.typeface = defaultBoldTypeface
        textPaintShared.textAlign = Paint.Align.LEFT
        canvas.drawText(src.goalTitle.ifBlank { "STREAM GOAL" }.take(28), 22f, 38f, textPaintShared)

        textPaintShared.color = 0xFFE2E8F0.toInt()
        textPaintShared.typeface = monoBoldTypeface
        textPaintShared.textAlign = Paint.Align.RIGHT
        canvas.drawText("$current / $target ($pct%)", width - 22f, 38f, textPaintShared)

        val barRect = RectF(20f, 54f, width - 20f, 88f)
        fillPaint.color = 0xFF272E3F.toInt()
        canvas.drawRoundRect(barRect, 14f, 14f, fillPaint)
        if (fraction > 0.01f) {
            fillPaint.color = src.goalAccentColorArgb.toInt()
            val fillRight = (barRect.left + barRect.width() * fraction).coerceAtLeast(barRect.left + 20f)
            canvas.drawRoundRect(RectF(barRect.left, barRect.top, fillRight, barRect.bottom), 14f, 14f, fillPaint)
        }
        return bmp
    }

    fun renderDrawingBitmap(
        strokes: List<DrawingStroke>,
        width: Int,
        height: Int
    ): Bitmap = synchronized(renderLock) {
        val w = width.coerceIn(320, 1920)
        val h = height.coerceIn(180, 1080)
        val bmp = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (strokes.isEmpty()) return bmp

        val canvas = Canvas(bmp)
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeJoin = Paint.Join.ROUND
        for (stroke in strokes) {
            if (stroke.points.isEmpty()) continue
            val sw = (stroke.strokeWidthFraction * min(w, h)).coerceIn(2f, 48f)
            if (stroke.points.size == 1) {
                val pt = stroke.points[0]
                fillPaint.color = stroke.colorArgb.toInt()
                canvas.drawCircle(pt.first * w, pt.second * h, sw * 0.5f, fillPaint)
            } else {
                strokePaint.color = stroke.colorArgb.toInt()
                strokePaint.strokeWidth = sw
                inkPath.reset()
                inkPath.moveTo(stroke.points[0].first * w, stroke.points[0].second * h)
                for (i in 1 until stroke.points.size) {
                    val p = stroke.points[i]
                    inkPath.lineTo(p.first * w, p.second * h)
                }
                canvas.drawPath(inkPath, strokePaint)
            }
        }
        return bmp
    }

    @Suppress("DEPRECATION")
    class AnimatedStickerHandle(
        private val movie: Movie?,
        private val animatedDrawable: AnimatedImageDrawable?,
        private val staticFallback: Bitmap?,
        val width: Int,
        val height: Int
    ) {
        private val startMs = SystemClock.uptimeMillis()
        private val frameBmp: Bitmap? = if (movie != null || animatedDrawable != null) {
            createBitmap(max(32, width), max(32, height), Bitmap.Config.ARGB_8888)
        } else null
        private val frameCanvas: Canvas? = frameBmp?.let { Canvas(it) }

        init {
            animatedDrawable?.start()
        }

        fun currentFrameBitmap(): Bitmap? {
            if (movie != null && frameCanvas != null && frameBmp != null) {
                val dur = max(100, movie.duration())
                val relTime = ((SystemClock.uptimeMillis() - startMs) % dur).toInt()
                movie.setTime(relTime)
                frameBmp.eraseColor(Color.TRANSPARENT)
                val sx = frameBmp.width.toFloat() / max(1, movie.width())
                val sy = frameBmp.height.toFloat() / max(1, movie.height())
                frameCanvas.save()
                frameCanvas.scale(sx, sy)
                movie.draw(frameCanvas, 0f, 0f)
                frameCanvas.restore()
                return frameBmp
            }
            if (animatedDrawable != null && frameCanvas != null && frameBmp != null) {
                frameBmp.eraseColor(Color.TRANSPARENT)
                animatedDrawable.setBounds(0, 0, frameBmp.width, frameBmp.height)
                animatedDrawable.draw(frameCanvas)
                return frameBmp
            }
            return staticFallback
        }

        fun release() {
            runCatching { animatedDrawable?.stop() }
            runCatching { frameBmp?.recycle() }
            runCatching { staticFallback?.recycle() }
        }
    }

    @Suppress("DEPRECATION")
    fun loadAnimatedSticker(context: Context?, pathOrUri: String, maxSize: Int = 512): AnimatedStickerHandle? {
        return runCatching {
            if (pathOrUri.endsWith(".gif", ignoreCase = true) || pathOrUri.endsWith(".webp", ignoreCase = true)) {
                val drawable = if (pathOrUri.startsWith("content://") && context != null) {
                    val src = ImageDecoder.createSource(context.contentResolver, pathOrUri.toUri())
                    ImageDecoder.decodeDrawable(src)
                } else {
                    val file = File(pathOrUri)
                    if (!file.exists()) return null
                    ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
                }
                if (drawable is AnimatedImageDrawable) {
                    val dw = drawable.intrinsicWidth.coerceIn(64, maxSize)
                    val dh = drawable.intrinsicHeight.coerceIn(64, maxSize)
                    return AnimatedStickerHandle(null, drawable, null, dw, dh)
                }
            }

            // Fallback to Movie for GIF streams or BitmapFactory for static stickers
            openInput(context, pathOrUri)?.use { input ->
                val bytes = input.readBytes()
                val movie = Movie.decodeByteArray(bytes, 0, bytes.size)
                if (movie != null && movie.duration() > 0 && movie.width() > 0 && movie.height() > 0) {
                    val scale = min(1f, maxSize.toFloat() / max(movie.width(), movie.height()))
                    val w = (movie.width() * scale).toInt().coerceIn(32, maxSize)
                    val h = (movie.height() * scale).toInt().coerceIn(32, maxSize)
                    return AnimatedStickerHandle(movie, null, null, w, h)
                }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                AnimatedStickerHandle(null, null, bmp, bmp.width, bmp.height)
            }
        }.getOrNull()
    }

    private fun openInput(context: Context?, pathOrUri: String): InputStream? {
        if (pathOrUri.startsWith("content://")) {
            return context?.contentResolver?.openInputStream(pathOrUri.toUri())
        }
        val f = File(pathOrUri)
        return if (f.exists() && f.canRead()) f.inputStream() else null
    }
}
