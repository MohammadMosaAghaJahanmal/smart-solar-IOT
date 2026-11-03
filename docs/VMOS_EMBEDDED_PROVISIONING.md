# Embedded guest preparation and legacy handoff

`GuestProvisioningManager` runs inside the integrated broker process. Its caller
serializes operations with broker setup and obtains explicit setup approval.
Inspection never invokes `su`. Preparation first requires a real guest root
command returning UID 0 and the exact registered framework/provider profile.

The supported profile is the inspected Android 9 ARM64-only VMOS ROM10075 guest.
The physical phone manufacturer is not used as a selector. Unknown framework,
camera, importer or recovery bytes are preserved and reported as unsupported.
No operation changes the physical phone operating system.

Preparation installs the exact measured four-byte JPEG importer repair and the
compatibility symlink to the unchanged original ARM64 JPEG helper. It does not
install the rejected experimental JPEG range shim. The separate integrated
runtime owns camera transport/crop installation and actual color/frame proof.
Preparation alone never reports the camera pipeline as verified.

## Recovery

Private intent journals are atomically published before privileged changes. The
JPEG shell installer independently records the original importer hash, backup,
link and directory ownership. Its root-owned lock records the repair process PID
and start ticks. A live matching process keeps the lock. A verified dead owner
can be recovered only when the lock contains its recognized owner record and no
staging or unknown files.

If this application's incomplete intent journal, root repair journal and factory
camera HAL match, setup restores the interrupted repair before reinstalling it.
This also restarts the provider when process death occurred after bytes were
published but before restart. Ownerless older locks, unknown data, inaccessible
process identity, interrupted lock recovery and unrecognized staging files are
preserved with diagnostics. Automatic recovery does not delete those files.

## Legacy broker migration

Handoff requires the old broker's package signature to match, its UID/data path
to match the recorded installation, all installed HAL/transport/backup hashes to
match, and exact live proxy identity before any signal. A socket is transferable
only after the verified old process has stopped and its recorded socket is
demonstrably stale. The original legacy component states and installation record
are preserved in the integrated private journal.

The old boot receiver is disabled during handoff. Its conflicting launcher is
disabled only after the caller completes actual integrated camera verification.
The old APK and its private files are retained until external device acceptance.
After the integrated migration/regression checks passed, the separate legacy
broker package was uninstalled with its data retained and a PC recovery archive
preserved. Fresh Release-16 setup and camera tests then passed without that
package installed; see [UNIFIED_ACCEPTANCE.md](UNIFIED_ACCEPTANCE.md).
Rollback refuses a replaced or active integrated socket and restores the prior
component states. Explicit Restore returns the factory camera and does not
restart the legacy broker against a HAL it no longer owns.

## Isolated verification

- `scripts/test-root-access-manager.ps1`: actual root-manager assertion and
  directory handling regression tests, plus interrupted-preparation policy.
- `scripts/test-root-authorization.ps1`: adversarial request identity parsing.
- Under WSL/Linux, `python3 -m unittest tests/test_vmos_embedded_guest_preparation.py
  tests/test_vmos_guest_jpeg_repair.py -q`: real temporary filesystem operations,
  original ELF fixture hashes, rollback, live/unknown locks and SIGKILL recovery.

These checks do not invoke ADB, sign an APK, obtain root or prove a device's camera
pipeline. The separate development root signing scope is documented in
[VMOS_DEVELOPMENT_ROOT_AUTHORIZATION.md](VMOS_DEVELOPMENT_ROOT_AUTHORIZATION.md).

Actual Release-16 fresh preparation and restore/re-prepare results are recorded
separately in [UNIFIED_ACCEPTANCE.md](UNIFIED_ACCEPTANCE.md). JPEG preparation has
its own durable `jpegPrepared` checkpoint before the later bridge transaction;
an interrupted JPEG operation remains recoverable without making a completed
repair depend on the whole setup's completion flag. The native worker enters
through a UID-checked system shell, and restoration verifies the factory camera
before retiring its authenticated owned worker and clearing the foreground
notice. No test/diagnostic APK is required for the normal installation workflow.
