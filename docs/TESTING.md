# Camera acceptance testing

## Host checks

```powershell
npm run typecheck
npm run lint
python -m unittest discover -s tests -p 'test_*.py' -v
.\scripts\build-android.ps1
```

The optional upstream patch-application check uses downloaded pinned source files
under `tools/upstream`; it skips when those research files are absent. The
integration tool separately verifies a real checkout against `git show` at its
required tag before applying any change.

Compile `tests/native/route_test.cpp` with a C++17 host compiler, or use the NDK
compiler for a connected emulator ABI. It tests route selection, independent
camera IDs, expiry, invalid records, and restoration. On Windows/x86_64:

```powershell
New-Item -ItemType Directory artifacts -Force
& "$env:ANDROID_HOME\ndk\27.1.12297006\toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe" --target=x86_64-linux-android28 -std=c++17 -static-libstdc++ tests\native\route_test.cpp -o artifacts\route-test
adb push artifacts\route-test /data/local/tmp/vcam-route-test
adb shell chmod 755 /data/local/tmp/vcam-route-test
adb shell /data/local/tmp/vcam-route-test
```

## Stock emulator checks

On API 28+ the controller must load and show System Integration Required, select
gallery images/videos and play the source preview, with activation unavailable.
It must never show Active on stock Android. Use the independent consumer's
physical-camera tests to validate the test harness:

```powershell
.\scripts\test-device.ps1 -Mode physical
```

`FrameEngineTest` separately checks actual EGL output pixels, source switching
and monotonic timestamps through an RGBA ImageReader, plus portrait fit/fill and
clockwise rotation. It also checks native MediaCodec video looping. These tests do not establish
system-wide replacement on the stock emulator.
Pass `-CameraId 1` when the test emulator only exposes ID `1`, and use
`-Serial emulator-5554` when multiple devices are connected.

## Physical-phone media and compatibility checks

After building the standalone controller, camera-client and both test APKs:

```powershell
.\scripts\test-physical-device.ps1 -Serial YOUR_PHONE_SERIAL
```

This runner requires one explicit physical device, checks the packaged ABI and
API minimum, updates the four APKs without clearing data, and runs only
`FrameEngineTest` and `DeviceCompatibilityTest`. It does not capture the physical
camera or install a broker. Its JSON separates media/diagnostic success from
`virtualCameraVerified:false`. The connected Infinix X687B passed these five
tests on Android 10; system camera replacement remains unavailable. See
[physical-device support](PHYSICAL_DEVICE_SUPPORT.md).

## Synthetic image and JPEG capture

The automatic reference-image suite exercises all available physical camera IDs
at 720p and 1080p input, red JPEG output, a live source change to blue, deactivation,
and successful baseline camera YUV/JPEG output after clearing the route. Another
test consumes a two-second native video for 6.5 seconds and requires at least four
red/blue YUV transitions. The third launches the installed original AOSP Camera2
application and separately verifies red preview screenshot pixels and the photo
saved by that application's shutter. It then deactivates, waits for the synthetic
camera to disappear, relaunches the original Camera app and requires another valid
photo from the remaining baseline camera.

```bash
export ANDROID_SERIAL=YOUR_CUTTLEFISH_SERIAL
bash scripts/run-system-tests.sh
```

The original Camera test handles the pinned first-run location/aspect-ratio controls.
Missing image prerequisites fail the suite; these tests do not use assumptions to
skip system requirements. Evidence is pulled to `artifacts/system-acceptance/`.
The controller is force-stopped before the tests, proving that the broker owns
production independently of React Native. Manual controller/button and CameraX
checks below remain useful in addition to this suite.

1. Boot the custom image in [SYSTEM_IMAGE.md](SYSTEM_IMAGE.md), install the
   controller and test APKs, and grant the test client Camera permission.
2. Run `python scripts/make-fixtures.py` with ffmpeg installed. Push fixtures to
   `/sdcard/Download/`, then select `red.png` through the controller's gallery.
3. Activate. Check `adb shell getprop sys.vcam.route`: expect
   `0,v0_pro,<future elapsed realtime deadline>` for camera 0. The UI must be Active.
4. Leave the controller. Run:

```powershell
.\scripts\test-device.ps1 -Mode image -CameraId 0
```

Both public Camera2 and CameraX tests wait for preview frames, capture actual JPEG
output, decode it and compare its center pixel with red (compression tolerance).
Saved captures are under the test consumer's external files directory. Pull and
inspect them; do not use the source preview as camera-output evidence.

## Video, looping and background operation

Select `red-blue-2s.mp4`, enable looping, activate, leave the controller, and run:

```powershell
.\scripts\test-device.ps1 -Mode video -CameraId 0
```

The video tests capture repeatedly for longer than multiple source durations and
require repeated red/blue transitions. Test once with the controller backgrounded,
again after `adb shell am force-stop com.virtualcamerapro`, and after moving
between Camera2 and CameraX. The broker owns its copy and must keep working.
Disable Loop: output should hold the final frame; enable it again: playback should
restart. Switch image/video while active and check existing/reopened sessions.

Test 720p and 1080p, portrait media, EXIF-oriented images, rotations, fit/letterbox
and fill/crop. Deactivate before changing resolution or target camera ID. Check
timestamps and logs for codec/Surface lifecycle errors under repeated opens.

## Original Camera app — separate required verification

Find the installed camera activity using `adb shell cmd package resolve-activity
--brief -a android.media.action.STILL_IMAGE_CAMERA`. Close/relaunch that exact
activity after activating the source. Confirm the preview shows the fixture and
take a photo through that app's shutter UI. Pull its saved JPEG from DCIM and
verify the selected content. Record package/version, build fingerprint and output
files. Repeat for video and with the controller backgrounded. A passing custom
test consumer does not establish original Camera compatibility.

## Restoration and failure cases

- Deactivate in the controller or ongoing notification. Confirm the route is
  empty, reopen the camera client and verify the physical source returns.
- Kill the broker process on userdebug. New camera opens must resolve to physical
  after at most five seconds. Existing synthetic clients may disconnect and need
  reopening. Reconnect and activate again; only one `v0_pro` registration should
  remain. No automatic activation occurs after process death or reboot.
- Restart `virtual_camera`; verify the controller reports error and route clears.
- Deny consumer Camera permission: opening must still fail through Android's
  permission checks. Check sensor privacy and device camera restrictions too.
- Select corrupt/unsupported media: activation must fail and must not report Active.
- Inspect `adb logcat -b all` and `adb shell dumpsys media.camera` for crashes,
  stalled queues, metadata/stream failures and SELinux denials.

Run AOSP virtual camera tests and applicable Camera provider VTS/Camera CTS tests
before claiming platform stability. Record all results in VERIFICATION.md; source
code or a successful APK build alone does not meet system-output acceptance.
