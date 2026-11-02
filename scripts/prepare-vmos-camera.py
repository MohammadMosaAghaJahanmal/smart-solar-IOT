#!/usr/bin/env python3
"""Prepare, but never install, an exact-build VMOS guest camera socket redirect.

This profile comes from the public rootfs bundled in com.vmos.virtual
2.0.0-alpha13 (20013), ROM 10075, Android 9 ARM64. It is deliberately not a
generic Android HAL patcher. The original module must match byte for byte.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import struct
import sys


PROFILE = "vmos-android9-arm64-rom10075"
ORIGINAL_SHA256 = "362261e9d1924bde73652cd8f2c467fbaabc59f50ad66e0b4de0215291ec6d3c"
ORIGINAL_SOCKET = b"/dev/socket/socket/qemu_pipe"
PROXY_SOCKET = b"/data/local/tmp/vcam_pipe"
SOCKET_OFFSET = 571343
GUEST_MODULE = "/vendor/lib64/hw/camera.vphw90.so"
ORIGINAL_FILE = "camera.vphw90.original.so"
PATCHED_FILE = "camera.vphw90.so"
MANIFEST_FILE = "installation.json"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def patch_module(original: bytes) -> bytes:
    if sha256(original) != ORIGINAL_SHA256:
        raise ValueError("Unsupported camera module SHA-256; no output was written")
    # ELF64, little endian, ET_DYN, AArch64. These checks also document the ABI
    # required by this one profile rather than accepting a similarly named file.
    if (original[:6] != b"\x7fELF\x02\x01" or
            struct.unpack_from("<HH", original, 16) != (3, 183)):
        raise ValueError("Expected an ELF64 little-endian AArch64 shared library")
    old = ORIGINAL_SOCKET + b"\0"
    if len(PROXY_SOCKET) >= len(old):
        raise ValueError("Socket redirect must fit the existing ELF string allocation")
    new = PROXY_SOCKET.ljust(len(old), b"\0")
    if original.count(old) != 1 or original.find(old) != SOCKET_OFFSET:
        raise ValueError("Expected camera socket literal was not found exactly once at its pinned offset")
    patched = original[:SOCKET_OFFSET] + new + original[SOCKET_OFFSET + len(old):]
    # Only the path literal changes, padded with NUL bytes to retain the same
    # allocation. Executable code, camera metadata and stream sizes stay intact.
    if (len(patched) != len(original) or patched[:SOCKET_OFFSET] != original[:SOCKET_OFFSET]
            or patched[SOCKET_OFFSET + len(old):] != original[SOCKET_OFFSET + len(old):]):
        raise ValueError("Unexpected bytes changed while preparing the redirect")
    return patched


def write_once_or_identical(path: Path, contents: bytes) -> None:
    if path.exists():
        if path.read_bytes() != contents:
            raise ValueError(f"Refusing to replace a different existing file: {path}")
        return
    # Exclusive creation preserves earlier originals or recovery bundles.
    with path.open("xb") as output:
        output.write(contents)


def prepare(original_path: Path, out_dir: Path) -> dict:
    original = original_path.read_bytes()
    patched = patch_module(original)
    manifest = {
        "schemaVersion": 1,
        "profile": PROFILE,
        "status": "prepared_uninstalled_unverified",
        "hostPackage": "com.vmos.virtual",
        "hostVersionName": "2.0.0-alpha13",
        "hostVersionCode": 20013,
        "guest": {"android": "9", "api": 28, "abi": "arm64-v8a", "romVersion": 10075},
        "guestModule": GUEST_MODULE,
        "guestProviderService": "vendor.camera-provider-2-4",
        "original": {"file": ORIGINAL_FILE, "size": len(original), "sha256": sha256(original)},
        "patched": {"file": PATCHED_FILE, "size": len(patched), "sha256": sha256(patched)},
        "redirect": {"offset": SOCKET_OFFSET, "from": ORIGINAL_SOCKET.decode(), "to": PROXY_SOCKET.decode()},
        "requirements": [
            "Explicitly identify the Android 9 VMOS guest; never install this module on the physical host",
            "Confirm guest module SHA-256 equals original.sha256 immediately before any replacement",
            "Confirm guest root and a working guest recovery or backup route",
            "Start and verify the native proxy and its pass-through to the original socket first",
            "Preserve the installed module ownership, permissions and security label",
            "Retain a recovery copy outside the guest before the one-time provider restart",
            "Verify Camera2, CameraX and original Camera preview and JPEGs with selected media",
        ],
        "rollback": {
            "file": ORIGINAL_FILE,
            "guestModule": GUEST_MODULE,
            "sha256": ORIGINAL_SHA256,
            "action": "Restore the original module with its original attributes, then restart the guest camera provider",
        },
        "notes": [
            "Offline preparation only; this script never invokes ADB, root, a shell or a phone command",
            "The redirect retains the existing guest HAL3 request, metadata, JPEG and gralloc implementation",
            "A prepared redirect alone does not deliver media and must not mark the application ready",
            "The guest root filesystem is derived from the public VMOS APK; no VMOS private app data is used",
            "This profile provides no compatibility claim for another VMOS ROM or Android version",
        ],
    }
    manifest_bytes = (json.dumps(manifest, indent=2) + "\n").encode()
    # Validate every existing output before creating any part of a bundle.
    files = [(ORIGINAL_FILE, original), (PATCHED_FILE, patched), (MANIFEST_FILE, manifest_bytes)]
    for name, contents in files:
        target = out_dir / name
        if target.exists() and target.read_bytes() != contents:
            raise ValueError(f"Refusing to replace a different existing file: {target}")
    out_dir.mkdir(parents=True, exist_ok=True)
    for name, contents in files:
        write_once_or_identical(out_dir / name, contents)
    return manifest


def verify(manifest_path: Path) -> dict:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("profile") != PROFILE or manifest.get("schemaVersion") != 1:
        raise ValueError("Unsupported preparation manifest")
    expected_names = {"original": ORIGINAL_FILE, "patched": PATCHED_FILE}
    data = {}
    for kind, name in expected_names.items():
        entry = manifest.get(kind, {})
        if entry.get("file") != name:
            raise ValueError(f"Unexpected {kind} bundle filename")
        data[kind] = (manifest_path.parent / name).read_bytes()
        if len(data[kind]) != entry.get("size") or sha256(data[kind]) != entry.get("sha256"):
            raise ValueError(f"The {kind} module no longer matches its manifest")
    if patch_module(data["original"]) != data["patched"]:
        raise ValueError("Prepared module does not match the exact approved transformation")
    rollback = manifest.get("rollback", {})
    if (manifest.get("redirect") != {"offset": SOCKET_OFFSET, "from": ORIGINAL_SOCKET.decode(), "to": PROXY_SOCKET.decode()}
            or manifest.get("guestModule") != GUEST_MODULE
            or manifest.get("guestProviderService") != "vendor.camera-provider-2-4"
            or manifest.get("hostPackage") != "com.vmos.virtual"
            or manifest.get("hostVersionCode") != 20013
            or manifest.get("guest") != {"android": "9", "api": 28, "abi": "arm64-v8a", "romVersion": 10075}
            or manifest.get("status") != "prepared_uninstalled_unverified"
            or rollback.get("file") != ORIGINAL_FILE
            or rollback.get("guestModule") != GUEST_MODULE
            or rollback.get("sha256") != ORIGINAL_SHA256):
        raise ValueError("Unexpected install or rollback metadata")
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--original", type=Path, help="Exact unmodified guest camera.vphw90.so")
    mode.add_argument("--verify", type=Path, help="Verify a prepared installation.json and both module copies")
    parser.add_argument("--out-dir", type=Path, default=Path("artifacts/vmos/prepared"))
    args = parser.parse_args()
    try:
        if args.verify:
            result = verify(args.verify)
            action = "verified"
        else:
            result = prepare(args.original, args.out_dir)
            action = "prepared"
        print(json.dumps({"action": action, "profile": result["profile"], "status": result["status"],
                          "originalSha256": result["original"]["sha256"],
                          "patchedSha256": result["patched"]["sha256"]}))
        return 0
    except (OSError, ValueError, KeyError, struct.error) as error:
        print(f"VMOS camera preparation failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
