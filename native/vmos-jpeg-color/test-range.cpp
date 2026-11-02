#include "nv21-range.h"
#include "sha256.h"
#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

int main(int argc,char** argv) {
    using namespace vcamjpeg;
    for (const auto& item:std::array<std::pair<std::string,std::string>,3>{{
        {"","e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"},
        {"abc","ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"},
        {std::string(1000000,'a'),"cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"}}}) {
        Sha256 digest;
        for (size_t at=0;at<item.first.size();at+=17) digest.update(item.first.data()+at,std::min<size_t>(17,item.first.size()-at));
        assert(digest.finish()==item.second);
    }
    if (argc==2) {
        std::ifstream input(argv[1],std::ios::binary);
        assert(input.good());
        std::vector<char> bytes((std::istreambuf_iterator<char>(input)),{});
        Sha256 digest; digest.update(bytes.data(),bytes.size());
        assert(digest.finish()=="0975abc8d3eec3f57bd607458101f69a11f0a7c93b2de97094cfab534175c2e3");
    }
    assert(fullLuma(0)==0 && fullLuma(16)==0 && fullLuma(235)==255 && fullLuma(255)==255);
    assert(fullChroma(16)==0 && fullChroma(128)==128 && fullChroma(240)==255);
    for (int value=1;value<=255;++value) {
        assert(fullLuma(uint8_t(value))>=fullLuma(uint8_t(value-1)));
        assert(fullChroma(uint8_t(value))>=fullChroma(uint8_t(value-1)));
    }
    // Independent JFIF inverse tests all 4096 combinations in a dense RGB
    // palette, including every gray, primary, secondary and muted color.
    const int values[]={0,8,16,32,48,64,80,96,112,128,144,160,192,224,240,255};
    auto clamp=[](int x) { return std::clamp(x,0,255); };
    int worst=0;
    for (int r:values) for (int g:values) for (int b:values) {
        // These are the deployed proxy's existing limited BT.601 samples.
        const uint8_t y=uint8_t(((66*r+129*g+25*b+128)>>8)+16);
        const uint8_t u=uint8_t(((-38*r-74*g+112*b+128)>>8)+128);
        const uint8_t v=uint8_t(((112*r-94*g-18*b+128)>>8)+128);
        const std::array<uint8_t,6> source{{y,y,y,y,v,u}};
        std::array<uint8_t,6> result{};
        assert(convertNv21(source.data(),source.size(),2,2,2,result.data(),result.size())==0);
        const double yy=result[0], uu=int(result[5])-128, vv=int(result[4])-128;
        const int decoded[]={clamp(int(std::lround(yy+1.402*vv))),clamp(int(std::lround(yy-.344136*uu-.714136*vv))),clamp(int(std::lround(yy+1.772*uu)))};
        const int expected[]={r,g,b};
        for (int channel=0;channel<3;++channel) worst=std::max(worst,std::abs(decoded[channel]-expected[channel]));
        if (r==g && g==b) assert(decoded[0]==decoded[1] && decoded[1]==decoded[2]);
    }
    assert(worst<=3);
    // Source padding is skipped, destination guard bytes and input untouched.
    std::array<uint8_t,24> source{{16,40,100,235,231,232,0,255,126,200,233,234,240,16,128,128,235,236,99,100,101,102,237,238}};
    const auto original=source;
    std::array<uint8_t,16> output; output.fill(0xaa);
    assert(convertNv21(source.data(),18,6,4,2,output.data()+2,12)==0);
    assert(source==original && output[0]==0xaa && output[1]==0xaa && output[14]==0xaa && output[15]==0xaa);
    assert(output[2]==0 && output[5]==255 && output[6]==0 && output[7]==255);
    assert(output[10]==255 && output[11]==0 && output[12]==128 && output[13]==128);
    Nv21Layout layout;
    for (const auto& size:std::array<std::pair<int,int>,6>{{{0,2},{2,0},{-2,2},{3,2},{2,3},{4098,2}}}) assert(nv21Layout(size.first,size.second,4096,layout)==EINVAL);
    assert(nv21Layout(2,2,std::numeric_limits<size_t>::max()-1,layout)==EOVERFLOW);
    assert(nv21Layout(4096,4096,8192,layout)==EOVERFLOW);
    assert(nv21Layout(4,2,2,layout)==EINVAL && nv21Layout(2,2,3,layout)==EINVAL);
    assert(nv21Layout(1920,1080,1920,layout)==0 && layout.outputBytes==3110400);
    assert(convertNv21(source.data(),17,6,4,2,output.data(),12)==EINVAL);
    assert(convertNv21(source.data(),18,6,4,2,output.data(),11)==EINVAL);
    assert(convertNv21(nullptr,18,6,4,2,output.data(),12)==EINVAL);
    assert(convertNv21(source.data(),18,6,4,2,source.data()+1,12)==EINVAL);
    std::printf("SHA256 vectors/module, NV21 bounds/stride/input preservation, 4096-color JFIF palette: PASS worst_channel_error=%d\n",worst);
}
