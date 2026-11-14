cmake_minimum_required(VERSION 3.22.1)
project(mobilelabs_obs LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(OBS_THIRD_PARTY_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/obs-studio")
set(OBS_BUILD_PREBUILT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI}")
set(OBS_JNILIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/jniLibs/${ANDROID_ABI}")

if(NOT EXISTS "${OBS_THIRD_PARTY_DIR}/libobs/obs.h")
    message(FATAL_ERROR "libobs headers missing at ${OBS_THIRD_PARTY_DIR}. Run scripts/fetch_and_build_obs.sh first.")
endif()

set(OBS_SO_PATH "${OBS_BUILD_PREBUILT_DIR}/libobs.so")
if(NOT EXISTS "${OBS_SO_PATH}" AND EXISTS "${OBS_JNILIBS_DIR}/libobs.so")
    set(OBS_SO_PATH "${OBS_JNILIBS_DIR}/libobs.so")
endif()
if(NOT EXISTS "${OBS_SO_PATH}")
    message(FATAL_ERROR "libobs.so missing at ${OBS_BUILD_PREBUILT_DIR}/libobs.so. Run scripts/fetch_and_build_obs.sh first.")
endif()

add_library(libobs SHARED IMPORTED)
set_target_properties(libobs PROPERTIES
    IMPORTED_LOCATION "${OBS_SO_PATH}"
    INTERFACE_INCLUDE_DIRECTORIES "${OBS_THIRD_PARTY_DIR}/libobs;${OBS_BUILD_PREBUILT_DIR}/include"
)

add_library(mobilelabs_obs SHARED
    mobilelabs_jni.cpp
    mediacodec_obs_encoder.cpp
)

target_compile_options(mobilelabs_obs PRIVATE
    -Wall
    -Wextra
    -Werror=return-type
)

target_include_directories(mobilelabs_obs PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${OBS_THIRD_PARTY_DIR}/libobs
    ${OBS_BUILD_PREBUILT_DIR}/include
)

find_library(LOG_LIB log)
find_library(ANDROID_LIB android)
find_library(EGL_LIB EGL)
find_library(GLESV3_LIB GLESv3)
find_library(MEDIANDK_LIB mediandk)
find_library(CAMERA2NDK_LIB camera2ndk)
find_library(AAUDIO_LIB aaudio)

target_link_libraries(mobilelabs_obs
    libobs
    ${LOG_LIB}
    ${ANDROID_LIB}
    ${EGL_LIB}
    ${GLESV3_LIB}
    ${MEDIANDK_LIB}
    ${CAMERA2NDK_LIB}
    ${AAUDIO_LIB}
)
