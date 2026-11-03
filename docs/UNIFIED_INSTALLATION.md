# Virtual Camera Pro: one-APK VMOS installation

Install [VirtualCameraPro.apk](../artifacts/VirtualCameraPro.apk), the single
integrated application. Version 2.2 passed actual frame, color, front/back/both,
image/video, floating-control, crop and recovery checks on the Samsung/VMOS
environment below. [Portability and exact acceptance](DEVICE_PORTABILITY.md)
describe the adaptive setup and its limits. The [Infinix 2.1 baseline](CAMERA_MODES_ACCEPTANCE.md)
and [Release 16 setup/restore baseline](UNIFIED_ACCEPTANCE.md) are preserved.
This build is development-signed for the measured guest.

## Requirements and compatibility

Install Virtual Camera Pro **inside the VMOS guest**, together with the camera
applications that will consume its image or video. The guest camera integration
does not replace the camera seen by applications outside VMOS.

| Item | Current scope |
| --- | --- |
| Validated integration profile | VMOS `2.0.0-alpha13`, guest ROM `10075`, Android 9/API 28, ARM64-only, `vphw90`, framework build `PQ3A.190801.002`, dated `20191015.144423`; matching component hashes are required despite host-dependent build labels |
| Guest camera files | Exact recognized provider, HAL, JPEG importer and encoder bytes; versioned profiles retain these guards |
| Guest security mode | The inspected profile uses guest SELinux `Disabled`; the installer refuses an unvalidated security configuration |
| Observed physical hosts | Samsung SM-N986U / Android 13: version 2.2 tested on all four guest cameras. Infinix X687B / Android 10: preserved version 2.1 acceptance; version 2.2 not retested there |
| Other Android 10 phones | Not validated by the Infinix result |
| Other Android 11–16 hosts | Untested beyond the Samsung Android 13 result; VMOS availability, permissions, camera passthrough and background operation must be established on each host |
| Other guest ROMs/architectures | No validated provisioning adapter currently supplied; unknown components are diagnosed and preserved |
| Root | Actual root inside the supported guest; physical-phone root is unnecessary for this integration |

The app inspects camera IDs, facing, advertised sizes/formats, orientation,
zoom/crop metadata, permissions, guest properties and component hashes. The phone
manufacturer is not the patch selector. Guest profiles pin the system paths and
binaries they have actually validated. Host Android and VMOS package information
that cannot be authenticated from inside the guest is reported as unknown.

### Development root authorization build

The inspected ROM has a root-permission launcher defect: its daemon expects a
missing `app_process32`. The optional development APK uses an explicit signing
lineage from the guest's public platform test certificate to the existing app
debug certificate. This deliberately adds guest signature privileges; it is not
a production signing arrangement. Its platform ancestry is limited to the
matching public-test-key guest, whose certificate SHA-256 is
`c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`.

That build can open the original Superuser consent screen only for an authentic
pending request from this app, after checking the registered guest, actual
signature permission grants, pinned Superuser APK/`su` binaries and the request's
process/socket identity. The original root manager decides whether to prompt,
reuse an existing app policy or apply its own configured automatic response. For
a new interactive request, approve **Allow** in that manager. The application
does not change those policies or select the response. Opening the screen alone
does not establish root or satisfy the UID-0 check. Unknown
signing keys or changed root-manager binaries disable this bridge. It supplies no
physical-phone root and performs no automatic root-policy grant.

Use the delivered APK identified by the [device manifest](../artifacts/camera-modes/final-acceptance.json). Development candidates
are named `VirtualCameraPro-VmosDevelopment-RootAuthorization.apk` and kept under
`artifacts/unified/development/`; their existence does not mean they have passed
acceptance or replaced `artifacts/VirtualCameraPro.apk`. See
[VMOS_DEVELOPMENT_ROOT_AUTHORIZATION.md](VMOS_DEVELOPMENT_ROOT_AUTHORIZATION.md)
for the trust scope and signing workflow. No private signing key is included in
the APK.

## First use

1. On the physical phone, grant **Camera** permission to VMOS. Open VMOS Settings
   and enable its **Camera** option **before starting the guest**. On the tested
   host build, load Settings in the same VMOS process that starts the guest. If
   camera access was enabled after startup, restart the guest; the guest app
   cannot change host permissions or repair that host startup condition.
2. Enable root inside the supported VMOS guest. Install the selected single
   Virtual Camera Pro APK inside that guest and open it. No separate broker,
   installer or zoom-adapter APK is required for the integrated backend.
3. Follow only the missing steps shown by the setup assistant. Grant the app's
   guest **Camera** permission and, when this ROM declares it, its runtime
   **Superuser** permission. These runtime grants are separate from root-manager
   approval.
