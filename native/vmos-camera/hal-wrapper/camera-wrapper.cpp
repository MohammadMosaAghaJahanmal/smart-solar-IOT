// SPDX-License-Identifier: Apache-2.0
// Exact Android 9 vphw90 camera3 ABI adapter. No system camera service hooks.
#include <hardware/camera3.h>
#include <system/camera_metadata.h>
#include <cerrno>
#include <climits>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <fcntl.h>
#include <mutex>
#include <new>
#include <poll.h>
#include <string>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>
#include <vector>
#ifdef __ANDROID__
#include <android/log.h>
#include <sys/system_properties.h>
#endif
#include "sha256.h"

extern "C" camera_module_t HAL_MODULE_INFO_SYM;

namespace vcam {
constexpr char kTransport[] = "/vendor/lib64/hw/.vcam-transport-camera.vphw90.so";
constexpr char kTransportSha[] = "02042c56980813c89611698d6501ccd78ca05c8a9827a55aa2c21e634bd5086a";
#if defined(VCAM_WRAPPER_TEST) || defined(VCAM_WRAPPER_LOADER_TEST)
constexpr char kSocket[] = "/tmp/vcam-hal-wrapper-test.sock";
#else
constexpr char kSocket[] = "/data/local/tmp/vcam_pipe";
#endif
using FindMetadata = int (*)(const camera_metadata_t*, uint32_t, camera_metadata_ro_entry_t*);
camera_module_t* realModule = nullptr;
FindMetadata findMetadata = nullptr;
std::once_flag loadOnce;

struct Crop {
    int32_t left, top, width, height;
    bool operator==(const Crop& b) const { return left == b.left && top == b.top && width == b.width && height == b.height; }
    bool operator!=(const Crop& b) const { return !(*this == b); }
};

int64_t timeNs(clockid_t clock) {
    timespec value{};
    if (clock_gettime(clock, &value)) return 0;
    return int64_t(value.tv_sec) * 1000000000LL + value.tv_nsec;
}

// Deadline covers connect, both NUL-terminated writes and the complete ACK.
bool waitFd(int fd, short events, int64_t deadline) {
    while (true) {
        const int64_t remaining = deadline - timeNs(CLOCK_MONOTONIC);
        if (remaining <= 0) return false;
        pollfd p{fd, events, 0};
        const int result = poll(&p, 1, static_cast<int>((remaining + 999999) / 1000000));
        if (result < 0 && errno == EINTR) continue;
        return result > 0 && (p.revents & events) != 0 && (p.revents & (POLLERR | POLLNVAL)) == 0;
    }
}

bool socketCrop(int camera, const Crop& crop, const Crop& active, int64_t epoch, int64_t serial) {
    char command[256];
    const int count = snprintf(command, sizeof(command),
        "crop left=%d top=%d width=%d height=%d active_width=%d active_height=%d epoch=%lld serial=%lld",
        crop.left, crop.top, crop.width, crop.height, active.width, active.height,
        static_cast<long long>(epoch), static_cast<long long>(serial));
    if (count <= 0 || size_t(count) >= sizeof(command)) return false;
    std::string message = "vcam:crop:camera_" + std::to_string(camera);
    message.push_back('\0'); message.append(command, size_t(count)); message.push_back('\0');
    const int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) return false;
    const int64_t deadline = timeNs(CLOCK_MONOTONIC) + 100000000;
    sockaddr_un address{}; address.sun_family = AF_UNIX;
    memcpy(address.sun_path, kSocket, sizeof(kSocket));
    bool ok = false;
    const int connected = connect(fd, reinterpret_cast<sockaddr*>(&address), sizeof(address));
    if (connected == 0 || (errno == EINPROGRESS && waitFd(fd, POLLOUT, deadline))) {
        int error = 0; socklen_t length = sizeof(error);
        if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &length) == 0 && error == 0) {
            size_t sent = 0;
            while (sent < message.size() && waitFd(fd, POLLOUT, deadline)) {
                const ssize_t n = send(fd, message.data() + sent, message.size() - sent, MSG_NOSIGNAL);
                if (n > 0) sent += size_t(n);
                else if (n == 0 || (errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK)) break;
            }
            char reply[2]{}; size_t received = 0;
            while (sent == message.size() && received < sizeof(reply) && waitFd(fd, POLLIN, deadline)) {
                const ssize_t n = recv(fd, reply + received, sizeof(reply) - received, 0);
                if (n > 0) received += size_t(n);
                else if (n == 0 || (errno != EINTR && errno != EAGAIN && errno != EWOULDBLOCK)) break;
            }
            ok = received == 2 && reply[0] == 'O' && reply[1] == 'K';
        }
    }
    close(fd);
    return ok;
}
using SendCrop = bool (*)(int, const Crop&, const Crop&, int64_t, int64_t);
SendCrop sendCrop = socketCrop;
struct SocketIdentity {
    dev_t device = 0;
    ino_t inode = 0;
    bool valid = false;
    bool operator==(const SocketIdentity& b) const { return valid && b.valid && device == b.device && inode == b.inode; }
};
SocketIdentity socketIdentity() {
    struct stat st{};
    if (lstat(kSocket, &st) != 0 || !S_ISSOCK(st.st_mode)) return {};
    return {st.st_dev, st.st_ino, true};
}
SocketIdentity (*observeSocket)() = socketIdentity;

