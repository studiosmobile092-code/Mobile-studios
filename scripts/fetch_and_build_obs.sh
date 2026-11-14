#!/usr/bin/env bash
set -euo pipefail

# Reproducible clean-checkout build script for libobs + Android arm64-v8a deps.
# Pins upstream obs-studio 32.2.1 (commit 57bfcf10a) per Master Build Spec §4.2.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CPP_DIR="$ROOT_DIR/app/src/main/cpp"
THIRD_PARTY_DIR="$CPP_DIR/third_party"
PREBUILT_DIR="$CPP_DIR/prebuilt/arm64-v8a"
JNILIBS_DIR="$CPP_DIR/jniLibs/arm64-v8a"

OBS_COMMIT="57bfcf10a"
ANDROID_ABI="arm64-v8a"
ANDROID_PLATFORM="29"

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  echo "ERROR: ANDROID_NDK_HOME is not set. Point it to NDK 27+ or 30.0.16138531."
  exit 1
fi

mkdir -p "$THIRD_PARTY_DIR" "$PREBUILT_DIR/include" "$JNILIBS_DIR"

if [ ! -d "$THIRD_PARTY_DIR/obs-studio/.git" ]; then
  echo "Cloning obs-studio at pinned commit $OBS_COMMIT..."
  git clone --filter=blob:none https://github.com/obsproject/obs-studio.git "$THIRD_PARTY_DIR/obs-studio"
fi

pushd "$THIRD_PARTY_DIR/obs-studio" >/dev/null
git fetch --depth 1 origin "$OBS_COMMIT" || true
git checkout "$OBS_COMMIT"
popd >/dev/null

echo "Building libobs ($ANDROID_ABI) via Android NDK toolchain..."
OBS_BUILD_DIR="$THIRD_PARTY_DIR/build-obs-$ANDROID_ABI"
cmake -S "$THIRD_PARTY_DIR/obs-studio" -B "$OBS_BUILD_DIR" \
  -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ANDROID_ABI" \
  -DANDROID_PLATFORM="android-$ANDROID_PLATFORM" \
  -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release \
  -DENABLE_UI=OFF \
  -DENABLE_SCRIPTING=OFF \
  -DENABLE_PLUGINS=OFF \
  -DENABLE_WAYLAND=OFF \
  -DENABLE_PULSEAUDIO=OFF \
  -DENABLE_PIPEWIRE=OFF \
  -DENABLE_ALSA=OFF \
  -DENABLE_JACK=OFF \
  -DENABLE_SNDIO=OFF \
  -DENABLE_V4L2=OFF \
  -DENABLE_BROWSER=OFF \
  -DENABLE_VST=OFF \
  -DENABLE_VIRTUALCAM=OFF \
  -DENABLE_WEBRTC=OFF \
  -DENABLE_AJA=OFF \
  -DENABLE_NEW_MPEGTS_OUTPUT=OFF \
  -DENABLE_QSV11=OFF \
  -DENABLE_VLC=OFF \
  -DBUILD_TESTS=OFF

cmake --build "$OBS_BUILD_DIR" --target libobs --parallel "$(nproc)"

# Anti-stale-plugin trap (§4.2): clean prebuilt + jniLibs staging and verify SHA-256 hash
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
