// Exact-build JPEG-only range correction for VMOS Android 9 ARM64 ROM10075.
// The original encoder stays at /vendor/lib64/hw/camera.vphw90.jpeg.so.
#include "nv21-range.h"
#include "sha256.h"

#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <memory>
#include <mutex>
#include <new>
#include <sys/stat.h>
#include <unistd.h>
#ifdef __ANDROID__
#include <android/log.h>
#include <sys/system_properties.h>
#endif

// Matches the original one-pointer JpegStub and its five extern-C functions.
struct JpegStub { void* compressor; };
struct _ExifData;
static_assert(sizeof(JpegStub) == sizeof(void*), "JpegStub ABI changed");

namespace vcamjpeg {
// Called only during call_once loader initialization, never once per frame.
// No source pixels, paths from callers or media metadata enter these messages.
void loaderFailure(const char* stage,int error=0) {
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_ERROR,"VcamJpegColor","encoder_verified=0 stage=%s errno=%d",stage,error);
#else
    (void)stage;(void)error;
#endif
}
#ifdef VCAM_JPEG_TESTING
// Compile-time-only fixture selection. Production accepts no environment or
// caller-controlled encoder path, identity bypass, or allocator override.
constexpr const char* kEncoder = VCAM_JPEG_TEST_ENCODER;
constexpr const char* kEncoderHash = VCAM_JPEG_TEST_ENCODER_SHA;
constexpr size_t kEncoderBytes = 0;
bool failAllocationForTesting = false;
#else
constexpr const char* kEncoder = "/vendor/lib64/hw/camera.vphw90.jpeg.so";
constexpr const char* kEncoderHash = "0975abc8d3eec3f57bd607458101f69a11f0a7c93b2de97094cfab534175c2e3";
constexpr size_t kEncoderBytes = 68120;
#endif

bool guestIdentity() {
#ifdef VCAM_JPEG_TESTING
    return true;
#elif defined(__ANDROID__) && defined(__aarch64__)
    char sdk[PROP_VALUE_MAX]{}, hardware[PROP_VALUE_MAX]{}, abi[PROP_VALUE_MAX]{};
    constexpr const char* suffix = ":9/PQ3A.190801.002/eng.builds.20191015.144423:user/release-keys";
    if (__system_property_get("ro.build.version.sdk",sdk)<=0 || std::strcmp(sdk,"28") ||
        __system_property_get("ro.hardware",hardware)<=0 || std::strcmp(hardware,"vphw90") ||
        __system_property_get("ro.product.cpu.abilist",abi)<=0 || std::strcmp(abi,"arm64-v8a")) {
        loaderFailure("guest_sdk_hardware_abi");return false;
    }
    // This guest's fingerprint is 93 bytes. PROP_VALUE_MAX is 92; the legacy
    // __system_property_get returns a compatibility value for long read-only
    // properties on Android 9. Read the real immutable value via the API26
    // callback, retaining the exact audited suffix guard.
    struct Check { const char* suffix;bool matches=false; } check{suffix};
    const prop_info* fingerprint=__system_property_find("ro.build.fingerprint");
    if (!fingerprint) {loaderFailure("guest_fingerprint_missing");return false;}
    __system_property_read_callback(fingerprint,[](void* cookie,const char*,const char* value,uint32_t) {
        auto& state=*static_cast<Check*>(cookie);
        const size_t length=std::strlen(value),suffixLength=std::strlen(state.suffix);
        state.matches=length>=suffixLength && !std::strcmp(value+length-suffixLength,state.suffix);
    },&check);
    if (!check.matches) loaderFailure("guest_fingerprint_suffix");
    return check.matches;
#else
    return false;
#endif
}

