#!/usr/bin/env python3
"""Generate deterministic camera acceptance fixtures (ffmpeg needed for MP4)."""
import argparse
import binascii
import pathlib
import struct
import subprocess
import zlib

def png(path, rgb, width=1280, height=720):
    def chunk(kind,data):
        return struct.pack('>I',len(data))+kind+data+struct.pack('>I',binascii.crc32(kind+data)&0xffffffff)
    raw = (b'\0'+bytes(rgb)*width)*height
    path.write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',width,height,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(raw))+chunk(b'IEND',b''))

def main():
    p=argparse.ArgumentParser();p.add_argument('--output',type=pathlib.Path,default=pathlib.Path('artifacts/fixtures'));p.add_argument('--ffmpeg',default='ffmpeg');p.add_argument('--images-only',action='store_true');args=p.parse_args()
    args.output.mkdir(parents=True,exist_ok=True)
    png(args.output/'red.png',(255,0,0));png(args.output/'blue.png',(0,0,255));png(args.output/'portrait.png',(0,255,0),720,1280)
    if not args.images_only:
        subprocess.run([args.ffmpeg,'-y','-f','lavfi','-i','color=c=red:s=1280x720:r=30:d=1','-f','lavfi','-i','color=c=blue:s=1280x720:r=30:d=1','-filter_complex','[0:v][1:v]concat=n=2:v=1:a=0','-c:v','libx264','-pix_fmt','yuv420p','-movflags','+faststart',str(args.output/'red-blue-2s.mp4')],check=True)
    print(args.output.resolve())
if __name__=='__main__': main()
