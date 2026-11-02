# Verification record

## Final installed VMOS build — 2026-11-02

The [installed-device identity report](../artifacts/vmos/final-installed-device-identity.json)
matches six on-device APK hashes to the published files, including controller
`1b30b77f…`, broker `bda73b4b…` and guest tools `e1712454…`. Packaging and runtime
retests passed on the measured Infinix/VMOS guest. After full guest cold startup,
the new proxy exposed both cameras without reviving the old source. Fresh
activation and all five front-camera crop/reset steps then passed in 2.148 s;
three-frame settled proof took 128–190 ms per step.

The user's portrait was restored at 1920 × 1080, fit, rotation 90°, guest camera
1. The matching preview/still multistream test and all six stock-JNI JPEG
comparisons passed in 3.995 s with nonzero normal chroma. The actual stock-camera
confirmation was visually normal and its new 155,579-byte, 1088 × 1920 JPEG had
mean RGB `(141.017, 133.664, 130.916)`. The stock JPEG JNI library was restored to
`6050a81c…`. These final results are appended to the crop and green-fix reports;
their earlier before/after measurements remain unchanged. The controller was
left Active with the user's portrait. Stock Android outside VMOS and additional
device/version combinations remain outside this verified scope.

## VMOS camera-app crop and visible pinch acceptance — 2026-11-02

The measured guest now passes standard Camera2 crop controls with a static image
on cameras 0 and 1 and a looping calibration video on camera 1, plus visible
stock-camera pinch zoom. Each 1280 × 720 run kept exactly one CameraDevice and
one capture session open through center 2×, top-left 2×, bottom-right 2×, a
different-aspect tall crop and full restoration. Each step required three
matching YUV buffers with increasing timestamps.

The bounded diagnostics sample a 320 × 180 grid using actual plane strides and
crop bounds, close Image before RGB conversion, and retain 117 comparison points
per frame. Back-image channel MAEs were at most `0.3704`; front-video MAEs were
at most `1.1254` against an unchanged limit of `14`. Back-image settled proof
took 118–186 ms per update and front-video proof took 129–198 ms. The original
front-image run's roughly 4.4-second proof included full-resolution Kotlin
conversion while holding camera buffers; that diagnostic overhead has been
removed. The video is a three-second, 15 fps gradient fixture with a blinking
corner; it establishes that the broker's active source was video, not arbitrary
moving-scene crop accuracy. See the pinned activation record and crop results.

The controller's source zoom/pan also passed separately while one independent
Camera2 session stayed open. Four 2× quadrant updates produced the expected
red, green, blue and white pixels in 297–348 ms. The final wrapper also passed
Camera2 and CameraX preview/JPEG image checks. Their two opt-in video checks
were skipped and are not counted as passing video tests.

The already-open stock `com.android.camera2` also accepted an actual two-pointer
pinch. Independent visible screenshots show the full selected calibration grid,
a magnified grid, and the restored full grid. CameraService crop metadata changed
to native `(left, top, width, height) = (858, 486, 204, 116)` after pinch-in and
returned to `(0, 0, 1920, 1088)` after pinch-out. Injection success alone is not
counted as zoom proof; the metadata and visible image changes provide it.

The [crop acceptance report](../artifacts/vmos/crop-acceptance.json) pins the
independent result, passing visible gesture logs, screenshots and metadata.
Earlier shared-handler submission failures and a gesture attempted while the
guest was hidden by the host are excluded. Canonical APK hashes and bundled
native assets were checked by `scripts/publish-vmos-build-manifest.py`, and final
installed runtime identities and post-cold-start retests are recorded above.

This establishes the recorded back-image and front-image/video crop cases on
the inspected Infinix/VMOS profile. Off-center tests establish FREEFORM metadata
behavior for apps that send those requests. One-finger input handled by an app as focus does not imply
pan. Crop controls describe latest state; queued frames are allowed to settle.
Additional devices or Android versions are not claimed by this entry.

