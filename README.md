# Mobile Labs

**OBS-powered live streaming and local recording for Android tablets**, built with a hybrid architecture pairing the real `libobs` C/C++ engine (`obs-studio` 32.2.1 baseline) with a native Kotlin + Jetpack Compose tablet studio workspace, engineered for the **Samsung Galaxy Tab A9+ (8 GB, Android 16)**.

## Phase 1 & Phase 2 Architecture Summary

- **Core Engine (`app/src/main/cpp/`)**:
  - Real `libobs` (`obs_startup`, `obs_reset_video`, `obs_reset_audio`, scene items, transitions, audio filters) connected over NDK/JNI (`mobilelabs_jni.cpp`).
  - Custom `mobilelabs_mediacodec_h264` OBS encoder module (`mediacodec_obs_encoder.cpp`) using hardware `AMediaCodec` `COLOR_FormatSurface` zero-copy texture handoff + live bitrate updates + bounded back-pressure queues.
  - Time-boxed Vulkan spike header (`vulkan_spike_probe.h`) evaluating Gate V2 (`GLES3/EGL` retained in production; see `docs/PHASE2_DECISION_REPORTS.md`).
- **Compositor, Sources & GPU Filters (`app/src/main/java/com/example/mobilelabs/media/`)**:
  - `GlZeroCopyCompositor`: Multi-layer GLES3/EGL renderer supporting `Screen Capture`, `Camera2`, `Image`, hardware `MediaSourceDecoder` (`MediaExtractor` + `MediaCodec` -> Surface), cached `Text`, and `Color` sources. Executes GPU **Color Correction, Crop/Pad, Scale/Aspect, Chroma Key, 3x3 Sharpen, and 4 LUT presets** completely in fragment shaders with zero CPU pixel copies.
  - Supports **Studio Mode** (split Preview | Program viewports; encoder strictly sees Program) and **5 Transitions** (`Cut`, `Fade`, `Slide`, `Swipe`, `Fade-to-Color`).
- **Audio Engine & DSP (`AudioCaptureMixer.kt`)**:
  - 48 kHz stereo `AudioPlaybackCapture` + `AudioRecord` mic mixer with per-channel zero-allocation DSP (**Gain, Android `NoiseSuppressor` + High-Pass, Noise Gate, Compressor, Peak Limiter**), master soft-limiter, and `AudioDeviceCallback` routing.
- **Workspace UI & Canvas Editor (`app/src/main/java/com/example/mobilelabs/ui/`)**:
  - Classic 5-dock OBS layout (**Scenes**, **Sources**, **Audio Mixer**, **Scene Transitions**, **Controls**) with Dock Swap full-height expand, full Scene/Source CRUD, interactive touch canvas editor (move, resize, 90°-snapping rotate, crop, center snap guides, Undo/Redo, Live safety lock), and 5-tab Settings.
- **Persistence & Interop (`persistence/` & `security/`)**:
  - `SceneCollectionRepository`: Atomic JSON + `.bak` backups for scene collections, profiles, audio/video filters, SAF media asset copies, and incomplete-recording crash recovery markers.
  - `ObsJsonImporterExporter`: Full OBS Studio JSON Scene Collection export & validated import (missing asset detection + zero secret leakage).
  - `KeystoreSecretStore`: AES-256-GCM encryption in `AndroidKeyStore` for RTMP stream keys.
- **Floating Aperture Control v2 (`FloatingApertureOverlay.kt`)**:
  - Opt-in `TYPE_APPLICATION_OVERLAY` plain-View circular badge & compact 2-row quick bar (`Stream`, `Record`, `Mute Mic`, `Mute Game`, `Next Scene`, `Preset`, `Open UI`) with 3 s dim, 4 s auto-collapse, and 2-tap stop confirm.

## Toolchain & Reproducible Clean-Checkout Build

- **JDK**: 17
- **Android Compile SDK / Build Tools**: 36 / 36.0.0 (`minSdk = 29`, `targetSdk = 35`)
- **NDK / CMake / Ninja**: Android NDK 27.2.12479018+ (or 30.0.16138531), CMake 3.31.6, Ninja 1.13.2
- **Pinned `libobs` Baseline**: `obsproject/obs-studio` commit `57bfcf10a` (32.2.1)

### Local Build (WSL2 / Linux)

```bash
export ANDROID_NDK_HOME=/path/to/android-ndk
bash scripts/build_wsl2.sh
```

### GitHub Actions CI

`.github/workflows/android-ci.yml` builds cleanly on every push/PR:
1. Installs JDK 17, Android SDK 36, NDK, CMake 3.31.6, Ninja.
2. Fetches and compiles pinned `libobs` (`arm64-v8a`) via `scripts/fetch_and_build_obs.sh`.
3. Runs `:app:testDebugUnitTest -PwithLibObs=true`.
4. Assembles `:app:assembleDebug -PwithLibObs=true`, verifies `arm64-v8a` `.so` packaging and SHA-256 checksums, and uploads `mobile-labs-debug-arm64-v8a`.
