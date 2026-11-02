"""Emulate actual original/repaired ARM64 post-scale copy instructions offline.

The independent fixture supplies the completed scaled NV21 image. This verifies
the changed binary's bounds/copy branches and pixel preservation; it does not
pretend to test libyuv, Android gralloc, or an actual device.
"""
from pathlib import Path
import hashlib
import json
import struct
import sys

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'artifacts/vmos/research/nv21-audit-deps'))
from unicorn import Uc,UC_ARCH_ARM64,UC_MODE_ARM,UC_HOOK_CODE,UC_HOOK_MEM_WRITE
from unicorn.arm64_const import *

REGS={i:globals()[f'UC_ARM64_REG_X{i}'] for i in range(29)}
REGS[29]=UC_ARM64_REG_FP;REGS[30]=UC_ARM64_REG_LR
def align(size):return (size+4095)&~4095

def emulate(binary:bytes,source:tuple[int,int],target:tuple[int,int],planar=False,stride_pad=0):
    cpu=Uc(UC_ARCH_ARM64,UC_MODE_ARM)
    # Exact executable PT_LOAD from the measured ELF.
    cpu.mem_map(0x6000,0x9f000);cpu.mem_write(0x6000,binary[:0x9e89c])
    stack=0x1000000;cpu.mem_map(stack,0x10000);sp=stack+0x4000
    cpu.reg_write(UC_ARM64_REG_SP,sp);cpu.reg_write(UC_ARM64_REG_FP,sp+0x2e0)
    w,h=target;pixels=w*h;uv=pixels//2;stride=w+stride_pad
    # Independent varying Y/V/U palette, never all-neutral or a solid red-only test.
    y=bytes(40+(i*7)%160 for i in range(pixels))
    vu=bytes(v for i in range(pixels//4) for v in (90+(i*11)%140,45+(i*13)%170))
    scaled=y+vu
    temp=0x2000000;dest_y=0x3000000;dest_uv=0x4000000;stamp=0x5000000
    y_size=stride*h;uv_stride=stride if not planar else stride//2
    plane_size=uv_stride*(h//2)
    uv_size=plane_size if not planar else plane_size*2
    for base,size in [(temp,len(scaled)+64),(dest_y,y_size+64),(dest_uv,uv_size+64),(stamp,4096)]:
        cpu.mem_map(base,align(size));cpu.mem_write(base,b'\xa5'*size)
    cpu.mem_write(temp,scaled);cpu.mem_write(dest_y,b'\0'*y_size);cpu.mem_write(dest_uv,b'\0'*uv_size)
    cpu.mem_write(stamp,struct.pack('<Q',0x12345678))
    cr=dest_uv;cb=cr+1 if not planar else cr+plane_size
    # Register/stack state immediately after the unchanged scaler helper returns.
    values={19:stride,20:cr,21:0,22:cb,23:w,24:uv,25:pixels,26:1,27:dest_y,28:temp}
    for index,value in values.items():cpu.reg_write(REGS[index],value)
    def put(at,value):cpu.mem_write(sp+at,struct.pack('<Q',value))
    put(0x08,dest_y);put(0x18,stamp);put(0x30,uv);put(0x38,pixels)
    put(0x78,source[0]*source[1]*3//2)
    reached=[];copies=[];writes=[]
    def hook(uc,address,size,user):
        reached.append(address)
        if address==0x4b580:
            uc.emu_stop();return
        if address==0x36bb8: # memcpy external call; validate exact mapped ranges.
            dst=uc.reg_read(REGS[0]);src=uc.reg_read(REGS[1]);n=uc.reg_read(REGS[2])
            assert temp<=src and src+n<=temp+len(scaled),(hex(address),hex(src),n)
            assert ((dest_y<=dst and dst+n<=dest_y+y_size) or (dest_uv<=dst and dst+n<=dest_uv+uv_size)),(hex(dst),n)
            uc.mem_write(dst,bytes(uc.mem_read(src,n)));copies.append([dst,src,n])
            uc.reg_write(REGS[0],dst);uc.reg_write(UC_ARM64_REG_PC,uc.reg_read(UC_ARM64_REG_LR));return
        if address in (0x36b98,0x36ac8): # free/log; no source code shortcut.
            uc.reg_write(REGS[0],0);uc.reg_write(UC_ARM64_REG_PC,uc.reg_read(UC_ARM64_REG_LR))
    def write_hook(uc,access,address,size,value,user):
        writes.append([address,size])
        assert ((dest_y<=address and address+size<=dest_y+y_size) or
                (dest_uv<=address and address+size<=dest_uv+uv_size) or
                (stack<=address and address+size<=stack+0x10000) or
                (stamp<=address and address+size<=stamp+8)),(hex(address),size)
    cpu.hook_add(UC_HOOK_CODE,hook)
    cpu.hook_add(UC_HOOK_MEM_WRITE,write_hook)
    cpu.emu_start(0x4b2dc,0x4b580,timeout=10000000,count=5000000)
    result=cpu.reg_read(REGS[21])&0xffffffff
    actual_y=bytes(cpu.mem_read(dest_y,y_size));actual_uv=bytes(cpu.mem_read(dest_uv,uv_size))
    assert bytes(cpu.mem_read(temp,len(scaled)))==scaled
    assert bytes(cpu.mem_read(dest_y+y_size,64))==b'\xa5'*64
    assert bytes(cpu.mem_read(dest_uv+uv_size,64))==b'\xa5'*64
    # Independently packed expected rows honor destination padding.
    expected_y=bytearray(y_size);expected_uv=bytearray(uv_size)
    for row in range(h):expected_y[row*stride:row*stride+w]=y[row*w:(row+1)*w]
    for row in range(h//2):
        raw=vu[row*w:(row+1)*w]
        if planar:
            expected_uv[row*uv_stride:row*uv_stride+w//2]=raw[0::2]
            expected_uv[plane_size+row*uv_stride:plane_size+row*uv_stride+w//2]=raw[1::2]
        else:expected_uv[row*stride:row*stride+w]=raw
    return {'return':result,'Y_matches':actual_y==expected_y,'UV_matches':actual_uv==expected_uv,
            'zero_uv':not any(actual_uv),'copied_bytes':sum(x[2] for x in copies),'machine_instructions':len(reached),
            'bounds_error':0x4b3a8 in reached,'timestamp':struct.unpack('<Q',cpu.mem_read(stamp,8))[0]}

def main():
    directory=ROOT/'artifacts/vmos/nv21-scale-repair'
    original=(directory/'camera.vphw90.transport.source.so').read_bytes()
    candidate=(directory/'camera.vphw90.transport.nv21-scale.so').read_bytes()
    results=[]
    for source,target in [((1920,1080),(1920,1088)),((8,8),(8,8)),((16,16),(8,8)),((8,8),(16,16)),((16,8),(8,16))]:
        for planar,padding in [(False,0),(False,16),(True,0),(True,16)]:
            old=emulate(original,source,target,planar,padding);fixed=emulate(candidate,source,target,planar,padding)
            assert fixed['return']==0 and fixed['Y_matches'] and fixed['UV_matches'],(source,target,planar,padding,fixed)
            assert fixed['timestamp']==0
            if source==(1920,1080):
                assert old['return']==22 and old['Y_matches'] and old['zero_uv'] and old['bounds_error'],old
            results.append({'source':source,'target':target,'planar':planar,'stridePadding':padding,'original':old,'candidate':fixed})
    evidence={'status':'PASS','method':'Actual ARM64 post-scale copy code under Unicorn; only memcpy/free/log external calls stubbed. Independently supplied varying scaled NV21 palette. Not a libyuv/gralloc/device test.',
              'candidateSha256':hashlib.sha256(candidate).hexdigest(),'cases':results}
    (directory/'arm64-copy-emulation.json').write_text(json.dumps(evidence,indent=2)+'\n')
    print(f'PASS {len(results)} actual ARM64 copy paths: original 1920x1080->1920x1088 Y-only/EINVAL reproduced; repaired NV21/planar padded/unpadded identity/up/down/mixed color planes exact, canaries intact')

if __name__=='__main__':main()
