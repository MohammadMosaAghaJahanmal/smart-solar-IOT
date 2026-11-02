#!/usr/bin/env python3
"""Fetch the pinned official AOSP 16 emulator image, with resumable bounded ranges."""
import concurrent.futures
import argparse
import hashlib
import pathlib
import shutil
import time
import urllib.request

URL = 'https://dl.google.com/android/repository/sys-img/android/x86_64-36_r02.zip'
CDN_URL = 'https://redirector.gvt1.com/edgedl/android/repository/sys-img/android/x86_64-36_r02.zip'
SIZE = 844217077
SHA1 = '829c076e8ff448a336097ae25a355b495ba36e2c'
ROOT = pathlib.Path(__file__).resolve().parents[1]
OUTPUT = ROOT/'artifacts/x86_64-36_r02.zip'
CHUNK = 16 * 1024 * 1024

def digest(path, algorithm='sha1'):
    value = hashlib.new(algorithm)
    with path.open('rb') as source:
        for data in iter(lambda: source.read(1024*1024), b''): value.update(data)
    return value.hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--connections', type=int, default=8, choices=range(1, 33))
    parser.add_argument('--cdn', action='store_true', help="Use Google's download CDN redirector; the same official checksum is required")
    parser.add_argument('--emulator-35', action='store_true', help='Fetch the official archived emulator 35.6.11 for the isolated Offworld experiment')
    args = parser.parse_args()
    url, size, checksum, algorithm, output = URL, SIZE, SHA1, 'sha1', OUTPUT
    if args.cdn: url = CDN_URL
    if args.emulator_35:
        url = 'https://dl.google.com/android/repository/emulator-windows_x64-13610412.zip'
        if args.cdn: url = url.replace('https://dl.google.com/', 'https://redirector.gvt1.com/edgedl/')
        size, checksum, algorithm = 440033908, '0865071aecf3d90a5b967cb6cb0cd48bea0a58a26ef4953b32037671c129b7e9', 'sha256'
        output = ROOT/'artifacts/emulator-windows_x64-13610412.zip'
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists() and output.stat().st_size == size and digest(output, algorithm) == checksum:
        print(f'Official archive already verified: {output}'); return
    if shutil.disk_usage(output.parent).free < size * 2:
        raise SystemExit('Insufficient space for download and assembly')
    parts = output.parent/('emulator-35-parts' if args.emulator_35 else 'reference-image-parts')
    parts.mkdir(exist_ok=True)
    def fetch(index):
        start = index*CHUNK; end = min(size, start+CHUNK)-1
        path = parts/f'{index:04d}.part'
        count = end-start+1
        for attempt in range(4):
            existing = path.stat().st_size if path.exists() else 0
            if existing == count: return count
            if existing > count: raise RuntimeError(f'Oversized part: {path}')
            request = urllib.request.Request(url, headers={'Range': f'bytes={start+existing}-{end}'})
            try:
                with urllib.request.urlopen(request, timeout=60) as response:
                    expected = f'bytes {start+existing}-{end}/{size}'
                    if response.status != 206 or response.headers.get('Content-Range') != expected:
                        raise RuntimeError('Server did not honor the exact byte range')
                    with path.open('ab') as target:
                        for data in iter(lambda: response.read(256*1024), b''): target.write(data)
                if path.stat().st_size == count: return count
            except OSError:
                if attempt == 3: raise
                time.sleep(attempt+1)
        raise RuntimeError(f'Incomplete part: {path}')
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.connections) as pool:
        for completed in concurrent.futures.as_completed([pool.submit(fetch, i) for i in range((size+CHUNK-1)//CHUNK)]):
            completed.result()
            downloaded = sum(path.stat().st_size for path in parts.glob('*.part'))
            print(f'{downloaded//1048576}/{size//1048576} MiB downloaded', flush=True)
    with output.open('wb') as destination:
        for index in range((size+CHUNK-1)//CHUNK):
            with (parts/f'{index:04d}.part').open('rb') as source: shutil.copyfileobj(source, destination)
    if output.stat().st_size != size or digest(output, algorithm) != checksum:
        raise SystemExit('Official checksum mismatch; archive must not be used')
    print(f'Official archive checksum verified: {output}', flush=True)

if __name__ == '__main__': main()
