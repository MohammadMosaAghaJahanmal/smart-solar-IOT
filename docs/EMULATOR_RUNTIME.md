# Virtual Camera Pro on the local Android emulator

This backend lets the controller select media and change an actual emulator
camera while other Android camera apps consume its frames. It uses the modern
Android Emulator environment renderer, a platform installed Android broker,
and an authenticated companion process on this PC. The standalone controller
APK starts without Metro.

The verified configuration is Android Emulator **37.1.11**, the AOSP **API 36**
image, AVD **VirtualCameraProBroker36**, serial **emulator-5556**, and its back
camera **10**. Camera IDs come from the running device; `10` is specific to this
configuration. This backend controls the camera configured as `environment`.

The emulator broker is a separate Gradle module, `:emulator-broker`. Its installed
package is `com.virtualcamerapro.broker`, with the same controller AIDL interface
as the AOSP production broker. Install one broker variant at a time. This
emulator implementation does not establish that the separate patched
CameraService product works on a physical phone.

## Prepare the PC and the stopped AVD

For this already prepared workspace, the launcher handles startup, authenticated
host connection, matching broker installation and opening the controller:

```powershell
.\scripts\start-emulator-camera.ps1
# Also run the complete acceptance test:
.\scripts\start-emulator-camera.ps1 -Test
```

It uses a hidden emulator window by default. Use `-ShowWindow` when starting a
stopped AVD to display the emulator. `-Build` rebuilds the Android APKs with JDK 17.
The detailed first-time setup follows.

Run these commands from `D:\vcamera` in PowerShell. The existing SDK, JDK 17,
NDK, Node dependencies, and Python installation are used.

```powershell
Set-Location D:\vcamera
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:\vcamera'
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
```

The companion requires Python `grpcio` and Pillow. The tested versions are
`grpcio==1.80.0` and `Pillow==12.1.1`. Its default FFmpeg executable is the
existing file under `tools\ffmpeg\imageio_ffmpeg\binaries\`; an alternative
can be supplied with `--ffmpeg`.

```powershell
python -m pip install grpcio==1.80.0 Pillow==12.1.1
python scripts/download-reference-image.py --connections 8
python scripts/prepare-broker-reference.py
python scripts/emulator-environment.py --avd VirtualCameraProBroker36 --prepare
```

Skip the downloads when the reference image and AVD are already prepared.
`--prepare` requires the selected AVD to be stopped and preserves an existing
`environment.ini`. It creates a default environment file when one is absent.
**The emulator must load this file during startup.** Creating it after boot is
insufficient: `reloadEnvironment` otherwise reports that the AVD did not load
`environment.ini` and returns false.

The downloaded image is the official AOSP development image. The preparer
fetches its matching public platform test key; the installer verifies the
actual platform certificate before modifying this isolated emulator.

## Start the emulator

Use the PC's normal temporary directory on C: with sufficient free space. An
earlier launch with temporary files on the constrained D: drive failed the
emulator's disk preflight. Run one test emulator at a time on this PC.

```powershell
$env:TEMP = "$env:LOCALAPPDATA\Temp"
$env:TMP = $env:TEMP
$emulator = Start-Process -FilePath "$env:ANDROID_HOME\emulator\emulator.exe" `
  -ArgumentList @('-avd','VirtualCameraProBroker36','-port','5556',
    '-writable-system','-no-snapshot','-no-audio','-no-boot-anim',
    '-qt-hide-window','-gpu','host','-feature','-Vulkan',
    '-memory','2048','-cores','2','-camera-back','environment',
    '-grpc','8554','-grpc-use-token') `
  -WindowStyle Hidden -PassThru

