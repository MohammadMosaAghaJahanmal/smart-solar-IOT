# Integrated Virtual Camera Pro acceptance

This is the preserved **Release 16 baseline**. The current delivered version and
its exact APK identity are in [camera modes acceptance](CAMERA_MODES_ACCEPTANCE.md).

Recorded 2026-11-03. **Release 16 passed the integrated device acceptance checks
on the Infinix/VMOS environment below.** The delivered APK is development-signed;
this acceptance does not establish a production signing arrangement or support
for untested phones. Earlier candidates remain identified separately.

The integrated application contains its own private VMOS camera broker. The
on-device release-16 checks used `com.virtualcamerapro` for both the
controller and broker. A separate broker APK is unnecessary for this backend.
First-use requirements and the supported environment are described in
[UNIFIED_INSTALLATION.md](UNIFIED_INSTALLATION.md).

## Delivered build

| Item | Status |
| --- | --- |
| APK | [Release 16 baseline APK](../artifacts/camera-modes/baseline/VirtualCameraPro.apk), 38,308,374 bytes |
| SHA-256 | `da6f476a4c7ecc0c1a3de53e3f9fd139f471d15ea720a8af46ddabccb208b5ca` |
| Build | Release, non-debuggable, JavaScript included; no Metro needed |
| Signing | Verified v3 development lineage; current development signer retained |
| Installed identity | Installed guest APK bytes match the delivered APK |
| Guest service | Private `:camera` process in the same APK and ordinary UID `10048`; legacy broker package uninstalled |
| Fresh preparation | Passed from empty app settings and original HAL/importer, with runtime permissions and preparation confirmation through the app UI |
| Restore/re-prepare | Passed; original HAL/importer restored, owned links removed, native proxy stopped and foreground notification removed; preparation created new verified backups |
| Final phone state | Environment Ready; user's portrait Active on front camera `1`, 1920 × 1080, rotation 90°, Fit; automatic recovery on, automatic resume off |

The [final device manifest](../artifacts/unified/final-device-acceptance.json)
binds the installed APK, component hashes and results. The
[package inspection](../artifacts/unified/release16-artifact-inspection.json)
records signing, manifest, native assets, ZIP alignment and source snapshot.
Signing preserves every ZIP-entry payload from the successful Release build.

## Release-16 device results

| Check | Actual result and evidence |
| --- | --- |
| First-use setup | [Fresh Ready snapshot](../artifacts/unified/fresh16-complete.json); default resume off; root check returned actual UID 0 |
| Preparation confirmation | [Actual guest-only confirmation](../artifacts/unified/fresh-backups16-consent.xml) before the second preparation |
| Both guest cameras | Back `0` and front `1`: increasing actual YUV timestamps and JPEG source MAE 7.20/255. PRIVATE 1920 × 1080 preview → 1920 × 1088 YUV still MAE 13.73/255; zero-chroma fraction 0 on both. [Current proof](../artifacts/unified/fresh-backups16-ready.json) |
| Private service, consent rejection, Stop/copy race, clear media and root command bounds | [Nine passing instrumentation tests](../artifacts/unified/integrated-release16-regression.txt) |
| Setup must clear resume intent | Active media with resume enabled was interrupted by real setup verification; it remained inactive for 12 seconds afterward. [Report](../artifacts/unified/setup-clears-resume16.json) |
| Back image/video and live transforms | Actual image switching, looped video, deactivation and one held-open Camera2 session across pan/zoom. [Media](../artifacts/unified/back-acceptance16.json), [transforms](../artifacts/unified/back-transform16.json) |
| Front 1080p image/video | Actual image switching, looped video and deactivation. [Report](../artifacts/unified/front-1080-acceptance16.json) |
| Independent camera-app crops | Consumer UID `10051` used only standard `SCALER_CROP_REGION`; centered/off-center crops and full-view restoration passed without reopening the camera, with a [back image](../artifacts/unified/back-image-independent-crop16.json) and [front video](../artifacts/unified/front-video-independent-crop16.json) |
| Process crashes | Real `:camera` SIGKILL with resume off stayed inactive; opt-in resume returned only after fresh Camera2 proof. Native-proxy SIGKILL also recovered with a new proxy and new verified source generation. [Report](../artifacts/unified/crash-recovery-release16.json) |
| Full VMOS shutdown/start | Guest zygote changed `4900` → `15770`, proxy `10250` → `17102`; broker/proxy recovered before opening the controller; source stayed off. [Boot state](../artifacts/unified/guest-restart16-after-boot.txt), [actual independent YUV/JPEG capture](../artifacts/unified/guest-restart16-actual-camera.txt) |
| Healthy launch | Main controls opened directly with Ready and Inactive after boot. [UI](../artifacts/unified/healthy-after-restart16.xml) |
| User's OCR capture | Activated through the real main-screen button, then captured with the image-to-text app's stock camera. Preview and post-shutter confirmation had normal colors. The freshly saved 1088 × 1920 JPEG exactly matches the earlier accepted color-correct JPEG bytes. [Report](../artifacts/unified/ocr-capture16-acceptance.json) |
| Final background operation | Ordinary-UID foreground camera service remained alive while OCR consumed the selected portrait; native overridden frames increased beyond the activation probe. [State](../artifacts/unified/ocr16-background-state.txt), [final Ready/Active](../artifacts/unified/final-ready-active16.json) |