void diagnostic(const char* text) {
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_ERROR, "VcamCropHAL", "%s", text);
#else
    (void)text;
#endif
}

bool verifyTransport() {
    const int fd = open(kTransport, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return false;
    struct stat st{};
    bool valid = fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_size == 2109016 && (st.st_mode & 0022) == 0;
    std::vector<uint8_t> bytes;
    if (valid) {
        bytes.resize(size_t(st.st_size)); size_t offset = 0;
        while (offset < bytes.size()) {
            const ssize_t n = read(fd, bytes.data() + offset, bytes.size() - offset);
            if (n > 0) offset += size_t(n);
            else if (n < 0 && errno == EINTR) continue;
            else { valid = false; break; }
        }
        if (valid) valid = sha256(bytes) == kTransportSha;
    }
    close(fd); return valid;
}

void load() {
#if defined(__ANDROID__) || defined(VCAM_WRAPPER_LOADER_TEST)
#ifdef __ANDROID__
    char property[PROP_VALUE_MAX]{};
    if (__system_property_get("ro.hardware", property) <= 0 || strcmp(property, "vphw90") != 0) return;
    if (__system_property_get("ro.build.version.sdk", property) <= 0 || strcmp(property, "28") != 0) return;
#ifndef __aarch64__
    return;
#endif
    if (!verifyTransport()) { diagnostic("Pinned transport path/hash/attributes check failed"); return; }
    void* metadata = dlopen("libcamera_metadata.so", RTLD_NOW | RTLD_LOCAL);
    if (!metadata) { diagnostic("Cannot load public camera metadata library"); return; }
    findMetadata = reinterpret_cast<FindMetadata>(dlsym(metadata, "find_camera_metadata_ro_entry"));
    if (!findMetadata) { diagnostic("Missing public find_camera_metadata_ro_entry symbol"); return; }
    void* transport = dlopen(kTransport, RTLD_NOW | RTLD_LOCAL);
    if (!transport) { diagnostic(dlerror()); return; }
#else
    const char* path = getenv("VCAM_TEST_TRANSPORT");
    if (!path) return;
    void* transport = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!transport) return;
    findMetadata = reinterpret_cast<FindMetadata>(dlsym(transport, "find_camera_metadata_ro_entry"));
    if (!findMetadata) return;
#endif
    auto* module = reinterpret_cast<camera_module_t*>(dlsym(transport, HAL_MODULE_INFO_SYM_AS_STR));
    if (!module || module == &HAL_MODULE_INFO_SYM || module->common.tag != HARDWARE_MODULE_TAG ||
        !module->common.id || strcmp(module->common.id, CAMERA_HARDWARE_MODULE_ID) != 0 ||
        module->common.module_api_version != CAMERA_MODULE_API_VERSION_2_3 || module->common.hal_api_version != 0x0100 ||
        !module->common.methods || !module->common.methods->open || !module->get_camera_info || !module->get_number_of_cameras ||
        module->set_torch_mode || module->init) { diagnostic("Pinned transport HMI does not match measured camera module 2.3 ABI"); return; }
    module->common.dso = transport;
    realModule = module;
#endif
}

bool ready() {
#ifndef VCAM_WRAPPER_TEST
    std::call_once(loadOnce, load);
#endif
    return realModule && findMetadata;
}

bool rectEntry(const camera_metadata_t* metadata, uint32_t tag, Crop& rect, bool& present) {
    camera_metadata_ro_entry_t entry{};
    const int result = findMetadata(metadata, tag, &entry);
    present = result == 0;
    if (result == -ENOENT || result == ENOENT) return true;
    if (result != 0 || entry.type != TYPE_INT32 || entry.count != 4 || !entry.data.i32) return false;
    rect = {entry.data.i32[0], entry.data.i32[1], entry.data.i32[2], entry.data.i32[3]};
    return true;
}

struct Device {
    camera3_device_t publicDevice{};
    camera3_device_ops_t publicOps{};
    camera3_device_t* real = nullptr;
    Crop active{};
    Crop accepted{};
    Crop sent{};
    bool sentValid = false;
    SocketIdentity sentSocket{};
    int camera = 0;
    int64_t epoch = 0;
    int64_t serial = 0;
    std::mutex requests;

