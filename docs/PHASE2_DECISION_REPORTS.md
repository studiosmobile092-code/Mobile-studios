# Phase 2 Engineering Decision Reports (M2.7)

## 1. Decision D3 Re-Evaluation — Native Kotlin/Compose UI vs Desktop Qt 6.9.3

- **Decision**: Retain **Native Kotlin + Jetpack Compose** frontend paired with **`libobs` C/C++ engine + GLES3/MediaCodec** (`PROPOSED` -> `APPROVED` for Phase 2 & 3).
- **Evidence & Instrumentation**:
  - `GlZeroCopyCompositor` measures GLES3 GPU render pass time (`compositorPassMicros`) per tick; `AudioCaptureMixer` measures DSP filter time (`audioDspMicros`) per 1024-frame stereo buffer.
  - All bottom docks (Scenes, Sources, Audio Mixer, Scene Transitions, Controls) and 5-tab Settings, Filters, Studio Mode dual-pane, and OBS JSON Import/Export operate cleanly inside Android native Views/Compose without bundling ~65 MB+ of Qt 6.9.3 shared libraries or managing Qt/Android EGL surface conflicts during split-screen/orientation switches.
  - **Device Physical Metrics**: `UNMEASURED` on Samsung Galaxy Tab A9+ until Owner Test Pass 2 (`telemetry_1hz.csv` + Play Store PSS memory trace).

## 2. Decision Gate V2 — Time-Boxed Vulkan `AHardwareBuffer` Spike Report

- **Spike Artifact**: `app/src/main/cpp/vulkan_spike_probe.h` (`mobilelabs::vulkan_spike::VulkanSpikeProbeResult`).
- **Evaluation**:
  - **Production Status**: GLES3/EGL (`GlZeroCopyCompositor`) runs multi-layer OES textures + `GL_TEXTURE_2D` + solid colors + GPU color/chroma/sharpen/LUT shaders zero-copy into the `AMediaCodec` `COLOR_FormatSurface` window surface.
  - **Vulkan Promotion Gate Criteria**: Switch to Vulkan (`VK_ANDROID_external_memory_android_hardware_buffer`) only if physical Tab A9+ profiling proves GPU compositing is a frame-time bottleneck (>4 ms/frame at 720p60) AND Vulkan yields >=10% measurable frame-time/thermal improvement without driver instability.
  - **Outcome**: Keep **GLES3/EGL** in production for Phase 2; physical Adreno 619 Vulkan delta remains `UNMEASURED` pending Owner Test Pass 2 HUD telemetry (`GLES: X µs`).

## 3. Decision D-BR1 — Browser Source (Phase 2 Deferral)

- **Attempted Analysis**: Spec §3.5 / §12.2 M2.1 explicitly states Browser Source is attempted *only if* a clean GPU texture path exists without degrading Geometry Dash or osu! framerate.
- **Root Cause for Deferral**:
  - Android `WebView` has no public zero-copy GPU `Surface`/`AHardwareBuffer` render target API compatible with third-party compositors; capturing `WebView` frames requires `WebView.draw(Canvas)` backed by a software bitmap or `PixelCopy`, causing continuous CPU pixel copies (violating Iron Rule R8) and spawning an isolated Chromium renderer process (~150–300 MB RSS).
  - On the 8 GB Galaxy Tab A9+, adding a Chromium renderer alongside a rhythm game + H.264 encode risks memory pressure and GPU contention.
- **UI Treatment**: Cleanly documented in Add Source dialog footer and README; no dead button or stub implementation exists. Image/Text/Media/Color overlays cover overlays cleanly in Phase 2.
