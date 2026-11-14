#!/usr/bin/env bash
# Builds the SystemUI overlay that hides MG's rear fog light button (layout_ball with 0 x 0 icons, see gen.py: one per
# id of a range, so that it fits every SystemUI numbering its lamps inside it, #113) and copies it into the car
# version's assets. Needs the Android SDK build-tools, the API 28 platform, Python 3 and the AOSP platform key
# (platform.pk8 next to platform.x509.pem in ../../keys).
# Usage: overlay/nofog/build.sh
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
BT="$(ls -d "$SDK"/build-tools/* | sort | tail -1)"
AJ="$SDK/platforms/android-28/android.jar"
# Windows: apksigner.bat, and python3 may be the Store's stub
SIGNER="$BT/apksigner"; [ -e "$SIGNER" ] || SIGNER="$BT/apksigner.bat"
PY=python3; "$PY" -c "" 2>/dev/null || PY=python; "$PY" -c "" 2>/dev/null || PY="py -3"
$PY gen.py
OUT="$(mktemp -d)"
# Stub SystemUI: only its ids, pinned to their numbers
"$BT/aapt2" compile --dir build/stub/res -o "$OUT/stub.zip"
"$BT/aapt2" link -o "$OUT/stub.apk" --manifest build/stub/AndroidManifest.xml -I "$AJ" --stable-ids build/stable-ids.txt "$OUT/stub.zip"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/unsigned.apk" --manifest AndroidManifest.xml -I "$AJ" -I "$OUT/stub.apk" "$OUT/res.zip" --min-sdk-version 28 --target-sdk-version 28
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$SIGNER" sign --key ../../keys/platform.pk8 --cert ../../keys/platform.x509.pem --out ../../app/src/car/assets/overlay_nofog.apk "$OUT/aligned.apk"
echo "app/src/car/assets/overlay_nofog.apk updated (bump FogOverlay.BUNDLED_VERSION and the manifest versionCode together)"
