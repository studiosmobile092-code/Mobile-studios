# Mobile Labs

**OBS-powered live streaming and local recording for Android tablets**, pairing the `libobs` C/C++ architecture (`obs-studio` 32.2.1 baseline) with a native Kotlin + Jetpack Compose 1:1 OBS Studio Classic Dark tablet workspace engineered for the **Samsung Galaxy Tab A9+ (8 GB, Android 16)**.

## Architecture & Phase 1–3 Features

- **1:1 Classic OBS Studio Dark Workspace (`app/src/main/java/com/example/mobilelabs/ui/`)**:
  - Top OBS Menu Bar (`File | Edit | View | Profile | Scene Collection | Tools | Help`), full-width dark bezel around a 16:9 black canvas with 16:10-to-16:9 safe-crop guides, split **Studio Mode** (`PREVIEW | PROGRAM`), and **5 Classic OBS Bottom Docks** (**Scenes**, **Sources**, **Audio Mixer** with `-60..0 dB` scale, peak-hold markers & custom horizontal faders, **Scene Transitions** dropdown + duration stepper, and stacked **Controls**), plus an isolated live OBS bottom status bar.
  - Zero-lag Compose isolation keeps 1 Hz telemetry and audio meter updates out of the GLES3 `SurfaceView` preview pass.
- **Core Engine & Compositor (`app/src/main/cpp/` & `media/`)**:
  - `libobs` JNI bridge (`mobilelabs_jni.cpp`) + GLES3 fullscreen-quad EGLSurface `mobilelabs_mediacodec_h264` hardware encoder plugin (`mediacodec_obs_encoder.cpp`).
  - `GlZeroCopyCompositor`: Multi-layer GLES3/EGL zero-copy renderer with API 31+ ADPF `PerformanceHintManager` frame hints, supporting `Screen Capture`, `Camera2` (clamped `<= 30 fps` for PiP thermal headroom), `Image`, hardware `MediaSourceDecoder`, cached `Text`, and `Color` sources, 5 scene transitions, and GPU fragment filters (Color Correction, Chroma Key, 3x3 Sharpen, 4 LUT presets).
  - `HardwareMediaPipeline`: Single shared hardware H.264 + AAC MediaCodec encode feeding local crash-safe MP4 / 10-minute Segmented MP4 recording (MediaStore or user-picked SAF folder) and up to **2 independent RTMP/RTMPS destinations (Dual Destination M3.4)** with compliant AVCC/FLV chunking, monotonic media clock rebasing, dynamic congestion bitrate adjustment, and bounded-queue back-pressure.
- **Audio DSP & Sync (`AudioCaptureMixer.kt`)**:
  - Non-blocking 48 kHz stereo `AudioPlaybackCapture` + `AudioRecord` mixer with per-channel `-500..+500 ms` circular delay sync offset, Gain, Android `NoiseSuppressor`, Noise Gate, Compressor, and Peak Limiter.
- **Adaptive Protection, Recovery & Security**:
  - `AdaptiveQualityEngine` (`L0`–`L5`) with strict hysteresis (>=5 s step-down, >=120 s/300 s recovery, 15 s cooldown, user `"Don't adapt"` lock) + `SamsungOneUiAdvisor`.
  - `SceneCollectionRepository` atomic JSON + `.bak` backups, OBS Studio JSON import/export, incomplete recording verification/recovery UI, `LocalCrashHandler` (redacted local crash log), and AES-256-GCM `AndroidKeyStore` encryption for primary & secondary stream keys.

## Build APK & XAPK in GitHub Actions (1-Click)

1. Open your repository on GitHub and click the **Actions** tab.
2. Select **Mobile Labs Android CI** -> **Run workflow** (or push to `main` / `master` / `develop`).
3. Choose:
   - **`build_variant`**: `both` (Debug + Release), `debug`, or `release`
   - **`package_xapk`**: `true` to also generate installable `.xapk` bundles via `scripts/package_xapk.sh`
   - **`build_native_libobs`**: `true` to compile pinned `obs-studio` `arm64-v8a` native libraries and package the native APK + XAPK
4. Download artifacts from the completed run:
   - **`mobile-labs-debug-apk-xapk`**: `mobile-labs-debug.apk`, `mobile-labs-debug.xapk`, and `sha256.txt`
   - **`mobile-labs-release-apk-xapk`**: `mobile-labs-release.apk`, `mobile-labs-release.xapk`, and `sha256.txt` *(uses optional repo secrets `KEYSTORE_B64`, `STORE_PASSWORD`, `KEY_PASSWORD`, or an ephemeral CI upload keystore when secrets are unset)*
   - **`mobile-labs-libobs-arm64-v8a`**: `mobile-labs-libobs-arm64-v8a.apk`, `mobile-labs-libobs-arm64-v8a.xapk`, and `sha256.txt`

## Local WSL2 / Linux Build

```bash
bash scripts/build_wsl2.sh
# Package any APK as XAPK locally:
bash scripts/package_xapk.sh app/build/outputs/apk/debug/app-debug.apk 3 1.0.0-rc1 app-debug.xapk
# Optional native libobs build when NDK & CMake are installed:
export ANDROID_NDK_HOME=/path/to/android-ndk
BUILD_LIBOBS=1 bash scripts/build_wsl2.sh
```
