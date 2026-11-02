"""CPU tests of exact JNI forwarding and bounded aggregate sampling; no device."""
from pathlib import Path
import hashlib
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[2]
SOURCE=ROOT/'native/vmos-jpegutil-diagnostic'
JNI=Path('/mnt/c/Users/JahanmalAgha/AppData/Local/Android/Sdk/ndk/27.1.12297006/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/include')
FAKE=r'''
#include <jni.h>
#include <cstring>
struct Record {void* env;void* type;int width,height;void* y;int yp,yr;void* u;int up,ur;void* v;int vp,vr;void* out;int cap,q,l,t,r,b,rot;};
extern "C" { Record fixture{}; int copied=0; }
extern "C" jint Java_com_android_camera_util_JpegUtilNative_compressJpegFromYUV420pNative(
 JNIEnv* env,jclass type,jint w,jint h,jobject y,jint yp,jint yr,jobject u,jint up,jint ur,jobject v,jint vp,jint vr,
 jobject out,jint cap,jint q,jint l,jint t,jint r,jint b,jint rot) {
 fixture={env,type,w,h,y,yp,yr,u,up,ur,v,vp,vr,out,cap,q,l,t,r,b,rot};
 return q==13?-1:777;
}
extern "C" void Java_com_android_camera_util_JpegUtilNative_copyImagePlaneToBitmap(
 JNIEnv* env,jclass type,jint w,jint h,jobject p,jint pixel,jint row,jobject bitmap,jint rot) {
 fixture={env,type,w,h,p,pixel,row,nullptr,0,0,nullptr,0,0,bitmap,0,0,0,0,0,0,rot};copied++;
}
'''
HARNESS=r'''
#include "jpegutil-diagnostic.cpp"
#include <array>
#include <cassert>
#include <iostream>
#include <limits>
struct Buffer {uint8_t* bytes;jlong size;};
struct Record {void* env;void* type;int width,height;void* y;int yp,yr;void* u;int up,ur;void* v;int vp,vr;void* out;int cap,q,l,t,r,b,rot;};
void* address(JNIEnv*,jobject object){return object?reinterpret_cast<Buffer*>(object)->bytes:nullptr;}
jlong capacity(JNIEnv*,jobject object){return object?reinterpret_cast<Buffer*>(object)->size:-1;}
jobject object(Buffer& b){return reinterpret_cast<jobject>(&b);}
int main(int argc,char**) {
 JNINativeInterface table{};table.GetDirectBufferAddress=address;table.GetDirectBufferCapacity=capacity;
 JNIEnv env{};env.functions=&table;jclass type=reinterpret_cast<jclass>(uintptr_t(0x1234));
 assert(vcamjni::originalMode(S_IFREG|0644) && vcamjni::originalMode(S_IFREG|0777));
 assert(!vcamjni::originalMode(S_IFREG|0666) && !vcamjni::originalMode(S_IFREG|0775) && !vcamjni::originalMode(S_IFREG|04777));
 vcamjni::PropertyValue property;
 const std::string fp=std::string(34,'x')+":9/PQ3A.190801.002/eng.builds.20191015.144423:user/release-keys";
 assert(fp.size()>92 && fp.size()<256);
 vcamjni::propertyCallback(&property,"ignored",fp.c_str(),1);
 assert(property.valid && std::string(property.value)==fp && vcamjni::fingerprintMatches(property));
 vcamjni::propertyCallback(&property,"ignored",(fp+"x").c_str(),1);
 assert(property.valid && !vcamjni::fingerprintMatches(property));
 vcamjni::propertyCallback(&property,"ignored",std::string(256,'x').c_str(),1);
 assert(!property.valid && !vcamjni::fingerprintMatches(property));
 vcamjni::propertyCallback(&property,"ignored",nullptr,1);assert(!property.valid);
 vcamjni::propertyCallback(&property,"ignored","",1);assert(!property.valid);
 vcamjni::propertyCallback(&property,"ignored","short",1);assert(property.valid && !vcamjni::fingerprintMatches(property));
 std::array<uint8_t,20> bytes{{10,0xa5,20,0xa5,30,0xa5,40,0xa5,50,0xa5,60,0xa5,70,0xa5,80,0xa5,0xa5,0xa5,0xa5,0xa5}};
 const auto before=bytes;
 const auto p=vcamjni::Plane{bytes.data(),16,2,8};
 const auto stats=vcamjni::sample(p,4,2,0,0,4,2,1);
 assert(stats.valid && stats.meanMilli==26250 && stats.minimum==10 && stats.maximum==80);
 const auto crop=vcamjni::sample(p,4,2,1,0,3,2,1);
 assert(crop.valid && crop.minimum==20 && crop.maximum==70);
 assert(!vcamjni::sample({bytes.data(),14,2,8},4,2,0,0,4,2,1).valid);
 assert(!vcamjni::sample({nullptr,100,2,8},4,2,0,0,4,2,1).valid);
 assert(!vcamjni::sample({bytes.data(),-1,2,8},4,2,0,0,4,2,1).valid);
 assert(!vcamjni::sample({bytes.data(),20,0,8},4,2,0,0,4,2,1).valid);
 assert(!vcamjni::sample({bytes.data(),20,2,0},4,2,0,0,4,2,1).valid);
 assert(!vcamjni::sample(p,16385,2,0,0,4,2,1).valid);
 assert(!vcamjni::sample(p,4,2,-1,0,4,2,1).valid);
 assert(!vcamjni::sample(p,4,2,0,0,5,2,1).valid);
 assert(!vcamjni::sample(p,4,2,0,0,4,2,0).valid);
 assert(!vcamjni::sample({bytes.data(),std::numeric_limits<jlong>::max(),16385,8},4,2,0,0,4,2,1).valid);
 assert(!vcamjni::sample({bytes.data(),std::numeric_limits<jlong>::max(),2,67108865},4,2,0,0,4,2,1).valid);
 std::array<uint8_t,64> ybytes{},ubytes{},vbytes{},output{};
 ybytes.fill(135);ubytes.fill(125);vbytes.fill(132);output.fill(0xa5);
 Buffer y{ybytes.data(),64},u{ubytes.data(),64},v{vbytes.data(),64},out{output.data(),64};
 auto call=[&](int q){return Java_com_android_camera_util_JpegUtilNative_compressJpegFromYUV420pNative(&env,type,8,8,object(y),1,8,object(u),2,8,object(v),2,8,object(out),60,q,1,2,7,6,3);};
 if (argc>1) {assert(call(95)==-1);assert(!vcamjni::api().ready);for(auto b:output)assert(b==0xa5);std::cout<<"foreign original hash refused: PASS\n";return 0;}
 assert(call(95)==777);
 auto* record=static_cast<Record*>(dlsym(vcamjni::api().handle,"fixture"));assert(record);
 assert(record->env==&env && record->type==type && record->width==8 && record->height==8);
 assert(record->y==object(y) && record->yp==1 && record->yr==8 && record->u==object(u) && record->up==2 && record->ur==8);
 assert(record->v==object(v) && record->vp==2 && record->vr==8 && record->out==object(out));
 assert(record->cap==60 && record->q==95 && record->l==1 && record->t==2 && record->r==7 && record->b==6 && record->rot==3);
 assert(call(13)==-1);assert(record->q==13);
 // Invalid observer bounds do not silently rewrite/reject the original call.
 u.size=1;assert(call(90)==777);assert(record->up==2 && record->ur==8);
 Java_com_android_camera_util_JpegUtilNative_copyImagePlaneToBitmap(&env,type,2,3,object(y),4,5,object(out),2);
 assert(*static_cast<int*>(dlsym(vcamjni::api().handle,"copied"))==1);
 assert(record->env==&env && record->type==type && record->width==2 && record->height==3 && record->y==object(y) && record->yp==4 && record->yr==5 && record->out==object(out) && record->rot==2);
 assert(bytes==before);for(auto b:ybytes)assert(b==135);for(auto b:ubytes)assert(b==125);for(auto b:vbytes)assert(b==132);for(auto b:output)assert(b==0xa5);
 std::cout<<"JNI all-argument/return/copy forwarding, padding/crop/capacity/invalid-layout observation bounds, input/output preservation: PASS\n";
}
'''

