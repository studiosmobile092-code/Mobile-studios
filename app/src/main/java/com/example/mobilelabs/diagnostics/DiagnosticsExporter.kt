package com.example.mobilelabs.diagnostics

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.example.mobilelabs.model.MobileLabsUiState
import com.example.mobilelabs.model.TelemetrySnapshot
import com.example.mobilelabs.security.KeystoreSecretStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Bounded 1 Hz telemetry CSV ring buffer + redacted ZIP exporter (§11.3, R12, R13).
 * Never writes primary or secondary stream keys or unredacted RTMP credentials into exported archives.
 */
class DiagnosticsExporter(private val context: Context) {

    private val maxRows = 7200 // Bounded 2 hours at 1 Hz (R12)
    private val csvRows = ArrayDeque<String>(256)
    private val lock = Any()

    fun appendTelemetrySample(state: MobileLabsUiState) {
        val t: TelemetrySnapshot = state.telemetry
        val row = buildString {
            append(t.timestampMs).append(',')
            append(String.format(Locale.US, "%.1f", t.captureCadenceFps)).append(',')
            append(String.format(Locale.US, "%.1f", t.compositorFps)).append(',')
            append(String.format(Locale.US, "%.1f", t.encoderFps)).append(',')
            append(t.outputWidth).append('x').append(t.outputHeight).append(',')
            append(t.configuredFps).append(',')
            append(t.actualBitrateKbps).append(',')
            append(t.keyframeIntervalSec).append(',')
            append(t.renderSkippedFrames).append(',')
            append(t.encoderDroppedFrames).append(',')
            append(t.networkDroppedFrames).append(',')
            append(t.captureGapCount).append(',')
            append(t.encoderQueueDepth).append(',')
            append(t.rtmpBufferedBytes).append(',')
            append(t.reconnectCount).append(',')
            append(t.processPssMb).append(',')
            append(t.nativeHeapMb).append(',')
            append(t.freeRamMb).append(',')
            append(t.thermal.statusLabel).append(',')
            append(String.format(Locale.US, "%.2f", t.thermal.headroomForecast)).append(',')
            append(t.thermal.batteryPercent).append(',')
            append(String.format(Locale.US, "%.1f", t.thermal.batteryTempC)).append(',')
            append(t.thermal.isCharging).append(',')
            append(t.freeStorageMb).append(',')
            append(state.videoConfig.presetId.name).append(',')
            append(state.adaptiveQuality.level.shortTag).append(',')
            append(t.encoderMode.hudTag).append(',')
            append(state.sessionPhase.name).append(',')
            append(state.dualDestination.phase.name).append(',')
            append(state.recordingPhase.name).append(',')
            append(t.isOverlayShown).append(',')
            append(t.isUiVisible)
        }
        synchronized(lock) {
            if (csvRows.size >= maxRows) {
                csvRows.removeFirst()
            }
            csvRows.addLast(row)
        }
    }

    fun exportRedactedZip(
        state: MobileLabsUiState,
        streamKeyToScrub: String?,
        secondaryStreamKeyToScrub: String? = null
    ): String? {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val zipName = "OBSmobile_Diag_$stamp.zip"
        val extraKeys = listOfNotNull(secondaryStreamKeyToScrub)
        val zipBytes = buildZipBytes(state, streamKeyToScrub, extraKeys)

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, zipName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = runCatching {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }.getOrNull()

        if (uri != null) {
            val wrote = runCatching {
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(zipBytes)
                    out.flush()
                }
                true
            }.getOrDefault(false)
            if (wrote) return "Downloads/$zipName"
        }

