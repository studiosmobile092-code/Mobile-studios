package com.example.mobilelabs.probes

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.example.mobilelabs.model.CodecProbeEntry
import com.example.mobilelabs.model.DeviceAndCodecReport
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Step 0 Device & Hardware Codec Capability Probe (§6.5, M1.0).
 * Enumerates actual MediaCodecList encoders, Surface-input support, bitrate modes,
 * resolution/fps capabilities, and tests concurrent hardware encoder session limits.
 */
class DeviceAndCodecProbe(private val context: Context) {

    fun runProbe(testConcurrency: Boolean = true): DeviceAndCodecReport {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val displayInfo = DisplayGeometryCompat.readDefaultDisplay(context)
        val widthPx = displayInfo.widthPx
        val heightPx = displayInfo.heightPx
        val refreshHz = displayInfo.refreshHz
        val supportedRates = displayInfo.supportedRefreshRates

        val statFs = StatFs(Environment.getDataDirectory().path)
        val availStorageMb = (statFs.availableBlocksLong * statFs.blockSizeLong) / (1024L * 1024L)

        val targetMimes = setOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)
        val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { it.isEncoder }
            .flatMap { info ->
                info.supportedTypes
                    .filter { it in targetMimes }
                    .map { mime -> inspectCodec(info, mime, testConcurrency) }
            }

        val maxAvcHw = codecs
            .filter { it.mimeType == MediaFormat.MIMETYPE_VIDEO_AVC && it.isHardwareAccelerated }
            .maxOfOrNull { it.maxSupportedInstances }
            ?: 0

        val isoTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

        val json = buildJsonReport(
            isoTime = isoTime,
            totalRamMb = memInfo.totalMem / (1024L * 1024L),
            availStorageMb = availStorageMb,
            widthPx = widthPx,
            heightPx = heightPx,
            refreshHz = refreshHz,
            supportedRates = supportedRates,
            maxAvcHw = maxAvcHw,
            encoders = codecs
        )

        return DeviceAndCodecReport(
            generatedAtIso = isoTime,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            androidVersion = Build.VERSION.RELEASE ?: "unknown",
            sdkInt = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH ?: "unknown",
            buildId = Build.DISPLAY ?: Build.ID,
            abis = Build.SUPPORTED_ABIS.joinToString(", "),
            totalRamMb = memInfo.totalMem / (1024L * 1024L),
            availableStorageMb = availStorageMb,
            displayWidthPx = widthPx,
            displayHeightPx = heightPx,
            displayRefreshHz = refreshHz,
            supportedRefreshRates = supportedRates,
            thermalApiSupported = true,
            thermalHeadroomSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
            performanceHintSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            maxConcurrentAvcHardwareInstances = maxAvcHw,
            encoders = codecs,
            rawJson = json
        )
    }

    private fun inspectCodec(
        info: MediaCodecInfo,
        mime: String,
        testConcurrency: Boolean
    ): CodecProbeEntry {
        val caps = info.getCapabilitiesForType(mime)
        val vCaps = caps.videoCapabilities
        val eCaps = caps.encoderCapabilities

        val isHw = info.isHardwareAccelerated

        val supportsSurface = caps.colorFormats.contains(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )

        val supports720p30 = vCaps?.areSizeAndRateSupported(1280, 720, 30.0) == true
        val supports720p60 = vCaps?.areSizeAndRateSupported(1280, 720, 60.0) == true
        val supports1080p30 = vCaps?.areSizeAndRateSupported(1920, 1080, 30.0) == true
        val supports1080p60 = vCaps?.areSizeAndRateSupported(1920, 1080, 60.0) == true

        val bitrateRange = vCaps?.bitrateRange?.let {
            "${it.lower / 1000}-${it.upper / 1000} kbps"
        } ?: "unknown"

        val cbr = eCaps?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true
        val vbr = eCaps?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) == true
        val cq = eCaps?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ) == true

        val lowLatency = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)

        val concurrentMax = if (testConcurrency && isHw && mime == MediaFormat.MIMETYPE_VIDEO_AVC) {
            probeConcurrentEncoderSessions(info.name, mime)
        } else {
            caps.maxSupportedInstances
        }

        return CodecProbeEntry(
            name = info.name,
            mimeType = mime,
            isHardwareAccelerated = isHw,
            supportsSurfaceInput = supportsSurface,
            supports720p30 = supports720p30,
            supports720p60 = supports720p60,
            supports1080p30 = supports1080p30,
            supports1080p60 = supports1080p60,
            bitrateRangeKbps = bitrateRange,
            supportsCbr = cbr,
            supportsVbr = vbr,
            supportsCq = cq,
            supportsLowLatency = lowLatency,
            maxSupportedInstances = concurrentMax
        )
    }

    /**
     * Opens 1, 2, then 3 concurrent configured 720p30 encoders and immediately releases them
     * to measure actual silicon session limits rather than trusting static metadata (§6.5).
     */
    private fun probeConcurrentEncoderSessions(codecName: String, mime: String): Int {
        val opened = mutableListOf<MediaCodec>()
        var maxSucceeded = 0
        try {
            for (count in 1..3) {
                val codec = MediaCodec.createByCodecName(codecName)
                val fmt = MediaFormat.createVideoFormat(mime, 1280, 720).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                }
                codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                opened += codec
                maxSucceeded = count
            }
        } catch (_: Throwable) {
            // Expected when device silicon caps concurrent sessions
        } finally {
            opened.forEach { codec ->
                runCatching { codec.release() }
            }
        }
        return maxSucceeded
    }

    private fun buildJsonReport(
        isoTime: String,
        totalRamMb: Long,
        availStorageMb: Long,
        widthPx: Int,
        heightPx: Int,
        refreshHz: Float,
        supportedRates: List<Float>,
        maxAvcHw: Int,
        encoders: List<CodecProbeEntry>
    ): String {
        val root = JSONObject().apply {
            put("generatedAt", isoTime)
            put("device", JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("device", Build.DEVICE)
                put("androidVersion", Build.VERSION.RELEASE)
                put("sdkInt", Build.VERSION.SDK_INT)
                put("securityPatch", Build.VERSION.SECURITY_PATCH)
                put("buildId", Build.DISPLAY)
                put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                put("totalRamMb", totalRamMb)
                put("availableStorageMb", availStorageMb)
                put("displayPx", "${widthPx}x${heightPx}")
                put("refreshHz", refreshHz.toDouble())
                put("supportedRefreshHz", JSONArray(supportedRates.map { it.toDouble() }))
                put("maxConcurrentAvcHwVerified", maxAvcHw)
            })
            put("encoders", JSONArray().apply {
                encoders.forEach { e ->
                    put(JSONObject().apply {
                        put("name", e.name)
                        put("mime", e.mimeType)
                        put("hardware", e.isHardwareAccelerated)
                        put("surfaceInput", e.supportsSurfaceInput)
                        put("720p30", e.supports720p30)
                        put("720p60", e.supports720p60)
                        put("1080p30", e.supports1080p30)
                        put("1080p60", e.supports1080p60)
                        put("bitrateRange", e.bitrateRangeKbps)
                        put("cbr", e.supportsCbr)
                        put("vbr", e.supportsVbr)
                        put("cq", e.supportsCq)
                        put("lowLatency", e.supportsLowLatency)
                        put("maxConcurrentInstances", e.maxSupportedInstances)
                    })
                }
            })
        }
        return root.toString(2)
    }
}
