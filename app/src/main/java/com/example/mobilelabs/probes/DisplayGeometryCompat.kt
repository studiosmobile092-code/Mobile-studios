package com.example.mobilelabs.probes

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.WindowManager

/**
 * Safely resolves rotation-aware physical display dimensions and refresh rates from ANY Context
 * (Application, Service, or Activity) across API 29-36 without throwing
 * UnsupportedOperationException from non-visual Context.display / currentWindowMetrics calls.
 */
object DisplayGeometryCompat {

    data class DisplayMetricsInfo(
        val widthPx: Int,
        val heightPx: Int,
        val refreshHz: Float,
        val supportedRefreshRates: List<Float>
    )

    fun readDefaultDisplay(context: Context): DisplayMetricsInfo {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        val dmRes = context.resources.displayMetrics

        if (display != null) {
            val mode = runCatching { display.mode }.getOrNull()
            val physW = mode?.physicalWidth?.takeIf { it > 0 } ?: 0
            val physH = mode?.physicalHeight?.takeIf { it > 0 } ?: 0
            val rotation = runCatching { display.rotation }.getOrDefault(Surface.ROTATION_0)

            var logicalW = dmRes.widthPixels
            var logicalH = dmRes.heightPixels
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = runCatching {
                    val displayCtx = context.createDisplayContext(display)
                    displayCtx.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
                }.getOrNull()
                if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                    logicalW = bounds.width()
                    logicalH = bounds.height()
                }
            }

            val (width, height) = resolveOrientedDimensions(
                physWidth = physW,
                physHeight = physH,
                logicalWidth = logicalW,
                logicalHeight = logicalH,
                rotation = rotation
            )

            val refresh = runCatching { display.refreshRate }.getOrDefault(60f).takeIf { it > 0f } ?: 60f
            val supported = runCatching {
                display.supportedModes.map { it.refreshRate }.distinct().sorted()
            }.getOrDefault(listOf(refresh)).ifEmpty { listOf(refresh) }
            return DisplayMetricsInfo(width, height, refresh, supported)
        }

        return DisplayMetricsInfo(
            widthPx = dmRes.widthPixels.coerceAtLeast(1280),
            heightPx = dmRes.heightPixels.coerceAtLeast(720),
            refreshHz = 60f,
            supportedRefreshRates = listOf(60f)
        )
    }

    /**
     * Resolves display pixel dimensions matched to the active UI orientation.
     * Prevents portrait-native tablet panels (e.g. 1200x1920) from arming a portrait
     * VirtualDisplay when the device is currently held in landscape (1920x1200).
     */
    fun resolveOrientedDimensions(
        physWidth: Int,
        physHeight: Int,
        logicalWidth: Int,
        logicalHeight: Int,
        rotation: Int
    ): Pair<Int, Int> {
        if (physWidth <= 0 || physHeight <= 0) {
            return logicalWidth.coerceAtLeast(720) to logicalHeight.coerceAtLeast(720)
        }
        val rotatedQuarterTurn = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
        val logicalWiderThanTall = logicalWidth > 0 && logicalHeight > 0 && logicalWidth > logicalHeight
        val logicalTallerThanWide = logicalWidth > 0 && logicalHeight > 0 && logicalHeight > logicalWidth
        val physWiderThanTall = physWidth >= physHeight

        // Prefer logical orientation if available; otherwise invert when rotation is 90/270 deg.
        val shouldBeLandscape = when {
            logicalWiderThanTall -> true
            logicalTallerThanWide -> false
            else -> if (rotatedQuarterTurn) !physWiderThanTall else physWiderThanTall
        }

        val longSide = maxOf(physWidth, physHeight).coerceAtLeast(720)
        val shortSide = minOf(physWidth, physHeight).coerceAtLeast(720)
        return if (shouldBeLandscape) {
            longSide to shortSide
        } else {
            shortSide to longSide
        }
    }
}
