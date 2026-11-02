# VMOS Android 9 camera3 crop adapter

This development adapter is restricted to the measured ARM64 Android 9 `vphw90` guest. It is not a physical-phone camera HAL. The standard camera3 request path reads `ANDROID_SCALER_CROP_REGION` so a camera app's zoom works when that app actually submits crop metadata. Touch events that an app handles for focus or its own interface do not imply a camera crop request.

The public headers under `include/` are unmodified AOSP `android-9.0.0_r1` headers. Their original copyright/license comments are retained. [upstream-sources.json](upstream-sources.json) records the exact primary-source URLs. Both active-array and crop metadata rectangles contain `(left, top, width, height)`, following the native camera metadata ABI.

## Pinned layout

* Main loaded HAL: `/vendor/lib64/hw/camera.vphw90.so`, the built adapter.
* Preserved transport HAL: `/vendor/lib64/hw/.vcam-transport-camera.vphw90.so`, exactly SHA-256 `02042c56980813c89611698d6501ccd78ca05c8a9827a55aa2c21e634bd5086a`, 2,109,016 bytes. This includes the measured NV21 scaled-copy chroma repair. The older socket-only `1c80cb97…` transport is retained as historical provenance, not loaded by this wrapper.
* Preserved original: `/vendor/lib64/hw/.vcam-original-362261e9d1924bde73652cd8f2c467fbaabc59f50ad66e0b4de0215291ec6d3c`.

The adapter checks guest properties and the transport file's hash, size, regular-file type, nonsymlink path and nonwritable group/other mode before loading it. The actual transport exports camera module API **2.3** (`0x0203`) with HAL API `0x0100`; the provider service's version 2.4 is a separate interface. The wrapper advertises the measured module version and retains the real module's absent torch/init callbacks. It forwards module methods to the real module and every camera3 operation to the real camera device, leaving the real private data unchanged. Optional NULL callbacks remain NULL. Unsupported device/ABI layouts fail closed. The wrapper has a distinct `libvcam_camera_crop.so` SONAME and exports only `HMI`.

## Control and request behavior

Each open sends a full crop with a new positive BOOTTIME-nanosecond epoch. A changed crop opens a fresh bounded 100 ms Unix connection to `/data/local/tmp/vcam_pipe`. It writes `vcam:crop:camera_N` plus NUL, then this command plus NUL:

```text
crop left=L top=T width=W height=H active_width=AW active_height=AH epoch=E serial=S
```

The reply must be exactly `OK`. Coordinates are positive-extent sensor pixels normalized by subtracting the active-array origin; camera IDs are canonical decimal 0–31. Crop/active bounds and metadata type/count are checked before forwarding the request. Null settings reuse the last accepted crop.

The crop ACK precedes the underlying request, so that request cannot begin rendering before its crop is applied. If the original HAL rejects the request, the prior crop is restored and its original error returned. Failed control ACKs return `-ENODEV` before transferring buffer ownership. Close always attempts a full-crop reset. Unchanged requests avoid IPC; replacement of the listening socket inode triggers republishing after daemon restart.

The control protocol carries the latest crop, not a per-frame queue. Frames already in flight when a crop changes may reflect the latest crop. Device acceptance waits for settled frames and checks actual pixels. On the inspected Infinix/VMOS guest, independent 720p Camera2 tests passed five crop/reset steps with back-image and front-image/video sources, and stock-camera pinch changed both visible selected pixels and crop metadata. The bounded back-image/front-video tests obtained three settled matching frames in 118–198 ms per update. See [crop-acceptance.json](../../../artifacts/vmos/crop-acceptance.json) for the exact cases and limits.

## Build, tests and guarded setup

```powershell
./scripts/build-vmos-hal-wrapper.ps1
./scripts/test-vmos-hal-wrapper.ps1
cd android
./gradlew.bat :vmos-broker:assembleDebug
```

The canonical proxy includes crop and output-aspect handling; `-PvcamProxyBinary=...` remains available for isolated candidate builds. The wrapper is bundled with an independently generated SHA-256 asset. `GuestRuntime.installCropAdapter()` is an explicit setup operation under profile `vmos-camera3-crop-v1`; setup retains the original backup and repaired transport profile, journals attributes and hashes before the atomic replacement, restarts only the pinned guest provider and verifies real YUV/JPEG delivery. The preserved transport is separately recorded and installed with mode `0644`; the main HAL retains its measured original attributes. Failure restores the prior working transport profile and verifies its camera pipeline. Explicit restore also supports interrupted prepared/restoring upgrades and retains its journal through final provider/pixel verification.

The explicit upgrade also replaces a known old proxy automatically, while its source is inactive. A status-file PID alone never authorizes shutdown: setup checks the broker-owned private directory and socket, exact full native argv, all four broker UIDs, process start ticks, and the direct known executable or pinned VMOS launcher plus known private binary history. It rechecks that identity before TERM and any bounded KILL escalation. The previous pinned binary is retained; an upgrade failure restores it, restarts the provider and checks actual final camera delivery. A private deployment record prevents repeated restarts of the same verified candidate. Unknown identities are refused.

Use the guest setup activity's **Install guest adapter for camera app zoom** action. Installation itself does not mark crop pixel acceptance as verified. The separate recorded tests establish the measured Camera2/stock-camera cases; each additional app, Camera1 zoom path or guest build needs its own actual capture checks.

The host test compiles against the actual public C structs and checks all callback forwarding pointers/return values, null methods, crop origin/bounds/type/count validation, null-setting reuse, request failure rollback, control failure ownership, socket restart republish, exact NUL framing, fragmented ACK, a stalled connection deadline, LP64 ABI sizes and three independent SHA-256 vectors. AddressSanitizer and UndefinedBehaviorSanitizer are enabled by the test script.

The final canonical runtime APKs were installed with matching on-device hashes.
After a full guest restart, fresh activation and the five-step front-camera
crop/reset test passed again, followed by normal user-portrait multistream and
actual stock-camera JPEG capture. [Final installed identities](../../../artifacts/vmos/final-installed-device-identity.json)
and the appended crop/green reports record this acceptance for the measured guest.
