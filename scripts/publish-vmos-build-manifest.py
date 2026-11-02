#!/usr/bin/env python3
"""Validate canonical VMOS artifacts, then merge their publication manifests.

Run only after copying the final APKs. This does not build, install, or operate
an Android device. Historical emulator and stock-host proof fields stay intact.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
from datetime import datetime, timezone
import zipfile

PROXY_SHA = "1162c3ef0f3dc4a9b2ebb8fabf558dbbe31aead0ef4fa0c04897c5ca10721bcc"
WRAPPER_SHA = "9d13ca05e5963c9486f34435b8c96baf755ebdbca0112c382e7f2c103d717157"
ORIGINAL_SHA = "362261e9d1924bde73652cd8f2c467fbaabc59f50ad66e0b4de0215291ec6d3c"
TRANSPORT_SHA = "02042c56980813c89611698d6501ccd78ca05c8a9827a55aa2c21e634bd5086a"
LEGACY_TRANSPORT_SHA = "1c80cb9711b08d28625e140263b6bd85aadaf2c6a1de50d161e19fc6e1773a97"
FILES = (
    "VirtualCameraPro.apk",
    "vmos/VirtualCameraProVmosController.apk",
    "vmos/VirtualCameraProVmosControllerTests.apk",
    "vmos/VirtualCameraProVmosBroker.apk",
    "vmos/VirtualCameraProVmosBrokerTests.apk",
    "vmos/VirtualCameraProVmosBridge.apk",
    "vmos/VmosIndependentCameraClient.apk",
    "vmos/VmosIndependentCameraClientTests.apk",
    "vmos/vmos-camera-proxy",
    "vmos/vmos-camera-proxy-pre-crop-8855",
    "vmos/camera.vphw90.crop-wrapper.so",
    "VirtualCameraProGuestTools.apk",
    "VcamGuestDiagnostics.apk",
)
EVIDENCE = (
    "vmos/finite-acceptance.json",
    "vmos/green-fix-acceptance.json",
    "vmos/crop-acceptance.json",
    "vmos/acceptance-camera0/acceptance.json",
    "vmos/acceptance-camera1/acceptance.json",
    "vmos/acceptance-1080p/acceptance.json",
    "vmos/background-external-image-camera2-camerax.log",
    "vmos/cold-boot-recovery-state.txt",
    "vmos/cold-boot-camera-pipeline.log",
    "vmos/idle-proxy-recovery-pipeline.log",
    "vmos/active-proxy-recovery-pipeline.log",
    "vmos/camera2-crop-back-sampled/crop-result.json",
    "vmos/camera2-crop-video/crop-result.json",
    "vmos/crop-video-activation.json",
    "vmos/live-transform-device/live-transform.json",
    "vmos/live-transform-device.log",
    "vmos/final-wrapper-camera-pipeline.log",
    "vmos/final-before-cold-start.txt",
    "vmos/final-cold-start.txt",
)


def fingerprint(path: Path) -> dict:
    if not path.is_file() or path.is_symlink():
        raise ValueError(f"Required ordinary artifact is missing: {path}")
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
            size += len(chunk)
    if not size:
        raise ValueError(f"Empty artifact: {path}")
    return {"bytes": size, "sha256": digest.hexdigest()}


def read_json(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8-sig"))
    if not isinstance(value, dict):
        raise ValueError(f"Expected a JSON object: {path}")
    return value


def apk_asset(apk: Path, name: str, *, expected_size: int | None = None) -> bytes:
    with zipfile.ZipFile(apk) as archive:
        entries = [entry for entry in archive.infolist() if entry.filename == name]
        if len(entries) != 1 or entries[0].is_dir():
            raise ValueError(f"{apk.name}: expected exactly one {name}, found {len(entries)}")
        entry = entries[0]
        if expected_size is not None and entry.file_size != expected_size:
            raise ValueError(f"{apk.name}: {name} size differs from canonical artifact")
        if not 0 < entry.file_size <= 32 * 1024 * 1024:
            raise ValueError(f"{apk.name}: invalid asset length for {name}")
        return archive.read(entry)


def native_asset(apk: Path, name: str, expected: dict, *, sidecar: bool) -> dict:
    value = apk_asset(apk, f"assets/{name}", expected_size=expected["bytes"])
    digest = hashlib.sha256(value).hexdigest()
    if digest != expected["sha256"]:
        raise ValueError(f"{apk.name}: stale or changed native asset {name}")
    if sidecar:
        declared = apk_asset(apk, f"assets/{name}.sha256", expected_size=65)
        if declared != (digest + "\n").encode("ascii"):
            raise ValueError(f"{apk.name}: malformed or stale {name}.sha256")
    return {**expected, "sha256SidecarVerified": sidecar}


def validate_crop(path: Path, expected_camera: str) -> dict:
    value = read_json(path)
    if not (value.get("passed") is True and value.get("cameraTestExecuted") is True
            and value.get("cameraId") == expected_camera
            and value.get("cameraOpens") == 1 and value.get("sessionConfigurations") == 1
            and value.get("cameraControlClosed") is True and value.get("imageReaderClosed") is True):
        raise ValueError(f"Crop acceptance is incomplete: {path}")
    rows = value.get("observations", [])
    names = ["center-2x", "top-left-2x", "bottom-right-2x", "tall-center", "restored-full"]
    if len(rows) != 5 or [row.get("name") for row in rows] != names:
        raise ValueError(f"Crop acceptance lacks five required steps: {path}")
    timestamps = []
    for row in rows:
        if row.get("stableMatchingFrames", 0) < 3 or not 0 <= row.get("lastChannelMae", 99) <= 14:
            raise ValueError(f"Crop acceptance pixels did not pass: {path}")
        timestamps.append(row["actualTimestampNs"])
    if not all(a < b for a, b in zip([value["baselineTimestampNs"]] + timestamps, timestamps)):
        raise ValueError(f"Crop timestamps did not advance: {path}")
    return {"passed": True, "cameraId": expected_camera,
            "actualCapture": [value["width"], value["height"]],
            "sampleGrid": value["sampleGrid"], "cameraOpens": 1, "sessionConfigurations": 1,
            "settledMatchingFramesPerStep": 3,
            "updateToProofMs": [row["updateToObservedMs"] for row in rows],
            "maximumChannelMae": max(row["lastChannelMae"] for row in rows)}


def prepare(root: Path, *, device_identity: Path | None = None) -> tuple[dict, dict]:
    artifacts = root / "artifacts"
    top = read_json(artifacts / "BUILD-MANIFEST.json")
    previous_guest = read_json(artifacts / "vmos/broker-build-manifest.json")
    files = {name: fingerprint(artifacts / name) for name in FILES}
    if files["vmos/vmos-camera-proxy"]["sha256"] != PROXY_SHA:
        raise ValueError("Canonical proxy does not match the device-tested crop/aspect build")
    if files["vmos/camera.vphw90.crop-wrapper.so"]["sha256"] != WRAPPER_SHA:
        raise ValueError("Canonical wrapper does not match the device-tested repaired-transport build")
    if files["VirtualCameraProGuestTools.apk"] != files["VcamGuestDiagnostics.apk"]:
        raise ValueError("Guest-tools canonical APK and diagnostics alias differ")
    if files["VirtualCameraPro.apk"] != files["vmos/VirtualCameraProVmosController.apk"]:
        raise ValueError("Main controller APK and VMOS controller alias differ")
    source_proxy = fingerprint(root / "android/guest-tools/src/main/assets/vmos-camera-proxy")
    if source_proxy != files["vmos/vmos-camera-proxy"]:
        raise ValueError("Guest-tools source proxy differs from canonical proxy")

    broker = artifacts / "vmos/VirtualCameraProVmosBroker.apk"
    bundled = {name: native_asset(broker, name, files[f"vmos/{name}"], sidecar=True)
               for name in ("vmos-camera-proxy", "camera.vphw90.crop-wrapper.so")}
    helper = native_asset(artifacts / "VirtualCameraProGuestTools.apk", "vmos-camera-proxy",
                          files["vmos/vmos-camera-proxy"], sidecar=False)
    bundle = apk_asset(artifacts / "vmos/VirtualCameraProVmosController.apk", "assets/index.android.bundle")

    evidence = {name: fingerprint(artifacts / name) for name in EVIDENCE}
    for name in ("finite-acceptance.json", "green-fix-acceptance.json", "crop-acceptance.json"):
        proof = read_json(artifacts / f"vmos/{name}")
        if proof.get("passed") is not True and proof.get("status") != "passed":
            raise ValueError(f"Required acceptance report did not pass: {name}")
    for camera in ("camera0", "camera1", "1080p"):
        proof = read_json(artifacts / f"vmos/acceptance-{camera}/acceptance.json")
        if proof.get("passed") is not True or proof.get("stage") != "complete":
            raise ValueError(f"Selected-media acceptance is incomplete: {camera}")
    back = validate_crop(artifacts / "vmos/camera2-crop-back-sampled/crop-result.json", "0")
    video = validate_crop(artifacts / "vmos/camera2-crop-video/crop-result.json", "1")
    activation = read_json(artifacts / "vmos/crop-video-activation.json")
    if not (activation.get("type") == "video" and activation.get("phase") == "active"
            and activation.get("cameraId") == "1" and activation.get("proof", {}).get("sourceVerified") is True):
        raise ValueError("Video crop fixture has no independently verified active video source")
    live = read_json(artifacts / "vmos/live-transform-device/live-transform.json")
    if live.get("passed") is not True:
        raise ValueError("Controller live-transform acceptance did not pass")
    timestamp = datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    verification = {
        "canonicalApkPackagingVerified": True,
        "brokerNativeAssetsAndHashSidecarsVerified": True,
        "guestToolsEmbeddedProxyVerified": True,
        "controllerStandaloneJavascriptBundleVerified": True,
        "controllerJavascriptBundleBytes": len(bundle),
        "measuredGuestCameraReplacementVerified": True,
        "measuredGuestGreenPhotoRepairVerified": True,
        "measuredGuestCropVerified": True,
        "measuredGuestControllerLivePanZoomVerified": True,
        "backImageCrop": back, "frontCalibrationVideoCrop": video,
        "scope": "Infinix X687B Android10 host; VMOS alpha13 ROM10075 Android9/API28 ARM64 vphw90 guest only",
        "stockPhysicalHostCameraReplacementVerified": False,
        "otherDevicesAndAndroidVersionsVerified": False,
        "canonicalApkSetInstalledAndRetested": False,
        "note": "Publication verifies the final APK assets against the measured native builds. Device evidence retains its own tested APK/configuration history; publication alone does not establish a fresh install-and-retest of this complete APK set.",
        "evidenceRoot": "artifacts", "evidence": evidence,
    }
    if device_identity is not None:
        device_identity = device_identity.resolve()
        identity = read_json(device_identity)
        declared = identity.get("packageApkSha256", {})
        required = ("vmos/VirtualCameraProVmosController.apk", "vmos/VirtualCameraProVmosBroker.apk",
                    "VirtualCameraProGuestTools.apk")
        if identity.get("passed") is not True or not isinstance(declared, dict) or any(name not in declared for name in required):
            raise ValueError("Final device identity must pass and pin controller, broker and guest-tools runtime APKs")
        for name, digest in declared.items():
            if name not in files or not name.endswith(".apk") or files[name]["sha256"] != digest:
                raise ValueError(f"Final installed APK does not match canonical artifact: {name}")
        verification.update({"canonicalRuntimeApksInstalled": True,
                             "canonicalRuntimeApksRetested": identity.get("finalRuntimeRetestPassed") is True,
                             "installedPackageApkSha256": declared,
                             "deviceIdentity": {"path": os.path.relpath(device_identity, artifacts).replace(os.sep, "/"),
                                                **fingerprint(device_identity)}})
    guest = copy.deepcopy(previous_guest)
    guest.update({"schemaVersion": 2, "generatedAtUtc": timestamp,
                  "backend": "vmos_camera_proxy", "profile": "vmos-camera3-crop-v1",
                  "fileRoot": "artifacts", "files": files, "verification": verification,
                  "guest": {"sdk": 28, "hardware": "vphw90", "abi": "arm64-v8a",
                            "originalHalSha256": ORIGINAL_SHA, "legacySocketHalSha256": LEGACY_TRANSPORT_SHA,
                            "patchedTransportHalSha256": TRANSPORT_SHA,
                            "loadedCropAdapterSha256": WRAPPER_SHA},
                  "bundledProxySha256": PROXY_SHA, "bundledWrapperSha256": WRAPPER_SHA,
                  "apkAssetsVerified": {"broker": bundled, "guestTools": helper}})
    merged = copy.deepcopy(top)
    merged.setdefault("files", {}).update(files)
    merged.update({"generatedAtUtc": timestamp, "vmosVerification": verification,
                   "vmosManifest": "vmos/broker-build-manifest.json"})
    return guest, merged


def publish(root: Path, *, check_only: bool = False, device_identity: Path | None = None) -> tuple[dict, dict]:
    guest, top = prepare(root, device_identity=device_identity)
    if not check_only:
        # Validate everything before staging either manifest. Each replacement is
        # atomic; both carry the same generation timestamp for interrupted writes.
        staged = []
        try:
            for relative, value in (("vmos/broker-build-manifest.json", guest), ("BUILD-MANIFEST.json", top)):
                destination = root / "artifacts" / relative
                with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline="\n",
                                                 dir=destination.parent, prefix=".vcam-manifest-",
                                                 suffix=".tmp", delete=False) as stream:
                    staged.append((Path(stream.name), destination))
                    json.dump(value, stream, indent=2, ensure_ascii=False)
                    stream.write("\n"); stream.flush(); os.fsync(stream.fileno())
            for temporary, destination in staged:
                os.replace(temporary, destination)
        finally:
            for temporary, _ in staged:
                temporary.unlink(missing_ok=True)
    return guest, top


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1],
                        help="Workspace root (primarily for isolated packaging tests)")
    parser.add_argument("--check-only", action="store_true", help="Validate without writing either manifest")
    parser.add_argument("--device-identity", type=Path,
                        help="Optional passing on-device APK identity report; hashes must match published runtime APKs")
    args = parser.parse_args()
    try:
        guest, _ = publish(args.root.resolve(), check_only=args.check_only, device_identity=args.device_identity)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, f"VMOS publication refused: {error}\n")
    print(f"VMOS packaging verified: {len(guest['files'])} artifacts; proxy {PROXY_SHA}; wrapper {WRAPPER_SHA}")
    print("No manifests written (--check-only)." if args.check_only else "Updated both manifests; retained historical emulator and stock-host verification.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
