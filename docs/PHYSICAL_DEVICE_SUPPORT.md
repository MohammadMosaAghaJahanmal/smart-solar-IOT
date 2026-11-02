# Physical-device support

The connected Infinix X687B cannot currently replace its system camera with the
selected media. The controller APK can run on this Android version, but neither
supplied camera backend supports this stock phone. Installing another APK does
not supply the missing operating-system camera integration.

Camera replacement is now verified **inside the Android 9 VMOS guest running
on this physical phone**. Applications must be installed inside the configured
guest to receive that feed. The stock Android 10 camera remains a separate,
unsupported target. See the dated VMOS evidence below.

## Verified support and implementation status

| Component or target | Android versions | Current evidence |
| --- | --- | --- |
| Controller APK | Android 9 / API 28 and later, ARM64 or x86_64, as packaged | Five native media/compatibility tests passed on the connected Android 10 phone. An installable controller does not establish a working system backend on every version. |
| PC Android Emulator environment backend | Android 16 / API 36, the configured AOSP x86_64 AVD | Real Camera2, CameraX and original Camera image/video delivery, background operation, finite playback, looping and restoration verified. The verified output is 1280 x 720. See [emulator runtime](EMULATOR_RUNTIME.md) and [verification](VERIFICATION.md). |
| VMOS guest on the connected Infinix X687B | Physical host Android 10 / API 29; inspected guest Android 9 / API 28, ARM64 `vphw90`, ROM `10075` | Back and front camera image/source-change/looping-video/restoration acceptance passed at 720p; back camera also passed at 1080p. Finite playback, guest cold boot and idle proxy recovery passed. Separate Camera2 and CameraX image preview/JPEG passed with the controller backgrounded. Requires the configured guest broker, transport and baseline JPEG repair. |
| Native AOSP provider and CameraService routing backend | Pinned `android-16.0.0_r1` reference product | Broker sources compile and integration checks exist. A complete patched OS image has not been built or booted; end-to-end provider/routing operation remains unverified. |
| Connected Infinix X687B | Android 10 / API 29, ARM64, stock build | No usable system backend. Fresh inspection found a locked bootloader, enforcing SELinux, no `su` on the shell path, no broker and no virtual-camera service or routing protocol. |
| Other physical devices or Android versions | Device and build dependent | No additional stock-device port or other phone/VMOS guest combination has been verified. Root access or a matching Android API level alone is insufficient evidence of support. |

The emulator backend uses the desktop emulator's environment-camera service.
It cannot control an OEM phone's camera provider. The native AOSP broker requires
API 36, system UID, the matching platform certificate, policy changes and the
patched CameraService. Lowering its minimum SDK would not provide these features
on Android 10.

## Verified VMOS guest on 2026-11-02

The physical Infinix X687B now runs a tested guest configuration in VMOS
`com.vmos.virtual` version `2.0.0-alpha13`. The guest is Android 9 / API 28,
ARM64, hardware `vphw90`, ROM `10075`. The controller and broker run as separate
ordinary guest UIDs (`10048` and `10049`); guest superuser access is used for
the guarded installation and provider restart. This is guest system integration,
not rooting or replacing the physical phone's stock camera service.

The verified path is:

```text
Android 10 physical phone
  -> configured VMOS Android 9 guest
  -> VirtualCameraPro controller and guest broker
  -> guest camera transport
  -> camera applications installed inside that guest
```

On-phone acceptance passed for the back camera at 1280 x 720, front camera at
1280 x 720, and back camera at 1920 x 1080. Each run used the actual controller
Binder API, required matching media SHA-256 and native generation/pixel proof,
then captured independent Camera2 YUV/JPEG output for red, live blue replacement
and moving video. Independent video delivered respectively 181, 176 and 99
buffers with increasing timestamps and six stable color transitions in each
run. Deactivation restored the original guest camera: mean RGB differences from
the measured baselines were 1.02, 2.05 and 1.49, versus approximately 82–85 from
the selected solid sources.

A separate guest application then passed both Camera2 and CameraX selected-image
preview/JPEG tests while the controller was in the background and the native
broker retained the active source. That result verifies camera sharing across
guest applications. It does not establish camera replacement for applications
installed outside VMOS.

The tested configuration includes a repair of the guest's original JPEG path:
the measured BLOB importer uses its allocation byte width as stride, and the
existing ARM64 JPEG encoder is made reachable through the required guest path.
The physical phone's gralloc validation and system partitions were not replaced.
The broker installer checks exact guest identity, module hashes, preserved
recovery files, actual root/provider identity and real post-restart Camera2
buffers. It journals recovery attributes before the atomic guest HAL change and
reports Active only after the selected source has passed actual camera delivery
and pixel verification. Installing the two APKs into an arbitrary VMOS image is
not equivalent to this configured system.

Evidence and exact measurements are in
[`VERIFICATION.md`](VERIFICATION.md),
[`acceptance-camera0/acceptance.json`](../artifacts/vmos/acceptance-camera0/acceptance.json),
[`acceptance-camera1/acceptance.json`](../artifacts/vmos/acceptance-camera1/acceptance.json),
[`acceptance-1080p/acceptance.json`](../artifacts/vmos/acceptance-1080p/acceptance.json), and
[`background-external-image-camera2-camerax.log`](../artifacts/vmos/background-external-image-camera2-camerax.log).

