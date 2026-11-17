#include "obs-internal.h"
#include "util/dstr.h"

#include <android/log.h>
#include <sys/system_properties.h>

#define OBS_ANDROID_LOG_TAG "libobs-android"

const char *find_libobs_data_file(const char *file) {
    if (!file) return NULL;
    return NULL;
}

void add_default_module_paths(void) {
    /* Android APK modules live statically inside libmobilelabs_obs.so / app native lib dir. */
}

void log_system_info(void) {
    char model[PROP_VALUE_MAX] = {0};
    char release[PROP_VALUE_MAX] = {0};
    char abi[PROP_VALUE_MAX] = {0};
    __system_property_get("ro.product.model", model);
    __system_property_get("ro.build.version.release", release);
    __system_property_get("ro.product.cpu.abi", abi);
    blog(LOG_INFO, "Platform: Android %s (Model: %s, ABI: %s)", release, model, abi);
    __android_log_print(
        ANDROID_LOG_INFO,
        OBS_ANDROID_LOG_TAG,
        "libobs %s initialized on Android %s (%s, %s)",
        obs_get_version_string(),
        release,
        model,
        abi
    );
}

struct obs_hotkeys_platform {
    int unused;
};

bool obs_hotkeys_platform_init(struct obs_core_hotkeys *hotkeys) {
    if (hotkeys) {
        hotkeys->platform_context = bzalloc(sizeof(struct obs_hotkeys_platform));
    }
    return true;
}

void obs_hotkeys_platform_free(struct obs_core_hotkeys *hotkeys) {
    if (hotkeys && hotkeys->platform_context) {
        bfree(hotkeys->platform_context);
        hotkeys->platform_context = NULL;
    }
}

bool obs_hotkeys_platform_is_pressed(obs_hotkeys_platform_t *context, obs_key_t key) {
    (void)context;
    (void)key;
    return false;
}

void obs_key_to_name(obs_key_t key, struct dstr *dstr) {
    (void)key;
    if (dstr) dstr_copy(dstr, "");
}

obs_key_t obs_key_from_name(const char *name) {
    (void)name;
    return OBS_KEY_NONE;
}

int obs_key_to_virtual_key(obs_key_t key) {
    return (int)key;
}

obs_key_t obs_key_from_virtual_key(int code) {
    (void)code;
    return OBS_KEY_NONE;
}
