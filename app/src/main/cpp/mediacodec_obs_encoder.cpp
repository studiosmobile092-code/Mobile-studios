#include "mediacodec_obs_encoder.h"

#include <obs-module.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <android/log.h>

#include <atomic>
#include <cstring>
#include <deque>
#include <mutex>
#include <thread>
#include <vector>

#define ML_TAG "OBSmobileObsEnc"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, ML_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, ML_TAG, __VA_ARGS__)

namespace {

constexpr int32_t kColorFormatSurface = 0x7F000789;
constexpr int32_t kBitrateModeCbr = 2;
constexpr int32_t kBitrateModeVbr = 1;
constexpr size_t kMaxOutputQueue = 8;

constexpr const char *kVertexShader = R"glsl(#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aTex;
out vec2 vTex;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vTex = aTex;
}
)glsl";

constexpr const char *kFragmentShader = R"glsl(#version 300 es
precision mediump float;
in vec2 vTex;
uniform sampler2D uTex;
out vec4 fragColor;
void main() {
    fragColor = texture(uTex, vTex);
}
)glsl";

struct EncodedPacket {
    std::vector<uint8_t> data;
    int64_t pts_us = 0;
    bool keyframe = false;
};

struct MobileLabsMediaCodecEncoder {
    obs_encoder_t *encoder = nullptr;
    AMediaCodec *codec = nullptr;
    ANativeWindow *input_surface = nullptr;

    EGLDisplay egl_display = EGL_NO_DISPLAY;
    EGLContext egl_context = EGL_NO_CONTEXT;
    EGLConfig egl_config = nullptr;
    EGLSurface egl_surface = EGL_NO_SURFACE;
    PFNEGLPRESENTATIONTIMEANDROIDPROC eglPresentationTimeANDROID = nullptr;

    GLuint blit_program = 0;
    GLint blit_sampler_loc = -1;
    GLuint quad_vbo = 0;
    bool gl_initialized = false;

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
    std::deque<EncodedPacket> output_queue;
    std::mutex packet_mutex;
    std::thread drain_thread;

    uint8_t *latest_packet_buf = nullptr;
    size_t latest_packet_cap = 0;
};

GLuint compile_shader(GLenum type, const char *src) {
    GLuint shader = glCreateShader(type);
    if (!shader) return 0;
    glShaderSource(shader, 1, &src, nullptr);
    glCompileShader(shader);
    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        char log_buf[256] = {};
        glGetShaderInfoLog(shader, sizeof(log_buf), nullptr, log_buf);
        LOGE("Shader compile error: %s", log_buf);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

bool ensure_egl_surface_and_gl(MobileLabsMediaCodecEncoder *ctx) {
    if (ctx->gl_initialized && ctx->egl_surface != EGL_NO_SURFACE) {
        return true;
    }
    EGLDisplay cur_disp = eglGetCurrentDisplay();
    EGLContext cur_ctx = eglGetCurrentContext();
    if (cur_disp == EGL_NO_DISPLAY || cur_ctx == EGL_NO_CONTEXT || !ctx->input_surface) {
        return false;
    }

    EGLint config_id = 0;
    eglQueryContext(cur_disp, cur_ctx, EGL_CONFIG_ID, &config_id);
    EGLint cfg_attrs[] = {EGL_CONFIG_ID, config_id, EGL_NONE};
    EGLConfig chosen = nullptr;
    EGLint num_cfg = 0;
    if (!eglChooseConfig(cur_disp, cfg_attrs, &chosen, 1, &num_cfg) || num_cfg < 1) {
        EGLint fallback_attrs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_NONE
        };
        if (!eglChooseConfig(cur_disp, fallback_attrs, &chosen, 1, &num_cfg) || num_cfg < 1) {
            LOGE("eglChooseConfig failed for MediaCodec input surface");
            return false;
        }
    }

    EGLSurface surf = eglCreateWindowSurface(cur_disp, chosen, ctx->input_surface, nullptr);
    if (surf == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface failed: 0x%x", eglGetError());
        return false;
    }

    GLuint vs = compile_shader(GL_VERTEX_SHADER, kVertexShader);
    GLuint fs = compile_shader(GL_FRAGMENT_SHADER, kFragmentShader);
    GLuint prog = 0;
    if (vs && fs) {
        prog = glCreateProgram();
        glAttachShader(prog, vs);
        glAttachShader(prog, fs);
        glLinkProgram(prog);
        GLint linked = 0;
        glGetProgramiv(prog, GL_LINK_STATUS, &linked);
        if (!linked) {
            glDeleteProgram(prog);
            prog = 0;
        }
    }
    if (vs) glDeleteShader(vs);
    if (fs) glDeleteShader(fs);
    if (!prog) {
        eglDestroySurface(cur_disp, surf);
        return false;
    }

    static constexpr GLfloat kQuadVerts[] = {
        -1.0f, -1.0f, 0.0f, 0.0f,
         1.0f, -1.0f, 1.0f, 0.0f,
        -1.0f,  1.0f, 0.0f, 1.0f,
         1.0f,  1.0f, 1.0f, 1.0f,
    };
    GLuint vbo = 0;
    glGenBuffers(1, &vbo);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(kQuadVerts), kQuadVerts, GL_STATIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    ctx->egl_display = cur_disp;
    ctx->egl_context = cur_ctx;
    ctx->egl_config = chosen;
    ctx->egl_surface = surf;
    ctx->blit_program = prog;
    ctx->blit_sampler_loc = glGetUniformLocation(prog, "uTex");
    ctx->quad_vbo = vbo;
    ctx->gl_initialized = true;
    return true;
}

