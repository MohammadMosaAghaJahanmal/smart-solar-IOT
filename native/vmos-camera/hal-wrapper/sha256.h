// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <array>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace vcam {
// FIPS 180-4 SHA-256. Used only to pin the measured underlying HAL before dlopen.
inline std::string sha256(const std::vector<uint8_t>& bytes) {
    static constexpr uint32_t k[64] = {
        0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
        0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
        0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
        0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
        0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
        0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
        0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
        0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
    auto r = [](uint32_t x, unsigned n) { return (x >> n) | (x << (32 - n)); };
    auto padded = bytes;
    padded.push_back(0x80);
    while (padded.size() % 64 != 56) padded.push_back(0);
    const uint64_t bits = static_cast<uint64_t>(bytes.size()) * 8;
    for (int i = 7; i >= 0; --i) padded.push_back(static_cast<uint8_t>(bits >> (i * 8)));
    std::array<uint32_t,8> h{0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
    for (size_t offset = 0; offset < padded.size(); offset += 64) {
        uint32_t w[64];
        for (size_t i = 0; i < 16; ++i) {
            const auto* p = padded.data() + offset + i * 4;
            w[i] = (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | p[3];
        }
        for (size_t i = 16; i < 64; ++i)
            w[i] = w[i-16] + (r(w[i-15],7)^r(w[i-15],18)^(w[i-15]>>3)) + w[i-7] + (r(w[i-2],17)^r(w[i-2],19)^(w[i-2]>>10));
        auto v = h;
        for (size_t i = 0; i < 64; ++i) {
            const uint32_t t = v[7] + (r(v[4],6)^r(v[4],11)^r(v[4],25)) + ((v[4]&v[5])^(~v[4]&v[6])) + k[i] + w[i];
            const uint32_t u = (r(v[0],2)^r(v[0],13)^r(v[0],22)) + ((v[0]&v[1])^(v[0]&v[2])^(v[1]&v[2]));
            for (size_t j = 7; j > 0; --j) v[j] = v[j-1];
            v[4] += t; v[0] = t + u;
        }
        for (size_t i = 0; i < h.size(); ++i) h[i] += v[i];
    }
    static constexpr char hex[] = "0123456789abcdef";
    std::string result;
    for (auto x : h) for (int i = 7; i >= 0; --i) result.push_back(hex[(x >> (i * 4)) & 15]);
    return result;
}
}