4. Choose **Check guest root** when requested. Approve the real guest Superuser
   prompt if it appears. The manager can reuse a prior policy or its existing
   automatic-response preference; the app does not override either. Setup
   continues only after a normal `su` command actually returns
   guest UID 0. If authorization is denied or its launcher is unavailable, use
   the precise diagnostic and **Check again**. Guest preparation still requires
   its own explicit confirmation, even when root was already authorized.
5. Choose **Prepare guest camera** or **Repair guest camera**, review the
   guest-only modification explanation and approve it. The app reuses recognized
   healthy components, backs up originals and applies the missing compatible
   JPEG, transport and crop preparations. The verified guest camera provider may
   restart. Keep the application available for any permission prompts.
6. Allow real camera verification to finish. Preparation runs verification; an
   already prepared guest can instead offer **Verify camera**. Verification
   stops any active source and checks every available uniquely mapped guest
   camera using actual decoded calibration frames, YUV/JPEG delivery and a
   PRIVATE-preview-to-YUV-still color check. A failed check leaves setup incomplete.
7. Once the current checks establish **Ready**, open the camera controls. Select
   an image or video, choose **Front Camera Only**, **Back Camera Only** or,
   when supported, **Replace Both Cameras**, choose an advertised resolution and
   press **Activate**. Wait for **Active**, then reopen the consuming camera app inside
   the same guest. Activation requires fresh source identity, actual camera
   frames, increasing timestamps and matching JPEG pixels.

The document picker supplies media access, and the app keeps a private copy for
background use. The guest broker currently rejects sources over 256 MiB. The
original gallery file is not modified. If upgrading an existing installation,
keep its data and backups: approved migration validates the matching legacy
broker before handing ownership to the integrated service. Legacy modules and
recovery copies remain available during validation.

## Everyday use and recovery

Healthy launches inspect the current environment and reuse verification that
matches the current profile, bundled components and camera topology. A cached
completion flag cannot override missing permissions or failed transport health.
Android and VMOS can revoke permissions or stop background work; the application
must recheck those conditions rather than promise permanent access.

| Preference/control | Behavior |
| --- | --- |
| Automatic recovery | Enabled by default. Supervises an already configured bridge, with bounded backoff between retries, when Android permits. It does not authorize new system-file installation. |
| Resume my active camera | Disabled by default. Explicit opt-in allows saved, previously active media to be resumed only after fresh camera verification. A stored request is never stored Active proof. |
| Deactivate | Clears the active request and source lease. A deliberately deactivated camera stays off, including when resume is enabled. An installed bridge can retain foreground supervision for ordinary-camera passthrough. |
| Clear selection | Deactivates and removes the application's selected-media state and private broker copy; it leaves the gallery original intact. |
| Guest restart | Recovery can restore an already validated transport. With the default resume setting, select/retain the source and activate it again. Host Camera must still be available. |

Existing green-photo behavior is preserved by the measured NV21 resize repair in
the transport HAL (`02042c56…`), together with the JPEG importer repair
(`b572750c…`) and original encoder (`0975abc8…`) compatibility link. The integrated
setup does not substitute an experimental JPEG color-range shim for those fixes.
Unknown JPEG compatibility libraries are left untouched and reported unsupported.

