import importlib.util
import pathlib
import subprocess
import tempfile
import unittest
import difflib
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('integration', ROOT/'scripts/integrate-aosp.py')
integration = importlib.util.module_from_spec(spec)
spec.loader.exec_module(integration)

class IntegrationTest(unittest.TestCase):
    def test_privileged_permission_allowlist_is_installed(self):
        manifest = ET.parse(ROOT/'aosp/system-service/AndroidManifest.xml')
        permission = 'android.permission.DUMP'
        android = '{http://schemas.android.com/apk/res/android}'
        self.assertTrue(any(node.get(android+'name') == permission for node in manifest.iter('uses-permission')))
        allowlist = ET.parse(ROOT/'aosp/build-integration/privapp-permissions.xml')
        package = allowlist.find(".//privapp-permissions[@package='com.virtualcamerapro.broker']")
        self.assertIsNotNone(package)
        self.assertIn(permission, [node.get('name') for node in package.findall('permission')])
        product = (ROOT/'aosp/build-integration/virtualcamera-pro.mk').read_text()
        self.assertIn('privapp-permissions.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/', product)
        installer = (ROOT/'scripts/install-reference-broker.ps1').read_text()
        self.assertLess(installer.index("aosp\\build-integration\\privapp-permissions.xml"), installer.index("@('push',$signed,"))
    def test_patch_preserves_nondefault_device_mapping(self):
        fixture = '''#include "CameraService.h"
    const char* kUnknownPackageName = "<unknown>";
std::optional<std::string> CameraService::resolveCameraId() {
        return inputCameraId;
    return mVirtualDeviceCameraIdMapper.getActualCameraId(deviceId, inputCameraId);
}
Status CameraService::getCameraInfo() {}
    return (*cameraIds)[cameraIdInt];
status_t CameraService::dump(int fd, const Vector<String16>& args) {
    bool locked = tryLock(mServiceLock);
}
'''
        result = integration.transform_camera(fixture)
        self.assertIn('return resolveVirtualCameraPro(inputCameraId);',result)
        self.assertIn('return resolveVirtualCameraPro((*cameraIds)[cameraIdInt]);',result)
        self.assertIn('return mVirtualDeviceCameraIdMapper.getActualCameraId(deviceId, inputCameraId);',result)
        self.assertIn('--virtual-camera-pro',result)
        self.assertIn('getCameraState(route->synthetic)',result)
    def test_patch_rejects_source_drift(self):
        with self.assertRaises((AssertionError,ValueError)):
            integration.transform_camera('An incompatible CameraService implementation')
    def test_pinned_patch_applies_to_clean_upstream(self):
        source = ROOT/'tools/upstream'
        if not (source/'CameraService.cpp').exists():
            self.skipTest('Optional downloaded upstream source is absent')
        with tempfile.TemporaryDirectory() as temp:
            root=pathlib.Path(temp)
            generated = []
            for relative in integration.TRANSFORMS:
                dest=root/relative;dest.parent.mkdir(parents=True,exist_ok=True)
                pristine = (source/pathlib.Path(relative).name).read_text()
                dest.write_text(pristine)
                patched = integration.TRANSFORMS[relative](pristine)
                generated.append(''.join(difflib.unified_diff(pristine.splitlines(True), patched.splitlines(True), fromfile='a/'+relative, tofile='b/'+relative)))
                if relative.endswith('/CameraService.cpp'):
                    start = patched.index('status_t CameraService::dump(')
                    self.assertLess(patched.index('Permission Denial:', start), patched.index('--virtual-camera-pro', start))
            self.assertEqual(''.join(generated), (ROOT/'aosp/camera-routing/frameworks-av.patch').read_text())
            subprocess.run(['git','apply','--check',str(ROOT/'aosp/camera-routing/frameworks-av.patch')],cwd=root,check=True,capture_output=True)

if __name__ == '__main__': unittest.main()
