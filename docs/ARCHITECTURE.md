# Architecture and scope

## Why a modified system image

AOSP's virtual camera service implements Camera HAL3 and accepts an RGBA input
Surface from a producer. It already provides YUV and JPEG outputs, scaling,
camera metadata, capture sequencing and synchronization. Its public
VirtualDeviceManager path associates cameras with virtual devices; that alone
does not replace the stock Camera app's physical camera on the primary display.

This project uses the same AIDL service from a platform-signed broker, registering
a device-0 camera. The controlled image enables provider discovery regardless of
the virtual-device feature flag. CameraService resolves the selected primary
camera ID to the synthetic ID both for Camera2 characteristics/open/configuration
queries and for Camera1's numeric camera mapping. Permissions, sensor privacy and
AppOps continue through CameraService's usual checks.

The route is read at camera query/open time. Existing physical sessions are not
transparently swapped mid-capture. Close/reopen a client after each transition;
force-stop/relaunch clients that cache characteristics. No guarantee is made for
OEM camera apps requiring private vendor extensions, RAW, depth, flash, manual
sensor controls, reprocessing or concurrent camera combinations. The validation
target is AOSP Cuttlefish's installed Camera app plus the included public-API
Camera2/CameraX consumer. Tests on those targets remain required.

## Alternatives considered

| Approach | Decision |
| --- | --- |
| Normal APK / CameraX overlay | Cannot replace other applications' camera streams |
| Public virtual-device camera | Suitable for virtual-device consumers, insufficient for primary-display replacement |
| Root hooks | Version and OEM dependent; not a robust supported baseline |
| `CameraManager.injectCamera` | System-only alternative; the pinned CameraService opens the original camera before injection and retains its capability constraints. Explicit routing is used for the controlled reference product |
| New custom Camera HAL | Repeats AOSP's existing virtual HAL, JPEG, fences and metadata work |
| AOSP HAL + platform broker + framework routing | Selected; controlled build, real BufferQueue frames, explicit camera-ID routing |

## State and failure handling

`sys.vcam.route` contains `physicalId,syntheticId,expiresAtElapsedRealtimeMs` in a
single property write. The C++ parser accepts only a bounded future expiry and a
device-0 virtual camera ID. The broker renews a 5-second lease every second, only
while its EGL/codec scheduler is responsive. On failure it clears the route,
unregisters the camera and reports an engine error. The framework falls back to
the physical ID for new opens when a lease expires; an existing synthetic session
can report disconnect/error and needs reopening. The user-visible ongoing system
notification has a Deactivate action.

The virtual camera ID is `v0_pro`. A registration removes a previous orphan with
that ID so process death does not accumulate duplicate primary virtual cameras.
The permission patch authorizes the platform system UID only when the controlled
image version property is present. It does not grant virtual-camera control to
ordinary APKs. Binder access to the broker requires the known controller signing
certificate and the exact controller package. No camera permissions are granted
to consumers automatically by this routing mechanism.

## Native producer

An engine thread owns EGL, textures, MediaCodec and output Surface lifetimes.
ImageDecoder bounds allocation and applies EXIF orientation. Video MediaExtractor
and MediaCodec decode to an external-OES SurfaceTexture; playback uses source PTS
and a monotonic clock. On EOS, enabled looping seeks and flushes the decoder; when
disabled, the last texture remains available for subsequent requests. Rotation,
fit/letterbox and fill/crop are applied with GLES matrices. Frames get increasing
EGL presentation timestamps. Capture-request callbacks mark streams ready for
submission; this prevents filling an idle HAL input queue continuously.

During activation the broker itself temporarily opens the selected public camera
ID through Camera2. The patched framework must resolve that open to the synthetic
HAL. Three nonempty YUV frames, increasing timestamps, a decodable JPEG, native
producer frame advancement, and a matching framework routing acknowledgement are
required before reporting Active. The probe closes its session before returning.
When another client opens the same public ID afterward, it gets its own HAL session.

The broker's serial control executor owns activation and cleanup. A separate
callback executor attaches/removes HAL input Surfaces so an activation waiting for
frames cannot block its own callbacks. The main-thread heartbeat renews the lease
while the control executor runs the probe. Generation checks discard stale HAL
callbacks after teardown. Failed verification clears the lease, unregisters the
provider camera and closes the EGL/codec engine. A source change waits for delivery
of its new revision; if no consumer is open it runs a new verification session.

`dumpsys media.camera --virtual-camera-pro` exposes protocol 2 diagnostics under
the existing DUMP permission check. It reports initialization, provider discovery
and the result of the same routing helper used by camera opens. The build marker
alone is insufficient for support or activation. The probe demonstrates buffer
delivery, while the separate acceptance APK additionally compares fixture pixels
and video transitions; the original Camera test is a distinct requirement.

## Primary references

- [AOSP virtual camera design](https://android.googlesource.com/platform/frameworks/av/+/android-16.0.0_r1/services/camera/virtualcamera/README.md)
- [Pinned virtual camera AIDL](https://android.googlesource.com/platform/frameworks/av/+/android-16.0.0_r1/services/camera/virtualcamera/aidl/)
- [Pinned CameraService](https://android.googlesource.com/platform/frameworks/av/+/android-16.0.0_r1/services/camera/libcameraservice/CameraService.cpp)
- [Camera HAL and AIDL interfaces](https://source.android.com/docs/core/camera/camera3)
- [Cuttlefish setup](https://source.android.com/docs/devices/cuttlefish/get-started)
- [AOSP build host requirements](https://source.android.com/docs/setup/start/requirements)
