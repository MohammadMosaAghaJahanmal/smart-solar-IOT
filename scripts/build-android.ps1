param([string]$JavaHome = 'C:\Program Files\Java\jdk-17')
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = $JavaHome
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$((Resolve-Path (Join-Path $PSScriptRoot '..')).Path)"
Push-Location (Join-Path $PSScriptRoot '..\android')
try {
    & .\gradlew.bat :app:assembleDebug :app:assembleStandalone :app:assembleStandaloneAndroidTest :broker:assembleDebug :emulator-broker:assembleDebug :system-tests:assembleDebug :camera-client:assembleDebug :camera-client:assembleDebugAndroidTest :vmos-broker:assembleDebug :vmos-broker:assembleDebugAndroidTest :guest-tools:assembleDebug :vmos-bridge:assembleDebug --max-workers=3
    if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
} finally { Pop-Location }
