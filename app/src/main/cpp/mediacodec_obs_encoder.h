#pragma once

#include <stdint.h>
#include <stdbool.h>
#include <android/native_window.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Registers the OBSmobile hardware MediaCodec H.264 encoder module
 * ("mobilelabs_mediacodec_h264") with libobs.
 * Supports zero-copy texture handoff via EGL shared context -> AMediaCodec Surface.
 */
void mobilelabs_register_mediacodec_encoder(void);

/**
 * Updates video bitrate dynamically without restarting the active stream.
 */
bool mobilelabs_mediacodec_update_bitrate(void *encoder_data, int32_t bitrate_kbps);

/**
 * Returns encoder telemetry counters (frames encoded, dropped at bounded queue,
 * active mode: 0 = OPTIMAL_HW_SURFACE, 1 = DEGRADED_HW_BYTEBUFFER, 2 = EMERGENCY_X264).
 */
void mobilelabs_mediacodec_get_stats(
    void *encoder_data,
    uint64_t *out_encoded_frames,
    uint64_t *out_dropped_frames,
    int32_t *out_mode
);

#ifdef __cplusplus
}
#endif
