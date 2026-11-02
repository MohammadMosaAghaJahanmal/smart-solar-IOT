#!/usr/bin/env python3
"""Install source + exact, reviewable modifications in an AOSP 16 r1 checkout."""
import argparse
import difflib
import hashlib
import json
import pathlib
import shutil
import subprocess

REPO = pathlib.Path(__file__).resolve().parents[1]
TAG = "android-16.0.0_r1"


def transform_camera(text):
    text = text.replace('#include "CameraService.h"', '#include "CameraService.h"\n#include "VirtualCameraProRoute.h"', 1)
    anchor = '    const char* kUnknownPackageName = "<unknown>";'
    helper = '''

    // Virtual Camera Pro: only default-device camera IDs pass this routing gate.
    std::string resolveVirtualCameraPro(const std::string& id) {
        char record[PROPERTY_VALUE_MAX] = {};
        property_get("sys.vcam.route", record, "");
        return android::vcam::resolve(id, record, android::elapsedRealtime());
    }'''
    assert text.count(anchor) == 1, "CameraService anchor changed"
    text = text.replace(anchor, anchor + helper, 1)
    start = text.index('std::optional<std::string> CameraService::resolveCameraId(')
    end = text.index('\nStatus CameraService::getCameraInfo(', start)
    section = text[start:end]
    assert section.count('return inputCameraId;') == 1
    section = section.replace('return inputCameraId;', 'return resolveVirtualCameraPro(inputCameraId);')
    text = text[:start] + section + text[end:]
    anchor = '    return (*cameraIds)[cameraIdInt];'
    assert text.count(anchor) == 1
    text = text.replace(anchor, '    return resolveVirtualCameraPro((*cameraIds)[cameraIdInt]);', 1)
    start = text.index('status_t CameraService::dump(int fd, const Vector<String16>& args) {')
    anchor = '    bool locked = tryLock(mServiceLock);'
    at = text.index(anchor, start)
    diagnostics = '''    // A privileged broker verifies the actual framework hook, not just a build property.
    if (args.size() == 1 && args[0] == String16("--virtual-camera-pro")) {
        const bool acquired = tryLock(mServiceLock);
        char record[PROPERTY_VALUE_MAX] = {};
        property_get("sys.vcam.route", record, "");
        const auto route = android::vcam::parseRoute(record, android::elapsedRealtime());
        const bool registered = acquired && route && getCameraState(route->synthetic) != nullptr;
        dprintf(fd, "{\\"protocol\\":2,\\"initialized\\":%s,\\"responsive\\":%s,"
                "\\"routeInstalled\\":%s,\\"registered\\":%s,\\"physicalId\\":\\"%s\\","
                "\\"syntheticId\\":\\"%s\\",\\"resolvedId\\":\\"%s\\"}\\n",
                acquired && mInitialized ? "true" : "false", acquired ? "true" : "false",
                route ? "true" : "false", registered ? "true" : "false",
                route ? route->physical.c_str() : "", route ? route->synthetic.c_str() : "",
                route ? resolveVirtualCameraPro(route->physical).c_str() : "");
        if (acquired) mServiceLock.unlock();
        return NO_ERROR;
    }

'''
    return text[:at] + diagnostics + text[at:]


def transform_provider(text):
    anchor = '    return vd_flags::virtual_camera_service_build_flag();'
    assert text.count(anchor) == 1
    # This product explicitly includes the HAL, independent of virtual-device flags.
    return text.replace(anchor, '    return true;  // Virtual Camera Pro controlled system image', 1)


def transform_hal(text):
    # Stable default-device ID allows cleanup/re-registration after broker process death.
    anchor = 'std::string createCameraId(const int32_t deviceId) {'
    assert text.count(anchor) == 1
    text = text.replace(anchor, anchor + '\n  if (deviceId == 0) return "v0_pro";', 1)
    anchor = '  std::shared_ptr<VirtualCameraDevice> camera =\n      mVirtualCameraProvider->createCamera(configuration, cameraId, deviceId);'
    cleanup = '''  // Only the controlled primary-display broker uses this stable ID.
  // Remove an orphan left behind by its previous process before re-registering.
  if (deviceId == 0 && cameraId == "v0_pro") {
    for (auto it = mTokenToCameraName.begin(); it != mTokenToCameraName.end();) {
      auto previous = mVirtualCameraProvider->getCamera(it->second);
      if (previous != nullptr && previous->getCameraId() == cameraId) {
        mVirtualCameraProvider->removeCamera(it->second);
        it = mTokenToCameraName.erase(it);
      } else {
        ++it;
      }
    }
  }

'''
    assert text.count(anchor) == 1, "VirtualCameraService anchor changed"
    return text.replace(anchor, cleanup + anchor, 1)


