# Owner Test Pass 2 — Phase 2 Verification Checklist (Samsung Galaxy Tab A9+, Android 16)

> Confirm Phase 1 launch stability and verify every Phase 2 feature (`M2.0`–`M2.8`) before Phase 3 begins.

---

## Preconditions
1. Install latest APK on Samsung Galaxy Tab A9+ (8 GB RAM, Android 16).
2. Confirm clean launch **straight to the dark studio workspace** with zero crash or black hang (verifies M2.0 non-visual Context fix).
3. Headphones connected for audio mic+game tests; local RTMP server (`ffprobe` / VLC / `mediamtx`) reachable over LAN.

---

## Test 1 — Studio Sources & Multi-Layer Compositor (M2.1)
1. In **Sources** dock -> `+`, add an **Image** overlay via Android Photo Picker -> verify it renders crisp over **Tablet Screen**.
2. Add a **Text** source -> edit text (`LIVE · GD PRACTICE`), color, and backdrop -> verify immediate update on canvas.
3. Add a **Color Source** -> resize/move or reorder **Below/Above** using Source `▲`/`▼` buttons -> verify layer Z-order matches compositor & output stream.
4. Add a **Media Source** (local MP4) -> verify looping playback on canvas -> toggle visibility (`Eye` icon) OFF and verify smooth frame cadence with zero memory growth.
5. Tap **Probes -> Run Concurrency Probe** and verify HUD shows zero CPU-copy degradation (`OPTIMAL` HW Surface).

## Test 2 — Canvas Touch Editing, Snap, Crop, Lock & Undo/Redo (M2.2)
1. Drag the selected overlay source near canvas center -> verify magnetic snap crosshair lines appear and snap cleanly.
2. Drag bottom-right corner handle to resize; drag top rotation handle -> verify 90° snap.
3. Switch Left Tool Rail to **Crop** -> drag edge handles to crop margins -> tap **Fit to Screen** -> verify full canvas restore.
4. Tap **Undo** / **Redo** in Left Tool Rail -> verify step reversal & re-application.
5. Go **LIVE** (or **REC**) -> attempt dragging the canvas -> verify **"Live Preview Locked · Unlock"** safety guard prevents accidental bumps unless explicitly unlocked.

## Test 3 — Audio DSP Filters & GLES3 Video Filters (M2.3)
1. Verify all filters are **OFF** by default in Audio Mixer & Sources.
2. Open **Audio Mixer -> Tune (Mic/Aux)** -> enable **Noise Suppression + Noise Gate + Compressor + Limiter** -> verify clear voice and non-zero `µs` DSP badge in HUD.
3. Open **Sources -> Magic Wand (`Fx`)** on Camera2 or Image -> toggle **Color Correction**, **Chroma Key**, **Sharpen**, or **Vivid Gaming LUT** -> verify live preview & output match and `GLES: X µs` stays < 4000 µs.

## Test 4 — Studio Mode & 5 Transitions (M2.4)
1. Tap **Studio Mode** in Controls dock -> verify side-by-side **PREVIEW (left)** and **PROGRAM (right)** canvases.
2. Select Scene 2 in **Scenes** dock -> move or hide a source in **PREVIEW** -> confirm local VLC/RTMP **PROGRAM** output does **NOT** change yet.
3. Test all 5 transitions (**Cut**, **Fade**, **Slide**, **Swipe**, **Fade Color**) + tap **TAKE -> LIVE** -> verify smooth transition to Program.

## Test 5 — Profiles, Collections & OBS JSON Export/Import (M2.5)
1. Open **Settings -> Advanced**:
   - Create a second **Profile** (e.g. `Low Overhead Practice`) and switch between profiles.
   - Tap **Export OBS JSON** and inspect exported JSON -> confirm **zero stream keys / RTMP secrets** exist anywhere in JSON.
   - Re-import the exported OBS JSON -> confirm scenes, sources, and filters restore cleanly.
2. Open **Settings -> Output -> Pick Recording Folder (SAF)** or use default **Movies/Mobile Labs**.

## Test 6 — Floating Aperture Control v2 Over Geometry Dash & osu! (M2.6)
1. Enable **Floating Aperture Control** in **Settings -> Advanced** (after reviewing rationale dialog).
2. Launch **Geometry Dash** / **osu!**:
   - Tap badge -> verify 2-row v2 quick bar opens (`Go Live`, `Rec`, `Mic: ON/OFF`, `Game: ON/OFF`, `Next (Scene)`, `Bal/Low Preset`, `Open UI`).
   - Switch scene & toggle Mic Mute mid-game without leaving the game; verify menu auto-collapses after 4 s and badge dims after 3 s.
3. Swipe badge long-press (10s hide) -> confirm osu!/GD touch controls remain 100% responsive.

---

## Owner Report Template
- Launch crash fixed? (`Yes` / `Logs`)
- Geometry Dash 720p60 drop count (`DROP X`):
- Studio Mode & transitions smooth? (`Yes` / `Issue`):
- Audio DSP & GPU Video Filters HUD timing (`GLES: __ µs`, `DSP: __ µs`):
- Overlay v2 usability over gameplay:
- Attached Diagnostics ZIP (`Export Redacted ZIP`) for Gate V2 / Phase 3 sign-off!
