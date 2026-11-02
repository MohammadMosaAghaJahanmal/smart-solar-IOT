#include "../../aosp/camera-routing/VirtualCameraProRoute.h"
#include <cassert>
#include <iostream>
int main() {
    using android::vcam::resolve;
    using android::vcam::parseRoute;
    assert(resolve("0", "0,v0_1000,6000", 1000) == "v0_1000");
    assert(resolve("1", "0,v0_1000,6000", 1000) == "1");
    assert(resolve("0", "0,v0_1000,6000", 6000) == "0");
    assert(resolve("0", "0,v0_1000,9999999", 1000) == "0");
    assert(resolve("v0_1000", "0,v0_1000,6000", 1000) == "v0_1000");
    for (auto record : {"", "0", "0,1", "0,0,6000", "0,physical,6000", "0,v0_1,-1", "0,v0_1,5000x", "0,v0_1,9999999999999999999999", "0,v0_1,6000,1", "0,v0_/1,6000"}) {
        assert(!parseRoute(record,1000));
    }
    std::cout << "Routing: activation, isolation, expiry, malformed inputs, and restore passed\n";
}