adb -s emulator-5556 shell getprop sys.boot_completed
```

Continue after `sys.boot_completed` returns `1`. The tested configuration uses
`-qt-hide-window`: Qt starts with its window hidden. The modern environment
backend has not been reverified with `-no-window` after the environment file
fix, so this recipe retains the tested launch mode.

## Build and install

```powershell
.\scripts\build-android.ps1
.\scripts\install-reference-broker.ps1 -Serial emulator-5556 -BrokerVariant emulator
```

The installer signs the emulator broker for this AOSP test image, installs it
under `/system/priv-app/VirtualCameraProBroker/`, installs the required
privileged permission allowlist, and reboots. It accepts only explicit emulator
serials. The `DUMP` allowlist must be installed before rebooting with the system
broker; Android 16 otherwise fails its privileged permission checks during boot.

The controller build is
`android\app\build\outputs\apk\standalone\app-standalone.apk`. Install it
directly or use the test runner, which also installs the instrumentation APKs:

```powershell
adb -s emulator-5556 install -r android\app\build\outputs\apk\standalone\app-standalone.apk
```

## Start the companion and connect the broker

Keep the companion running in a separate PowerShell terminal:

```powershell
Set-Location D:\vcamera
python scripts/emulator-camera-host.py --avd VirtualCameraProBroker36 --camera-id 10
```

It binds only `127.0.0.1:8791`, discovers the exact named emulator's authenticated
gRPC service, and creates
`artifacts\emulator-host\connection.json`. This ignored file contains a random
Bearer token. Keep it private; do not paste it into logs or commit it.

In the first terminal:

```powershell
adb -s emulator-5556 root
.\scripts\connect-emulator-camera.ps1 -Serial emulator-5556
.\scripts\test-broker.ps1 -Serial emulator-5556 -ExpectedSupportCode READY
adb -s emulator-5556 shell am start -n com.virtualcamerapro/.MainActivity
```

The connector validates the local host and target emulator, establishes
`adb reverse` for port 8791, and copies the connection into the system broker's
private files with UID 1000 ownership and mode 600. Reconnect after restarting
the companion, because its authentication token changes, and after restarting
the emulator, because ADB reverse is temporary.

Select an image or video, choose the environment back camera, and press
**Activate Virtual Camera**. The Android broker uploads the source and settings
to the companion. Pillow or FFmpeg prepares native media, and
`reloadEnvironment` changes the emulator camera's source. JavaScript does not
produce or transport individual frames.

The broker opens the selected camera through Android Camera2, requires real
YUV frames and a decodable JPEG, and asks the companion to compare that JPEG
with the selected source. Renderer configuration alone is insufficient to
report Active. The app's Deactivate action restores the saved environment
baseline.

## Verification

Pure host tests do not contact an emulator:

```powershell
python -m unittest discover -s tests -p 'test_*.py' -v
```

The controller and independent camera consumer acceptance runner is:

```powershell
.\scripts\test-emulator-backend.ps1 -Serial emulator-5556 -CameraId 10
```

It installs the standalone controller and its tests, plus the independent
Camera2/CameraX client and its tests. It records the complete result under
`artifacts\emulator-acceptance\`. A successful run requires both
`instrumentation.txt` containing `OK (1 test)` and a new `acceptance.json` with
`passed: true` and `stage: complete`; the runner rejects stale or missing
proofs. Consult the actual collected report before claiming that the whole
suite passed.

Verified individually on November 1, 2026:

- Native Camera2 YUV observation of the runtime video source: 182 frames over
  6.03 seconds, six red/blue transitions, and monotonic camera timestamps.
  Evidence: `artifacts/modern-environment-video-proof.json`.
- Platform installed emulator broker, system UID 1000, and the ordinary
  controller's live Binder connection with support code `READY`.
- The actual React Native Activate button changed to Deactivate after a real
  source verification; a red controller activation also passed its native
  frame proof.
- Independent Camera2 and CameraX consumers received the red source while the
  controller was in the background.
- The original Android Camera app displayed the source and saved a red JPEG.
  `artifacts/emulator-original-camera-red.jpg` has center RGB approximately
  `(235, 16, 14)`.
- Killing the companion process triggered its independent watcher, which
  restored the baseline through the emulator's actual gRPC service. The next
  independent camera JPEG showed the normal scene rather than the red source.
  Evidence: `artifacts/emulator-host/watchdog-af909d4ae9a24834898bd82f3d252898.json`.

The combined acceptance run also passed: `instrumentation.txt` reports
`OK (1 test)` in 68.735 seconds on the final build, and `acceptance.json` reports `passed: true`,
`stage: complete`. It exercised real controller red/blue/video switching,
Camera2 and CameraX while the controller was in the background, 6.5 seconds of
native YUV video looping, the original Camera preview and saved source photo,
and a restored Camera photo with a baseline pixel comparison. These results are
under `artifacts/emulator-acceptance/`.

## Recovery and limits

The companion uses a 15-second lease renewed by the Android broker. Missing
heartbeats restore the baseline. A separate hidden Python watcher waits for
the companion's process to exit, checks that the journal still belongs to
that process and the same emulator run, and restores the baseline if needed.
The journal also supports recovery on the next companion start. The persisted
AVD environment file is kept at the baseline, so a cold emulator boot starts
with the normal source.

For a manual baseline restore on this running AVD:

```powershell
python scripts/emulator-environment.py --avd VirtualCameraProBroker36 --restore
```

This emulator camera advertises **720p** YUV/JPEG output. Its compiled HAL size
list does not include 1920×1080. The broker checks actual YUV and JPEG sizes and
the UI disables unavailable resolutions. A 1080p render canvas alone is not
1080p camera delivery. Fit/fill and rotations of 0, 90, 180, or 270 degrees are
supported; image EXIF orientation and ICC-to-sRGB conversion precede user rotation.
The companion accepts sources up to 256 MiB and images up to 48 megapixels.
Both video modes have passed actual Camera2 observation. Loop enabled continues
changing frames; Loop disabled plays once and holds the actual final decoded
frame. The finite-mode test observed 59 blue final-frame samples and zero red
samples in the last two seconds, within a 6.527-second capture. The looping-mode
test observed five stable color transitions. Evidence:
`artifacts/emulator-playback-modes/`.

Host protocol 2 uses unique activation request IDs and explicit cancellation.
Cancelled uploads/conversions cannot later configure the renderer. An exclusive
AVD lock prevents duplicate hosts from changing an active source; the independent
crash watcher uses the same lock. Both duplicate-port cases and cancellation were
also exercised through the running host, and the independent camera retained the
expected source. See `artifacts/emulator-host-audit-proof.json`.

The backend rejects a camera ID other than its
configured environment camera. Configuring both lenses as `environment`
would share one global scene, so this setup configures only the back lens.

This procedure verifies a PC connected emulator backend. It does not claim
physical phone support or completion of the separate full AOSP system image
build. The original AOSP broker verification remains documented in
[EMULATOR_REFERENCE.md](EMULATOR_REFERENCE.md), and the Android framework product
integration remains documented in [SYSTEM_IMAGE.md](SYSTEM_IMAGE.md).
