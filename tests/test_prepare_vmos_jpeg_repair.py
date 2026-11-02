"""Exact-module safety and scope checks for offline VMOS JPEG candidates."""
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("prepare_vmos_jpeg_repair", ROOT / "scripts/prepare-vmos-jpeg-repair.py")
REPAIR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REPAIR)
IMPORTER = ROOT / "artifacts/vmos/research/camera-device-3.2-runtime.so"
HAL = ROOT / "artifacts/vmos/research/rootfs-selected/vendor/lib64/hw/camera.vphw90.so"


@unittest.skipUnless(IMPORTER.is_file() and HAL.is_file(), "Exact inspected VMOS modules not present")
class VmosJpegCandidateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)

    def test_blob_stride_instruction_and_validation_call_scope(self):
        original = IMPORTER.read_bytes()
        candidate = REPAIR.repair_importer(original)
        at = REPAIR.IMPORTER_OFFSET
        self.assertEqual(original[:at], candidate[:at])
        self.assertEqual(original[at + 4:], candidate[at + 4:])
        self.assertEqual(len(original), len(candidate))
        # Decode the actual AArch64 unsigned-offset LDR instruction. The BLOB
        # branch now reads exactly the same allocation width used by W2 from
        # SP+44, instead of the image stride at X25+96. No call is bypassed.
        old, new = struct.unpack_from("<I", original, at)[0], struct.unpack_from("<I", candidate, at)[0]
        for instruction in (old, new):
            self.assertEqual(instruction & 0xFFC00000, 0xB9400000)
            self.assertEqual(instruction & 31, 7)
        self.assertEqual(((old >> 5) & 31, ((old >> 10) & 0xFFF) * 4), (25, 96))
        self.assertEqual(((new >> 5) & 31, ((new >> 10) & 0xFFF) * 4), (31, 44))
        width_load = struct.unpack_from("<I", candidate, 0x15D14)[0]
        self.assertEqual((width_load & 31, (width_load >> 5) & 31, ((width_load >> 10) & 0xFFF) * 4), (2, 31, 44))
        call = struct.unpack_from("<I", candidate, 0x15D6C)[0]
        self.assertEqual(call & 0xFC000000, 0x94000000)  # Original BL still executes.
        self.assertEqual(original[0x15D48:0x15D70], candidate[0x15D48:0x15D70])  # Non-BLOB arguments unchanged.

    def test_optional_encoder_path_uses_only_reserved_string_bytes(self):
        original = HAL.read_bytes()
        candidate = REPAIR.repair_jpeg_path(original)
        start, end = REPAIR.JPEG_PATH_OFFSET, REPAIR.JPEG_PATH_OFFSET + REPAIR.JPEG_PATH_ALLOCATION
        self.assertEqual(original[:start], candidate[:start])
        self.assertEqual(original[end:], candidate[end:])
        self.assertEqual(len(original), len(candidate))
        self.assertEqual(candidate[start:end].split(b"\0", 1)[0], REPAIR.JPEG_PATH_NEW)
        self.assertEqual(original[0x8B7CF:0x8B7CF + 28], candidate[0x8B7CF:0x8B7CF + 28])  # Camera socket literal unchanged.

    def test_foreign_or_already_changed_modules_fail_before_bundle_creation(self):
        for kind, source in (("importer", IMPORTER), ("hal", HAL)):
            modified = bytearray(source.read_bytes())
            modified[-1] ^= 1
            path = self.directory / f"changed-{kind}.so"
            path.write_bytes(modified)
            output = self.directory / f"bundle-{kind}"
            with self.subTest(kind=kind), self.assertRaisesRegex(ValueError, "SHA-256"):
                REPAIR.prepare(path if kind == "importer" else IMPORTER, path if kind == "hal" else HAL, output)
            self.assertFalse(output.exists())
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            REPAIR.repair_importer(REPAIR.repair_importer(IMPORTER.read_bytes()))

    def test_recovery_copy_and_existing_manifest_are_never_overwritten(self):
        for filename in ("camera.device@3.2-impl.original.so", "jpeg-repair.json"):
            output = self.directory / filename.replace(".", "-")
            output.mkdir()
            guarded = output / filename
            guarded.write_bytes(b"preserve this existing recovery evidence")
            with self.subTest(filename=filename), self.assertRaisesRegex(ValueError, "Refusing to overwrite"):
                REPAIR.prepare(IMPORTER, HAL, output)
            self.assertEqual(guarded.read_bytes(), b"preserve this existing recovery evidence")
            self.assertEqual(len(list(output.iterdir())), 1)

    def test_bundle_is_idempotent_and_each_hash_matches_its_preserved_file(self):
        output = self.directory / "bundle"
        manifest = REPAIR.prepare(IMPORTER, HAL, output)
        self.assertEqual(manifest, REPAIR.prepare(IMPORTER, HAL, output))
        self.assertEqual(manifest, json.loads((output / "jpeg-repair.json").read_text()))
        self.assertEqual(manifest["status"], "prepared_uninstalled_unverified")
        for component in manifest["components"]:
            self.assertEqual(component["originalSha256"], REPAIR.sha256((output / component["originalFile"]).read_bytes()))
            self.assertEqual(component["candidateSha256"], REPAIR.sha256((output / component["candidateFile"]).read_bytes()))

    def test_importer_only_bundle_does_not_change_or_include_camera_hal(self):
        output = self.directory / "importer-only"
        manifest = REPAIR.prepare(IMPORTER, None, output)
        self.assertEqual(len(manifest["components"]), 1)
        self.assertEqual(manifest["components"][0]["guestPath"], "/vendor/lib64/camera.device@3.2-impl.so")
        self.assertEqual({p.name for p in output.iterdir()}, {"camera.device@3.2-impl.original.so", "camera.device@3.2-impl.so", "jpeg-repair.json"})


if __name__ == "__main__":
    unittest.main()
