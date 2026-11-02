# Production broker on the local Android emulator

For the working controller-driven PC camera setup, use
[EMULATOR_RUNTIME.md](EMULATOR_RUNTIME.md). This page documents the separate AOSP
broker installation diagnostic; installing that variant replaces the emulator
runtime broker.

The SDK AOSP API 36 image supports testing the real broker's platform identity and
Binder connection without building Android. The emulator's unmodified camera
framework does not contain this project's ID routing hook, so this installation
alone must not report camera activation as supported.

## Prepare and start

Use JDK 17 and the installed SDK/NDK from README.md. The download script pins the
official Google archive and verifies its published checksum; interrupted range
downloads can be resumed.

```powershell
python scripts/download-reference-image.py --connections 8
python scripts/prepare-broker-reference.py

& "$env:ANDROID_HOME\emulator\emulator.exe" -avd VirtualCameraProBroker36 `
  -port 5556 -writable-system -no-snapshot -gpu host
```

The preparer creates a separate AVD and refuses to overwrite another SDK image or
AVD. It downloads the public AOSP development signing key. Those keys match this
official AOSP test image; the installer checks the actual platform certificate
before rooting or modifying the emulator. The existing Google APIs API 35 image
has a different platform key and is not a valid signing target.

## Install and verify

```powershell
.\scripts\build-android.ps1
.\scripts\install-reference-broker.ps1 -Serial emulator-5556
.\scripts\test-broker.ps1 -Serial emulator-5556 -ExpectedSupportCode SYSTEM_IMAGE_MISSING
```

The installer only accepts emulator serials, verifies API 36/userdebug, remounts
the isolated writable system, installs both the privileged permission XML and
the platform-signed broker, and reboots. The `DUMP` allowlist entry must be present
before rebooting with the broker installed; omitting it prevents Android 16 from
finishing boot. The product configuration includes the same permission file.

The test runs as the ordinary controller UID. It requires:

- Broker installation on a system partition with UID 1000 and platform signature.
- The controller's real signing permission and a live exported AIDL Binder.
- Responses from a different PID with actual broker identity metadata.
- A successful call through `VirtualCameraController`, used by the TurboModule.
- An honest unavailable result and no Active state on an unpatched OS.
- Real foreground-service startup and clean deactivation back to Stopped.

Evidence is saved under `artifacts/broker-reference/`. On 2026-11-01 this test
passed on the official API 36 AOSP image: broker UID 1000, ordinary controller UID
10150, and support code `SYSTEM_IMAGE_MISSING`. The service is installed and
responding; this result does not demonstrate synthetic camera routing.

## Existing API 35 camera checks

The existing `VirtualCameraProTest` AVD has a native `virtual_camera` daemon and
enabled provider-discovery flags. Starting that daemon with root allows
CameraService to discover it. Actual registration on the host graphics backend
fails with `GL_EXT_YUV_target not supported`. A service lookup before the lazy
daemon starts is therefore insufficient to diagnose provider availability.

The installed emulator also supports `-camera-back videofile:ABSOLUTE_MP4_PATH`.
With the two-second red/blue fixture selected at emulator launch, a camera with
ID `10` was exposed. Both independent Camera2 and CameraX preview/JPEG tests and
their looping-video tests passed (`OK (4 tests)`). These are actual HAL buffers,
but a launch-time source does not verify controller-driven activation or source
switching. Runtime Offworld video injection is rejected by emulator 37.1.11;
the isolated older-emulator experiment must be verified before being integrated.

The complete product acceptance still requires selected gallery media reaching a
separate consumer, the original Camera app, background operation and restoration
under controller control. See SYSTEM_IMAGE.md for the framework integration.
