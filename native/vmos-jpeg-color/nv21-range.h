#pragma once

#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <limits>

namespace vcamjpeg {
constexpr size_t kMaxFrameBytes = 32u * 1024u * 1024u;
constexpr int kMaxDimension = 4096;

struct Nv21Layout {
    size_t sourceBytes = 0;
    size_t outputBytes = 0;
    size_t pixels = 0;
};

inline int nv21Layout(int width, int height, size_t sourceStride, Nv21Layout& layout) {
    layout = {};
    if (width <= 0 || height <= 0 || width > kMaxDimension || height > kMaxDimension ||
        (width & 1) || (height & 1) || sourceStride < size_t(width) || (sourceStride & 1)) return EINVAL;
    const size_t rows = size_t(height) + size_t(height) / 2;
    if (sourceStride > std::numeric_limits<size_t>::max() / rows) return EOVERFLOW;
    const size_t sourceBytes = sourceStride * rows;
    const size_t outputBytes = size_t(width) * rows;
    if (sourceBytes > kMaxFrameBytes || outputBytes > kMaxFrameBytes) return EOVERFLOW;
    layout = {sourceBytes, outputBytes, size_t(width) * size_t(height)};
    return 0;
}

inline uint8_t fullLuma(uint8_t sample) {
    if (sample <= 16) return 0;
    if (sample >= 235) return 255;
    return uint8_t(((int(sample) - 16) * 255 + 109) / 219);
}

inline uint8_t fullChroma(uint8_t sample) {
    const int numerator = (int(sample) - 128) * 255;
    // Symmetric rounding keeps neutral 128 exactly neutral, including below
    // center where signed C++ division otherwise truncates toward zero.
    const int scaled = numerator >= 0 ? (numerator + 112) / 224 : -((-numerator + 112) / 224);
    const int value = 128 + scaled;
    return uint8_t(value < 0 ? 0 : value > 255 ? 255 : value);
}

inline bool overlaps(const uint8_t* a, size_t aSize, const uint8_t* b, size_t bSize) {
    const uintptr_t first = reinterpret_cast<uintptr_t>(a), second = reinterpret_cast<uintptr_t>(b);
    return first <= second ? second - first < aSize : first - second < bSize;
}

// Explicit strides exist for CPU validation of padding. The inspected JpegStub
// ABI has no stride/length arguments and supplies tight NV21 (stride == width).
inline int convertNv21(const uint8_t* source, size_t sourceBytes, size_t sourceStride,
                       int width, int height, uint8_t* destination, size_t destinationBytes) {
    Nv21Layout layout;
    const int result = nv21Layout(width, height, sourceStride, layout);
    if (result) return result;
    if (!source || !destination || sourceBytes < layout.sourceBytes || destinationBytes < layout.outputBytes ||
        overlaps(source, layout.sourceBytes, destination, layout.outputBytes)) return EINVAL;
    for (int row = 0; row < height; ++row) {
        for (int column = 0; column < width; ++column)
            destination[size_t(row) * size_t(width) + size_t(column)] = fullLuma(source[size_t(row) * sourceStride + size_t(column)]);
    }
    const uint8_t* chroma = source + sourceStride * size_t(height);
    uint8_t* outputChroma = destination + layout.pixels;
    for (int row = 0; row < height / 2; ++row) {
        // Apply the same centered range change independently: preserve V,U
        // ordering rather than swapping or reinterpreting chroma channels.
        for (int column = 0; column < width; ++column)
            outputChroma[size_t(row) * size_t(width) + size_t(column)] = fullChroma(chroma[size_t(row) * sourceStride + size_t(column)]);
    }
    return 0;
}
} // namespace vcamjpeg
