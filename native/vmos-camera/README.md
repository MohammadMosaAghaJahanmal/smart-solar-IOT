# VMOS Android 9 camera transport bridge

This native program is a backend component for one inspected VMOS guest. It is
not a general Android root method, a replacement phone Camera HAL, or an assertion
that VMOS is compatible with every Android phone. The physical phone's camera
service and partitions are outside its scope.

The inspected ARM64 guest HAL is `camera.vphw90.so`, SHA-256
`362261e9d1924bde73652cd8f2c467fbaabc59f50ad66e0b4de0215291ec6d3c`.
Its `QemuClient` connects an ordinary UNIX stream to
`/dev/socket/socket/qemu_pipe`. The separately guarded installer redirects the
camera HAL's one socket pathname to the proxy. This executable **does not patch
or install anything itself**.

Build from the repository root:

```powershell
.\scripts\build-vmos-camera.ps1
```

The result is `artifacts/vmos/vmos-camera-proxy`, an ARM64 Android API 28 ELF with
the C++ runtime statically linked. The program can run under an ordinary guest
application UID if that UID can execute the binary, connect to the original
socket and bind the replacement socket. It never attempts to obtain root.
**Running this proxy as an ordinary UID does not grant permission to patch the
HAL or restart a camera provider.** Those installation steps need independently
verified access. An installer must also ensure the actual camera process can
reach the new socket under the guest's SELinux policy.

## Observed protocol

1. HAL sends NUL-terminated `pipe:qemud:camera` for factory queries, or
   `pipe:qemud:camera:name=...` for a camera connection. The original service sends
   the literal two bytes `OK`.
2. Each command is NUL-terminated text. Each reply starts with an **eight-byte
   length header**. Standard Goldfish uses eight ASCII hex digits. The measured
   VMOS host instead formats `%04zx`, then transmits eight bytes: four or more
   hex digits, NUL, and unused padding. For example, the actual camera list
   starts `30 34 39 30 00 00 d9 b9` (hex), declaring 1168 payload bytes.
   The guest HAL parses the prefix using `strtol(base=16)`. This exact variant
   and standard eight-digit headers are supported; other malformed headers and
   lengths above 32 MiB are rejected before payload allocation. Forwarded
   headers, including unused padding, remain byte-for-byte unchanged.
3. `start dim=WIDTHxHEIGHT pix=FOURCC` establishes the requested output geometry
   and format after the original service has acknowledged it.
4. Standard `frame video=N preview=M ... time=1` returns `ok:`, then `N` video
   bytes, `M` preview bytes, then the original little-endian int64 camera
   timestamp. The timestamp is absent for `time=0`. The measured VMOS host
   returns one pixel slot, then a final NUL, with no transport timestamp even
   for `time=1`; its `queryFrameWapper` explicitly returns timestamp zero and
   leaves capture timing to the sensor thread. That frame variant is accepted
   only alongside the VMOS NUL-padded header. Mixed-slot VMOS frames and
   unmeasured trailers remain transparent.
5. This VMOS fork's `queryFrameWapper` requests **NV21 bytes in the preview slot**:
   `video=0 preview=WIDTH*HEIGHT*3/2`. Standard Goldfish RGB preview uses
   `WIDTH*HEIGHT*4`. Slot names therefore do not establish pixel formats.

