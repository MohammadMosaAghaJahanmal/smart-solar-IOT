#!/usr/bin/env python3
"""Prepare three exact-build scaled-NV21 output-bound repairs offline only."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import struct

ORIGINAL_SHA='362261e9d1924bde73652cd8f2c467fbaabc59f50ad66e0b4de0215291ec6d3c'
TRANSPORT_SHA='1c80cb9711b08d28625e140263b6bd85aadaf2c6a1de50d161e19fc6e1773a97'
SOURCES={ORIGINAL_SHA:'original',TRANSPORT_SHA:'transport'}
SIZE=2109016
EDITS=(
    (0x4B2E0,bytes.fromhex('e43f40f9'),bytes.fromhex('0403198b'),'ldr x4,[sp,#0x78]','add x4,x24,x25'),
    (0x4B338,bytes.fromhex('e83f40f9'),bytes.fromhex('a8011b8b'),'ldr x8,[sp,#0x78]','add x8,x13,x27'),
    (0x4B398,bytes.fromhex('e83f40f9'),bytes.fromhex('a8011b8b'),'ldr x8,[sp,#0x78]','add x8,x13,x27'),
)

def digest(data): return hashlib.sha256(data).hexdigest()

def offset(data:bytes,address:int)->int:
    if data[:6]!=b'\x7fELF\x02\x01' or struct.unpack_from('<HH',data,16)!=(3,183):
        raise ValueError('Expected ELF64 little-endian AArch64 shared library')
    start=struct.unpack_from('<Q',data,32)[0]
    width,count=struct.unpack_from('<HH',data,54)
    for index in range(count):
        kind,flags,at,va,_,size,_,_=struct.unpack_from('<IIQQQQQQ',data,start+index*width)
        if kind==1 and flags&1 and va<=address and address+4<=va+size:
            return at+address-va
    raise ValueError('Instruction is not in an executable ELF load segment')

def repair(source:bytes)->bytes:
    if len(source)!=SIZE or digest(source) not in SOURCES:
        raise ValueError('Unsupported exact source HAL SHA-256 or size')
    # Both sources differ only in the separately audited socket literal. The
    # scaled-temp allocation, scaler call, original copy/bounds branches and
    # cleanup must all remain exactly as inspected.
    guards={
        0x4B2B4:'0003198b', # malloc argument targetY+targetUV
        0x4B2BC:'6fb0ff97', # malloc
        0x4B2D8:'cefdff97', # complete NV21 scaler helper
        0x4B2DC:'fa010034', # Y/capacity null branch (retained)
        0x4B2E4:'9f0019eb', # scaled Y-capacity comparison (retained)
        0x4B2E8:'a2070054', # bounds failure/success branch (retained)
        0x4B33C:'04010dcb', # subtract actual scaled Y offset
        0x4B340:'9f001beb', # compare available against target UV length
        0x4B344:'23030054', # retain NV21 chroma bounds failure
        0x4B39C:'04010dcb', # same check for planar destination
        0x4B3A0:'9f001beb',
        0x4B3A4:'62050054',
        0x4B3FC:'8103088b', # copies use scaled x28 buffer, not original reply
        0x4B434:'ed1f40f9', # x13 restored target Y size after copy
        0x4B43C:'81030d8b', # UV source scaled x28+targetY
        0x4B574:'5f0300f9', # original timestamp behavior retained
    }
    for address,expected in guards.items():
        at=offset(source,address)
        if source[at:at+4]!=bytes.fromhex(expected):
            raise ValueError(f'Pinned scaling/copy guard differs at {address:#x}')
    result=bytearray(source)
    for address,before,after,_,_ in EDITS:
        at=offset(source,address)
        if source[at:at+4]!=before:raise ValueError('Pinned old length-load instruction differs')
        result[at:at+4]=after
    return bytes(result)

def prepare(source:Path,destination:Path)->dict:
    data=source.read_bytes();fixed=repair(data);kind=SOURCES[digest(data)]
    original_name=f'camera.vphw90.{kind}.source.so'
    candidate_name=f'camera.vphw90.{kind}.nv21-scale.so'
    manifest={
        'profile':'vmos-android9-arm64-rom10075-nv21-scale-bounds-v1',
        'status':'prepared_offline_device_acceptance_required','guestPath':'/vendor/lib64/hw/camera.vphw90.so',
        'sourceKind':kind,'sourceSha256':digest(data),'candidateSha256':digest(fixed),'size':len(data),
        'sourceFile':original_name,'candidateFile':candidate_name,
        'edits':[{'virtualAddress':a,'fileOffset':offset(data,a),'beforeHex':b.hex(),'afterHex':c.hex(),'before':d,'after':e} for a,b,c,d,e in EDITS],
        'reason':'After NV21 scaling, output copies must be bounded by the allocated scaled target buffer, rather than the original lower-resolution camera reply.',
        'scope':'Exactly three length-load instructions in CameraQemuClient::queryFrameNV21Wapper. No condition, call, format, socket literal, source geometry, gralloc policy or timestamp operation is removed.',
        'allocationProof':{'at':'0x4b2b4','bytes':'targetY+targetUV','returnRegister':'x28','scalerCall':'0x4b2d8','copySource':'x28','YcopyOffset':'x13','UVrequestedSize':'x27'},
        'failureExample':{'source':[1920,1080],'target':[1920,1088],'originalReplyBytes':3110400,'targetY':2088960,'targetUV':1044480,'wrongAvailableUV':1021440,'correctAvailableUV':1044480},
        'limits':['The original NV21 scaler has no malloc-return or libyuv-return checks; these existing behaviors are unchanged.','Original reply framing/input handling is unchanged; this repair does not add input-buffer validation that the existing scaler lacks.','The exact camera caller supplies positive, even dimensions and all Y/U/V buffers. No new formats, strides, properties or devices are supported.','Prepared files are not device acceptance evidence.'],
    }
    files={original_name:data,candidate_name:fixed,f'{kind}-repair.json':(json.dumps(manifest,indent=2)+'\n').encode()}
    for name,content in files.items():
        p=destination/name
        if p.exists() and p.read_bytes()!=content:raise ValueError(f'Refusing to overwrite different existing evidence: {p}')
    destination.mkdir(parents=True,exist_ok=True)
    for name,content in files.items():
        p=destination/name
        if not p.exists():
            with p.open('xb') as f:f.write(content)
    return manifest

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source',required=True,type=Path)
    parser.add_argument('--out-dir',type=Path,default=Path('artifacts/vmos/nv21-scale-repair'))
    args=parser.parse_args()
    print(json.dumps(prepare(args.source,args.out_dir),indent=2))
