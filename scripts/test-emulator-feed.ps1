param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [ValidateSet('handshake','play','default')][string]$Mode = 'handshake',
    [int]$HoldSeconds = 15
)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw 'Select an isolated Android emulator; this experiment never targets a physical phone' }
$repo = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$sdk = $env:ANDROID_HOME
if (!$sdk) { throw 'ANDROID_HOME must identify the Android SDK' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (((& $adb '-s' $Serial 'shell' 'getprop ro.kernel.qemu') -join '').Trim() -ne '1') { throw 'The target is not an Android emulator' }
$clang = Join-Path $sdk 'ndk\27.1.12297006\toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe'
$output = Join-Path $repo 'artifacts\offworld-feed'
& $clang '--target=x86_64-linux-android28' '-std=c++17' '-O2' '-Wall' '-Wextra' '-Werror' '-static-libstdc++' (Join-Path $repo 'native\emulator-feed\offworld-feed.cpp') '-o' $output
if ($LASTEXITCODE) { throw 'Native Offworld proof build failed' }
& $adb '-s' $Serial 'push' $output '/data/local/tmp/offworld-feed'
if ($LASTEXITCODE) { throw 'Push failed' }
& $adb '-s' $Serial 'shell' 'chmod 755 /data/local/tmp/offworld-feed'
if ($LASTEXITCODE) { throw 'chmod failed' }
if ($Mode -eq 'play') {
    $video = Join-Path $repo 'android\camera-client\src\androidTest\assets\red-blue-2s.mp4'
    & $adb '-s' $Serial 'push' $video '/data/local/tmp/offworld-red-blue.mp4'
    if ($LASTEXITCODE) { throw 'Fixture push failed' }
    & $adb '-s' $Serial 'shell' '/data/local/tmp/offworld-feed' 'play' '/data/local/tmp/offworld-red-blue.mp4' $HoldSeconds
} else {
    & $adb '-s' $Serial 'shell' '/data/local/tmp/offworld-feed' $Mode
}
if ($LASTEXITCODE) { throw "Offworld $Mode proof failed" }
