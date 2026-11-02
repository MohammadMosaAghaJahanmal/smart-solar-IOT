// SPDX-License-Identifier: Apache-2.0
// Isolated Android Emulator Offworld proof. This does not impersonate a camera.
// Protocol: platform/external/qemu at 31777795c7b4eee01e0451bae8ac6196d31df280,
// android/android-emu/android/offworld/proto/offworld.proto.
// Transport: device/generic/goldfish-opengl shared/qemupipe/qemu_pipe_guest.cpp.
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <linux/vm_sockets.h>
#include <time.h>
#include <unistd.h>
#include <vector>

using Bytes = std::vector<uint8_t>;
static constexpr size_t kLimit = 64 * 1024 * 1024;
static int channel = -1;

[[noreturn]] static void fail(const char* what) {
    fprintf(stderr, "FAIL %s: %s\n", what, strerror(errno));
    if (channel >= 0) close(channel);
    exit(1);
}
static int64_t millis() {
    timespec t{}; clock_gettime(CLOCK_MONOTONIC, &t);
    return int64_t(t.tv_sec) * 1000 + t.tv_nsec / 1000000;
}
static void transfer(void* memory, size_t count, bool writing, int timeout = 15000) {
    auto* data = static_cast<uint8_t*>(memory);
    const int64_t deadline = millis() + timeout;
    while (count) {
        const int64_t remaining = deadline - millis();
        if (remaining <= 0) { errno = ETIMEDOUT; fail(writing ? "write timeout" : "read timeout (is videoplayback camera open?)"); }
        pollfd p{channel, short(writing ? POLLOUT : POLLIN), 0};
        int ready = poll(&p, 1, int(remaining));
        if (ready < 0 && errno == EINTR) continue;
        if (ready <= 0) { errno = ready == 0 ? ETIMEDOUT : errno; fail("poll emulator pipe"); }
        ssize_t n = writing ? write(channel, data, count) : read(channel, data, count);
        if (n < 0 && (errno == EINTR || errno == EAGAIN)) continue;
        if (n <= 0) { if (!n) errno = EPIPE; fail(writing ? "write emulator pipe" : "read emulator pipe"); }
        data += n; count -= size_t(n);
    }
}
static void send(const Bytes& payload) {
    uint32_t n = uint32_t(payload.size());
    uint8_t header[] = {uint8_t(n), uint8_t(n >> 8), uint8_t(n >> 16), uint8_t(n >> 24)};
    transfer(header, 4, true); transfer(const_cast<uint8_t*>(payload.data()), n, true);
}
static Bytes receive(int timeout = 15000) {
    const int64_t deadline = millis() + timeout;
    uint8_t h[4]; transfer(h, 4, false, timeout);
    uint32_t n = h[0] | uint32_t(h[1]) << 8 | uint32_t(h[2]) << 16 | uint32_t(h[3]) << 24;
    if (n > kLimit) { errno = EOVERFLOW; fail("oversized emulator reply"); }
    const int remaining = int(deadline - millis());
    if (remaining <= 0) { errno = ETIMEDOUT; fail("read emulator reply timeout"); }
    Bytes b(n); transfer(b.data(), n, false, remaining); return b;
}
static void varint(Bytes& b, uint64_t value) {
    while (value >= 128) { b.push_back(uint8_t(value) | 128); value >>= 7; }
    b.push_back(uint8_t(value));
}
static void scalar(Bytes& b, unsigned field, uint64_t value) { varint(b, field << 3); varint(b, value); }
static void float32(Bytes& b, unsigned field, float value) {
    static_assert(sizeof(float) == sizeof(uint32_t), "Offworld requires a 32-bit float");
    uint32_t bits; memcpy(&bits, &value, sizeof(bits));
    varint(b, (field << 3) | 5);
    for (unsigned shift = 0; shift < 32; shift += 8) b.push_back(uint8_t(bits >> shift));
}
static void nested(Bytes& b, unsigned field, const Bytes& value) {
    varint(b, (field << 3) | 2); varint(b, value.size()); b.insert(b.end(), value.begin(), value.end());
}
struct Field { unsigned number; uint64_t scalar; Bytes data; };
static uint64_t parseVarint(const Bytes& b, size_t& offset) {
    uint64_t result = 0;
    for (unsigned shift = 0; shift < 64 && offset < b.size(); shift += 7) {
        uint8_t byte = b[offset++]; result |= uint64_t(byte & 127) << shift;
        if (!(byte & 128)) return result;
    }
    errno = EPROTO; fail("malformed protobuf varint");
}
static std::vector<Field> fields(const Bytes& b) {
    std::vector<Field> result;
    size_t at = 0;
    while (at < b.size()) {
        uint64_t tag = parseVarint(b, at); Field f{unsigned(tag >> 3), 0, {}};
        unsigned kind = tag & 7;
        if (!f.number) { errno = EPROTO; fail("zero protobuf tag"); }
        if (kind == 0) f.scalar = parseVarint(b, at);
        else if (kind == 2) {
            uint64_t n = parseVarint(b, at);
            if (n > b.size() - at) { errno = EPROTO; fail("truncated protobuf field"); }
            f.data.assign(b.begin() + at, b.begin() + at + size_t(n)); at += size_t(n);
        } else if (kind == 5 || kind == 1) {
            size_t n = kind == 5 ? 4 : 8;
            if (n > b.size() - at) { errno = EPROTO; fail("truncated protobuf number"); }
            at += n;
        } else { errno = EPROTO; fail("unsupported protobuf wire type"); }
        result.push_back(std::move(f));
    }
    return result;
}
static uint64_t number(const Bytes& b, unsigned tag, uint64_t fallback = 0) {
    for (const auto& f : fields(b)) if (f.number == tag) return f.scalar;
    return fallback;
}
static std::vector<Field> checkedResponse(const Bytes& response) {
    auto values = fields(response);
    for (const auto& f : values) if (f.number == 2) {
        fprintf(stderr, "Emulator error: %.*s\n", int(f.data.size()), reinterpret_cast<const char*>(f.data.data()));
    }
    if (number(response, 1, 0) != 0) { errno = EPROTO; fail("emulator rejected video request"); }
    return values;
}
static void openPipe() {
    channel = socket(AF_VSOCK, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (channel < 0) fail("socket AF_VSOCK");
    sockaddr_vm address{}; address.svm_family = AF_VSOCK; address.svm_cid = VMADDR_CID_HOST; address.svm_port = 5000;
    if (connect(channel, reinterpret_cast<sockaddr*>(&address), sizeof(address)) < 0) {
        if (errno != EINPROGRESS) fail("connect emulator vsock 2:5000");
        const int64_t deadline = millis() + 15000;
        for (;;) {
            int remaining = int(deadline - millis());
            if (remaining <= 0) { errno = ETIMEDOUT; fail("connect emulator vsock timeout"); }
            pollfd p{channel, POLLOUT, 0};
            int ready = poll(&p, 1, remaining);
            if (ready < 0 && errno == EINTR) continue;
            if (ready <= 0) { errno = ready ? errno : ETIMEDOUT; fail("connect emulator vsock poll"); }
            int error = 0; socklen_t length = sizeof(error);
            if (getsockopt(channel, SOL_SOCKET, SO_ERROR, &error, &length) < 0) fail("connect emulator vsock status");
            if (error) { errno = error; fail("connect emulator vsock 2:5000"); }
            break;
        }
    }
    char name[] = "pipe:OffworldPipe"; transfer(name, sizeof(name), true);
    Bytes hello; scalar(hello, 1, 1); send(hello);
    Bytes response = receive();
    if (number(response, 1, UINT64_MAX) != 0) { errno = EPROTO; fail("Offworld protocol-1 handshake"); }
    printf("{\"offworldProtocol\":1,\"connected\":true}\n"); fflush(stdout);
}
static void request(unsigned sequence, unsigned operation, const Bytes& argument, bool completionRequired) {
    Bytes injection, envelope;
    scalar(injection, 1, sequence); nested(injection, operation, argument); nested(envelope, 3, injection); send(envelope);
    uint64_t asyncId = 0;
    const int64_t deadline = millis() + 15000;
    for (;;) {
        const int remaining = int(deadline - millis());
        if (remaining <= 0) { errno = ETIMEDOUT; fail("video request acknowledgement timeout"); }
        Bytes response = receive(remaining); auto values = checkedResponse(response);
        for (const auto& f : values) {
            if (f.number == 3) asyncId = f.scalar;
            if (f.number == 201 && number(f.data, 1) == asyncId && asyncId) {
                bool complete = number(f.data, 2) != 0;
                if (!completionRequired || complete) {
                    printf("{\"sequence\":%u,\"operation\":%u,\"acknowledged\":true,\"completed\":%s}\n", sequence, operation, complete ? "true" : "false");
                    fflush(stdout); return;
                }
            }
        }
    }
}
static int integerArgument(const char* value, int minimum, int maximum, const char* name) {
    errno = 0; char* end = nullptr; long n = strtol(value, &end, 10);
    if (errno || !end || *end || end == value || n < minimum || n > maximum) {
        errno = EINVAL; fail(name);
    }
    return int(n);
}
static void waitForSeek(int64_t deadline) {
    // Consume asynchronous completion/errors while waiting, so the socket cannot
    // fill up during the experiment. Acknowledgements alone do not prove frames.
    for (;;) {
        const int remaining = int(deadline - millis());
        if (remaining <= 0) return;
        pollfd p{channel, POLLIN, 0};
        int ready = poll(&p, 1, remaining);
        if (ready < 0 && errno == EINTR) continue;
        if (ready < 0) fail("poll between seek requests");
        if (!ready) return;
        if (p.revents & (POLLERR | POLLHUP | POLLNVAL)) { errno = EPIPE; fail("Offworld disconnected between seek requests"); }
        if (p.revents & POLLIN) checkedResponse(receive());
    }
}
static void seekLoop(int hold, int period) {
    // Opt-in workaround experiment, not the normal looping path. A nonzero
    // offset makes this emulator's VideoPlayer::start call internalSeek().
    // android/android-emu/android/camera/camera-videoplayback-render-multiplexer.cpp
    // passes this float offset and looping flag to VideoPlayer::PlayConfig.
    Bytes play; float32(play, 1, 0.001f); scalar(play, 2, 1);
    const int64_t start = millis(), stop = start + int64_t(hold) * 1000;
    unsigned sequence = 2, seeks = 0;
    for (int64_t next = start; next < stop; next += period) {
        waitForSeek(next);
        if (millis() >= stop) break;
        request(sequence++, 51, play, false); ++seeks;
        // Avoid a burst of catch-up commands if an acknowledgement was delayed.
        if (next + period < millis()) next = millis();
    }
    waitForSeek(stop);
    printf("{\"experiment\":\"seekloop\",\"seekOffsetSeconds\":0.001,\"periodMilliseconds\":%d,\"holdSeconds\":%d,\"acknowledgedSeeks\":%u}\n", period, hold, seeks);
    fflush(stdout);
}
int main(int argc, char** argv) {
    if (argc < 2 || (strcmp(argv[1], "handshake") && strcmp(argv[1], "play") && strcmp(argv[1], "seekloop") && strcmp(argv[1], "default"))) {
        fprintf(stderr, "Usage: offworld-feed handshake | play /absolute/video.mp4 [holdSeconds] | seekloop /absolute/video.mp4 [holdSeconds=30] [periodMilliseconds=2000] | default\n"); return 2;
    }
    const bool seekExperiment = !strcmp(argv[1], "seekloop");
    openPipe();
    if (!strcmp(argv[1], "play") || seekExperiment) {
        if (argc < 3) { errno = EINVAL; fail("missing video path"); }
        FILE* input = fopen(argv[2], "rb"); if (!input) fail("open selected video");
        if (fseek(input, 0, SEEK_END)) fail("seek video"); long n = ftell(input);
        if (n <= 0 || size_t(n) > kLimit - 1024) { fclose(input); errno = EFBIG; fail("video size must be 1..64 MiB"); }
        rewind(input); Bytes media(size_t(n), 0);
        if (fread(media.data(), 1, media.size(), input) != media.size()) fail("read video"); fclose(input);
        Bytes load; nested(load, 1, media); request(1, 50, load, true);
        if (seekExperiment) {
            int hold = argc > 3 ? integerArgument(argv[3], 1, 3600, "invalid holdSeconds") : 30;
            int period = argc > 4 ? integerArgument(argv[4], 100, 3600000, "invalid periodMilliseconds") : 2000;
            seekLoop(hold, period);
        } else {
            Bytes play; scalar(play, 2, 1); request(2, 51, play, false);
            int hold = argc > 3 ? atoi(argv[3]) : 15;
            if (hold < 0 || hold > 3600) { errno = EINVAL; fail("invalid holdSeconds"); }
            sleep(unsigned(hold));
        }
    } else if (!strcmp(argv[1], "default")) request(1, 54, {}, true);
    close(channel); channel = -1; return 0;
}
