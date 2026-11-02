"""Compile the production identity function against Android property API fixtures.

The inspected 93-byte fingerprint is a real long-property regression: the old
PROP_VALUE_MAX getter receives Bionic's compatibility value instead of it.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile

SOURCE = Path(__file__).with_name("jpeg-color-shim.cpp")
HEADER = r'''
#include <cassert>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <string>
#define PROP_VALUE_MAX 92
struct prop_info { const char* value; };
const char* actualFingerprint = "Infinix/X687B-OP/Infinix-X687B:9/PQ3A.190801.002/eng.builds.20191015.144423:user/release-keys";
const char* hardware = "vphw90";
const char* sdk = "28";
const char* abi = "arm64-v8a";
bool propertyPresent = true;
int callbackReads = 0;
int legacyFingerprintReads = 0;
int failures = 0;
int __system_property_get(const char* name, char* value) {
    const char* selected = nullptr;
    if (!strcmp(name, "ro.hardware")) selected = hardware;
    else if (!strcmp(name, "ro.build.version.sdk")) selected = sdk;
    else if (!strcmp(name, "ro.product.cpu.abilist")) selected = abi;
    else if (!strcmp(name, "ro.build.fingerprint")) {
        legacyFingerprintReads++;
        selected = "Must use __system_property_read_callback() to read";
    }
    if (!selected) {value[0] = 0; return 0;}
    assert(strlen(selected) < PROP_VALUE_MAX);
    strcpy(value, selected); return strlen(value);
}
const prop_info* __system_property_find(const char* name) {
    assert(!strcmp(name, "ro.build.fingerprint"));
    static prop_info property;
    property.value = actualFingerprint;
    return propertyPresent ? &property : nullptr;
}
void __system_property_read_callback(const prop_info* property,
    void (*callback)(void*, const char*, const char*, uint32_t), void* cookie) {
    callbackReads++;
    callback(cookie, "ro.build.fingerprint", property->value, 0);
}
void loaderFailure(const char*, int = 0) {failures++;}
'''
MAIN = r'''
int main() {
    assert(strlen(actualFingerprint) == 93);
    assert(guestIdentity());
    assert(callbackReads == 1 && legacyFingerprintReads == 0 && failures == 0);
    const char* original = actualFingerprint;
    actualFingerprint = "Infinix/X687B-OP/Infinix-X687B:9/PQ3A.190801.002/eng.builds.20191015.144423:userdebug/test-keys";
    assert(!guestIdentity());
    actualFingerprint = "Infinix";
    assert(!guestIdentity());
    actualFingerprint = original;
    propertyPresent = false;
    assert(!guestIdentity());
    propertyPresent = true;
    hardware = "mt6785";
    assert(!guestIdentity());
    hardware = "vphw90";
    sdk = "29";
    assert(!guestIdentity());
    sdk = "28";
    abi = "arm64-v8a,armeabi-v7a";
    assert(!guestIdentity());
    abi = "arm64-v8a";
    assert(guestIdentity());
    assert(legacyFingerprintReads == 0);
    std::cout << "actual 93-byte fingerprint callback; mismatched/missing fingerprint, SDK, hardware, ABI refusal: PASS\n";
}
'''


def main():
    compiler = shutil.which("g++")
    if compiler is None:
        raise SystemExit("Run this CPU check under Linux/WSL with g++")
    source = SOURCE.read_text()
    identity = source[source.index("bool guestIdentity() {"):source.index("\nbool verifiedEncoder() {")]
    with tempfile.TemporaryDirectory(prefix="vcam-jpeg-identity-") as directory:
        directory = Path(directory)
        fixture = directory / "identity.cpp"
        fixture.write_text(HEADER + identity + MAIN)
        binary = directory / "identity"
        subprocess.run([compiler, "-std=c++17", "-Wall", "-Wextra", "-Werror", "-D__ANDROID__", "-D__aarch64__",
                        str(fixture), "-o", str(binary)], check=True)
        subprocess.run([str(binary)], check=True)


if __name__ == "__main__":
    main()
