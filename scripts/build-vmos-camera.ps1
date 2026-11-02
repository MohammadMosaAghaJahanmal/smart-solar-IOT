param(
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$NdkVersion = '27.1.12297006'
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (!$Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$clang = Join-Path $Sdk "ndk\$NdkVersion\toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe"
if (!(Test-Path -LiteralPath $clang)) { throw "Android NDK compiler is missing: $clang" }
$directory = Join-Path $repo 'artifacts\vmos'
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$output = Join-Path $directory 'vmos-camera-proxy'
& $clang '--target=aarch64-linux-android28' '-std=c++17' '-O2' '-Wall' '-Wextra' '-Werror' '-fPIE' '-pie' '-static-libstdc++' '-pthread' (Join-Path $repo 'native\vmos-camera\vmos-camera-proxy.cpp') '-o' $output
if ($LASTEXITCODE) { throw 'VMOS ARM64 guest camera proxy build failed' }
[pscustomobject]@{
    Path = $output
    Bytes = (Get-Item -LiteralPath $output).Length
    Sha256 = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
} | Format-List
