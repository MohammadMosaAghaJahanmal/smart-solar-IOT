// Temporary exact-build JPEG JNI observer. Forwarding only; no pixel changes.
#include "../vmos-jpeg-color/sha256.h"
#include <jni.h>
#include <atomic>
#include <cerrno>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <mutex>
#include <sys/stat.h>
#include <unistd.h>
#ifdef __ANDROID__
#include <android/log.h>
#include <sys/system_properties.h>
#endif

namespace vcamjni {
#ifdef VCAM_JPEGUTIL_TESTING
constexpr const char* kOriginal=VCAM_JPEGUTIL_TEST_ORIGINAL;
constexpr const char* kHash=VCAM_JPEGUTIL_TEST_HASH;
constexpr size_t kBytes=0;
#else
constexpr const char* kOriginal="/system/lib64/.vcam-original-libjni_jpegutil.so";
constexpr const char* kHash="6050a81c4eae572989b284bc55531a853efe83512b5413b7bffe00fb308c2bd5";
constexpr size_t kBytes=465864;
#endif
// The inspected VMOS rootfs presents its unchanged system libraries as 0777.
// Accept only that measured exception, never arbitrary writable modes. Identity,
// root owner, link count, exact ELF size/hash and before/after inode checks remain.
bool originalMode(mode_t mode) { return !(mode&0022) || (mode&07777)==0777; }
void log(const char* format,...) {
    va_list args;va_start(args,format);
#ifdef __ANDROID__
    __android_log_vprint(ANDROID_LOG_INFO,"VcamJpegJni",format,args);
#else
    std::vfprintf(stderr,format,args);std::fputc('\n',stderr);
#endif
    va_end(args);
}
struct PropertyValue { char value[256]{};bool valid=false; };
void propertyCallback(void* cookie,const char*,const char* value,uint32_t) {
    auto& result=*static_cast<PropertyValue*>(cookie);
    result={};
    if (!value) return;
    const size_t length=strnlen(value,sizeof(result.value));
    if (!length || length>=sizeof(result.value)) return;
    std::memcpy(result.value,value,length+1);result.valid=true;
}
bool fingerprintMatches(const PropertyValue& property) {
    constexpr const char* suffix=":9/PQ3A.190801.002/eng.builds.20191015.144423:user/release-keys";
    const size_t length=std::strlen(property.value),s=std::strlen(suffix);
    return property.valid && length>=s && !std::strcmp(property.value+length-s,suffix);
}
#ifdef __ANDROID__
PropertyValue property(const char* name) {
    PropertyValue value;
    // Read-only properties may exceed PROP_VALUE_MAX since Android O. The
    // callback API supplies their actual value without legacy truncation.
    const prop_info* info=__system_property_find(name);
    if (info) __system_property_read_callback(info,propertyCallback,&value);
    return value;
}
#endif
bool guest() {
#ifdef VCAM_JPEGUTIL_TESTING
    return true;
#elif defined(__ANDROID__) && defined(__aarch64__)
    const auto sdk=property("ro.build.version.sdk");
    if (!sdk.valid || std::strcmp(sdk.value,"28")) {log("identity_rejected=sdk");return false;}
    const auto hardware=property("ro.hardware");
    if (!hardware.valid || std::strcmp(hardware.value,"vphw90")) {log("identity_rejected=hardware");return false;}
    const auto abi=property("ro.product.cpu.abilist");
    if (!abi.valid || std::strcmp(abi.value,"arm64-v8a")) {log("identity_rejected=abi");return false;}
    const auto fingerprint=property("ro.build.fingerprint");
    if (!fingerprint.valid) {log("identity_rejected=fingerprint_missing_or_overbound");return false;}
    if (!fingerprintMatches(fingerprint)) {log("identity_rejected=fingerprint_build");return false;}
    return true;
#else
    return false;
#endif
}
bool originalVerified() {
    struct stat named{},opened{},after{};
    if (lstat(kOriginal,&named) || !S_ISREG(named.st_mode) || named.st_nlink!=1 || named.st_size<=0 || named.st_size>2*1024*1024) return false;
#ifndef VCAM_JPEGUTIL_TESTING
    if (named.st_uid!=0 || uint64_t(named.st_size)!=kBytes || !originalMode(named.st_mode)) return false;
#endif
    const int fd=open(kOriginal,O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
    if (fd<0) return false;
    bool ok=!fstat(fd,&opened) && named.st_dev==opened.st_dev && named.st_ino==opened.st_ino &&
        named.st_mode==opened.st_mode && named.st_size==opened.st_size && named.st_nlink==opened.st_nlink;
    vcamjpeg::Sha256 digest;uint8_t buffer[4096];size_t total=0;
    while (ok) {
        const ssize_t got=read(fd,buffer,sizeof(buffer));
        if (got<0 && errno==EINTR) continue;
        if (got<0) { ok=false;break; }
        if (!got) break;
#ifndef VCAM_JPEGUTIL_TESTING
        if (!total && (got<20 || std::memcmp(buffer,"\177ELF\002\001",6) || buffer[16]!=3 || buffer[17]!=0 || buffer[18]!=183 || buffer[19]!=0)) ok=false;
#endif
        total+=size_t(got);if (total>size_t(named.st_size)) {ok=false;break;}
        digest.update(buffer,size_t(got));
    }
    ok=ok && total==size_t(named.st_size) && !lstat(kOriginal,&after) &&
        named.st_dev==after.st_dev && named.st_ino==after.st_ino && named.st_mode==after.st_mode &&
        named.st_size==after.st_size && named.st_uid==after.st_uid && named.st_nlink==after.st_nlink && digest.finish()==kHash;
    close(fd);return ok;
}
using Compress=jint (*)(JNIEnv*,jclass,jint,jint,jobject,jint,jint,jobject,jint,jint,jobject,jint,jint,jobject,jint,jint,jint,jint,jint,jint,jint);
using Copy=void (*)(JNIEnv*,jclass,jint,jint,jobject,jint,jint,jobject,jint);
struct Api {void* handle=nullptr;Compress compress=nullptr;Copy copy=nullptr;bool ready=false;};
Api& api() {
    static Api value;static std::once_flag once;
    std::call_once(once,[] {
        if (!guest() || !originalVerified()) {log("original_verified=0 reason=identity_or_hash");return;}
        value.handle=dlopen(kOriginal,RTLD_NOW|RTLD_LOCAL);
        if (!value.handle) {log("original_verified=0 reason=dlopen");return;}
        value.compress=reinterpret_cast<Compress>(dlsym(value.handle,"Java_com_android_camera_util_JpegUtilNative_compressJpegFromYUV420pNative"));
        value.copy=reinterpret_cast<Copy>(dlsym(value.handle,"Java_com_android_camera_util_JpegUtilNative_copyImagePlaneToBitmap"));
        Dl_info info{};
        value.ready=value.compress && value.copy && dladdr(reinterpret_cast<void*>(value.compress),&info) &&
            info.dli_fname && !std::strcmp(info.dli_fname,kOriginal) && originalVerified();
        log("original_verified=%d observer=statistics_only original_has_JNI_OnLoad=%d",value.ready?1:0,dlsym(value.handle,"JNI_OnLoad")?1:0);
        if (!value.ready) {dlclose(value.handle);value.handle=nullptr;value.compress=nullptr;value.copy=nullptr;}
    });
    return value;
}
struct Plane {
    const uint8_t* bytes=nullptr;jlong capacity=-1;int pixel=0,row=0;
};
struct Stats {bool valid=false;unsigned meanMilli=0,minimum=0,maximum=0;};
// Every read is checked against the JNI capacity. An invalid layout skips
// observation and is still forwarded unchanged to preserve original behavior.
Stats sample(const Plane& p,int width,int height,int left,int top,int right,int bottom,int divisor) {
    Stats result;
    if (!p.bytes || p.capacity<=0 || width<=0 || height<=0 || width>16384 || height>16384 ||
        p.pixel<=0 || p.row<=0 || p.pixel>16384 || p.row>64*1024*1024 ||
        left<0 || top<0 || right<=left || bottom<=top || right>width || bottom>height || (divisor!=1 && divisor!=2)) return result;
    const int firstX=left/divisor,firstY=top/divisor,lastX=(right-1)/divisor,lastY=(bottom-1)/divisor;
    const uint64_t last=uint64_t(lastY)*unsigned(p.row)+uint64_t(lastX)*unsigned(p.pixel);
    if (last>=uint64_t(p.capacity)) return result;
    uint64_t sum=0;unsigned low=255,high=0;
    for (unsigned sy=0;sy<8;++sy) for (unsigned sx=0;sx<8;++sx) {
        const uint64_t x=unsigned(firstX)+uint64_t(lastX-firstX)*sx/7;
        const uint64_t y=unsigned(firstY)+uint64_t(lastY-firstY)*sy/7;
        const uint64_t at=y*unsigned(p.row)+x*unsigned(p.pixel);
        if (at>=uint64_t(p.capacity)) return {};
        const unsigned b=p.bytes[at];sum+=b;if(b<low)low=b;if(b>high)high=b;
    }
    result.valid=true;result.meanMilli=unsigned(sum*1000/64);result.minimum=low;result.maximum=high;return result;
}
Plane plane(JNIEnv* env,jobject buffer,int pixel,int row) {
    if (!buffer) return {nullptr,-1,pixel,row};
    return {static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer)),env->GetDirectBufferCapacity(buffer),pixel,row};
}
std::atomic<unsigned> calls{0};
} // namespace vcamjni