    bool publish(const Crop& crop) {
        const auto socket = observeSocket();
        // Reopening the listening socket starts a fresh daemon crop table.
        if (sentValid && sent == crop && sentSocket == socket) return true;
        if (serial == INT64_MAX) return false;
        const bool ok = sendCrop(camera, crop, active, epoch, ++serial);
        if (ok) { sent = crop; sentValid = true; sentSocket = socket; }
        return ok;
    }
};
Device* state(const camera3_device_t* device) { return static_cast<Device*>(device->priv); }
int initialize(const camera3_device_t* d, const camera3_callback_ops_t* c) { auto* s = state(d); return s->real->ops->initialize(s->real,c); }
int configure(const camera3_device_t* d, camera3_stream_configuration_t* c) { auto* s=state(d); return s->real->ops->configure_streams(s->real,c); }
int buffers(const camera3_device_t* d, const camera3_stream_buffer_set_t* b) { auto* s=state(d); return s->real->ops->register_stream_buffers(s->real,b); }
const camera_metadata_t* defaults(const camera3_device_t* d, int type) { auto* s=state(d); return s->real->ops->construct_default_request_settings(s->real,type); }
void vendor(const camera3_device_t* d, vendor_tag_query_ops_t* ops) { auto* s=state(d); s->real->ops->get_metadata_vendor_tag_ops(s->real,ops); }
void dump(const camera3_device_t* d, int fd) { auto* s=state(d); s->real->ops->dump(s->real,fd); }
int flush(const camera3_device_t* d) { auto* s=state(d); return s->real->ops->flush(s->real); }

int request(const camera3_device_t* device, camera3_capture_request_t* capture) {
    if (!capture) return -EINVAL;
    auto* s = state(device);
    std::lock_guard<std::mutex> lock(s->requests);
    Crop next = s->accepted;
    if (capture->settings) {
        Crop absolute{}; bool present = false;
        if (!rectEntry(capture->settings, ANDROID_SCALER_CROP_REGION, absolute, present)) return -EINVAL;
        if (present) {
            const int64_t left = int64_t(absolute.left) - s->active.left;
            const int64_t top = int64_t(absolute.top) - s->active.top;
            if (left < 0 || top < 0 || absolute.width <= 0 || absolute.height <= 0 ||
                left + absolute.width > s->active.width || top + absolute.height > s->active.height) return -EINVAL;
            next = {int32_t(left), int32_t(top), absolute.width, absolute.height};
        }
    }
    if (!s->publish(next)) {
        // No buffer ownership has passed to the underlying HAL. A lost ACK
        // might have applied next, so restore the last accepted crop as well.
        s->sentValid = false;
        s->publish(s->accepted);
        return -ENODEV;
    }
    const int result = s->real->ops->process_capture_request(s->real, capture);
    if (result == 0) {
        s->accepted = next;
    } else {
        // The real HAL rejected ownership. Restore its previous accepted crop
        // while preserving the exact original return code.
        s->publish(s->accepted);
    }
    return result;
}

int closeDevice(hw_device_t* common) {
    auto* s = state(reinterpret_cast<camera3_device_t*>(common));
    const int result = s->real->common.close(&s->real->common);
    s->sentValid = false; // Always reset on close, including failed prior ACK.
    s->publish({0,0,s->active.width,s->active.height});
    delete s;
    return result;
}

int parseCamera(const char* name) {
    if (!name || !*name || (name[0] == '0' && name[1])) return -1;
    int id = 0;
    for (const char* p = name; *p; ++p) {
        if (*p < '0' || *p > '9') return -1;
        id = id * 10 + (*p - '0'); if (id > 31) return -1;
    }
    return id;
}

