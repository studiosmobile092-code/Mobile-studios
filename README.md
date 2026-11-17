# OBSmobile

**OBS-style live streaming and local recording for Android tablets.**  
Primary target: **Samsung Galaxy Tab A9+ (Android 16)**.

Built with Kotlin + Jetpack Compose. Pure Android media pipeline is the primary path.

---

## Current Status (honest)

| Area | Status |
|------|--------|
| Classic OBS-style dark UI (Scenes / Sources / Mixer / Transitions / Controls) | Working |
| Screen capture (MediaProjection) | Working |
| Camera2 facecam | Working |
| Hardware H.264 + AAC (MediaCodec) | Working |
| Local recording (MP4 / segmented) | Working (see notes) |
| RTMP / RTMPS streaming | Working (primary + dual destination) |
| Adaptive quality + thermal awareness | Implemented |
| Stream-key encryption (Android Keystore) | Implemented |
| Full upstream libobs NDK build | **Broken / experimental** (CMake ECM) — job is optional |
| System / game audio capture | Partial — many apps block AudioPlaybackCapture |

---

## Known Limitations

1. **"Recording too short — no playable frames"**  
   Happens if you stop recording before the first keyframe is encoded and written. Always wait at least 1–2 seconds after pressing Record before stopping. The pipeline already requests an IDR on start.

2. **System / game audio silent**  
   Android only allows capture of apps that opt in (`ALLOW_CAPTURE_BY_ALL`). Many games, Spotify, Netflix, YouTube, etc. block capture. This is a platform restriction, not a bug in OBSmobile. Mic capture still works.

3. **libobs native library**  
   The CI job that builds `libobs` for arm64-v8a currently fails (missing ECM / Linux CMake paths). It is disabled by default and marked `continue-on-error`. The pure Android path does **not** depend on it.

4. **Scaling / tablet layout**  
   Designed for 16:10 landscape (Tab A9+). Portrait shows a rotate prompt. Some dock layouts can still be tight on smaller tablets.

5. **Real-device confidence**  
   Code paths for RTMP handshake, keyframe handling, pause/resume PTS rebase, and dual destination exist and look solid, but end-to-end live streams on a physical Tab A9+ still need verification by you.

---

## How to build the single APK

### GitHub Actions (recommended)
1. Go to **Actions** → **OBSmobile Android CI**
2. Run workflow (or just push to `main`)
3. Leave `build_native_libobs` = **false**
4. Download `obsmobile-debug-apk-xapk` or `obsmobile-release-apk-xapk`

### Local
```bash
bash scripts/build_wsl2.sh
# Output: app/build/outputs/apk/debug/app-debug.apk
```

---

## How to test a real stream on Tab A9+

1. Install the debug or release APK.
2. Grant **Microphone**, **Camera**, and **Notifications** when asked.
3. Open stream settings → enter your Twitch or YouTube RTMP URL + stream key (keys are stored encrypted).
4. Tap **Start Streaming** / Arm → accept the MediaProjection consent dialog.
5. Confirm the notification says **OBSmobile · LIVE**.
6. Check Twitch/YouTube dashboard for the stream.
7. For recording: start Record, wait ≥ 2 seconds, stop. File should appear under Movies (or your chosen folder).

---

## Architecture (short)

- **UI**: Jetpack Compose, classic OBS dark theme, 5 bottom docks
- **Capture**: MediaProjection + VirtualDisplay → GLES compositor
- **Encode**: Shared MediaCodec H.264 (Surface input) + AAC
- **Outputs**: Local MediaMuxer (with pause/resume PTS rebase) + RTMP/RTMPS senders
- **Audio**: AudioRecord (mic) + AudioPlaybackCapture (game/system, when the other app allows it)
- **Native**: Optional libobs JNI bridge (not required for core features)

---

## Phase history

- **Phase 1**: Renamed to OBSmobile, made native job optional, single reliable APK path
- **Phase 2**: Branding polish, notification improvements, research into recording/audio platform limits, accurate status docs

---

## Disclaimer

This is an independent project inspired by OBS Studio. It is **not** affiliated with or endorsed by the OBS Project.
