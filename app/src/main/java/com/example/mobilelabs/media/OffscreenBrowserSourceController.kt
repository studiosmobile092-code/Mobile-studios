package com.example.mobilelabs.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.graphics.createBitmap
import com.example.mobilelabs.model.SourceModel
import com.example.mobilelabs.model.SourceType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Throttled offscreen WebView renderer for OBS Browser Sources & Alert Widgets (Streamlabs, StreamElements, custom HTML).
 * - Renders offscreen WebViews at a bounded FPS cap (15–30 fps) to preserve gaming performance
 * - Supports transparent backgrounds for stream alerts/overlays
 * - Pauses JS timers and capture automatically when the source is hidden or screen is locked
 */
class OffscreenBrowserSourceController(private val context: Context) {

    data class BrowserFrameSnapshot(
        val version: Long,
        val bitmap: Bitmap
    )

    private class BrowserInstance(
        val sourceId: String,
        var url: String,
        var widthPx: Int,
        var heightPx: Int,
        var fpsCap: Int,
        var transparentBg: Boolean,
        val webView: WebView,
        var lastCaptureMs: Long = 0L,
        val versionCounter: AtomicLong = AtomicLong(0L),
        @Volatile var latestBitmap: Bitmap? = null
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val instances = ConcurrentHashMap<String, BrowserInstance>()
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)

    private val captureTick = object : Runnable {
        override fun run() {
            if (!running.get()) return
            val now = System.currentTimeMillis()
            if (!paused.get()) {
                instances.values.forEach { inst ->
                    val periodMs = (1000L / inst.fpsCap.coerceIn(5, 30)).coerceAtLeast(33L)
                    if (now - inst.lastCaptureMs >= periodMs) {
                        inst.lastCaptureMs = now
                        captureWebViewBitmap(inst)
                    }
                }
            }
            mainHandler.postDelayed(this, 33L)
        }
    }

    fun start() {
        if (running.getAndSet(true)) return
        mainHandler.post(captureTick)
    }

    fun setPaused(isPaused: Boolean) {
        paused.set(isPaused)
        mainHandler.post {
            instances.values.forEach { inst ->
                if (isPaused) inst.webView.onPause() else inst.webView.onResume()
            }
        }
    }

    fun syncVisibleSources(visibleBrowserSources: List<SourceModel>) {
        val activeIds = visibleBrowserSources.filter { it.type == SourceType.BROWSER_SOURCE && it.visible }
            .take(3)
            .associateBy { it.id }

        mainHandler.post {
            // Destroy stale instances
            val toRemove = instances.keys.filter { !activeIds.containsKey(it) }
            toRemove.forEach { id ->
                instances.remove(id)?.let { inst ->
                    runCatching {
                        inst.webView.stopLoading()
                        inst.webView.destroy()
                    }
                    runCatching { inst.latestBitmap?.recycle() }
                }
            }

            // Create or update active instances
            activeIds.forEach { (id, src) ->
                val existing = instances[id]
                if (existing == null) {
                    val created = createBrowserInstance(src)
                    instances[id] = created
                } else {
                    existing.fpsCap = src.browserFpsCap.coerceIn(5, 30)
                    if (existing.transparentBg != src.browserTransparentBg) {
                        existing.transparentBg = src.browserTransparentBg
                        existing.webView.setBackgroundColor(
                            if (src.browserTransparentBg) Color.TRANSPARENT else Color.BLACK
                        )
                    }
                    if (existing.widthPx != src.browserWidthPx || existing.heightPx != src.browserHeightPx) {
                        existing.widthPx = src.browserWidthPx.coerceIn(160, 1280)
                        existing.heightPx = src.browserHeightPx.coerceIn(120, 720)
                        layoutOffscreenWebView(existing.webView, existing.widthPx, existing.heightPx)
                    }
                    if (existing.url != src.browserUrl && src.browserUrl.isNotBlank()) {
                        existing.url = src.browserUrl
                        existing.webView.loadUrl(sanitizeUrl(src.browserUrl))
                    }
                }
            }
        }
    }

    fun reloadSource(sourceId: String) {
        mainHandler.post {
            instances[sourceId]?.webView?.reload()
        }
    }

    fun getLatestFrame(sourceId: String): BrowserFrameSnapshot? {
        val inst = instances[sourceId] ?: return null
        val bmp = inst.latestBitmap ?: return null
        if (bmp.isRecycled) return null
        return BrowserFrameSnapshot(inst.versionCounter.get(), bmp)
    }

    fun releaseAll() {
        running.set(false)
        mainHandler.removeCallbacks(captureTick)
        mainHandler.post {
            instances.values.forEach { inst ->
                runCatching {
                    inst.webView.stopLoading()
                    inst.webView.destroy()
                }
                runCatching { inst.latestBitmap?.recycle() }
            }
            instances.clear()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createBrowserInstance(src: SourceModel): BrowserInstance {
        val w = src.browserWidthPx.coerceIn(160, 1280)
        val h = src.browserHeightPx.coerceIn(120, 720)
        val webView = WebView(context).apply {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null) // Deterministic Canvas.draw() offscreen capture
            setBackgroundColor(if (src.browserTransparentBg) Color.TRANSPARENT else Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            webViewClient = object : WebViewClient() {}
        }
        layoutOffscreenWebView(webView, w, h)
        val cleanUrl = sanitizeUrl(src.browserUrl)
        webView.loadUrl(cleanUrl)

        return BrowserInstance(
            sourceId = src.id,
            url = cleanUrl,
            widthPx = w,
            heightPx = h,
            fpsCap = src.browserFpsCap.coerceIn(5, 30),
            transparentBg = src.browserTransparentBg,
            webView = webView
        )
    }

    private fun layoutOffscreenWebView(webView: WebView, w: Int, h: Int) {
        val wSpec = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY)
        val hSpec = View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        webView.measure(wSpec, hSpec)
        webView.layout(0, 0, w, h)
    }

    private fun captureWebViewBitmap(inst: BrowserInstance) {
        val w = inst.widthPx
        val h = inst.heightPx
        val bmp = createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (!inst.transparentBg) {
            canvas.drawColor(Color.BLACK)
        }
        runCatching {
            inst.webView.draw(canvas)
            val prev = inst.latestBitmap
            inst.latestBitmap = bmp
            inst.versionCounter.incrementAndGet()
            if (prev != null && prev !== bmp && !prev.isRecycled) {
                // Delayed safety: compositor copies into GL texture synchronously or checks isRecycled
            }
        }.onFailure {
            bmp.recycle()
        }
    }

    private fun sanitizeUrl(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("https://", true) || trimmed.startsWith("http://", true) -> trimmed
            trimmed.isBlank() -> "about:blank"
            else -> "https://$trimmed"
        }
    }
}
