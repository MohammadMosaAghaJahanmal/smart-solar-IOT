# Activation investigation and protocol 2 changes

This is the earlier AOSP framework investigation. The configured PC emulator now
has a separate working backend with complete controller, external client,
original Camera, background-video and restoration acceptance. See
[EMULATOR_RUNTIME.md](EMULATOR_RUNTIME.md) and [VERIFICATION.md](VERIFICATION.md).

The disabled button was a correct symptom of missing system components. In
`CameraScreen.tsx`, activation requires selected media and native `supported:true`.
The old support check depended on an installed privileged broker, API 36, the
system UID, a version marker and the virtual camera Binder service. None of those
system components are installed by an ordinary controller APK. Enabling the
button on stock Android would not deliver frames to another application.

The native engine and bridge already existed. The full path is:

1. `CameraScreen` invokes `camera.activate()` through `useCamera.run`.
2. `NativeVirtualCamera` codegen connects to `VirtualCameraModule.activate`.
3. `VirtualCameraController` validates source and support, starts the broker FGS,
   and sends a read-only media file descriptor plus settings through AIDL.
4. The platform-signed `BrokerService` copies the source, decodes it with the
   shared image/MediaCodec engine, registers a device-0 AOSP virtual camera and
   waits until CameraManager discovers it.
5. The broker publishes a five-second `sys.vcam.route` lease for the selected
   actual camera ID. CameraService's common resolver and Camera1 integer resolver
   map that ID to `v0_pro`; other camera IDs keep their normal mapping.
6. HAL configuration callbacks give the native EGL producer an RGBA input Surface.
   Capture-request callbacks demand new frames. The AOSP C++ HAL converts them to
   client YUV/preview and JPEG outputs. JavaScript is outside this frame path.

The previous activation implementation stopped short of proving this pipeline:
registration and a property write could produce an Active state without a
successful external camera open or buffer delivery. It also offered hardcoded
IDs 0/1 without querying the device. These are now addressed in source:

- Protocol 2 verifies the actual framework hook through a DUMP-protected command,
  including CameraService initialization and registered/resolved virtual ID.
- Activation opens the selected public ID through a real Camera2 probe and
  requires at least three nonempty YUV frames, increasing timestamps, valid JPEG
  data and native producer advancement from the selected source revision.
- HAL callback handling uses a separate executor; activation cannot deadlock
  waiting for callbacks queued behind itself. Lease renewal continues during the
  probe. Generation checks protect against stale callbacks after cleanup.
- Failures remove routing and tear down the provider/decoder. No successful
  activation result is returned without frame evidence. Source switching also
  waits for delivery of its new revision.
- The UI queries CameraManager IDs and lens facing, shows activation progress,
  and reports missing component codes with specific recovery actions.
- A dedicated Cuttlefish product, boot initialization, runtime permissions,
  platform-private SELinux rules and separate acceptance APK are included.

## Environment evidence and verification boundary

Read-only diagnostics on 2026-10-29 found no connected physical phone. Testing
used `emulator-5554`, Android 15/API 35, x86_64, SELinux enforcing. Its camera dump
reports `android.hardware.camera.provider.ICameraProvider/internal/...` (AIDL),
with public camera ID `1`. The shell UID is 2000, `ro.debuggable=1`, and a `su`
binary exists; no root command was invoked. Bootloader lock/verified-boot
properties are blank, so that status is unknown. At that time the emulator had
no integration marker or broker APK, and the `virtual_camera` Binder service was
not running. Later checks found its native daemon installed: starting that lazy
service lets CameraService discover its provider, but actual registration fails
on the current graphics backend with `GL_EXT_YUV_target not supported`. Its stock
CameraService does not implement the routing query. A system APK update cannot
install those missing native framework changes.

The supported reference architecture is therefore the supplied Android 16 r1
Cuttlefish product with an AIDL virtual HAL. It is not a firmware image for an
unidentified OEM phone. Android 13+ can support AIDL providers, but the actual
transport must be read from each device; older HIDL device ports are outside this
pinned reference implementation.

Windows free space measured about 33.7 GiB on C: and 18.7 GiB on D:. WSL Ubuntu
22.04 exists with 15.5 GiB RAM, 4 GiB swap and `/dev/kvm`; its user is not in the
KVM group. There is no full AOSP checkout or Repo executable. WSL's reported
virtual disk capacity does not create free space on the physical backing volume.
The official build baseline is 400 GB free disk and 64 GB RAM:
[AOSP host requirements](https://source.android.com/docs/setup/start/requirements).
There is insufficient physical storage to fetch/build the reference image here.

All Android modules compile through Gradle. Stock-emulator tests exercise the
real shared probe, native buffer pixels, image/video producer, transforms and
independent physical-camera consumers. Later API 36 tests installed the real
privileged broker and verified controller Binder communication; see
[VERIFICATION.md](VERIFICATION.md). Patched CameraService has not been executed.
The Soong build, SELinux compilation, patched-image boot, selected-media
delivery to an external camera client, original Camera app output, and patched
system restoration/crash recovery remain unverified until the reference image
is built and the acceptance suite runs. This task has not achieved demonstrated
system-wide replacement on this workstation.

Use `scripts/device-diagnostics.py --serial SERIAL` for current evidence and
`scripts/run-system-tests.sh` on the built reference image for acceptance. The
latter fails on missing prerequisites and saves JPEGs, preview screenshot, video
luma transitions, route evidence and logs. See SYSTEM_IMAGE.md and TESTING.md.
