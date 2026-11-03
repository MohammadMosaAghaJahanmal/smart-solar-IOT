# VMOS development root authorization candidate

This opt-in development build tests a one-APK workaround for a defect in the
inspected VMOS Android 9 ARM64 root environment. It is separate from the canonical
`artifacts/VirtualCameraPro.apk` and must pass device acceptance before promotion.

The original root daemon launches its permission UI through a missing
`/system/bin/app_process32`. A private copy of the `su` client cannot repair the
already running daemon. The built-in Superuser request activity is also private
and protected by a signature permission.

## Development signing scope

The candidate uses APK Signature Scheme V3 with an explicit signing lineage:

`VMOS guest public platform test certificate → existing application debug certificate`

The current certificate remains the existing debug certificate, so the package
name, ordinary application UID and existing current-signer comparisons can remain
unchanged. Platform ancestry deliberately gives the APK access to the signature
permissions it requests in this public-test-key guest. This is broader trust than
the ordinary debug APK. It is not a production signing key or a general Android
root mechanism. No private signing key is packaged inside the APK.

This candidate is V3-only and requires Android 9/API 28 or later for signature
verification. It does not support unrelated VMOS signing keys, grant root on a
physical phone, change the physical phone operating system, or establish host
Android 10–16 compatibility. Custom lineage capability flags are not presented as
a mitigation: Android versions before 12 have a documented shared-signer
capability bug, and no such mitigation has been device-validated here.

## Normal root consent remains required

The authorization bridge runs only after the user requests a root check. It
requires the exact registered guest framework/provider, original `su` and
Superuser APK hashes, plus actual `REQUEST_SUPERUSER` and `START_ANY_ACTIVITY`
grants. It only opens the original root manager's pending consent activity.
It never creates an authorization socket, writes a root policy, or selects Allow.
The original manager retains authority over an existing per-app policy and its
global automatic-response preference. An already allowed policy or a manager
configured to allow automatically can return root without an interactive tap;
the application does not enable that preference or override it. A completed
normal `su` command returning actual UID 0 is still mandatory. Explicit guest
preparation confirmation remains separate from root authorization.

### Anonymous pipe request binding

A real cross-UID test disproved ordinary private-file isolation in this VMOS
build: UID 10051 could open UID 10048's `0600` file through its `0700` directory.
The previous regular-file descriptor adapter is therefore rejected. File modes,
path secrecy, a process appearing at the right time, socket counts and PID order
are not accepted as root-request authentication.

The probe now uses ProcessBuilder's anonymous stdout pipe, with no filesystem
log pathname. A different ordinary guest UID was denied when opening the live
ordinary `su` client's proc stdout descriptor with `EACCES`. The actual anonymous
pipe test has now repeated that check against both the `su` client's stdout and
the controller camera process's reader descriptor: guest UID 10051 received
`EACCES` for native read and write opens, and Java opens were denied. See
[the saved privacy result](../artifacts/unified/root-anonymous-pipe-privacy.json).
The bridge authenticates its own client before inspecting pending root children:

- Before spawning, it snapshots process PIDs and its own anonymous pipe identities.
- Exactly one newly created same-UID `su` client must have the controller service
  as its direct parent, the exact fixed root-probe argv, complete matching UID
  fields and a stable readable process start identity.
- That client's stdout must be an anonymous pipe whose device/inode is also held
  in a newly created descriptor in the controller process. Existing pipes and
  pipes not owned by the controller are refused.
- The direct client identity and controller's ownership of the same pipe are
  checked again immediately before opening consent.

The preferred `full-proc-anonymous-pipe-v2` adapter additionally verifies a fresh
listening kernel endpoint, expected root credentials, exact daemon command line,
pinned executable bytes, stable process start ticks and the endpoint's live FD.

### Restricted proc view in the inspected guest

In ROM10075, all three fixed Unix table aliases return `ENOENT`. Ordinary root
process metadata opens return `EACCES`, while descriptor readlink/stat work.
`stat.st_uid` is rewritten to the calling virtual UID and is not authentication.
The `vmos-anonymous-pipe-v2` adapter therefore binds each candidate to the probe's
anonymous stdout pipe and requires the exact pinned VMOS launcher device/inode,
new proc directory identity, denied root metadata and observable socket FDs.
The launcher is hashed through the controller's own readable proc executable;
no foreign descriptor or physical host path is opened.

Both a waiting compatibility parent and its root request child can inherit this
pipe. The request child can close inherited sockets, so a larger socket set is
not reliable selection. The bridge requires exactly one correlated process with
an observed real socket at the original daemon's PID-derived pathname. The
complete process cluster, pipe identity and socket pathname metadata must remain
unchanged across at least three scans and 300 milliseconds and are rechecked
immediately before consent. Multiple observed request paths are refused. The
filesystem socket inode is never equated with its separate kernel FD inode.

