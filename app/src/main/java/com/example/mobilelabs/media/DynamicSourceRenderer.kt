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
import com.example.mobilelabs.model.DrawingStroke
import com.example.mobilelabs.model.PlatformChatMessage
import com.example.mobilelabs.model.SourceModel
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

    fun computeTimerDisplayString(src: SourceModel, nowMs: Long = System.currentTimeMillis()): String {
        val activeElapsed = if (src.timerRunning && src.timerStartedAtMs > 0L) {
            src.timerElapsedBeforeMs + (nowMs - src.timerStartedAtMs).coerceAtLeast(0L)
        } else {
            src.timerElapsedBeforeMs
        }
        val elapsedSec = (activeElapsed / 1000L).toInt()
        val displaySec = if (src.timerCountDown) {
            (src.timerTargetSec - elapsedSec).coerceAtLeast(0)
        } else {
            elapsedSec.coerceAtLeast(0)
        }
        val hrs = displaySec / 3600
        val mins = (displaySec % 3600) / 60
        val secs = displaySec % 60
        return if (hrs > 0) "%d:%02d:%02d".format(hrs, mins, secs) else "%02d:%02d".format(mins, secs)
    }

    fun renderTimerBitmap(src: SourceModel, nowMs: Long = System.currentTimeMillis()): Bitmap {
        val text = computeTimerDisplayString(src, nowMs)
        val bmp = createBitmap(420, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = src.textBgColorArgb.toInt()
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = if (src.timerRunning) 0xFF2ECC71.toInt() else 0xFFFFB020.toInt()
        }
        val rect = RectF(6f, 6f, 414f, 122f)
        canvas.drawRoundRect(rect, 22f, 22f, bgPaint)
        canvas.drawRoundRect(rect, 22f, 22f, borderPaint)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFAEB4C2.toInt()
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val modeLabel = if (src.timerCountDown) "COUNTDOWN" else "STOPWATCH"
        canvas.drawText(modeLabel, 210f, 34f, labelPaint)

        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = src.textColorArgb.toInt()
            textSize = 62f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(text, 210f, 102f, timePaint)
        return bmp
    }

    fun renderScoreboardBitmap(src: SourceModel): Bitmap {
        val bmp = createBitmap(640, 116, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xDC11141B.toInt() }
        val accentHome = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFCC1B1B.toInt() }
        val accentAway = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2563EB.toInt() }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = 0xFF394154.toInt()
        }

        val outer = RectF(4f, 4f, 636f, 112f)
        canvas.drawRoundRect(outer, 18f, 18f, bg)
        canvas.drawRoundRect(RectF(8f, 8f, 270f, 108f), 14f, 14f, accentHome)
        canvas.drawRoundRect(RectF(370f, 8f, 632f, 108f), 14f, 14f, accentAway)
        canvas.drawRoundRect(outer, 18f, 18f, border)

        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
        val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 46f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val vsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFCBD5E1.toInt()
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        canvas.drawText(src.scoreHomeName.take(10), 22f, 66f, namePaint)
        canvas.drawText(src.scoreHome.toString(), 224f, 74f, scorePaint)
        canvas.drawText("VS", 320f, 66f, vsPaint)
        canvas.drawText(src.scoreAway.toString(), 416f, 74f, scorePaint)
        namePaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(src.scoreAwayName.take(10), 616f, 66f, namePaint)
        return bmp
    }

    fun renderChatOverlayBitmap(
        messages: List<PlatformChatMessage>,
        src: SourceModel
    ): Bitmap {
        val width = 560
        val height = 440
        val bmp = createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val alphaInt = (src.chatOverlayOpacity.coerceIn(0.1f, 1f) * 255).toInt()
        val bgColor = Color.argb(alphaInt, 14, 17, 23)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.argb(alphaInt, 60, 72, 92)
        }
        canvas.drawRoundRect(RectF(2f, 2f, width - 2f, height - 2f), 16f, 16f, bgPaint)
        canvas.drawRoundRect(RectF(2f, 2f, width - 2f, height - 2f), 16f, 16f, borderPaint)

        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE2E8F0.toInt()
            textSize = 19f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("LIVE CHAT", 16f, 28f, headerPaint)

        val recent = messages.takeLast(src.chatOverlayMaxMessages.coerceIn(2, 12))
        if (recent.isEmpty()) {
            val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF94A3B8.toInt()
                textSize = 20f
            }
            canvas.drawText("Waiting for YouTube / Twitch chat…", 18f, 72f, emptyPaint)
            return bmp
        }

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val authorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 21f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        var yCursor = 52f
        val maxY = height - 14f
        for (msg in recent) {
            if (yCursor >= maxY) break
            val platTag = if (msg.platform == StreamingPlatform.YOUTUBE) "[YT]" else "[TW]"
            authorPaint.color = msg.colorArgb.toInt()
            val prefix = buildString {
                append(platTag)
                if (msg.badgeLabel.isNotBlank()) append(" ${msg.badgeLabel}")
                append(" ${msg.author}: ")
            }
            val fullLine = prefix + msg.text
            val layout = StaticLayout.Builder
                .obtain(fullLine, 0, fullLine.length, textPaint, width - 32)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(2)
                .build()
            if (yCursor + layout.height > maxY) break
            canvas.save()
            canvas.translate(16f, yCursor)
            layout.draw(canvas)
            canvas.restore()
            // Paint author tag over first line left edge cleanly
            canvas.drawText(prefix, 16f, yCursor + 22f, authorPaint)
            yCursor += layout.height + 10f
        }
        return bmp
    }

    fun renderDrawingBitmap(
        strokes: List<DrawingStroke>,
        width: Int,
        height: Int
    ): Bitmap {
        val w = width.coerceIn(320, 1920)
        val h = height.coerceIn(180, 1080)
        val bmp = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (strokes.isEmpty()) return bmp

        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (stroke in strokes) {
            if (stroke.points.isEmpty()) continue
            paint.color = stroke.colorArgb.toInt()
            paint.strokeWidth = (stroke.strokeWidthFraction * min(w, h)).coerceIn(2f, 48f)
            if (stroke.points.size == 1) {
                val pt = stroke.points[0]
                paint.style = Paint.Style.FILL
                canvas.drawCircle(pt.first * w, pt.second * h, paint.strokeWidth * 0.5f, paint)
                paint.style = Paint.Style.STROKE
            } else {
                val path = Path()
                path.moveTo(stroke.points[0].first * w, stroke.points[0].second * h)
                for (i in 1 until stroke.points.size) {
                    val p = stroke.points[i]
                    path.lineTo(p.first * w, p.second * h)
                }
                canvas.drawPath(path, paint)
            }
        }
        return bmp
    }

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
                    val src = ImageDecoder.createSource(context.contentResolver, Uri.parse(pathOrUri))
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
            return context?.contentResolver?.openInputStream(Uri.parse(pathOrUri))
        }
        val f = File(pathOrUri)
        return if (f.exists() && f.canRead()) f.inputStream() else null
    }
}
