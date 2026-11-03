# Device portability: adaptive VMOS setup

The 2.2 portability changes adapt setup to measured differences between hosts;
they do not establish universal Android 10–16 support. Camera replacement runs
inside a supported VMOS guest. Applications outside that guest continue to use
the physical Android camera backend.

## Current evidence

| Environment | Evidence |
| --- | --- |
| Infinix X687B, Android 10 host; VMOS `2.0.0-alpha13`, ROM `10075`, Android 9/API 28 ARM64 guest | Version `2.1-camera-modes` passed the recorded image/video, front/back/both, transform, color and recovery checks. See [baseline acceptance](CAMERA_MODES_ACCEPTANCE.md). Those results do not validate the new 2.2 candidate on this phone. |
| Samsung SM-N986U, Android 13 host; same VMOS version and measured guest camera binaries | Version `2.2-adaptive-vmos` passed 21 setup/policy/recovery checks, four mode/overlay/crash tests, eight held-open image/video transform tests and four independent camera-app crop tests. All four cameras were measured: two front and two back. A VMOS shutdown/start retained setup, permissions and mapping; subsequent activation verified all four cameras again. |
| Other Android 10–16 hosts | Compatibility target only. Host VMOS operation, camera passthrough, security restrictions and actual output require testing. |

The preserved 2.1 APK and its identity are recorded in
[`artifacts/portability/baseline-manifest.json`](../artifacts/portability/baseline-manifest.json).
A development candidate is not an accepted replacement merely because it builds
or recognizes the guest.

The [acceptance report](../artifacts/portability/final-acceptance.json) records
the exact installed APK and saved reports. The delivered version is
`2.2-adaptive-vmos`, version code `4`, SHA-256
`a9698d66ae235a066507bf89e3d34c2a9cf870f777ef2ffbcf9d4f8841bbacd6`.
The Samsung is left **Ready / Inactive** with test sources cleared. Select your
own media and activate it; other camera apps must run inside the same guest.

## What adapts automatically

- **Host installation and process identity:** the pinned VMOS launcher can be
  recognized in both `/data/app/package-token/…` and
  `/data/app/~~token/package-token/…` layouts. The observed guest
  `/system/bin/app_process64` process-executable alias is accepted only with the
  exact pinned launcher hash. Path matching never replaces byte, UID, process
  lifetime or request/socket identity checks.
- **Guest build labels:** VMOS can combine host product and build-user labels
  with the same guest framework. The profile accepts that label variation while
  retaining the Android 9/API 28, ARM64-only, `vphw90`, framework build and date
  restrictions. Exact known camera provider, HAL, JPEG and related component
  hashes remain required by provisioning. An unfamiliar ROM or binary is not
  patched by guessing its phone model.
- **Camera selection and sizes:** current camera metadata supplies actual IDs,
  facing, formats, dimensions and crop capabilities. Inactive saved selections
  adapt to usable mapped routes and advertised common YUV/JPEG sizes; a valid
  selection and its transforms are preserved. **Both Cameras** includes all
  mapped front and back routes, including multiple lenses with the same facing,
  at a resolution common to those routes. Missing common sizes produce an error
  rather than choosing an unsupported output.
- **Measured multi-lens mapping:** Camera2 IDs are opaque. During setup,
  calibration passes a known source through the real decoder, proxy and camera
  pipeline. Each opened Camera2 ID must deliver matching YUV/JPEG pixels,
  increasing timestamps and correct PRIVATE-preview-to-still colors. Exclusive
  fresh native-route frame counters identify which transport actually served
  that ID; neither list order nor numeric IDs select its route. Only a complete,
  distinct, facing-consistent map is published atomically after all cameras pass.
  The map is bound to the guest, kernel, actual camera factory, camera inventory
  and capabilities, and relevant component hashes. Changed or incomplete maps
  cannot keep setup **Ready** using old frame-verification evidence.
