// SPDX-License-Identifier: Apache-2.0
#define VCAM_WRAPPER_TEST
#include "camera-wrapper.cpp"
#include <algorithm>
#include <cassert>
#include <iostream>
#include <thread>

namespace {
struct Metadata { uint32_t tag; uint8_t type = TYPE_INT32; size_t count = 4; int32_t values[4]; };
Metadata active{ANDROID_SENSOR_INFO_ACTIVE_ARRAY_SIZE, TYPE_INT32, 4, {10,20,1920,1080}};
Metadata crop{ANDROID_SCALER_CROP_REGION, TYPE_INT32, 4, {490,290,960,540}};
Metadata other{ANDROID_CONTROL_MODE, TYPE_INT32, 1, {1,0,0,0}};
camera3_device_t real{};
camera3_device_ops_t ops{};
camera_module_t module{};
int requestResult = 0, requestCount = 0, closed = 0, sent = 0, legacyCalls = 0;
bool ack = true;
ino_t socketInode = 1;
vcam::Crop last{};
int64_t lastSerial = 0, lastEpoch = 0;
const camera3_callback_ops_t* callbackPointer = nullptr;
const camera_metadata_t* metadata(const Metadata& m) { return reinterpret_cast<const camera_metadata_t*>(&m); }
int lookup(const camera_metadata_t* ptr, uint32_t tag, camera_metadata_ro_entry_t* out) {
    const auto* m = reinterpret_cast<const Metadata*>(ptr);
    if (m->tag != tag) return -ENOENT;
    out->tag = tag; out->type = m->type; out->count = m->count; out->data.i32 = m->values;
    return 0;
}
bool sendCrop(int id, const vcam::Crop& c, const vcam::Crop& a, int64_t epoch, int64_t serial) {
    assert(id == 1); assert(a.left == 10 && a.top == 20 && a.width == 1920 && a.height == 1080);
    assert(epoch > 0); assert(epoch != lastEpoch || serial > lastSerial);
    last = c; lastEpoch = epoch; lastSerial = serial; ++sent;
    return ack;
}
int closeReal(hw_device_t* d) { assert(d == &real.common); ++closed; return 23; }
int initialize(const camera3_device_t* d,const camera3_callback_ops_t* c) { assert(d == &real); callbackPointer = c; return 11; }
int configure(const camera3_device_t* d,camera3_stream_configuration_t* c) { assert(d == &real && c->num_streams == 7); return 12; }
int buffers(const camera3_device_t* d,const camera3_stream_buffer_set_t* b) { assert(d == &real && b->num_buffers == 8); return 13; }
const camera_metadata_t* defaults(const camera3_device_t* d,int type) { assert(d == &real && type == 3); return metadata(other); }
int request(const camera3_device_t* d,camera3_capture_request_t* r) {
    assert(d == &real && r->frame_number == 9);
    if (r->settings) {
        const auto* m = reinterpret_cast<const Metadata*>(r->settings);
        if (m->tag == ANDROID_SCALER_CROP_REGION)
            assert(last == (vcam::Crop{m->values[0]-10,m->values[1]-20,m->values[2],m->values[3]}));
    }
    ++requestCount; return requestResult;
}
void vendor(const camera3_device_t* d,vendor_tag_query_ops_t* p) { assert(d == &real && p); }
void dump(const camera3_device_t* d,int fd) { assert(d == &real && fd == 10); }
int flush(const camera3_device_t* d) { assert(d == &real); return 14; }
int openReal(const hw_module_t* m,const char* name,hw_device_t** out) { assert(m == &module.common && strcmp(name,"1") == 0); *out = &real.common; return 0; }
int legacy(const hw_module_t* m,const char* name,uint32_t v,hw_device_t** out) { assert(v == CAMERA_DEVICE_API_VERSION_3_3); ++legacyCalls; return openReal(m,name,out); }
int info(int id,camera_info* out) { assert(id == 1); out->static_camera_characteristics = metadata(active); return 0; }
int count() { return 2; }
int callbacks(const camera_module_callbacks_t* p) { return p ? 18 : 19; }
void tags(vendor_tag_ops_t* p) { assert(p); p->reserved[0] = reinterpret_cast<void*>(uintptr_t(42)); }
int torch(const char* id,bool on) { assert(strcmp(id,"1") == 0 && on); return 20; }
int init() { return 21; }

void setup() {
    real = {}; ops = {}; module = {};
    real.common.tag = HARDWARE_DEVICE_TAG; real.common.version = CAMERA_DEVICE_API_VERSION_3_3; real.common.close = closeReal;
    real.ops = &ops; real.priv = reinterpret_cast<void*>(uintptr_t(0x1234));
    ops.initialize = initialize; ops.configure_streams = configure; ops.register_stream_buffers = buffers;
    ops.construct_default_request_settings = defaults; ops.process_capture_request = request;
    ops.get_metadata_vendor_tag_ops = vendor; ops.dump = dump; ops.flush = flush;
    static hw_module_methods_t methods{openReal};
    module.common.methods = &methods; module.get_number_of_cameras = count; module.get_camera_info = info;
    module.set_callbacks = callbacks; module.get_vendor_tag_ops = tags; module.open_legacy = legacy;
    module.set_torch_mode = torch; module.init = init;
    vcam::realModule = &module; vcam::findMetadata = lookup; vcam::sendCrop = sendCrop;
    vcam::observeSocket = [] { return vcam::SocketIdentity{1,socketInode,true}; };
    requestResult = requestCount = closed = sent = legacyCalls = 0; ack = true; lastSerial = lastEpoch = 0; socketInode = 1;
}
camera3_device_t* openWrapped() {
    hw_device_t* out = nullptr;
    assert(HAL_MODULE_INFO_SYM.common.methods->open(&HAL_MODULE_INFO_SYM.common,"1",&out) == 0);
    assert(out != &real.common && out->module == &HAL_MODULE_INFO_SYM.common);
    assert(sent == 1 && last == (vcam::Crop{0,0,1920,1080}));
    assert(real.priv == reinterpret_cast<void*>(uintptr_t(0x1234)));
    return reinterpret_cast<camera3_device_t*>(out);
}
void forwarding() {
    setup();
    assert(HAL_MODULE_INFO_SYM.get_number_of_cameras() == 2);
    assert(HAL_MODULE_INFO_SYM.set_callbacks(nullptr) == 19);
    vendor_tag_ops_t tagOps{}; HAL_MODULE_INFO_SYM.get_vendor_tag_ops(&tagOps);
    assert(tagOps.reserved[0] == reinterpret_cast<void*>(uintptr_t(42)));
    assert(!HAL_MODULE_INFO_SYM.set_torch_mode && !HAL_MODULE_INFO_SYM.init);
    assert(vcam::torch("1",true) == 20 && vcam::init() == 21);
    auto* d = openWrapped();
    camera3_callback_ops_t c{}; assert(d->ops->initialize(d,&c) == 11 && callbackPointer == &c);
    camera3_stream_configuration_t config{}; config.num_streams = 7; assert(d->ops->configure_streams(d,&config) == 12);
    camera3_stream_buffer_set_t buffers{}; buffers.num_buffers = 8; assert(d->ops->register_stream_buffers(d,&buffers) == 13);
    assert(d->ops->construct_default_request_settings(d,3) == metadata(other));
    vendor_tag_query_ops_t v{}; d->ops->get_metadata_vendor_tag_ops(d,&v);
    d->ops->dump(d,10); assert(d->ops->flush(d) == 14);
    assert(d->common.close(&d->common) == 23 && closed == 1 && sent == 2 && last == (vcam::Crop{0,0,1920,1080}));
    std::cout << "PASS all module and eight camera3 callbacks forward the original device and preserve return values\n";
}
void requests() {
    setup(); auto* d = openWrapped(); camera3_capture_request_t r{}; r.frame_number = 9; r.settings = metadata(crop);
    assert(d->ops->process_capture_request(d,&r) == 0 && requestCount == 1 && sent == 2 && last == (vcam::Crop{480,270,960,540}));
    r.settings = nullptr; assert(d->ops->process_capture_request(d,&r) == 0 && sent == 2);
    r.settings = metadata(other); assert(d->ops->process_capture_request(d,&r) == 0 && sent == 2);
    std::cout << "PASS metadata crop uses sensor origin, unchanged crop sends once, null settings reuse\n";
    Metadata invalid = crop; invalid.values[0] = INT32_MAX; r.settings = metadata(invalid);
    int before = requestCount; assert(d->ops->process_capture_request(d,&r) == -EINVAL && requestCount == before && sent == 2);
    invalid = crop; invalid.values[2] = 0; assert(d->ops->process_capture_request(d,&r) == -EINVAL);
    invalid = crop; invalid.values[1] = 19; assert(d->ops->process_capture_request(d,&r) == -EINVAL);
    invalid = crop; invalid.count = 3; assert(d->ops->process_capture_request(d,&r) == -EINVAL);
    invalid = crop; invalid.type = TYPE_FLOAT; assert(d->ops->process_capture_request(d,&r) == -EINVAL);
    assert(d->ops->process_capture_request(d,nullptr) == -EINVAL);
    std::cout << "PASS malformed crop count/type/overflow/negative and empty extents rejected before underlying request\n";
    Metadata full = active; full.tag = ANDROID_SCALER_CROP_REGION; r.settings = metadata(full); requestResult = -EIO;
    assert(d->ops->process_capture_request(d,&r) == -EIO && sent == 4 && last == (vcam::Crop{480,270,960,540}));
    r.settings = nullptr; requestResult = 0; assert(d->ops->process_capture_request(d,&r) == 0 && sent == 4);
    std::cout << "PASS failed underlying request restores previous crop and preserves original error\n";
    before = requestCount;
    r.settings = metadata(full); ack = false; assert(d->ops->process_capture_request(d,&r) == -ENODEV && sent == 6 && requestCount == before);
    const int64_t failedSerial = lastSerial;
    ack = true; assert(d->ops->process_capture_request(d,&r) == 0 && sent == 7 && lastSerial > failedSerial);
    assert(last == (vcam::Crop{0,0,1920,1080}));
    std::cout << "PASS lost ACK never transfers buffer ownership; retried request succeeds after control recovery\n";
    ++socketInode; r.settings = nullptr;
    assert(d->ops->process_capture_request(d,&r) == 0 && sent == 8 && lastSerial > failedSerial);
    std::cout << "PASS replacement listening socket republishes crop for an unchanged repeating request\n";
    d->common.close(&d->common); assert(sent == 9 && closed == 1);
}
void guards() {
    setup(); hw_device_t* out = reinterpret_cast<hw_device_t*>(uintptr_t(1));
    for (const char* name : {"", "01", "-1", "32", "9999999999999", "a"})
        assert(HAL_MODULE_INFO_SYM.common.methods->open(nullptr,name,&out) == -EINVAL && !out);
    assert(HAL_MODULE_INFO_SYM.open_legacy(nullptr,"1",CAMERA_DEVICE_API_VERSION_1_0,&out) == -ENOSYS && legacyCalls == 0);
    assert(HAL_MODULE_INFO_SYM.open_legacy(nullptr,"1",CAMERA_DEVICE_API_VERSION_3_3,&out) == 0 && legacyCalls == 1);
    out->close(out);
    setup(); ops.register_stream_buffers = nullptr; ops.get_metadata_vendor_tag_ops = nullptr; ops.flush = nullptr;
    auto* d = openWrapped(); assert(!d->ops->register_stream_buffers && !d->ops->get_metadata_vendor_tag_ops && !d->ops->flush); d->common.close(&d->common);
    setup(); ack = false; assert(HAL_MODULE_INFO_SYM.common.methods->open(nullptr,"1",&out) == -ENODEV && !out && closed == 1);
    setup(); ops.reserved[0] = reinterpret_cast<void*>(uintptr_t(1));
    assert(HAL_MODULE_INFO_SYM.common.methods->open(nullptr,"1",&out) == -ENOSYS && !out && closed == 1);
    setup(); active.values[2] = 65537;
    assert(HAL_MODULE_INFO_SYM.common.methods->open(nullptr,"1",&out) == -ENODEV && !out && closed == 1); active.values[2] = 1920;
    std::cout << "PASS canonical camera names, camera3-only legacy, optional NULL callbacks, open ACK, active bounds and reserved ABI guards\n";
}
void socketProtocol(bool reply) {
    unlink(vcam::kSocket);
    const int server = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0); assert(server >= 0);
    sockaddr_un address{}; address.sun_family = AF_UNIX; strcpy(address.sun_path, vcam::kSocket);
    assert(bind(server,reinterpret_cast<sockaddr*>(&address),sizeof(address)) == 0 && listen(server,1) == 0);
    std::thread peer([&] {
        const int fd = accept(server,nullptr,nullptr); assert(fd >= 0);
        std::string input; char c;
        while (std::count(input.begin(),input.end(),'\0') != 2 && recv(fd,&c,1,0) == 1) input.push_back(c);
        std::string expected = "vcam:crop:camera_1"; expected.push_back('\0');
        expected += "crop left=480 top=270 width=960 height=540 active_width=1920 active_height=1080 epoch=1234 serial=2"; expected.push_back('\0');
        assert(input == expected);
        if (reply) { assert(send(fd,"O",1,MSG_NOSIGNAL) == 1); assert(send(fd,"K",1,MSG_NOSIGNAL) == 1); }
        else std::this_thread::sleep_for(std::chrono::milliseconds(180));
        close(fd);
    });
    const auto begin = vcam::timeNs(CLOCK_MONOTONIC);
    const bool ok = vcam::socketCrop(1,{480,270,960,540},{10,20,1920,1080},1234,2);
    const auto duration = vcam::timeNs(CLOCK_MONOTONIC) - begin;
    assert(ok == reply && duration < 160000000);
    peer.join(); close(server); unlink(vcam::kSocket);
    std::cout << (reply ? "PASS exact NUL framing and fragmented two-byte ACK\n" : "PASS stalled control connection bounded by 100ms total deadline\n");
}
}
int main() {
    assert(sizeof(hw_module_t) == 248 && sizeof(hw_device_t) == 120 && sizeof(camera3_device_t) == 136);
    assert(HAL_MODULE_INFO_SYM.common.module_api_version == CAMERA_MODULE_API_VERSION_2_3 && HAL_MODULE_INFO_SYM.common.hal_api_version == 0x0100);
    assert(ANDROID_SCALER_CROP_REGION == 0xd0000);
    assert(vcam::sha256({}) == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    assert(vcam::sha256({'a','b','c'}) == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    assert(vcam::sha256(std::vector<uint8_t>(1000000,'a')) == "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0");
    std::cout << "PASS Android 9 LP64 struct sizes, metadata tag and independent SHA-256 vectors\n";
    forwarding(); requests(); guards(); socketProtocol(true); socketProtocol(false);
}