bool verifiedEncoder() {
    struct stat named{}, opened{}, after{};
    if (lstat(kEncoder,&named)) {loaderFailure("encoder_lstat",errno);return false;}
    if (!S_ISREG(named.st_mode) || named.st_nlink!=1 || named.st_size<=0 || named.st_size>1024*1024) {
        loaderFailure("encoder_type_links_size");return false;
    }
#ifndef VCAM_JPEG_TESTING
    if (named.st_uid!=0 || uint64_t(named.st_size)!=kEncoderBytes) {loaderFailure("encoder_uid_size");return false;}
#endif
    const int fd=open(kEncoder,O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
    if (fd<0) {loaderFailure("encoder_open",errno);return false;}
    bool valid=!fstat(fd,&opened) && named.st_dev==opened.st_dev && named.st_ino==opened.st_ino &&
        named.st_mode==opened.st_mode && named.st_size==opened.st_size && named.st_nlink==opened.st_nlink;
    Sha256 digest;
    uint8_t buffer[4096];
    size_t total=0;
    while (valid) {
        const ssize_t count=read(fd,buffer,sizeof(buffer));
        if (count<0 && errno==EINTR) continue;
        if (count<0) { valid=false; break; }
        if (!count) break;
#ifndef VCAM_JPEG_TESTING
        if (!total && (count<20 || std::memcmp(buffer,"\177ELF\002\001",6) || buffer[16]!=3 || buffer[17]!=0 || buffer[18]!=183 || buffer[19]!=0)) valid=false;
#endif
        total+=size_t(count);
        if (total>size_t(named.st_size)) { valid=false; break; }
        digest.update(buffer,size_t(count));
    }
    valid=valid && total==size_t(named.st_size) && !lstat(kEncoder,&after) &&
        named.st_dev==after.st_dev && named.st_ino==after.st_ino && named.st_size==after.st_size && named.st_mode==after.st_mode &&
        named.st_nlink==after.st_nlink && named.st_uid==after.st_uid && digest.finish()==kEncoderHash;
    close(fd);
    if (!valid) loaderFailure("encoder_fd_identity_or_hash");
    return valid;
}

struct Encoder {
    void* handle=nullptr;
    void (*init)(JpegStub*)=nullptr;
    void (*cleanup)(JpegStub*)=nullptr;
    int (*compress)(JpegStub*,const void*,int,int,int,_ExifData*)=nullptr;
    void (*copy)(JpegStub*,void*)=nullptr;
    size_t (*size)(JpegStub*)=nullptr;
    bool ready=false;
};

Encoder& encoder() {
    static Encoder api;
    static std::once_flag once;
    std::call_once(once,[] {
        if (!guestIdentity() || !verifiedEncoder()) return;
        api.handle=dlopen(kEncoder,RTLD_NOW|RTLD_LOCAL);
        if (!api.handle) {
            loaderFailure("encoder_dlopen",errno);
#ifdef __ANDROID__
            const char* error=dlerror();
            __android_log_print(ANDROID_LOG_ERROR,"VcamJpegColor","encoder_dlopen_error=%.256s",error?error:"none");
#endif
            return;
        }
        api.init=reinterpret_cast<decltype(api.init)>(dlsym(api.handle,"JpegStub_init"));
        api.cleanup=reinterpret_cast<decltype(api.cleanup)>(dlsym(api.handle,"JpegStub_cleanup"));
        api.compress=reinterpret_cast<decltype(api.compress)>(dlsym(api.handle,"JpegStub_compress"));
        api.copy=reinterpret_cast<decltype(api.copy)>(dlsym(api.handle,"JpegStub_getCompressedImage"));
        api.size=reinterpret_cast<decltype(api.size)>(dlsym(api.handle,"JpegStub_getCompressedSize"));
        api.ready=api.init && api.cleanup && api.compress && api.copy && api.size && verifiedEncoder();
#ifdef __ANDROID__
        __android_log_print(api.ready?ANDROID_LOG_INFO:ANDROID_LOG_ERROR,"VcamJpegColor","encoder_verified=%d range=limited601_to_jfif",api.ready?1:0);
#endif
        if (!api.ready) { dlclose(api.handle); api.handle=nullptr; }
    });
    return api;
}

struct State {
    JpegStub original{};
    std::mutex mutex;
    size_t bytes=0;
};
} // namespace vcamjpeg

#define VCAM_EXPORT extern "C" __attribute__((visibility("default")))

VCAM_EXPORT void JpegStub_init(JpegStub* stub) noexcept {
    if (!stub) return;
    stub->compressor=nullptr;
    try {
        auto& api=vcamjpeg::encoder();
        if (!api.ready) { errno=ENODEV; return; }
        std::unique_ptr<vcamjpeg::State> state(new(std::nothrow) vcamjpeg::State);
        if (!state) { errno=ENOMEM; return; }
        api.init(&state->original);
        if (!state->original.compressor) { errno=ENOMEM; return; }
        stub->compressor=state.release();
    } catch (...) { errno=ENOMEM; }
}