int wrap(int id, hw_device_t* underlying, hw_device_t** output) {
    if (!underlying) return -ENODEV;
    auto reject = [&](int code) { if (underlying->close) underlying->close(underlying); return code; };
    if (underlying->tag != HARDWARE_DEVICE_TAG || underlying->version < CAMERA_DEVICE_API_VERSION_3_0 ||
        underlying->version > CAMERA_DEVICE_API_VERSION_3_5 || !underlying->close) return reject(-ENOSYS);
    auto* device = reinterpret_cast<camera3_device_t*>(underlying);
    if (!device->ops || !device->ops->initialize || !device->ops->configure_streams ||
        !device->ops->construct_default_request_settings || !device->ops->process_capture_request || !device->ops->dump) return reject(-ENODEV);
    for (void* reserved : device->ops->reserved) if (reserved) return reject(-ENOSYS);
    camera_info info{};
    if (realModule->get_camera_info(id, &info) || !info.static_camera_characteristics) return reject(-ENODEV);
    Crop active{}; bool present = false;
    if (!rectEntry(info.static_camera_characteristics, ANDROID_SENSOR_INFO_ACTIVE_ARRAY_SIZE, active, present) || !present ||
        active.left < 0 || active.top < 0 || active.width <= 0 || active.height <= 0 || active.width > 65536 || active.height > 65536 ||
        int64_t(active.left) + active.width > INT32_MAX || int64_t(active.top) + active.height > INT32_MAX) return reject(-ENODEV);
    auto* s = new (std::nothrow) Device;
    if (!s) return reject(-ENOMEM);
    s->real = device; s->active = active; s->accepted = {0,0,active.width,active.height}; s->camera = id;
    s->epoch = timeNs(CLOCK_BOOTTIME);
    s->publicDevice = *device; s->publicOps = *device->ops;
    s->publicDevice.common.module = &HAL_MODULE_INFO_SYM.common;
    s->publicDevice.common.close = closeDevice;
    s->publicDevice.ops = &s->publicOps; s->publicDevice.priv = s;
    s->publicOps.initialize = initialize; s->publicOps.configure_streams = configure;
    s->publicOps.register_stream_buffers = device->ops->register_stream_buffers ? buffers : nullptr;
    s->publicOps.construct_default_request_settings = defaults; s->publicOps.process_capture_request = request;
    s->publicOps.get_metadata_vendor_tag_ops = device->ops->get_metadata_vendor_tag_ops ? vendor : nullptr;
    s->publicOps.dump = dump; s->publicOps.flush = device->ops->flush ? flush : nullptr;
    if (s->epoch <= 0 || !s->publish(s->accepted)) {
        diagnostic("Full-crop proxy control ACK failed; wrapped camera open refused");
        delete s; return reject(-ENODEV);
    }
    *output = &s->publicDevice.common;
    return 0;
}

int openDevice(const hw_module_t*, const char* name, hw_device_t** output) {
    if (!output) return -EINVAL;
    *output = nullptr;
    const int id = parseCamera(name);
    if (id < 0) return -EINVAL;
    if (!ready()) return -ENODEV;
    hw_device_t* underlying = nullptr;
    const int result = realModule->common.methods->open(&realModule->common, name, &underlying);
    if (result) { char message[100]; snprintf(message, sizeof(message), "Underlying camera module open returned %d", result); diagnostic(message); }
    return result ? result : wrap(id, underlying, output);
}
int legacy(const hw_module_t*, const char* name, uint32_t version, hw_device_t** output) {
    if (!output) return -EINVAL;
    *output = nullptr;
    const int id = parseCamera(name);
    if (id < 0) return -EINVAL;
    if (version < CAMERA_DEVICE_API_VERSION_3_0 || version > CAMERA_DEVICE_API_VERSION_3_5) return -ENOSYS;
    if (!ready() || !realModule->open_legacy) return -ENOSYS;
    hw_device_t* underlying = nullptr;
    const int result = realModule->open_legacy(&realModule->common, name, version, &underlying);
    return result ? result : wrap(id, underlying, output);
}
int number() { return ready() ? realModule->get_number_of_cameras() : 0; }
int info(int id, camera_info* value) { return ready() ? realModule->get_camera_info(id,value) : -ENODEV; }
int callbacks(const camera_module_callbacks_t* c) { return ready() && realModule->set_callbacks ? realModule->set_callbacks(c) : -ENOSYS; }
void tags(vendor_tag_ops_t* ops) { if (ready() && realModule->get_vendor_tag_ops) realModule->get_vendor_tag_ops(ops); }
int torch(const char* id, bool on) { return ready() && realModule->set_torch_mode ? realModule->set_torch_mode(id,on) : -ENOSYS; }
int init() { return ready() ? (realModule->init ? realModule->init() : 0) : -ENODEV; }
hw_module_methods_t methods{openDevice};
camera_module_t module() {
    camera_module_t m{};
    m.common.tag = HARDWARE_MODULE_TAG;
    m.common.module_api_version = CAMERA_MODULE_API_VERSION_2_3;
    m.common.hal_api_version = 0x0100;
    m.common.id = CAMERA_HARDWARE_MODULE_ID;
    m.common.name = "Measured VMOS Android 9 camera3 crop adapter";
    m.common.author = "VirtualCameraPro";
    m.common.methods = &methods;
    m.get_number_of_cameras = number; m.get_camera_info = info; m.set_callbacks = callbacks;
    m.get_vendor_tag_ops = tags; m.open_legacy = legacy;
    return m;
}
}

extern "C" {
__attribute__((visibility("default"))) camera_module_t HAL_MODULE_INFO_SYM = vcam::module();
}
