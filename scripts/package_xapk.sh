#!/usr/bin/env bash
set -euo pipefail

# Packages a built Android APK into a compliant XAPK archive (manifest.json + icon.png + base APK).
# Usage: bash scripts/package_xapk.sh <apk_path> <version_code> <version_name> <output_xapk_path>

if [ "$#" -lt 4 ]; then
  echo "Usage: $0 <apk_path> <version_code> <version_name> <output_xapk_path>" >&2
  exit 1
fi

APK_PATH="$1"
VERSION_CODE="$2"
VERSION_NAME="$3"
OUT_XAPK="$4"

if [ ! -f "$APK_PATH" ]; then
  echo "ERROR: APK not found at $APK_PATH" >&2
  exit 1
fi

OUT_DIR="$(dirname "$OUT_XAPK")"
mkdir -p "$OUT_DIR"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

BASE_APK_NAME="com.mobilelabs.studio.apk"
cp "$APK_PATH" "$WORK_DIR/$BASE_APK_NAME"

ICON_ENTRY=""
if [ -f "app/src/main/res/drawable-nodpi/mobile_labs_logo.png" ]; then
  cp "app/src/main/res/drawable-nodpi/mobile_labs_logo.png" "$WORK_DIR/icon.png"
  ICON_ENTRY="icon.png"
fi

python3 - "$WORK_DIR" "$BASE_APK_NAME" "$VERSION_CODE" "$VERSION_NAME" "$ICON_ENTRY" "$OUT_XAPK" <<'PYEOF'
import json
import os
import sys
import zipfile

work_dir, base_apk_name, version_code, version_name, icon_entry, out_xapk = sys.argv[1:7]
apk_full = os.path.join(work_dir, base_apk_name)
total_size = os.path.getsize(apk_full)

manifest = {
    "xapk_version": 2,
    "package_name": "com.mobilelabs.studio",
    "name": "OBSmobile",
    "version_code": str(version_code),
    "version_name": str(version_name),
    "min_sdk_version": "29",
    "target_sdk_version": "35",
    "total_size": total_size,
    "expansions": [],
    "split_apks": [
        {
            "file": base_apk_name,
            "id": "base"
        }
    ]
}
if icon_entry:
    manifest["icon"] = icon_entry

manifest_path = os.path.join(work_dir, "manifest.json")
with open(manifest_path, "w", encoding="utf-8") as f:
    json.dump(manifest, f, indent=2)

with zipfile.ZipFile(out_xapk, "w", compression=zipfile.ZIP_DEFLATED) as zf:
    zf.write(manifest_path, arcname="manifest.json")
    zf.write(apk_full, arcname=base_apk_name)
    if icon_entry:
        zf.write(os.path.join(work_dir, icon_entry), arcname=icon_entry)

print(f"Packaged XAPK: {out_xapk} ({os.path.getsize(out_xapk)} bytes)")
PYEOF

sha256sum "$OUT_XAPK"
