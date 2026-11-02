param([string]$Sdk=$env:ANDROID_HOME,[string]$NdkVersion='27.1.12297006')
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (!$Sdk) { $Sdk=Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$clang=Join-Path $Sdk "ndk\$NdkVersion\toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe"
if (!(Test-Path -LiteralPath $clang)) { throw "Android NDK compiler missing: $clang" }
$directory=Join-Path $repo 'artifacts\vmos\jpegutil-diagnostic'
New-Item -ItemType Directory -Force -Path $directory | Out-Null
$out=Join-Path $directory 'libjni_jpegutil.so'
& $clang '--target=aarch64-linux-android28' '-std=c++17' '-O2' '-Wall' '-Wextra' '-Werror' '-fPIC' '-shared' '-fvisibility=hidden' '-static-libstdc++' '-pthread' '-Wl,-soname,libvcam_jpegutil_diagnostic.so' '-Wl,--exclude-libs,ALL' "-Wl,--version-script=$(Join-Path $repo 'native\vmos-jpegutil-diagnostic\exports.map')" (Join-Path $repo 'native\vmos-jpegutil-diagnostic\jpegutil-diagnostic.cpp') '-ldl' '-llog' '-o' $out
if ($LASTEXITCODE) { throw 'Exact stock JPEG JNI diagnostic build failed' }
$hash=(Get-FileHash -Algorithm SHA256 -LiteralPath $out).Hash.ToLowerInvariant()
$manifest=[ordered]@{profile='vmos-android9-arm64-rom10075-stock-jpegutil-diagnostic';status='temporary_observation_only_device_acceptance_required';target='/system/lib64/libjni_jpegutil.so';originalBackup='/system/lib64/.vcam-original-libjni_jpegutil.so';originalSha256='6050a81c4eae572989b284bc55531a853efe83512b5413b7bffe00fb308c2bd5';originalBytes=465864;candidateSha256=$hash;candidateBytes=(Get-Item -LiteralPath $out).Length;soname='libvcam_jpegutil_diagnostic.so';exports=@('Java_com_android_camera_util_JpegUtilNative_compressJpegFromYUV420pNative','Java_com_android_camera_util_JpegUtilNative_copyImagePlaneToBitmap');jniOnLoad='Absent in both original and observer';observation='At most256 JPEG calls per process;64 bounded samples per plane; aggregate statistics only; original jobjects and every argument forwarded unchanged.'}
$manifest|ConvertTo-Json -Depth 4|Set-Content -Encoding utf8 -LiteralPath (Join-Path $directory 'diagnostic.json')
[pscustomobject]@{Path=$out;Bytes=$manifest.candidateBytes;Sha256=$hash}|Format-List
