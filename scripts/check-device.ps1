param([string]$Serial = '')
$ErrorActionPreference = 'Stop'
if ($Serial) { $env:ANDROID_SERIAL = $Serial }
adb wait-for-device
adb shell getprop ro.build.fingerprint
adb shell getprop ro.virtualcamera.pro.version
adb shell service check virtual_camera
adb shell pm path com.virtualcamerapro.broker
adb shell getprop sys.vcam.route
adb shell dumpsys media.camera
