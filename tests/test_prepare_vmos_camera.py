"""Recovery-bundle safety tests using the public, exact-build guest module.

Set VMOS_TEST_CAMERA_MODULE to an extracted ROM10075 camera.vphw90.so when the
public VMOS APK artifacts are not in their default local research directory.
The module is not redistributed in this repository.
"""

import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("prepare_vmos_camera", ROOT / "scripts/prepare-vmos-camera.py")
PREPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARE)
MODULE = Path(os.environ.get("VMOS_TEST_CAMERA_MODULE", str(
    ROOT / "artifacts/vmos/research/rootfs-selected/vendor/lib64/hw/camera.vphw90.so")))


@unittest.skipUnless(MODULE.is_file(), "Exact public VMOS camera module not present")
class VmosRecoveryBundleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)

    def test_real_exact_module_round_trip_and_unchanged_executable_bytes(self):
        output = self.directory / "bundle"
        manifest = PREPARE.prepare(MODULE, output)
        original = MODULE.read_bytes()
        patched = (output / PREPARE.PATCHED_FILE).read_bytes()
        self.assertEqual(original, (output / PREPARE.ORIGINAL_FILE).read_bytes())
        self.assertEqual(manifest, PREPARE.verify(output / PREPARE.MANIFEST_FILE))
        self.assertEqual(manifest, PREPARE.prepare(MODULE, output))
        self.assertEqual(len(original), len(patched))
        self.assertEqual(original[:PREPARE.SOCKET_OFFSET], patched[:PREPARE.SOCKET_OFFSET])
        end = PREPARE.SOCKET_OFFSET + len(PREPARE.ORIGINAL_SOCKET) + 1
        self.assertEqual(original[end:], patched[end:])
        self.assertEqual(PREPARE.PROXY_SOCKET, patched[PREPARE.SOCKET_OFFSET:end].split(b"\0", 1)[0])

    def test_one_changed_original_byte_is_rejected_before_creating_outputs(self):
        altered = bytearray(MODULE.read_bytes())
        altered[-1] ^= 1
        invalid = self.directory / "similar-module.so"
        invalid.write_bytes(altered)
        output = self.directory / "bundle"
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            PREPARE.prepare(invalid, output)
        self.assertFalse(output.exists())

    def test_existing_recovery_copy_is_never_replaced(self):
        output = self.directory / "bundle"
        output.mkdir()
        recovery = output / PREPARE.ORIGINAL_FILE
        recovery.write_bytes(b"a different recovery copy")
        with self.assertRaisesRegex(ValueError, "Refusing to replace"):
            PREPARE.prepare(MODULE, output)
        self.assertEqual(b"a different recovery copy", recovery.read_bytes())
        self.assertFalse((output / PREPARE.PATCHED_FILE).exists())
        self.assertFalse((output / PREPARE.MANIFEST_FILE).exists())

    def test_tampered_prepared_module_fails_verification(self):
        output = self.directory / "bundle"
        PREPARE.prepare(MODULE, output)
        patched = output / PREPARE.PATCHED_FILE
        changed = bytearray(patched.read_bytes())
        changed[-1] ^= 1
        patched.write_bytes(changed)
        with self.assertRaisesRegex(ValueError, "no longer matches"):
            PREPARE.verify(output / PREPARE.MANIFEST_FILE)

    def test_changed_install_path_or_rollback_metadata_fails_verification(self):
        output = self.directory / "bundle"
        original_manifest = PREPARE.prepare(MODULE, output)
        for key, value in [
            ("guestModule", "/system/lib64/hw/camera.some-other-phone.so"),
            ("guestProviderService", "some-other-service"),
            ("guest", {"android": "10", "api": 29}),
            ("hostPackage", "some.other.vm"),
            ("status", "ready"),
            ("rollback", {"file": "another-file.so"}),
        ]:
            with self.subTest(key=key):
                manifest = dict(original_manifest)
                manifest[key] = value
                (output / PREPARE.MANIFEST_FILE).write_text(json.dumps(manifest))
                with self.assertRaisesRegex(ValueError, "install or rollback"):
                    PREPARE.verify(output / PREPARE.MANIFEST_FILE)

    def test_manifest_cannot_select_a_file_outside_the_bundle(self):
        output = self.directory / "bundle"
        manifest = PREPARE.prepare(MODULE, output)
        manifest["original"]["file"] = "../another-module.so"
        (output / PREPARE.MANIFEST_FILE).write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, "filename"):
            PREPARE.verify(output / PREPARE.MANIFEST_FILE)


if __name__ == "__main__":
    unittest.main()
