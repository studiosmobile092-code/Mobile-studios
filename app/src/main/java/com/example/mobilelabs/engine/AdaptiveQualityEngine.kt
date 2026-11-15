package com.example.mobilelabs.engine

import android.os.PowerManager
import com.example.mobilelabs.model.AdaptiveQualityLevel
import com.example.mobilelabs.model.AdaptiveQualityState
import com.example.mobilelabs.model.TelemetrySnapshot

/**
 * M3.1 Adaptive Quality Engine with strict hysteresis (§12.3):
 * - Evaluates thermal status & headroom forecast, encoder drops/queue, and battery temperature.
 * - Step-down triggers only after >= 5 seconds of sustained stress (except L5 critical safety).
 * - Step-up recovery only after >= 120 seconds of stable nominal conditions (or >= 300s from L3).
 * - Rate-limited to at most 1 transition per 15 seconds.
 * - Honors explicit user lock ("Don't adapt").
 */
class AdaptiveQualityEngine {

    data class EvaluationOutcome(
        val state: AdaptiveQualityState,
        val levelChanged: Boolean,
        val recommendedBitrateScale: Float,
        val shouldSwitchLowOverhead: Boolean,
        val shouldSuspendFilters: Boolean,
        val shouldDropCamera: Boolean,
        val shouldEmergencyStopStream: Boolean
    )

    private var currentLevel = AdaptiveQualityLevel.L0_NORMAL
    private var lockedByUser = false
    private var lastChangeAtMs = 0L
    private var highTriggerSinceMs = 0L
    private var pendingTargetLevel: AdaptiveQualityLevel? = null
    private var stableSinceMs = 0L
    private val history = ArrayDeque<String>(24)

    fun setLockedByUser(locked: Boolean): AdaptiveQualityState {
        lockedByUser = locked
        val msg = if (locked) "Locked by user (adaptation paused)" else "Adaptation unlocked"
        appendHistory(msg)
        return snapshot(msg)
    }

    fun resetToNormal(reason: String = "Manual reset to L0"): AdaptiveQualityState {
        currentLevel = AdaptiveQualityLevel.L0_NORMAL
        pendingTargetLevel = null
        highTriggerSinceMs = 0L
        stableSinceMs = 0L
        appendHistory(reason)
        return snapshot(reason)
    }

    fun evaluate(
        telemetry: TelemetrySnapshot,
        isStreamingOrRecording: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): EvaluationOutcome {
        if (!isStreamingOrRecording) {
            highTriggerSinceMs = 0L
            pendingTargetLevel = null
            if (currentLevel != AdaptiveQualityLevel.L0_NORMAL) {
                currentLevel = AdaptiveQualityLevel.L0_NORMAL
                appendHistory("Session idle -> reset L0")
            }
            return buildOutcome(snapshot("Session idle"), changed = false)
        }

        if (lockedByUser) {
            return buildOutcome(snapshot("Quality locked by user"), changed = false)
        }

        val thermal = telemetry.thermal
        val dropTotal = telemetry.encoderDroppedFrames + telemetry.networkDroppedFrames
        val desiredDown = classifyStressLevel(
            thermalStatus = thermal.statusCode,
            headroom = thermal.headroomForecast,
            batteryTempC = thermal.batteryTempC,
            encoderQueueDepth = telemetry.encoderQueueDepth,
            totalDroppedFrames = dropTotal
        )

        var changed = false
        var reason = snapshotReason()

        if (desiredDown.ordinal > currentLevel.ordinal) {
            stableSinceMs = 0L
            // Emergency L5 triggers immediately; L1..L4 require >= 5s sustained pressure
            val sustainedNeededMs = if (desiredDown == AdaptiveQualityLevel.L5_CRITICAL_STOP) 0L else 5_000L
            if (pendingTargetLevel != desiredDown) {
                pendingTargetLevel = desiredDown
                highTriggerSinceMs = nowMs
            }
            val elapsedTrigger = nowMs - highTriggerSinceMs
            val cooldownElapsed = lastChangeAtMs == 0L || (nowMs - lastChangeAtMs) >= 15_000L || desiredDown == AdaptiveQualityLevel.L5_CRITICAL_STOP
            if (elapsedTrigger >= sustainedNeededMs && cooldownElapsed) {
                currentLevel = desiredDown
                lastChangeAtMs = nowMs
                pendingTargetLevel = null
                highTriggerSinceMs = 0L
                changed = true
                reason = "Step down -> ${currentLevel.shortTag} (${thermal.statusLabel}, headroom=${"%.2f".format(thermal.headroomForecast)}, temp=${"%.1f".format(thermal.batteryTempC)}°C)"
                appendHistory(reason)
            }
        } else if (desiredDown == AdaptiveQualityLevel.L0_NORMAL && currentLevel != AdaptiveQualityLevel.L0_NORMAL) {
            pendingTargetLevel = null
            highTriggerSinceMs = 0L
            // Only automatic recovery for L1..L3; L4/L5 require manual intervention per §12.3
            if (currentLevel.ordinal <= AdaptiveQualityLevel.L3_SEVERE_THERMAL.ordinal) {
                if (stableSinceMs == 0L) stableSinceMs = nowMs
                val recoveryWindowMs = if (currentLevel == AdaptiveQualityLevel.L3_SEVERE_THERMAL) 300_000L else 120_000L
                val cooldownElapsed = (nowMs - lastChangeAtMs) >= 15_000L
                if ((nowMs - stableSinceMs) >= recoveryWindowMs && cooldownElapsed) {
                    val prev = currentLevel
                    currentLevel = AdaptiveQualityLevel.entries[currentLevel.ordinal - 1]
                    lastChangeAtMs = nowMs
                    stableSinceMs = nowMs
                    changed = true
                    reason = "Recovered ${prev.shortTag} -> ${currentLevel.shortTag} after sustained stability"
                    appendHistory(reason)
                }
            }
        } else {
            pendingTargetLevel = null
            highTriggerSinceMs = 0L
        }

        return buildOutcome(snapshot(reason), changed)
    }

