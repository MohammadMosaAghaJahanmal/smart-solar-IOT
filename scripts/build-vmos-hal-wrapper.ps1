param([string]$Sdk = $env:ANDROID_HOME, [string]$NdkVersion = '27.1.12297006')
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (!$Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$clang = Join-Path $Sdk "ndk\$NdkVersion\toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe"
if (!(Test-Path -LiteralPath $clang)) { throw "Android NDK compiler is missing: $clang" }
$source = Join-Path $repo 'native\vmos-camera\hal-wrapper'
$output = Join-Path $repo 'artifacts\vmos\camera.vphw90.crop-wrapper.so'
& $clang '--target=aarch64-linux-android28' '-std=c++17' '-O2' '-Wall' '-Wextra' '-Werror' '-Wno-unused-parameter' '-fPIC' '-shared' '-static-libstdc++' '-pthread' '-fvisibility=hidden' '-Wl,-soname,libvcam_camera_crop.so' "-Wl,--version-script=$(Join-Path $source 'exports.map')" '-I' (Join-Path $source 'include') (Join-Path $source 'camera-wrapper.cpp') '-ldl' '-llog' '-o' $output
if ($LASTEXITCODE) { throw 'VMOS ARM64 camera3 crop wrapper build failed' }
$digest = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText("$output.sha256", "$digest`n", [Text.UTF8Encoding]::new($false))
[pscustomobject]@{ Path = $output; Bytes = (Get-Item -LiteralPath $output).Length; Sha256 = $digest } | Format-List