The image/video runners report three back tests and two front tests; each count
includes a conditionally assumed finite-only branch. The JSON reports identify
the actual image/video and transform checks that ran. Instrumentation exits can
terminate their target package, so final background/OCR acceptance activated
through the actual app UI and used no controller-target instrumentation while
the other camera app was running.

Local validation also passed TypeScript, ESLint, 21 Jest tests in seven suites,
42 root-request identity cases, two root-manager bounds cases, six guest recovery
cases and 25 Linux installer tests. Earlier interactive root Deny/Allow and
legacy migration evidence remains scoped to its recorded candidate below.

## Tested environment and trust scope

| Item | Observed value |
| --- | --- |
| Physical host | Infinix X687B, Android 10 |
| VM application | VMOS `2.0.0-alpha13`, guest ROM `10075` |
| Guest | Android 9/API 28, ARM64-only, hardware `vphw90`, SELinux reported Disabled |
| Guest framework | `PQ3A.190801.002` / `eng.builds.20191015.144423` |
| Profile | `vmos-android9-arm64-rom10075`, version 1 |
| Guest cameras | Back `0`, front `1`; capabilities obtained from actual guest Camera2 metadata |
| App identity during tests | Package `com.virtualcamerapro`, ordinary guest UID `10048` |
| Other physical hosts or Android 11–16 | **Untested** |
| Other VMOS ROMs, architectures or changed camera binaries | **Not validated** by this result |
| Stock camera applications outside VMOS | No supported replacement backend established by this VMOS integration |

The development authorization APK uses APK Signature Scheme v3 lineage from the
guest's public platform test certificate to the existing development signer. It
deliberately receives matching guest signature permissions. It is **not a
production signing arrangement** and does not provide root on the physical
phone. The delivered non-debuggable build retains this development trust
limitation.

- Current signer SHA-256:
  `fac61745dc0903786fb9ede62a962b399f7348f0bb6f899b8332667591033b9c`.
- Guest platform ancestor SHA-256:
  `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`.

The exact mechanism and its guards are documented in
[VMOS_DEVELOPMENT_ROOT_AUTHORIZATION.md](VMOS_DEVELOPMENT_ROOT_AUTHORIZATION.md).
The application asks the original guest Superuser manager to authorize its real
request; it does not write root-policy grants. The original manager can reuse
its own remembered policy or configured automatic response.

## Candidate-specific identity

| Candidate | APK SHA-256 | Evidence covered here |
| --- | --- | --- |
| 07 | `4b58cca37df8b38fc90ca770e0d83250a2445546759f04122128ad2525c8a982` | Real interactive root denial/approval and anonymous-pipe isolation |
| 10 | `71eb5ce214ef59659ed9460ea8469883e237bd28ef160409ef64aaf6c94f4ba1` | Integrated migration/Ready, private broker identity, both-camera color verification, image/video, live transforms, independent crop, stop and clear-selection |
| 12 | `84ab947e201bc0f1818de25792cdf52e3f6197bf7fc976e3a37a11fbc0be19cb` | Explicit in-app restoration and recorded restored state |
| Final delivery, Release 16 | `da6f476a4c7ecc0c1a3de53e3f9fd139f471d15ea720a8af46ddabccb208b5ca` | Independently accepted; current delivery table and device manifest above |