def run(args):
    result=subprocess.run(args,text=True,capture_output=True)
    if result.returncode:
        print(result.stdout);print(result.stderr)
        result.check_returncode()
    return result

def main():
    with tempfile.TemporaryDirectory(prefix='vcam-jni-') as name:
        work=Path(name);fake=work/'original.cpp';fake.write_text(FAKE)
        # JNI itself is VM-neutral; do not put Android's libc headers ahead
        # of the Linux headers used by this CPU fixture.
        (work/'jni.h').write_bytes((JNI/'jni.h').read_bytes())
        lib=work/'original.so'
        run(['g++','-std=c++17','-O2','-fPIC','-shared','-I'+str(work),str(fake),'-o',str(lib)])
        digest=hashlib.sha256(lib.read_bytes()).hexdigest()
        harness=work/'harness.cpp';harness.write_text(HARNESS)
        for label,pin in [('valid',digest),('wrong','0'*64)]:
            binary=work/label
            run(['g++','-std=c++17','-O2','-Wall','-Wextra','-Werror','-DVCAM_JPEGUTIL_TESTING',
                '-DVCAM_JPEGUTIL_TEST_ORIGINAL="'+str(lib)+'"','-DVCAM_JPEGUTIL_TEST_HASH="'+pin+'"',
                '-I'+str(SOURCE),'-I'+str(work),str(harness),'-ldl','-pthread','-o',str(binary)])
            result=run([str(binary)]+(['wrong'] if label=='wrong' else []))
            print(result.stdout.strip())
            if label=='valid':
                assert 'meanMilliYUV=135000,125000,132000' in result.stderr
                assert 'validYUV=101' in result.stderr
                print('Aggregate log values and invalid-capacity skip: PASS')

if __name__=='__main__':main()
