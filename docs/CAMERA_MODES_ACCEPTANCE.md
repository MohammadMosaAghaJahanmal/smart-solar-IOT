# Camera modes and floating controls: device acceptance

Recorded 2026-11-03 for version **2.1-camera-modes**, version code **3**.
The installed VMOS APK and [delivered APK](../artifacts/VirtualCameraPro.apk)
match SHA-256
`51d519ba99ec6df2128ede2de2d1be3f2b26c5f624363468c592e517b25b4347`
(38,369,814 bytes). This is a non-debuggable Release build with bundled
JavaScript and the integrated camera service; no separate broker APK is needed.
The existing development signing and guest trust scope still apply.

The measured environment is the connected Infinix X687B Android 10 host,
VMOS `2.0.0-alpha13`, ROM `10075`, Android 9/API 28 ARM64 guest, `vphw90`.
Other physical devices, guest profiles and newer Android hosts remain untested.
Camera replacement operates inside this supported guest.

## Actual device results

All **10 feature instrumentation tests passed** against the exact installed APK.
Tests used ordinary application UIDs and actual Camera2 frame buffers and JPEGs.

| Check | Measured result |
| --- | --- |
| Front only / back only | Selected route replaced; unselected route delivered its ordinary feed without incrementing replacement counters. |
| Both-camera image | Front/back/front switching preserved one source generation and proxy; both actual JPEGs reflected pan/zoom and reset. |
| Both-camera video | Image → video → image source changes passed; looping red/blue video reached both routes while front/back switching preserved the shared generation. |
| Floating controls | Real injected two-finger zoom in/out, pad drag, zoom buttons, reset, header movement, collapse/expand and close passed over Android Settings. Ten YUV/JPEG capture pairs checked both routes. |
| Held-open image, each camera | Four live pan/zoom positions changed real YUV frames with exactly one CameraDevice and one capture session per test. |
| Held-open video, each camera | The same four positions reached the encoded quadrant video output while its camera session stayed open. |
| Third-party Camera2 crop, each camera | Independent UID `10051` used only public `SCALER_CROP_REGION`; centered/off-center crops, aspect handling and reset passed without reopening its camera. |
| Both-camera process recovery | Resume off stayed inactive; resume on recovered after service and native-proxy crashes, requiring fresh proof and actual front/back/front color captures. |

The optional floating panel changed both actual output routes from 1× to about
2.09× zoom, then to horizontal/vertical position +0.5/+0.5. Reset restored the
original captured pixels. Moving or closing the panel preserved the camera
source and its generation. Android Settings retained keyboard focus beneath the
non-focusable panel. Overlay permission was checked through Android Settings;
the panel is shown only by an explicit user action.

Fresh preparation after the bundled proxy upgrade also passed normal app setup,
front/back YUV/JPEG verification, and the existing PRIVATE 1920×1080 preview →
1920×1088 YUV still color check. Both cameras had zero missing-chroma samples;
still-source mean absolute error was approximately 13.73/255.

The actual Image to Text → stock Camera shutter → Done flow saved a fresh
1088×1920 user-photo JPEG with normal colors. Its bytes match the earlier
accepted color-correct capture:
`c3e0ad15801e40e5ed58549014a375901915f7f559d4abab20f06dbd6d73cd5d`.
The original selected photograph was preserved.

## Automated and build checks

- Release APK and Release instrumentation APK assembled successfully.
- Native C++ compiled with `-Wall -Wextra -Werror`; 42 protocol tests ran,
  41 passed and one root-only test was skipped. Coverage includes concurrent
  clients, independent camera crops, atomic publication, shared video-ring
  generation/accounting, pass-through and malformed input/security checks.
- Nine pure Kotlin overlay tests passed, including gesture conflation, rate
  limiting, reset ordering, close races, worker-only phase reads and bounds.
- Thirty Jest tests in eight suites, TypeScript checking and ESLint passed.

The video device test exposed an atomic publication race: replacing a private
config/frame pathname between descriptor checks discarded valid generation
accounting. The proxy now retries those races with full ownership validation;
an unvalidated descriptor never supplies replacement pixels. A concurrent
publication stress test and the actual image/video device tests passed after
the correction.

## Evidence and final phone state

[Machine-readable acceptance](../artifacts/camera-modes/final-acceptance.json)
links the individual test reports. Evidence includes
[floating gestures and actual captures](../artifacts/camera-modes/overlay04d/report.json),
[image modes](../artifacts/camera-modes/device04/both-image-modes.json),
[video modes](../artifacts/camera-modes/device04/both-video-modes.json),
[both-camera crash recovery](../artifacts/camera-modes/crash-both-device04.json),
and the [fresh setup snapshot](../artifacts/camera-modes/setup04-ready.json).

The phone is left **Ready / Active**, with the user's selected photo replacing
both guest cameras at 1920×1080, rotation 90°, Fit, original 1× zoom and centered
position. The floating panel is hidden. Automatic transport recovery remains
enabled and automatic source resumption remains disabled. The native process
has all four UID fields equal to ordinary app UID `10048`.

The adapter and native proxy hashes are recorded with the evidence. The prior
[Release 16 acceptance](UNIFIED_ACCEPTANCE.md) and its
[original APK](../artifacts/camera-modes/baseline/VirtualCameraPro.apk) are
preserved separately.

Use [camera modes and controls](CAMERA_MODES_AND_CONTROLS.md) for permissions and
operation. A consuming app must submit real camera crop requests for its own
gestures to work. Both routes share one producer; opening two camera sessions
concurrently still depends on the guest's Camera2 capacity. Deactivate before
changing replacement mode, camera ID or output dimensions.
