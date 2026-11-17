cmake_minimum_required(VERSION 3.22.1)
project(obs_android_libobs LANGUAGES C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

if(NOT OBS_SRC_DIR OR NOT EXISTS "${OBS_SRC_DIR}/libobs/obs.h")
    message(FATAL_ERROR "OBS_SRC_DIR must point to a valid obs-studio checkout containing libobs/obs.h")
endif()

if(NOT OBS_DEPS_PREFIX OR NOT EXISTS "${OBS_DEPS_PREFIX}/include")
    message(FATAL_ERROR "OBS_DEPS_PREFIX must point to cross-compiled Android dependencies prefix")
endif()

set(OBS_GENERATED_CONFIG_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated_config")
file(MAKE_DIRECTORY "${OBS_GENERATED_CONFIG_DIR}")

# Gather core libobs sources cleanly while excluding non-Android desktop platform backends.
file(GLOB LIBOBS_ROOT_C "${OBS_SRC_DIR}/libobs/*.c")
file(GLOB LIBOBS_UTIL_C "${OBS_SRC_DIR}/libobs/util/*.c")
file(GLOB LIBOBS_CALLBACK_C "${OBS_SRC_DIR}/libobs/callback/*.c")
file(GLOB LIBOBS_GRAPHICS_C "${OBS_SRC_DIR}/libobs/graphics/*.c")
file(GLOB LIBOBS_MEDIA_IO_C "${OBS_SRC_DIR}/libobs/media-io/*.c")
file(GLOB LIBOBS_AUDIO_MON_NULL_C "${OBS_SRC_DIR}/libobs/audio-monitoring/null/*.c")

set(LIBOBS_ALL_C
    ${LIBOBS_ROOT_C}
    ${LIBOBS_UTIL_C}
    ${LIBOBS_CALLBACK_C}
    ${LIBOBS_GRAPHICS_C}
    ${LIBOBS_MEDIA_IO_C}
    ${LIBOBS_AUDIO_MON_NULL_C}
)

list(FILTER LIBOBS_ALL_C EXCLUDE REGEX ".*/(obs-windows|obs-win32|obs-cocoa|obs-apple|obs-osx|obs-freebsd|obs-nix).*\\.c$")
list(FILTER LIBOBS_ALL_C EXCLUDE REGEX ".*/(platform-windows|platform-cocoa|platform-osx|threading-windows|pipe-windows|win-dshow|win-wasapi).*\\.c$")
list(FILTER LIBOBS_ALL_C EXCLUDE REGEX ".*/(file-updater|audio-monitoring/(win32|osx|pulse|sndio|pipewire)).*\\.c$")
list(FILTER LIBOBS_ALL_C EXCLUDE REGEX ".*/graphics/(matrix3-sse|vec4-sse|quat-sse|bounds-sse).*\\.c$")
list(FILTER LIBOBS_ALL_C EXCLUDE REGEX ".*/util/(simde|cocoa|windows)/.*\\.c$")

add_library(libobs SHARED
    ${LIBOBS_ALL_C}
    ${CMAKE_CURRENT_SOURCE_DIR}/obs_android_platform.c
)
set_target_properties(libobs PROPERTIES OUTPUT_NAME "obs")

target_compile_definitions(libobs PRIVATE
    HAVE_OBSCONFIG_H=1
    LIBOBS_EXPORTS=1
    _GNU_SOURCE=1
    NO_CURL=1
)

target_include_directories(libobs
    PUBLIC
        ${OBS_SRC_DIR}/libobs
        ${OBS_GENERATED_CONFIG_DIR}
        ${OBS_DEPS_PREFIX}/include
    PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/compat_include
)

target_compile_options(libobs PRIVATE
    -O2
    -fPIC
    -Wno-unused-parameter
    -Wno-sign-compare
    -Wno-deprecated-declarations
    -include ${CMAKE_CURRENT_SOURCE_DIR}/android_compat.h
)

# Static dependencies built by scripts/fetch_and_build_obs.sh
set(JANSSON_LIB "${OBS_DEPS_PREFIX}/lib/libjansson.a")
set(AVFORMAT_LIB "${OBS_DEPS_PREFIX}/lib/libavformat.a")
set(AVCODEC_LIB "${OBS_DEPS_PREFIX}/lib/libavcodec.a")
set(SWSCALE_LIB "${OBS_DEPS_PREFIX}/lib/libswscale.a")
set(SWRESAMPLE_LIB "${OBS_DEPS_PREFIX}/lib/libswresample.a")
set(AVUTIL_LIB "${OBS_DEPS_PREFIX}/lib/libavutil.a")

find_library(LOG_LIB log)
find_library(ANDROID_LIB android)
find_library(Z_LIB z)

target_link_libraries(libobs PRIVATE
    ${AVFORMAT_LIB}
    ${AVCODEC_LIB}
    ${SWSCALE_LIB}
    ${SWRESAMPLE_LIB}
    ${AVUTIL_LIB}
    ${JANSSON_LIB}
    ${LOG_LIB}
    ${ANDROID_LIB}
    ${Z_LIB}
    dl
    m
)