## VMOS stock-camera green-photo repair — 2026-11-02

The reproduced image-to-text capture now passes on the physical Infinix X687B
inside the measured VMOS alpha13 / ROM10075 Android 9 ARM64 guest. The OCR app
`com.ea.image.text` 1.43 (code 18) launches stock `com.android.camera2` through
`ACTION_IMAGE_CAPTURE`. Its preview was normal, but its 1920 × 1088 still had
zero chroma before JPEG compression. The saved 1088 × 1920 image was neon green.

The HAL scaled the 1920 × 1080 preview source into a new 1920 × 1088 NV21 buffer,
then checked that buffer against the smaller original reply length. Y copied;
UV failed the old bound and remained zero. Three exact AArch64 length loads now
use the allocated scaled-buffer capacity. Original buffer validation, source
handling, copy branches and timestamps remain intact. The true original HAL
recovery copy remains SHA-256 `362261e9…`; the repaired socket transport is
`02042c56980813c89611698d6501ccd78ca05c8a9827a55aa2c21e634bd5086a`.

The independent `VmosMultistreamColorTest` reproduces the actual configuration:
SurfaceTexture preview-only repeating requests at 1920 × 1080, then a one-shot
1920 × 1088 YUV still. Before repair it failed with U/V means `0/0` and all
chroma near zero. After repair it passed with U/V means `125.496/131.872` and
zero near-zero chroma fraction. Six stock JNI JPEGs—live and copied planes,
rotations 0/1/3—passed; measured channel MAE was 9.319–9.326 against independently
decoded YUV, below the test's threshold 18. JPEG output is not pixel-perfect.

At the actual stock shutter boundary, the same Y sample mean `137.187` was
preserved while U/V changed from `0/0` to `125.843/131.062`. Both the stock capture
confirmation and the OCR app's saved JPEG were visually checked with normal
colors. The temporary JNI observer was removed and the original stock JNI hash
`6050a81c4eae572989b284bc55531a853efe83512b5413b7bffe00fb308c2bd5` restored.

The frozen broker used for this acceptance is
`VirtualCameraProVmosBroker-scale-candidate.apk`, 3,959,294 bytes, SHA-256
`df1cc3f2700bc2a30041183d7dc7b5b3aa30e438305673d0e367f5dbec98c428`.
The consolidated [green-fix acceptance](../artifacts/vmos/green-fix-acceptance.json)
pins the APK and each evidence file, including before/after stream metrics,
actual stock JNI logs and the saved OCR JPEG. Offline verification also passed
5 patch-scope tests, 20 installer recovery tests and 20 actual ARM64 post-scale
copy cases. This establishes the reproduced guest still-capture repair; it does
not establish support for every phone, Android release, guest ROM or the separate
camera-app zoom adapter.

## Physical phone running the VMOS Android 9 guest — 2026-11-02

The project now has verified camera replacement **inside the inspected VMOS
guest on the physical Infinix X687B**. The physical host remains Android 10 /
API 29; the tested ARM64 guest is Android 9 / API 28, hardware `vphw90`, ROM
`10075`, in VMOS `com.vmos.virtual` version `2.0.0-alpha13`. These results concern
applications installed inside this guest. They do not establish replacement of
the stock phone camera or compatibility with another VMOS ROM or phone.

`VmosBackendAcceptanceTest` ran as ordinary controller UID `10048`, using the
actual signed Binder API to the separate broker UID `10049`. The broker required
the pinned guest HAL setup, actual native camera-factory forwarding, a fresh
native source generation, matching selected-media SHA-256, decoded source-pixel
comparison, and real Camera2 YUV/JPEG buffers before reporting Active. The
controller process then opened Camera2 independently of the broker's own probe
and private frame files.

The following three acceptance reports contain `passed:true` and
`stage:complete`. Each verifies red-image activation, a live change to blue,
another live change to video with a new native generation, monotonic actual
sensor timestamps, and deactivation with original-camera restoration.