- **Sensor aspect and camera crop:** a stronger Samsung overlay fixture exposed
  an extra sensor-aspect crop being applied to an already rendered 16:9 source.
  The proxy now normalizes Camera2 `SCALER_CROP_REGION` relative to the uncropped
  stream window and the source's own aspect base. Neutral camera zoom preserves
  the complete rendered media; app-requested zoom and pan are then applied in
  that coordinate system. RGBA and I420 protocol tests cover 1920×1440 and
  1920×1088 active arrays. The [45-test native run](../artifacts/portability/native09-tests.log)
  passed 44 tests and skipped one requiring Linux root.
- **Diagnostics:** camera enumeration failures remain explicit errors. Camera
  runtime permission, read-only AppOps, overlay permission, execution ABI and
  security signals are inspected separately. Metadata access is not proof that
  opening a camera or delivering correct frames works.

On all four Samsung routes, candidate `09` setup measured calibration JPEG mean
absolute error of **7.05/255**, down from **22.90/255**, and preview-to-still error
of **13.67/255**, down from **23.94/255**. No color acceptance threshold was
relaxed. These are [actual setup measurements](../artifacts/portability/samsung/setup09-ready.json),
and are separate from the feature acceptance tests linked above.

## One-time setup and recovery

Initial guest repair requires explicit approval and real guest-root access
through the existing Superuser manager. The app persists approval for the exact
guest repair plan separately from camera-verification evidence. Healthy checks
do not need another root request. A private worker update alone does not change
the approved privileged repair plan.

With automatic recovery enabled, setup may repair that previously approved plan
only when its profile and current permissions still match, original recovery
material is available, host camera access is not reported disabled, and the
camera broker is idle. A second live idle check prevents repair from racing with
activation. Privileged work requires a fresh successful root command; a saved
root result cannot authorize it. The root manager retains control of its own
prompt and policy.

Failed automatic attempts are persisted for the observed environment, boot and
permission state so periodic checks do not continuously retry or prompt. An
explicit retry or relevant state change permits another attempt. Changed repair
plans need renewed approval; changed camera metadata, worker identity or
execution environment invalidates old frame-verification evidence. Explicit
**Restore** revokes repair approval so recovery does not undo the restore.

A missing private routing map can be recalibrated automatically while idle when
the approved guest setup and transport remain healthy. This frame verification
does not repeat root authorization or activate the user's selected media. On
the Samsung candidate `08`, controlled loss of the map caused fresh four-camera
verification and a new complete map, restored **Ready**, and left the root-check
timestamp unchanged. See the actual
[mapping-recovery report](../artifacts/portability/samsung/mapping-recovery08.json)
and [21-test result](../artifacts/portability/samsung/policy08-test.log).

The observed Samsung upgrade to candidate `09` reused the approved preparation
plan and completed automatic setup without repeating preparation consent or
changing the recorded root-authorization time. This does not override a root
manager policy that requires a prompt for a later privileged operation.

## Limits outside the guest app

The user must grant VMOS camera access on the physical phone, enable its Camera
option and enable root in the supported guest. A guest app cannot change those
host permissions, defeat the host camera privacy control, or make an unsupported
VMOS build run on a newer Android version. Enabling the VMOS Camera option may
require restarting the guest before camera passthrough becomes available.
On the Samsung, VMOS has also started a guest with camera passthrough disabled
while its saved Camera option showed On. This external startup discrepancy may
recur. The later [shutdown/start test](../artifacts/portability/samsung/cold-restart09.json)
passed without another camera toggle, permission grant or setup approval. The guest app reports the
disabled state rather than treating saved host settings as proof of access.

Host Android and VMOS package versions that the guest cannot authenticate are
reported as unknown. Shared-kernel signals are diagnostics, not an authenticated
host identity. Guest camera permission and AppOps cannot prove host camera
permission or privacy state; actual camera/frame verification remains required.
Overlay controls require their separate guest permission when used. Guest root
does not provide or require root on the physical phone.

An exact binary-profile match establishes eligibility for the measured repair,
not successful output on every host. Front/back/both replacement, image/video,
live transforms, color and recovery must still pass actual frame tests on each
new host. Android 10–16 support must not be advertised as universal before that
evidence exists.
