package com.example.mobilelabs.probes

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import com.example.mobilelabs.model.SamsungOneUiStatus

/**
 * M3.3 Samsung / One UI Awareness using strictly standard Android APIs (no root, no private hooks).
 * Detects Galaxy Tab A9+ / Samsung hardware, RAM tier (4/6/8 GB), battery optimization status,
 * 90 Hz vs 60 Hz refresh cadence, and creates Play-compliant System Settings intents.
 */
object SamsungOneUiAdvisor {

    fun inspect(context: Context): SamsungOneUiStatus {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        val isSamsung = manufacturer.equals("samsung", ignoreCase = true)

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val totalRamMb = memInfo.totalMem / (1024L * 1024L)
        val ramTier = when {
            totalRamMb >= 7000L -> "8 GB Tier ($totalRamMb MB · Primary Target)"
            totalRamMb >= 5000L -> "6 GB Tier ($totalRamMb MB · Monitor Background Apps)"
            else -> "4 GB Tier ($totalRamMb MB · Prefer Low-Overhead/Balanced)"
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isExempt = runCatching {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)

        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        val maxHz = display?.supportedModes?.maxOfOrNull { it.refreshRate }
            ?: display?.refreshRate
            ?: 60f

        val notes = buildList {
            if (!isExempt) {
                add("Set Battery to 'Unrestricted' in App Info so One UI does not throttle the background capture service during long streams.")
            } else {
                add("Battery optimization is already Unrestricted (good for long 30–60 min streams).")
            }
            if (maxHz > 65f) {
                add("Display supports ${maxHz.toInt()} Hz. For even 60 fps cadence (zero 90→60 judder), set tablet Motion Smoothness to 60 Hz or stream at 30 fps.")
            }
            if (isSamsung) {
                add("Samsung Game Booster: Avoid 'Battery Saver' mode inside Gaming Hub while streaming; choose Balanced or Performance.")
                add("One UI Overlay: Grant 'Appear on top' only if using the floating quick-bar; system ongoing notification always works without overlay permission.")
            }
            if (totalRamMb < 7000L) {
                add("Tight RAM device ($totalRamMb MB): avoid keeping >2 heavy video/media layers decoded simultaneously.")
            }
        }

        val displayModel = buildString {
            if (manufacturer.isNotBlank()) append(manufacturer).append(' ')
            append(model.ifBlank { "Android Tablet" })
        }

        return SamsungOneUiStatus(
            isSamsungDevice = isSamsung,
            deviceDisplayModel = displayModel,
            ramTierLabel = ramTier,
            isBatteryOptimizationExempt = isExempt,
            displayMaxRefreshHz = maxHz,
            guidanceNotes = notes
        )
    }

    fun buildBatteryOptimizationSettingsIntent(): Intent {
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
