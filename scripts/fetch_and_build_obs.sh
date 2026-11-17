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
JNILIBS_DIR="$ROOT_DIR/app/src/main/jniLibs/arm64-v8a"

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
  echo "ERROR: ANDROID_NDK_HOME is not set. Point it to NDK 27+." >&2
  exit 1
fi

if [ -z "${OBS_ANDROID_FORK_DIR:-}" ]; then
  cat >&2 <<'EOF'
ERROR: Upstream obs-studio (32.2.1 / commit 57bfcf10a) does NOT support Android NDK cross-compilation.
Forcing OS_LINUX=ON triggers Linux desktop dependencies unavailable in Android Bionic/NDK:
  - CMake: KDE Extra CMake Modules (ECM via cmake/linux/ecmconfig.cmake)
  - Libraries: libuuid, X11/XCB/GLX, Wayland/EGL, D-Bus/Gio, PulseAudio/PipeWire, mbedTLS, x264
  - Platform code: platform-nix.c (pthread_cancel, execinfo.h), obs-nix-*.c, gl-nix.c (no ANativeWindow winsys)

Mobile Labs production streaming/recording runs on the pure Android-native pipeline
(MediaProjection + GlZeroCopyCompositor GLES3 + AudioCaptureMixer + MediaCodec + RtmpFlvSender).
If you maintain a custom Android-ported libobs fork, set OBS_ANDROID_FORK_DIR=/path/to/obs-android-fork.
EOF
  exit 2
fi

OBS_SRC_DIR="$OBS_ANDROID_FORK_DIR"
if [ ! -f "$OBS_SRC_DIR/libobs/obs.h" ]; then
  echo "ERROR: OBS_ANDROID_FORK_DIR ($OBS_SRC_DIR) does not contain libobs/obs.h" >&2
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

echo "Building Android-ported libobs ($ANDROID_ABI) via Android NDK toolchain (target: libobs only)..."
OBS_BUILD_DIR="$THIRD_PARTY_DIR/build-obs-$ANDROID_ABI"
export PKG_CONFIG_LIBDIR="$DEPS_PREFIX/lib/pkgconfig"

cmake -S "$OBS_SRC_DIR" -B "$OBS_BUILD_DIR" \
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
  -DENABLE_BROWSER=OFF \
  -DENABLE_RTMPS=OFF \
  -DBUILD_TESTS=OFF

cmake --build "$OBS_BUILD_DIR" --target libobs --parallel "$(nproc)"

rm -f "$PREBUILT_DIR/"*.so "$JNILIBS_DIR/"*.so
cp "$OBS_BUILD_DIR/libobs/libobs.so" "$PREBUILT_DIR/libobs.so"
cp "$OBS_BUILD_DIR/libobs/libobs.so" "$JNILIBS_DIR/libobs.so"
cp -R "$OBS_BUILD_DIR/config/"* "$PREBUILT_DIR/include/" 2>/dev/null || true

sha256sum "$PREBUILT_DIR/libobs.so" > "$PREBUILT_DIR/libobs.so.sha256"
echo "Staged libobs.so cleanly into app/src/main/jniLibs/arm64-v8a:"
cat "$PREBUILT_DIR/libobs.so.sha256"
