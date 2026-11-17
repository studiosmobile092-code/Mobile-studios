# Owner Test Pass 1 & Perfetto / Dumpsys Measurement Guide

## Part A: External Measurement Commands (Run from PC over ADB)

Use the exact same method for Game-Only baseline (3 runs) and Game + OBSmobile Live runs:

```bash
# 1. Record device build & thermal baseline before run
adb shell getprop ro.product.model
adb shell getprop ro.build.display.id
adb shell dumpsys thermalservice

# 2. Sample OBSmobile memory PSS while streaming
adb shell dumpsys meminfo com.mobilelabs.studio

# 3. Sample battery temperature & drain
adb shell dumpsys battery

# 4. Capture 60-second Perfetto trace (SurfaceFlinger FrameTimeline + CPU/Sched)
adb shell perfetto -o /data/misc/perfetto-traces/ml_trace.pftrace -t 60s \
  sched freq idle am wm gfx view binder_driver hal
adb pull /data/misc/perfetto-traces/ml_trace.pftrace .
```

## Part B: Owner Test Pass 1 Checklist (Paste Results Back for Phase 2)

| # | Check | Expected | Level | Result (PASS/FAIL + Notes) |
|---|---|---|---|---|
| T1.1 | Install APK from GitHub Actions; launch | Opens in landscape; `#720000` aperture splash; no crash | MUST | |
| T1.2 | Open `Probes -> Device & Codec Probe` -> `Run Concurrency Probe` | Shows Tab A9+ model/Android 16/8GB RAM; lists hardware H.264 encoder & max concurrent count | MUST | |
| T1.3 | Tap `Arm Capture` | Full-screen consent only; preview shows tablet screen | MUST | |
| T1.4 | Set `Canvas 1080p -> Out 720p`; collapse docks; rotate & split-screen | HUD shows `OUT 1280x720`; VLC/ffprobe receiver shows `1280x720` | MUST | |
| T1.5 | Mic and system audio | Mic meter moves; game/YouTube meter moves, or honest unavailable notice | MUST | |
| T1.6 | Add Camera2 source, switch front/rear, remove | Front/rear/switch work; releases cleanly on removal | MUST | |
| T1.7 | Stream to local RTMP receiver, then toggle Wi-Fi off/on | `LIVE` only after connect; `RECONNECTING` countdown shown; clean stop | MUST | |
| T1.8 | Record 10 min; then force-stop during a second recording | Finalized MP4 in `Movies/OBSmobile`; interrupted file listed `INCOMPLETE` | MUST | |
| T1.9 | Enable optional Floating Control in Settings; test over osu! & Geometry Dash | Start/stop works; game touch unaffected | MUST | |
| T1.10 | 30-min stream + record with Geometry Dash & osu!; `Export Redacted ZIP` | Meets §11.4 thresholds vs baseline, or deviations reported | MUST | |
| T1.11 | Check encoder mode in HUD | Shows `OPTIMAL` hardware H.264 (or honest `DEGRADED`/`EMERGENCY`) | MUST | |
| T1.12 | Stop screen sharing from Android status bar chip mid-stream | App shows capture stopped, finalizes recording, offers Re-Arm | MUST | |
| T1.13 | Force-stop and relaunch | Scene/sources restored; no phantom `LIVE` badge | MUST | |
| T1.14 | Inspect exported ZIP for your stream key | Stream key absent from CSV, JSON, and logs | MUST | |
| T1.15 | Warm-device back-to-back soak | Thermal banner appears with one-tap `Low-Overhead` | SHOULD | |
| T1.16 | 60-min soak at 1080p30 | Stable; flat PSS memory; report numbers | SHOULD | |