| Guest camera | Actual YUV and JPEG size | Independent video buffers | Sensor time span | Stable red/blue transitions | Restored-to-baseline mean RGB error |
| --- | --- | --- | --- | --- | --- |
| Back, ID `0` | 1280 x 720 | 181 | 7.126 s | 6 | 1.02 |
| Front, ID `1` | 1280 x 720 | 176 | 6.953 s | 6 | 2.05 |
| Back, ID `0` | 1920 x 1080 | 99 | 7.203 s | 6 | 1.49 |

The measured buffer counts are recorded rather than claiming a sustained
30 frames per second at every resolution. The actual captured dimensions match
the requested output in every row. JPEG source comparison errors were 15.33 for
red/video and 15.67 for blue, below the enforced maximum of 25. Restored images
were much closer to the original baseline than to either selected solid color
(the red/blue differences were approximately 82–85).

Evidence:

- [`artifacts/vmos/acceptance-camera0/acceptance.json`](../artifacts/vmos/acceptance-camera0/acceptance.json),
  with independent JPEGs and paired per-frame luma/sensor timestamps;
  [`vmos-end-to-end.log`](../artifacts/vmos/vmos-end-to-end.log), one passing test
  in 22.125 seconds.
- [`artifacts/vmos/acceptance-camera1/acceptance.json`](../artifacts/vmos/acceptance-camera1/acceptance.json);
  [`vmos-front-camera-end-to-end.log`](../artifacts/vmos/vmos-front-camera-end-to-end.log),
  one passing test in 20.175 seconds.
- [`artifacts/vmos/acceptance-1080p/acceptance.json`](../artifacts/vmos/acceptance-1080p/acceptance.json);
  [`vmos-1080p-end-to-end.log`](../artifacts/vmos/vmos-1080p-end-to-end.log),
  27.913 seconds. Its `OK (2 tests)` includes the main acceptance and a JUnit
  assumption skip of the separate finite-only test; it is not evidence that two
  playback modes passed.

After the 1080p acceptance restored the original camera, its explicit
`leaveActive=image` option reactivated a verified red source and retained the
native foreground broker. The controller was backgrounded and the separate
`com.virtualcamerapro.client` application passed both
`CameraPipelineTest.camera2PreviewAndJpeg` and
`CameraPipelineTest.cameraXPreviewAndJpeg` against that selected red source.
[`background-external-image-camera2-camerax.log`](../artifacts/vmos/background-external-image-camera2-camerax.log)
records `OK (2 tests)` in 6.282 seconds. These are actual independent client
preview/JPEG tests. This log does not establish separate-client video looping or
the original guest Camera application's saved output.

The original VMOS JPEG path first required a guest-specific repair. Its BLOB
buffer importer supplied an image stride where an allocation byte width was
required, and the guest JPEG encoder path needed the existing ARM64 encoder to
be reachable. The change retained host gralloc validation and was confined to
the measured guest libraries/paths. Recovery copies and the four-byte importer
edit are recorded in
[`jpeg-repair-prepared/jpeg-repair.json`](../artifacts/vmos/jpeg-repair-prepared/jpeg-repair.json).
Baseline evidence before media routing includes three increasing 640 x 480 YUV
buffers in [`baseline-yuv.json`](../artifacts/vmos/baseline-yuv.json), an actual
decoded 640 x 480 JPEG in
[`baseline-jpeg-repaired.json`](../artifacts/vmos/baseline-jpeg-repaired.json), and
nine combined YUV frames plus a 546,458-byte JPEG in
[`baseline-combined-repaired.json`](../artifacts/vmos/baseline-combined-repaired.json).
This repair is part of the tested guest configuration; an unmodified VMOS
installation plus the controller APK is not the verified setup.

The native transport is documented in
[`native/vmos-camera/README.md`](../native/vmos-camera/README.md). It forwards the
real camera protocol and timestamps, replaces only the selected guest camera's
pixel payloads, and consumes bounded atomically published `VCAM2` frames from
the native EGL/MediaCodec producer. Removing or expiring the source lease
restores original pixels while the proxy remains available.