def transform_permissions(text):
    text = text.replace('#include "Permissions.h"', '#include "Permissions.h"\n#include <binder/IPCThreadState.h>\n#include <android-base/properties.h>\n#include <private/android_filesystem_config.h>', 1)
    anchor = '  int32_t uid;\n  int32_t pid;'
    assert text.count(anchor) == 1
    return text.replace(anchor, '''  // The platform-signed system UID broker is authorized on this product only.
  if (permission == "android.permission.CREATE_VIRTUAL_DEVICE" &&
      IPCThreadState::self()->getCallingUid() == AID_SYSTEM &&
      android::base::GetProperty("ro.virtualcamera.pro.version", "") == "2") {
    return true;
  }
''' + anchor, 1)


TRANSFORMS = {
    'services/camera/libcameraservice/CameraService.cpp': transform_camera,
    'services/camera/libcameraservice/common/CameraProviderManager.cpp': transform_provider,
    'services/camera/virtualcamera/VirtualCameraService.cc': transform_hal,
    'services/camera/virtualcamera/util/Permissions.cc': transform_permissions,
}


def main():
    p = argparse.ArgumentParser()
    p.add_argument('aosp', type=pathlib.Path)
    p.add_argument('--cert-sha256', required=True, help='SHA-256 of the UI APK signing certificate')
    p.add_argument('--apply', action='store_true', help='Without this, print diff only')
    args = p.parse_args()
    root = args.aosp.resolve()
    av = root / 'frameworks/av'
    head = subprocess.check_output(['git','rev-parse','HEAD'],cwd=av,text=True).strip()
    pinned = subprocess.check_output(['git','rev-parse',f'{TAG}^{{commit}}'],cwd=av,text=True).strip()
    if head != pinned:
        p.error(f'Requires {TAG} ({pinned}), found HEAD {head}')
    cert = args.cert_sha256.replace(':','').upper()
    if len(cert) != 64 or any(c not in '0123456789ABCDEF' for c in cert):
        p.error('Expected a 64-digit certificate SHA-256')
    changes = []
    for relative, transform in TRANSFORMS.items():
        path = av / relative
        before = path.read_text()
        pristine = subprocess.check_output(['git','show',f'{TAG}:{relative}'],cwd=av,text=True)
        after = transform(pristine)
        if before not in (pristine, after):
            p.error(f'Refusing to overwrite existing edits in {path}')
        changes.append((path,after))
        print(''.join(difflib.unified_diff(before.splitlines(True),after.splitlines(True),fromfile='a/'+relative,tofile='b/'+relative)),end='')
    sepolicy = root / 'system/sepolicy'
    contexts = sepolicy / 'private/property_contexts'
    original = subprocess.check_output(['git','show',f'{TAG}:private/property_contexts'],cwd=sepolicy,text=True)
    after = original.rstrip() + '\n\n# Virtual Camera Pro controlled product\n' + (REPO/'aosp/build-integration/sepolicy/property_contexts').read_text()
    if contexts.read_text() not in (original, after):
        p.error(f'Refusing to overwrite unrelated edits in {contexts}')
    changes.append((contexts,after))
    print(''.join(difflib.unified_diff(contexts.read_text().splitlines(True), after.splitlines(True), fromfile='a/system/sepolicy/private/property_contexts', tofile='b/system/sepolicy/private/property_contexts')),end='')
    policy = sepolicy/'private/virtualcamerapro.te'
    policy_text = (REPO/'aosp/build-integration/sepolicy/vcam.te').read_text()
    if policy.exists() and policy.read_text() != policy_text:
        p.error(f'Refusing to overwrite unrelated policy {policy}')
    changes.append((policy,policy_text))
    print(''.join(difflib.unified_diff((policy.read_text() if policy.exists() else '').splitlines(True), policy_text.splitlines(True), fromfile='a/system/sepolicy/private/virtualcamerapro.te', tofile='b/system/sepolicy/private/virtualcamerapro.te')),end='')
    if not args.apply:
        print('\nReview the diff, then rerun with --apply to install source and patches.')
        return
    dest = root / 'vendor/virtualcamerapro'
    if dest.exists() and not (dest / 'virtualcamerapro-source.json').exists():
        p.error(f'Refusing to overwrite unrelated directory {dest}')
    shutil.copytree(REPO/'aosp',dest,dirs_exist_ok=True)
    shutil.copytree(REPO/'native',dest/'native',dirs_exist_ok=True)
    resource = dest/'system-service/res/values/controller_certs.xml'
    resource.parent.mkdir(parents=True,exist_ok=True)
    resource.write_text(f'<resources><string-array name="controller_certs"><item>{cert}</item></string-array></resources>\n')
    for path,after in changes:
        path.write_text(after)
    shutil.copy2(REPO/'aosp/camera-routing/VirtualCameraProRoute.h',av/'services/camera/libcameraservice/VirtualCameraProRoute.h')
    (dest/'virtualcamerapro-source.json').write_text(json.dumps({'tag':TAG,'protocol':2,'controllerCertificate':cert,'routingHeaderSha256':hashlib.sha256((REPO/'aosp/camera-routing/VirtualCameraProRoute.h').read_bytes()).hexdigest()},indent=2)+'\n')
    print(f'\nInstalled {dest}. Reference lunch: vcam_cf_x86_64_phone-trunk_staging-userdebug')


if __name__ == '__main__':
    main()
