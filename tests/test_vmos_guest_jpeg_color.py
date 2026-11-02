"""Execute the production color utility in an isolated mocked VMOS filesystem.

The shim, importer, HAL and original JPEG helper use their actual inspected bytes.
Only Android identity/provider commands, synthetic provider hash and virtual UID
metadata are mocked. File copies, atomic renames, hashes, links and modes are real.
No ADB, device, system-library modification, or host root access is used.
"""
import hashlib
import importlib.util
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/vmos-guest-jpeg-color.sh"
SHIM = ROOT / "artifacts/vmos/jpeg-color/camera.vphw90.jpeg.so"
IMPORTER = ROOT / "artifacts/vmos/jpeg-stride-prepared/camera.device@3.2-impl.so"
HAL = ROOT / "artifacts/vmos/research/rootfs-selected/vendor/lib64/hw/camera.vphw90.so"
HELPER = HAL.with_name("camera.vphw90.jpeg.so")
PROVIDER_HASH = "fe58ac53d066187a0fd499a38354101ccff9cc0d3379164bd9f36b5b631d7e87"
SPEC = importlib.util.spec_from_file_location("vmos_camera_prepare", ROOT / "scripts/prepare-vmos-camera.py")
CAMERA_PREPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CAMERA_PREPARE)


@unittest.skipUnless(os.name == "posix" and Path("/bin/sh").is_file() and
                     all(path.is_file() for path in (SHIM, IMPORTER, HAL, HELPER)),
                     "Run under Linux/WSL with the exact inspected guest modules and prepared color shim")
class VmosGuestJpegColorTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="vcam-jpeg-color-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "mock-bin"
        self.bin.mkdir()
        self.target = self.path("/vendor/lib/hw/camera.vphw90.jpeg.so")
        self.helper = self.path("/vendor/lib64/hw/camera.vphw90.jpeg.so")
        self.importer = self.path("/vendor/lib64/camera.device@3.2-impl.so")
        self.camera = self.path("/vendor/lib64/hw/camera.vphw90.so")
        self.provider = self.path("/vendor/bin/hw/android.hardware.camera.provider@2.4-service")
        self.record = self.path("/data/local/tmp/vcam-jpeg-color-v1.txt")
        self.lock = self.path("/vendor/lib/hw/.vcam-jpeg-color-lock")
        self.journal_lock = self.path("/data/local/tmp/.vcam-jpeg-color-journal-lock")
        self.candidate = self.path("/data/local/tmp/vcam-jpeg-color.so")
        for path in (self.target, self.helper, self.importer, self.camera, self.provider, self.candidate):
            path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(SHIM, self.candidate)
        shutil.copyfile(HELPER, self.helper)
        shutil.copyfile(IMPORTER, self.importer)
        shutil.copyfile(HAL, self.camera)
        self.provider.write_bytes(b"mock exact provider executable")
        self.target.symlink_to(self.helper)
        self.root.joinpath("pid").write_text("100")
        self.path("/proc/100").mkdir(parents=True)
        self.path("/proc/100/cmdline").write_bytes(str(self.provider).encode() + b"\0")
        self.environment = dict(os.environ, MOCK_ROOT=str(self.root), MOCK_PROVIDER=str(self.provider),
                                MOCK_TARGET=str(self.target), MOCK_RECORD=str(self.record))
        self.mock("getprop", """#!/bin/sh
case "$1" in
ro.hardware) echo "${MOCK_HARDWARE:-vphw90}";;
ro.build.version.sdk) echo "${MOCK_SDK:-28}";;
ro.product.cpu.abilist) echo "${MOCK_ABI:-arm64-v8a}";;
ro.build.fingerprint) echo "${MOCK_FINGERPRINT:-Infinix/X687B-OP/Infinix-X687B:9/PQ3A.190801.002/eng.builds.20191015.144423:user/release-keys}";;
init.svc.vendor.camera-provider-2-4) echo "${MOCK_SERVICE:-running}";;
esac
""")
        self.mock("getenforce", '#!/bin/sh\necho "${MOCK_SELINUX:-Disabled}"\n')
        self.mock("id", '#!/bin/sh\necho "${MOCK_UID:-0}"\n')
        self.mock("stat", """#!/usr/bin/python3
import os, pathlib, stat, subprocess, sys
fmt = sys.argv[2] if sys.argv[1:2] == ['-c'] else ''
if fmt == '%u': print(0)
elif fmt == '%a:%u:%g': print(format(stat.S_IMODE(pathlib.Path(sys.argv[3]).stat().st_mode), 'o') + ':0:0')
else: raise SystemExit(subprocess.call(['/usr/bin/stat'] + sys.argv[1:]))
""")
        self.mock("chown", '#!/bin/sh\n[ "$1" = 0:0 ] || exit 1\nexit 0\n')
        self.mock("sha256sum", f"""#!/usr/bin/python3
import hashlib, pathlib, sys
if len(sys.argv) == 1:
    b = sys.stdin.buffer.read(); name = '-'
else:
    name = sys.argv[1]; b = pathlib.Path(name).read_bytes()
h = '{PROVIDER_HASH}' if b == b'mock exact provider executable' else hashlib.sha256(b).hexdigest()
print(h + '  ' + name)
""")
        self.mock("pidof", '#!/bin/sh\ncat "$MOCK_ROOT/pid"\n')
        self.mock("setprop", """#!/usr/bin/python3
import os, pathlib
r = pathlib.Path(os.environ['MOCK_ROOT'])
if os.environ.get('MOCK_RESTART_CORRUPT') == '1':
    pathlib.Path(os.environ['MOCK_TARGET']).write_bytes(b'preserve unexpected target change')
    raise SystemExit(0)
if os.environ.get('MOCK_RESTART_JOURNAL_CORRUPT') == '1':
    pathlib.Path(os.environ['MOCK_RECORD']).write_bytes(b'preserve unexpected journal change')
    raise SystemExit(0)
if os.environ.get('MOCK_RESTART_FAIL') == '1': raise SystemExit(0)
p = r / 'pid'; pid = str(int(p.read_text()) + 1); p.write_text(pid)
d = r / 'proc' / pid; d.mkdir(exist_ok=True)
(d / 'cmdline').write_bytes(os.environ['MOCK_PROVIDER'].encode() + b'\\0')
""")
        self.mock("sleep", '#!/bin/sh\nexit 0\n')
        self.mock("mv", """#!/usr/bin/python3
import os, pathlib, subprocess, sys
source, destination = map(pathlib.Path, sys.argv[1:3])
if os.environ.get('MOCK_FAIL_SHIM_PUBLISH') == '1' and source.name == 'shim.tmp': raise SystemExit(1)
if os.environ.get('MOCK_SPLIT_VENDOR_DATA') == '1':
    root = pathlib.Path(os.environ['MOCK_ROOT'])
    if source.relative_to(root).parts[0] != destination.relative_to(root).parts[0]:
        raise SystemExit('Refused cross-filesystem publication')
raise SystemExit(subprocess.call(['/bin/mv'] + sys.argv[1:]))
""")
        # Only a test copy receives a path override. The production utility does not.
        script = SCRIPT.read_text()
        for prefix in ("/vendor", "/data", "/proc"):
            script = script.replace(prefix, str(self.root) + prefix)
        script = script.replace("PATH=/system/bin:/system/xbin", f"PATH={self.bin}:/usr/bin:/bin")
        self.script = self.root / "color.sh"
        self.script.write_text(script)

    def path(self, guest):
        return self.root / guest.lstrip("/")

    def mock(self, name, contents):
        path = self.bin / name
        path.write_text(contents)
        path.chmod(0o755)

    def run_script(self, action, expected=0, **environment):
        result = subprocess.run(["/bin/sh", str(self.script), action], env=dict(self.environment, **environment),
                                text=True, capture_output=True, timeout=30)
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        return result

    def install(self):
        self.run_script("install")
        self.assertFalse(self.target.is_symlink())
        self.assertEqual(self.target.read_bytes(), SHIM.read_bytes())

    def assert_original_link(self):
        self.assertTrue(self.target.is_symlink())
        self.assertEqual(self.target.readlink(), self.helper)

    def test_release_shim_hash_is_pinned_and_shell_parses(self):
        pinned = re.search(r"^shim_hash=([a-f0-9]{64})$", SCRIPT.read_text(), re.MULTILINE)
        self.assertIsNotNone(pinned, "Production utility must pin its exact release binary")
        self.assertEqual(hashlib.sha256(SHIM.read_bytes()).hexdigest(), pinned.group(1))
        subprocess.run(["/bin/sh", "-n", str(SCRIPT)], check=True)

    def test_install_restore_changes_only_compatibility_path_and_journal(self):
        originals = {path: path.read_bytes() for path in (self.helper, self.importer, self.camera, self.provider)}
        original_modes = {path: stat.S_IMODE(path.stat().st_mode) for path in originals}
        self.install()
        self.assertEqual(stat.S_IMODE(self.target.stat().st_mode), 0o755)
        self.assertEqual(stat.S_IMODE(self.record.stat().st_mode), 0o600)
        self.assertIn("prior_link=" + str(self.helper), self.record.read_text())
        self.assertFalse(self.lock.exists())
        self.assertFalse(self.journal_lock.exists())
        self.run_script("restore")
        self.assert_original_link()
        for path, contents in originals.items():
            self.assertEqual(path.read_bytes(), contents)
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), original_modes[path])
        self.assertFalse(self.record.exists())
        self.assertFalse(self.lock.exists())
        self.assertFalse(self.journal_lock.exists())
        self.assertEqual(len(list(self.record.parent.glob(self.record.name + ".restored.*.txt"))), 1)

    def test_inspect_is_read_only_before_and_after_install(self):
        self.run_script("inspect")
        self.assert_original_link()
        self.assertFalse(self.record.exists())
        self.assertFalse(self.lock.exists())
        self.install()
        journal = self.record.read_bytes()
        self.run_script("inspect")
        self.assertEqual(self.record.read_bytes(), journal)
        self.assertEqual(self.target.read_bytes(), SHIM.read_bytes())
        self.assertFalse(self.lock.exists())

    def test_exact_broker_socket_redirect_is_also_accepted(self):
        self.camera.write_bytes(CAMERA_PREPARE.patch_module(HAL.read_bytes()))
        before = self.camera.read_bytes()
        self.install()
        self.run_script("restore")
        self.assertEqual(self.camera.read_bytes(), before)

    def test_target_and_journal_publications_each_stay_on_their_own_filesystem(self):
        self.run_script("install", MOCK_SPLIT_VENDOR_DATA="1")
        self.assertEqual(self.target.read_bytes(), SHIM.read_bytes())
        self.run_script("restore", MOCK_SPLIT_VENDOR_DATA="1")
        self.assert_original_link()

    def test_foreign_guest_and_nonroot_refused_without_mutation(self):
        cases = ({"MOCK_HARDWARE": "physical"}, {"MOCK_SDK": "29"}, {"MOCK_UID": "1000"},
                 {"MOCK_ABI": "arm64-v8a,armeabi-v7a"}, {"MOCK_FINGERPRINT": "another-build"},
                 {"MOCK_SELINUX": "Enforcing"})
        for environment in cases:
            with self.subTest(environment=environment):
                self.run_script("install", expected=1, **environment)
                self.assert_original_link()
                self.assertFalse(self.record.exists())
                self.assertFalse(self.lock.exists())

    def test_changed_measured_modules_refused_without_journal(self):
        for path in (self.helper, self.importer, self.camera, self.provider):
            with self.subTest(path=path.name):
                original = path.read_bytes()
                path.write_bytes(b"unrecognized module")
                self.run_script("install", expected=1)
                self.assert_original_link()
                self.assertFalse(self.record.exists())
                path.write_bytes(original)

    def test_candidate_with_changed_bytes_or_symlink_is_refused(self):
        self.candidate.write_bytes(b"unrecognized candidate")
        self.run_script("install", expected=1)
        self.assert_original_link()
        self.assertFalse(self.record.exists())
        self.candidate.unlink()
        self.candidate.symlink_to(SHIM)
        self.run_script("install", expected=1)
        self.assertFalse(self.record.exists())

    def test_existing_unknown_file_or_link_is_preserved(self):
        self.target.unlink()
        self.target.write_bytes(b"unknown compatibility file")
        self.run_script("install", expected=1)
        self.assertEqual(self.target.read_bytes(), b"unknown compatibility file")
        self.target.unlink()
        self.target.symlink_to(self.camera)
        self.run_script("restore", expected=1)
        self.assertEqual(self.target.readlink(), self.camera)
        self.assertFalse(self.record.exists())

    def test_existing_unknown_or_symbolic_journal_is_preserved(self):
        self.record.write_bytes(b"unknown recovery journal")
        self.run_script("install", expected=1)
        self.assertEqual(self.record.read_bytes(), b"unknown recovery journal")
        self.assert_original_link()
        self.record.unlink()
        self.record.symlink_to(self.helper)
        self.run_script("install", expected=1)
        self.assertEqual(self.record.readlink(), self.helper)

    def test_existing_lock_and_unknown_contents_are_preserved(self):
        self.lock.mkdir(mode=0o700)
        sentinel = self.lock / "preserve.txt"
        sentinel.write_bytes(b"existing recovery operation")
        self.run_script("install", expected=1)
        self.assertEqual(sentinel.read_bytes(), b"existing recovery operation")
        self.assert_original_link()

    def test_existing_private_journal_lock_is_preserved(self):
        self.journal_lock.mkdir(mode=0o700)
        sentinel = self.journal_lock / "preserve.txt"
        sentinel.write_bytes(b"existing journal operation")
        self.run_script("install", expected=1)
        self.assertEqual(sentinel.read_bytes(), b"existing journal operation")
        self.assertFalse(self.lock.exists())
        self.assert_original_link()

    def test_reinstall_does_not_overwrite_the_journal(self):
        self.install()
        journal = self.record.read_bytes()
        self.run_script("install", expected=1)
        self.assertEqual(self.record.read_bytes(), journal)
        self.assertEqual(self.target.read_bytes(), SHIM.read_bytes())

    def test_changed_shim_bytes_permissions_and_hardlinks_are_refused(self):
        self.install()
        self.target.write_bytes(b"unknown installed bytes")
        self.run_script("restore", expected=1)
        self.assertEqual(self.target.read_bytes(), b"unknown installed bytes")
        shutil.copyfile(SHIM, self.target)
        self.target.chmod(0o777)
        self.run_script("restore", expected=1)
        self.assertEqual(stat.S_IMODE(self.target.stat().st_mode), 0o777)
        self.target.chmod(0o755)
        hardlink = self.target.with_name("preserve-hardlink.so")
        hardlink.hardlink_to(self.target)
        self.run_script("restore", expected=1)
        self.assertEqual(hardlink.read_bytes(), SHIM.read_bytes())
        self.assertTrue(self.record.is_file())

    def test_changed_journal_even_trailing_data_is_refused(self):
        self.install()
        original = self.record.read_bytes()
        for suffix in (b"\n", b"\0", b"extra=1\n"):
            with self.subTest(suffix=suffix):
                self.record.write_bytes(original + suffix)
                self.run_script("restore", expected=1)
                self.assertEqual(self.record.read_bytes(), original + suffix)
                self.assertEqual(self.target.read_bytes(), SHIM.read_bytes())

    def test_restore_never_overwrites_an_existing_recovery_archive(self):
        self.install()
        self.script.write_text(self.script.read_text().replace("$$", "4242"))
        archive = self.record.with_name(self.record.name + ".restored.4242.txt")
        archive.write_bytes(b"preserve earlier recovery evidence")
        self.run_script("restore", expected=1)
        self.assertEqual(archive.read_bytes(), b"preserve earlier recovery evidence")
        self.assertEqual(self.target.read_bytes(), SHIM.read_bytes())
        self.assertTrue(self.record.is_file())

    def test_failed_install_restart_rolls_back_link_and_retains_finishable_journal(self):
        self.run_script("install", expected=1, MOCK_RESTART_FAIL="1")
        self.assert_original_link()
        self.assertTrue(self.record.is_file())
        self.assertFalse(self.lock.exists())
        self.run_script("restore")
        self.assert_original_link()
        self.assertFalse(self.record.exists())

    def test_failed_restore_restart_can_be_retried_without_losing_journal(self):
        self.install()
        journal = self.record.read_bytes()
        self.run_script("restore", expected=1, MOCK_RESTART_FAIL="1")
        self.assert_original_link()
        self.assertEqual(self.record.read_bytes(), journal)
        self.run_script("restore")
        self.assertFalse(self.record.exists())

    def test_failure_before_target_publish_preserves_link_and_recoverable_journal(self):
        self.run_script("install", expected=1, MOCK_FAIL_SHIM_PUBLISH="1")
        self.assert_original_link()
        self.assertTrue(self.record.is_file())
        self.assertFalse(self.lock.exists())
        self.run_script("restore")
        self.assertFalse(self.record.exists())

    def test_failed_restart_never_overwrites_an_unexpected_target_change(self):
        self.run_script("install", expected=1, MOCK_RESTART_CORRUPT="1")
        self.assertEqual(self.target.read_bytes(), b"preserve unexpected target change")
        self.assertTrue(self.record.is_file())
        self.assertFalse(self.lock.exists())
        self.run_script("restore", expected=1)
        self.assertEqual(self.target.read_bytes(), b"preserve unexpected target change")

    def test_failed_restart_never_overwrites_an_unexpected_journal_change(self):
        self.run_script("install", expected=1, MOCK_RESTART_JOURNAL_CORRUPT="1")
        self.assertEqual(self.target.read_bytes(), SHIM.read_bytes())
        self.assertEqual(self.record.read_bytes(), b"preserve unexpected journal change")
        self.run_script("restore", expected=1)
        self.assertEqual(self.record.read_bytes(), b"preserve unexpected journal change")

    def test_unverified_provider_state_or_cmdline_cannot_trigger_restart(self):
        self.run_script("install", expected=1, MOCK_SERVICE="stopped")
        self.assert_original_link()
        self.assertEqual(self.root.joinpath("pid").read_text(), "100")
        self.run_script("restore")
        self.path("/proc/101/cmdline").write_bytes(b"/unrecognized/provider\0")
        self.run_script("install", expected=1)
        self.assert_original_link()
        self.assertEqual(self.root.joinpath("pid").read_text(), "101")


if __name__ == "__main__":
    unittest.main()