The revised finite-playback test passed on the guest in 17.52 seconds. Its
independent eight-second fixture contains four seconds red followed by four
seconds blue. Camera2 received 313 actual YUV buffers spanning 11.736 seconds,
with strictly increasing timestamps and the stable sequence exactly
`[red, blue]`: one transition and no wrap. All 59 buffers in the final two seconds
were blue, with none red; the native source generation remained unchanged.
Evidence: [`finite-acceptance.json`](../artifacts/vmos/finite-acceptance.json) and
[`vmos-finite-playback.log`](../artifacts/vmos/vmos-finite-playback.log). The
runner's `OK (2 tests)` includes an assumption skip of the main looping
acceptance; the finite test itself supplies the passing evidence. The earlier
two-second-fixture run failed because its red segment ended before the
independent observer opened; it is not counted as a playback pass.

Guest cold boot also passed. The recovery foreground service started a fresh
native proxy with no selected source, CameraService exposed both guest cameras,
and an independent Camera2 YUV/JPEG probe passed in 1.234 seconds. Evidence:
[`cold-boot-recovery-state.txt`](../artifacts/vmos/cold-boot-recovery-state.txt) and
[`cold-boot-camera-pipeline.log`](../artifacts/vmos/cold-boot-camera-pipeline.log).
An additional idle crash test killed the verified proxy PID `20226`; supervision
started PID `23754`, and a fresh independent camera-pipeline test passed in
1.105 seconds. See [`idle-proxy-crash.log`](../artifacts/vmos/idle-proxy-crash.log)
and
[`idle-proxy-recovery-pipeline.log`](../artifacts/vmos/idle-proxy-recovery-pipeline.log).
This establishes recovery from the tested **idle** proxy crash. It does not
claim uninterrupted selected-media delivery during an active-source crash.

The later green-photo entry above additionally verifies the actual stock guest
Camera application's OCR capture and saved photo. Other phones, newer host
Android versions and other guest versions remain unverified.

## Physical Infinix X687B inspection and media tests — 2026-11-01

The connected ARM64 phone runs Android 10 / API 29, build `210518V215`. It has
a locked bootloader, enforcing SELinux, no existing `su` command, and a HIDL 2.5
camera provider with 3.5 device interfaces. No project broker, virtual-camera
service or CameraService routing protocol is installed. Neither supplied API 36
backend implements this Android 10 OEM camera stack. Physical camera replacement
is **unavailable on the stock Android 10 host**. The later VMOS guest acceptance
above is a separate backend and does not alter this stock-host result.

The updated standalone controller was installed without clearing its data. On
this actual phone, `FrameEngineTest` passed three tests in 7.148 seconds: image
pixels and source switching with monotonic timestamps, portrait fit/fill and
clockwise rotation, and sustained MediaCodec video with repeated loops into real
EGL/ImageReader buffers. `DeviceCompatibilityTest` passed two tests in 0.078
seconds, checking actual host reporting and that an absent broker cannot report
Active. These tests do not route media into external camera clients.

Evidence: `artifacts/physical-device-tests/05932250AF001496/runner-result.json`,
`media-engine.txt`, `compatibility-tests.txt`, `device-compatibility.json` and APK
hashes in `installations.json`. The runner explicitly records
`mediaEngineVerified:true`, `diagnosticsVerified:true`, and
`virtualCameraVerified:false`. Reproduce with
`scripts/test-physical-device.ps1 -Serial YOUR_SERIAL` after building the
standalone controller, camera-client and their instrumentation APKs.

The app now reports its actual Android version and separates controller
installation compatibility from backend support. Its missing-broker recovery no
longer suggests the x86 Cuttlefish image for an OEM phone. The TypeScript, lint,
four UI tests and Android build passed after these changes. See
[physical-device support](PHYSICAL_DEVICE_SUPPORT.md) for the remaining firmware
and per-device backend requirements. No root, unlock or partition write was done.

