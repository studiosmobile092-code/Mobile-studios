#!/usr/bin/env bash
set -euo pipefail

# Local WSL2 / Linux clean build script for Mobile Labs (Phase 1)
# Matches GitHub Actions CI pipeline and clears stale Gradle/JNI intermediates.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "=== Mobile Labs WSL2 Reproducible Build ==="
java -version

if [ -n "${ANDROID_NDK_HOME:-}" ]; then
  echo "Building pinned libobs native core..."
  bash scripts/fetch_and_build_obs.sh
  EXTRA_GRADLE_PROPS="-PwithLibObs=true"
else
  echo "WARN: ANDROID_NDK_HOME not set; building Android Kotlin/Compose/MediaCodec APK without libobs .so."
  EXTRA_GRADLE_PROPS=""
fi

# Wipe stale native intermediates before assembling (§4.2 pitfall mitigation)
rm -rf app/.cxx

./gradlew :app:testDebugUnitTest $EXTRA_GRADLE_PROPS
./gradlew :app:assembleDebug $EXTRA_GRADLE_PROPS

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK_PATH" ]; then
  echo "Build succeeded! Artifact hash:"
  sha256sum "$APK_PATH"
fi
