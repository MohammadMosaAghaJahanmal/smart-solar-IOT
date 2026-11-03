#!/usr/bin/env python3
"""Read a pulled setup-preferences XML without restarting or operating the Android app.

Usage: python scripts/extract-unified-setup-snapshot.py preferences.xml --output snapshot.json
The full sanitized snapshot is written when --output is supplied; stdout is a compact summary.
Pull /data/user/0/com.virtualcamerapro/shared_prefs/virtual-camera-unified-setup-v1.xml
using the already-authorized guest ADB connection. This script itself never invokes ADB or su.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys
from typing import Any
import xml.etree.ElementTree as ET

MAX_INPUT_BYTES = 4 * 1024 * 1024
PRIVATE_KEYS = re.compile(
    r"sourcePath|sourceUri|sourceFile|sourceSha256|sourceHash|sourceDigest|mediaPath|"
    r"mediaUri|mediaName|runtimeDirectory|selectedMedia|serialNumber|androidId|"
    r"accessToken|refreshToken|password|apiKey", re.IGNORECASE
)
PRIVATE_DIRECTORY = re.compile(r"/(?:data/user/\d+|data/data)/com\.virtualcamerapro/files")
MEDIA_URI = re.compile(r"(?:content|file)://[^\s\"'<>]+", re.IGNORECASE)
PRIVATE_SOURCE = re.compile(r"source-[A-Za-z0-9._-]+")


def sanitize(value: Any) -> Any:
    if isinstance(value, dict):
        return {key: sanitize(item) for key, item in value.items() if not PRIVATE_KEYS.search(key)}
    if isinstance(value, list):
        return [sanitize(item) for item in value]
    if isinstance(value, str):
        text = MEDIA_URI.sub("<media-reference>", value)
        text = PRIVATE_DIRECTORY.sub("<private-files>", text)
        return PRIVATE_SOURCE.sub("<selected-media>", text)[:8192]
    return value


def extract_snapshot(data: bytes) -> dict[str, Any]:
    if len(data) > MAX_INPUT_BYTES:
        raise ValueError("Setup preferences exceed the diagnostic input limit")
    root = ET.fromstring(data)
    if root.tag != "map":
        raise ValueError("Expected an Android SharedPreferences map")
    entries = [entry for entry in root.findall("string") if entry.get("name") == "snapshot"]
    if len(entries) != 1 or not entries[0].text:
        raise ValueError("A unique persisted setup snapshot is not available yet")
    snapshot = json.loads(entries[0].text)
    if not isinstance(snapshot, dict) or not isinstance(snapshot.get("state"), str):
        raise ValueError("The persisted setup snapshot has an invalid structure")
    return sanitize(snapshot)


def summary(snapshot: dict[str, Any]) -> dict[str, Any]:
    keys = ("state", "ready", "supported", "setupBackend", "title", "message", "recovery",
            "errorCode", "currentStep", "operation", "profile", "preferences", "lastVerifiedAt", "updatedAt")
    result = {key: snapshot[key] for key in keys if key in snapshot}
    components = snapshot.get("components")
    if isinstance(components, dict) and isinstance(components.get("root"), dict):
        root = components["root"]
        result["root"] = {key: root[key] for key in
                          ("available", "verified", "code", "requiresAuthorization", "authorizationBridge") if key in root}
        last_check = root.get("lastAuthorizationCheck")
        if isinstance(last_check, dict):
            result["root"]["lastAuthorizationCheck"] = {
                key: last_check[key] for key in
                ("verified", "code", "message", "recovery", "checkedAt", "startedAt", "completedAt",
                 "startedElapsedRealtimeMs", "completedElapsedRealtimeMs", "authorizationBridge") if key in last_check
            }
    result["evidenceSource"] = "persisted application snapshot; no live action was performed"
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", help="Pulled SharedPreferences XML, or - for stdin")
    parser.add_argument("--output", type=Path, help="Write the complete sanitized snapshot as UTF-8 JSON")
    args = parser.parse_args(argv)
    try:
        if args.input == "-":
            data = sys.stdin.buffer.read(MAX_INPUT_BYTES + 1)
        else:
            with Path(args.input).open("rb") as stream:
                data = stream.read(MAX_INPUT_BYTES + 1)
        snapshot = extract_snapshot(data)
        if args.output:
            args.output.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(summary(snapshot), ensure_ascii=True, indent=2))
        return 0
    except (OSError, ValueError, ET.ParseError) as error:
        print(json.dumps({"error": str(error), "retry": "Pull the preferences file again if a write was in progress"}), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
