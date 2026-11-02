#!/usr/bin/env python3
"""Prepare exact-build VMOS JPEG repair candidates without accessing a device.

The candidates address two defects measured in Android 9 ARM64 ROM 10075:
the camera provider passes image stride for a byte-addressed BLOB allocation,
and the ARM64 camera HAL loads its JPEG helper from the missing lib32 path.
Preparation is not evidence that either candidate works on the guest.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import struct
import sys


PROFILE = "vmos-android9-arm64-rom10075-jpeg-repair"
IMPORTER_SHA256 = "be8cbff7d20ec7f9e8ea16645ca919196e174911bb7cfcfbcac458acaa8b6acc"
CAMERA_HAL_SHA256 = "362261e9d1924bde73652cd8f2c467fbaabc59f50ad66e0b4de0215291ec6d3c"
IMPORTER_OFFSET = 0x15D0C
IMPORTER_OLD = bytes.fromhex("27 63 40 b9")  # ldr w7, [x25, #0x60]
IMPORTER_NEW = bytes.fromhex("e7 2f 40 b9")  # ldr w7, [sp, #0x2c]
JPEG_PATH_OFFSET = 0x8F3A0
JPEG_PATH_OLD = b"/vendor/lib/hw/camera.vphw90.jpeg.so"
JPEG_PATH_NEW = b"/vendor/lib64/hw/camera.vphw90.jpeg.so"
JPEG_PATH_ALLOCATION = 40  # Ends immediately before the existing jump table.


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def require_elf(original: bytes, digest: str) -> None:
    if sha256(original) != digest:
        raise ValueError("Unsupported module SHA-256; no candidate may be installed")
    if original[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<HH", original, 16) != (3, 183):
        raise ValueError("Expected an ELF64 little-endian AArch64 shared library")


def replace_exact(original: bytes, offset: int, old: bytes, new: bytes) -> bytes:
    if len(old) != len(new) or original[offset:offset + len(old)] != old:
        raise ValueError("Pinned instruction or string allocation does not match")
    return original[:offset] + new + original[offset + len(old):]


def repair_importer(original: bytes) -> bytes:
    require_elf(original, IMPORTER_SHA256)
    # The exact format comparison and BLOB argument setup remain intact. Full
    # module pinning plus these guards document why the modified load is scoped.
    if original[0x156A4:0x156B0] != bytes.fromhex("9f 86 00 71 fa 2b 00 f9 a1 03 00 54"):
        raise ValueError("Expected BLOB-only branch guard is missing")
    if original[0x15D10:0x15D20] != bytes.fromhex("e1 2b 40 f9 e2 2f 40 b9 e8 a3 01 91 e3 03 00 32"):
        raise ValueError("Expected BLOB capacity and height argument setup is missing")
    return replace_exact(original, IMPORTER_OFFSET, IMPORTER_OLD, IMPORTER_NEW)


def repair_jpeg_path(original: bytes) -> bytes:
    require_elf(original, CAMERA_HAL_SHA256)
    old = JPEG_PATH_OLD.ljust(JPEG_PATH_ALLOCATION, b"\0")
    new = (JPEG_PATH_NEW + b"\0").ljust(JPEG_PATH_ALLOCATION, b"\0")
    if len(new) != JPEG_PATH_ALLOCATION:
        raise ValueError("The 64-bit JPEG helper pathname exceeds its reserved allocation")
    return replace_exact(original, JPEG_PATH_OFFSET, old, new)


def prepare(importer: Path, camera_hal: Path | None, destination: Path) -> dict:
    original_importer = importer.read_bytes()
    patched_importer = repair_importer(original_importer)
    components = [{
        "guestPath": "/vendor/lib64/camera.device@3.2-impl.so",
        "originalFile": "camera.device@3.2-impl.original.so",
        "candidateFile": "camera.device@3.2-impl.so",
        "originalSha256": sha256(original_importer),
        "candidateSha256": sha256(patched_importer),
        "size": len(original_importer),
        "edit": {"fileOffset": IMPORTER_OFFSET, "virtualAddress": 0x16D0C,
                 "beforeHex": IMPORTER_OLD.hex(), "afterHex": IMPORTER_NEW.hex(),
                 "reason": "For BLOB output only, use the already computed allocation byte width as stride; retain host buffer validation."},
    }]
    outputs = [(components[0]["originalFile"], original_importer),
               (components[0]["candidateFile"], patched_importer)]
    if camera_hal is not None:
        original_hal = camera_hal.read_bytes()
        patched_hal = repair_jpeg_path(original_hal)
        components.append({
            "guestPath": "/vendor/lib64/hw/camera.vphw90.so",
            "originalFile": "camera.vphw90.original.so",
            "candidateFile": "camera.vphw90.so",
            "originalSha256": sha256(original_hal),
            "candidateSha256": sha256(patched_hal),
            "size": len(original_hal),
            "edit": {"fileOffset": JPEG_PATH_OFFSET, "allocationBytes": JPEG_PATH_ALLOCATION,
                     "from": JPEG_PATH_OLD.decode(), "to": JPEG_PATH_NEW.decode(),
                     "reason": "Load the verified existing ARM64 JPEG encoder from lib64 instead of the missing lib32 pathname."},
        })
        outputs.extend([(components[1]["originalFile"], original_hal),
                        (components[1]["candidateFile"], patched_hal)])
    manifest = {
        "schemaVersion": 1, "profile": PROFILE,
        "status": "prepared_uninstalled_unverified",
        "guest": {"api": 28, "android": "9", "hardware": "vphw90", "abi": "arm64-v8a", "romVersion": 10075},
        "components": components,
        "requirements": [
            "Verify the exact guest identity and every installed original module hash immediately before testing.",
            "Keep exact recovery copies outside the guest and record original ownership, permissions and labels.",
            "Restart only the guest camera provider after any explicitly authorized candidate replacement.",
            "Test ordinary baseline Camera2 YUV and single JPEG first, before configuring virtual media.",
            "Keep host gralloc validation enabled; a failed candidate must be restored, not accepted as camera support.",
            "The JPEG helper must already exist as an audited ARM64 ELF with the required JpegStub exports.",
        ],
        "notes": ["This script performs offline preparation only and never invokes ADB, root, a shell or a phone command.",
                  "No native proxy, socket redirect, boot image, physical phone library or global gralloc policy is changed."],
    }
    outputs.append(("jpeg-repair.json", (json.dumps(manifest, indent=2) + "\n").encode()))
    for name, content in outputs:
        path = destination / name
        if path.exists() and path.read_bytes() != content:
            raise ValueError(f"Refusing to overwrite a different existing bundle file: {path}")
    destination.mkdir(parents=True, exist_ok=True)
    for name, content in outputs:
        path = destination / name
        if not path.exists():
            with path.open("xb") as output:
                output.write(content)
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--importer", type=Path, required=True)
    parser.add_argument("--camera-hal", type=Path)
    parser.add_argument("--out-dir", type=Path, default=Path("artifacts/vmos/jpeg-repair-prepared"))
    args = parser.parse_args()
    try:
        manifest = prepare(args.importer, args.camera_hal, args.out_dir)
        print(json.dumps({"profile": PROFILE, "status": manifest["status"], "components": manifest["components"]}))
        return 0
    except (OSError, ValueError, struct.error) as error:
        print(f"VMOS JPEG candidate preparation failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
