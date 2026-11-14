package com.example.mobilelabs.probes

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * Safely resolves physical display dimensions and refresh rates from ANY Context
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
            val width = mode?.physicalWidth?.takeIf { it > 0 } ?: dmRes.widthPixels.coerceAtLeast(1280)
            val height = mode?.physicalHeight?.takeIf { it > 0 } ?: dmRes.heightPixels.coerceAtLeast(720)
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
}
