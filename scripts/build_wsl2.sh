#!/usr/bin/env bash
set -euo pipefail

# Local WSL2 / Linux clean build script for Mobile Labs (Phase 3)
# Uses ./gradlew if present or system `gradle` (Gradle 9.3.1), matching GitHub Actions CI.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

GRADLE_CMD="gradle"
if [ -x "./gradlew" ]; then
  GRADLE_CMD="./gradlew"
fi

echo "=== Mobile Labs WSL2 Reproducible Build ($GRADLE_CMD) ==="
java -version

if [ ! -f debug.keystore ]; then
  keytool -genkeypair -v -keystore debug.keystore \
    -storepass android -alias androiddebugkey -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi

if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -n "${OBS_ANDROID_FORK_DIR:-}" ]; then
  echo "Building Android-ported libobs native libraries into app/src/main/jniLibs/arm64-v8a..."
  bash scripts/fetch_and_build_obs.sh
else
  echo "Building unified Android-native Mobile Labs APK (MediaProjection + GLES3 + MediaCodec + RTMP)."
fi

rm -rf app/.cxx

$GRADLE_CMD :app:testDebugUnitTest --no-daemon
$GRADLE_CMD :app:lintDebug --no-daemon
$GRADLE_CMD :app:assembleDebug --no-daemon

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK_PATH" ]; then
  echo "Build succeeded! Artifact hash:"
  sha256sum "$APK_PATH"
fi
