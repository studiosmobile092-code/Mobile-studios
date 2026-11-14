#pragma once

#include <cstdint>

/**
 * M2.7 Gate V2: Time-Boxed Vulkan AHardwareBuffer Compositor Spike Probe.
 *
 * This header defines the standalone experimental probe contract used to evaluate
 * VK_ANDROID_external_memory_android_hardware_buffer vs the production GLES3/EGL
 * zero-copy compositor on Adreno 619 (Galaxy Tab A9+, Android 16).
 *
 * Decision Gate V2 Outcome: Production remains on GLES3/EGL in Phase 2 because:
 * 1. GLES3 zero-copy OES textures already run with sub-millisecond compositor pass times
 *    and broad OEM MediaCodec surface compatibility.
 * 2. Physical Tab A9+ Vulkan vs GLES3 thermal/FPS delta is UNMEASURED until APK runs
 *    on physical hardware.
 * 3. Any Vulkan switch requires proven >10% GPU speedup without stability regression.
 */
namespace mobilelabs::vulkan_spike {

struct VulkanSpikeProbeResult {
    bool vulkanAvailable = false;
    bool ahbExtensionSupported = false;
    uint32_t apiVersion = 0;
    uint32_t driverVersion = 0;
    const char* deviceName = "UNMEASURED";
    const char* recommendation = "KEEP_GLES3_PRODUCTION";
};

inline VulkanSpikeProbeResult describePhase2Decision() {
    return VulkanSpikeProbeResult{};
}

} // namespace mobilelabs::vulkan_spike