#define VCAM_EXPORT extern "C" __attribute__((visibility("default")))
VCAM_EXPORT jint Java_com_android_camera_util_JpegUtilNative_compressJpegFromYUV420pNative(
    JNIEnv* env,jclass type,jint width,jint height,
    jobject y,jint yp,jint yr,jobject u,jint up,jint ur,jobject v,jint vp,jint vr,
    jobject output,jint capacity,jint quality,jint left,jint top,jint right,jint bottom,jint rotation) {
    auto& original=vcamjni::api();
    if (!original.ready) return -1;
    const unsigned call=vcamjni::calls.fetch_add(1,std::memory_order_relaxed)+1;
    if (call<=256 && env) {
        const auto py=vcamjni::plane(env,y,yp,yr),pu=vcamjni::plane(env,u,up,ur),pv=vcamjni::plane(env,v,vp,vr);
        const auto ys=vcamjni::sample(py,width,height,left,top,right,bottom,1);
        const auto us=vcamjni::sample(pu,width,height,left,top,right,bottom,2);
        const auto vs=vcamjni::sample(pv,width,height,left,top,right,bottom,2);
        const jlong outCapacity=output?env->GetDirectBufferCapacity(output):-1;
        const bool vuAdjacent=pv.bytes && pu.bytes && uintptr_t(pv.bytes)<UINTPTR_MAX && uintptr_t(pv.bytes)+1==uintptr_t(pu.bytes);
        const bool uvAdjacent=pv.bytes && pu.bytes && uintptr_t(pu.bytes)<UINTPTR_MAX && uintptr_t(pu.bytes)+1==uintptr_t(pv.bytes);
        vcamjni::log("call=%u width=%d height=%d quality=%d crop=%d,%d,%d,%d rot90=%d yPixel=%d yRow=%d yCap=%lld uPixel=%d uRow=%d uCap=%lld vPixel=%d vRow=%d vCap=%lld outArgCap=%d outDirectCap=%lld vuAdjacent=%d uvAdjacent=%d validYUV=%d%d%d meanMilliYUV=%u,%u,%u minYUV=%u,%u,%u maxYUV=%u,%u,%u",
            call,width,height,quality,left,top,right,bottom,rotation,yp,yr,static_cast<long long>(py.capacity),up,ur,static_cast<long long>(pu.capacity),vp,vr,static_cast<long long>(pv.capacity),capacity,static_cast<long long>(outCapacity),vuAdjacent?1:0,uvAdjacent?1:0,ys.valid?1:0,us.valid?1:0,vs.valid?1:0,ys.meanMilli,us.meanMilli,vs.meanMilli,ys.minimum,us.minimum,vs.minimum,ys.maximum,us.maximum,vs.maximum);
    }
    const jint result=original.compress(env,type,width,height,y,yp,yr,u,up,ur,v,vp,vr,output,capacity,quality,left,top,right,bottom,rotation);
    if (call<=256) vcamjni::log("call=%u original_return=%d",call,result);
    return result;
}
VCAM_EXPORT void Java_com_android_camera_util_JpegUtilNative_copyImagePlaneToBitmap(
    JNIEnv* env,jclass type,jint width,jint height,jobject buffer,jint pixel,jint row,jobject bitmap,jint rotation) {
    auto& original=vcamjni::api();if(original.ready)original.copy(env,type,width,height,buffer,pixel,row,bitmap,rotation);
}