## Complete local emulator acceptance — 2026-11-01

The configured PC emulator passed the complete controller-driven acceptance test:
`EmulatorBackendAcceptanceTest` returned `OK (1 test)` in 68.735 seconds on the final
build (the earlier complete run took 71.417 seconds), and the
collected report has `passed:true` and `stage:complete`. Configuration: Android
Emulator 37.1.11, official AOSP API 36 image, `VirtualCameraProBroker36`, camera ID
`10`, platform-installed `:emulator-broker`, and the authenticated local camera
companion. This backend uses the emulator's real environment camera HAL.

The ordinary controller UID 10150 selected an image, invoked the same
`VirtualCameraController.activate()` used by the React Native button, and
required actual Camera2 YUV/JPEG output, selected-source pixel comparison, and
matching SHA-256 before Active. A separate manual UI check also pressed the
actual React Native Activate button and observed Deactivate after verification.

The combined acceptance proved:

- Red image activation and live switching to blue and video.
- Independent Camera2 and CameraX preview/JPEG for both images while the
  controller was in the background: two actual tests for each color.
- Moving, looping video: 194 YUV frames spanning 6.519 seconds, monotonic
  timestamps, and four stable color transitions. The independent client then
  passed all four Camera2/CameraX video/preview/JPEG cases in the background.
- The original `com.android.camera2` app's visible red preview and saved JPEG.
- A real foreground broker throughout external camera use.
- Deactivation and restoration through both the independent probe and original
  Camera app. Restored pixels differed from the baseline by mean absolute RGB
  error 8.74, versus 84.21 from red and 105.52 from blue.

Evidence: `artifacts/emulator-acceptance/acceptance.json`, `instrumentation.txt`,
`runner-result.json`, per-source status/frame proofs, client test reports,
original Camera screenshots, and JPEGs. The controller and broker remain
separate processes with ordinary app UID and system UID respectively.

A separate crash test killed the owned companion process during actual
button-activated red output. Its independent watchdog restored the baseline
through the real emulator gRPC API; a subsequent independent Camera2 JPEG showed
the normal scene (RGB difference 84.29 from the injected red source).
Evidence: `artifacts/emulator-host/watchdog-af909d4ae9a24834898bd82f3d252898.json`
and `artifacts/emulator-host-crash-restored.jpg`.

The complete gallery UI path also passed: select `VirtualCamera-red.png` through
Android's document picker, press the actual React Native Activate button, and
observe matching source SHA/pixels plus two passing independent Camera2/CameraX
preview/JPEG cases with the controller backgrounded. Evidence:
`artifacts/gallery-activation-proof.json`.

Finite and looping video modes passed their separate 16.332-second native test:
193 frames over 6.527 seconds in finite mode, with all 59 final-two-second samples
blue and none red; looping mode produced five stable color transitions. Exact
paired sensor timestamps are saved in `artifacts/emulator-playback-modes/`.
The current emulator advertises 720p, so unsupported 1080p is disabled and rejected
before source upload; actual captured dimensions must match the selected size.

Host protocol 2 cancellation and singleton ownership checks passed 25 Python
tests. Running-host checks also rejected a cancelled request and two duplicate
host starts (same and different HTTP ports), preserved the source generation and
journal, and verified the source remained red through independent Camera2.
Evidence: `artifacts/emulator-host-audit-proof.json`. TypeScript, ESLint and four
Jest tests passed, and the final standalone controller, emulator broker and test
APKs built with JDK 17.

The launcher also passed a full QEMU shutdown/cold boot: it reused the persisted
matching system broker APK, replaced only its authenticated stale companion,
restored ADB reverse/private configuration, and reopened the app. A fresh Binder
foreground/start-stop test passed after boot. The actual UI advertised 720p as
enabled and 1080p as disabled. Evidence: `artifacts/emulator-cold-start-launcher.log`,
`emulator-cold-start-broker-test.log`, and `emulator-resolution-ui-proof.json`.

