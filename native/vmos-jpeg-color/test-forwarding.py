"""Linux CPU checks of the production JpegStub ABI against a pinned fake encoder.

The fake encoder returns a metadata header plus its received NV21 bytes; this
tests argument forwarding and buffer safety, not Android JPEG compatibility.
"""
import hashlib
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "native/vmos-jpeg-color"
ENCODER = r'''
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <vector>
struct JpegStub { void* compressor; };
struct Header { int width,height,quality; uintptr_t exif; };
struct State { std::vector<unsigned char> bytes; bool oversize=false; };
extern "C" void JpegStub_init(JpegStub* stub) { stub->compressor=new State; }
extern "C" void JpegStub_cleanup(JpegStub* stub) { delete static_cast<State*>(stub->compressor);stub->compressor=nullptr; }
extern "C" int JpegStub_compress(JpegStub* stub,const void* pixels,int w,int h,int quality,void* exif) {
    auto* state=static_cast<State*>(stub->compressor);
    if (quality==13) return EIO;
    state->oversize=quality==14;
    Header header{};header.width=w;header.height=h;header.quality=quality;header.exif=reinterpret_cast<uintptr_t>(exif);
    const size_t count=size_t(w)*size_t(h)*3/2;
    state->bytes.resize(sizeof(header)+count);
    std::memcpy(state->bytes.data(),&header,sizeof(header));
    std::memcpy(state->bytes.data()+sizeof(header),pixels,count);
    return 0;
}
extern "C" size_t JpegStub_getCompressedSize(JpegStub* stub) {
    auto* state=static_cast<State*>(stub->compressor);
    return state->oversize?33554433:state->bytes.size();
}
extern "C" void JpegStub_getCompressedImage(JpegStub* stub,void* output) {
    auto* state=static_cast<State*>(stub->compressor);
    std::memcpy(output,state->bytes.data(),state->bytes.size());
}
'''
HARNESS = r'''
#include "jpeg-color-shim.cpp"
#include <array>
#include <cassert>
#include <iostream>
#include <thread>
#include <vector>
struct Header { int width,height,quality; uintptr_t exif; };
void successfulCall(JpegStub& stub,int quality) {
    const std::array<uint8_t,6> source{{82,82,82,82,240,90}};
    const auto before=source;
    auto* exif=reinterpret_cast<_ExifData*>(uintptr_t(0x12345678));
    assert(JpegStub_compress(&stub,source.data(),2,2,quality,exif)==0);
    assert(source==before);
    const size_t size=JpegStub_getCompressedSize(&stub);
    assert(size==sizeof(Header)+6);
    std::vector<uint8_t> output(size+4,0xa5);
    JpegStub_getCompressedImage(&stub,output.data()+2);
    assert(output[0]==0xa5 && output[1]==0xa5 && output[size+2]==0xa5 && output[size+3]==0xa5);
    Header header{};std::memcpy(&header,output.data()+2,sizeof(header));
    assert(header.width==2 && header.height==2 && header.quality==quality && header.exif==uintptr_t(exif));
    const std::array<uint8_t,6> expected{{77,77,77,77,255,85}};
    assert(!std::memcmp(output.data()+2+sizeof(Header),expected.data(),expected.size()));
}
int main(int argc,char**) {
    JpegStub stub{};JpegStub_init(&stub);
    if (argc>1) {
        assert(!stub.compressor);
        assert(JpegStub_compress(&stub,nullptr,2,2,95,nullptr)==ENODEV);
        assert(JpegStub_getCompressedSize(&stub)==0);
        JpegStub_cleanup(&stub);
        std::cout<<"runtime pin refusal: PASS\n";return 0;
    }
    assert(stub.compressor && JpegStub_getCompressedSize(&stub)==0);
    successfulCall(stub,87);
    std::array<uint8_t,6> pixels{{82,82,82,82,240,90}};
    assert(JpegStub_compress(&stub,pixels.data(),3,2,95,nullptr)==EINVAL);
    assert(JpegStub_getCompressedSize(&stub)==0);
    std::array<uint8_t,64> unchanged;unchanged.fill(0xa5);
    JpegStub_getCompressedImage(&stub,unchanged.data());
    for (uint8_t value:unchanged) assert(value==0xa5);
    assert(JpegStub_compress(&stub,nullptr,2,2,95,nullptr)==EINVAL);
    assert(JpegStub_compress(&stub,pixels.data(),2,2,101,nullptr)==EINVAL);
    vcamjpeg::failAllocationForTesting=true;
    assert(JpegStub_compress(&stub,pixels.data(),2,2,95,nullptr)==ENOMEM);
    vcamjpeg::failAllocationForTesting=false;
    assert(JpegStub_getCompressedSize(&stub)==0);
    successfulCall(stub,90);
    assert(JpegStub_compress(&stub,pixels.data(),2,2,13,nullptr)==EIO);
    assert(JpegStub_getCompressedSize(&stub)==0);
    assert(JpegStub_compress(&stub,pixels.data(),2,2,14,nullptr)==EOVERFLOW);
    assert(JpegStub_getCompressedSize(&stub)==0);
    successfulCall(stub,91);
    JpegStub_cleanup(&stub);assert(!stub.compressor);JpegStub_cleanup(&stub);
    std::vector<std::thread> threads;
    for (int i=0;i<8;++i) threads.emplace_back([] {
        JpegStub local{};JpegStub_init(&local);assert(local.compressor);
        for (int call=0;call<20;++call) successfulCall(local,96);
        JpegStub_cleanup(&local);
    });
    for (auto& thread:threads) thread.join();
    std::cout<<"exact ABI/EXIF/quality forwarding, bounded output, allocation/encoder failure, stale output refusal, 8 concurrent stubs: PASS\n";
}
'''


def main():
    compiler = shutil.which("g++")
    if compiler is None:
        raise SystemExit("Run this CPU test under Linux/WSL with g++")
    with tempfile.TemporaryDirectory(prefix="vcam-jpeg-forward-") as directory:
        directory = Path(directory)
        fixture = directory / "encoder.cpp"
        fixture.write_text(ENCODER)
        library = directory / "libfixture_encoder.so"
        common = [compiler, "-std=c++17", "-O2", "-Wall", "-Wextra", "-Werror", "-pthread"]
        subprocess.run(common + ["-shared", "-fPIC", str(fixture), "-o", str(library)], check=True)
        digest = hashlib.sha256(library.read_bytes()).hexdigest()
        harness = directory / "forwarding.cpp"
        harness.write_text(HARNESS)
        for name, expected in (("valid", digest), ("foreign", "0" * 64)):
            executable = directory / name
            subprocess.run(common + ["-I", str(SOURCE), "-DVCAM_JPEG_TESTING",
                                     f'-DVCAM_JPEG_TEST_ENCODER="{library}"', f'-DVCAM_JPEG_TEST_ENCODER_SHA="{expected}"',
                                     str(harness), "-ldl", "-o", str(executable)], check=True)
            subprocess.run([str(executable)] + ([] if name == "valid" else ["refused"]), check=True)


if __name__ == "__main__":
    main()