Camera apps can zoom or move the selected view when they submit standard crop
requests. Off-center crop requests support panning; a gesture that an app uses
for focus does not become a pan gesture automatically. Source zoom/pan controls
in Virtual Camera Pro are also retained. The documented baseline acceptance and
its exact limitations are in [VMOS_RUNTIME.md](VMOS_RUNTIME.md#standard-camera-app-crop-and-pinch-zoom).

The current controls also offer **Replace Both Cameras** with shared playback
and common output sizes. Deactivate before switching replacement mode, camera or
resolution. For camera apps without suitable crop controls, **Reframe in other
apps** offers an optional floating controller: explicitly grant the guest app's
**Display over other apps** permission, return and choose **Show Floating
Controller**. Its pad, zoom buttons, arrows and Reset adjust the actual source
frames; dragging its header moves only the panel. Permission alone does not
activate a camera or open the controller. See
[camera modes and controls](CAMERA_MODES_AND_CONTROLS.md) for full steps and the
verification status of these additions; the Release 16 measurements below are
the earlier accepted baseline.

## Diagnostics and restoration

Use **Diagnostics** for guest capabilities, current service/transport state,
component versions, failed steps and corrective actions. **Export sanitized
diagnostics** opens Android's sharing interface with the filtered JSON report.
The export removes private selected-media references and sensitive identifiers;
review it in the chosen sharing application before sending it. Opening
Diagnostics alone sends nothing.

Root diagnostics retain the last explicit authorization attempt and its actual
start/completion times across health checks and app restarts. That historical
report is not restored as current root authorization or camera readiness.

**Restore original guest camera** requires separate confirmation and actual guest
root. It stops the virtual source, restores the exact original camera HAL and
restores baseline changes recorded as installed by this application, preserving
the recovery copies. Previously existing repairs are not silently claimed as
app-owned changes. Interrupted operations retain journals for inspection/retry;
unknown bytes or invalid backups cause refusal. Restoration does not delete the
gallery original or modify the physical host. Virtual-camera use afterward needs
the missing setup steps again. Do not remove backups or clear app data while a
guest camera installation still depends on them.

## Integrated architecture

| Component | Responsibility |
| --- | --- |
| React Native UI and `VirtualCameraModule` | Conditional setup, media controls, runtime permission requests and worker-thread native calls |
| `EnvironmentDetector` / `CompatibilityRegistry` | Runtime capability inventory and versioned, hash-guarded adapter selection |
| `UnifiedSetupService` / `SetupOrchestrator` | Private same-UID Binder in `:camera`; persistent asynchronous preparation, verification, health inspection and restoration |
| `RootAccessManager` / development authorization bridge | Explicit guest root checks, authentic consent UI where eligible and fixed internal privileged operations |
| `GuestProvisioningManager` / `GuestRuntime` | Compatible repairs, original backups, journals, legacy handoff, native proxy and pinned provider recovery |
| Integrated `BrokerService` | Private `:camera` service, foreground frame delivery, verified activation, media persistence and recovery |
| Shared native frame engine / proxy / camera3 adapter | Image/video decode, transforms, real camera transport, measured color repair and standard camera-app crop handling |
| `SetupRepository` / `BrokerSessionStore` | Separate setup evidence/preferences and saved media requests; neither persists an Active assertion |
| `ExternalBackendInspector` | Retains existing trusted emulator/AOSP protocol-2 integrations without requesting VMOS guest root or provisioning |

One APK contains both UI and guest broker functionality; separate internal
processes and bundled native files provide the necessary boundaries. Existing
emulator/AOSP backends retain their own external platform integration. This
VMOS consolidation does not turn those runtimes into a one-APK stock-phone
deployment.

## Recorded verification

The [acceptance report](UNIFIED_ACCEPTANCE.md) and
[device manifest](../artifacts/unified/final-device-acceptance.json) bind Release
16 to its installed APK hash, guest profile, component hashes and saved test
results. The following results cover that measured environment.

| Check | Evidence/status |
| --- | --- |
| Image/video, front/back, background delivery and deactivation | Passed on Release 16; front 1080p and back 720p media switching were measured, including normal-UI background operation with OCR |
| Green-photo repair and standard crop behavior | Both-camera PRIVATE-preview/still color checks passed; the fresh OCR saved JPEG matches accepted color-correct bytes. Independent back-image/front-video crop requests passed |
| Unified APK packaging/build | Release, non-debuggable, bundled JavaScript and native assets; installed bytes match the delivered APK |
| Missing-consent rejection and private broker identity | Passed in the nine-test Release instrumentation run; broker uses the same ordinary UID in its private process |
| Authentic request parsing/root authorization | Offline tests are provided by `scripts/test-root-authorization.ps1`; these do not prove live permission grants or guest UID 0 |
| Exact-guest interactive root bootstrap and anonymous pipe isolation | Candidate 07 passed authentic Prompt → Deny and Prompt → remembered Allow/actual UID 0 with the missing launcher alias still absent; a different ordinary guest UID could not open either tested pipe endpoint. [Root-only acceptance](../artifacts/unified/root-authorization-acceptance.json); camera setup acceptance is separate |
| Fresh preparation and original-file backups | Passed from empty app settings and original camera files, with runtime permission grants, actual UID-0 root check and preparation confirmation through the app. New backups were created during re-preparation |
| Both-camera YUV/JPEG and PRIVATE-preview-to-still colors | Passed: JPEG source MAE 7.20/255, still-source MAE 13.73/255 and zero-chroma fraction 0 on each camera |
| Setup persistence | Healthy main-screen launch passed after full VMOS restart, without repeating the wizard |
| Crash recovery and resume preference | Real service/native crashes passed. Resume off stayed inactive; opt-in resume required new actual Camera2 proof. Setup and deliberate Stop cleared resume intent |
| Restore/re-prepare | Original HAL/importer restored, owned links removed, native worker stopped and foreground notice cleared; preparing again returned Ready |
| Physical host Android 11, 12, 13, 14, 15 and 16 | Untested; no universal compatibility claim |

The runtime itself shows **Ready** only after its required current checks pass,
and **Active** only after selected-media frame verification. Those device states
were observed and saved for the delivered APK. The app continues to perform its
current health and activation checks during ordinary use.