VCAM_EXPORT void JpegStub_cleanup(JpegStub* stub) noexcept {
    if (!stub || !stub->compressor) return;
    auto* state=static_cast<vcamjpeg::State*>(stub->compressor);
    stub->compressor=nullptr;
    try { vcamjpeg::encoder().cleanup(&state->original); }
    catch (...) { errno=EIO; }
    delete state;
}

VCAM_EXPORT int JpegStub_compress(JpegStub* stub,const void* image,int width,int height,int quality,_ExifData* exif) noexcept {
    if (!stub || !stub->compressor) return ENODEV;
    auto* state=static_cast<vcamjpeg::State*>(stub->compressor);
    try {
        std::lock_guard<std::mutex> guard(state->mutex);
        state->bytes=0; // Failed calls cannot expose a previous, stale JPEG.
        vcamjpeg::Nv21Layout layout;
        const int dimensions=vcamjpeg::nv21Layout(width,height,width>0?size_t(width):0,layout);
        if (dimensions) return dimensions;
        if (!image || quality<0 || quality>100) return EINVAL;
#ifdef VCAM_JPEG_TESTING
        if (vcamjpeg::failAllocationForTesting) return ENOMEM;
#endif
        std::unique_ptr<uint8_t,decltype(&std::free)> converted(static_cast<uint8_t*>(std::malloc(layout.outputBytes)),&std::free);
        if (!converted) return ENOMEM;
        const int conversion=vcamjpeg::convertNv21(static_cast<const uint8_t*>(image),layout.sourceBytes,size_t(width),width,height,converted.get(),layout.outputBytes);
        if (conversion) return conversion;
#ifdef __ANDROID__
        // Bounded statistics help distinguish a range mismatch from missing
        // Camera1 chroma/stride. Never log source pixels or image contents.
        uint64_t sumY=0,sumV=0,sumU=0;
        const auto* input=static_cast<const uint8_t*>(image);
        for (size_t sample=0;sample<64;++sample) {
            sumY+=input[(layout.pixels-1)*sample/63];
            const size_t pair=(layout.pixels/4-1)*sample/63;
            sumV+=input[layout.pixels+pair*2]; sumU+=input[layout.pixels+pair*2+1];
        }
        __android_log_print(ANDROID_LOG_INFO,"VcamJpegColor","compress width=%d height=%d quality=%d inputMeanY=%llu inputMeanU=%llu inputMeanV=%llu",
            width,height,quality,static_cast<unsigned long long>(sumY/64),static_cast<unsigned long long>(sumU/64),static_cast<unsigned long long>(sumV/64));
#endif
        auto& api=vcamjpeg::encoder();
        const int result=api.compress(&state->original,converted.get(),width,height,quality,exif);
        if (result) return result;
        const size_t size=api.size(&state->original);
        if (!size || size>vcamjpeg::kMaxFrameBytes) return EOVERFLOW;
        state->bytes=size;
        return 0;
    } catch (const std::bad_alloc&) { return ENOMEM; }
      catch (...) { return EIO; }
}

VCAM_EXPORT size_t JpegStub_getCompressedSize(JpegStub* stub) noexcept {
    if (!stub || !stub->compressor) return 0;
    try {
        auto* state=static_cast<vcamjpeg::State*>(stub->compressor);
        std::lock_guard<std::mutex> guard(state->mutex);
        return state->bytes;
    } catch (...) { errno=EIO; return 0; }
}

VCAM_EXPORT void JpegStub_getCompressedImage(JpegStub* stub,void* output) noexcept {
    if (!stub || !stub->compressor || !output) { errno=EINVAL; return; }
    try {
        auto* state=static_cast<vcamjpeg::State*>(stub->compressor);
        std::lock_guard<std::mutex> guard(state->mutex);
        if (!state->bytes) { errno=EINVAL; return; }
        // This original ABI provides no output capacity. Its caller must supply
        // at least getCompressedSize() bytes, exactly as for the original stub.
        vcamjpeg::encoder().copy(&state->original,output);
    } catch (...) { errno=EIO; }
}
