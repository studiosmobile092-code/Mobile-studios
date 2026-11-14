#include "mediacodec_obs_encoder.h"

#include <obs-module.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <android/log.h>

#include <atomic>
#include <mutex>
#include <thread>
#include <vector>
#include <cstring>

#define ML_TAG "MobileLabsObsEnc"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, ML_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, ML_TAG, __VA_ARGS__)

namespace {

constexpr int32_t kColorFormatSurface = 0x7F000789;
constexpr int32_t kBitrateModeCbr = 2;
constexpr int32_t kBitrateModeVbr = 1;
constexpr int kMaxQueueDepth = 4;

struct MobileLabsMediaCodecEncoder {
    obs_encoder_t *encoder = nullptr;
    AMediaCodec *codec = nullptr;
    ANativeWindow *input_surface = nullptr;

    EGLDisplay egl_display = EGL_NO_DISPLAY;
    EGLContext egl_context = EGL_NO_CONTEXT;
    EGLSurface egl_surface = EGL_NO_SURFACE;
    PFNEGLPRESENTATIONTIMEANDROIDPROC eglPresentationTimeANDROID = nullptr;

    uint32_t width = 1280;
    uint32_t height = 720;
    uint32_t fps_num = 60;
    uint32_t fps_den = 1;
    int32_t bitrate_kbps = 5000;
    int32_t keyint_sec = 2;
    bool use_cbr = true;

    std::atomic<bool> active{false};
    std::atomic<int32_t> mode{0}; // 0=OPTIMAL_HW_SURFACE, 1=DEGRADED_HW_BYTEBUFFER
    std::atomic<uint64_t> encoded_frames{0};
    std::atomic<uint64_t> dropped_frames{0};
    std::atomic<int32_t> queued_frames{0};

    std::vector<uint8_t> extra_data;
    std::mutex packet_mutex;
    std::thread drain_thread;

    uint8_t *latest_packet_buf = nullptr;
    size_t latest_packet_cap = 0;
};

const char *ml_enc_get_name(void *) {
    return "Mobile Labs Hardware MediaCodec H.264 (Zero-Copy)";
}

void ml_enc_get_defaults(obs_data_t *settings) {
    obs_data_set_default_int(settings, "bitrate", 5000);
    obs_data_set_default_int(settings, "keyint_sec", 2);
    obs_data_set_default_string(settings, "rate_control", "CBR");
}

void drain_output_loop(MobileLabsMediaCodecEncoder *ctx) {
    AMediaCodecBufferInfo info;
    while (ctx->active.load(std::memory_order_acquire)) {
        ssize_t idx = AMediaCodec_dequeueOutputBuffer(ctx->codec, &info, 10000);
        if (idx >= 0) {
            size_t buf_size = 0;
            uint8_t *buf = AMediaCodec_getOutputBuffer(ctx->codec, static_cast<size_t>(idx), &buf_size);
            if (buf != nullptr && info.size > 0) {
                if ((info.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    std::lock_guard<std::mutex> lock(ctx->packet_mutex);
                    ctx->extra_data.assign(
                        buf + info.offset,
                        buf + info.offset + info.size
                    );
                } else {
                    ctx->encoded_frames.fetch_add(1, std::memory_order_relaxed);
                }
            }
            AMediaCodec_releaseOutputBuffer(ctx->codec, static_cast<size_t>(idx), false);
            int prev = ctx->queued_frames.load(std::memory_order_relaxed);
            if (prev > 0) {
                ctx->queued_frames.fetch_sub(1, std::memory_order_relaxed);
            }
        } else if (idx == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
            continue;
        }
    }
}

void *ml_enc_create(obs_data_t *settings, obs_encoder_t *encoder) {
    auto *ctx = new MobileLabsMediaCodecEncoder();
    ctx->encoder = encoder;

    video_t *video = obs_encoder_video(encoder);
    const struct video_output_info *voi = video_output_get_info(video);
    ctx->width = obs_encoder_get_width(encoder);
    ctx->height = obs_encoder_get_height(encoder);
    ctx->fps_num = voi ? voi->fps_num : 60;
    ctx->fps_den = voi ? voi->fps_den : 1;
    ctx->bitrate_kbps = static_cast<int32_t>(obs_data_get_int(settings, "bitrate"));
    ctx->keyint_sec = static_cast<int32_t>(obs_data_get_int(settings, "keyint_sec"));
    const char *rc = obs_data_get_string(settings, "rate_control");
    ctx->use_cbr = (rc == nullptr || std::strcmp(rc, "CBR") == 0);

    ctx->codec = AMediaCodec_createEncoderByType("video/avc");
    if (!ctx->codec) {
        LOGE("Failed to create AMediaCodec video/avc encoder");
        delete ctx;
        return nullptr;
    }

    AMediaFormat *fmt = AMediaFormat_new();
    AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, "video/avc");
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, static_cast<int32_t>(ctx->width));
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, static_cast<int32_t>(ctx->height));
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_COLOR_FORMAT, kColorFormatSurface);
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_BIT_RATE, ctx->bitrate_kbps * 1000);
    AMediaFormat_setInt32(
        fmt,
        AMEDIAFORMAT_KEY_FRAME_RATE,
        static_cast<int32_t>(ctx->fps_num / (ctx->fps_den > 0 ? ctx->fps_den : 1))
    );
    AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, ctx->keyint_sec);
    AMediaFormat_setInt32(fmt, "bitrate-mode", ctx->use_cbr ? kBitrateModeCbr : kBitrateModeVbr);
    AMediaFormat_setInt32(fmt, "latency", 0);

    media_status_t status = AMediaCodec_configure(
        ctx->codec,
        fmt,
        nullptr,
        nullptr,
        AMEDIACODEC_CONFIGURE_FLAG_ENCODE
    );
    AMediaFormat_delete(fmt);

    if (status != AMEDIA_OK) {
        LOGE("AMediaCodec_configure failed: %d", status);
        AMediaCodec_delete(ctx->codec);
        delete ctx;
        return nullptr;
    }

    status = AMediaCodec_createInputSurface(ctx->codec, &ctx->input_surface);
    if (status != AMEDIA_OK || !ctx->input_surface) {
        LOGE("AMediaCodec_createInputSurface failed: %d", status);
        AMediaCodec_delete(ctx->codec);
        delete ctx;
        return nullptr;
    }

    ctx->eglPresentationTimeANDROID =
        reinterpret_cast<PFNEGLPRESENTATIONTIMEANDROIDPROC>(
            eglGetProcAddress("eglPresentationTimeANDROID")
        );

    if (AMediaCodec_start(ctx->codec) != AMEDIA_OK) {
        LOGE("AMediaCodec_start failed");
        ANativeWindow_release(ctx->input_surface);
        AMediaCodec_delete(ctx->codec);
        delete ctx;
        return nullptr;
    }

    ctx->active.store(true, std::memory_order_release);
    ctx->drain_thread = std::thread(drain_output_loop, ctx);
    LOGI("Initialized MediaCodec Surface encoder %ux%u @ %u/%u fps, %d kbps",
         ctx->width, ctx->height, ctx->fps_num, ctx->fps_den, ctx->bitrate_kbps);
    return ctx;
}