Finite playback also passed: the independent Camera2 consumer received 313 YUV
buffers spanning 11.736 seconds from an eight-second red/blue fixture. It saw
exactly one red-to-blue transition, and all 59 final-two-second buffers were
blue, demonstrating final-frame hold without wrapping. The recorded native
generation remained the same. See
[`finite-acceptance.json`](../artifacts/vmos/finite-acceptance.json).

After a guest cold boot, the recovery service started the native proxy with the
original guest camera feed and an independent Camera2 YUV/JPEG probe passed.
The exact idle proxy was then killed and replaced automatically; another
independent camera probe passed. Evidence:
[`cold-boot-recovery-state.txt`](../artifacts/vmos/cold-boot-recovery-state.txt),
[`cold-boot-camera-pipeline.log`](../artifacts/vmos/cold-boot-camera-pipeline.log),
[`idle-proxy-crash.log`](../artifacts/vmos/idle-proxy-crash.log), and
[`idle-proxy-recovery-pipeline.log`](../artifacts/vmos/idle-proxy-recovery-pipeline.log).
The crash test establishes idle recovery; uninterrupted active-source crash
delivery has not been established.

The actual image-to-text app's stock-camera capture and saved photo now also
pass inside this guest. Its 1920 × 1080 preview / 1920 × 1088 still combination
previously produced zero U/V and a green JPEG. A three-instruction correction to
the guest HAL's scaled-buffer accounting restored chroma; the independent
matching test passed and the actual saved OCR photo has normal colors. The
temporary stock-JNI observer was removed afterward. See the pinned
[green-fix acceptance report](../artifacts/vmos/green-fix-acceptance.json) and
[exact runtime repair](VMOS_RUNTIME.md#stock-camera-green-photo-repair).

The verified broker automatically upgrades the exact older guest transport
`1c80cb97…` to `02042c56…` through **Install guest camera bridge**, preserving the
true original `362261e9…` recovery copy and a rollback journal. It rejects unknown
HAL revisions. This is a repair for the measured guest, not a stock Android 10
camera-service port. Additional devices and newer host Android versions remain
outside the verified results; each VMOS/phone combination still needs its own
acceptance tests.

Standard camera-app crop controls also passed with a static selected image on
guest cameras 0 and 1 and a looping calibration video on camera 1. Independent
1280 × 720 Camera2 runs verified centered,
off-center and different-aspect crops plus full restoration in one held session;
the stock camera's visible two-finger pinch changed both image and crop metadata,
then restored them. [Crop acceptance](../artifacts/vmos/crop-acceptance.json)
records those results, including 320 × 180 sampled diagnostics and three-frame
settled proof in 118–198 ms in the bounded back-image/front-video tests.
Apps must emit the standard crop request; a gesture an app
uses for focus is not automatically a pan command. This adds no support claim
for stock-host camera replacement or another VMOS/phone combination.

The final published controller, broker and guest tools were installed with
matching on-device hashes and retested after guest cold startup. Front-camera
crop/reset passed again, followed by the user's portrait multistream/JPEG checks
and an actual normal-color stock-camera capture. The selected portrait was left
active. [Final installed identities](../artifacts/vmos/final-installed-device-identity.json)
bind these results to the exact runtime APKs; earlier host/emulator proof hashes
remain historical records.

## Connected phone inspected on 2026-11-01

The read-only report was collected at `2026-11-01T09:45:34Z` in
`artifacts/physical-device-2026-11-01.json`. The relevant facts are:

| Observation | Value |
| --- | --- |
| Model | Infinix X687B |
| Android | 10, API 29 |
| Supported application ABIs | `arm64-v8a,armeabi-v7a,armeabi` |
| Build | `QP1A.190711.020`, OEM revision `210518V215`, `user/release-keys` |
| ADB identity | UID 2000, shell |
| `ro.debuggable` | `0` |
| Bootloader state | `ro.boot.flash.locked=1`, vbmeta state `locked` |
| Verified Boot | `green` |
| SELinux | Enforcing |
| Existing root command | No `su` found on the shell path; no root attempt was made |
| Camera provider | HIDL provider `internal/0`, version 2.5; camera device interfaces version 3.5 |
| Project broker package | Not found |
| `virtual_camera` Binder service | Not found |
| Project routing protocol and lease | Absent; the requested routing query returned the ordinary camera dump |

No bootloader unlock, partition write, rooting attempt or firmware flash was
performed by this inspection. The detailed report contains the camera service's
client history; that history is unnecessary for assessing support and is not
reproduced here.

The updated controller and diagnostic APKs were subsequently installed without
clearing app data. All three native frame-engine tests passed on the phone in
7.148 seconds (image pixels/source switch/timestamps, fit/fill/rotation, and
moving looping video into real local EGL buffers). Both compatibility tests
passed in 0.078 seconds. The report is
`artifacts/physical-device-tests/05932250AF001496/runner-result.json` and explicitly
sets `virtualCameraVerified:false`. Local renderer success is not evidence that
another application receives those frames.

## What a real physical-device port requires

Android camera applications receive buffers through the framework and camera
HAL. Android introduced the HIDL camera HAL with Android 8 and added AIDL camera
HAL support with Android 13; newer frameworks can also retain HIDL providers.
The provider transport must therefore be inspected on each build. See the
[official camera HAL documentation](https://source.android.com/docs/core/camera/camera3).

For this Android 10 phone, a real implementation needs a compatible HIDL camera
provider or a tested native camera-stack integration that replaces the selected
camera's actual output. It also needs request handling, buffers, metadata,
timestamps, JPEG delivery and restoration compatible with this OEM build. The
existing Android 16 virtual-camera integration is not a drop-in backport.

A supported port must establish all of the following before modifying the phone:

1. Exact firmware/build identity, partition layout and a verified recovery path
   with matching original images.
2. A supported way to run the integration with the required privileges. For a
   custom system image this includes a usable device tree, kernel, vendor HALs
   and blobs, build configuration, signing and SELinux policy.
3. An implemented camera provider/routing backend matching the device's Android
   framework, ABI and actual HIDL or AIDL interfaces.
4. A reproducible installation and rollback procedure for that exact device and
   firmware, followed by on-device acceptance tests.

The [official Infinix source catalog](https://opensource.dev.infinixmobility.com/)
was checked on 2026-11-01. Its visible entries cover newer Android 15 models; a
matching X687B source release, official firmware package and documented OEM
unlock procedure were not established. This search does not establish that
Infinix cannot provide them. This project currently has no runnable X687B system
image and no prepared device-specific unlock or flashing procedure.

Android's [application sandbox](https://source.android.com/docs/security/app-sandbox)
also applies to native code. USB debugging, granting camera permission or
installing the broker as an ordinary app does not grant control over the system
camera stack. [Verified Boot](https://source.android.com/docs/security/features/verifiedboot)
checks the authenticity of system and vendor partitions. The
[bootloader unlocking specification](https://source.android.com/docs/core/architecture/bootloader/locking_unlocking)
requires a user confirmation and factory data reset for unlocking; no such
change is included in the current inspection or app installation.

Root could enable a different integration approach, but that backend still has
to be implemented and validated. Root alone does not create a virtual camera.
The [official Magisk installation guide](https://topjohnwu.github.io/Magisk/install.html)
requires an unlocked bootloader and matching firmware images. An application
hook that handles only some Java camera calls must not be described as verified
system-wide support: Camera1, Camera2, native clients and OEM extensions can use
different paths.

## Additional official firmware research on 2026-11-01

The [Infinix support page](https://battery.infinixmobility.com/care/support)
links its System Update option to an XClub post. That post did not expose a
usable X687B firmware listing in the current response. The
[Infinix India support page](https://infinixmobiles.in/pages/support) links
OS & Drivers to [Carlcare Drivers and Manuals](https://www.carlcare.com/global/drivers-download/);
no exact-build X687B recovery package was established there. Carlcare's
[official update guide](https://www.carlcare.com/global/tips-detail/infinix-system-and-software-update/)
documents the phone's built-in updater and service-center support, but does not
publish a matching `210518V215` recovery image or a public firmware SDK.

The phone's public, factory-installed `com.transsion.systemupdate` APK was copied
to the PC for static inspection only. `apksigner` verified its signature, whose
certificate subject identifies XOS / InfinixMobility. Its version is `V7.0.0`,
version code `20200615`. The embedded production request URL is
`https://osupdate2020.shalltry.com/OSUpdate/api/getPushInfo`; that hostname failed
DNS resolution from this PC during the inspection. This is a local observation,
not proof that the manufacturer has discontinued every update route. The URL is
an update-related request endpoint, not an established downloadable firmware
package. The APK also mentions `com.transsion.osupdate.OSUpdateActivity`, but
that separate package was not installed on the connected phone.

No private app data, IMEI or other device identifier was sent to an external
service. No updater activity was launched and no update or firmware package was
downloaded or installed. Third-party pages advertising similarly named firmware
were not accepted as manufacturer provenance. The machine-readable findings are
in `artifacts/physical-firmware-research/findings.json`; the inspected stock APK
is retained locally beside it. There is still no trusted exact-build recovery
image or prepared physical-device installation available in this workspace.

## Multiple Android versions

Support must be tracked by device build and tested backend, rather than by a
single Android version checkbox. Android 9-12 need an appropriate HIDL/platform
implementation; Android 13 and later need the actual device's HIDL or AIDL
implementation. No unimplemented version is marked ready.

Every supported port must independently verify selected image and video pixels
through Camera1, Camera2, CameraX and the device's original Camera application,
including saved JPEGs and native camera paths where applicable. Acceptance must
cover front/back selection, advertised stream sizes and combinations, rotation,
monotonic frame timestamps, looping and finite playback, background operation,
source changes, service death and restoration of the physical camera. Provider
CTS/VTS checks are also required for a HAL port. Successful preview inside the
controller, an installed broker, or an emulator test cannot replace those
physical-device checks.
