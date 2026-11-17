# OBSmobile

**OBS-style live streaming and local recording for Android tablets**, built with Kotlin + Jetpack Compose. Primary target: **Samsung Galaxy Tab A9+ (Android 16)**.

> **Status (honest):**  
> The pure Android pipeline (MediaProjection + MediaCodec + GLES compositor + RTMP) is the main path.  
> Full upstream `libobs` cross-compile for Android is **experimental and currently broken** in CI (CMake ECM / Linux path issues). The native job is optional and non-blocking.

## What works today (main APK)

- Classic OBS-style dark workspace UI (Scenes, Sources, Audio Mixer, Transitions, Controls)
- Screen capture (MediaProjection) + Camera2 facecam
- Hardware H.264 + AAC encoding via MediaCodec
- Local recording (MP4 / segmented)
- RTMP/RTMPS streaming (dual destination support in code)
- Adaptive quality engine, scene collections, stream-key encryption

## Known limitations

- Full `libobs` native library build fails in CI (see Phase 1 notes)
- Some advanced OBS features are partial or UI-only
- Thorough real-device stream testing still needed on Tab A9+

## Build APK & XAPK in GitHub Actions

1. Open the repository → **Actions** tab
2. Select **OBSmobile Android CI** → **Run workflow** (or just push to `main`)
3. Options:
   - `build_variant`: `both` / `debug` / `release`
   - `package_xapk`: `true` to also make `.xapk`
   - `build_native_libobs`: `false` by default (set `true` only if you want to try the experimental native build)
4. Download artifacts:
   - `obsmobile-debug-apk-xapk`
   - `obsmobile-release-apk-xapk`
   - (optional) `obsmobile-libobs-arm64-v8a` if the native job succeeded

## Local build

```bash
bash scripts/build_wsl2.sh
# Optional experimental native build:
export ANDROID_NDK_HOME=/path/to/android-ndk
BUILD_LIBOBS=1 bash scripts/build_wsl2.sh
```

## Architecture notes

- UI: Jetpack Compose, 1:1 classic OBS dark theme for tablets
- Engine: Pure Android path is primary (`HardwareMediaPipeline`, `GlZeroCopyCompositor`, `AudioCaptureMixer`, etc.)
- Native: `libobs` JNI bridge + MediaCodec encoder plugin exist but depend on a successful NDK build of libobs
