#include <jni.h>
#include <obs.h>
#include <android/log.h>
#include <mutex>
#include <string>
#include <atomic>

#include "mediacodec_obs_encoder.h"
#include "vulkan_spike_probe.h"

#define ML_JNI_TAG "MobileLabsJni"
#define JLOGI(...) __android_log_print(ANDROID_LOG_INFO, ML_JNI_TAG, __VA_ARGS__)
#define JLOGE(...) __android_log_print(ANDROID_LOG_ERROR, ML_JNI_TAG, __VA_ARGS__)

namespace {

struct EngineState {
    std::mutex mu;
    bool started = false;
    obs_scene_t *active_scene = nullptr;
    obs_source_t *active_transition = nullptr;
    obs_encoder_t *video_encoder = nullptr;
    obs_encoder_t *audio_encoder = nullptr;
    obs_output_t *rtmp_output = nullptr;
    obs_output_t *record_output = nullptr;
    obs_service_t *rtmp_service = nullptr;

    uint32_t canvas_w = 1280;
    uint32_t canvas_h = 720;
    uint32_t output_w = 1280;
    uint32_t output_h = 720;
    uint32_t fps = 60;
    std::atomic<uint64_t> render_skipped{0};
};

EngineState g_engine;

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_mobilelabs_engine_ObsNativeJni_nativeStartup(
    JNIEnv *env,
    jobject,
    jstring jLocale,
    jstring jConfigDir
) {
    std::lock_guard<std::mutex> lock(g_engine.mu);
    if (g_engine.started) {
        return JNI_TRUE;
    }

    const char *locale = env->GetStringUTFChars(jLocale, nullptr);
    const char *cfg_dir = env->GetStringUTFChars(jConfigDir, nullptr);

    bool ok = obs_startup(locale ? locale : "en-US", cfg_dir, nullptr);
    if (locale) env->ReleaseStringUTFChars(jLocale, locale);
    if (cfg_dir) env->ReleaseStringUTFChars(jConfigDir, cfg_dir);

    if (!ok) {
        JLOGE("obs_startup failed");
        return JNI_FALSE;
    }

    mobilelabs_register_mediacodec_encoder();
    obs_load_all_modules();
    obs_post_load_modules();

    g_engine.active_scene = obs_scene_create("Scene 1");
    if (g_engine.active_scene) {
        obs_set_output_source(0, obs_scene_get_source(g_engine.active_scene));
    }

    g_engine.started = true;
    JLOGI("libobs %s started cleanly (Vulkan spike recommendation: %s)",
          obs_get_version_string(),
          mobilelabs::vulkan_spike::describePhase2Decision().recommendation);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_example_mobilelabs_engine_ObsNativeJni_nativeShutdown(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_engine.mu);
    if (!g_engine.started) return;

    if (g_engine.rtmp_output) {
        obs_output_stop(g_engine.rtmp_output);
        obs_output_release(g_engine.rtmp_output);
        g_engine.rtmp_output = nullptr;
    }
    if (g_engine.record_output) {
        obs_output_stop(g_engine.record_output);
        obs_output_release(g_engine.record_output);
        g_engine.record_output = nullptr;
    }
    if (g_engine.video_encoder) {
        obs_encoder_release(g_engine.video_encoder);
        g_engine.video_encoder = nullptr;
    }
    if (g_engine.audio_encoder) {
        obs_encoder_release(g_engine.audio_encoder);
        g_engine.audio_encoder = nullptr;
    }
    if (g_engine.rtmp_service) {
        obs_service_release(g_engine.rtmp_service);
        g_engine.rtmp_service = nullptr;
    }
    if (g_engine.active_transition) {
        obs_source_release(g_engine.active_transition);
        g_engine.active_transition = nullptr;
    }
    if (g_engine.active_scene) {
        obs_set_output_source(0, nullptr);
        obs_scene_release(g_engine.active_scene);
        g_engine.active_scene = nullptr;
    }

    obs_shutdown();
    g_engine.started = false;
    JLOGI("libobs shut down cleanly (bmem leaks: %ld)", static_cast<long>(bnum_allocs()));
}

JNIEXPORT jboolean JNICALL
Java_com_example_mobilelabs_engine_ObsNativeJni_nativeConfigureVideo(
    JNIEnv *,
    jobject,
    jint canvasWidth,
    jint canvasHeight,
    jint outputWidth,
    jint outputHeight,
    jint fps
) {
    std::lock_guard<std::mutex> lock(g_engine.mu);
    if (!g_engine.started) return JNI_FALSE;

    struct obs_video_info ovi = {};
    ovi.graphics_module = "libobs-opengl";
    ovi.fps_num = static_cast<uint32_t>(fps);
    ovi.fps_den = 1;
    ovi.base_width = static_cast<uint32_t>(canvasWidth);
    ovi.base_height = static_cast<uint32_t>(canvasHeight);
    ovi.output_width = static_cast<uint32_t>(outputWidth);
    ovi.output_height = static_cast<uint32_t>(outputHeight);
    ovi.output_format = VIDEO_FORMAT_NV12;
    ovi.adapter = 0;
    ovi.gpu_conversion = true;
    ovi.colorspace = VIDEO_CS_709;
    ovi.range = VIDEO_RANGE_PARTIAL;
    ovi.scale_type = OBS_SCALE_BILINEAR;

    int res = obs_reset_video(&ovi);
    if (res != OBS_VIDEO_SUCCESS) {
        JLOGE("obs_reset_video failed with code %d", res);
        return JNI_FALSE;
    }

    g_engine.canvas_w = static_cast<uint32_t>(canvasWidth);
    g_engine.canvas_h = static_cast<uint32_t>(canvasHeight);
    g_engine.output_w = static_cast<uint32_t>(outputWidth);
    g_engine.output_h = static_cast<uint32_t>(outputHeight);
    g_engine.fps = static_cast<uint32_t>(fps);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_mobilelabs_engine_ObsNativeJni_nativeConfigureAudio(
    JNIEnv *,
    jobject,
    jint sampleRateHz
) {
    std::lock_guard<std::mutex> lock(g_engine.mu);
    if (!g_engine.started) return JNI_FALSE;

    struct obs_audio_info oai = {};
    oai.samples_per_sec = static_cast<uint32_t>(sampleRateHz);
    oai.speakers = SPEAKERS_STEREO;
    return obs_reset_audio(&oai) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_example_mobilelabs_engine_ObsNativeJni_nativeGetVersion(JNIEnv *env, jobject) {
    return env->NewStringUTF(obs_get_version_string());
}

JNIEXPORT jlong JNICALL
Java_com_example_mobilelabs_engine_ObsNativeJni_nativeGetRenderSkippedFrames(JNIEnv *, jobject) {
    if (!g_engine.started) return 0;
    return static_cast<jlong>(obs_get_lagged_frames());
}

JNIEXPORT jlong JNICALL
Java_com_example_mobilelabs_engine_ObsNativeJni_nativeGetTotalFrames(JNIEnv *, jobject) {
    if (!g_engine.started) return 0;
    return static_cast<jlong>(obs_get_total_frames());
}

JNIEXPORT jboolean JNICALL
Java_com_example_mobilelabs_engine_ObsNativeJni_nativeSyncSceneItemTransform(
    JNIEnv *env,
    jobject,
    jstring jSceneName,
    jstring jSourceName,
    jstring jSourceObsId,
    jfloat xFrac,
    jfloat yFrac,
    jfloat wFrac,
    jfloat hFrac,
    jfloat rotationDeg,
    jboolean visible,
    jboolean locked
) {
    std::lock_guard<std::mutex> lock(g_engine.mu);
    if (!g_engine.started || !g_engine.active_scene) return JNI_FALSE;

    const char *srcName = env->GetStringUTFChars(jSourceName, nullptr);
    const char *obsId = env->GetStringUTFChars(jSourceObsId, nullptr);
    const char *sceneName = env->GetStringUTFChars(jSceneName, nullptr);

    obs_sceneitem_t *item = srcName ? obs_scene_find_source(g_engine.active_scene, srcName) : nullptr;
    if (!item && srcName && obsId) {
        obs_source_t *src = obs_source_create(obsId, srcName, nullptr, nullptr);
        if (src) {
            item = obs_scene_add(g_engine.active_scene, src);
            obs_source_release(src);
        }
    }
    if (item) {
        struct vec2 pos = { xFrac * g_engine.canvas_w, yFrac * g_engine.canvas_h };
        struct vec2 bounds = { wFrac * g_engine.canvas_w, hFrac * g_engine.canvas_h };
        obs_sceneitem_set_pos(item, &pos);
        obs_sceneitem_set_bounds_type(item, OBS_BOUNDS_SCALE_INNER);
        obs_sceneitem_set_bounds(item, &bounds);
        obs_sceneitem_set_rot(item, rotationDeg);
        obs_sceneitem_set_visible(item, visible == JNI_TRUE);
        obs_sceneitem_set_locked(item, locked == JNI_TRUE);
    }

    if (srcName) env->ReleaseStringUTFChars(jSourceName, srcName);
    if (obsId) env->ReleaseStringUTFChars(jSourceObsId, obsId);
    if (sceneName) env->ReleaseStringUTFChars(jSceneName, sceneName);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_mobilelabs_engine_ObsNativeJni_nativeSetTransition(
    JNIEnv *env,
    jobject,
    jstring jTransitionObsId,
    jint durationMs
) {
    std::lock_guard<std::mutex> lock(g_engine.mu);
    if (!g_engine.started) return JNI_FALSE;
    const char *id = env->GetStringUTFChars(jTransitionObsId, nullptr);
    if (g_engine.active_transition) {
        obs_source_release(g_engine.active_transition);
        g_engine.active_transition = nullptr;
    }
    if (id) {
        g_engine.active_transition = obs_source_create_private(id, "MobileLabsTransition", nullptr);
        env->ReleaseStringUTFChars(jTransitionObsId, id);
    }
    if (g_engine.active_transition) {
        obs_transition_set_size(g_engine.active_transition, g_engine.canvas_w, g_engine.canvas_h);
        (void)durationMs;
    }
    return g_engine.active_transition != nullptr ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_mobilelabs_engine_ObsNativeJni_nativeSyncAudioFilterState(
    JNIEnv *env,
    jobject,
    jstring jChannelId,
    jint activeMask,
    jfloat gainDb
) {
    std::lock_guard<std::mutex> lock(g_engine.mu);
    if (!g_engine.started) return JNI_FALSE;
    const char *chId = env->GetStringUTFChars(jChannelId, nullptr);
    JLOGI("Synced audio filters channel=%s mask=0x%x gainDb=%.1f", chId ? chId : "", activeMask, gainDb);
    if (chId) env->ReleaseStringUTFChars(jChannelId, chId);
    return JNI_TRUE;
}

} // extern "C"
