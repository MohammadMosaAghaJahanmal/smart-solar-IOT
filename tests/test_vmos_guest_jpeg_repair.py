"""Run the production guest shell utility inside an isolated, mocked guest tree.

All absolute guest paths in a temporary script copy point beneath a TemporaryDirectory.
Only Android identity/process tools and the provider fixture digest are mocked; cp,
mv, ln, rm, permissions, file hashes and recovery journals use the real filesystem.
No test invokes ADB, modifies a system library, or requires root.
"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/vmos-guest-jpeg-repair.sh"
IMPORTER = ROOT / "artifacts/vmos/research/camera-device-3.2-runtime.so"
HAL = ROOT / "artifacts/vmos/research/rootfs-selected/vendor/lib64/hw/camera.vphw90.so"
HELPER = HAL.with_name("camera.vphw90.jpeg.so")
SPEC = importlib.util.spec_from_file_location("jpeg_repair", ROOT / "scripts/prepare-vmos-jpeg-repair.py")
REPAIR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REPAIR)
ORIGINAL = REPAIR.IMPORTER_SHA256
PATCHED = "b572750c30143af55a8b8ec4bc1a60017cac3ffec512deb5edffb054877817ab"
PROVIDER_HASH = "fe58ac53d066187a0fd499a38354101ccff9cc0d3379164bd9f36b5b631d7e87"


@unittest.skipUnless(os.name == "posix" and Path("/bin/sh").is_file() and IMPORTER.is_file() and HAL.is_file() and HELPER.is_file(),
                     "Run under Linux/WSL with the exact inspected guest module fixtures")
class VmosGuestRepairShellTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="vcam-jpeg-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "mock-bin"
        self.bin.mkdir()
        self.target = self.path("/vendor/lib64/camera.device@3.2-impl.so")
        self.backup = self.path("/vendor/lib64/.vcam-camera-device-3.2-original-be8cbff7.so")
        self.link = self.path("/vendor/lib/hw/camera.vphw90.jpeg.so")
        self.helper = self.path("/vendor/lib64/hw/camera.vphw90.jpeg.so")
        self.camera = self.path("/vendor/lib64/hw/camera.vphw90.so")
        self.provider = self.path("/vendor/bin/hw/android.hardware.camera.provider@2.4-service")
        self.record = self.path("/data/local/tmp/vcam-jpeg-repair-v1.txt")
        self.legacy = self.path("/data/local/tmp/vcam-jpeg-repair.json")
        self.candidate = self.path("/data/local/tmp/vcam-jpeg-stride.so")
        for file in (self.target, self.helper, self.camera, self.provider, self.candidate):
            file.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(IMPORTER, self.target)
        self.target.chmod(0o751)
        self.original_mode = stat.S_IMODE(self.target.stat().st_mode)
        shutil.copyfile(HAL, self.camera)
        shutil.copyfile(HELPER, self.helper)
        self.provider.write_bytes(b"mock exact provider executable")
        self.candidate.write_bytes(REPAIR.repair_importer(IMPORTER.read_bytes()))
        self.root.joinpath("pid").write_text("100")
        self.path("/proc/100").mkdir(parents=True)
        self.path("/proc/100/cmdline").write_bytes(str(self.provider).encode() + b"\0")
        self.environment = dict(os.environ, MOCK_ROOT=str(self.root), MOCK_PROVIDER=str(self.provider))
        self.mock("getprop", """#!/bin/sh
case "$1" in
ro.hardware) echo "${MOCK_HARDWARE:-vphw90}";;
ro.build.version.sdk) echo "${MOCK_SDK:-28}";;
ro.product.cpu.abilist) echo arm64-v8a;;
ro.build.fingerprint) echo Infinix/X687B-OP/Infinix-X687B:9/PQ3A.190801.002/eng.builds.20191015.144423:user/release-keys;;
init.svc.vendor.camera-provider-2-4) echo running;;
esac
""")
        self.mock("getenforce", "#!/bin/sh\necho Disabled\n")
        self.mock("id", "#!/bin/sh\necho \"${MOCK_UID:-0}\"\n")
        self.mock("stat", "#!/bin/sh\nif [ \"$1\" = -c ] && [ \"$2\" = %u ]; then echo 0; else exec /usr/bin/stat \"$@\"; fi\n")
        self.mock("sha256sum", f"""#!/usr/bin/python3
import hashlib, pathlib, sys
p = pathlib.Path(sys.argv[1]); b = p.read_bytes()
h = '{PROVIDER_HASH}' if b == b'mock exact provider executable' else hashlib.sha256(b).hexdigest()
print(h + '  ' + str(p))
""")
        self.mock("pidof", "#!/bin/sh\ncat \"$MOCK_ROOT/pid\"\n")
        self.mock("setprop", """#!/usr/bin/python3
import os, pathlib
if os.environ.get('MOCK_RESTART_FAIL') == '1':
    raise SystemExit(0)
