// Diagnostic only: run the exact stock Camera2 encoder on saved, normal YUV
// planes. Does not acquire a camera, invoke root, or change a guest library.
#include "sha256.h"
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace {
constexpr const char* kLibrary="/system/lib64/libjni_jpegutil.so";
constexpr const char* kHash="6050a81c4eae572989b284bc55531a853efe83512b5413b7bffe00fb308c2bd5";
constexpr const char* kSymbol="_ZN8jpegutil8CompressEiiPhiiS0_iiS0_iiS0_miiiiii";
using Compress=int(*)(int,int,unsigned char*,int,int,unsigned char*,int,int,unsigned char*,int,int,unsigned char*,size_t,int,int,int,int,int,int);
void require(bool condition,const char* message) { if (!condition) throw std::runtime_error(message); }
std::vector<unsigned char> read(const std::string& path,size_t expected) {
    struct stat named{},opened{};
    require(!lstat(path.c_str(),&named) && S_ISREG(named.st_mode) && named.st_size>0 && size_t(named.st_size)==expected,"Unexpected input file size/type");
    const int fd=open(path.c_str(),O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
    require(fd>=0,"Cannot open diagnostic input");
    if (fstat(fd,&opened) || named.st_dev!=opened.st_dev || named.st_ino!=opened.st_ino || named.st_size!=opened.st_size) { close(fd);throw std::runtime_error("Input changed during open"); }
    std::vector<unsigned char> bytes(expected);
    size_t at=0;
    while (at<expected) {
        const ssize_t count=::read(fd,bytes.data()+at,expected-at);
        if (count<0 && errno==EINTR) continue;
        if (count<=0) { close(fd);throw std::runtime_error("Cannot read diagnostic input"); }
        at+=size_t(count);
    }
    close(fd);return bytes;
}
void writeNew(const std::string& path,const unsigned char* bytes,size_t size) {
    const int fd=open(path.c_str(),O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
    require(fd>=0,"Diagnostic output already exists or is unavailable");
    size_t at=0;
    while (at<size) {
        const ssize_t count=::write(fd,bytes+at,size-at);
        if (count<0 && errno==EINTR) continue;
        if (count<=0) { close(fd);throw std::runtime_error("Diagnostic output write failed"); }
        at+=size_t(count);
    }
    close(fd);
}
}
int main(int argc,char** argv) {
    try {
        require(argc==2,"usage: vcam-jpegutil-probe /absolute/directory/containing/1920x1088/plane-dumps");
        const std::string directory=argv[1];
        require(!directory.empty() && directory[0]=='/' && directory.find("..") == std::string::npos,"Expected an absolute diagnostic directory");
        auto library=read(kLibrary,465864);
        vcamjpeg::Sha256 hash;hash.update(library.data(),library.size());
        require(hash.finish()==kHash,"Stock encoder differs from the inspected exact build");
        require(library.size()>=20 && !std::memcmp(library.data(),"\177ELF\002\001",6) && library[18]==183 && library[19]==0,"Expected exact ARM64 ELF");
        void* handle=dlopen(kLibrary,RTLD_NOW|RTLD_LOCAL);
        if (!handle) throw std::runtime_error(dlerror());
        const auto compress=reinterpret_cast<Compress>(dlsym(handle,kSymbol));
        require(compress!=nullptr,"Exact stock encoder export is unavailable");
        constexpr int width=1920,height=1088;
        auto y=read(directory+"/plane-0-zero-to-limit.bin",2088960);
        auto u=read(directory+"/plane-1-zero-to-limit.bin",1044479);
        auto v=read(directory+"/plane-2-zero-to-limit.bin",1044479);
        const auto originalY=y,originalU=u,originalV=v;
        std::vector<unsigned char> output(size_t(width)*height*4+128);
        for (int rotation=0;rotation<4;++rotation) {
            std::fill(output.begin(),output.end(),0xa5);
            const size_t capacity=output.size()-128;
            const int count=compress(width,height,y.data(),1,1920,u.data(),2,1920,v.data(),2,1920,
                output.data()+64,capacity,95,0,0,width,height,rotation);
            require(count>0 && size_t(count)<=capacity,"Stock encoder returned an invalid size");
            require(y==originalY && u==originalU && v==originalV,"Stock encoder mutated input planes");
            for (size_t guard=0;guard<64;++guard) require(output[guard]==0xa5 && output[output.size()-64+guard]==0xa5,"Stock encoder crossed the supplied output capacity");
            const std::string path=directory+"/stock-native-rot"+std::to_string(rotation)+".jpg";
            writeNew(path,output.data()+64,size_t(count));
            std::printf("rotation=%d quarterTurns bytes=%d output=%s\n",rotation,count,path.c_str());
        }
        dlclose(handle);
        return 0;
    } catch (const std::exception& error) { std::fprintf(stderr,"JPEGUTIL_PROBE_REFUSED: %s\n",error.what());return 1; }
}
