param([ValidateSet('physical','image','video')][string]$Mode = 'physical', [string]$CameraId = '0', [string]$Serial = '')
$ErrorActionPreference = 'Stop'
if ($Serial) { $env:ANDROID_SERIAL = $Serial }
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
adb wait-for-device
if ($Mode -ne 'physical') {
    $version = (adb shell getprop ro.virtualcamera.pro.version).Trim()
    $route = (adb shell getprop sys.vcam.route).Trim()
    if ($version -ne '2' -or !$route.StartsWith("$CameraId,v0_")) { throw 'Activate the selected fixture on the protocol 2 AOSP device first.' }
}
adb install -r "$root\android\camera-client\build\outputs\apk\debug\camera-client-debug.apk"
if ($LASTEXITCODE -ne 0) { throw 'Failed to install the independent camera client' }
adb install -r "$root\android\camera-client\build\outputs\apk\androidTest\debug\camera-client-debug-androidTest.apk"
if ($LASTEXITCODE -ne 0) { throw 'Failed to install camera instrumentation tests' }
adb shell pm grant com.virtualcamerapro.client android.permission.CAMERA
$extra = @('-e','cameraId',$CameraId)
if ($Mode -eq 'image') { $extra += @('-e','expectedRgb','FF0000') }
if ($Mode -eq 'video') { $extra += @('-e','video','true') }
$result = adb shell am instrument -w @extra com.virtualcamerapro.client.test/androidx.test.runner.AndroidJUnitRunner
$result | Write-Output
$report = $result -join "`n"
if ($report -notmatch 'OK \(' -or $report -match 'FAILURES|INSTRUMENTATION_FAILED') { throw 'Camera acceptance tests failed' }
