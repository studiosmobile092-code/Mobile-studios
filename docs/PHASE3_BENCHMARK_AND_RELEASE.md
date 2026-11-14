# Mobile Labs — Phase 3 Benchmark, Decision & Release Readiness Report

## 1. Vulkan Gate V3 — Formal Decision
- **Decision**: **`KEEP_GLES3_PRODUCTION_DEFAULT`**
- **Rationale**: The zero-copy GLES3/EGL multi-layer compositor (`GlZeroCopyCompositor.kt`) executes OES VirtualDisplay, Camera2, MediaSourceDecoder surfaces, color grading, chroma key, 3x3 sharpen, and LUT presets entirely in fragment shaders with zero CPU pixel reads. Vulkan `AHardwareBuffer` interop (`vulkan_spike_probe.h`) remains compiled behind `VK_USE_PLATFORM_ANDROID_KHR` feature flags until Adreno 619 hardware on the Samsung Galaxy Tab A9+ passes a side-by-side 30-minute thermal and 1% low FPS benchmark without regressions.

## 2. Phase 3 Feature Matrix
| Milestone | Module | Status | Notes |
|---|---|---|---|
| **M3.1 Adaptive Quality Engine** | `AdaptiveQualityEngine.kt` | Implemented + Unit Tested | `L0`–`L5` ladder; >=5 s step-down hysteresis, >=120 s / 300 s step-up recovery, 15 s rate-limit cooldown, `"Don't adapt"` lock |
| **M3.2 Gaming Scene Presets** | `GamingScenePresets.kt` | Implemented + Unit Tested | One-tap `Game Only`, `Game + Facecam`, `Game + Facecam + Chat Box`, `Starting Soon / BRB / Ending Pack` generating editable scenes |
| **M3.3 Samsung / One UI Advisor** | `SamsungOneUiAdvisor.kt` | Implemented + Unit Tested | Standard APIs only (`PowerManager`, `ActivityManager`, `DisplayManager`, `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`); zero prohibited permissions |
| **M3.4 Dual RTMP(S) Destination** | `HardwareMediaPipeline.kt` | Implemented | Secondary `RtmpFlvSender` shares the single hardware H.264/AAC encode; failures & drops isolated from primary output & local recording |
| **M3.5 Vulkan V3 Close** | `GlZeroCopyCompositor.kt` | Closed | GLES3/EGL remains production default |
| **M3.6 Branding, Wizard & About** | `Phase3Dialogs.kt` | Implemented | Quick-Start Onboarding & Auto-Tune Wizard (<5 min), RTMP reachability handshake test, About + SBOM + GPL-2.0-or-later source offer |
| **M3.7 Benchmark Kit (§14.4)** | `Phase3Dialogs.kt`, `DiagnosticsExporter.kt` | Implemented | In-app benchmark logger + JSON export inside diagnostics ZIP |
| **M3.8 Crash Handler & CI** | `LocalCrashHandler.kt`, `.github/workflows/android-ci.yml` | Implemented + Verified | Local redacted `UncaughtExceptionHandler` + GitHub Actions clean-checkout APK build |

## 3. Target Device & RAM Matrix
| Device Tier | Target Preset | Status |
|---|---|---|
| **Samsung Galaxy Tab A9+ (8 GB, Android 16)** | Balanced 720p60 (5 Mbps) | **Primary Target — On-Device Owner Test Pass 3 Pending (`UNMEASURED` on device)** |
| **6 GB RAM Android Tablets** | Balanced 720p60 / Quality 1080p30 | **UNTESTED** — Advisor warns against >2 simultaneous decoded video layers |
| **4 GB RAM Android Tablets** | Low-Overhead 720p30 (3.5 Mbps) | **UNTESTED** — Auto-Tune Wizard recommends Low-Overhead automatically below 4.8 GB RAM |

## 4. Competitive Benchmark Comparison (§14.4)
No simulated or guessed competitor metrics are included. Run `Tools -> Mobile Labs Benchmark Kit` on-device to record real measurements alongside Prism Live Studio Mobile and Streamlabs Mobile under identical conditions:

| Metric | Mobile Labs (720p60 Balanced) | Prism Live Studio Mobile | Streamlabs Mobile |
|---|---|---|---|
| **Game Avg FPS (Geometry Dash)** | `UNMEASURED` (Log on Tab A9+) | `UNTESTED` | `UNTESTED` |
| **Game 1% Low FPS** | `UNMEASURED` | `UNTESTED` | `UNTESTED` |
| **Stream Dropped Frames %** | `UNMEASURED` | `UNTESTED` | `UNTESTED` |
| **Peak Thermal / Temp (°C)** | `UNMEASURED` | `UNTESTED` | `UNTESTED` |
| **Process Memory PSS (MB)** | `MEASURED` live in Status Bar / HUD | `UNTESTED` | `UNTESTED` |

## 5. Google Play Release Readiness & Privacy
- **Zero Analytics / Zero Ads**: No Firebase Analytics, Crashlytics, or ad monetization SDKs are included.
- **Keystore Encryption**: Primary and secondary RTMP stream keys use AES-256-GCM in `AndroidKeyStore`.
- **Redacted Diagnostics & Crash Logs**: Both `DiagnosticsExporter` and `LocalCrashHandler` scrub primary keys, secondary keys, and RTMP credential query parameters before writing files.
- **Play Policy Compliance**: Uses Android Photo Picker (`PickVisualMedia`) and SAF (`OpenDocumentTree`), no broad media storage permissions, and standard `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`.
