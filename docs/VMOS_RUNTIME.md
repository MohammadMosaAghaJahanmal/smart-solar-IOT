# Measured VMOS guest runtime

The inspected VMOS guest now delivers selected images and looping video to a
separate Camera2 consumer at 1280 × 720 and 1920 × 1080 on the connected Infinix
X687B. Both guest camera IDs passed the 720p red image → blue image → looping
video → ordinary-camera restoration sequence; camera 0 also passed at 1080p.
The two guest JPEG repairs below also establish ordinary Camera2
YUV preview and decodable JPEG capture. These results cover this measured guest
profile; the physical phone's camera API remains outside this backend.

The stock guest Camera app's image-to-text capture also passed after the
[scaled-NV21 green-photo repair](#stock-camera-green-photo-repair): the matching
independent still test and the actual OCR app's saved photo now retain color.

## Supported profile

| Component | Measured version |
| --- | --- |
| Physical phone | Infinix X687B, Android 10; physical camera libraries unchanged |
| VMOS host | `com.vmos.virtual`, `2.0.0-alpha13`, version code `20013` |
| Host base APK SHA-256 | `dcf31019813ddccbc9bafc0230d174b40436be5d5eb1b3a453a81d76b808ea85` |
| Guest | ROM `10075`, Android 9 / API 28, `vphw90`, ARM64-only |
| Guest framework build | `PQ3A.190801.002`, `eng.builds.20191015.144423` |
| Guest SELinux | Disabled in this inspected ROM |

Host identity and original module evidence are recorded in
[`artifacts/vmos/research/inventory.json`](../artifacts/vmos/research/inventory.json).
The guest build fingerprint inherits the physical phone's model prefix, so the
repair checks its framework-build suffix plus exact module hashes rather than
treating the model name as firmware identity. The guest utility cannot inspect
the outer host APK; verify that version separately before using this profile.
There is no compatibility claim for other VMOS ROMs, physical firmware, or
Android versions. Apps using the guest camera must run inside this guest.

## Enable the actual VMOS camera transport

Grant Camera permission to VMOS on the physical phone. Before starting the guest,
open its VMOS Settings and let the Camera setting finish loading. If necessary,
toggle Camera off, wait for the guest shutdown, then enable it and start the guest
from that same VMOS process. Avoid force-stopping VMOS between loading that
setting and starting the guest.

In this host build, a fresh host process can start the guest before its Camera
setting observer has supplied a value. The UI can then show the saved Camera
toggle enabled while the running guest has `vmos.camera.enable=0`. A full guest
restart after loading Settings produced `vmos.camera.enable=1`, a successful real
camera-factory handshake, and two Camera2 cameras. A screenshot of the enabled
toggle alone is insufficient evidence. The guest service's **Check guest camera
transport** action must reach the real factory and list actual cameras.

The measured factory protocol is documented in
[`native/vmos-camera/README.md`](../native/vmos-camera/README.md). The raw header
capture is [`artifacts/vmos/camera-wire-probe.txt`](../artifacts/vmos/camera-wire-probe.txt).

## Startup and recovery supervision

An installed guest HAL still points at the broker's proxy while selected media
is deactivated. The guest service therefore keeps foreground supervision running
after Deactivate, allowing the ordinary guest camera to pass through. Successful
setup and the setup transport check start that supervision. Restoring the original
guest HAL stops it.

The broker listens for `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`. It starts only
when the configured installation record, exact patched HAL and preserved original
backup all verify inside the supported guest. Startup restores the proxy's real
factory connection. If Camera2 exposes no IDs, recovery independently verifies
guest root and the pinned provider process before restarting that provider and
waiting for actual Camera2 IDs. It does not patch any file during recovery.

Every ten seconds the service checks the actual proxy transport. Idle recovery,
activation and setup are serialized. While selected media is active, checks only
probe the existing transport; they never silently restart it under old proof.
A lost active proxy clears the source lease and activation proof. Later recovery
can restore ordinary pass-through, but selected media requires a fresh activation
and independent frame verification. Service/process restart also clears previous
source leases rather than reviving a dead process's selection.

Recovery failures appear in the foreground notification and broker status, with
a link back to guest setup.

A full guest shutdown followed by the Settings-loading startup procedure was
tested successfully. Before opening either app, the boot receiver had started
the foreground broker and a new proxy PID `20226`; CameraService exposed two
cameras. Native status had `configured:false`, empty source identity and zero
source frames, confirming that previous media was not revived. The ordinary
Camera2 YUV/JPEG pipeline then passed. Evidence:
[`cold-boot-recovery-state.txt`](../artifacts/vmos/cold-boot-recovery-state.txt) and
[`cold-boot-camera-pipeline.log`](../artifacts/vmos/cold-boot-camera-pipeline.log).

The final crop-adapter configuration was also cold-started after the current
runtime APKs were installed. A new proxy PID `27016` appeared with two guest
cameras and `configured:false`, zero frames and an empty source identity.
[`final-before-cold-start.txt`](../artifacts/vmos/final-before-cold-start.txt)
pins the installed adapter/repaired transport, and
[`final-cold-start.txt`](../artifacts/vmos/final-cold-start.txt) records the new
process and camera inventory. Fresh activation and all five front-camera pixel
crop/reset steps then passed in 2.148 s; see
[`cold-start-crop-test/crop-result.json`](../artifacts/vmos/cold-start-crop-test/crop-result.json).
Selected media requires fresh activation after every guest restart.

### Watchdog acceptance procedure

Daemon-death recovery is a separate check from full guest startup. Use an
independently verified guest-root maintenance session, with no current camera
client and selected media deactivated:

1. Record broker foreground-service state, the configured installation record,
   installed HAL/recovery-copy hashes and native status. Confirm the proxy is
   idle (`configured:false`, no live source lease).
2. Identify exactly one daemon using the PID from native status, the broker UID,
   and its complete command line: the private `vmos-camera-proxy` executable,
   `--listen /data/local/tmp/vcam_pipe`, the original VMOS socket, and the broker's
   private `camera-runtime` directory. Verify the executable digest against the
   bundled native digest. Do not use a process-name-wide kill.
3. Send `SIGKILL` only to that verified guest daemon PID. Leave the stale socket
   and lock file in place, and do not reopen either app. Allow the ten-second
   watchdog cycle plus bounded factory/provider handshakes; record the result
   within one minute rather than waiting indefinitely.
4. Require a different native PID and an actual successful camera-factory query,
   two Camera2 IDs, continued foreground supervision, unchanged installed HAL and
   backup hashes, and no revived source identity. A provider restart is allowed
   only if Camera2 was empty and its exact process identity was verified.
5. Run the ordinary `CameraFrameProbeTest` again and require increasing actual
   YUV timestamps plus a decodable JPEG. A new process or listening socket alone
   does not establish recovery. Save broker/proxy logs and the independent test
   result; report a failed check instead of marking the virtual source Active.

The offline socket suite includes a `SIGKILL` regression that leaves the socket
and lock pathname behind, then verifies a replacement daemon can acquire the
released lock, replace the owned disconnected socket and forward a real fixture
factory query. Existing tests also verify a live listener cannot be displaced.
That Linux fixture test does not replace this guest watchdog acceptance.

## Guest JPEG repair

The original guest could deliver 640 × 480 YUV frames but rejected JPEG BLOB
buffers during import: the Mali mapper expected image stride `640`, while the
byte-buffer allocation used stride `546458`. The importer already passed the
BLOB allocation capacity as width and `1` as height, but supplied the original
image stride as its final stride argument.

The exact-build candidate changes one AArch64 load in the BLOB-only branch of
`/vendor/lib64/camera.device@3.2-impl.so`: file offset `0x15d0c`, virtual address
`0x16d0c`, `27 63 40 b9` → `e7 2f 40 b9`. This loads the already computed BLOB
allocation width from `sp+44` instead of the image stride from `x25+96`.
The non-BLOB argument branch and the host's buffer-validation call remain intact.
Disassembly is retained in
[`artifacts/vmos/jpeg-stride-prepared/stride-disassembly.txt`](../artifacts/vmos/jpeg-stride-prepared/stride-disassembly.txt).

The ARM64 camera HAL also requested its existing ARM64 JPEG encoder using the
missing `/vendor/lib/hw/camera.vphw90.jpeg.so` pathname. The repair creates only
that previously absent symlink to the verified existing
`/vendor/lib64/hw/camera.vphw90.jpeg.so`. It replaces neither encoder nor camera
HAL, and changes no global gralloc policy.

| Pinned guest file | SHA-256 |
| --- | --- |
| Original camera importer | `be8cbff7d20ec7f9e8ea16645ca919196e174911bb7cfcfbcac458acaa8b6acc` |
| Repaired camera importer | `b572750c30143af55a8b8ec4bc1a60017cac3ffec512deb5edffb054877817ab` |
| Original camera HAL | `362261e9d1924bde73652cd8f2c467fbaabc59f50ad66e0b4de0215291ec6d3c` |
| Existing ARM64 JPEG helper | `0975abc8d3eec3f57bd607458101f69a11f0a7c93b2de97094cfab534175c2e3` |
| Camera provider executable | `fe58ac53d066187a0fd499a38354101ccff9cc0d3379164bd9f36b5b631d7e87` |

### Prepare, install, inspect, restore

Prepare the importer candidate on the PC using the pulled, original module:

```powershell
python scripts/prepare-vmos-jpeg-repair.py --importer artifacts/vmos/research/camera-device-3.2-runtime.so --out-dir artifacts/vmos/jpeg-stride-prepared
```

This is offline preparation. The manifest's `prepared_uninstalled_unverified`
status describes that operation, even if a separately recorded device test later
passes. The optional `--camera-hal` output is an alternate offline candidate;
it was not the deployed repair. The tested repair uses the compatibility symlink
and retains the original HAL bytes.

The maintenance utility
[`scripts/vmos-guest-jpeg-repair.sh`](../scripts/vmos-guest-jpeg-repair.sh) is for an
already independently verified **guest UID 0** shell. It obtains no root and
invokes no ADB. Transfer the script and exact importer candidate into the guest,
then use these commands **inside the guest**:

```sh
sh /data/local/tmp/vmos-guest-jpeg-repair.sh inspect
sh /data/local/tmp/vmos-guest-jpeg-repair.sh install /data/local/tmp/vcam-jpeg-stride.so
sh /data/local/tmp/vmos-guest-jpeg-repair.sh restore
```

Before either mutation it verifies the guest build, original HAL, encoder,
provider executable, importer identity, regular-file ownership, recovery copy,
and any existing compatibility pathname. A private lock serializes this utility.
The original importer is preserved with its ownership and permissions at
`/vendor/lib64/.vcam-camera-device-3.2-original-be8cbff7.so`; retain a matching
copy outside the guest as well. A journal records whether the compatibility
directories were created. The provider restart must produce a different PID
whose command line is the exact pinned provider path, within 15 seconds.

Restore accepts only the exact known original/repaired importer and a validated
installation journal. It preserves the backup, restores its ownership and
permissions, removes only the exact repair-created symlink, archives the journal
under a unique name, and removes recorded new directories only if empty. It also
recognizes the exact JSON journal from the initial measured developer install;
that older journal lacks directory-creation evidence, so those directories are
preserved. Unknown binaries, files, symlinks, or recovery state cause refusal.

If an install fails after publishing the importer, the utility attempts to put
the verified original back and retains its journal. `restore` can finish such an
interrupted recovery, including when the original bytes are already restored.
If the broker's separate camera-socket redirect is installed, first use the guest
service's **Restore original guest camera** action. The JPEG utility intentionally
requires the original camera HAL for installation or restoration. Restoring the
broker HAL does not itself undo this separately recorded JPEG repair.

These are maintenance tools; the current broker setup does not automatically
apply this external JPEG repair. After installation, run real Camera2 preview
and JPEG acceptance tests before setting up or activating selected-media routing.
The measured manual installation is device-verified by the baseline and routing
results. The formal utility's install/rollback paths are offline-verified; they
have not been exercised on the phone solely to repeat that installation.

## Stock-camera green-photo repair

The image-to-text app `com.ea.image.text` 1.43 (code 18) uses the stock guest
`com.android.camera2` capture activity. On camera 1 its SurfaceTexture preview
repeated at 1920 × 1080, while a shutter request added one 1920 × 1088 YUV
ImageReader frame. Preview looked normal; the captured photo became neon green.

The exact HAL resize path had already allocated and filled a scaled NV21 target,
but checked its output copies against the smaller original camera-reply length.
It copied Y and rejected UV, leaving both chroma planes zero. Three AArch64
length loads now use the actual scaled allocation. All original copy branches,
source handling, formats, gralloc validation and timestamp behavior remain.
[Disassembly, instruction offsets and bounds proof](../native/vmos-camera/nv21-scale-repair.md)
describe this separate repair. `prepare-vmos-camera.py` remains the original
socket-only preparation tool; `prepare-vmos-nv21-scale-repair.py` adds this exact
three-instruction transform. Neither offline tool installs a guest file.

| Guest HAL state | SHA-256 |
| --- | --- |
| True original recovery HAL | `362261e9d1924bde73652cd8f2c467fbaabc59f50ad66e0b4de0215291ec6d3c` |
| Older socket-only transport | `1c80cb9711b08d28625e140263b6bd85aadaf2c6a1de50d161e19fc6e1773a97` |
| Repaired socket transport, device verified | `02042c56980813c89611698d6501ccd78ca05c8a9827a55aa2c21e634bd5086a` |
| Repaired original candidate, offline only | `fa79aee82358025a8cb3f776a511417184d39eb45716170f639aa21581a30cf3` |

The updated broker's **Install guest camera bridge** action also upgrades the
exact older configured transport. It journals the previous installation before
replacement, retains original ownership/mode/label and the true original
recovery copy, restarts only the verified guest provider, and checks real
Camera2 YUV/JPEG before committing configuration. Unknown HAL changes prevent
installation or rollback. Fresh setup applies both the socket redirect and
the scale repair. Restore returns to the true original HAL, not to the repaired
original candidate.

An independent matching preview-only-repeat → YUV-still test failed before the
repair with U/V means `0/0` and all chroma near zero. It passed afterward with
U/V `125.496/131.872` and no near-zero chroma. All six live/copied stock-JNI JPEGs
for rotations 0/1/3 passed the test's declared color threshold. The actual stock
shutter diagnostic independently changed from sampled U/V `0/0` to
`125.843/131.062`, with the same Y mean. The capture confirmation and saved
1088 × 1920 OCR JPEG were visually checked and have normal colors. JPEG is
lossy; these results do not claim pixel-perfect color equality.

The temporary JPEG-JNI observer was removed and the original stock JNI restored
to SHA-256 `6050a81c4eae572989b284bc55531a853efe83512b5413b7bffe00fb308c2bd5`.
The diagnostic wrapper is not required for the repair to operate.

Evidence and the frozen tested broker APK are pinned in
[`green-fix-acceptance.json`](../artifacts/vmos/green-fix-acceptance.json).
`VirtualCameraProVmosBroker-scale-candidate.apk` is 3,959,294 bytes, SHA-256
`df1cc3f2700bc2a30041183d7dc7b5b3aa30e438305673d0e367f5dbec98c428`.
This acceptance covers the reproduced front-camera still path in the supported
profile above. The separate optional crop/zoom adapter, other VMOS ROMs and
additional phones are not established by this result.

The standalone guarded maintenance tool
[`vmos-guest-nv21-scale-repair.sh`](../scripts/vmos-guest-nv21-scale-repair.sh)
has 20 passing offline recovery tests and accepts only the two exact source/repair
pairs. It leaves the broker installation record untouched; the broker setup
action is the integrated path for an existing configured app. The standalone
tool was not separately exercised on the phone to duplicate the successful
broker-managed installation.

## Standard camera-app crop and pinch zoom

The optional **Install guest adapter for camera app zoom** setup action adds the
exact Android 9 camera3 adapter and a crop-aware proxy. It forwards the original
camera module/device ABI while observing public `SCALER_CROP_REGION` metadata;
the proxy applies that sensor crop and the output stream's aspect ratio to the
selected pixels. Guest installation retains the original recovery copy and the
repaired transport, journals before replacement, and verifies real YUV/JPEG after
the pinned provider restart. See the
[adapter implementation and controls](../native/vmos-camera/hal-wrapper/README.md).

Device acceptance passed at 1280 × 720 with a static calibration image on
cameras 0 and 1 and a looping calibration video on camera 1. One CameraDevice
and one capture session remained open in each run through centered
2×, top-left 2×, bottom-right 2×, tall different-aspect and restored-full crops.
Each phase required three matching actual YUV buffers and increasing timestamps.
Maximum channel MAE was `0.3704` for the bounded back-image test and `1.1254`
for the front-video test, under the unchanged `14` limit. Their three-frame
settled proof took 118–198 ms per update. Diagnostics now sample a 320 × 180
grid with independent actual-plane addressing, close Image before RGB
conversion, and compare 117 points per frame. Saved PNGs explicitly identify
their sampled resolution; capture resolution remains 1280 × 720. The initial
front-image test's roughly 4.4-second proof included expensive full-frame Kotlin
diagnostics, not a measured camera-control deadline. The three-second, 15 fps
video fixture retains a gradient and blinking corner; broader changing-scene
video accuracy is not established by that fixture.

The controller's source zoom/pan was verified separately in one held independent
Camera2 session: four 2× quadrant changes produced the expected actual pixels
in 297–348 ms. The final adapter also passed Camera2 and CameraX preview/JPEG
image checks; two opt-in video checks were skipped. The crop report records
these checks separately from camera-app crop metadata and gesture acceptance.

The stock Camera application's two-pointer pinch was also verified with visible
selected-image changes and CameraService metadata. Pinch-in produced native crop
`[858, 486, 204, 116]`; pinch-out restored `[0, 0, 1920, 1088]`. Full → magnified
→ full screenshots and passing gesture-driver logs are pinned in
[`crop-acceptance.json`](../artifacts/vmos/crop-acceptance.json). Input injection
alone is not treated as functional zoom evidence. Earlier tests that failed
before crop submission or ran while the guest was hidden are excluded.

Apps must send standard camera crop metadata. Independent off-center tests prove
FREEFORM crop behavior for apps that request it; a one-finger gesture the app
interprets as focus does not automatically pan the selected source. The control
protocol carries latest crop state rather than a per-frame queue, so acceptance
waits for settled output. These results cover the recorded back-image and
front-image/video cases in the exact supported guest. Final canonical packaging
and installed runtime hashes passed verification; the identity report is
[`final-installed-device-identity.json`](../artifacts/vmos/final-installed-device-identity.json).
After cold startup, the user's portrait at 1920 × 1080, fit, rotation 90° on
camera 1 also passed the multistream/six-JPEG test and an actual stock-camera
shutter/save with normal colors. The original stock JPEG JNI library is restored
and the controller was left Active with the portrait. Other phones, Android
versions and VMOS ROMs remain unverified.

## Recorded acceptance

The connected guest produced these results after the importer repair and helper
symlink, with the original camera HAL still installed:

| Check | Recorded result |
| --- | --- |
| JPEG-only 640 × 480 | Delivered image; Android bitmap decoder returned the expected dimensions |
| Combined YUV/JPEG 640 × 480 | Nine preview frames with increasing timestamps and a delivered JPEG |
| Original `CameraFrameProbeTest` | Passed its 1280 × 720 YUV/JPEG capture and real JPEG decode |

Evidence:
[`baseline-jpeg-repaired.json`](../artifacts/vmos/baseline-jpeg-repaired.json),
[`baseline-combined-repaired.json`](../artifacts/vmos/baseline-combined-repaired.json),
[`repaired-jpeg640-baseline.log`](../artifacts/vmos/repaired-jpeg640-baseline.log),
[`repaired-combined-baseline.log`](../artifacts/vmos/repaired-combined-baseline.log).
The tests verify image buffers, timestamps and JPEG decoding; they do not count
a private producer frame as proof of selected-media delivery to another app.

Selected-media acceptance is separately recorded in
[`acceptance-camera0/acceptance.json`](../artifacts/vmos/acceptance-camera0/acceptance.json)
and
[`acceptance-camera1/acceptance.json`](../artifacts/vmos/acceptance-camera1/acceptance.json).
Both report `passed: true` and `stage: complete`. The controller and broker run
under different application UIDs. Each run verifies actual independent YUV/JPEG
capture for red and blue images, observes six stable color transitions in a
looping two-second video, and compares the post-deactivation JPEG with the
ordinary-camera baseline. This verifies guest camera 0 and 1 at 720p.

Camera 0 also passed the same sequence at 1920 × 1080:
[`acceptance-1080p/acceptance.json`](../artifacts/vmos/acceptance-1080p/acceptance.json)
records 99 observed video frames, six stable color transitions and restoration
mean absolute error `1.49` relative to its baseline. The instrumentation run is
[`vmos-1080p-end-to-end.log`](../artifacts/vmos/vmos-1080p-end-to-end.log).

With the selected image active and the controller in the background, the
separate client application's Camera2 and CameraX preview/JPEG checks both
passed. Those checks require actual preview frames, decode the actual JPEG and
compare its center pixel with the selected solid-color fixture. Evidence:
[`background-external-image-camera2-camerax.log`](../artifacts/vmos/background-external-image-camera2-camerax.log).
That earlier run covers the included independent camera client. The later
green-photo acceptance above additionally verifies the stock camera launched by
Image to Text, with the selected front-camera source at 1920 × 1080. Other
third-party apps still require their own acceptance.

Offline verification:

```sh
python3 -m unittest discover -s tests -p 'test*vmos*jpeg*repair.py' -v
```

Six tests verify the exact binary transformation and preserved backup outputs.
Ten further tests run the production shell logic in an isolated Linux/WSL mock
guest: real file replacement, permissions, symlinks, journals and refusal paths,
with only Android identity/process tools and the provider fixture digest mocked.
No offline test modifies a phone. The formal maintenance script still needs its
own device acceptance before being included in an automated setup flow.