Ready APKs and checksums are in `artifacts/VirtualCameraPro.apk`,
`artifacts/VirtualCameraProEmulatorBroker.apk`, and `artifacts/BUILD-MANIFEST.json`.
The manifest ties each verification to its exact APK hash. The controller used
for the complete emulator run is preserved as
`artifacts/VirtualCameraPro-emulator-verified.apk`; the latest controller adds
device diagnostics and passed the physical media/compatibility tests above.
The launcher installs the broker on the prepared emulator; the controller APK
alone is not the complete PC runtime. Sample images and a two-second video are
available in this AVD's Pictures and Movies collections.

See [EMULATOR_RUNTIME.md](EMULATOR_RUNTIME.md) to run this PC setup. These results
verify the configured emulator backend. The separate patched AOSP CameraService
product and stock physical-phone camera deployment remain unverified.

## Local broker installation — 2026-11-01

The production broker was installed on the official AOSP API 36 SDK emulator
(`VirtualCameraProBroker36`, `emulator-5556`). Android reports its path as
`/system/priv-app/VirtualCameraProBroker/VirtualCameraProBroker.apk`, UID 1000,
platform signature matching the actual `android` package, and a separate process.
The ordinary controller UID 10150 successfully bound through the real exported
AIDL service and the same `VirtualCameraController` used by React Native.
`BrokerConnectionTest` passed (`OK (1 test)`), including actual foreground-service
startup and clean deactivation to Stopped. Missing camera integration was
reported as `SYSTEM_IMAGE_MISSING`, with `supported:false` and no Active state.
Thus broker availability has been verified on this local emulator; full camera
replacement has not. Evidence: `artifacts/broker-reference/`.

This test found a real boot blocker: `android.permission.DUMP` required a
privileged permission allowlist entry. The matching XML is now included in both
the product and emulator installer and allowed the system to finish booting.

The existing API 35 AVD also delivered the two-second red/blue MP4 through the
emulator's actual file-backed HAL camera (ID `10`). Independent Camera2 and CameraX
preview/JPEG and looping tests all passed (`OK (4 tests)`). This launch-time file
source is not proof of controller-driven activation. Its stock AOSP virtual-camera
daemon can start and be discovered, but registration on the current host graphics
backend fails with `GL_EXT_YUV_target not supported`.

The shared native image/video/transform engine, Camera2 probe, and independent
baseline-camera consumers were rerun on API 36: `OK (8 tests)`, six applicable
checks passed and two synthetic-video tests were skipped because no live
controller-driven synthetic route was active. These skipped cases are not proof
of system-wide video output.

See [EMULATOR_REFERENCE.md](EMULATOR_REFERENCE.md) for reproducible local steps.

## Earlier component checks for the AOSP backend

Workstation: Windows, Android SDK 36 / NDK 27.1. Verification used the emulator;
no physical device was modified or used for these tests.
Baseline emulator: API 35 x86_64, unmodified Google emulator image.

