#!/usr/bin/env python3
"""Restore one owned emulator's baseline after its companion process exits."""

import argparse
import ctypes
from ctypes import wintypes
import importlib.util
import json
import os
from pathlib import Path
import re
import time


ROOT = Path(__file__).resolve().parent.parent
DATA_ROOT = ROOT / "artifacts/emulator-host"
_lock_spec = importlib.util.spec_from_file_location("vcam_owner_lock", ROOT / "scripts/emulator-camera-lock.py")
owner_lock = importlib.util.module_from_spec(_lock_spec)
_lock_spec.loader.exec_module(owner_lock)


def wait_for_parent(pid: int) -> None:
    if os.name != "nt":
        while True:
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                return
            time.sleep(0.5)
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.OpenProcess.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.WaitForSingleObject.argtypes = (wintypes.HANDLE, wintypes.DWORD)
    kernel.WaitForSingleObject.restype = wintypes.DWORD
    kernel.CloseHandle.argtypes = (wintypes.HANDLE,)
    kernel.CloseHandle.restype = wintypes.BOOL
    handle = kernel.OpenProcess(0x00100000, False, pid)  # SYNCHRONIZE only
    if not handle:
        code = ctypes.get_last_error()
        if code in (87, 1168):  # The process already exited.
            return
        raise ctypes.WinError(code)
    try:
        while True:
            result = kernel.WaitForSingleObject(handle, 1000)
            if result == 0:
                return
            if result != 258:  # WAIT_TIMEOUT
                raise ctypes.WinError(ctypes.get_last_error())
    finally:
        kernel.CloseHandle(handle)


def restore_after_parent(avd: str, pid: int, instance: str) -> dict:
    try:
        owner = owner_lock.AvdProcessLock(avd, DATA_ROOT / "locks").acquire()
    except owner_lock.OwnerBusyError:
        # A new owner performs recovery before accepting requests. Never race
        # its recovery or overwrite a scene it subsequently selects.
        return {"restored": False, "reason": "another_owner_running"}
    try:
        return restore_owned(avd, pid, instance)
    finally:
        owner.close()


def restore_owned(avd: str, pid: int, instance: str) -> dict:
    journal_path = DATA_ROOT / f"{avd}-session.json"
    journal = json.loads(journal_path.read_text(encoding="utf-8"))
    if journal.get("hostPid") != pid or journal.get("instanceId") != instance or journal.get("avd") != avd:
        return {"restored": False, "reason": "session_replaced"}
    if not journal.get("rendererConfigured"):
        return {"restored": False, "reason": "parent_shutdown_clean"}
    spec = importlib.util.spec_from_file_location("vcam_environment", ROOT / "scripts/emulator-environment.py")
    environment = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(environment)
    discovery, live = environment.discover(avd, None)
    if (str(discovery) != journal.get("discoveryFile") or live["avd.dir"] != journal.get("avdDir")
            or discovery.stat().st_mtime_ns != journal.get("discoveryMtimeNs")):
        return {"restored": False, "reason": "emulator_restarted"}
    saved = json.loads((ROOT / "artifacts/emulator-environment" / f"{avd}.json").read_text(encoding="utf-8"))
    if saved.get("avd") != avd or saved.get("avdDir") != live["avd.dir"]:
        raise ValueError("Baseline belongs to another emulator")
    environment.reload_environment(live, saved["environmentConfig"])
    journal["rendererConfigured"] = False
    journal["watchdogRestored"] = True
    temporary = journal_path.with_suffix(".watchdog-tmp")
    temporary.write_text(json.dumps(journal, indent=2), encoding="utf-8")
    temporary.replace(journal_path)
    return {"restored": True, "reason": "parent_exited", "avd": avd, "hostPid": pid, "instanceId": instance}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--avd", required=True)
    parser.add_argument("--watch-parent", required=True, type=int)
    parser.add_argument("--instance", required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", args.avd) or not re.fullmatch(r"[a-f0-9]{32}", args.instance):
        parser.error("Invalid emulator or session identity")
    if args.watch_parent <= 1:
        parser.error("Invalid parent process")
    wait_for_parent(args.watch_parent)
    result = None
    for attempt in range(3):
        try:
            result = restore_after_parent(args.avd, args.watch_parent, args.instance)
            break
        except Exception as error:
            result = {"restored": False, "reason": "restore_failed", "error": str(error)}
            if attempt < 2:
                time.sleep(1)
    (DATA_ROOT / f"watchdog-{args.instance}.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    return 0 if result.get("restored") or result.get("reason") != "restore_failed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