Evidence is the inspected guest binary/disassembly under the ignored research
artifacts, particularly `CameraQemuClient::queryFrame`, `queryFrameWapper`, and
`QemuSensor::captureRGBA`. The analogous upstream implementation is
[Android Emulator camera service](https://android.googlesource.com/platform/external/qemu/+/cf0f40a0aad1aa3fca68f2f89372525c8d28ca68/android/android-emu/android/camera/camera-service.cpp),
but the NV21 preview wrapper was established from **this guest**, not assumed
from upstream.

The proxy always sends the real command to the original host first. It preserves
factory replies, errors, connection state, timestamps and NUL trailers. It replaces pixels
only for an acknowledged stream, supported format, live source lease, successful
`ok:` frame and exact expected payload size. Every unsupported or invalid case
keeps the original response. Unsupported streams are logged.

`vmos-camera-proxy --list-cameras` connects directly to the original socket,
sends only the factory `list` request and prints its result. It does not bind a
socket, write state, open a camera or patch a file. This can establish the guest
host camera names before an installer changes the HAL. A nondefault socket can
be selected with `--original PATH`.
The probe logs bounded protocol stages to stderr and includes the failed stage
in its error (connect, service write, handshake, list length or list payload).
Handshake bytes are printed only after both bytes were received. This separates
an unavailable host camera service from a command framing failure without
changing the protocol.

## Runtime and source contract

Run inside the guest under its owning application's UID or root, normally
supervised by a guest service:

```text
vmos-camera-proxy --listen /data/local/tmp/vcam_pipe \
  --original /dev/socket/socket/qemu_pipe \
  --state-dir /data/local/tmp/vcam-runtime
```

The state directory must be owned by the daemon UID, mode 0700, and not a symlink.
An ordinary application should prefer its private files directory for this state;
only the HAL's short socket pathname needs to be shared. The daemon holds an
exclusive lock there. A duplicate process exits before it can unlink the running
proxy's socket, including when given a different state directory. Android clients
are restricted using `SO_PEERCRED` to root, system, camera, cameraserver and the
proxy's own UID. The socket itself is mode 0666 so these camera processes can
reach it; guest SELinux still applies. A VM with non-sticky, world-writable parent
directories permits other guest processes to remove socket entries; an installer
must account for that guest limitation when establishing supervision.

The measured VMOS runtime rewrites pathname `stat` ownership to a guest UID
while descriptor `fstat` exposes the physical VMOS host app UID. Ownership checks
therefore compare a non-following pathname lookup with the opened descriptor:
the pathname must be owned by the current guest UID, and device, inode, mode,
type, hard-link count and size must match the descriptor. A descriptor UID
difference is accepted only on ARM64 Android 9 with `ro.hardware=vphw90`.
Config and raw file descriptor UIDs must also match the physical UID established
by the validated private runtime directory. `O_NOFOLLOW`, private directory
permissions and all regular-file/length checks still apply.

If `SO_PEERCRED` similarly exposes a physical UID, that UID alone grants no
access. On this exact guest, it must match the private directory's physical UID,
and the peer's `/proc/PID/status` must identify consistent virtual credentials.
Only an exact camera-provider or cameraserver command with an allowed camera
system UID, or the exact private proxy executable with the owning app UID, is
accepted. Unavailable or inconsistent peer identity fails closed. This fallback
does not grant host root or access to other guest applications.

Write a uniquely named, immutable raw file into the private directory. Its exact
length is `width*height*4*frames` for packed `RGBA` or
`width*height*3/2*frames` for planar `I420` (Y, then U, then V). The current limit
is 256 MiB, dimensions must be positive/even and at most 4096. Sources are mmap'd
read-only; never truncate or overwrite a mapped raw file. A producer must write
a new file and generation for changed media or transforms. The config and raw
files must be owned by the daemon UID, not writable by group/other, and have one
hard link. Raw files must be direct children of the state directory. Reads use a
pinned directory descriptor with `openat` and `O_NOFOLLOW`.

Activate or renew using an atomically renamed UTF-8, LF-delimited `source.cfg`:

```text
VCAM1
id=unique-generation-UUID
camera_name=camera_0
path=/data/local/tmp/vcam-runtime/unique-generation-UUID.raw
format=RGBA
width=640
height=480
fps=30
frames=1
loop=1
sha256=64-lowercase-hex-digits-identifying-the-selected-original-media
expires_ns=BOOT_TIME_CLOCK_DEADLINE_IN_NANOSECONDS
```

The deadline uses `CLOCK_BOOTTIME`, matching Android
`SystemClock.elapsedRealtimeNanos()`. It must be in the future and no more than
30 seconds ahead. A controller can renew a 15-second lease every two seconds
without resetting playback by keeping the same generation, file and settings.
Removing `source.cfg` deactivates atomically on the next frame. Missing, expired,
malformed, oversized, out-of-directory or symlink sources use the original
camera pixels. Source identity is metadata supplied by the controller; it does
not replace independent comparison of the selected media with captured frames.
`camera_name` is mandatory and must match the exact `name=...` returned by the
original VMOS camera factory. Only that camera's connection is replaced. Factory
connections and all other cameras remain transparent. This host name is not
necessarily the Camera2 public numeric ID, so an installer must verify their
mapping rather than assuming they are interchangeable.

The selected frame follows elapsed boot time and FPS. `loop=1` wraps modulo
frame count, `loop=0` holds the final frame. Transport timestamps remain untouched;
the VMOS variant continues to let the guest HAL assign actual capture timestamps.
The first implementation scales pixels
with nearest-neighbor sampling; the producer should pre-render fit/fill/rotation
and the intended output aspect ratio. I420 at the camera dimensions avoids an
RGB conversion round trip.

### Camera application crop requests

The guest camera3 HAL wrapper forwards standard `SCALER_CROP_REGION` changes to
a reserved local control service on the same proxy socket. It sends two
NUL-terminated strings together, then waits for the two literal bytes `OK`:

```text
vcam:crop:camera_N\0
crop left=L top=T width=W height=H active_width=AW active_height=AH epoch=E serial=S\0
```

`camera_N` is canonical decimal `camera_0` through `camera_31`. All eight fields
are unsigned integers. Active-array dimensions and crop coordinates are limited
to 65536; positive crop extents must stay within the active array. `left` and
`top` are relative to the active-array origin. A full-array rectangle resets
the crop. `epoch` is a positive `CLOCK_BOOTTIME` nanosecond token created when
the camera opens; `serial` increases for changed requests and the close reset.
Older epochs and non-increasing serials within an epoch are rejected, as are
epochs more than one second in the future. No acknowledgment is sent for an
invalid control request. Reserved `vcam:` services never reach the original host.

Only root, system, or cameraserver UIDs may send controls. On the fingerprinted
VMOS guest, a physical-UID mapping still requires the exact camera-provider or
cameraserver executable and consistent allowed virtual credentials. The proxy's
ordinary owning application UID cannot change another application's camera crop.

The proxy snapshots the selected camera's crop once per delivered frame, then
samples that rectangle before RGBA/I420/NV21 conversion. A request arriving
during a frame takes effect on the next frame. Camera names stay isolated; source
generation, video clock, transport timestamp and original error handling remain
unchanged. I420 crops resample the source Y/U/V planes directly.

This supports another camera application's own zoom or off-center crop requests.
It does not reinterpret a gesture that the application uses for focus, exposure
or navigation. On-device verification must establish that its gestures emit
standard crop requests and change actual captured pixels.

### Bounded live playback

An on-device video decoder should use `VCAM2` instead of decoding an entire
movie into one raw file. It keeps a stable generation and source hash, with
`format=RGBA`, `frames=1`, and a monotonically increasing `sequence` field.
The width, height, FPS, loop setting, camera name and original-media hash are
immutable within that generation. Its raw frame file contains a 16-byte header:
the eight ASCII bytes `VCAMFR2\n`, then the sequence as an unsigned 64-bit
big-endian value, then exactly `width*height*4` RGBA bytes.

The producer can alternate two fixed raw filenames under the private state
directory. It writes a complete temporary file, atomically renames it over the
next slot, then atomically publishes `source.cfg` with that path and sequence.
It must never truncate or overwrite an inode that the proxy may have mapped.
An increased sequence remaps the new file while preserving generation delivery
counts. An unchanged sequence retains its immutable mapping, so lease renewal
does not touch pixels. If a fast producer reuses a slot between configuration
read and file open, the inline sequence detects the race and that request keeps
the original camera response. Files with malformed or mismatched headers are
never rendered. This bounds the raw storage to two frames plus a write-in-progress
file and keeps camera timestamps independent of video playback timestamps.

`status.json` protocol 2 is replaced atomically, at most five times per second. It records
actual delivered `sourceFrames`, process-wide `overriddenFrames`, source ID/hash,
exact `cameraName`, `lastFrameBootNs`, `expiresBootNs`, actual camera dimensions
and pixel format, plus live frame `sequence` and `live` mode.
It is evidence of transport delivery, **not sufficient evidence of activation**.
The broker must require a live lease, fresh delivery for the requested generation,
and an independent Camera2 pixel/JPEG probe before reporting Active.

## Lifetime and recovery

Source failure is transparent while the proxy is alive. A crashed proxy cannot
accept the patched HAL's connections. A production installer must supervise and
restart it, or restore the original verified HAL and restart the guest camera
provider. Keep the original exact HAL and a recovery path before installing.
Never apply this pathname patch to a different HAL based only on its filename.

## Verification

`vmos-camera-proxy --selftest` exercises parsing, RGBA-to-NV21/I420 conversion,
finite/loop playback, exact frame length checks, the fork's NV21 preview wrapper,
expired leases and preservation of sensor timestamp bytes. It opens no camera or
listening socket and can be run under an ordinary guest UID before installation.

For the Linux socket integration suite, build the same source using a Linux C++17
compiler and run:

```sh
g++ -std=c++17 -O2 -Wall -Wextra -Werror -pthread \
  native/vmos-camera/vmos-camera-proxy.cpp -o /tmp/vmos-camera-proxy-test
python3 native/vmos-camera/test-proxy.py /tmp/vmos-camera-proxy-test
```

The thirty-seven socket tests verify original factory/errors/frame forwarding, the
read-only factory-list command, RGBA and I420
conversion, timestamp preservation, NV21 preview wrapping, lease expiry,
atomic source removal, malformed configs, symlink/nested-path rejection, writable
source/FIFO rejection, selected-camera isolation and duplicate-daemon protection across
state directories. The live playback tests cover alternating slots under a stable
generation, advancing delivery counts, header validation, sequence regression,
slot reuse and immutable media settings. The measured wire-variant tests cover
NUL-padded hex headers, preservation of padding and trailers, absence of transport
timestamps, rejection of malformed and oversized headers, and leaving unsupported
frame variants untouched. A crash-recovery test uses SIGKILL to leave an owned
stale socket and lock pathname, then verifies a replacement daemon can acquire
the released lock and forward a factory query. These tests are explicitly
protocol tests. Actual VMOS Camera2,
CameraX, saved JPEGs, video looping and restoration need separate on-device
acceptance before the backend can be called operational.
The crop cases additionally verify actual quadrant pixels under zoom and
off-center crops, full-array reset on an existing stream, direct I420 plane
resampling, live slot changes, camera isolation, malformed/stale controls and
rejection of an ordinary UID. The foreign-UID socket test requires Linux root
to drop a test child to UID 65534; it is explicitly skipped otherwise.

Crop rendering also applies Android's per-stream aspect rule: use the largest
rectangle with the output stream's aspect ratio, centered within the requested
sensor crop. It never independently stretches the crop axes. For the measured
1920x1088 active array, an uncropped 1920x1080/1280x720 preview uses sensor rows
4 through 1083; centered 2x uses `(480,274,960,540)`. Integer rounding stays
inside the requested rectangle. Both RGBA and direct I420 plane paths use this
same geometry. The default marker before any crop adapter metadata arrives
retains previous source scaling. See Android's
[SCALER_CROP_REGION contract](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#SCALER_CROP_REGION).

The separate development candidate is `artifacts/vmos/vmos-camera-proxy-crop-aspect`,
1,347,064 bytes, SHA-256
`1162c3ef0f3dc4a9b2ebb8fabf558dbbe31aead0ef4fa0c04897c5ca10721bcc`.
It has not replaced the canonical proxy or APK assets and needs guest acceptance.
The independent consumer `VmosCamera2CropTest` changes only public Camera2 crop
metadata while retaining one device/session, checks three settled pixel matches
at each centered/off-center/aspect-changing/reset step, and requires increasing
actual YUV timestamps. `prepareOnly=true` writes a distinctive static calibration
PNG without opening the camera; select that image before running the full test.
Its expected colors derive from the actual full-crop baseline, not controller
state, reported crop metadata, an overlay, or socket acknowledgements.
