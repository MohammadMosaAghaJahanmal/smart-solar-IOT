// SPDX-License-Identifier: Apache-2.0
// Separate DSO deliberately references its own exported HMI like the real HAL.
#include <hardware/camera3.h>
#include <cassert>
#include <cerrno>
#include <cstring>
extern "C" camera_module_t HMI;
namespace {
camera3_device_t device{};
int calls = 0;
int initialize(const camera3_device_t* d,const camera3_callback_ops_t*) { assert(d == &device); ++calls; return 31; }
int configure(const camera3_device_t* d,camera3_stream_configuration_t*) { assert(d == &device); ++calls; return 32; }
const camera_metadata_t* defaults(const camera3_device_t* d,int) { assert(d == &device); ++calls; return reinterpret_cast<const camera_metadata_t*>(uintptr_t(1)); }
int request(const camera3_device_t* d,camera3_capture_request_t*) { assert(d == &device); ++calls; return 0; }
void dump(const camera3_device_t* d,int) { assert(d == &device); ++calls; }
int closeDevice(hw_device_t* d) { assert(d == &device.common); ++calls; return 33; }
int open(const hw_module_t* module,const char* id,hw_device_t** result) {
    assert(module == &HMI.common && strcmp(id,"0") == 0);
    ++calls;
    static camera3_device_ops_t ops{};
    ops.initialize = initialize; ops.configure_streams = configure;
    ops.construct_default_request_settings = defaults; ops.process_capture_request = request; ops.dump = dump;
    device.common.tag = HARDWARE_DEVICE_TAG; device.common.version = CAMERA_DEVICE_API_VERSION_3_2;
    device.common.module = &HMI.common; device.common.close = closeDevice; device.ops = &ops;
    *result = &device.common; return 0;
}
int cameras() { return 1; }
int info(int id,camera_info* value) { assert(id == 0); value->static_camera_characteristics = reinterpret_cast<const camera_metadata_t*>(uintptr_t(1)); return 0; }
camera_module_t module() {
    static hw_module_methods_t methods{open};
    camera_module_t m{}; m.common.tag = HARDWARE_MODULE_TAG; m.common.module_api_version = CAMERA_MODULE_API_VERSION_2_3;
    m.common.hal_api_version = 0x0100; m.common.id = CAMERA_HARDWARE_MODULE_ID; m.common.methods = &methods;
    m.get_number_of_cameras = cameras; m.get_camera_info = info;
    return m;
}
}
extern "C" {
camera_module_t HMI = module();
int find_camera_metadata_ro_entry(const camera_metadata_t* metadata,uint32_t tag,camera_metadata_ro_entry_t* entry) {
    assert(metadata == reinterpret_cast<const camera_metadata_t*>(uintptr_t(1)));
    if (tag != ANDROID_SENSOR_INFO_ACTIVE_ARRAY_SIZE) return -ENOENT;
    static int32_t rect[]{0,0,1920,1080}; entry->type = TYPE_INT32; entry->count = 4; entry->data.i32 = rect; return 0;
}
int vcam_test_calls() { return calls; }
}
