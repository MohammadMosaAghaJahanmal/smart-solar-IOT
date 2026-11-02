# Guest diagnostics

Small standalone Android application for investigating an Android VM from inside
the guest. It has no React Native dependency and supports API 21 and higher.
Installing it does not install or verify a virtual-camera backend.

Version 1.1 adds read-only file existence/access flags for the inspected guest's
camera HAL, its SHA-256 when readable, application PID and private/source paths.
**Test guest staging access** creates uniquely named empty probe files only in
`/vendor/lib64/hw` and `/data/local/tmp`, attempts to rename its own files, and
removes them. It never writes or renames an existing HAL. The operation is
restricted to the inspected API 28 / arm64 / `vphw90` guest identity.

**Run camera proxy self-test** copies the bundled native ARM64 executable to the
application's private files directory, verifies its pinned SHA-256 and ELF
architecture, marks that private copy executable, and runs only `--selftest`
with an eight-second limit. Its output and exit status join the diagnostics.
It does not install the proxy, change a HAL or imply working camera injection.
**Read VMOS camera list** runs the same verified executable with its fixed
`--list-cameras` argument. This reads the VMOS camera factory socket without
opening a camera or starting a stream; its bounded result appears under
`vmosCameraList`.

Build with JDK 17:

```powershell
cd D:\vcamera\android
.\gradlew.bat :guest-tools:assembleDebug
```

Import `build/outputs/apk/debug/guest-tools-debug.apk` into the VM and launch
**Vcam Guest Diagnostics**. Initial inventory does not request root. The
**Read guest diagnostics and request root** button explicitly invokes `su -c id`
and, if successful, reads an allowlisted camera/system inventory. It does not
collect serial numbers, accounts or personal media. Commands have time and
output limits. The report includes the guest Android build fingerprint and ABI
so it can be distinguished from the physical host.

The activity runs a read-only HTTP server on guest loopback port 18792 while it
is alive. `GET /identity` returns its package, API level and instance UUID;
`GET /diagnostics` returns the same inventory shown onscreen. There are no HTTP
mutation, shell-execution, root-execution or arbitrary-file endpoints. The
report is also saved privately as `files/diagnostics.json`. The export button
writes `Download/VcamGuestDiagnostics.json`, requesting storage permission on
Android 6–9. It does not send the report to an external service.

Optional TCP ADB is an explicitly confirmed development action. Both root
verification and the operator's guest checkbox are required before the button
is enabled. Enabling requests `service.adb.tcp.port=5557` and restarts the guest
`adbd`; disabling resets that property to `-1` and restarts it again. This does
not prove that ADB is reachable from the PC, and the guest may expose TCP ADB on
its network. Disable it after development. Do not use those buttons in the
physical phone's Android environment.