Signing records:
[candidate 07](../artifacts/unified/development/candidate-07/development-signing-report.json),
[candidate 10](../artifacts/unified/development/candidate-10/development-signing-report.json),
[candidate 12](../artifacts/unified/development/candidate-12/development-signing-report.json).
Their signing-time `installed` and verification fields describe what the signing
script knew at creation; the later device reports supply execution evidence.
Candidate 06 was rejected as a stale build and supplies no acceptance evidence.

## Measured integrated-device results

| Check | Actual observation | Saved evidence |
| --- | --- | --- |
| Original Superuser consent, candidate 07 | With the missing `app_process32` alias still absent, Prompt → Deny left `ROOT_MANAGER_AUTH_REQUIRED`, Ready false and preparation unavailable. Prompt → remembered Allow returned actual guest UID 0 and made preparation available; camera Ready remained false. Settings/policy selection used the original manager UI. | [Root-only acceptance](../artifacts/unified/root-authorization-acceptance.json) |
| Root request pipe boundary, candidate 07 | A different ordinary guest UID `10051` was denied native read/write and Java opens at both tested anonymous-pipe descriptor endpoints. This test does not establish isolation from trusted root or same-UID code. | [Pipe probe](../artifacts/unified/root-anonymous-pipe-privacy.json) |
| Controller and private broker, candidate 10 | Controller and broker had UID `10048`, different PIDs and a non-exported service; broker package was `com.virtualcamerapro`. | [Broker identity](../artifacts/unified/unified-broker-identity10.json) |
| Setup proof and missing-consent rejection, candidate 10 | Ready was true with both cameras `[0, 1]` verified. The regression also rejected absent permission consent. | [Setup regression](../artifacts/unified/unified-setup-regression10.json), [Ready snapshot](../artifacts/unified/integrated-ready10.json) |
| Actual back and front calibration, candidate 10 | Each camera delivered 11 YUV preview frames at 1280 × 720 with increasing timestamps and a real HAL JPEG at 1280 × 720. Calibration JPEG sample MAE was 7.1997 for each camera. | [Full production verification](../artifacts/unified/integrated-ready10.json) |
| Green-capture regression, candidate 10 | Each camera used an actual PRIVATE preview at 1920 × 1080 followed by a YUV still at 1920 × 1088. U/V row stride was 1920, pixel stride 2, zero-chroma fraction 0 and calibration sample MAE 13.7274. Actual plane strides were read independently. This YUV color measurement is separate from the HAL-JPEG check above. | [Still-color observations](../artifacts/unified/integrated-ready10.json) |
| Back image/video at 720p, candidate 10 | Independent Camera2 capture observed red image, blue image after switching and looping red/blue video. Deactivation returned live original-camera frames. | [Back acceptance](../artifacts/unified/back-acceptance10.json), [test output](../artifacts/unified/back-image-video-transform10.txt) |
| Front image/video at 1080p, candidate 10 | Independent Camera2 capture observed both image colors and looping video with five stable color transitions. Deactivation returned original-camera frames. | [Front acceptance](../artifacts/unified/front-1080-acceptance10.json), [test output](../artifacts/unified/front-1080-image-video10.txt) |
| Live source pan/zoom, candidate 10 | Four off-center 2× source transforms changed measured quadrant pixels while the consuming camera stayed open; observed update latency was 304–559 ms. | [Live transform report](../artifacts/unified/back-transform10.json) |
| Other-app standard crop, candidate 10 | Independent Camera2 consumer UID `10051` submitted only public `SCALER_CROP_REGION` requests. It measured five crop windows including full-frame restoration over 29 frames, one camera open and one session. Three stable matching frames were required per window; crop update latency was 135–181 ms. | [Independent front crop](../artifacts/unified/front-independent-crop10.json), [test output](../artifacts/unified/front-independent-crop10.txt) |
| Stop wins over an unfinished activation, candidate 10 | A real Binder source-copy operation was blocked, Stop was requested with auto-resume enabled, activation did not return Active, and 57 stopped-state samples remained stopped. | [Cancellation report](../artifacts/unified/unified-deactivate-race10.json) |
| Clear selection, candidate 10 | Both controller and broker private selected-media copies were removed, with 57 stopped-state samples. The regression verifies the gallery original remains intact. | [Clear-selection report](../artifacts/unified/unified-clear-selection10.json), [eight-test regression output](../artifacts/unified/integrated-device-regression10.txt) |
| Restore original guest camera, candidate 12 | Explicit in-app confirmation restored the exact original HAL, removed the app-owned root launcher alias and cleared camera verification. Ready became false and setup showed the remaining root/bridge/crop/verification steps. The pre-existing JPEG repair was retained because it was not owned by this app. | [Restore confirmation](../artifacts/unified/restore12-consent.xml), [restored state](../artifacts/unified/restore12-result.json) |

