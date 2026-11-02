// Goldfish camera socket bridge for the explicitly fingerprinted VMOS Android 9
// guest. This is not a physical-device Camera HAL or a general Android backend.
// Every request is sent to the original VMOS camera service first. Only a
// successful, exactly sized frame response may have its pixel bytes replaced.
#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cmath>
#include <csignal>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <fstream>
#include <iostream>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <poll.h>
#include <sstream>
#include <stdexcept>
#include <string>
#include <sys/file.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <thread>
#include <unistd.h>
#include <vector>
#ifdef __ANDROID__
#include <sys/system_properties.h>
#endif

namespace vcam {
constexpr size_t kMaxReply = 32 * 1024 * 1024;
constexpr size_t kMaxSource = 256 * 1024 * 1024;
constexpr uint32_t fourcc(char a, char b, char c, char d) {
    return uint32_t(a) | uint32_t(b) << 8 | uint32_t(c) << 16 | uint32_t(d) << 24;
}
constexpr uint32_t kI420 = fourcc('Y', 'U', '1', '2');
constexpr uint32_t kYv12 = fourcc('Y', 'V', '1', '2');
constexpr uint32_t kNv21 = fourcc('N', 'V', '2', '1');
constexpr uint32_t kNv12 = fourcc('N', 'V', '1', '2');
constexpr uint32_t kRgb32 = fourcc('R', 'G', 'B', '4');
constexpr uint32_t kBgr32 = fourcc('B', 'G', 'R', '4');
std::atomic<bool> running{true};
std::atomic<int> clients{0};
std::atomic<uint64_t> totalFrames{0};
int listener = -1;
std::mutex logMutex;

int64_t nowNs() {
    timespec t{};
    if (clock_gettime(CLOCK_BOOTTIME, &t)) throw std::runtime_error("CLOCK_BOOTTIME failed");
    return int64_t(t.tv_sec) * 1000000000LL + t.tv_nsec;
}
void log(const std::string& message) {
    std::lock_guard<std::mutex> guard(logMutex);
    std::cerr << "vcam-proxy " << message << '\n';
}
struct Fd {
    int value = -1;
    explicit Fd(int n = -1) : value(n) {}
    ~Fd() { if (value >= 0) close(value); }
    Fd(const Fd&) = delete;
    Fd& operator=(const Fd&) = delete;
};
void check(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}
bool expectedVmosGuest() {
#if defined(__ANDROID__) && defined(__aarch64__)
    char sdk[PROP_VALUE_MAX]{}, hardware[PROP_VALUE_MAX]{};
    return __system_property_get("ro.build.version.sdk", sdk) > 0
        && __system_property_get("ro.hardware", hardware) > 0
        && !std::strcmp(sdk, "28") && !std::strcmp(hardware, "vphw90");
#else
    return false;
#endif
}
bool ownedObjectMatches(const struct stat& named, const struct stat& opened, uid_t owner, bool mappedUidAllowed) {
    // VMOS rewrites pathname ownership to guest UIDs while fstat exposes its
    // physical host app UID. Ownership comes from the non-following pathname
    // lookup; descriptor identity still has to match that very same object.
    return named.st_uid == owner && (opened.st_uid == owner || mappedUidAllowed)
        && named.st_dev == opened.st_dev && named.st_ino == opened.st_ino
        && named.st_mode == opened.st_mode && named.st_nlink == opened.st_nlink
        && named.st_size == opened.st_size;
}
struct OwnedObject {
    struct stat named{}, opened{};
};
OwnedObject ownedObject(const std::string& path, int fd) {
    OwnedObject result;
    check(fd >= 0 && !lstat(path.c_str(), &result.named) && !fstat(fd, &result.opened), "cannot inspect owned object and descriptor");
    if (!ownedObjectMatches(result.named, result.opened, geteuid(), expectedVmosGuest())) {
        throw std::runtime_error("owned pathname does not match opened descriptor: owner=" + std::to_string(geteuid())
            + " namedUid=" + std::to_string(result.named.st_uid) + " fdUid=" + std::to_string(result.opened.st_uid)
            + " namedInode=" + std::to_string(result.named.st_ino) + " fdInode=" + std::to_string(result.opened.st_ino)
            + " namedDevice=" + std::to_string(result.named.st_dev) + " fdDevice=" + std::to_string(result.opened.st_dev)
            + " namedMode=" + std::to_string(result.named.st_mode) + " fdMode=" + std::to_string(result.opened.st_mode)
            + " namedSize=" + std::to_string(result.named.st_size) + " fdSize=" + std::to_string(result.opened.st_size));
    }
    return result;
}
std::string boundedProcessFile(const std::string& path, size_t limit) {
    Fd fd(open(path.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK));
    check(fd.value >= 0, "cannot inspect mapped camera peer process");
    std::string result;
    std::array<char, 512> buffer{};
    while (result.size() < limit) {
        ssize_t count = read(fd.value, buffer.data(), std::min(buffer.size(), limit - result.size()));
        if (count < 0 && errno == EINTR) continue;
        check(count >= 0, "cannot read mapped camera peer process");
        if (!count) return result;
        result.append(buffer.data(), size_t(count));
    }
    throw std::runtime_error("mapped camera peer identity exceeds limit");
}
bool mappedPeerIdentity(const std::string& status, const std::string& command, const std::string& ownExecutable, uid_t owner) {
    std::istringstream lines(status);
    std::string line;
    while (std::getline(lines, line)) {
        if (line.rfind("Uid:", 0)) continue;
        std::istringstream values(line.substr(4));
        uint64_t real = UINT64_MAX, effective = UINT64_MAX, saved = UINT64_MAX, filesystem = UINT64_MAX;
        if (!(values >> real >> effective >> saved >> filesystem) || real != effective || saved != effective || filesystem != effective) return false;
        if (command == ownExecutable && effective == owner) return true;
        if (command == "/vendor/bin/hw/android.hardware.camera.provider@2.4-service")
            return effective == 0 || effective == 1000 || effective == 1006 || effective == 1047;
        if (command == "/system/bin/cameraserver") return effective == 0 || effective == 1000 || effective == 1047;
        return false;
    }
    return false;
}
bool cropSystemUid(uid_t uid) { return uid == 0 || uid == 1000 || uid == 1047; }
bool mappedCropPeerIdentity(const std::string& status, const std::string& command) {
    std::istringstream lines(status);
    std::string line;
    while (std::getline(lines, line)) {
        if (line.rfind("Uid:", 0)) continue;
        std::istringstream values(line.substr(4));
        uint64_t real = UINT64_MAX, effective = UINT64_MAX, saved = UINT64_MAX, filesystem = UINT64_MAX;
        if (!(values >> real >> effective >> saved >> filesystem)
            || real != effective || saved != effective || filesystem != effective || effective > UINT32_MAX) return false;
        return cropSystemUid(uid_t(effective)) &&
            (command == "/vendor/bin/hw/android.hardware.camera.provider@2.4-service" || command == "/system/bin/cameraserver");
    }
    return false;
}
bool waitReady(int fd, short events, int64_t deadline) {
    while (running) {
        int timeout = int(std::clamp<int64_t>((deadline - nowNs()) / 1000000, 0, 500));
        pollfd p{fd, events, 0};
        int r = poll(&p, 1, timeout);
        if (r > 0) return (p.revents & (events | POLLHUP | POLLERR | POLLNVAL)) != 0;
        if (r < 0 && errno != EINTR) return false;
        if (nowNs() >= deadline) return false;
    }
    return false;
}
void readExact(int fd, void* data, size_t length, int timeoutMs = 10000) {
    auto* p = static_cast<uint8_t*>(data);
    int64_t deadline = nowNs() + int64_t(timeoutMs) * 1000000;
    while (length) {
        check(waitReady(fd, POLLIN, deadline), "socket read timed out or disconnected");
        ssize_t n = recv(fd, p, length, MSG_DONTWAIT);
        if (n < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
        check(n > 0, "socket read disconnected");
        p += n;
        length -= size_t(n);
    }
}
void writeExact(int fd, const void* data, size_t length) {
    auto* p = static_cast<const uint8_t*>(data);
    int64_t deadline = nowNs() + 10000000000LL;
    while (length) {
        check(waitReady(fd, POLLOUT, deadline), "socket write timed out or disconnected");
        ssize_t n = send(fd, p, length, MSG_NOSIGNAL | MSG_DONTWAIT);
        if (n < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
        check(n > 0, "socket write disconnected");
        p += n;
        length -= size_t(n);
    }
}
std::string readCommand(int fd, size_t limit, bool idle = false) {
    // A single byte read avoids consuming bytes belonging to the next request.
    std::string value;
    while (running) {
        if (idle && value.empty()) {
            if (!waitReady(fd, POLLIN, nowNs() + 500000000)) continue;
        }
        char c;
        readExact(fd, &c, 1);
        if (!c) return value;
        check(value.size() < limit, "command exceeds protocol limit");
        value.push_back(c);
    }
    throw std::runtime_error("proxy stopping");
}
bool hexSize(const std::array<char, 8>& header, size_t& result, bool* vmosPadded = nullptr) {
    result = 0;
    if (vmosPadded) *vmosPadded = false;
    for (size_t index = 0; index < header.size(); ++index) {
        char c = header[index];
        // The measured VMOS host formats "%04zx" into a nine-byte stack
        // buffer, but sends eight bytes. Its guest HAL parses the hex prefix
        // using strtol, stopping at NUL. Only this four-or-more-digit,
        // NUL-terminated variant is accepted alongside standard eight hex
        // digits. Padding after that NUL is unused and remains unmodified.
        if (!c) {
            if (index < 4 || result < 3 || result > kMaxReply) return false;
            if (vmosPadded) *vmosPadded = true;
            return true;
        }
        unsigned d;
        if (c >= '0' && c <= '9') d = unsigned(c - '0');
        else if (c >= 'a' && c <= 'f') d = unsigned(c - 'a' + 10);
        else if (c >= 'A' && c <= 'F') d = unsigned(c - 'A' + 10);
        else return false;
        result = result * 16 + d;
    }
    return result >= 3 && result <= kMaxReply;
}
std::map<std::string, std::string> parameters(const std::string& text) {
    std::istringstream input(text);
    std::map<std::string, std::string> result;
    std::string token;
    while (input >> token) {
        auto separator = token.find('=');
        if (separator != std::string::npos) result.emplace(token.substr(0, separator), token.substr(separator + 1));
    }
    return result;
}
uint64_t number(const std::string& value, uint64_t maximum) {
    check(!value.empty() && value.size() <= 20, "invalid numeric value");
    uint64_t n = 0;
    for (char c : value) {
        check(c >= '0' && c <= '9', "invalid numeric value");
        unsigned digit = unsigned(c - '0');
        check(n <= maximum / 10 && (n != maximum / 10 || digit <= maximum % 10), "numeric value exceeds limit");
        n = n * 10 + digit;
    }
    return n;
}
bool safeId(const std::string& value) {
    return !value.empty() && value.size() <= 64 && std::all_of(value.begin(), value.end(), [](char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-';
    });
}
bool sha256(const std::string& value) {
    return value.size() == 64 && std::all_of(value.begin(), value.end(), [](char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    });
}
bool cameraNameValid(const std::string& value) {
    return !value.empty() && value.size() <= 128 && std::all_of(value.begin(), value.end(), [](char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
    });
}
std::string serviceCamera(const std::string& service) {
    constexpr char prefix[] = "pipe:qemud:camera:name=";
    if (service.rfind(prefix, 0)) return {};
    std::string name = service.substr(sizeof(prefix) - 1);
    return cameraNameValid(name) ? name : std::string{};
}
int cropCameraIndex(const std::string& name) {
    constexpr char prefix[] = "camera_";
    if (name.rfind(prefix, 0)) return -1;
    std::string suffix = name.substr(sizeof(prefix) - 1);
    if (suffix.empty() || suffix.size() > 2 || (suffix.size() > 1 && suffix[0] == '0')) return -1;
    unsigned index = 0;
    for (char value : suffix) {
        if (value < '0' || value > '9') return -1;
        index = index * 10 + unsigned(value - '0');
    }
    return index < 32 ? int(index) : -1;
}
struct Crop {
    unsigned left = 0, top = 0, width = 1, height = 1, activeWidth = 1, activeHeight = 1;
    uint64_t epoch = 0, serial = 0;
    bool full() const { return !left && !top && width == activeWidth && height == activeHeight; }
};
class Crops {
    std::array<Crop, 32> cameras_{};
    std::mutex mutex_;
public:
    Crop snapshot(const std::string& camera) {
        int index = cropCameraIndex(camera);
        if (index < 0) return {};
        std::lock_guard<std::mutex> guard(mutex_);
        return cameras_[size_t(index)];
    }
    void update(const std::string& camera, const std::string& command) {
        int index = cropCameraIndex(camera);
        check(index >= 0, "invalid crop camera identity");
        std::istringstream input(command);
        std::string token;
        check(bool(input >> token) && token == "crop", "invalid crop command");
        std::map<std::string, std::string> fields;
        while (input >> token) {
            size_t at = token.find('=');
            check(at != std::string::npos && at > 0 && fields.emplace(token.substr(0, at), token.substr(at + 1)).second, "invalid or duplicate crop field");
        }
        check(fields.size() == 8, "unexpected crop fields");
        Crop next;
        next.left = unsigned(number(fields.at("left"), 65536));
        next.top = unsigned(number(fields.at("top"), 65536));
        next.width = unsigned(number(fields.at("width"), 65536));
        next.height = unsigned(number(fields.at("height"), 65536));
        next.activeWidth = unsigned(number(fields.at("active_width"), 65536));
        next.activeHeight = unsigned(number(fields.at("active_height"), 65536));
        next.epoch = number(fields.at("epoch"), uint64_t(INT64_MAX));
        next.serial = number(fields.at("serial"), uint64_t(INT64_MAX));
        check(next.width && next.height && next.activeWidth && next.activeHeight && next.epoch && next.serial,
            "zero crop dimensions or ordering token");
        check(next.left < next.activeWidth && next.top < next.activeHeight
            && next.width <= next.activeWidth - next.left && next.height <= next.activeHeight - next.top, "crop exceeds active array");
        check(next.epoch <= uint64_t(nowNs()) + 1000000000ULL, "crop epoch is in the future");
        std::lock_guard<std::mutex> guard(mutex_);
        const auto& previous = cameras_[size_t(index)];
        check(next.epoch > previous.epoch || (next.epoch == previous.epoch && next.serial > previous.serial), "stale crop ordering token");
        cameras_[size_t(index)] = next;
    }
};
Crops crops;

struct Source {
    std::string id, path, format, hash, cameraName;
    unsigned width = 0, height = 0, fps = 0, frames = 0;
    bool loop = false;
    bool live = false;
    uint64_t sequence = 0;
    int64_t createdNs = 0;
    std::atomic<int64_t> expiresNs{0};
    std::shared_ptr<std::atomic<uint64_t>> deliveredFrames = std::make_shared<std::atomic<uint64_t>>(0);
    size_t frameSize = 0, mappedSize = 0, headerSize = 0;
    uint8_t* bytes = nullptr;
    ~Source() { if (bytes) munmap(bytes - headerSize, mappedSize); }
    Source() = default;
    Source(const Source&) = delete;
    Source& operator=(const Source&) = delete;
    unsigned frameAt(int64_t time) const {
        uint64_t frame = uint64_t(std::max<int64_t>(0, time - createdNs)) * fps / 1000000000;
        return loop ? unsigned(frame % frames) : unsigned(std::min<uint64_t>(frame, frames - 1));
    }
};

class Sources {
    std::string directory_;
    Fd directoryFd_;
    uid_t physicalUid_ = uid_t(-1);
    std::mutex mutex_;
    std::shared_ptr<Source> source_;
    std::string lastError_;
public:
    explicit Sources(std::string directory) : directory_(std::move(directory)), directoryFd_(open(directory_.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW)) {
        auto info = ownedObject(directory_, directoryFd_.value);
        check(S_ISDIR(info.named.st_mode) && !(info.named.st_mode & 0077), "cannot open owned private source directory");
        physicalUid_ = info.opened.st_uid;
    }
    const std::string& directory() const { return directory_; }
    uid_t physicalUid() const { return physicalUid_; }
    std::string executablePath() const { return directory_.substr(0, directory_.rfind('/')) + "/vmos-camera-proxy"; }
    std::shared_ptr<Source> current(int64_t time) {
        std::lock_guard<std::mutex> guard(mutex_);
        try {
            Fd config(openat(directoryFd_.value, "source.cfg", O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK));
            if (config.value < 0 && errno == ENOENT) { source_.reset(); return nullptr; }
            check(config.value >= 0, "cannot open source.cfg");
            auto object = ownedObject(directory_ + "/source.cfg", config.value);
            const auto& info = object.named;
            check(object.opened.st_uid == physicalUid_ && S_ISREG(info.st_mode) && !(info.st_mode & 0022) && info.st_nlink == 1 && info.st_size > 0 && info.st_size <= 4096, "invalid source.cfg file");
            std::string text(size_t(info.st_size), '\0');
            size_t offset = 0;
            while (offset < text.size()) {
                ssize_t n = read(config.value, text.data() + offset, text.size() - offset);
                if (n < 0 && errno == EINTR) continue;
                check(n > 0, "cannot read source.cfg");
                offset += size_t(n);
            }
            std::istringstream input(text);
            std::string line;
            check(bool(std::getline(input, line)) && (line == "VCAM1" || line == "VCAM2"), "unknown source.cfg protocol");
            bool live = line == "VCAM2";
            std::map<std::string, std::string> fields;
            while (std::getline(input, line)) {
                auto at = line.find('=');
                check(at != std::string::npos && at > 0 && fields.emplace(line.substr(0, at), line.substr(at + 1)).second, "invalid or duplicate source.cfg field");
            }
            std::string id = fields.at("id"), path = fields.at("path"), format = fields.at("format"), hash = fields.at("sha256"), cameraName = fields.at("camera_name");
            check(safeId(id) && sha256(hash), "invalid source identity");
            check(cameraNameValid(cameraName), "invalid target camera name");
            check(path.rfind(directory_ + "/", 0) == 0 && path.find("..") == std::string::npos && path.find('\0') == std::string::npos, "source path outside private directory");
            std::string filename = path.substr(directory_.size() + 1);
            check(!filename.empty() && filename.find('/') == std::string::npos, "raw source must be a direct child of private directory");
            check(format == "RGBA" || format == "I420", "unsupported source format");
            unsigned width = unsigned(number(fields.at("width"), 4096));
            unsigned height = unsigned(number(fields.at("height"), 4096));
            unsigned fps = unsigned(number(fields.at("fps"), 60));
            unsigned frames = unsigned(number(fields.at("frames"), 18000));
            uint64_t sequence = live ? number(fields.at("sequence"), uint64_t(INT64_MAX)) : 0;
            bool loop = number(fields.at("loop"), 1) != 0;
            int64_t expires = int64_t(number(fields.at("expires_ns"), uint64_t(INT64_MAX)));
            check(width && height && fps && frames && !(width & 1) && !(height & 1), "invalid source dimensions or playback settings");
            check(!live || (format == "RGBA" && frames == 1), "live source requires exactly one RGBA frame");
            check(expires > time && expires <= time + 30000000000LL, "source lease expired or exceeds 30 seconds");
            size_t frameSize = size_t(width) * height * (format == "RGBA" ? 4 : 3) / (format == "RGBA" ? 1 : 2);
            check(frameSize <= kMaxSource / frames, "source exceeds 256 MiB limit");
            if (source_ && source_->id == id) {
                check(source_->cameraName == cameraName && source_->width == width && source_->height == height && source_->fps == fps && source_->frames == frames && source_->loop == loop && source_->format == format && source_->hash == hash && source_->live == live, "source identity reused with changed media");
                check(live || source_->path == path, "source identity reused with changed file");
                check(!live || sequence >= source_->sequence, "live source sequence went backwards");
                if (!live || sequence == source_->sequence) {
                    check(source_->path == path, "source path changed without advancing sequence");
                    source_->expiresNs = expires;
                    lastError_.clear();
                    return source_;
                }
            }
            Fd file(openat(directoryFd_.value, filename.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW | O_NONBLOCK));
            check(file.value >= 0, "cannot open raw source");
            auto rawObject = ownedObject(path, file.value);
            const auto& raw = rawObject.named;
            size_t headerSize = live ? 16 : 0;
            check(rawObject.opened.st_uid == physicalUid_ && S_ISREG(raw.st_mode) && !(raw.st_mode & 0022) && raw.st_nlink == 1 && raw.st_size >= 0 && uint64_t(raw.st_size) == frameSize * frames + headerSize, "raw source size or ownership mismatch");
            auto fresh = std::make_shared<Source>();
            fresh->mappedSize = frameSize * frames + headerSize;
            void* mapping = mmap(nullptr, fresh->mappedSize, PROT_READ, MAP_PRIVATE, file.value, 0);
            check(mapping != MAP_FAILED, "cannot map raw source");
            fresh->headerSize = headerSize;
            fresh->bytes = static_cast<uint8_t*>(mapping) + headerSize;
            if (live) {
                const auto* header = static_cast<const uint8_t*>(mapping);
                check(!std::memcmp(header, "VCAMFR2\n", 8), "invalid live frame header");
                uint64_t frameSequence = 0;
                for (unsigned i = 8; i < 16; ++i) frameSequence = (frameSequence << 8) | header[i];
                check(frameSequence == sequence, "live frame sequence does not match source.cfg");
            }
            fresh->id = id; fresh->path = path; fresh->format = format; fresh->hash = hash; fresh->cameraName = cameraName;
            fresh->width = width; fresh->height = height; fresh->fps = fps; fresh->frames = frames;
            fresh->loop = loop; fresh->live = live; fresh->sequence = sequence; fresh->frameSize = frameSize; fresh->createdNs = time; fresh->expiresNs = expires;
            bool sameGeneration = source_ && source_->id == id;
            if (sameGeneration) {
                fresh->createdNs = source_->createdNs;
                fresh->deliveredFrames = source_->deliveredFrames;
            }
            source_ = fresh;
            lastError_.clear();
            if (!sameGeneration) log("source configured id=" + id + " format=" + format + " size=" + std::to_string(width) + "x" + std::to_string(height) + " frames=" + std::to_string(frames) + " live=" + (live ? "1" : "0"));
            return fresh;
        } catch (const std::exception& error) {
            source_.reset();
            if (lastError_ != error.what()) { lastError_ = error.what(); log("source inactive: " + lastError_); }
            return nullptr;
        }
    }
};

uint8_t clamp(int value) { return uint8_t(std::clamp(value, 0, 255)); }
struct Rgb { int r, g, b; };
unsigned cropCoordinate(unsigned position, unsigned outputExtent, unsigned sourceExtent, unsigned offset, unsigned extent, unsigned activeExtent) {
    uint64_t numerator = (uint64_t(offset) * outputExtent + uint64_t(position) * extent) * sourceExtent;
    return unsigned(std::min<uint64_t>(sourceExtent - 1, numerator / (uint64_t(activeExtent) * outputExtent)));
}
Crop streamCrop(Crop crop, unsigned width, unsigned height) {
    // Android SCALER_CROP_REGION is a sensor-space rectangle. Each stream
    // takes the largest centered rectangle of its own aspect ratio within it;
    // independently stretching the requested axes distorts nonmatching crops.
    // The default 1x1 marker means no sensor metadata has arrived yet and keeps
    // the previous pass-through source scaling until the adapter publishes it.
    if (crop.activeWidth == 1 && crop.activeHeight == 1) return crop;
    if (uint64_t(crop.width) * height > uint64_t(crop.height) * width) {
        const unsigned extent = unsigned(std::max<uint64_t>(1, uint64_t(crop.height) * width / height));
        crop.left += (crop.width - extent) / 2;
        crop.width = extent;
    } else if (uint64_t(crop.width) * height < uint64_t(crop.height) * width) {
        const unsigned extent = unsigned(std::max<uint64_t>(1, uint64_t(crop.width) * height / width));
        crop.top += (crop.height - extent) / 2;
        crop.height = extent;
    }
    return crop;
}
Rgb pixel(const Source& source, unsigned frame, unsigned x, unsigned y, unsigned outWidth, unsigned outHeight, const Crop& crop = {}) {
    unsigned sx = cropCoordinate(x, outWidth, source.width, crop.left, crop.width, crop.activeWidth);
    unsigned sy = cropCoordinate(y, outHeight, source.height, crop.top, crop.height, crop.activeHeight);
    const uint8_t* p = source.bytes + size_t(frame) * source.frameSize;
    if (source.format == "RGBA") {
        p += (size_t(sy) * source.width + sx) * 4;
        return {p[0], p[1], p[2]};
    }
    size_t pixels = size_t(source.width) * source.height;
    int yy = int(p[size_t(sy) * source.width + sx]) - 16;
    int u = int(p[pixels + size_t(sy / 2) * (source.width / 2) + sx / 2]) - 128;
    int v = int(p[pixels + pixels / 4 + size_t(sy / 2) * (source.width / 2) + sx / 2]) - 128;
    return {clamp((298 * yy + 409 * v + 128) >> 8), clamp((298 * yy - 100 * u - 208 * v + 128) >> 8), clamp((298 * yy + 516 * u + 128) >> 8)};
}
size_t videoSize(unsigned width, unsigned height, uint32_t format) {
    if (format == kRgb32 || format == kBgr32) return size_t(width) * height * 4;
    if (format == kI420 || format == kYv12 || format == kNv12 || format == kNv21) return size_t(width) * height * 3 / 2;
    return 0;
}
void renderRgb(const Source& source, unsigned frame, unsigned width, unsigned height, bool bgr, uint8_t* destination, const Crop& crop = {}) {
    const Crop view = streamCrop(crop, width, height);
    for (unsigned y = 0; y < height; ++y) for (unsigned x = 0; x < width; ++x) {
        Rgb p = pixel(source, frame, x, y, width, height, view);
        *destination++ = uint8_t(bgr ? p.b : p.r);
        *destination++ = uint8_t(p.g);
        *destination++ = uint8_t(bgr ? p.r : p.b);
        *destination++ = 255;
    }
}
void renderYuv(const Source& source, unsigned frame, unsigned width, unsigned height, uint32_t format, uint8_t* destination, const Crop& crop = {}) {
    const Crop view = streamCrop(crop, width, height);
    size_t pixels = size_t(width) * height;
    // The exact I420 case needs no RGB round trip, preserving decoded video.
    if (source.format == "I420") {
        const uint8_t* input = source.bytes + size_t(frame) * source.frameSize;
        size_t sourcePixels = size_t(source.width) * source.height;
        const uint8_t* u = input + sourcePixels;
        const uint8_t* v = u + sourcePixels / 4;
        if (source.width == width && source.height == height && view.full()) std::memcpy(destination, input, pixels);
        else for (unsigned y = 0; y < height; ++y) for (unsigned x = 0; x < width; ++x) {
            unsigned sx = cropCoordinate(x, width, source.width, view.left, view.width, view.activeWidth);
            unsigned sy = cropCoordinate(y, height, source.height, view.top, view.height, view.activeHeight);
            destination[size_t(y) * width + x] = input[size_t(sy) * source.width + sx];
        }
        for (unsigned y = 0; y < height; y += 2) for (unsigned x = 0; x < width; x += 2) {
            unsigned sx = cropCoordinate(x, width, source.width, view.left, view.width, view.activeWidth);
            unsigned sy = cropCoordinate(y, height, source.height, view.top, view.height, view.activeHeight);
            size_t at = size_t(y / 2) * (width / 2) + x / 2;
            size_t sourceAt = size_t(sy / 2) * (source.width / 2) + sx / 2;
            if (format == kI420 || format == kYv12) {
                destination[pixels + at] = format == kI420 ? u[sourceAt] : v[sourceAt];
                destination[pixels + pixels / 4 + at] = format == kI420 ? v[sourceAt] : u[sourceAt];
            } else {
                destination[pixels + at * 2] = format == kNv21 ? v[sourceAt] : u[sourceAt];
                destination[pixels + at * 2 + 1] = format == kNv21 ? u[sourceAt] : v[sourceAt];
            }
        }
        return;
    }
    for (unsigned y = 0; y < height; y += 2) for (unsigned x = 0; x < width; x += 2) {
        int sumU = 0, sumV = 0;
        for (unsigned dy = 0; dy < 2; ++dy) for (unsigned dx = 0; dx < 2; ++dx) {
            Rgb p = pixel(source, frame, x + dx, y + dy, width, height, view);
            destination[size_t(y + dy) * width + x + dx] = clamp(((66 * p.r + 129 * p.g + 25 * p.b + 128) >> 8) + 16);
            sumU += ((-38 * p.r - 74 * p.g + 112 * p.b + 128) >> 8) + 128;
            sumV += ((112 * p.r - 94 * p.g - 18 * p.b + 128) >> 8) + 128;
        }
        uint8_t u = clamp((sumU + 2) / 4), v = clamp((sumV + 2) / 4);
        size_t at = size_t(y / 2) * (width / 2) + x / 2;
        if (format == kI420 || format == kYv12) {
            destination[pixels + at] = format == kI420 ? u : v;
            destination[pixels + pixels / 4 + at] = format == kI420 ? v : u;
        } else {
            destination[pixels + at * 2] = format == kNv21 ? v : u;
            destination[pixels + at * 2 + 1] = format == kNv21 ? u : v;
        }
    }
}

class Status {
    std::string directory_;
    Fd directoryFd_;
    std::mutex mutex_;
    int64_t lastWrite_ = 0;
public:
    explicit Status(std::string directory) : directory_(std::move(directory)), directoryFd_(open(directory_.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW)) {
        auto info = ownedObject(directory_, directoryFd_.value);
        check(S_ISDIR(info.named.st_mode) && !(info.named.st_mode & 0077), "cannot open owned private status directory");
    }
    void write(const std::shared_ptr<Source>& source, unsigned frame, unsigned width, unsigned height, uint32_t format, bool force = false) {
        std::lock_guard<std::mutex> guard(mutex_);
        int64_t time = nowNs();
        if (!force && time - lastWrite_ < 200000000) return;
        lastWrite_ = time;
        // Remove a stale temporary entry before exclusive creation. This also
        // avoids truncating a same-UID hard link accidentally left at this name.
        if (unlinkat(directoryFd_.value, "status.tmp", 0) && errno != ENOENT) return;
        Fd fd(openat(directoryFd_.value, "status.tmp", O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600));
        if (fd.value < 0) return;
        std::ostringstream out;
        out << "{\"protocol\":2,\"pid\":" << getpid() << ",\"configured\":" << (source ? "true" : "false")
            << ",\"overriddenFrames\":" << totalFrames.load() << ",\"lastFrameBootNs\":" << (source ? time : 0)
            << ",\"expiresBootNs\":" << (source ? source->expiresNs.load() : 0)
            << ",\"sourceId\":\"" << (source ? source->id : "") << "\",\"sourceSha256\":\"" << (source ? source->hash : "")
            << "\",\"sourceFrames\":" << (source ? source->deliveredFrames->load() : 0)
            << ",\"sequence\":" << (source ? source->sequence : 0)
            << ",\"live\":" << (source && source->live ? "true" : "false")
            << ",\"cameraName\":\"" << (source ? source->cameraName : "") << "\",\"sourceFrame\":" << frame << ",\"width\":" << width << ",\"height\":" << height
            << ",\"pixelFormat\":" << format << "}\n";
        std::string text = out.str();
        const char* p = text.data(); size_t remaining = text.size();
        while (remaining) {
            ssize_t n = ::write(fd.value, p, remaining);
            if (n < 0 && errno == EINTR) continue;
            if (n <= 0) return;
            p += n; remaining -= size_t(n);
        }
        if (renameat(directoryFd_.value, "status.tmp", directoryFd_.value, "status.json")) log("cannot replace status.json");
    }
};

struct Stream {
    std::string cameraName;
    unsigned width = 0, height = 0;
    uint32_t format = 0;
    bool started = false;
    void command(const std::string& command, const std::vector<uint8_t>& reply) {
        if (reply.size() < 3 || reply[0] != 'o' || reply[1] != 'k') return;
        if (command == "stop" || command == "disconnect") { started = false; return; }
        if (command.rfind("start ", 0) != 0) return;
        try {
            auto p = parameters(command);
            std::string dimensions = p.at("dim");
            auto at = dimensions.find('x');
            check(at != std::string::npos, "invalid start dimensions");
            unsigned w = unsigned(number(dimensions.substr(0, at), 4096));
            unsigned h = unsigned(number(dimensions.substr(at + 1), 4096));
            uint32_t f = uint32_t(number(p.at("pix"), UINT32_MAX));
            check(w && h && !(w & 1) && !(h & 1) && videoSize(w, h, f), "unsupported camera dimensions or pixel format");
            width = w; height = h; format = f; started = true;
            log("stream start " + std::to_string(w) + "x" + std::to_string(h) + " pix=" + std::to_string(f));
        } catch (const std::exception& error) { started = false; log(std::string("transparent stream: ") + error.what() + " command=" + command); }
    }
    bool overrideFrame(const std::string& command, std::vector<uint8_t>& reply, const std::shared_ptr<Source>& source, int64_t time, unsigned& sourceFrame, bool vmosPadded = false, const Crop& crop = {}) const {
        if (!started || !source || cameraName.empty() || cameraName != source->cameraName || time >= source->expiresNs || command.rfind("frame ", 0) != 0 || reply.size() < 3 || std::memcmp(reply.data(), "ok:", 3)) return false;
        try {
            auto p = parameters(command);
            size_t video = size_t(number(p.at("video"), kMaxReply));
            size_t preview = size_t(number(p.at("preview"), kMaxReply));
            bool timestamp = p.count("time") && number(p.at("time"), 1) != 0;
            check(video || preview, "frame has no pixel payload");
            // VMOS's queryFrameWapper puts NV21 in the slot named "preview".
            // Standard Goldfish uses RGBA there. Distinguish by exact length;
            // never assume a slot name establishes its actual pixel format.
            bool previewNv21 = preview == size_t(width) * height * 3 / 2;
            check((!video || video == videoSize(width, height, format)) && (!preview || previewNv21 || preview == size_t(width) * height * 4), "frame dimensions do not match request sizes");
            if (vmosPadded) {
                // This exact VMOS host returns one pixel slot plus a final
                // NUL, even when time=1. Its queryFrameWapper sets the output
                // timestamp to zero and lets the sensor thread assign capture
                // timing. Do not insert a timestamp or reinterpret pixel bytes.
                check((!video || !preview) && reply.size() == 4 + video + preview && reply.back() == 0, "VMOS frame response length or NUL trailer does not match request");
            } else {
                check(reply.size() == 3 + video + preview + (timestamp ? 8 : 0), "frame response length does not match request");
            }
            sourceFrame = source->frameAt(time);
            uint8_t* pixels = reply.data() + 3;
            if (video) {
                if (format == kRgb32 || format == kBgr32) renderRgb(*source, sourceFrame, width, height, format == kBgr32, pixels, crop);
                else renderYuv(*source, sourceFrame, width, height, format, pixels, crop);
            }
            if (preview) {
                if (previewNv21) renderYuv(*source, sourceFrame, width, height, kNv21, pixels + video, crop);
                else renderRgb(*source, sourceFrame, width, height, false, pixels + video, crop);
            }
            // Standard timestamps and the VMOS NUL trailer remain unchanged.
            return true;
        } catch (const std::exception& error) { log(std::string("frame kept original: ") + error.what()); return false; }
    }
};

int connectUnix(const std::string& path) {
    check(path.size() < sizeof(sockaddr_un::sun_path), "original socket path is too long");
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    check(fd >= 0, "cannot create original socket");
    sockaddr_un address{}; address.sun_family = AF_UNIX;
    std::memcpy(address.sun_path, path.c_str(), path.size() + 1);
    if (connect(fd, reinterpret_cast<sockaddr*>(&address), sizeof(address))) {
        if (errno != EINPROGRESS && errno != EAGAIN) { close(fd); throw std::runtime_error("cannot connect original VMOS camera service"); }
        int error = 0; socklen_t length = sizeof(error);
        if (!waitReady(fd, POLLOUT, nowNs() + 10000000000LL) || getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &length) || error) {
            close(fd); throw std::runtime_error("original VMOS camera connection failed or timed out");
        }
    }
    return fd;
}
int listCameras(const std::string& original) {
    std::string stage = "connect";
    try {
    log("factory probe stage=connect socket=" + original);
    Fd host(connectUnix(original));
    stage = "write-service";
    constexpr char service[] = "pipe:qemud:camera";
    log("factory probe stage=write-service bytes=" + std::to_string(sizeof(service)));
    writeExact(host.value, service, sizeof(service));
    stage = "read-handshake";
    log("factory probe stage=read-handshake expected=2");
    char acknowledgement[2]; readExact(host.value, acknowledgement, sizeof(acknowledgement));
    log("factory probe handshake bytes=" + std::to_string(static_cast<unsigned char>(acknowledgement[0])) + "," + std::to_string(static_cast<unsigned char>(acknowledgement[1])));
    check(!std::memcmp(acknowledgement, "OK", 2), "original camera factory handshake rejected");
    stage = "write-list";
    constexpr char command[] = "list";
    log("factory probe stage=write-list bytes=" + std::to_string(sizeof(command)));
    writeExact(host.value, command, sizeof(command));
    stage = "read-list-length";
    log("factory probe stage=read-list-length expected=8");
    std::array<char, 8> header{}; readExact(host.value, header.data(), header.size());
    size_t length = 0; check(hexSize(header, length) && length <= 64 * 1024, "invalid camera list response length");
    stage = "read-list-payload";
    log("factory probe stage=read-list-payload expected=" + std::to_string(length));
    std::vector<char> response(length); readExact(host.value, response.data(), response.size());
    check(response[0] == 'o' && response[1] == 'k', "original camera factory rejected list");
    if (response[2] == ':') {
        size_t textLength = response.size() - 3;
        if (textLength && response.back() == 0) --textLength;
        std::cout.write(response.data() + 3, std::streamsize(textLength));
    }
    else check(response[2] == '\0' && response.size() == 3, "unexpected camera list prefix");
    std::cout << '\n';
    log("factory probe stage=complete payloadBytes=" + std::to_string(length));
    return 0;
    } catch (const std::exception& error) {
        throw std::runtime_error("factory probe stage=" + stage + ": " + error.what());
    }
}
void serve(int accepted, const std::string& original, Sources& sources, Status& status) {
    Fd client(accepted);
    struct ClientCount { ~ClientCount() { --clients; } } count;
    try {
#ifdef __ANDROID__
        ucred peer{}; socklen_t size = sizeof(peer);
        check(!getsockopt(client.value, SOL_SOCKET, SO_PEERCRED, &peer, &size), "cannot identify camera client");
        if (!(peer.uid == 0 || peer.uid == 1000 || peer.uid == 1047 || peer.uid == 1006 || peer.uid == geteuid())) {
            bool mapped = false;
            if (expectedVmosGuest() && peer.pid > 0 && peer.uid == sources.physicalUid()) {
                std::string proc = "/proc/" + std::to_string(peer.pid);
                std::string command = boundedProcessFile(proc + "/cmdline", 4096);
                auto terminator = command.find('\0');
                if (terminator != std::string::npos) command.resize(terminator);
                mapped = mappedPeerIdentity(boundedProcessFile(proc + "/status", 16384), command, sources.executablePath(), geteuid());
            }
            if (!mapped) throw std::runtime_error("client UID " + std::to_string(peer.uid) + " is not a verified camera system process or proxy owner " + std::to_string(geteuid()));
        }
#endif
        std::string service = readCommand(client.value, 256);
        if (service.rfind("vcam:", 0) == 0) {
            constexpr char cropPrefix[] = "vcam:crop:";
            check(service.rfind(cropPrefix, 0) == 0, "unknown private camera control service");
            std::string name = service.substr(sizeof(cropPrefix) - 1);
            check(cropCameraIndex(name) >= 0, "invalid private crop camera");
            ucred controlPeer{}; socklen_t controlSize = sizeof(controlPeer);
            check(!getsockopt(client.value, SOL_SOCKET, SO_PEERCRED, &controlPeer, &controlSize), "cannot identify crop client");
            bool permitted = cropSystemUid(controlPeer.uid);
#ifdef __ANDROID__
            if (!permitted && expectedVmosGuest() && controlPeer.pid > 0 && controlPeer.uid == sources.physicalUid()) {
                std::string proc = "/proc/" + std::to_string(controlPeer.pid);
                std::string command = boundedProcessFile(proc + "/cmdline", 4096);
                auto terminator = command.find('\0');
                if (terminator != std::string::npos) command.resize(terminator);
                permitted = mappedCropPeerIdentity(boundedProcessFile(proc + "/status", 16384), command);
            }
#endif
            check(permitted, "crop client is not a verified camera system process");
            crops.update(name, readCommand(client.value, 512));
            writeExact(client.value, "OK", 2);
            return;
        }
        check(service == "pipe:qemud:camera" || service.rfind("pipe:qemud:camera:", 0) == 0, "not a goldfish camera service");
        Fd host(connectUnix(original));
        writeExact(host.value, service.c_str(), service.size() + 1);
        char acknowledgement[2]; readExact(host.value, acknowledgement, sizeof(acknowledgement));
        writeExact(client.value, acknowledgement, sizeof(acknowledgement));
        check(!std::memcmp(acknowledgement, "OK", 2), "original camera handshake rejected");
        Stream stream;
        stream.cameraName = serviceCamera(service);
        log("connected service=" + service);
        while (running) {
            std::string command = readCommand(client.value, 4096, true);
            writeExact(host.value, command.c_str(), command.size() + 1);
            std::array<char, 8> header{}; readExact(host.value, header.data(), header.size());
            size_t length = 0; bool vmosPadded = false;
            check(hexSize(header, length, &vmosPadded), "invalid original camera response length");
            std::vector<uint8_t> response(length); readExact(host.value, response.data(), response.size());
            stream.command(command, response);
            int64_t time = nowNs();
            auto source = sources.current(time);
            unsigned frame = 0;
            const Crop crop = crops.snapshot(stream.cameraName);
            bool replaced = stream.overrideFrame(command, response, source, time, frame, vmosPadded, crop);
            writeExact(client.value, header.data(), header.size());
            writeExact(client.value, response.data(), response.size());
            if (replaced) { ++totalFrames; ++*source->deliveredFrames; status.write(source, frame, stream.width, stream.height, stream.format); }
            else if (!source) status.write(nullptr, 0, 0, 0, 0);
        }
    } catch (const std::exception& error) { log(std::string("connection ended: ") + error.what()); }
}
void stop(int) { running = false; if (listener >= 0) close(listener); listener = -1; }

int selftest() {
    struct stat guest{}, physical{};
    guest.st_uid = 10051; guest.st_dev = 3; guest.st_ino = 17;
    guest.st_mode = S_IFREG | 0600; guest.st_nlink = 1; guest.st_size = 16;
    physical = guest; physical.st_uid = 10632;
    check(!ownedObjectMatches(guest, physical, 10051, false) && ownedObjectMatches(guest, physical, 10051, true), "mapped UID ownership validation failed");
    auto changed = physical; ++changed.st_ino;
    check(!ownedObjectMatches(guest, changed, 10051, true), "different descriptor inode accepted");
    changed = physical; ++changed.st_dev;
    check(!ownedObjectMatches(guest, changed, 10051, true), "different descriptor device accepted");
    changed = physical; ++changed.st_size;
    check(!ownedObjectMatches(guest, changed, 10051, true), "different descriptor size accepted");
    changed = physical; changed.st_mode |= 0022;
    check(!ownedObjectMatches(guest, changed, 10051, true), "different descriptor permissions accepted");
    changed = physical; ++changed.st_nlink;
    check(!ownedObjectMatches(guest, changed, 10051, true), "different descriptor link count accepted");
    changed = guest; changed.st_uid = 10052;
    check(!ownedObjectMatches(changed, physical, 10051, true), "foreign pathname owner accepted");
    const std::string ownExecutable = "/data/user/0/com.virtualcamerapro.broker/files/vmos-camera-proxy";
    const std::string provider = "/vendor/bin/hw/android.hardware.camera.provider@2.4-service";
    check(mappedPeerIdentity("Name:\tproxy\nUid:\t10051\t10051\t10051\t10051\n", ownExecutable, ownExecutable, 10051), "mapped owning proxy rejected");
    check(mappedPeerIdentity("Uid:\t1047\t1047\t1047\t1047\n", provider, ownExecutable, 10051), "mapped camera provider rejected");
    check(mappedPeerIdentity("Uid:\t1000\t1000\t1000\t1000\n", "/system/bin/cameraserver", ownExecutable, 10051), "mapped cameraserver rejected");
    check(!mappedPeerIdentity("Uid:\t10632\t10632\t10632\t10632\n", provider, ownExecutable, 10051), "unmapped physical host UID accepted");
    check(!mappedPeerIdentity("Uid:\t10051\t10051\t10051\t10051\n", provider, ownExecutable, 10051), "ordinary app impersonating provider accepted");
    check(!mappedPeerIdentity("Uid:\t10051\t0\t0\t0\n", provider, ownExecutable, 10051), "inconsistent mapped process credentials accepted");
    check(!mappedPeerIdentity("Uid:\t1047\t1047\t1047\t1047\n", provider + "-untrusted", ownExecutable, 10051), "nonexact camera provider command accepted");
    check(!mappedPeerIdentity("Uid:\t0\t0\t0\t0\n", "/system/bin/sh", ownExecutable, 10051), "unknown mapped root command accepted");
    check(!mappedPeerIdentity("Name:\tproxy\n", ownExecutable, ownExecutable, 10051), "process without mapped UID accepted");
    check(cropSystemUid(0) && cropSystemUid(1000) && cropSystemUid(1047) && !cropSystemUid(10051) && !cropSystemUid(10632), "crop system UID allowlist failed");
    check(mappedCropPeerIdentity("Uid:\t1047\t1047\t1047\t1047\n", provider), "mapped crop provider rejected");
    check(!mappedCropPeerIdentity("Uid:\t10051\t10051\t10051\t10051\n", provider), "ordinary mapped crop caller accepted");
    check(!mappedCropPeerIdentity("Uid:\t1047\t1047\t1047\t1047\n", ownExecutable), "unknown mapped crop executable accepted");
    check(!mappedCropPeerIdentity("Uid:\t1047\t0\t0\t0\n", provider), "inconsistent mapped crop credentials accepted");
    check(cropCameraIndex("camera_0") == 0 && cropCameraIndex("camera_31") == 31 && cropCameraIndex("camera_32") < 0 && cropCameraIndex("camera_00") < 0, "crop camera canonical bounds failed");
    Crop fullSensor{0,0,1920,1088,1920,1088};
    const Crop fullPreview = streamCrop(fullSensor,1920,1080);
    check(fullPreview.left == 0 && fullPreview.top == 4 && fullPreview.width == 1920 && fullPreview.height == 1080,
        "actual active-array preview aspect crop failed");
    const Crop centeredZoom = streamCrop({480,272,960,544,1920,1088},1280,720);
    check(centeredZoom.left == 480 && centeredZoom.top == 274 && centeredZoom.width == 960 && centeredZoom.height == 540,
        "centered zoom preview aspect crop failed");
    const Crop docExample = streamCrop({500,375,1000,750,2000,1500},1280,720);
    check(docExample.left == 500 && docExample.top == 469 && docExample.width == 1000 && docExample.height == 562,
        "standard nonmatching rectangle aspect example failed");
    std::cout << "ownership and mapped camera peer identity: PASS\n";
    check(safeId("abc-123") && !safeId("bad\"id") && sha256(std::string(64, 'a')) && !sha256(std::string(64, 'z')), "identity test failed");
    size_t size = 0;
    check(hexSize({'0','0','0','0','0','0','0','3'}, size) && size == 3, "reply header test failed");
    check(!hexSize({'0','0','0','0','0','0','0','z'}, size), "invalid header accepted");
    Source source;
    source.width = 2; source.height = 2; source.format = "RGBA"; source.fps = 30; source.frames = 2; source.frameSize = 16; source.cameraName = "camera_0";
    source.createdNs = 1000000000; source.expiresNs = 3000000000;
    source.mappedSize = 32;
    source.bytes = static_cast<uint8_t*>(mmap(nullptr, source.mappedSize, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
    check(source.bytes != MAP_FAILED, "selftest mmap failed");
    for (unsigned i = 0; i < 8; ++i) { source.bytes[i * 4] = i < 4 ? 255 : 0; source.bytes[i * 4 + 1] = 0; source.bytes[i * 4 + 2] = i < 4 ? 0 : 255; source.bytes[i * 4 + 3] = 255; }
    std::array<uint8_t, 6> yuv{};
    renderYuv(source, 0, 2, 2, kNv21, yuv.data());
    check(yuv[0] == 82 && yuv[4] == 240 && yuv[5] == 90, "red NV21 conversion failed");
    renderYuv(source, 0, 2, 2, kI420, yuv.data());
    check(yuv[4] == 90 && yuv[5] == 240, "red I420 conversion failed");
    source.loop = false;
    check(source.frameAt(1000000000) == 0 && source.frameAt(2000000000) == 1, "finite playback does not hold last frame");
    source.loop = true;
    check(source.frameAt(1100000000) == 1 && source.frameAt(1200000000) == 0, "loop playback frame calculation failed");
    Stream stream;
    stream.cameraName = "camera_0";
    stream.command("start dim=2x2 pix=" + std::to_string(kNv21), {'o','k',0});
    std::vector<uint8_t> reply(3 + 6 + 16 + 8, 123); reply[0] = 'o'; reply[1] = 'k'; reply[2] = ':';
    auto shared = std::shared_ptr<Source>(&source, [](Source*) {});
    unsigned frame = 99;
    check(stream.overrideFrame("frame video=6 preview=16 time=1", reply, shared, 1000000000, frame), "frame was not replaced");
    check(reply[3] == 82 && reply[9] == 255 && reply[11] == 0, "frame payload was not rendered");
    check(std::all_of(reply.end() - 8, reply.end(), [](uint8_t n) { return n == 123; }), "sensor timestamp changed");
    auto original = reply;
    check(!stream.overrideFrame("frame video=7 preview=16 time=1", reply, shared, 1000000000, frame) && reply == original, "invalid response was modified");
    check(!stream.overrideFrame("frame video=6 preview=16 time=1", reply, shared, 3000000001, frame) && reply == original, "expired source was used");
    reply.assign(3 + 6 + 8, 123); reply[0] = 'o'; reply[1] = 'k'; reply[2] = ':';
    check(stream.overrideFrame("frame video=0 preview=6 time=1", reply, shared, 1000000000, frame), "VMOS NV21 preview wrapper was not replaced");
    check(reply[3] == 82 && reply[7] == 240 && reply[8] == 90 && reply.back() == 123, "VMOS NV21 preview wrapper bytes are incorrect");
    std::cout << "vmos-camera-proxy selftest: PASS\n";
    return 0;
}
} // namespace vcam

int main(int argc, char** argv) {
    using namespace vcam;
    try {
        std::string socketPath = "/data/local/tmp/vcam_pipe", original = "/dev/socket/socket/qemu_pipe", directory = "/data/local/tmp/vcam-runtime";
        bool listOnly = false;
        for (int i = 1; i < argc; ++i) {
            std::string option = argv[i];
            if (option == "--selftest") return selftest();
            if (option == "--list-cameras") { listOnly = true; continue; }
            check(i + 1 < argc, "missing argument value");
            if (option == "--listen") socketPath = argv[++i];
            else if (option == "--original") original = argv[++i];
            else if (option == "--state-dir") directory = argv[++i];
            else throw std::runtime_error("unknown option");
        }
        if (listOnly) return listCameras(original);
        check(!directory.empty() && directory[0] == '/' && directory.find("..") == std::string::npos, "invalid private state directory");
        check(socketPath != original && socketPath.size() < sizeof(sockaddr_un::sun_path), "invalid proxy socket path");
        if (mkdir(directory.c_str(), 0700)) check(errno == EEXIST, "cannot create private state directory");
        struct stat directoryInfo{};
        check(!lstat(directory.c_str(), &directoryInfo) && S_ISDIR(directoryInfo.st_mode) && directoryInfo.st_uid == geteuid() && !(directoryInfo.st_mode & 0077), "state directory must be private and owned by current UID");
        Fd lock(open((directory + "/proxy.lock").c_str(), O_CREAT | O_RDWR | O_CLOEXEC | O_NOFOLLOW, 0600));
        check(lock.value >= 0 && !flock(lock.value, LOCK_EX | LOCK_NB), "another camera proxy owns this state directory");
        struct stat existing{};
        if (!lstat(socketPath.c_str(), &existing)) {
            check(S_ISSOCK(existing.st_mode) && existing.st_uid == geteuid(), "refusing to replace a non-owned socket path");
            Fd probe(socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0));
            check(probe.value >= 0, "cannot inspect existing proxy socket");
            sockaddr_un active{}; active.sun_family = AF_UNIX;
            std::memcpy(active.sun_path, socketPath.c_str(), socketPath.size() + 1);
            if (!connect(probe.value, reinterpret_cast<sockaddr*>(&active), sizeof(active)) || errno == EINPROGRESS || errno == EAGAIN) {
                throw std::runtime_error("another process is listening at proxy socket path");
            }
            check(errno == ECONNREFUSED || errno == ENOENT, "cannot establish that existing socket is stale");
            check(!unlink(socketPath.c_str()), "cannot remove stale proxy socket");
        } else check(errno == ENOENT, "cannot inspect proxy socket path");
        Fd server(socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0));
        check(server.value >= 0, "cannot create listening socket");
        sockaddr_un address{}; address.sun_family = AF_UNIX;
        std::memcpy(address.sun_path, socketPath.c_str(), socketPath.size() + 1);
        check(!bind(server.value, reinterpret_cast<sockaddr*>(&address), sizeof(address)), "cannot bind camera proxy socket");
        check(!chmod(socketPath.c_str(), 0666) && !listen(server.value, 16), "cannot expose camera proxy socket");
        listener = server.value;
        signal(SIGINT, stop); signal(SIGTERM, stop); signal(SIGPIPE, SIG_IGN);
        Sources sources(directory); Status status(directory);
        status.write(nullptr, 0, 0, 0, 0, true);
        log("ready pid=" + std::to_string(getpid()) + " listen=" + socketPath + " original=" + original);
        while (running) {
            int fd = accept4(server.value, nullptr, nullptr, SOCK_CLOEXEC);
            if (fd < 0) { if (!running) break; if (errno == EINTR) continue; throw std::runtime_error("accept failed"); }
            if (++clients > 32) { --clients; close(fd); log("client limit reached"); continue; }
            std::thread(serve, fd, std::cref(original), std::ref(sources), std::ref(status)).detach();
        }
        // Keep state objects alive until connections observe the stop signal.
        int64_t end = nowNs() + 12000000000LL;
        while (clients && nowNs() < end) std::this_thread::sleep_for(std::chrono::milliseconds(20));
        if (clients) {
            // A pathological slow operation must not access destroyed source
            // objects. Process teardown safely closes all remaining sockets.
            status.write(nullptr, 0, 0, 0, 0, true);
            unlink(socketPath.c_str());
            _exit(0);
        }
        status.write(nullptr, 0, 0, 0, 0, true);
        unlink(socketPath.c_str());
        server.value = -1; // closed by signal handler
        return 0;
    } catch (const std::exception& error) { std::cerr << "vcam-proxy fatal: " << error.what() << '\n'; return 1; }
}