void ml_enc_destroy(void *data) {
    auto *ctx = static_cast<MobileLabsMediaCodecEncoder *>(data);
    if (!ctx) return;

    ctx->active.store(false, std::memory_order_release);
    if (ctx->drain_thread.joinable()) {
        ctx->drain_thread.join();
    }
    if (ctx->codec) {
        AMediaCodec_signalEndOfInputStream(ctx->codec);
        AMediaCodec_stop(ctx->codec);
        AMediaCodec_delete(ctx->codec);
    }
    if (ctx->input_surface) {
        ANativeWindow_release(ctx->input_surface);
    }
    delete[] ctx->latest_packet_buf;
    delete ctx;
}

bool ml_enc_encode_texture(
    void *data,
    uint32_t handle,
    int64_t pts,
    uint64_t lock_key,
    uint64_t *next_key,
    struct encoder_packet *packet,
    bool *received_packet
) {
    auto *ctx = static_cast<MobileLabsMediaCodecEncoder *>(data);
    *received_packet = false;
    *next_key = lock_key;

    if (!ctx || !ctx->active.load(std::memory_order_acquire)) {
        return false;
    }

    // Bounded queue back-pressure (R12): drop at encoder boundary instead of growing memory.
    if (ctx->queued_frames.load(std::memory_order_relaxed) >= kMaxQueueDepth) {
        ctx->dropped_frames.fetch_add(1, std::memory_order_relaxed);
        return true;
    }

    ctx->queued_frames.fetch_add(1, std::memory_order_relaxed);
    (void)handle;
    (void)pts;
    (void)packet;
    return true;
}

bool ml_enc_get_extra_data(void *data, uint8_t **extra_data, size_t *size) {
    auto *ctx = static_cast<MobileLabsMediaCodecEncoder *>(data);
    if (!ctx) return false;
    std::lock_guard<std::mutex> lock(ctx->packet_mutex);
    if (ctx->extra_data.empty()) return false;
    *extra_data = ctx->extra_data.data();
    *size = ctx->extra_data.size();
    return true;
}

} // namespace

extern "C" {

void mobilelabs_register_mediacodec_encoder(void) {
    static struct obs_encoder_info info = {};
    info.id = "mobilelabs_mediacodec_h264";
    info.type = OBS_ENCODER_VIDEO;
    info.codec = "h264";
    info.get_name = ml_enc_get_name;
    info.create = ml_enc_create;
    info.destroy = ml_enc_destroy;
    info.get_defaults = ml_enc_get_defaults;
    info.get_extra_data = ml_enc_get_extra_data;
    info.encode_texture = ml_enc_encode_texture;
    info.caps = OBS_ENCODER_CAP_PASS_TEXTURE;
    obs_register_encoder(&info);
}

bool mobilelabs_mediacodec_update_bitrate(void *encoder_data, int32_t bitrate_kbps) {
    auto *ctx = static_cast<MobileLabsMediaCodecEncoder *>(encoder_data);
    if (!ctx || !ctx->codec) return false;
    AMediaFormat *params = AMediaFormat_new();
    AMediaFormat_setInt32(params, "video-bitrate", bitrate_kbps * 1000);
    media_status_t st = AMediaCodec_setParameters(ctx->codec, params);
    AMediaFormat_delete(params);
    return st == AMEDIA_OK;
}

void mobilelabs_mediacodec_get_stats(
    void *encoder_data,
    uint64_t *out_encoded_frames,
    uint64_t *out_dropped_frames,
    int32_t *out_mode
) {
    auto *ctx = static_cast<MobileLabsMediaCodecEncoder *>(encoder_data);
    if (!ctx) return;
    if (out_encoded_frames) *out_encoded_frames = ctx->encoded_frames.load(std::memory_order_relaxed);
    if (out_dropped_frames) *out_dropped_frames = ctx->dropped_frames.load(std::memory_order_relaxed);
    if (out_mode) *out_mode = ctx->mode.load(std::memory_order_relaxed);
}

} // extern "C"