The back/front instrumentation outputs include tests that are conditionally
assumed for the unselected test mode. Their `OK (3 tests)` and `OK (2 tests)`
counts must not be read as three and two independent full image/video runs. The
saved camera-specific JSON reports identify the executions and actual pixels.
The integrated setup/recovery group reports `OK (8 tests)`.

Camera-app gestures work when that app submits standard camera crop requests.
The measured off-center crop support enables panning where an app requests it;
a tap or drag used by an app for focus does not become a pan gesture
automatically. The earlier stock-camera pinch and OCR saved-photo results remain
documented as **two-app baseline** evidence in
[VMOS_RUNTIME.md](VMOS_RUNTIME.md); they are not relabeled as final integrated-APK
tests here.

## Camera components observed in the accepted candidate-10 setup

These hashes identify the measured profile. They are not permission to patch an
unknown guest binary.

| Component | SHA-256 |
| --- | --- |
| Original guest camera HAL | `362261e9d1924bde73652cd8f2c467fbaabc59f50ad66e0b4de0215291ec6d3c` |
| Installed crop camera HAL | `9d13ca05e5963c9486f34435b8c96baf755ebdbca0112c382e7f2c103d717157` |
| Green-resize-fixed transport HAL | `02042c56980813c89611698d6501ccd78ca05c8a9827a55aa2c21e634bd5086a` |
| Camera provider | `fe58ac53d066187a0fd499a38354101ccff9cc0d3379164bd9f36b5b631d7e87` |
| Original JPEG importer | `be8cbff7d20ec7f9e8ea16645ca919196e174911bb7cfcfbcac458acaa8b6acc` |
| Repaired JPEG importer | `b572750c30143af55a8b8ec4bc1a60017cac3ffec512deb5edffb054877817ab` |
| Original encoder / compatibility link target | `0975abc8d3eec3f57bd607458101f69a11f0a7c93b2de97094cfab534175c2e3` |
| Bundled native proxy | `1162c3ef0f3dc4a9b2ebb8fabf558dbbe31aead0ef4fa0c04897c5ca10721bcc` |

The resize fix retains the original encoder. An experimental color-range shim
is not part of this accepted profile.

## Fixture provenance and final state

After the candidate-12 app restoration, a separate audited test-fixture operation
restored the older, pre-existing JPEG repair to the original importer. The saved
[fixture state](../artifacts/unified/factory-fixture-state12.txt) records the
original HAL `362261e9…`, original importer `be8cbff7…` and absent repair link.
This fixture reset is **not** counted as automatic app provisioning, and the app
correctly preserved that unowned repair during its own restoration.

Release 16 then prepared the guest from empty application settings and the exact
original camera files. A second app Restore returned both original hashes and
removed its own compatibility/launcher links and native worker. For the next
backup-creation test, older original-file recovery copies were preserved under
`.pre-fresh16` names; the app created new exact recovery copies itself before
installing its repairs. [Fixture provenance](../artifacts/unified/fresh-backups16-fixture.txt)
and [new backups](../artifacts/unified/newly-created-backups16.txt) distinguish
test preparation from the automatic installation. Normal supported installation
requires no ADB, copied system files or separate installer APK.

Fresh tests caught and fixed a JPEG-phase completion checkpoint error and a VMOS
native-launch UID problem. The final launcher verifies the ordinary app UID
through the system shell before exec; process identity guards still verify all
four UID fields, argv, launcher/executable hashes and start time. Restoration
retires only an authenticated owned worker after factory camera verification.

The user's portrait was restored and activated through the actual app UI. The
main screen was left at Ready/Active with automatic resume off. The root
manager's original Automatic Response=Allow preference was restored through its
UI after the temporary Prompt consent tests; app code never edits root-policy
databases or selects a consent response.

No Android 10–16 universal-support or 100% reliability claim follows from this
single guest/host test. Other host versions and guest profiles require their own
measured compatibility results.
