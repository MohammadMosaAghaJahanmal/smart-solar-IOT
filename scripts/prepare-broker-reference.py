#!/usr/bin/env python3
"""Install a verified official API 36 AOSP image and create an isolated test AVD.

This prepares broker connectivity tests; it does not install CameraService patches.
"""
import argparse
import base64
import hashlib
import os
import pathlib
import re
import shutil
import subprocess
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
PACKAGE = 'system-images;android-36;default;x86_64'
IMAGE_SHA1 = '829c076e8ff448a336097ae25a355b495ba36e2c'
TAG = 'android-16.0.0_r1'

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=pathlib.Path, default=os.environ.get('ANDROID_HOME'))
    parser.add_argument('--avd', default='VirtualCameraProBroker36')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', args.avd): parser.error('Use a simple AVD name containing letters, digits, underscores or hyphens')
    if not args.sdk or not args.sdk.is_dir(): parser.error('Set ANDROID_HOME or --sdk')
    sdk = args.sdk.resolve()
    archive = ROOT/'artifacts/x86_64-36_r02.zip'
    if not archive.exists():
        raise SystemExit('Run python scripts/download-reference-image.py first')
    checksum = hashlib.sha1()
    with archive.open('rb') as source:
        for data in iter(lambda: source.read(1024*1024), b''): checksum.update(data)
    if checksum.hexdigest() != IMAGE_SHA1: raise SystemExit('Official archive checksum mismatch')
    target = sdk/'system-images/android-36/default/x86_64'
    marker = target/'.vcamera-reference-sha1'
    if target.exists() and (not marker.exists() or marker.read_text().strip() != IMAGE_SHA1):
        raise SystemExit(f'Refusing to overwrite an independently installed SDK image: {target}')
    target.mkdir(parents=True, exist_ok=True)
    # The marker allows a safe retry after an interrupted extraction.
    marker.write_text(IMAGE_SHA1+'\n')
    if not all((target/name).is_file() for name in ('system.img', 'vendor.img', 'package.xml')):
        with zipfile.ZipFile(archive) as image:
            members = [item for item in image.infolist() if not item.is_dir()]
            if shutil.disk_usage(target).free < sum(item.file_size for item in members) + 1024**3:
                raise SystemExit('Insufficient space for image extraction plus 1 GiB reserve')
            for item in members:
                parts = pathlib.PurePosixPath(item.filename).parts
                if not parts or parts[0] != 'x86_64' or '..' in parts:
                    raise SystemExit(f'Unexpected image archive path: {item.filename}')
                output = target.joinpath(*parts[1:])
                output.parent.mkdir(parents=True, exist_ok=True)
                with image.open(item) as source, output.open('wb') as destination: shutil.copyfileobj(source, destination)
    else:
        print('Reusing the already prepared SDK image; its disk files are left untouched')
    metadata = urllib.request.urlopen('https://dl.google.com/android/repository/sys-img/android/sys-img2-3.xml', timeout=30).read()
    repository = ET.fromstring(metadata)
    package = next(p for p in repository.iter('remotePackage') if p.get('path') == PACKAGE)
    if package.findtext('./revision/major') != '2': raise SystemExit('Pinned SDK revision changed; review metadata before installing')
    if package.findtext('./archives/archive/complete/checksum') != IMAGE_SHA1: raise SystemExit('Repository checksum changed')
    package.tag = 'localPackage'; package.set('obsolete', 'false')
    for name in ('archives', 'channelRef'):
        for element in package.findall(name): package.remove(element)
    common = 'http://schemas.android.com/repository/android/common/02'
    ET.register_namespace('common', common)
    ET.register_namespace('xsi', 'http://www.w3.org/2001/XMLSchema-instance')
    local = ET.Element('{'+common+'}repository', {'xmlns:sys-img': 'http://schemas.android.com/sdk/android/repo/sys-img2/03'})
    license_ref = package.find('uses-license').get('ref')
    local.append(next(lic for lic in repository.iter('license') if lic.get('id') == license_ref)); local.append(package)
    ET.ElementTree(local).write(target/'package.xml', encoding='utf-8', xml_declaration=True)
    keys = ROOT/'tools/test-platform-key'; keys.mkdir(parents=True, exist_ok=True)
    for name in ('platform.pk8', 'platform.x509.pem'):
        if keys.joinpath(name).is_file(): continue
        url = f'https://android.googlesource.com/platform/build/+/{TAG}/target/product/security/{name}?format=TEXT'
        keys.joinpath(name).write_bytes(base64.b64decode(urllib.request.urlopen(url, timeout=30).read()))
    manager = sdk/'cmdline-tools/latest/bin'/('avdmanager.bat' if os.name == 'nt' else 'avdmanager')
    avd_home = pathlib.Path(os.environ.get('ANDROID_AVD_HOME', pathlib.Path.home()/'.android/avd'))
    existing = avd_home/(args.avd+'.ini')
    if existing.exists():
        print(f'Preserving the existing AVD {args.avd}; no user data was changed')
        return
    # No --force: preserve any pre-existing AVD and its user data.
    result = subprocess.run([str(manager), 'create', 'avd', '--name', args.avd, '--package', PACKAGE, '--device', 'pixel_3a'], input='no\n', text=True)
    if result.returncode: raise SystemExit(result.returncode)
    print(f'Created {args.avd}; start with -writable-system -no-snapshot -gpu host')
    print('Public AOSP development keys are for this isolated test emulator only.')

if __name__ == '__main__': main()
