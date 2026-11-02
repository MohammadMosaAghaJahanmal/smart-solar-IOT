param([string]$Sdk=$env:ANDROID_HOME,[string]$NdkVersion='27.1.12297006')
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (!$Sdk) { $Sdk=Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$clang=Join-Path $Sdk "ndk\$NdkVersion\toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe"
if (!(Test-Path -LiteralPath $clang)) { throw "Android NDK compiler is missing: $clang" }
$out=Join-Path $repo 'artifacts\vmos\jpeg-color\vcam-jpegutil-probe'
& $clang '--target=aarch64-linux-android28' '-std=c++17' '-O2' '-Wall' '-Wextra' '-Werror' '-fPIE' '-pie' '-static-libstdc++' (Join-Path $repo 'native\vmos-jpeg-color\jpegutil-probe.cpp') '-ldl' '-o' $out
if ($LASTEXITCODE) { throw 'Exact stock JPEG utility diagnostic build failed' }
[pscustomobject]@{Path=$out;Bytes=(Get-Item -LiteralPath $out).Length;Sha256=(Get-FileHash -Algorithm SHA256 -LiteralPath $out).Hash.ToLowerInvariant()} | Format-List