        val fallback = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), zipName)
        fallback.parentFile?.mkdirs()
        FileOutputStream(fallback).use { it.write(zipBytes) }
        return fallback.absolutePath
    }

    private fun buildZipBytes(
        state: MobileLabsUiState,
        streamKeyToScrub: String?,
        extraKeys: List<String>
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            // 1) 1 Hz CSV
            zos.putNextEntry(ZipEntry("telemetry_1hz.csv"))
            val header = "timestamp_ms,capture_fps,compositor_fps,encoder_fps,output_res,cfg_fps," +
                "bitrate_kbps,keyint_s,render_skipped,encoder_dropped,net_dropped,capture_gaps," +
                "enc_queue,rtmp_buf_bytes,reconnects,pss_mb,native_heap_mb,free_ram_mb," +
                "thermal_status,thermal_headroom,battery_pct,battery_temp_c,charging,free_storage_mb," +
                "preset,adaptive_level,encoder_mode,session_phase,dual_phase,recording_phase,overlay_shown,ui_visible\n"
            zos.write(header.toByteArray(Charsets.UTF_8))
            synchronized(lock) {
                csvRows.forEach { row ->
                    zos.write((row + "\n").toByteArray(Charsets.UTF_8))
                }
            }
            zos.closeEntry()

            // 2) Device & Codec Probe JSON
            zos.putNextEntry(ZipEntry("codec_probe.json"))
            val probeJson = state.deviceReport?.rawJson ?: "{\"status\":\"UNMEASURED\"}"
            zos.write(probeJson.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 3) Redacted Settings & Phase 3 Decision Snapshot (never includes stream keys)
            zos.putNextEntry(ZipEntry("settings_redacted.json"))
            val settingsJson = JSONObject().apply {
                put("appVersion", state.appVersionLabel)
                put("engineBackend", state.engineBackend.name)
                put("libObsVersion", state.libObsVersion)
                put("vulkanDecision", state.vulkanDecisionLabel)
                put("preset", state.videoConfig.presetId.name)
                put("adaptiveLevel", state.adaptiveQuality.level.name)
                put("adaptiveLocked", state.adaptiveQuality.lockedByUser)
                put("canvas", "${state.videoConfig.canvasWidth}x${state.videoConfig.canvasHeight}")
                put("output", "${state.videoConfig.outputWidth}x${state.videoConfig.outputHeight}")
                put("fps", state.videoConfig.fps)
                put("videoBitrateKbps", state.videoConfig.videoBitrateKbps)
                put("audioBitrateKbps", state.videoConfig.audioBitrateKbps)
                put("screenFitMode", state.videoConfig.screenFitMode.name)
                put("rtmpPreset", state.rtmpServicePreset.name)
                put("rtmpUrlRedacted", KeystoreSecretStore.redactUrlAndKeys(state.rtmpServerUrl, streamKeyToScrub, extraKeys))
                put("hasStreamKeyConfigured", state.hasStreamKeySaved)
                put("dualDestinationEnabled", state.dualDestination.enabled)
                put("secondaryUrlRedacted", KeystoreSecretStore.redactUrlAndKeys(state.dualDestination.serverUrl, streamKeyToScrub, extraKeys))
                put("hasSecondaryStreamKeyConfigured", state.dualDestination.hasKeySaved)
                put("samsungRamTier", state.samsungStatus.ramTierLabel)
                put("floatingOverlayEnabled", state.floatingOverlayEnabled)
            }.toString(2)
            zos.write(settingsJson.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 4) Benchmark Kit Runs (M3.7 §14.4)
            zos.putNextEntry(ZipEntry("benchmark_records.json"))
            val bArr = JSONArray().apply {
                state.benchmarkRecords.forEach { b ->
                    put(JSONObject().apply {
                        put("appName", b.appName)
                        put("preset", b.presetLabel)
                        put("game", b.gameName)
                        put("avgFps", b.gameAvgFps.toDouble())
                        put("onePercentLowFps", b.gameOnePercentLowFps.toDouble())
                        put("streamDroppedPct", b.streamDroppedPct.toDouble())
                        put("peakThermal", b.peakThermalLabel)
                        put("processPssMb", b.processPssMb)
                        put("notes", b.notes)
                        put("recordedAtIso", b.recordedAtIso)
                    })
                }
            }
            zos.write(bArr.toString(2).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 5) Redacted Session Events Log
            zos.putNextEntry(ZipEntry("session_events.log"))
            val scrubbedEvents = state.eventLog.joinToString("\n") { line ->
                KeystoreSecretStore.redactUrlAndKeys(line, streamKeyToScrub, extraKeys)
            }
            zos.write(scrubbedEvents.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return baos.toByteArray()
    }
}
