# Step 0 Audit, Decision Log, Feature Matrix & Risk Register (Phase 2 Updated)

## A. Step 0 Source Audit Summary

| Component | Truth Label | Verified Status |
|---|---|---|
| `obsproject/obs-studio` 32.2.1 (`57bfcf10a`) | `CONFIRMED-SOURCE` | Pinned baseline for `libobs` C/C++ engine (`arm64-v8a`), `obs-outputs` (`rtmp-stream`), `rtmp-services`, `obs-ffmpeg`. |
| `cunminF/OBS_for_Android` | `CONFIRMED-SOURCE` | Reference GLES/EGL Android port; Mobile Labs replaces Qt 6.9.3 with native Kotlin/Compose (D3). |
| AI Studio build sandbox | `MEASURED` | Android SDK 36 + Gradle 9.1.1 present; Kotlin/Compose APK & Robolectric tests run locally; CI (`android-ci.yml`) compiles full `libobs` `.so`. |
| Galaxy Tab A9+ hardware telemetry | `UNMEASURED` (awaiting Owner Test Pass 2) | `DeviceAndCodecProbe` + HUD GLES/DSP microsecond timers bundled to measure Adreno 619 & Snapdragon 695 headroom. |

## B. Decision Log (Phase 1 & Phase 2)

- **D1 / Gate V2 — Renderer (`GLES3/EGL` production, `Vulkan` spike evaluated)**: `APPROVED`. `GlZeroCopyCompositor` renders multi-source layers + GPU filters cleanly; `vulkan_spike_probe.h` documents why GLES3 remains default until physical profiling proves a bottleneck (see `docs/PHASE2_DECISION_REPORTS.md`).
- **D2 — Hardware `MediaCodec` H.264 Surface Encoder**: `APPROVED`. `OPTIMAL` zero-copy Surface input.
- **D3 — Native Kotlin + Jetpack Compose Frontend**: `APPROVED` (re-evaluated in `docs/PHASE2_DECISION_REPORTS.md`).
- **D4 — ARM -> GO Session Model**: `APPROVED`.
- **D5 — Shared Single Hardware Encode**: `APPROVED`.
- **D6 — Optional `SYSTEM_ALERT_WINDOW` Floating Aperture Control v2**: `APPROVED`. Plain Android Views (`FloatingApertureOverlay`), disabled by default.
- **D-BR1 — Browser Source Deferred**: `APPROVED`. Deferred in Phase 2 because Android `WebView` lacks a zero-copy GPU texture render path and adds Chromium RAM/CPU overhead hurting rhythm games.

## C. Feature Matrix (Phase 1 + Phase 2)

| Area | Feature | Status | Notes |
|---|---|---|---|
| Stability (M2.0) | Non-visual Context `DisplayManager` display probe + `CoroutineExceptionHandler` | `Complete` | Fixes Android 11–16 startup crash (`Application.getDisplay()` / `WindowManager.currentWindowMetrics`) |
| Engine | `libobs` JNI lifecycle + scene item transform + transition + filter sync | `Android + Native JNI` | Built cleanly in CI (`-PwithLibObs=true`); honest status in non-NDK APK |
| Sources (M2.1) | Screen Capture, Camera2, Image (Photo Picker), Media (HW `MediaCodec`), Text, Color, Audio | `Complete` | Size-aware image decode + bounded LRU cache + immediate MediaCodec release on hide |
| Canvas Editor (M2.2) | Touch select/move/resize/rotate/crop + edge/center snap + Undo/Redo + Live lock guard | `Complete` | Includes accessible `SelectedSourceNudgeBar` and multi-select center alignment |
| Audio DSP (M2.3) | Per-channel Gain, Android `NoiseSuppressor` + HPF, Noise Gate, Compressor, Limiter | `Complete` | Zero-allocation mixer-thread DSP; all OFF by default; measured `audioDspMicros` |
| Video Filters (M2.3) | GLES3 GPU Color Correction, Crop/Pad, Scale/Aspect, Chroma Key, Sharpen, 4 LUTs | `Complete` | 100% GPU fragment shader; zero CPU pixel copies; measured `compositorPassMicros` |
| Studio Mode (M2.4) | Split Preview/Program dual viewport + `TAKE` + Cut, Fade, Slide, Swipe, Fade-to-Color | `Complete` | Preview edits never leak to Program/Encoder until Take |
| Persistence (M2.5) | Atomic JSON + `.bak`, Profiles, Scene Collections, OBS JSON Export/Import, SAF Folder | `Complete` | Keystore secrets never written to exports; missing media assets flagged for relink |
| Overlay v2 (M2.6) | 2-row Plain-View quick bar (Stream, Record, Mic/Game Mute, Next Scene, Preset, Open UI) | `Complete` | Zero idle CPU; 3 s dim; 4 s auto-collapse; 2-tap stop confirm |
| Telemetry & CI | 1 Hz HUD (incl. GLES/DSP µs) + Device Probe + Redacted ZIP + Reproducible CI | `Complete` | Full regression test suite in `MobileLabsPhase1Test` & `MobileLabsPhase2Test` |

## D. Risk Register (Phase 2)

| # | Risk | L/I | Status / Mitigation |
|---|---|---|---|
| 1 | Multi-layer Image/Text VRAM growth | M/M | Mitigated via `inSampleSize` size-aware decoding + 16-entry LRU cache + frame-age pruning |
| 2 | Media Source decoder thread leak on scene switch | M/H | Stopped immediately when hidden or switched out of active Program/Preview |
| 3 | Accidental touch edits during LIVE stream | M/H | `PreviewTouchEditorOverlay` locks automatically while LIVE/REC unless Studio Mode ON or unlocked |
| 4 | Tab A9+ thermal throttling under heavy GPU filters | H/M | All filters OFF by default + 1 Hz `PowerManager` thermal warning banner + quick Low-Overhead toggle |
| 5 | Secret key leak in OBS JSON exports | L/H | `ObsJsonImporterExporter` strictly exports scenes/sources/filters/transitions only; stream keys stay in Keystore |
