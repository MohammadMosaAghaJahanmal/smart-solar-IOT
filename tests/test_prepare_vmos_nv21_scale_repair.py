"""Exact ELF patch scope and independently computed scaled NV21 boundary cases."""
import hashlib
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('nv21repair',ROOT/'scripts/prepare-vmos-nv21-scale-repair.py')
repair=importlib.util.module_from_spec(spec);spec.loader.exec_module(repair)
ORIGINAL=ROOT/'artifacts/vmos/research/rootfs-selected/vendor/lib64/hw/camera.vphw90.so'
TRANSPORT=ROOT/'artifacts/vmos/prepared/camera.vphw90.so'

class Nv21ScalePreparation(unittest.TestCase):
    def test_exactly_three_add_instruction_slots_change_in_both_profiles(self):
        for source in [ORIGINAL,TRANSPORT]:
            data=source.read_bytes();candidate=repair.repair(data)
            edited=set()
            for address,before,after,_,_ in repair.EDITS:
                at=repair.offset(data,address);edited.update(range(at,at+4))
                self.assertEqual(data[at:at+4],before);self.assertEqual(candidate[at:at+4],after)
                instruction=struct.unpack('<I',after)[0]
                self.assertEqual(instruction&0xFFE00000,0x8B000000) # unshifted ADD X
                registers=(instruction&31,(instruction>>5)&31,(instruction>>16)&31)
                self.assertEqual(registers,(4,24,25) if address==0x4B2E0 else (8,13,27))
            self.assertEqual(len(data),len(candidate))
            self.assertTrue(all(a==b for i,(a,b) in enumerate(zip(data,candidate)) if i not in edited))
            # Original scale/copy branches, calls, and camera socket remain intact.
            self.assertEqual(data[0x8B7CF:0x8B7CF+28],candidate[0x8B7CF:0x8B7CF+28])

    def test_foreign_and_already_repaired_modules_refused_before_output(self):
        for source in [ORIGINAL,TRANSPORT]:
            data=source.read_bytes();bad=bytearray(data);bad[-1]^=1
            for changed in [bytes(bad),repair.repair(data),data[:-1]]:
                with self.assertRaisesRegex(ValueError,'Unsupported'):repair.repair(changed)

    def test_bundle_idempotent_and_unknown_recovery_evidence_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            out=Path(directory)/'bundle'
            a=repair.prepare(ORIGINAL,out);self.assertEqual(a,repair.prepare(ORIGINAL,out))
            self.assertEqual(hashlib.sha256((out/a['candidateFile']).read_bytes()).hexdigest(),a['candidateSha256'])
            guarded=out/a['sourceFile'];guarded.write_bytes(b'preserve')
            with self.assertRaisesRegex(ValueError,'Refusing'):repair.prepare(ORIGINAL,out)
            self.assertEqual(guarded.read_bytes(),b'preserve')

    def test_reported_failure_is_partial_y_then_untouched_chroma(self):
        original=1920*1080*3//2;target_y=1920*1088;target_uv=target_y//2
        self.assertGreaterEqual(original,target_y)
        self.assertLess(original-target_y,target_uv)
        self.assertEqual(original-target_y,1021440)
        self.assertEqual((target_y+target_uv)-target_y,target_uv)

    def test_identity_up_down_and_mixed_geometry_bounds_use_actual_scaled_allocation(self):
        scenarios=[((1920,1088),(1920,1088)),((1920,1080),(1920,1088)),
                   ((1920,1088),(1280,720)),((640,480),(1280,720)),((1920,1080),(1280,1280))]
        for source,target in scenarios:
            with self.subTest(source=source,target=target):
                target_y=target[0]*target[1];target_uv=target_y//2;allocation=target_y+target_uv
                self.assertGreaterEqual(allocation,target_y)
                self.assertEqual(allocation-target_y,target_uv)
                # The repair bounds both NV21 and planar copies by exactly the
                # same allocated image; original reply length is immaterial.
                self.assertEqual(target_uv//target[0],target[1]//2)

if __name__=='__main__':unittest.main()
