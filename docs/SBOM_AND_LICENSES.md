# Software Bill of Materials (SBOM) & Licensing Notices

OBSmobile distributes a `libobs`-based application under **GPL-2.0-or-later**.

| Component | Version / Pinned Ref | Licence | Modified? | Linkage | Redistributable |
|---|---|---|---|---|---|
| `obsproject/obs-studio` (`libobs`) | `32.2.1` (`57bfcf10a`) | GPL-2.0-or-later | Integration modules in `app/src/main/cpp/` | Dynamic (`libobs.so` + `libmobilelabs_obs.so`) | Yes (GPL-2.0) |
| AndroidX Core / Compose / Lifecycle / Navigation / DataStore | Jetpack BOM `2024.09.00` / `2.8.7` / `1.1.7` | Apache-2.0 | No | Static Kotlin/Java | Yes |
| Kotlin Coroutines | `1.10.2` | Apache-2.0 | No | Static | Yes |
| Inter & JetBrains Mono Fonts | Bundled TTF (`res/font/`) | SIL Open Font License 1.1 | No | Asset | Yes |

**Explicit Exclusions (Privacy & Clean-Room Policy §4.3, §15.2)**:
- Zero advertising SDKs.
- Zero third-party analytics / crash telemetry SDKs.
- Zero proprietary code, assets, or protocols copied from closed-source mobile streamers.
