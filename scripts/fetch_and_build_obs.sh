#!/usr/bin/env bash
set -euo pipefail

# Hermetic clean-checkout NDK build script for Jansson + SIMDe + Uthash + minimal static FFmpeg + libobs (arm64-v8a).
# Pins upstream obs-studio 32.2.1 (commit 57bfcf10a) and compiles ONLY core libobs.so via obs_android_libobs/.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CPP_DIR="$ROOT_DIR/app/src/main/cpp"
ANDROID_LIBOBS_CMAKE_DIR="$CPP_DIR/obs_android_libobs"
THIRD_PARTY_DIR="$CPP_DIR/third_party"
DEPS_SRC_DIR="$THIRD_PARTY_DIR/deps-src"
DEPS_PREFIX="$THIRD_PARTY_DIR/deps-install-arm64-v8a"
OBS_SRC_DIR="${OBS_ANDROID_FORK_DIR:-$THIRD_PARTY_DIR/obs-studio}"
PREBUILT_DIR="$CPP_DIR/prebuilt/arm64-v8a"
JNILIBS_DIR="$ROOT_DIR/app/src/main/jniLibs/arm64-v8a"

OBS_COMMIT="57bfcf10a"
OBS_VERSION_STR="32.2.1"
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
  echo "ERROR: ANDROID_NDK_HOME is not set. Point it to Android NDK 27+." >&2
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

# 1. Fetch pinned upstream obs-studio (32.2.1 / 57bfcf10a) if not provided or already checked out
if [ -z "${OBS_ANDROID_FORK_DIR:-}" ]; then
  if [ ! -f "$OBS_SRC_DIR/libobs/obs.h" ]; then
    echo "Fetching upstream obs-studio ($OBS_COMMIT / $OBS_VERSION_STR)..."
    rm -rf "$OBS_SRC_DIR"
    git clone https://github.com/obsproject/obs-studio.git "$OBS_SRC_DIR"
    git -C "$OBS_SRC_DIR" checkout "$OBS_COMMIT"
  fi
fi

if [ ! -f "$OBS_SRC_DIR/libobs/obs.h" ]; then
  echo "ERROR: OBS_SRC_DIR ($OBS_SRC_DIR) does not contain libobs/obs.h" >&2
  exit 1
fi

# 2. Cross-compile Jansson (static, PIC)
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

# 3. Stage header-only SIMDe & Uthash
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

# 4. Cross-compile minimal static FFmpeg (avutil/avcodec/avformat/swscale/swresample)
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
    --disable-vulkan \
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

# 5. Generate Android obsconfig.h without invoking desktop Linux KDE ECM CMake scripts
OBS_BUILD_DIR="$THIRD_PARTY_DIR/build-obs-$ANDROID_ABI"
GENERATED_CFG_DIR="$OBS_BUILD_DIR/generated_config"
mkdir -p "$OBS_BUILD_DIR" "$GENERATED_CFG_DIR"

python3 - "$OBS_SRC_DIR" "$GENERATED_CFG_DIR/obsconfig.h" "$OBS_VERSION_STR" <<'PYEOF'
import os
import re
import sys

obs_src, out_path, ver = sys.argv[1:4]
candidates = [
    os.path.join(obs_src, "libobs", "obsconfig.h.in"),
    os.path.join(obs_src, "libobs", "cmake", "obsconfig.h.in"),
]
template = next((p for p in candidates if os.path.isfile(p)), None)

repls = {
    "OBS_VERSION": ver,
    "OBS_VERSION_CANONICAL": ver,
    "OBS_VERSION_MAJOR": ver.split(".")[0],
    "OBS_VERSION_MINOR": ver.split(".")[1],
    "OBS_VERSION_PATCH": ver.split(".")[2],
    "OBS_DATA_PATH": "/data/local/tmp/obsmobile-obs-data",
    "OBS_INSTALL_PREFIX": "",
    "OBS_PLUGIN_DESTINATION": "",
    "OBS_RELATIVE_PREFIX": "",
    "OBS_UNIX_STRUCTURE": "0",
    "OBS_RELEASE_CANDIDATE": "0",
    "OBS_BETA": "0",
}

if template:
    with open(template, "r", encoding="utf-8") as f:
        text = f.read()
    for k, v in repls.items():
        text = text.replace(f"@{k}@", v).replace(f"${{{k}}}", v)
    text = re.sub(r"@[^@\n]+@|\$\{[^}\n]+\}", "", text)

    lines = []
    for line in text.splitlines():
        m01 = re.match(r"^\s*#cmakedefine01\s+(\S+)", line)
        if m01:
            macro = m01.group(1)
            val = "1" if macro == "HAVE_OBSCONFIG_H" else "0"
            lines.append(f"#define {macro} {val}")
            continue
        m = re.match(r"^\s*#cmakedefine\s+(\S+)(.*)$", line)
        if m:
            macro = m.group(1)
            if macro in ("HAVE_OBSCONFIG_H",):
                lines.append(f"#define {macro} 1")
            else:
                lines.append(f"/* #undef {macro} */")
            continue
        lines.append(line)
    out_text = "\n".join(lines) + "\n"
else:
    out_text = f"""#pragma once
#define HAVE_OBSCONFIG_H 1
#define OBS_VERSION "{ver}"
#define OBS_VERSION_CANONICAL "{ver}"
#define OBS_DATA_PATH "/data/local/tmp/obsmobile-obs-data"
#define OBS_INSTALL_PREFIX ""
#define OBS_PLUGIN_DESTINATION ""
#define OBS_RELATIVE_PREFIX ""
#define OBS_RELEASE_CANDIDATE 0
#define OBS_BETA 0
"""

with open(out_path, "w", encoding="utf-8") as f:
    f.write(out_text)
print(f"Generated Android obsconfig.h at {out_path} (from template={template})")
PYEOF

echo "Building core libobs.so ($ANDROID_ABI) via Android NDK toolchain (target: libobs only)..."
cmake -S "$ANDROID_LIBOBS_CMAKE_DIR" -B "$OBS_BUILD_DIR" \
  -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ANDROID_ABI" \
  -DANDROID_PLATFORM="android-$ANDROID_PLATFORM" \
  -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release \
  -DOBS_SRC_DIR="$OBS_SRC_DIR" \
  -DOBS_DEPS_PREFIX="$DEPS_PREFIX"

cmake --build "$OBS_BUILD_DIR" --target libobs --parallel "$(nproc)"

LIBOBS_SO="$(find "$OBS_BUILD_DIR" -maxdepth 3 -name 'libobs.so' | head -n1)"
if [ -z "$LIBOBS_SO" ] || [ ! -f "$LIBOBS_SO" ]; then
  echo "ERROR: libobs.so was not produced under $OBS_BUILD_DIR" >&2
  exit 1
fi

rm -f "$PREBUILT_DIR/"*.so "$JNILIBS_DIR/"*.so
cp "$LIBOBS_SO" "$PREBUILT_DIR/libobs.so"
cp "$LIBOBS_SO" "$JNILIBS_DIR/libobs.so"
cp "$GENERATED_CFG_DIR/obsconfig.h" "$PREBUILT_DIR/include/obsconfig.h"

sha256sum "$JNILIBS_DIR/libobs.so" > "$PREBUILT_DIR/libobs.so.sha256"
echo "Staged libobs.so cleanly into app/src/main/jniLibs/arm64-v8a/libobs.so:"
cat "$PREBUILT_DIR/libobs.so.sha256"
