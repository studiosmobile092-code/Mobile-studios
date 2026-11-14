# Owner Test Pass 3 — Tab A9+ On-Device Checklist

Run these manual checks on the **Samsung Galaxy Tab A9+ (8 GB, Android 16)** using the APK built from GitHub Actions (`mobile-labs-debug-apk`):

1. **1:1 OBS Studio GUI & Zero-Lag Check**:
   - Launch Mobile Labs in landscape. Verify the classic OBS dark window matches OBS Studio: top menu bar (`File | Edit | View | Profile | Scene Collection | Tools | Help`), unobstructed center 16:9 black canvas, 5 docks (`Scenes`, `Sources`, `Audio Mixer` with dB tick scale, `Scene Transitions`, `Controls`), and bottom status bar.
   - Tap `Arm Capture` -> grant permissions -> play **Geometry Dash** / preview for 5 minutes and confirm smooth responsiveness with no UI stutter.
2. **Setup & Auto-Tune Wizard (`Tools -> Auto-Tune & Quick Setup Wizard`)**:
   - Run **Auto-Tune & Apply Recommended**, verify real hardware MediaCodec probe count and recommended preset.
   - Enter your RTMP server URL and run **Test RTMP Connection** to verify handshake RTT (`ms`).
3. **Gaming Scene Presets (`Scene Collection -> Gaming Scene Presets`)**:
   - Apply **Game + Facecam** and **Starting Soon / BRB / Ending Pack**; verify new editable scenes appear in the `Scenes` dock and switch smoothly via `Scene Transitions`.
4. **Adaptive Quality Engine & One UI Advisor (`Tools -> Adaptive Quality & Samsung One UI Advisor`)**:
   - Verify device RAM tier (`8 GB Tier`), display refresh rate (`90 Hz` advisory), and Battery Optimization status.
   - Toggle **"Don't adapt"** lock ON/OFF and verify the lock icon updates cleanly in the bottom status bar.
5. **Dual RTMP(S) Destination (`Settings -> Stream`)**:
   - Enable Dual RTMP, enter a secondary RTMP endpoint + stream key, save encrypted credentials, and verify `DUAL` indicator appears in the bottom status bar during a test stream without affecting local recording.
6. **Diagnostics, Redaction & GPL Source Offer**:
   - Run `Help -> Export Redacted Diagnostics ZIP` and inspect the exported ZIP in `Downloads/` to confirm neither primary nor secondary stream keys appear anywhere.
   - Open `Help -> About Mobile Labs & GPL-2.0 Source Offer` to verify SBOM and attribution display cleanly.