    private fun classifyStressLevel(
        thermalStatus: Int,
        headroom: Float,
        batteryTempC: Float,
        encoderQueueDepth: Int,
        totalDroppedFrames: Long
    ): AdaptiveQualityLevel {
        return when {
            thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL || batteryTempC >= 46.0f ->
                AdaptiveQualityLevel.L5_CRITICAL_STOP
            thermalStatus == PowerManager.THERMAL_STATUS_SEVERE && currentLevel.ordinal >= AdaptiveQualityLevel.L3_SEVERE_THERMAL.ordinal ->
                AdaptiveQualityLevel.L4_PROLONGED_SEVERE
            thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE || headroom >= 1.0f ->
                AdaptiveQualityLevel.L3_SEVERE_THERMAL
            thermalStatus == PowerManager.THERMAL_STATUS_MODERATE && currentLevel.ordinal >= AdaptiveQualityLevel.L1_MILD_THROTTLE.ordinal ->
                AdaptiveQualityLevel.L2_SUSTAINED_LOAD
            headroom >= 0.85f || thermalStatus == PowerManager.THERMAL_STATUS_MODERATE || encoderQueueDepth >= 24 || totalDroppedFrames >= 60L ->
                AdaptiveQualityLevel.L1_MILD_THROTTLE
            else -> AdaptiveQualityLevel.L0_NORMAL
        }
    }

    private fun buildOutcome(state: AdaptiveQualityState, changed: Boolean): EvaluationOutcome {
        val lvl = state.level
        return EvaluationOutcome(
            state = state,
            levelChanged = changed,
            recommendedBitrateScale = lvl.bitrateScale,
            shouldSwitchLowOverhead = lvl.ordinal >= AdaptiveQualityLevel.L3_SEVERE_THERMAL.ordinal,
            shouldSuspendFilters = lvl.ordinal >= AdaptiveQualityLevel.L2_SUSTAINED_LOAD.ordinal,
            shouldDropCamera = lvl.ordinal >= AdaptiveQualityLevel.L4_PROLONGED_SEVERE.ordinal,
            shouldEmergencyStopStream = lvl == AdaptiveQualityLevel.L5_CRITICAL_STOP
        )
    }

    private fun snapshot(reason: String): AdaptiveQualityState {
        val actions = buildList {
            when (currentLevel) {
                AdaptiveQualityLevel.L0_NORMAL -> add("Full preset quality")
                AdaptiveQualityLevel.L1_MILD_THROTTLE -> add("Bitrate -20%; HUD graphs dimmed")
                AdaptiveQualityLevel.L2_SUSTAINED_LOAD -> add("Bitrate -40%; GPU source filters bypassed")
                AdaptiveQualityLevel.L3_SEVERE_THERMAL -> add("Switched to Low-Overhead 720p30")
                AdaptiveQualityLevel.L4_PROLONGED_SEVERE -> add("Camera source hidden to protect game FPS")
                AdaptiveQualityLevel.L5_CRITICAL_STOP -> add("Finalized recording & stopped stream")
            }
        }
        return AdaptiveQualityState(
            level = currentLevel,
            lockedByUser = lockedByUser,
            lastChangeReason = reason,
            activeActions = actions,
            decisionHistory = history.toList()
        )
    }

    private fun snapshotReason(): String = history.lastOrNull() ?: "Nominal thermal & queue headroom"

    private fun appendHistory(entry: String) {
        if (history.size >= 24) history.removeFirst()
        history.addLast(entry)
    }

    companion object {
        /**
         * Pure Network ABR step-down & smooth recovery calculator:
         * Steps bitrate down promptly when RTMP congestion pressure >= 0.35 or frames drop,
         * and gently recovers up toward baseBitrateKbps when queue stays clear.
         */
        fun evaluateNetworkAbr(
            baseBitrateKbps: Int,
            currentTargetKbps: Int,
            congestionPressure: Float,
            queueDepth: Int,
            recentDropDelta: Long
        ): Int {
            val ceiling = baseBitrateKbps.coerceAtLeast(800)
            val floor = (ceiling * 0.35f).toInt().coerceAtLeast(600)
            val active = (if (currentTargetKbps <= 0) ceiling else currentTargetKbps).coerceIn(floor, ceiling)

            return when {
                recentDropDelta > 0L || congestionPressure >= 0.55f || queueDepth >= 60 -> {
                    (active * 0.78f).toInt().coerceIn(floor, ceiling)
                }
                congestionPressure >= 0.30f || queueDepth >= 28 -> {
                    (active * 0.88f).toInt().coerceIn(floor, ceiling)
                }
                congestionPressure < 0.12f && queueDepth < 8 && active < ceiling -> {
                    minOf(ceiling, active + maxOf(150, (ceiling * 0.06f).toInt()))
                }
                else -> active
            }
        }
    }
}
