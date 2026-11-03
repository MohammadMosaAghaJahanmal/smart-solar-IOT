"""Exercise the exact shell installer packaged in the single APK against real temp files.

The existing repair suite supplies original measured ELF fixtures and simulated
guest-only commands. This variant validates the embedded installer actually used
by GuestProvisioningManager, including interrupted-operation recovery.
"""
from pathlib import Path
import hashlib
import os
import unittest

from tests import test_vmos_guest_jpeg_repair as baseline


ROOT = Path(__file__).resolve().parents[1]
EMBEDDED = ROOT / "android/app/src/main/assets/provisioning/vmos-guest-jpeg-repair.sh"


class EmbeddedGuestPreparationTests(baseline.VmosGuestRepairShellTests):
    def setUp(self):
        previous = baseline.SCRIPT
        baseline.SCRIPT = EMBEDDED
        self.addCleanup(setattr, baseline, "SCRIPT", previous)
        super().setUp()

    def test_embedded_script_is_the_audited_rollback_installer(self):
        self.assertEqual(EMBEDDED.read_bytes(), (ROOT / "scripts/vmos-guest-jpeg-repair.sh").read_bytes())

    def test_process_death_after_publish_is_recovered_before_reinstall(self):
        restart = self.bin / "setprop"
        original_restart = restart.read_text()
        self.mock("setprop", "#!/usr/bin/python3\nimport os, signal\nos.kill(os.getppid(), signal.SIGKILL)\n")
        self.run_script("install", expected=-9)
        self.assertEqual(hashlib.sha256(self.target.read_bytes()).hexdigest(), baseline.PATCHED)
        self.assertTrue(self.link.is_symlink())
        self.assertTrue(self.record.is_file())
        self.assertEqual(self.root.joinpath("pid").read_text(), "100")
        self.mock("setprop", original_restart)
        self.run_script("restore")
        self.assertEqual(self.target.read_bytes(), baseline.IMPORTER.read_bytes())
        self.install()
        self.assertEqual(self.root.joinpath("pid").read_text(), "102")

    def test_live_repair_owner_is_never_reclaimed(self):
        lock = self.path("/vendor/lib64/.vcam-jpeg-repair-lock")
        lock.mkdir(mode=0o700)
        pid = os.getpid()
        start = Path(f"/proc/{pid}/stat").read_text().rsplit(") ", 1)[1].split()[19]
        owner = lock / "owner.txt"
        contents = f"VCAM_JPEG_LOCK_1\npid={pid}\nstart={start}\n"
        owner.write_text(contents)
        owner.chmod(0o600)
        self.run_script("install", expected=1)
        self.assertEqual(owner.read_text(), contents)
        self.assertEqual(self.target.read_bytes(), baseline.IMPORTER.read_bytes())
        self.assertFalse(self.backup.exists())

    def test_unknown_ownerless_lock_is_preserved(self):
        lock = self.path("/vendor/lib64/.vcam-jpeg-repair-lock")
        lock.mkdir(mode=0o700)
        marker = lock / "unrecognized"
        marker.write_bytes(b"preserve this data")
        self.run_script("install", expected=1)
        self.assertEqual(marker.read_bytes(), b"preserve this data")
        self.assertEqual(self.target.read_bytes(), baseline.IMPORTER.read_bytes())
        self.assertFalse(self.backup.exists())

    def test_stale_owner_with_unknown_data_preserves_both(self):
        lock = self.path("/vendor/lib64/.vcam-jpeg-repair-lock")
        lock.mkdir(mode=0o700)
        owner = lock / "owner.txt"
        contents = "VCAM_JPEG_LOCK_1\npid=2147483647\nstart=1\n"
        owner.write_text(contents)
        owner.chmod(0o600)
        marker = lock / "unrecognized"
        marker.write_bytes(b"preserve this data")
        self.run_script("install", expected=1)
        self.assertEqual(owner.read_text(), contents)
        self.assertEqual(marker.read_bytes(), b"preserve this data")
        self.assertEqual(self.target.read_bytes(), baseline.IMPORTER.read_bytes())


if __name__ == "__main__":
    unittest.main()
