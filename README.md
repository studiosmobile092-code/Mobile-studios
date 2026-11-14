# Mobile Labs

**OBS-powered live streaming and local recording for Android tablets**, pairing the `libobs` C/C++ architecture (`obs-studio` 32.2.1 baseline) with a native Kotlin + Jetpack Compose 1:1 OBS Studio Classic Dark tablet workspace engineered for the **Samsung Galaxy Tab A9+ (8 GB, Android 16)**.

## Architecture & Phase 1–3 Features

- **1:1 Classic OBS Studio Dark Workspace (`app/src/main/java/com/example/mobilelabs/ui/`)**:
  - Top OBS Menu Bar (`File | Edit | View | Profile | Scene Collection | Tools | Help`), full-width dark bezel around a 16:9 black canvas, split **Studio Mode** (`PREVIEW | PROGRAM`), and **5 Classic OBS Bottom Docks** (**Scenes**, **Sources**, **Audio Mixer** with `-60..0 dB` scale & segmented peak meters, **Scene Transitions** dropdown + duration stepper, and stacked **Controls**), plus a live OBS bottom status bar.
  - Zero-lag Compose isolation keeps 1 Hz telemetry and audio meter updates out of the GLES3 `SurfaceView` preview pass.
- **Core Engine & Compositor (`app/src/main/cpp/` & `media/`)**:
  - `libobs` JNI bridge (`mobilelabs_jni.cpp`) + custom `mobilelabs_mediacodec_h264` encoder bindings.
  - `GlZeroCopyCompositor`: Multi-layer GLES3/EGL zero-copy renderer supporting `Screen Capture`, `Camera2`, `Image`, hardware `MediaSourceDecoder`, cached `Text`, and `Color` sources, 5 scene transitions, and GPU fragment filters (Color Correction, Chroma Key, 3x3 Sharpen, 4 LUT presets). Vulkan Gate V3 formally retains GLES3 as production default (`docs/PHASE3_BENCHMARK_AND_RELEASE.md`).
  - `HardwareMediaPipeline`: Single shared hardware H.264 + AAC MediaCodec encode feeding local MP4 recording and up to **2 independent RTMP/RTMPS destinations (Dual Destination M3.4)** with bounded-queue back-pressure.
- **Audio DSP & Adaptive Protection**:
  - 48 kHz stereo `AudioPlaybackCapture` + `AudioRecord` mixer with Gain, Android `NoiseSuppressor`, Noise Gate, Compressor, and Peak Limiter.
  - `AdaptiveQualityEngine` (`L0`–`L5`) with strict hysteresis (>=5 s step-down, >=120 s/300 s recovery, 15 s cooldown, user `"Don't adapt"` lock) + `SamsungOneUiAdvisor`.
- **Persistence, Security & Recovery**:
  - `SceneCollectionRepository` atomic JSON + `.bak` backups, OBS Studio JSON import/export, incomplete recording crash recovery, `LocalCrashHandler` (redacted local crash log), and AES-256-GCM `AndroidKeyStore` encryption for primary & secondary stream keys.

## How to Build the APK with GitHub Actions

1. Push this repository to GitHub (branches `main`, `master`, or `develop`) or trigger **Actions -> Mobile Labs Android CI -> Run workflow** manually.
2. The **`build-android-apk`** job automatically installs JDK 17, Android SDK 36, Gradle 9.3.1, runs all unit + Robolectric tests, runs Android Lint, builds `app/build/outputs/apk/debug/app-debug.apk`, and uploads it as **`mobile-labs-debug-apk`** under workflow **Artifacts**.
3. Download `mobile-labs-debug-apk.zip`, unzip `app-debug.apk`, and install it directly on your Samsung Galaxy Tab A9+.

## Local WSL2 / Linux Build

```bash
bash scripts/build_wsl2.sh
# Optional native libobs build when prebuilt NDK deps are present:
export ANDROID_NDK_HOME=/path/to/android-ndk
BUILD_LIBOBS=1 bash scripts/build_wsl2.sh
```