const char *ml_enc_get_name(void *) {
    return "OBSmobile Hardware MediaCodec H.264 (Zero-Copy)";
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
                    EncodedPacket pkt;
                    pkt.data.assign(buf + info.offset, buf + info.offset + info.size);
                    pkt.pts_us = info.presentationTimeUs;
                    pkt.keyframe = (info.flags & AMEDIACODEC_BUFFER_FLAG_KEY_FRAME) != 0;

                    std::lock_guard<std::mutex> lock(ctx->packet_mutex);
                    if (ctx->output_queue.size() >= kMaxOutputQueue) {
                        ctx->output_queue.pop_front();
                        ctx->dropped_frames.fetch_add(1, std::memory_order_relaxed);
                    }
                    ctx->output_queue.push_back(std::move(pkt));
                    ctx->encoded_frames.fetch_add(1, std::memory_order_relaxed);
                }
            }
            AMediaCodec_releaseOutputBuffer(ctx->codec, static_cast<size_t>(idx), false);
            int prev = ctx->queued_frames.load(std::memory_order_relaxed);
            if (prev > 0) {
                ctx->queued_frames.fetch_sub(1, std::memory_order_relaxed);
            }
        } else if (idx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            AMediaFormat *out_fmt = AMediaCodec_getOutputFormat(ctx->codec);
            if (out_fmt) {
                void *csd0 = nullptr;
                size_t csd0_len = 0;
                void *csd1 = nullptr;
                size_t csd1_len = 0;
                std::vector<uint8_t> combined;
                if (AMediaFormat_getBuffer(out_fmt, "csd-0", &csd0, &csd0_len) && csd0 && csd0_len > 0) {
                    const auto *p0 = static_cast<const uint8_t *>(csd0);
                    combined.insert(combined.end(), p0, p0 + csd0_len);
                }
                if (AMediaFormat_getBuffer(out_fmt, "csd-1", &csd1, &csd1_len) && csd1 && csd1_len > 0) {
                    const auto *p1 = static_cast<const uint8_t *>(csd1);
                    combined.insert(combined.end(), p1, p1 + csd1_len);
                }
                if (!combined.empty()) {
                    std::lock_guard<std::mutex> lock(ctx->packet_mutex);
                    ctx->extra_data = std::move(combined);
                }
                AMediaFormat_delete(out_fmt);
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
    if (ctx->egl_display != EGL_NO_DISPLAY && ctx->egl_surface != EGL_NO_SURFACE) {
        eglDestroySurface(ctx->egl_display, ctx->egl_surface);
        ctx->egl_surface = EGL_NO_SURFACE;
    }
    if (ctx->egl_display != EGL_NO_DISPLAY && eglGetCurrentContext() == ctx->egl_context) {
        if (ctx->blit_program) glDeleteProgram(ctx->blit_program);
        if (ctx->quad_vbo) glDeleteBuffers(1, &ctx->quad_vbo);
    }
    if (ctx->input_surface) {
        ANativeWindow_release(ctx->input_surface);
    }
    delete[] ctx->latest_packet_buf;
    delete ctx;
}

bool ml_enc_update(void *data, obs_data_t *settings) {
    auto *ctx = static_cast<MobileLabsMediaCodecEncoder *>(data);
    if (!ctx || !ctx->codec || !settings) return false;
    int32_t next_kbps = static_cast<int32_t>(obs_data_get_int(settings, "bitrate"));
    if (next_kbps > 0 && next_kbps != ctx->bitrate_kbps) {
        ctx->bitrate_kbps = next_kbps;
        return mobilelabs_mediacodec_update_bitrate(ctx, next_kbps);
    }
    return true;
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
    if (ctx->queued_frames.load(std::memory_order_relaxed) >= static_cast<int32_t>(kMaxOutputQueue)) {
        ctx->dropped_frames.fetch_add(1, std::memory_order_relaxed);
    } else if (ensure_egl_surface_and_gl(ctx)) {
        EGLDisplay prev_disp = eglGetCurrentDisplay();
        EGLSurface prev_draw = eglGetCurrentSurface(EGL_DRAW);
        EGLSurface prev_read = eglGetCurrentSurface(EGL_READ);
        EGLContext prev_ctx = eglGetCurrentContext();

        if (eglMakeCurrent(ctx->egl_display, ctx->egl_surface, ctx->egl_surface, ctx->egl_context)) {
            glViewport(0, 0, static_cast<GLsizei>(ctx->width), static_cast<GLsizei>(ctx->height));
            glUseProgram(ctx->blit_program);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, handle);
            if (ctx->blit_sampler_loc >= 0) {
                glUniform1i(ctx->blit_sampler_loc, 0);
            }
            glBindBuffer(GL_ARRAY_BUFFER, ctx->quad_vbo);
            glEnableVertexAttribArray(0);
            glEnableVertexAttribArray(1);
            glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), reinterpret_cast<const void *>(0));
            glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), reinterpret_cast<const void *>(2 * sizeof(GLfloat)));
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            glBindBuffer(GL_ARRAY_BUFFER, 0);

            if (ctx->eglPresentationTimeANDROID) {
                const int64_t fps_den = ctx->fps_den > 0 ? static_cast<int64_t>(ctx->fps_den) : 1;
                const int64_t fps_num = ctx->fps_num > 0 ? static_cast<int64_t>(ctx->fps_num) : 60;
                const int64_t pts_ns = (pts * 1000000000LL * fps_den) / fps_num;
                ctx->eglPresentationTimeANDROID(ctx->egl_display, ctx->egl_surface, pts_ns);
            }
            eglSwapBuffers(ctx->egl_display, ctx->egl_surface);
            ctx->queued_frames.fetch_add(1, std::memory_order_relaxed);
            eglMakeCurrent(prev_disp, prev_draw, prev_read, prev_ctx);
        }
    }

    // Pop next encoded packet emitted by drain_output_loop and hand it to libobs muxer/RTMP output.
    std::lock_guard<std::mutex> lock(ctx->packet_mutex);
    if (!ctx->output_queue.empty() && packet) {
        EncodedPacket front = std::move(ctx->output_queue.front());
        ctx->output_queue.pop_front();
        if (front.data.size() > ctx->latest_packet_cap) {
            delete[] ctx->latest_packet_buf;
            ctx->latest_packet_cap = front.data.size();
            ctx->latest_packet_buf = new uint8_t[ctx->latest_packet_cap];
        }
        std::memcpy(ctx->latest_packet_buf, front.data.data(), front.data.size());
        const int64_t fps_num = ctx->fps_num > 0 ? static_cast<int64_t>(ctx->fps_num) : 60;
        const int64_t fps_den = ctx->fps_den > 0 ? static_cast<int64_t>(ctx->fps_den) : 1;
        const int64_t obs_pts = (front.pts_us * fps_num) / (1000000LL * fps_den);
        packet->data = ctx->latest_packet_buf;
        packet->size = front.data.size();
        packet->pts = obs_pts;
        packet->dts = obs_pts;
        packet->type = OBS_ENCODER_VIDEO;
        packet->keyframe = front.keyframe;
        *received_packet = true;
    }

    return true;
}

#if defined(LIBOBS_API_VER) && LIBOBS_API_VER >= MAKE_SEMANTIC_VERSION(30, 0, 0)
bool ml_enc_encode_texture2(
    void *data,
    struct encoder_texture *texture,
    int64_t pts,
    uint64_t lock_key,
    uint64_t *next_key,
    struct encoder_packet *packet,
    bool *received_packet
) {
    uint32_t handle = texture ? texture->tex[0] : 0;
    return ml_enc_encode_texture(data, handle, pts, lock_key, next_key, packet, received_packet);
}
#endif

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
    info.update = ml_enc_update;
    info.get_defaults = ml_enc_get_defaults;
    info.get_extra_data = ml_enc_get_extra_data;
#if defined(LIBOBS_API_VER) && LIBOBS_API_VER >= MAKE_SEMANTIC_VERSION(30, 0, 0)
    info.encode_texture2 = ml_enc_encode_texture2;
#else
    info.encode_texture = ml_enc_encode_texture;
#endif
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
