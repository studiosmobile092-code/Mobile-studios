package com.example.mobilelabs.model

import androidx.compose.runtime.Immutable

/**
 * Audio & Video filter configurations (M2.3).
 * All filters are OFF by default (Spec §12.2 M2.3 Gate) and execute strictly on the
 * audio DSP path or GLES3 GPU fragment shader (zero CPU pixel copies).
 */
@Immutable
data class AudioFilterConfig(
    val gainEnabled: Boolean = false,
    val gainDb: Float = 0f,
    val noiseSuppressionEnabled: Boolean = false,
    val noiseGateEnabled: Boolean = false,
    val gateCloseDb: Float = -45f,
    val gateOpenDb: Float = -38f,
    val compressorEnabled: Boolean = false,
    val compressorThresholdDb: Float = -18f,
    val compressorRatio: Float = 4f,
    val compressorMakeupDb: Float = 3f,
    val limiterEnabled: Boolean = false,
    val limiterCeilingDb: Float = -1.5f,
    val autoDuckingEnabled: Boolean = false,
    val duckAmountDb: Float = -10f,
    val pushToTalkEnabled: Boolean = false
) {
    fun anyActive(): Boolean =
        gainEnabled || noiseSuppressionEnabled || noiseGateEnabled ||
            compressorEnabled || limiterEnabled || autoDuckingEnabled || pushToTalkEnabled

    fun activeSummary(): String {
        val parts = buildList {
            if (gainEnabled) add("Gain")
            if (noiseSuppressionEnabled) add("NS")
            if (noiseGateEnabled) add("Gate")
            if (compressorEnabled) add("Comp")
            if (limiterEnabled) add("Lim")
            if (autoDuckingEnabled) add("Duck")
            if (pushToTalkEnabled) add("PTT")
        }
        return if (parts.isEmpty()) "Filters: Off" else parts.joinToString(" · ")
    }
}

enum class LutPreset(val label: String) {
    NONE("None"),
    VIVID_GAMING("Vivid Gaming"),
    TEAL_ORANGE("Teal & Orange"),
    CRISP_MONO("Crisp Mono"),
    WARM_CINEMA("Warm Cinema")
}

enum class ScaleFilterType(val label: String) {
    BILINEAR("Bilinear (GPU)"),
    BICUBIC("Sharp (GPU)"),
    POINT("Pixel / Nearest")
}

@Immutable
data class VideoFilterConfig(
    val colorCorrectionEnabled: Boolean = false,
    val brightness: Float = 0f, // -0.5f..0.5f
    val contrast: Float = 1f,   // 0.2f..2.0f
    val saturation: Float = 1f, // 0f..2.0f
    val gamma: Float = 1f,      // 0.5f..2.0f
    val opacity: Float = 1f,    // 0.1f..1.0f
    val cropPadEnabled: Boolean = false,
    val scaleAspectEnabled: Boolean = false,
    val scaleFilter: ScaleFilterType = ScaleFilterType.BILINEAR,
    val chromaKeyEnabled: Boolean = false,
    val chromaKeyColorArgb: Long = 0xFF00B140L,
    val chromaSimilarity: Float = 0.28f,
    val chromaSmoothness: Float = 0.08f,
    val chromaSpill: Float = 0.15f,
    val sharpenEnabled: Boolean = false,
    val sharpenAmount: Float = 0.35f,
    val lutEnabled: Boolean = false,
    val lutPreset: LutPreset = LutPreset.NONE,
    val lutIntensity: Float = 0.7f
) {
    fun anyActive(): Boolean =
        colorCorrectionEnabled || cropPadEnabled || scaleAspectEnabled ||
            chromaKeyEnabled || sharpenEnabled || (lutEnabled && lutPreset != LutPreset.NONE)

    fun activeTags(): List<String> = buildList {
        if (colorCorrectionEnabled) add("Color")
        if (cropPadEnabled) add("Crop/Pad")
        if (scaleAspectEnabled) add(scaleFilter.label)
        if (chromaKeyEnabled) add("ChromaKey")
        if (sharpenEnabled) add("Sharpen")
        if (lutEnabled && lutPreset != LutPreset.NONE) add("LUT:${lutPreset.label}")
    }
}

enum class RecordingFormat(val label: String, val extension: String) {
    FMP4("MP4 (Crash-Safe Checkpoint)", "mp4"),
    SEGMENTED_MP4("Segmented MP4 (10m Split)", "mp4")
}

enum class DownscaleFilter(val label: String) {
    BILINEAR("Bilinear"),
    BICUBIC("Bicubic (16-sample)"),
    LANCZOS("Lanczos (High)")
}

enum class DockPanelId(val title: String) {
    SCENES("Scenes"),
    SOURCES("Sources"),
    AUDIO_MIXER("Audio Mixer"),
    TRANSITIONS("Scene Transitions"),
    CONTROLS("Controls")
}
