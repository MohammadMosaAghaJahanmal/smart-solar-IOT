param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [ValidateSet('play','seekloop')][string]$Mode = 'play',
    [ValidatePattern('^[A-Za-z0-9_.:/-]{1,100}$')][string]$CameraId = '10',
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$')][string]$ArtifactPrefix = 'offworld-runtime'
)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw 'Select an explicit isolated emulator serial; physical devices are not supported' }
$repo = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$adbPath = (Get-Command adb -CommandType Application -ErrorAction Stop).Source
$artifacts = Join-Path $repo ('artifacts\' + $ArtifactPrefix)
New-Item -ItemType Directory -Force -Path $artifacts | Out-Null

function ConvertTo-RuntimeProcessArgument([string]$Value) {
    # Windows argv quoting; no host shell receives these arguments.
    if ($Value -ne '' -and $Value -notmatch '[\s"]') { return $Value }
    return '"' + (($Value -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"'
}
function Start-RuntimeAdbProcess {
    param([Parameter(Mandatory=$true)][string[]]$Arguments)
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $adbPath
    $start.Arguments = (@('-s', $Serial) + $Arguments | ForEach-Object { ConvertTo-RuntimeProcessArgument $_ }) -join ' '
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $start
    try {
        if (!$process.Start()) { throw 'Could not start ADB' }
        return [pscustomobject]@{
            Process = $process
            Stdout = $process.StandardOutput.ReadToEndAsync()
            Stderr = $process.StandardError.ReadToEndAsync()
            Arguments = $Arguments
        }
    } catch { $process.Dispose(); throw }
}
function Complete-RuntimeAdbProcess {
    param([Parameter(Mandatory=$true)]$Handle, [int]$TimeoutSeconds = 30)
    $timedOut = !$Handle.Process.WaitForExit([Math]::Max(0, $TimeoutSeconds) * 1000)
    if ($timedOut) {
        try { $Handle.Process.Kill() } catch { }
        $null = $Handle.Process.WaitForExit(2000)
    }
    $lines = @()
    foreach ($reader in @($Handle.Stdout, $Handle.Stderr)) {
        if ($reader.Wait(2000) -and !$reader.IsFaulted) { $lines += $reader.Result }
        else { $lines += 'ADB output pipe did not close within two seconds' }
    }
    if ($timedOut) { $lines += "ADB timed out after $TimeoutSeconds seconds: $($Handle.Arguments -join ' ')" }
    $exitCode = -1
    if (!$timedOut -and $Handle.Process.HasExited) { $exitCode = $Handle.Process.ExitCode }
    return [pscustomobject]@{ ExitCode = $exitCode; TimedOut = $timedOut; Text = ($lines -join "`n").Trim() }
}
function Invoke-RuntimeAdbProcess {
    param([Parameter(Mandatory=$true)][string[]]$Arguments, [int]$TimeoutSeconds = 30)
    $handle = Start-RuntimeAdbProcess -Arguments $Arguments
    try { return Complete-RuntimeAdbProcess -Handle $handle -TimeoutSeconds $TimeoutSeconds }
    finally { $handle.Process.Dispose() }
}
function Invoke-RuntimeAdb {
    param([Parameter(Mandatory=$true)][string[]]$Arguments, [int]$TimeoutSeconds = 30)
    $response = Invoke-RuntimeAdbProcess -Arguments $Arguments -TimeoutSeconds $TimeoutSeconds
    if ($response.ExitCode -ne 0) { throw "ADB failed: $($Arguments -join ' ')`n$($response.Text)" }
    return $response.Text
}

if ((Invoke-RuntimeAdb -Arguments @('shell','getprop','ro.kernel.qemu')).Trim() -ne '1') {
    throw 'The selected device is not an Android emulator'
}
if ((Invoke-RuntimeAdb -Arguments @('shell','id','-u')).Trim() -ne '0') {
    throw 'This isolated emulator must already have a root ADB shell; run adb -s <serial> root first'
}
$client = Join-Path $repo 'android\camera-client\build\outputs\apk\debug\camera-client-debug.apk'
$tests = Join-Path $repo 'android\camera-client\build\outputs\apk\androidTest\debug\camera-client-debug-androidTest.apk'
$helper = Join-Path $repo 'artifacts\offworld-feed'
$fixture = Join-Path $repo 'android\camera-client\src\androidTest\assets\red-blue-2s.mp4'
foreach ($file in @($client, $tests, $helper, $fixture)) {
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Required prebuilt input is missing: $file" }
}
$runId = [guid]::NewGuid().ToString('N')
$remoteRoot = '/data/local/tmp/vcam-offworld-runtime-' + $runId
$remoteHelper = $remoteRoot + '/feed'
$remoteFixture = $remoteRoot + '/red-blue-2s.mp4'
$remoteProof = '/sdcard/Android/data/com.virtualcamerapro.client/files/offworld-camera-proof.json'
$proofPath = Join-Path $artifacts 'offworld-camera-proof.json'
$testPath = Join-Path $artifacts 'camera-test.txt'
$helperPath = Join-Path $artifacts 'helper.txt'
$collectionPath = Join-Path $artifacts 'collection.txt'
$helperHandle = $null
$testResult = $null
$failure = $null
$helperText = ''
$collection = @()
# Overwrite prior evidence before starting; a failed pull can never reuse an old proof.
'{"collected":false,"reason":"No proof collected in this run"}' | Set-Content -LiteralPath $proofPath -Encoding utf8
'Instrumentation was not started' | Set-Content -LiteralPath $testPath -Encoding utf8
'Native helper was not started' | Set-Content -LiteralPath $helperPath -Encoding utf8
try {
    foreach ($apk in @($client, $tests)) {
        Invoke-RuntimeAdb -Arguments @('install','-t','-r',$apk) -TimeoutSeconds 60 | Write-Output
    }
    Invoke-RuntimeAdb -Arguments @('shell','am','force-stop','com.virtualcamerapro.client') | Write-Output
    Invoke-RuntimeAdb -Arguments @('shell','rm','-f',$remoteProof) | Write-Output
    Invoke-RuntimeAdb -Arguments @('shell','mkdir','-p',$remoteRoot) | Write-Output
    Invoke-RuntimeAdb -Arguments @('push',$helper,$remoteHelper) | Write-Output
    Invoke-RuntimeAdb -Arguments @('push',$fixture,$remoteFixture) | Write-Output
    Invoke-RuntimeAdb -Arguments @('shell','chmod','755',$remoteHelper) | Write-Output
    # PID belongs to this run's shell, then exec preserves it as the native helper PID.
    # Load is queued before the independent Camera2 consumer opens its camera.
    $command = 'echo $$ > {0}/pid; exec {0}/feed {1} {0}/red-blue-2s.mp4 30' -f $remoteRoot, $Mode
    $helperHandle = Start-RuntimeAdbProcess -Arguments @('shell',$command)
    $testResult = Invoke-RuntimeAdbProcess -Arguments @(
        'shell','am','instrument','-w','-r','-e','class',
        'com.virtualcamerapro.client.OffworldCameraTest',
        '-e','offworld','true','-e','cameraId',$CameraId,
        'com.virtualcamerapro.client.test/androidx.test.runner.AndroidJUnitRunner'
    ) -TimeoutSeconds 45
    $testResult.Text | Tee-Object -FilePath $testPath | Write-Output
} catch { $failure = $_.Exception.Message }
finally {
    # Only signal the PID whose actual executable path matches this unique run.
    # Never kill the emulator, cameraserver, or another feed process.
    if ($helperHandle) {
        $cleanupCommand = @'
if [ -r {ROOT}/pid ]; then
  p=$(cat {ROOT}/pid)
  case "$p" in ''|*[!0-9]*) exit 2;; esac
  if [ -r /proc/$p/cmdline ]; then
    executable=$(tr '\000' '\n' < /proc/$p/cmdline | head -n 1)
    if [ "$executable" = {ROOT}/feed ]; then kill "$p"; fi
  fi
fi
'@.Replace('{ROOT}', $remoteRoot)
        try {
            $cleanup = Invoke-RuntimeAdbProcess -Arguments @('shell',$cleanupCommand) -TimeoutSeconds 5
            $collection += "Helper cleanup exit=$($cleanup.ExitCode)`n$($cleanup.Text)"
        } catch { $collection += "Helper cleanup failed: $($_.Exception.Message)" }
        try {
            $helperResult = Complete-RuntimeAdbProcess -Handle $helperHandle -TimeoutSeconds 3
            $helperText = $helperResult.Text
            $helperText | Set-Content -LiteralPath $helperPath -Encoding utf8
        } catch {
            $collection += "Helper log collection failed: $($_.Exception.Message)"
        } finally { $helperHandle.Process.Dispose() }
    }
    try {
        $pull = Invoke-RuntimeAdbProcess -Arguments @('pull',$remoteProof,$proofPath) -TimeoutSeconds 10
        $collection += "YUV proof pull exit=$($pull.ExitCode)`n$($pull.Text)"
        if ($pull.ExitCode -ne 0 -and !$failure) { $failure = 'The independent camera did not produce a YUV proof file' }
    } catch { $collection += "YUV proof pull failed: $($_.Exception.Message)" }
    try {
        $logcat = Invoke-RuntimeAdbProcess -Arguments @('logcat','-d','-v','threadtime') -TimeoutSeconds 10
        $logcat.Text | Set-Content -LiteralPath (Join-Path $artifacts 'logcat.txt') -Encoding utf8
        $collection += "Logcat collection exit=$($logcat.ExitCode)"
    } catch { $collection += "Logcat collection failed: $($_.Exception.Message)" }
    $collection | Set-Content -LiteralPath $collectionPath -Encoding utf8
}

$loadAcknowledged = $false
$playAcknowledged = $false
foreach ($line in ($helperText -split '\r?\n')) {
    try {
        $event = $line | ConvertFrom-Json -ErrorAction Stop
        if ($event.operation -eq 50 -and $event.acknowledged -eq $true -and $event.completed -eq $true) { $loadAcknowledged = $true }
        if ($event.operation -eq 51 -and $event.acknowledged -eq $true) { $playAcknowledged = $true }
    } catch { } # Non-JSON native diagnostics are preserved in helper.txt.
}
$instrumentationPassed = $testResult -and $testResult.ExitCode -eq 0 -and
    $testResult.Text -match 'OK \(1 test\)' -and
    $testResult.Text -notmatch 'FAILURES|INSTRUMENTATION_FAILED|Process crashed'
$frameProofPassed = $false
try {
    $proof = Get-Content -LiteralPath $proofPath -Raw | ConvertFrom-Json
    $frameProofPassed = $proof.cameraId -eq $CameraId -and $proof.completedObservation -eq $true -and
        $proof.sourceFrameCount -ge 20 -and $proof.sourceSensorSpanNs -ge 5500000000 -and
        $proof.colorTransitions -ge 4 -and !$proof.failure
} catch { if (!$failure) { $failure = 'The YUV proof file is missing or invalid JSON' } }
$passed = !$failure -and $instrumentationPassed -and $frameProofPassed -and $loadAcknowledged -and $playAcknowledged
[ordered]@{
    serial = $Serial; cameraId = $CameraId; mode = $Mode; runId = $runId; remoteRoot = $remoteRoot
    loadAcknowledged = $loadAcknowledged; playAcknowledged = $playAcknowledged
    instrumentationPassed = [bool]$instrumentationPassed; frameProofPassed = [bool]$frameProofPassed
    passed = [bool]$passed; failure = $failure
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $artifacts 'result.json') -Encoding utf8
if (!$passed) { throw "Offworld runtime camera proof failed. Inspect $artifacts. $failure" }
Write-Output "Independent Camera2 YUV proof passed for camera $CameraId in $Mode mode; artifacts: $artifacts"
Write-Output 'This verifies this isolated emulator feed. It does not establish production broker camera routing.'
