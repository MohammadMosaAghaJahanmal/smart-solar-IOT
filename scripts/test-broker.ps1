param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [string]$ExpectedSupportCode = 'READY'
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ($Serial -notmatch '^emulator-\d+$') { throw 'Select an explicit test emulator serial' }
$adbPath = (Get-Command adb -CommandType Application -ErrorAction Stop).Source
function ConvertTo-BrokerProcessArgument([string]$Value) {
    # Quote Windows process arguments without passing them through a shell.
    if ($Value -ne '' -and $Value -notmatch '[\s"]') { return $Value }
    return '"' + (($Value -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"'
}
function Invoke-BrokerAdbProcess {
    param([Parameter(Mandatory=$true)][string[]]$Arguments, [int]$TimeoutSeconds = 30)
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $adbPath
    $start.Arguments = (@('-s', $Serial) + $Arguments | ForEach-Object { ConvertTo-BrokerProcessArgument $_ }) -join ' '
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
        if (!$process.WaitForExit([Math]::Max(1, $TimeoutSeconds) * 1000)) {
            $process.Kill()
            $null = $process.WaitForExit(2000)
            $partial = @()
            if ($stdout.IsCompleted -and !$stdout.IsFaulted) { $partial += $stdout.Result }
            if ($stderr.IsCompleted -and !$stderr.IsFaulted) { $partial += $stderr.Result }
            $partial += "ADB timed out after $TimeoutSeconds seconds: $($Arguments -join ' ')"
            return [pscustomobject]@{ ExitCode = -1; Text = ($partial -join "`n").Trim() }
        }
        if (!$stdout.Wait(2000) -or !$stderr.Wait(2000)) {
            return [pscustomobject]@{ ExitCode = -1; Text = 'ADB output pipes did not close after the command exited' }
        }
        return [pscustomobject]@{ ExitCode = $process.ExitCode; Text = (($stdout.Result, $stderr.Result) -join "`n").Trim() }
    } finally { $process.Dispose() }
}
function Invoke-BrokerAdb {
    param([Parameter(Mandatory=$true)][string[]]$Arguments, [int]$TimeoutSeconds = 30)
    $response = Invoke-BrokerAdbProcess -Arguments $Arguments -TimeoutSeconds $TimeoutSeconds
    if ($response.ExitCode -ne 0) { throw "ADB failed: $($Arguments -join ' ')`n$($response.Text)" }
    return $response.Text
}
$artifacts = Join-Path $root 'artifacts\broker-reference'
New-Item -ItemType Directory -Force -Path $artifacts | Out-Null
$controller = Join-Path $root 'android\app\build\outputs\apk\standalone\app-standalone.apk'
$tests = Join-Path $root 'android\app\build\outputs\apk\androidTest\standalone\app-standalone-androidTest.apk'
foreach ($apk in @($controller,$tests)) {
    if (!(Test-Path -LiteralPath $apk)) { throw "Build the standalone controller and Android tests first: $apk" }
    Invoke-BrokerAdb -Arguments @('install','-t','-r',$apk) -TimeoutSeconds 60 | Write-Output
}
Invoke-BrokerAdb -Arguments @('shell','am','force-stop','com.virtualcamerapro') | Write-Output
Invoke-BrokerAdb -Arguments @('logcat','-c') | Write-Output
$result = Invoke-BrokerAdbProcess -Arguments @('shell','am','instrument','-w','-r','-e','class','com.virtualcamerapro.camera.BrokerConnectionTest','-e','expectedSupportCode',$ExpectedSupportCode,'com.virtualcamerapro.test/androidx.test.runner.AndroidJUnitRunner') -TimeoutSeconds 90
$result.Text | Tee-Object -FilePath (Join-Path $artifacts 'binder-test.txt') | Write-Output
Invoke-BrokerAdb -Arguments @('logcat','-d','-v','threadtime') | Set-Content -LiteralPath (Join-Path $artifacts 'logcat.txt') -Encoding utf8
Invoke-BrokerAdb -Arguments @('pull','/sdcard/Android/data/com.virtualcamerapro/files/broker-connection.json',(Join-Path $artifacts 'broker-connection.json')) | Write-Output
Invoke-BrokerAdb -Arguments @('pull','/sdcard/Android/data/com.virtualcamerapro/files/broker-lifecycle.json',(Join-Path $artifacts 'broker-lifecycle.json')) | Write-Output
$report = $result.Text
if ($result.ExitCode -ne 0 -or $report -notmatch 'OK \(1 test\)' -or $report -match 'FAILURES|INSTRUMENTATION_FAILED|Process crashed') { throw 'Production broker connection test failed; inspect broker-reference evidence' }
Write-Output "Production broker Binder test passed; reported camera support code is $ExpectedSupportCode."
if ($ExpectedSupportCode -ne 'READY') { Write-Output 'Camera replacement is unavailable in this image; this result proves system broker installation and real controller communication only.' }
