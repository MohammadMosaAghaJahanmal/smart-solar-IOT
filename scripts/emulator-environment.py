#!/usr/bin/env python3
"""Change a named, locally running Android emulator's environment camera.

Requires grpcio and emulator 37's incubating VirtualSceneService. Run
``--avd NAME --prepare`` while the AVD is stopped, then launch it with
``-camera-back environment -grpc PORT -grpc-use-token``. A true
RPC response confirms renderer configuration only; use a separate Camera2
client to verify the resulting frames before reporting camera activation.

Upstream wire schema:
https://android.googlesource.com/platform/hardware/google/aemu/+/refs/heads/emu-main-dev/protos/services/incubating/virtualscene/virtual_scene_service.proto
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import sys


RPC = "/android.emulation.control.incubating.VirtualSceneService/reloadEnvironment"
ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ENVIRONMENT = "version=1\nscene.mode=mesh3d:Toren1BD.obj\nbackground.enabled=false\n"


def read_ini(path: Path) -> dict[str, str]:
    result = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if "=" in line and not line.lstrip().startswith(("#", ";")):
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    return result


def discover(avd: str, discovery: Path | None) -> tuple[Path, dict[str, str]]:
    if discovery:
        candidates = [discovery.resolve(strict=True)]
    else:
        running = Path(os.environ.get("LOCALAPPDATA", "")) / "Temp/avd/running"
        candidates = list(running.glob("pid_*.ini"))
    found = []
    for candidate in candidates:
        try:
            info = read_ini(candidate)
        except (OSError, UnicodeError):
            continue
        if info.get("avd.id") == avd or info.get("avd.name") == avd:
            found.append((candidate, info))
    if len(found) != 1:
        raise ValueError(f"Expected one running {avd!r} AVD, found {len(found)}")
    path, info = found[0]
    if not info.get("grpc.token"):
        raise ValueError("Selected emulator has no authenticated gRPC token")
    port = int(info.get("grpc.port", "0"))
    serial_port = int(info.get("port.serial", "0"))
    if not (1024 <= port <= 65535 and 5554 <= serial_port <= 5682):
        raise ValueError("Invalid emulator discovery ports")
    avd_dir = Path(info.get("avd.dir", "")).resolve(strict=True)
    if not (avd_dir / "config.ini").is_file():
        raise ValueError("Discovery does not refer to a valid AVD directory")
    return path, info


def prepare(avd: str) -> dict:
    running = Path(os.environ.get("LOCALAPPDATA", "")) / "Temp/avd/running"
    for candidate in running.glob("pid_*.ini"):
        info = read_ini(candidate)
        if info.get("avd.id") == avd or info.get("avd.name") == avd:
            raise ValueError("Stop the selected AVD before preparing its environment.ini")
    avd_home = os.environ.get("ANDROID_AVD_HOME")
    if avd_home:
        registry = Path(avd_home)
    else:
        user_home = os.environ.get("ANDROID_USER_HOME")
        registry = Path(user_home) / "avd" if user_home else Path.home() / ".android/avd"
    avd_config = read_ini((registry / f"{avd}.ini").resolve(strict=True))
    avd_dir = Path(avd_config.get("path", "")).resolve(strict=True)
    if not (avd_dir / "config.ini").is_file():
        raise ValueError("Named AVD registry does not refer to a valid AVD")
    environment = avd_dir / "environment.ini"
    existed = environment.exists()
    if not existed:
        # The emulator must load an environment.ini at startup before its
        # reloadEnvironment implementation accepts any runtime updates.
        with environment.open("x", encoding="utf-8", newline="\n") as target:
            target.write(DEFAULT_ENVIRONMENT)
    return {"avd": avd, "environmentIni": str(environment), "created": not existed}


def varint(value: int) -> bytes:
    data = bytearray()
    while value > 127:
        data.append((value & 127) | 128)
        value >>= 7
    data.append(value)
    return bytes(data)


def reload_environment(info: dict[str, str], config: str) -> None:
    try:
        import grpc
    except ImportError as exc:
        raise ValueError("Install the Python grpcio package to use this script") from exc
    # ReloadEnvironmentRequest.environment_config = field 1, UTF-8 string;
    # ReloadEnvironmentResponse.success = field 1, boolean. No generated
    # bindings or guessed emulator-controller methods are needed.
    body = config.encode("utf-8")
    request = b"\x0a" + varint(len(body)) + body
    with grpc.insecure_channel(f"127.0.0.1:{int(info['grpc.port'])}") as channel:
        invoke = channel.unary_unary(RPC)
        response = invoke(
            request,
            metadata=(("authorization", "Bearer " + info["grpc.token"]),),
            timeout=20,
        )
    if response != b"\x08\x01":
        raise ValueError("Emulator rejected the environment configuration")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--avd", required=True, help="Exact expected AVD name")
    parser.add_argument("--discovery", type=Path, help="Explicit emulator discovery INI")
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--source", type=Path, help="Host image/video path")
    action.add_argument("--restore", action="store_true", help="Restore saved environment")
    action.add_argument("--prepare", action="store_true", help="Create environment.ini while the AVD is stopped")
    parser.add_argument("--kind", choices=("image", "video"))
    parser.add_argument("--state", type=Path, help="Workspace baseline JSON path")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", args.avd):
        parser.error("AVD name must contain only letters, digits, dots, underscores, or hyphens")
    if args.source and not args.kind:
        parser.error("--source requires --kind image or video")
    if args.prepare:
        print(json.dumps(prepare(args.avd), indent=2))
        return 0
    discovery, info = discover(args.avd, args.discovery)
    state = (args.state or ROOT / "artifacts/emulator-environment" / f"{args.avd}.json").resolve()
    if not state.is_relative_to(ROOT):
        raise ValueError("Baseline state must stay within this project")
    saved = None
    if state.exists():
        saved = json.loads(state.read_text(encoding="utf-8"))
        if saved.get("avd") != args.avd or saved.get("avdDir") != info["avd.dir"]:
            raise ValueError("Baseline belongs to a different AVD")
    if args.restore:
        if saved is None:
            raise ValueError("No saved environment is available to restore")
        config = saved["environmentConfig"]
    else:
        source = args.source.resolve(strict=True)
        if not source.is_file() or any(c in str(source) for c in "\r\n|"):
            raise ValueError("Source must be a regular file with no INI line separators")
        if not state.exists():
            environment = Path(info["avd.dir"]) / "environment.ini"
            if not environment.is_file():
                raise ValueError("AVD must load environment.ini at startup: stop it, run --prepare, and relaunch")
            baseline = environment.read_text(encoding="utf-8-sig")
            state.parent.mkdir(parents=True, exist_ok=True)
            state.write_text(json.dumps({
                "avd": args.avd,
                "avdDir": info["avd.dir"],
                "environmentConfig": baseline,
                "environmentFileExisted": environment.exists(),
            }, indent=2), encoding="utf-8")
        config = f"version=1\nscene.mode={'videofile' if args.kind == 'video' else 'imagefile'}:{source}\nbackground.enabled=false\n"
    reload_environment(info, config)
    print(json.dumps({
        "avd": args.avd,
        "serial": f"emulator-{info['port.serial']}",
        "discovery": str(discovery),
        "rendererAccepted": True,
        "restored": args.restore,
        "source": None if args.restore else str(source),
        "cameraFramesVerified": False,
    }, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"Environment change failed: {error}", file=sys.stderr)
        raise SystemExit(1)
