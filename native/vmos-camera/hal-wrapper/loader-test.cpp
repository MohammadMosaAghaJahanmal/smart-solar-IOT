// SPDX-License-Identifier: Apache-2.0
#include <hardware/camera3.h>
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <string>
#include <sys/socket.h>
#include <sys/un.h>
#include <thread>
#include <unistd.h>

int main(int argc,char** argv) {
    assert(argc == 3);
    assert(setenv("VCAM_TEST_TRANSPORT",argv[2],1) == 0);
    const char* path = "/tmp/vcam-hal-wrapper-test.sock";
    unlink(path); int server = socket(AF_UNIX,SOCK_STREAM,0); assert(server >= 0);
    sockaddr_un address{}; address.sun_family = AF_UNIX; strcpy(address.sun_path,path);
    assert(bind(server,reinterpret_cast<sockaddr*>(&address),sizeof(address)) == 0 && listen(server,2) == 0);
    std::thread peer([&] {
        for (int i = 0; i < 2; ++i) {
            const int client = accept(server,nullptr,nullptr); assert(client >= 0);
            std::string first,second; char c;
            while (recv(client,&c,1,0) == 1 && c) first += c;
            while (recv(client,&c,1,0) == 1 && c) second += c;
            assert(first == "vcam:crop:camera_0" && second.find("crop left=0 top=0 width=1920 height=1080 active_width=1920 active_height=1080 epoch=") == 0);
            assert(send(client,"OK",2,MSG_NOSIGNAL) == 2); close(client);
        }
    });
    void* wrapper = dlopen(argv[1],RTLD_NOW|RTLD_LOCAL);
    if (!wrapper) { fprintf(stderr,"%s\n",dlerror()); abort(); }
    auto* module = reinterpret_cast<camera_module_t*>(dlsym(wrapper,"HMI")); assert(module);
    assert(module->get_number_of_cameras() == 1);
    hw_device_t* device = nullptr; assert(module->common.methods->open(&module->common,"0",&device) == 0);
    assert(device && device->module == &module->common);
    auto* camera = reinterpret_cast<camera3_device_t*>(device);
    assert(camera->ops->initialize(camera,nullptr) == 31 && camera->ops->configure_streams(camera,nullptr) == 32);
    assert(camera->ops->construct_default_request_settings(camera,1) == reinterpret_cast<const camera_metadata_t*>(uintptr_t(1)));
    camera3_capture_request_t request{}; assert(camera->ops->process_capture_request(camera,&request) == 0);
    assert(!camera->ops->register_stream_buffers && !camera->ops->flush);
    camera->ops->dump(camera,1);
    assert(device->close(device) == 33);
    void* mock = dlopen(argv[2],RTLD_NOW|RTLD_LOCAL); assert(mock);
    auto count = reinterpret_cast<int(*)()>(dlsym(mock,"vcam_test_calls")); assert(count && count() == 7);
    assert(dlsym(mock,"HMI") != module);
    peer.join(); close(server); unlink(path);
    puts("PASS separate-DSO dlopen/dlsym HMI self-relocation, original module/device forwarding, metadata lookup, open/request/close control");
}
