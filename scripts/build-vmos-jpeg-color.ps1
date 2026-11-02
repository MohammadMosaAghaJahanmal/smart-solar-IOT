param(
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$NdkVersion = '27.1.12297006'
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (!$Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$vcamToolchain = Join-Path $Sdk "ndk\$NdkVersion\toolchains\llvm\prebuilt\windows-x86_64\bin"
$clang = Join-Path $vcamToolchain 'clang++.exe'
if (!(Test-Path -LiteralPath $clang)) { throw "Android NDK compiler is missing: $clang" }
$source = Join-Path $repo 'native\vmos-jpeg-color\jpeg-color-shim.cpp'
$directory = Join-Path $repo 'artifacts\vmos\jpeg-color'
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$output = Join-Path $directory 'camera.vphw90.jpeg.so'
& $clang '--target=aarch64-linux-android28' '-std=c++17' '-O2' '-Wall' '-Wextra' '-Werror' '-fPIC' '-shared' '-fvisibility=hidden' '-static-libstdc++' '-pthread' '-Wl,-soname,libvcam_jpeg_color.so' '-Wl,--exclude-libs,ALL' "-Wl,--version-script=$(Join-Path $repo 'native\vmos-jpeg-color\exports.map')" $source '-ldl' '-llog' '-o' $output
if ($LASTEXITCODE) { throw 'VMOS ARM64 JPEG range shim build failed' }
$bytes = [IO.File]::ReadAllBytes($output)
if ($bytes.Length -lt 64 -or $bytes[0] -ne 127 -or $bytes[1] -ne 69 -or $bytes[2] -ne 76 -or $bytes[3] -ne 70 -or $bytes[4] -ne 2 -or $bytes[5] -ne 1 -or $bytes[18] -ne 183 -or $bytes[19] -ne 0) { throw 'Expected an ELF64 little-endian AArch64 library' }
$hash = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
$manifest = [ordered]@{
    schemaVersion = 1
    profile = 'vmos-android9-arm64-rom10075-jpeg-color'
    status = 'prepared_device_acceptance_required'
    guest = [ordered]@{ api = 28; hardware = 'vphw90'; abi = 'arm64-v8a'; romVersion = 10075 }
    compatibilityPath = '/vendor/lib/hw/camera.vphw90.jpeg.so'
    originalSymlinkTarget = '/vendor/lib64/hw/camera.vphw90.jpeg.so'
    originalEncoderSha256 = '0975abc8d3eec3f57bd607458101f69a11f0a7c93b2de97094cfab534175c2e3'
    originalEncoderBytes = 68120
    candidateFile = 'camera.vphw90.jpeg.so'
    candidateSha256 = $hash
    candidateBytes = $bytes.Length
    soname = 'libvcam_jpeg_color.so'
    maxDimension = 4096
    maxInputBytes = 33554432
    maxOutputBytes = 33554432
    conversion = 'Copy tight NV21; expand Y 16..235 and centered V/U 16..240 into full-range JFIF samples only at the JPEG entry point.'
    limitations = @('The original JpegStub ABI supplies no input or output byte length; the verified HAL caller retains that buffer-capacity contract.', 'Cannot reconstruct missing chroma or guess a padded layout; Camera1 green output still requires separate diagnosis.', 'No proxy, broker, preview stream or original lib64 encoder is changed by this build.')
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 -LiteralPath (Join-Path $directory 'shim.json')
[pscustomobject]@{ Path = $output; Bytes = $bytes.Length; Sha256 = $hash } | Format-List
