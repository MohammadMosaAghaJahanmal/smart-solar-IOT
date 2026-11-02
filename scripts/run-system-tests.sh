#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the controlled Cuttlefish instance}"
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
output="${ACCEPTANCE_OUTPUT:-$repo_root/artifacts/system-acceptance}"
mkdir -p "$output"
[[ "$(adb shell getprop ro.virtualcamera.pro.version | tr -d '\r')" == 2 ]] || { echo 'Protocol 2 reference image required'; exit 1; }
[[ "$(adb shell getprop ro.debuggable | tr -d '\r')" == 1 ]] || { echo 'Userdebug reference image required'; exit 1; }
if ! adb shell pm path com.virtualcamerapro.systemtests | grep -q '^package:'; then
  test_apk="${SYSTEM_TEST_APK:-}"
  if [[ -z "$test_apk" && -n "${ANDROID_PRODUCT_OUT:-}" ]]; then
    test_apk=$(find "$ANDROID_PRODUCT_OUT/testcases" -name VirtualCameraProSystemTests.apk -print -quit 2>/dev/null || true)
  fi
  [[ -f "$test_apk" ]] || { echo 'Set SYSTEM_TEST_APK to the platform-signed Soong acceptance APK (or source lunch to set ANDROID_PRODUCT_OUT)'; exit 1; }
  adb install -t -r "$test_apk"
fi
adb shell pm grant com.virtualcamerapro.systemtests android.permission.CAMERA
adb shell pm grant com.virtualcamerapro.systemtests android.permission.READ_MEDIA_IMAGES
python3 "$repo_root/scripts/device-diagnostics.py" --serial "$ANDROID_SERIAL" --out "$output/before.json"
adb shell am force-stop com.virtualcamerapro
adb logcat -c
# The original Camera test completes the pinned Camera2 first-run controls, then
# requires both preview pixels and a saved JPEG to contain the selected fixture.
adb shell am instrument -w -r \
  -e class com.virtualcamerapro.systemtests.SystemCameraAcceptanceTest \
  com.virtualcamerapro.systemtests/androidx.test.runner.AndroidJUnitRunner | tee "$output/instrumentation.txt"
adb logcat -d -v threadtime > "$output/logcat.txt"
adb shell dumpsys media.camera > "$output/camera-service.txt"
adb pull /sdcard/Android/data/com.virtualcamerapro.systemtests/files/acceptance "$output/" || true
python3 "$repo_root/scripts/device-diagnostics.py" --serial "$ANDROID_SERIAL" --out "$output/after.json"
grep -q 'OK (3 tests)' "$output/instrumentation.txt" || { echo "Acceptance failed. Evidence: $output"; exit 1; }
if grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "$output/instrumentation.txt"; then exit 1; fi
echo "All three real-system tests passed. Review images, video samples and original Camera evidence in $output."
