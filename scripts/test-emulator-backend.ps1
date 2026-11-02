param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [ValidatePattern('^[A-Za-z0-9_]{1,32}$')][string]$CameraId = '10'
)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw 'Select an explicit Android emulator serial; this runner never targets a physical device' }
$repo = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$adbPath = (Get-Command adb -CommandType Application -ErrorAction Stop).Source
$artifactRoot = Join-Path $repo 'artifacts'
$evidence = Join-Path $artifactRoot 'emulator-acceptance'
New-Item -ItemType Directory -Force -Path $evidence | Out-Null

function Protect-EmulatorLog([string]$Text) {
    # Never read connection files. Redact credentials if an exception includes one.
    $Text = $Text -replace '(?i)(Bearer\s+)[A-Za-z0-9._~+/-]+=*', '$1[REDACTED]'
    $Text = $Text -replace '(?i)("(?:token|secret|authorization)"\s*:\s*")[^"]*(")', '$1[REDACTED]$2'
    return $Text -replace '(?i)(\b(?:token|secret|authorization)\s*=\s*)[^\s&,;]+', '$1[REDACTED]'
}
function ConvertTo-EmulatorProcessArgument([string]$Value) {
    if ($Value -ne '' -and $Value -notmatch '[\s"]') { return $Value }
    return '"' + (($Value -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"'
}
function Invoke-EmulatorAdbProcess {
    param([Parameter(Mandatory=$true)][string[]]$Arguments, [int]$TimeoutSeconds = 30)
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $adbPath
    $start.Arguments = (@('-s', $Serial) + $Arguments | ForEach-Object { ConvertTo-EmulatorProcessArgument $_ }) -join ' '
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $start
    try {
        if (!$process.Start()) { throw 'Could not start ADB' }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $timedOut = !$process.WaitForExit([Math]::Max(1, $TimeoutSeconds) * 1000)
        if ($timedOut) {
            try { $process.Kill() } catch { }
            $null = $process.WaitForExit(2000)
        }
        $lines = @()
        foreach ($reader in @($stdout, $stderr)) {
            if ($reader.Wait(2000) -and !$reader.IsFaulted) { $lines += $reader.Result }
            else { $lines += 'ADB output pipe did not close within two seconds' }
        }
        if ($timedOut) { $lines += "ADB command timed out after $TimeoutSeconds seconds" }
        $exitCode = -1
        if (!$timedOut -and $process.HasExited) { $exitCode = $process.ExitCode }
        return [pscustomobject]@{ ExitCode = $exitCode; TimedOut = $timedOut; Text = (Protect-EmulatorLog ($lines -join "`n")).Trim() }
    } finally { $process.Dispose() }
}
function Invoke-EmulatorAdb {
    param([Parameter(Mandatory=$true)][string[]]$Arguments, [int]$TimeoutSeconds = 30)
    $response = Invoke-EmulatorAdbProcess -Arguments $Arguments -TimeoutSeconds $TimeoutSeconds
    if ($response.ExitCode -ne 0) { throw "ADB failed: $($Arguments -join ' ')`n$($response.Text)" }
    return $response.Text
}

if ((Invoke-EmulatorAdb -Arguments @('shell','getprop','ro.kernel.qemu')).Trim() -ne '1') {
    throw 'The selected device is not an Android emulator'
}
$apks = @(
    (Join-Path $repo 'android\app\build\outputs\apk\standalone\app-standalone.apk'),
    (Join-Path $repo 'android\app\build\outputs\apk\androidTest\standalone\app-standalone-androidTest.apk'),
    (Join-Path $repo 'android\camera-client\build\outputs\apk\debug\camera-client-debug.apk'),
    (Join-Path $repo 'android\camera-client\build\outputs\apk\androidTest\debug\camera-client-debug-androidTest.apk')
)
foreach ($apk in $apks) {
    if (!(Test-Path -LiteralPath $apk -PathType Leaf)) { throw "Build the controller and independent-client APKs and instrumentation first: $apk" }
}
$remoteEvidence = '/sdcard/Android/data/com.virtualcamerapro/files/emulator-acceptance'
$acceptancePath = Join-Path $evidence 'acceptance.json'
$testPath = Join-Path $evidence 'instrumentation.txt'
$result = $null
$failure = $null
$collection = @()
# These placeholders prevent an earlier successful run from passing a failed rerun.
'{"passed":false,"stage":"runner-started","failure":"No acceptance proof collected in this run"}' |
    Set-Content -LiteralPath $acceptancePath -Encoding utf8
'Instrumentation was not started' | Set-Content -LiteralPath $testPath -Encoding utf8
try {
    foreach ($apk in $apks) {
        Invoke-EmulatorAdb -Arguments @('install','-t','-r',$apk) -TimeoutSeconds 60 | Write-Output
    }
    Invoke-EmulatorAdb -Arguments @('shell','am','force-stop','com.virtualcamerapro') | Write-Output
    Invoke-EmulatorAdb -Arguments @('shell','am','force-stop','com.virtualcamerapro.client') | Write-Output
    # Remove only the old summary; existing photos remain available for diagnosis.
    Invoke-EmulatorAdb -Arguments @('shell','rm','-f',($remoteEvidence + '/acceptance.json')) | Write-Output
    Invoke-EmulatorAdb -Arguments @('logcat','-c') | Write-Output
    $result = Invoke-EmulatorAdbProcess -Arguments @(
        'shell','am','instrument','-w','-r','-e','class',
        'com.virtualcamerapro.camera.EmulatorBackendAcceptanceTest',
        '-e','emulatorBackend','true','-e','cameraId',$CameraId,
        'com.virtualcamerapro.test/androidx.test.runner.AndroidJUnitRunner'
    ) -TimeoutSeconds 180
    $result.Text | Tee-Object -FilePath $testPath | Write-Output
    if ($result.TimedOut) {
        $failure = 'Acceptance instrumentation exceeded its 180-second limit'
        # Stop only this runner's two test apps; leave the broker and emulator intact.
        foreach ($package in @('com.virtualcamerapro.client', 'com.virtualcamerapro')) {
            $stop = Invoke-EmulatorAdbProcess -Arguments @('shell','am','force-stop',$package) -TimeoutSeconds 5
            $collection += "Timeout cleanup $package exit=$($stop.ExitCode)`n$($stop.Text)"
        }
    }
} catch { $failure = Protect-EmulatorLog $_.Exception.Message }
finally {
    try {
        # Pulling the named folder into artifacts merges it into artifacts/emulator-acceptance.
        $pull = Invoke-EmulatorAdbProcess -Arguments @('pull',$remoteEvidence,$artifactRoot) -TimeoutSeconds 30
        $collection += "Evidence pull exit=$($pull.ExitCode)`n$($pull.Text)"
        if ($pull.ExitCode -ne 0 -and !$failure) { $failure = 'Acceptance evidence could not be collected from the emulator' }
    } catch { $collection += "Evidence pull failed: $(Protect-EmulatorLog $_.Exception.Message)" }
    try {
        $logcat = Invoke-EmulatorAdbProcess -Arguments @('logcat','-d','-v','threadtime') -TimeoutSeconds 15
        $logcat.Text | Set-Content -LiteralPath (Join-Path $evidence 'logcat.txt') -Encoding utf8
        $collection += "Logcat collection exit=$($logcat.ExitCode)"
    } catch { $collection += "Logcat collection failed: $(Protect-EmulatorLog $_.Exception.Message)" }
    # Pulled checkpoints contain no connection configuration; still redact credentials
    # defensively in textual failure diagnostics before they are shared.
    foreach ($file in Get-ChildItem -LiteralPath $evidence -File -Recurse) {
        if ($file.Extension -in @('.json', '.txt', '.log')) {
            $text = Get-Content -LiteralPath $file.FullName -Raw
            $protected = Protect-EmulatorLog $text
            if ($protected -cne $text) { $protected | Set-Content -LiteralPath $file.FullName -Encoding utf8 }
        }
    }
    $collection | Set-Content -LiteralPath (Join-Path $evidence 'collection.txt') -Encoding utf8
}
$instrumentationPassed = $result -and $result.ExitCode -eq 0 -and
    $result.Text -match 'OK \(1 test\)' -and
    $result.Text -notmatch 'FAILURES|INSTRUMENTATION_FAILED|Process crashed'
$acceptancePassed = $false
try {
    $acceptance = Get-Content -LiteralPath $acceptancePath -Raw | ConvertFrom-Json
    $acceptancePassed = $acceptance.passed -eq $true -and $acceptance.stage -eq 'complete'
} catch { if (!$failure) { $failure = 'The acceptance summary is missing or invalid JSON' } }
$passed = !$failure -and $instrumentationPassed -and $acceptancePassed
[ordered]@{
    serial = $Serial; cameraId = $CameraId
    instrumentationPassed = [bool]$instrumentationPassed; acceptancePassed = [bool]$acceptancePassed
    passed = [bool]$passed; failure = $failure
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence 'runner-result.json') -Encoding utf8
if (!$passed) { throw "Emulator backend acceptance failed. Inspect $evidence. $failure" }
Write-Output "Controller, independent camera clients, original Camera, background video, and restoration passed. Evidence: $evidence"
