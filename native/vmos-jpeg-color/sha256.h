#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>

namespace vcamjpeg {
// SHA-256 is used solely to identify the already inspected encoder before
// dlopen. No keys or user media hashes are involved.
class Sha256 {
    std::array<uint32_t, 8> state_{{0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19}};
    std::array<uint8_t, 64> pending_{};
    size_t used_ = 0;
    uint64_t bytes_ = 0;
    static uint32_t rotate(uint32_t x, unsigned bits) { return (x >> bits) | (x << (32 - bits)); }
    void block(const uint8_t* input) {
        static constexpr uint32_t k[64] = {
            0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
            0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
            0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
            0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
            0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
            0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
            0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
            0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
        uint32_t words[64];
        for (unsigned i = 0; i < 16; ++i)
            words[i] = uint32_t(input[i*4]) << 24 | uint32_t(input[i*4+1]) << 16 | uint32_t(input[i*4+2]) << 8 | input[i*4+3];
        for (unsigned i = 16; i < 64; ++i) {
            const uint32_t a = words[i-15], b = words[i-2];
            words[i] = words[i-16] + (rotate(a,7)^rotate(a,18)^(a>>3)) + words[i-7] + (rotate(b,17)^rotate(b,19)^(b>>10));
        }
        uint32_t a=state_[0], b=state_[1], c=state_[2], d=state_[3], e=state_[4], f=state_[5], g=state_[6], h=state_[7];
        for (unsigned i=0; i<64; ++i) {
            const uint32_t t1=h+(rotate(e,6)^rotate(e,11)^rotate(e,25))+((e&f)^(~e&g))+k[i]+words[i];
            const uint32_t t2=(rotate(a,2)^rotate(a,13)^rotate(a,22))+((a&b)^(a&c)^(b&c));
            h=g; g=f; f=e; e=d+t1; d=c; c=b; b=a; a=t1+t2;
        }
        state_[0]+=a; state_[1]+=b; state_[2]+=c; state_[3]+=d; state_[4]+=e; state_[5]+=f; state_[6]+=g; state_[7]+=h;
    }
public:
    void update(const void* bytes, size_t count) {
        const auto* data = static_cast<const uint8_t*>(bytes);
        bytes_ += count;
        while (count) {
            const size_t take = count < 64-used_ ? count : 64-used_;
            std::memcpy(pending_.data()+used_,data,take);
            used_+=take; data+=take; count-=take;
            if (used_==64) { block(pending_.data()); used_=0; }
        }
    }
    std::string finish() {
        const uint64_t bits = bytes_*8;
        const uint8_t one=0x80, zero=0;
        update(&one,1);
        while (used_!=56) update(&zero,1);
        uint8_t length[8];
        for (unsigned i=0;i<8;++i) length[7-i]=uint8_t(bits>>(i*8));
        update(length,8);
        static constexpr char digits[]="0123456789abcdef";
        std::string out;
        out.reserve(64);
        for (uint32_t word:state_) for (int shift=28;shift>=0;shift-=4) out.push_back(digits[(word>>shift)&15]);
        return out;
    }
};
} // namespace vcamjpeg
