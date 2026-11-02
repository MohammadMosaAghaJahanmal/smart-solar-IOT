param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$root"
if (-not $SkipBuild) {
    Push-Location (Join-Path $root 'android')
    try { & .\gradlew.bat :vmos-bridge:assembleDebug --console=plain; if ($LASTEXITCODE -ne 0) { throw 'Bridge build failed' } }
    finally { Pop-Location }
}
$source = Join-Path $root 'android\vmos-bridge\build\outputs\apk\debug\vmos-bridge-debug.apk'
$destination = Join-Path $root 'artifacts\vmos\VirtualCameraProVmosBridge.apk'
$key = Join-Path $root 'tools\test-platform-key\platform.pk8'
$certificate = Join-Path $root 'tools\test-platform-key\platform.x509.pem'
$signer = Join-Path $sdk 'build-tools\36.0.0\lib\apksigner.jar'
New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
& (Join-Path $env:JAVA_HOME 'bin\java.exe') -jar $signer sign --key $key --cert $certificate --out $destination $source
if ($LASTEXITCODE -ne 0) { throw 'Bridge signing failed' }
& (Join-Path $env:JAVA_HOME 'bin\java.exe') -jar $signer verify --print-certs $destination
if ($LASTEXITCODE -ne 0) { throw 'Bridge signature verification failed' }
Write-Output $destination
