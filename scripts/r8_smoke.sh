#!/bin/sh
# Smoke test of the shrunk (R8) build: installs the emulator release APK, opens each page and
# fails on any crash. Run in an Android 9 emulator (CI: .github/workflows/r8-smoke.yml).
set -u
PKG=app.mg4dash.emu
APK=${1:-app/build/outputs/apk/emulator/release/app-emulator-release.apk}
adb root >/dev/null 2>&1; sleep 3; adb wait-for-device
# root: the pages are not exported, only root can open them directly
# -g: runtime permissions granted, as the car grants them to the system app
adb install -r -g "$APK" || exit 1
adb logcat -c
fail=0
for a in .MainActivity .pages.ClimateActivity .pages.VehicleActivity .pages.TripsActivity .pages.SoundActivity \
         .pages.PhoneActivity .pages.EnergyActivity .settings.SettingsActivity .apps.AppsActivity; do
  out=$(adb shell am start -W -n "$PKG/app.mg4dash$a" 2>&1)
  case "$out" in *"Permission Denial"*|*"not exported"*|*Error*) echo "FAIL $a: not opened: $out"; fail=1; continue;; esac
  sleep 6
  if ! adb shell pidof "$PKG" >/dev/null; then echo "FAIL $a: process gone"; fail=1; fi
  echo "ok $a"
done
sleep 5
if adb logcat -d -b crash -b main | grep -E "FATAL EXCEPTION|AndroidRuntime: (Process|java\.|kotlin\.)" | grep -B2 -A30 "$PKG\|app\.mg4dash"; then
  echo "FAIL: crash in the shrunk build"; fail=1
fi
[ $fail = 0 ] && echo "R8 smoke test passed"
exit $fail
