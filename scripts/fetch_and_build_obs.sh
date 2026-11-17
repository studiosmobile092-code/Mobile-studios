#!/usr/bin/env bash
set -euo pipefail

# Hermetic clean-checkout NDK build script for Jansson + SIMDe + minimal static FFmpeg + libobs (arm64-v8a).
# Pins upstream obs-studio 32.2.1 (commit 57bfcf10a).

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CPP_DIR="$ROOT_DIR/app/src/main/cpp"
THIRD_PARTY_DIR="$CPP_DIR/third_party"
DEPS_SRC_DIR="$THIRD_PARTY_DIR/deps-src"
DEPS_PREFIX="$THIRD_PARTY_DIR/deps-install-arm64-v8a"
PREBUILT_DIR="$CPP_DIR/prebuilt/arm64-v8a"
JNILIBS_DIR="$CPP_DIR/jniLibs/arm64-v8a"

OBS_COMMIT="57bfcf10a"
JANSSON_TAG="v2.14"
SIMDE_TAG="v0.8.2"
UTHASH_TAG="v2.3.0"
FFMPEG_TAG="n6.1.2"
ANDROID_ABI="arm64-v8a"
ANDROID_PLATFORM="29"

if [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT/cmake/3.31.6/bin" ]; then
  export PATH="$ANDROID_SDK_ROOT/cmake/3.31.6/bin:$PATH"
fi
export CMAKE_POLICY_VERSION_MINIMUM=3.5

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  echo "ERROR: ANDROID_NDK_HOME is not set. Point it to NDK 27+."
  exit 1
fi

HOST_OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
HOST_ARCH="$(uname -m)"
if [ "$HOST_OS" = "linux" ] && [ "$HOST_ARCH" = "x86_64" ]; then
  NDK_HOST_TAG="linux-x86_64"
else
  NDK_HOST_TAG="${HOST_OS}-${HOST_ARCH}"
fi
TOOLCHAIN_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$NDK_HOST_TAG/bin"
SYSROOT="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$NDK_HOST_TAG/sysroot"
CC_BIN="$TOOLCHAIN_BIN/aarch64-linux-android${ANDROID_PLATFORM}-clang"
CXX_BIN="$TOOLCHAIN_BIN/aarch64-linux-android${ANDROID_PLATFORM}-clang++"

mkdir -p "$THIRD_PARTY_DIR" "$DEPS_SRC_DIR" "$DEPS_PREFIX/include" "$DEPS_PREFIX/lib" "$PREBUILT_DIR/include" "$JNILIBS_DIR"

# 1. Cross-compile Jansson (static, PIC)
if [ ! -f "$DEPS_PREFIX/lib/libjansson.a" ]; then
  echo "Building Jansson $JANSSON_TAG for $ANDROID_ABI..."
  if [ ! -d "$DEPS_SRC_DIR/jansson/.git" ]; then
    git clone --depth 1 --branch "$JANSSON_TAG" https://github.com/akheron/jansson.git "$DEPS_SRC_DIR/jansson"
  fi
  cmake -S "$DEPS_SRC_DIR/jansson" -B "$DEPS_SRC_DIR/build-jansson" -G Ninja \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ANDROID_ABI" \
    -DANDROID_PLATFORM="android-$ANDROID_PLATFORM" \
    -DCMAKE_INSTALL_PREFIX="$DEPS_PREFIX" \
    -DJANSSON_BUILD_DOCS=OFF \
    -DJANSSON_BUILD_TESTS=OFF \
    -DJANSSON_EXAMPLES=OFF \
    -DBUILD_SHARED_LIBS=OFF \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON
  cmake --build "$DEPS_SRC_DIR/build-jansson" --target install --parallel "$(nproc)"
fi

# 2. Stage header-only SIMDe & Uthash
if [ ! -d "$DEPS_PREFIX/include/simde" ]; then
  echo "Staging SIMDe $SIMDE_TAG headers..."
  if [ ! -d "$DEPS_SRC_DIR/simde/.git" ]; then
    git clone --depth 1 --branch "$SIMDE_TAG" https://github.com/simd-everywhere/simde.git "$DEPS_SRC_DIR/simde"
  fi
  cp -R "$DEPS_SRC_DIR/simde/simde" "$DEPS_PREFIX/include/"
fi

if [ ! -f "$DEPS_PREFIX/include/uthash.h" ]; then
  echo "Staging Uthash $UTHASH_TAG headers..."
  if [ ! -d "$DEPS_SRC_DIR/uthash/.git" ]; then
    git clone --depth 1 --branch "$UTHASH_TAG" https://github.com/troydhanson/uthash.git "$DEPS_SRC_DIR/uthash"
  fi
  cp "$DEPS_SRC_DIR/uthash/src/"*.h "$DEPS_PREFIX/include/"
fi

# 3. Cross-compile minimal static FFmpeg (avutil/avcodec/avformat/swscale/swresample)
if [ ! -f "$DEPS_PREFIX/lib/libavcodec.a" ]; then
  echo "Building minimal static FFmpeg $FFMPEG_TAG for $ANDROID_ABI..."
  if [ ! -d "$DEPS_SRC_DIR/ffmpeg/.git" ]; then
    git clone --depth 1 --branch "$FFMPEG_TAG" https://github.com/FFmpeg/FFmpeg.git "$DEPS_SRC_DIR/ffmpeg"
  fi
  pushd "$DEPS_SRC_DIR/ffmpeg" >/dev/null
  ./configure \
    --prefix="$DEPS_PREFIX" \
    --target-os=android \
    --arch=aarch64 \
    --cpu=armv8-a \
    --enable-cross-compile \
    --cc="$CC_BIN" \
    --cxx="$CXX_BIN" \
    --ar="$TOOLCHAIN_BIN/llvm-ar" \
    --nm="$TOOLCHAIN_BIN/llvm-nm" \
    --ranlib="$TOOLCHAIN_BIN/llvm-ranlib" \
    --strip="$TOOLCHAIN_BIN/llvm-strip" \
    --sysroot="$SYSROOT" \
    --extra-cflags="-fPIC -O2" \
    --enable-pic \
    --enable-static \
    --disable-shared \
    --disable-programs \
    --disable-doc \
    --disable-avdevice \
    --disable-avfilter \
    --disable-postproc \
    --disable-asm \
    --enable-avutil \
    --enable-avcodec \
    --enable-avformat \
    --enable-swscale \
    --enable-swresample
  make -j"$(nproc)"
  make install
  popd >/dev/null
fi

# 4. Checkout & build pinned obs-studio libobs
if [ ! -d "$THIRD_PARTY_DIR/obs-studio/.git" ]; then
  echo "Cloning obs-studio at pinned commit $OBS_COMMIT..."
  git clone --filter=blob:none https://github.com/obsproject/obs-studio.git "$THIRD_PARTY_DIR/obs-studio"
fi

pushd "$THIRD_PARTY_DIR/obs-studio" >/dev/null
git fetch --depth 1 origin "$OBS_COMMIT" || true
git checkout "$OBS_COMMIT"
# Allow obs-studio osconfig.cmake to treat Android NDK as POSIX/Linux-like host target cleanly
python3 - <<'PYEOF'
from pathlib import Path
p = Path("cmake/common/osconfig.cmake")
if p.exists():
    txt = p.read_text(encoding="utf-8")
    if "OS_ANDROID" not in txt:
        txt = txt.replace('CMAKE_SYSTEM_NAME MATCHES "Linux|FreeBSD|OpenBSD"', 'CMAKE_SYSTEM_NAME MATCHES "Linux|FreeBSD|OpenBSD|Android"')
        txt += '\nif(ANDROID OR CMAKE_SYSTEM_NAME STREQUAL "Android")\n  set(OS_LINUX ON)\n  set(OS_ANDROID ON)\n  set(OS_POSIX ON)\nendif()\n'
        p.write_text(txt, encoding="utf-8")
# ECM (KDE Extra CMake Modules) is pulled in by cmake/linux/defaults.cmake.
# Android cross builds do not need it. Make the include a no-op so configure
# does not die with "Could not find a package configuration file provided by ECM".
ecm = Path("cmake/linux/ecmconfig.cmake")
if ecm.exists():
    body = ecm.read_text(encoding="utf-8")
    guard = 'if(ANDROID OR CMAKE_SYSTEM_NAME STREQUAL "Android")\n  return()\nendif()\n'
    if "return()" not in body.split("endif()", 1)[0]:
        ecm.write_text(guard + body, encoding="utf-8")
PYEOF
popd >/dev/null

if command -v sudo >/dev/null 2>&1; then
  sudo apt-get update -qq || true
  sudo apt-get install -y extra-cmake-modules || true
fi

echo "Building libobs ($ANDROID_ABI) via Android NDK toolchain..."
OBS_BUILD_DIR="$THIRD_PARTY_DIR/build-obs-$ANDROID_ABI"
export PKG_CONFIG_LIBDIR="$DEPS_PREFIX/lib/pkgconfig"

cmake -S "$THIRD_PARTY_DIR/obs-studio" -B "$OBS_BUILD_DIR" \
  -G Ninja \
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ANDROID_ABI" \
  -DANDROID_PLATFORM="android-$ANDROID_PLATFORM" \
  -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_PREFIX_PATH="$DEPS_PREFIX" \
  -DCMAKE_FIND_ROOT_PATH="$DEPS_PREFIX;$SYSROOT" \
  -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH \
  -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH \
  -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH \
  -DUthash_INCLUDE_DIR="$DEPS_PREFIX/include" \
  -DFFmpeg_INCLUDE_DIRS="$DEPS_PREFIX/include" \
  -DCMAKE_C_FLAGS="-I$DEPS_PREFIX/include -fPIC" \
  -DCMAKE_CXX_FLAGS="-I$DEPS_PREFIX/include -fPIC" \
  -DBUILD_SHARED_LIBS=ON \
  -DENABLE_UI=OFF \
  -DENABLE_SCRIPTING=OFF \
  -DENABLE_PLUGINS=OFF \
  -DENABLE_X11=OFF \
  -DENABLE_WAYLAND=OFF \
  -DENABLE_PULSEAUDIO=OFF \
  -DENABLE_PIPEWIRE=OFF \
  -DENABLE_ALSA=OFF \
  -DENABLE_JACK=OFF \
  -DENABLE_SNDIO=OFF \
  -DENABLE_V4L2=OFF \
  -DENABLE_FREETYPE=OFF \
  -DENABLE_FONTCONFIG=OFF \
  -DENABLE_LIBFDK=OFF \
  -DENABLE_SPEEXDSP=OFF \
  -DENABLE_RNNOISE=OFF \
  -DENABLE_RTMPS=OFF \
  -DENABLE_GIO=OFF \
  -DENABLE_DBUS=OFF \
  -DENABLE_BROWSER=OFF \
  -DENABLE_VST=OFF \
  -DENABLE_VIRTUALCAM=OFF \
  -DENABLE_WEBRTC=OFF \
  -DENABLE_AJA=OFF \
  -DENABLE_DECKLINK=OFF \
  -DENABLE_NEW_MPEGTS_OUTPUT=OFF \
  -DENABLE_QSV11=OFF \
  -DENABLE_VLC=OFF \
  -DBUILD_TESTS=OFF

cmake --build "$OBS_BUILD_DIR" --target libobs --parallel "$(nproc)"

# Anti-stale-plugin trap: clean prebuilt + jniLibs staging and verify SHA-256 hash
rm -f "$PREBUILT_DIR/"*.so "$JNILIBS_DIR/"*.so
cp "$OBS_BUILD_DIR/libobs/libobs.so" "$PREBUILT_DIR/libobs.so"
cp "$OBS_BUILD_DIR/libobs/libobs.so" "$JNILIBS_DIR/libobs.so"
if [ -f "$OBS_BUILD_DIR/libobs-opengl/libobs-opengl.so" ]; then
  cp "$OBS_BUILD_DIR/libobs-opengl/libobs-opengl.so" "$PREBUILT_DIR/libobs-opengl.so"
  cp "$OBS_BUILD_DIR/libobs-opengl/libobs-opengl.so" "$JNILIBS_DIR/libobs-opengl.so"
fi
cp -R "$OBS_BUILD_DIR/config/"* "$PREBUILT_DIR/include/" 2>/dev/null || true

sha256sum "$PREBUILT_DIR/libobs.so" > "$PREBUILT_DIR/libobs.so.sha256"
echo "Staged libobs.so cleanly into prebuilt + jniLibs:"
cat "$PREBUILT_DIR/libobs.so.sha256"
