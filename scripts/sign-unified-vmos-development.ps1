param(
    [Parameter(Mandatory = $true)][string]$InputApk,
    [string]$OutputDirectory,
    [switch]$AcknowledgeDevelopmentPlatformTrust
)

# Explicit development experiment for the public-test-key VMOS Android 9 guest.
# This script never installs an APK or replaces artifacts/VirtualCameraPro.apk.
$ErrorActionPreference = 'Stop'
if (-not $AcknowledgeDevelopmentPlatformTrust) {
    throw 'This candidate intentionally adds guest platform signing ancestry. Supply -AcknowledgeDevelopmentPlatformTrust only after reviewing docs/VMOS_DEVELOPMENT_ROOT_AUTHORIZATION.md.'
}
$workspace = [System.IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$inputFile = (Resolve-Path -LiteralPath $InputApk).Path
$developmentRoot = Join-Path $workspace 'artifacts\unified\development'
if (-not $OutputDirectory) { $OutputDirectory = $developmentRoot }
$outputRoot = [System.IO.Path]::GetFullPath($OutputDirectory)
if ($outputRoot -ne $developmentRoot -and -not $outputRoot.StartsWith($developmentRoot + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Development signing output must remain inside artifacts/unified/development.'
}
$outputFile = Join-Path $outputRoot 'VirtualCameraPro-VmosDevelopment-RootAuthorization.apk'
if ([System.StringComparer]::OrdinalIgnoreCase.Equals($inputFile, $outputFile)) { throw 'Input and output APK paths must differ.' }
if (Test-Path -LiteralPath $outputFile) { throw 'A development candidate already exists. Select a new output subdirectory; prior candidates are preserved.' }
$java = Join-Path 'C:\Program Files\Java\jdk-17' 'bin\java.exe'
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$signer = Join-Path $sdk 'build-tools\36.0.0\lib\apksigner.jar'
$aapt = Join-Path $sdk 'build-tools\36.0.0\aapt.exe'
$debugKey = Join-Path $workspace 'android\app\debug.keystore'
$platformKey = Join-Path $workspace 'tools\test-platform-key\platform.pk8'
$platformCertificate = Join-Path $workspace 'tools\test-platform-key\platform.x509.pem'
foreach ($path in @($java, $signer, $aapt, $debugKey, $platformKey, $platformCertificate)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required development signing resource is missing: $path" }
}
$protectedCanonical = Join-Path $workspace 'artifacts\VirtualCameraPro.apk'
$canonicalBefore = if (Test-Path -LiteralPath $protectedCanonical) { (Get-FileHash -LiteralPath $protectedCanonical -Algorithm SHA256).Hash } else { $null }
$inputHash = (Get-FileHash -LiteralPath $inputFile -Algorithm SHA256).Hash.ToLowerInvariant()
$debugCertificateSha = 'fac61745dc0903786fb9ede62a962b399f7348f0bb6f899b8332667591033b9c'
$platformCertificateSha = 'c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8'
$inputSignatureInfo = (& $java -jar $signer verify --min-sdk-version 28 --print-certs $inputFile 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $inputSignatureInfo -notmatch "Signer #1 certificate SHA-256 digest: $debugCertificateSha") {
    throw 'Input must be the reviewed integrated APK signed by the existing application debug certificate.'
}
$manifest = (& $aapt dump badging $inputFile 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) { throw 'Input APK manifest could not be inspected.' }
if ($manifest -notmatch "package: name='com.virtualcamerapro'") { throw 'Only the integrated Virtual Camera Pro package may be signed by this script.' }
foreach ($permission in @('android.permission.REQUEST_SUPERUSER', 'android.permission.START_ANY_ACTIVITY')) {
    if ($manifest -notmatch [regex]::Escape("uses-permission: name='$permission'")) { throw "Input APK does not declare required development bootstrap permission: $permission" }
}
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
$lineage = Join-Path $outputRoot 'platform-to-debug-root-authorization.lineage'
if (Test-Path -LiteralPath $lineage) { throw 'A signing lineage already exists in this output directory; preserve it and select a new subdirectory.' }

# The current signer remains the existing debug key. The platform certificate
# becomes its explicit ancestor. Default capabilities avoid claiming an untested
# custom-capability mitigation for the Android-before-12 lineage capability bug.
& $java -jar $signer rotate --out $lineage --old-signer --key $platformKey --cert $platformCertificate --new-signer --ks $debugKey --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android
if ($LASTEXITCODE -ne 0) { throw 'Development signing lineage generation failed.' }
$lineageInfo = (& $java -jar $signer lineage --in $lineage --print-certs 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) { throw 'Development signing lineage could not be inspected.' }
if ($lineageInfo -notmatch $debugCertificateSha -or $lineageInfo -notmatch $platformCertificateSha) { throw 'Signing lineage certificates do not match the explicitly reviewed development identities.' }

# V3-only: Android 9/API28 and later. Older signature schemes would expose the
# oldest platform signer and are intentionally disabled for this experiment.
& $java -jar $signer sign --out $outputFile --min-sdk-version 28 --rotation-min-sdk-version 28 --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true --v4-signing-enabled false --lineage $lineage --key $platformKey --cert $platformCertificate --next-signer --ks $debugKey --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android $inputFile
if ($LASTEXITCODE -ne 0) { throw 'Development candidate signing failed; no canonical APK was changed.' }
$verification = (& $java -jar $signer verify --min-sdk-version 28 --verbose --print-certs $outputFile 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) { throw 'Development candidate signature verification failed.' }
if ($verification -notmatch "Signer #1 certificate SHA-256 digest: $debugCertificateSha" -or $verification -notmatch 'Verified using v3 scheme.*: true') {
    throw 'Development candidate did not retain the expected current debug signer and V3 signature.'
}
if ($verification -match 'Verified using v[12] scheme.*: true') { throw 'Unexpected legacy signing scheme in the V3-only development candidate.' }
if ((Get-FileHash -LiteralPath $inputFile -Algorithm SHA256).Hash.ToLowerInvariant() -ne $inputHash) { throw 'Input APK unexpectedly changed during signing.' }
if ($null -ne $canonicalBefore -and (Get-FileHash -LiteralPath $protectedCanonical -Algorithm SHA256).Hash -ne $canonicalBefore) { throw 'Protected canonical APK unexpectedly changed.' }

[System.IO.File]::WriteAllText((Join-Path $outputRoot 'signature-verification.txt'), $verification + "`n" + $lineageInfo, [System.Text.UTF8Encoding]::new($false))
$report = [ordered]@{
    schemaVersion = 1
    kind = 'DEVELOPMENT_ONLY_VMOS_ROOT_AUTHORIZATION'
    inputApk = $inputFile
    inputSha256 = $inputHash
    candidateApk = $outputFile
    candidateSha256 = (Get-FileHash -LiteralPath $outputFile -Algorithm SHA256).Hash.ToLowerInvariant()
    lineageSha256 = (Get-FileHash -LiteralPath $lineage -Algorithm SHA256).Hash.ToLowerInvariant()
    currentSignerSha256 = $debugCertificateSha
    platformAncestorSha256 = $platformCertificateSha
    signatureMinimumApi = 28
    v3Only = $true
    trustScope = 'Intentional platform ancestry on the exact public-test-key VMOS guest; current debug signer and ordinary application UID are retained. This adds signature permissions requested by the APK in that guest.'
    normalSuperuserConsentRequired = $true
    automaticPolicyGrant = $false
    rootDatabaseWritesByApplication = $false
    physicalPhoneRootProvided = $false
    otherGuestSigningKeysSupported = $false
    canonicalApkChanged = $false
    installed = $false
    actualGuestPermissionAndRootBootstrapVerified = $false
    createdUtc = [DateTime]::UtcNow.ToString('o')
}
[System.IO.File]::WriteAllText((Join-Path $outputRoot 'development-signing-report.json'), ($report | ConvertTo-Json -Depth 8), [System.Text.UTF8Encoding]::new($false))
Write-Output "Development candidate created: $outputFile"
Write-Output 'This is not the canonical production APK. Actual guest permission, normal Superuser consent and camera regression verification remain required.'