| Check | Result |
| --- | --- |
| TypeScript strict check | Passed |
| ESLint error check | Passed |
| UI status/activation gating and progress | Passed (4 Jest tests) |
| Android Metro bundle | Passed |
| Pinned upstream patch application, source-drift guards and privileged permission installation | Passed (4 Python tests) |
| C++ routing parser compiled with Android NDK | Passed |
| C++ routing tests executed on API 35 emulator | Passed |
| Controller APK build | Passed (Gradle assembleDebug / assembleStandalone) |
| Privileged broker SDK compilation | Passed (Kotlin + pinned Java AIDL, Gradle assembleDebug) |
| Independent Camera2/CameraX client and instrumentation compilation | Passed (both APKs) |
| Separate system acceptance APK SDK compilation | Passed; platform Soong build is still required for execution |
| New shared activation probe | Passed on stock camera ID 1: real YUV planes, increasing timestamps and a decodable JPEG |
| Native EGL pixel/source-switch/timestamp tests | Passed: real RGBA pixel readback, source switch and increasing timestamps |
| Native MediaCodec video looping into EGL buffers | Passed: 6.5-second repeated red/blue transitions and monotonic timestamps |
| Portrait fit/fill and clockwise rotation | Passed: independent pixel readback of an asymmetric red/blue pattern |
| Standalone controller without Metro | Passed: cold launch, persisted video restored, no native crash |
| Controller launch/gallery/image and video preview | Passed: app launch + gallery image/video selection and native preview |
| Physical Camera2/CameraX preview/JPEG test harness | Passed on emulator camera ID 1; valid JPEGs from both APIs |
| Full AOSP/Soong build, SELinux compilation and boot | Not run: full AOSP checkout/build environment unavailable; available disks are below AOSP space requirements |
| External synthetic image/video/YUV/JPEG and looping | Not run: requires patched image |
| Original AOSP Camera app uses selected synthetic source | Not run: requires patched image |
| Deactivation restores physical camera on patched system | Not run: requires patched image |
| Background operation and broker/daemon crash recovery on patched image | Not run: requires patched image |

The patched AOSP product's remaining acceptance steps are specified in TESTING.md
and SYSTEM_IMAGE.md. Its source and compilation checks do not establish physical
phone support. The separate local emulator backend has the complete measured
acceptance result above.

## Device test run

2026-10-29, API 35 Google emulator, x86_64, host OpenGL graphics, SELinux
unchanged. The emulator exposes only public camera ID `1`; the initial consumer
run against ID `0` correctly failed and was rerun with the available ID.

`scripts/test-device.ps1 -Mode physical -CameraId 1 -Serial emulator-5554`
completed with `OK (8 tests)`, zero failures. Six tests exercised the shared probe,
engine and physical Camera2/CameraX pipeline; two synthetic external-video tests used JUnit
assumptions and were skipped because this was a stock image. Their passing status
must not be inferred from the suite summary.

A standalone test APK was also built with its Hermes bundle, arm64-v8a and x86_64
native libraries. Signing digest matches the broker's allowed development
certificate. The bundled controller APK is `artifacts/VirtualCameraPro.apk`.

The suite contains eight tests: six applicable stock-emulator checks and two
synthetic external-video checks requiring the patched image. Standalone native components
use the debug React Android ABI consistently; the APK was cold-launched with the
project Metro process stopped and crash logs were empty.

## Protocol 2 integration changes

The exact pinned framework patch applies cleanly and matches the current installer
transforms. Its diagnostic branch remains behind the existing DUMP permission
check. Shell build/acceptance scripts pass Bash syntax validation. All four Gradle
modules build; that does not compile CameraService, Soong or SELinux policy.

The new acceptance suite has three real-system tests: image/source-switch and
restoration across actual IDs at 720p/1080p, repeated video loops in an independent
Camera2 process, and the original Camera2 application's visible preview plus saved
JPEG, followed by a fresh original-app photo after removal of the virtual camera.
These tests have been compiled against the SDK but **not executed**. They
fail rather than skip if required system integration is absent.

Initial read-only diagnostics found an API 35 emulator with an AIDL provider,
no running `virtual_camera` Binder service, and no broker or routing hook. Later
checks found the native virtual-camera daemon installed and confirmed provider
discovery after starting it; registration failed on the current graphics backend.
There was no connected physical phone during those initial diagnostics.
The source implementation targets the separate Android 16 Cuttlefish product.
Host storage was about C: 33.7 GiB free / D: 18.7 GiB free; WSL has 15.5 GiB RAM,
4 GiB swap and no AOSP checkout/Repo. WSL virtual disk capacity does not overcome
the physical storage shortfall. Full details and activation trace are in
[ACTIVATION_AUDIT.md](ACTIVATION_AUDIT.md).
