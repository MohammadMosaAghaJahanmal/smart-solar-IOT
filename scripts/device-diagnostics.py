#!/usr/bin/env python3
"""Read-only evidence collection. Never roots, unlocks, flashes, or selects a phone implicitly."""
import argparse
import concurrent.futures
import datetime
import json
import pathlib
import platform
import shutil
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]

def run(command, timeout=20):
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=timeout, encoding='utf-8', errors='replace')
        return {'exitCode': result.returncode, 'output': result.stdout.strip(), 'error': result.stderr.strip()}
    except (OSError, subprocess.TimeoutExpired) as error:
        return {'exitCode': None, 'error': str(error)}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', help='Explicit ADB target; omit to inventory only')
    parser.add_argument('--out', type=pathlib.Path, default=ROOT/'artifacts/device-diagnostics.json')
    args = parser.parse_args()
    report = {'collectedAtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'host': platform.platform()}
    report['storage'] = {str(drive): {'freeGiB': round(shutil.disk_usage(drive).free/2**30, 2)} for drive in
                         ([pathlib.Path('C:/'), pathlib.Path('D:/')] if platform.system() == 'Windows' else [ROOT]) if drive.exists()}
    adb = shutil.which('adb')
    report['adbDevices'] = run([adb, 'devices', '-l']) if adb else {'error': 'ADB not found'}
    if platform.system() == 'Windows':
        report['wslBuildHost'] = run(['wsl.exe', '-d', 'Ubuntu-22.04', '--', 'bash', '-lc',
            'uname -m; free -m; ls -l /dev/kvm; id; command -v repo || true'])
        report['storageNote'] = 'WSL virtual disk capacity does not represent free space on its Windows backing volume.'
    if args.serial and adb:
        queries = {
            'androidVersion': 'getprop ro.build.version.release', 'sdk': 'getprop ro.build.version.sdk',
            'architecture': 'getprop ro.product.cpu.abilist', 'model': 'getprop ro.product.model',
            'fingerprint': 'getprop ro.build.fingerprint', 'debuggable': 'getprop ro.debuggable',
            'shellIdentity': 'id', 'suBinary': 'command -v su',
            'flashLocked': 'getprop ro.boot.flash.locked', 'vbmetaState': 'getprop ro.boot.vbmeta.device_state',
            'verifiedBoot': 'getprop ro.boot.verifiedbootstate', 'selinux': 'getenforce',
            'protocol': 'getprop ro.virtualcamera.pro.version', 'providerService': 'service check virtual_camera',
            'broker': 'pm path com.virtualcamerapro.broker', 'routingLease': 'getprop sys.vcam.route',
            'routingHook': 'dumpsys media.camera --virtual-camera-pro', 'cameraService': 'dumpsys media.camera',
        }
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            report['device'] = dict(zip(queries, pool.map(lambda command: run([adb, '-s', args.serial, 'shell', command]), queries.values())))
        report['serial'] = args.serial
        report['rootNote'] = 'Only existing shell UID, debuggable flag, and su binary presence were inspected. No root or unlock was attempted; blank bootloader properties mean unknown.'
        dump = report['device']['cameraService'].get('output', '')
        report['cameraProviderLines'] = [line.strip() for line in dump.splitlines() if any(term in line.lower() for term in ('provider', 'aidl', 'hidl', 'device@'))]
        report['cameraApiNote'] = 'Provider transport comes from the device dump; API level alone does not establish an OEM HAL implementation.'
    else:
        report['deviceInspection'] = 'Inventory only. Pass --serial to inspect one explicit target; no device properties were inferred.'
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
    print(args.out.resolve())

if __name__ == '__main__':
    main()
