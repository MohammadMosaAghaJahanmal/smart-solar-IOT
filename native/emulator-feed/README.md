# Android Emulator camera transport proof

This isolated program calls the Android Emulator's existing **Offworld video
playback camera** protocol from the Android guest. It is a capability experiment,
not the AOSP routing integration and not a completed production backend.

Primary protocol sources:

- [Offworld schema](https://android.googlesource.com/platform/external/qemu/+/refs/heads/emu-master-dev/android/emu/protos/offworld.proto)
- [Pipe registration and dispatch](https://android.googlesource.com/platform/external/qemu/+/refs/heads/emu-master-dev/android/android-emu/android/offworld/OffworldPipe.cpp)
- [Actual camera playback renderer](https://android.googlesource.com/platform/external/qemu/+/refs/heads/emu-master-dev/android/android-emu/android/camera/camera-videoplayback-render-multiplexer.cpp)
- [Guest vsock pipe bootstrap](https://android.googlesource.com/device/generic/goldfish-opengl/+/759df6c11d9174fe09be4d6950edda26e3090661/shared/qemupipe/qemu_pipe_guest.cpp)

Launch a dedicated reference AVD with the `VideoPlayback` and `Offworld` features
and a `videoplayback` camera. This mode must be checked against the installed
emulator version. It must not be assumed to work from source alone.

`scripts/test-emulator-feed.ps1 -Serial emulator-5554 -Mode handshake` builds and
pushes the native probe. `-Mode play` uploads the existing red/blue MP4 fixture,
loads its real bytes, requests looping playback and requires asynchronous
acknowledgment from the host renderer. A camera consumer must already be open:
requests are processed by the camera's render loop. `-Mode default` requests the
backend's normal default frame.

Transport is AF_VSOCK host CID 2, port 5000, followed by the null-terminated pipe
service name `pipe:OffworldPipe`, then protocol-1 handshake. Subsequent messages
use the upstream protobuf schema with a little-endian uint32 frame length.
Only Load, Play(looping), and DisplayDefaultFrame are implemented. Errors and
timeouts fail; there are no successful response substitutes.

The protocol contains no camera selector. Dual-camera behavior, Android app
SELinux access, switching, image encoding, lifecycle recovery, real external
YUV/JPEG delivery and restoration require separate verification before this
could become an explicitly labeled emulator backend.