The registered ELF uses
`/dev/socket/com.koushikdutta.superuser/.socket<child-pid>`. Its base path is at
file offset `0xc0caf`, its `%s/.socket%d` format is at `0xc1374`, and the formatter
at `0x6b58`–`0x6b84` uses the child's `getpid()` result. This VMOS binary differs
from upstream Superuser's private cache pathname, which this adapter refuses.
An empty manager cache is not evidence that no request exists.

Root credentials, command line and start ticks are unavailable for the restricted
root child and reported as unavailable, rather than claimed to be verified.
Request identity depends on the anonymous pipe's foreign-UID proc-FD isolation,
which passed the saved real-device test in this exact guest. Other guest builds
need their own acceptance. Already trusted root processes
and code executing in this application's UID are outside that boundary.

The original Superuser manager reads request details from its socket protocol;
its actual dex does not call `LocalSocket.getPeerCredentials`. The bridge must
therefore establish authentic request correlation itself. Merely delegating a
guessed pathname to that activity cannot substitute for these checks.

When the manager is configured to prompt and no saved policy applies, the user
must approve the normal Superuser prompt. Only a completed UID-0 probe
allows later approved guest preparation, including creation of the missing
launcher alias. Opening consent alone never makes setup ready. Failure or expiry
preserves an actionable setup error; no fallback grants root.
## Create an explicitly named candidate

After building the reviewed integrated debug APK with the two development
signature permissions declared:

```powershell
.\scripts\sign-unified-vmos-development.ps1 `
  -InputApk .\android\app\build\outputs\apk\debug\app-debug.apk `
  -OutputDirectory .\artifacts\unified\development\candidate-01 `
  -AcknowledgeDevelopmentPlatformTrust
```

The output is
`VirtualCameraPro-VmosDevelopment-RootAuthorization.apk`, its proof-of-rotation
lineage, signature verification output and a report explicitly marking device
acceptance as unverified. The script refuses to overwrite an earlier candidate,
keeps output inside `artifacts/unified/development`, verifies the current debug
certificate and checks that the input and canonical APK remain unchanged.

## Root authorization device evidence

Candidate 07 was tested inside the registered ROM10075 guest on the Infinix
X687B. Its SHA-256 is
`4b58cca37df8b38fc90ca770e0d83250a2445546759f04122128ad2525c8a982`.
The existing application UID 10048 and data were retained; the current debug
signer and explicit platform ancestry are recorded in its
[signing report](../artifacts/unified/development/candidate-07/development-signing-report.json).
That immutable signing-time report predates installation; it is not the device
acceptance report.

The original manager's existing automatic response was **Allow**. For the
interactive test, its own settings UI was used to select **Prompt**, and only
this application's previous policy was removed through that UI. No root-policy
database was edited. The missing `app_process32` alias remained absent during
both authorization attempts.

- The authentic request screen named **Virtual Camera Pro** and offered its
  ordinary Deny/Allow and remember-choice controls. Selecting **Deny** produced
  `ROOT_MANAGER_AUTH_REQUIRED`, persisted the failed authorization result and
  left `ready:false` and `canPrepare:false`.
- A subsequent authentic request was approved with **Remember choice forever**
  and **Allow**. The ordinary `su` probe then returned actual UID 0 with
  `GUEST_ROOT_VERIFIED`. Setup offered guest preparation, while `ready:false`,
  `lastVerifiedAt:0` and the absent camera verification evidence correctly
  remained unchanged.
- The correlated anonymous pipe resisted the separate ordinary UID's native
  read/write and Java opens at both ends, as described above.

The compact [root-only acceptance report](../artifacts/unified/root-authorization-acceptance.json)
links the saved prompt, denial, approval and privacy evidence with their hashes.
This establishes the root bootstrap, interactive decision handling and tested
pipe isolation in the exact development-signed guest. It does not establish
camera migration, frame delivery, restoration or compatibility with another
phone/guest.

## Root tests and separate camera acceptance

CPU-only adversarial request parsing tests can be run with
`powershell -File scripts/test-root-authorization.ps1`. These do not invoke root,
operate a device, sign an APK or establish the Android permission behavior.
`powershell -File scripts/test-root-access-manager.ps1` compiles the actual root
manager against isolated host stubs and checks that authorization creates no
private file or directory as a request capability.

The full integrated camera pipeline was validated separately on Release 16:
automatic guest preparation, actual camera/color/zoom/image/video regression,
restoration, process crashes and full VMOS restart. See
[UNIFIED_ACCEPTANCE.md](UNIFIED_ACCEPTANCE.md) for the exact delivered APK and
candidate-specific results. The root-only candidate-07 test above remains one
gate; it is not relabeled as complete camera acceptance. Earlier bootstrap
failures remain recorded as failures. A signing experiment or offline test alone
does not establish device camera results.

The inspected Android 9 behavior is documented in AOSP's
[signature permission implementation](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r61/services/core/java/com/android/server/pm/permission/PermissionManagerService.java),
[current-signature comparisons](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r61/services/core/java/com/android/server/pm/PackageManagerService.java)
and [activity launch permission checks](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-9.0.0_r61/services/core/java/com/android/server/am/ActivityStackSupervisor.java).