r = pathlib.Path(os.environ['MOCK_ROOT']); p = r / 'pid'
pid = str(int(p.read_text()) + 1); p.write_text(pid)
d = r / 'proc' / pid; d.mkdir(exist_ok=True)
(d / 'cmdline').write_bytes(os.environ['MOCK_PROVIDER'].encode() + b'\\0')
""")
        self.mock("sleep", "#!/bin/sh\nexit 0\n")
        # Rewrite only the test copy; production has no environment path override.
        script = SCRIPT.read_text()
        for prefix in ("/vendor", "/data", "/proc"):
            script = script.replace(prefix, str(self.root) + prefix)
        script = script.replace("PATH=/system/bin:/system/xbin", f"PATH={self.bin}:/usr/bin:/bin")
        self.script = self.root / "repair.sh"
        self.script.write_text(script)

    def path(self, guest):
        return self.root / guest.lstrip("/")

    def mock(self, name, contents):
        file = self.bin / name
        file.write_text(contents)
        file.chmod(0o755)

    def run_script(self, action, expected=0, **environment):
        result = subprocess.run(["/bin/sh", str(self.script), action], env=dict(self.environment, **environment),
                                text=True, capture_output=True, timeout=15)
        self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
        return result

    def install(self):
        self.run_script("install")
        self.assertEqual(hashlib.sha256(self.target.read_bytes()).hexdigest(), PATCHED)
        self.assertEqual(self.link.readlink(), self.helper)

    def test_production_script_parses_as_posix_shell(self):
        subprocess.run(["/bin/sh", "-n", str(SCRIPT)], check=True)

    def test_install_restore_preserves_attributes_backup_and_journal(self):
        self.run_script("inspect")
        self.assertFalse(self.backup.exists())
        self.install()
        self.assertEqual(self.backup.read_bytes(), IMPORTER.read_bytes())
        self.assertEqual(stat.S_IMODE(self.target.stat().st_mode), self.original_mode)
        self.assertEqual(stat.S_IMODE(self.record.stat().st_mode), 0o600)
        self.run_script("restore")
        self.assertEqual(self.target.read_bytes(), IMPORTER.read_bytes())
        self.assertEqual(stat.S_IMODE(self.target.stat().st_mode), self.original_mode)
        self.assertFalse(self.link.is_symlink())
        self.assertFalse(self.path("/vendor/lib").exists())
        self.assertTrue(self.backup.is_file())
        self.assertFalse(self.record.exists())
        self.assertEqual(len(list(self.record.parent.glob(self.record.name + ".restored.*.txt"))), 1)

    def test_restore_preserves_preexisting_or_nonempty_directories(self):
        self.link.parent.mkdir(parents=True)
        sentinel = self.link.parent / "other.so"
        sentinel.write_bytes(b"unrelated existing file")
        self.install()
        self.run_script("restore")
        self.assertEqual(sentinel.read_bytes(), b"unrelated existing file")
        self.assertTrue(self.link.parent.is_dir())

    def test_foreign_guest_or_nonroot_is_refused_without_mutation(self):
        for environment in ({"MOCK_HARDWARE": "physical"}, {"MOCK_SDK": "29"}, {"MOCK_UID": "1000"}):
            with self.subTest(environment=environment):
                self.run_script("install", expected=1, **environment)
                self.assertEqual(self.target.read_bytes(), IMPORTER.read_bytes())
                self.assertFalse(self.backup.exists())

    def test_changed_candidate_or_original_never_creates_backup(self):
        self.candidate.write_bytes(b"foreign candidate")
        self.run_script("install", expected=1)
        self.assertFalse(self.backup.exists())
        self.candidate.write_bytes(REPAIR.repair_importer(IMPORTER.read_bytes()))
        self.target.write_bytes(b"foreign importer")
        self.run_script("install", expected=1)
        self.assertFalse(self.backup.exists())

    def test_existing_recovery_data_is_not_overwritten(self):
        self.backup.write_bytes(b"preserve unknown recovery data")
        self.run_script("install", expected=1)
        self.assertEqual(self.backup.read_bytes(), b"preserve unknown recovery data")
        self.assertEqual(self.target.read_bytes(), IMPORTER.read_bytes())

    def test_restore_refuses_changed_link_and_changed_journal(self):
        self.install()
        self.link.unlink()
        self.link.symlink_to(self.camera)
        self.run_script("restore", expected=1)
        self.assertEqual(self.link.readlink(), self.camera)
        self.assertEqual(hashlib.sha256(self.target.read_bytes()).hexdigest(), PATCHED)
        self.link.unlink()
        self.link.symlink_to(self.helper)
        self.record.write_text(self.record.read_text() + "extra=1\n")
        self.run_script("restore", expected=1)
        self.assertTrue(self.link.is_symlink())
        self.assertEqual(hashlib.sha256(self.target.read_bytes()).hexdigest(), PATCHED)

    def test_legacy_measured_install_restores_without_deleting_unrecorded_directories(self):
        self.install()
        self.record.unlink()
        self.legacy.write_text(json.dumps({"originalSha256": ORIGINAL, "patchedSha256": PATCHED,
                                          "backup": str(self.backup), "helperLinkCreated": True}, separators=(",", ":")) + "\n")
        self.run_script("restore")
        self.assertEqual(self.target.read_bytes(), IMPORTER.read_bytes())
        self.assertFalse(self.link.is_symlink())
        self.assertTrue(self.link.parent.is_dir())
        self.assertFalse(self.legacy.exists())
        self.assertEqual(len(list(self.legacy.parent.glob(self.legacy.name + ".restored.*.txt"))), 1)

    def test_restart_failure_restores_original_and_retains_finishable_journal(self):
        self.run_script("install", expected=1, MOCK_RESTART_FAIL="1")
        self.assertEqual(self.target.read_bytes(), IMPORTER.read_bytes())
        self.assertFalse(self.link.is_symlink())
        self.assertTrue(self.record.is_file())
        self.assertTrue(self.backup.is_file())
        self.run_script("restore")
        self.assertFalse(self.record.exists())
        self.assertFalse(self.path("/vendor/lib").exists())

    def test_restore_refuses_camera_socket_redirect_until_broker_is_restored(self):
        self.install()
        self.camera.write_bytes(b"broker camera socket redirect")
        self.run_script("restore", expected=1)
        self.assertTrue(self.link.is_symlink())
        self.assertEqual(hashlib.sha256(self.target.read_bytes()).hexdigest(), PATCHED)


if __name__ == "__main__":
    unittest.main()
