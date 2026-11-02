param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [string]$ConnectionFile = ''
)
$ErrorActionPreference = 'Stop'
if ($Serial -notmatch '^emulator-\d+$') { throw 'Select an explicit Android emulator serial' }
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (!$ConnectionFile) { $ConnectionFile = Join-Path $root 'artifacts\emulator-host\connection.json' }
$ConnectionFile = (Resolve-Path -LiteralPath $ConnectionFile).Path
$connection = Get-Content -LiteralPath $ConnectionFile -Raw | ConvertFrom-Json
$url = [Uri]$connection.url
if ($connection.backend -ne 'android_emulator_environment' -or $url.Scheme -ne 'http' -or $url.Host -ne '127.0.0.1' -or $url.Port -lt 1024 -or $connection.token.Length -lt 32) {
    throw 'Expected an authenticated local emulator-camera host connection'
}
if ($connection.serial -and $connection.serial -ne $Serial) { throw 'The connection belongs to another emulator' }
$adb = (Get-Command adb -CommandType Application -ErrorAction Stop).Source
function Invoke-CameraAdb([string[]]$Arguments) {
    $quoted = @('-s', $Serial) + $Arguments | ForEach-Object {
        if ($_ -ne '' -and $_ -notmatch '[\s"]') { $_ }
        else { '"' + (($_ -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"' }
    }
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $adb
    $start.Arguments = $quoted -join ' '
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        if (!$process.Start()) { throw 'Could not start ADB' }
        $out = $process.StandardOutput.ReadToEndAsync()
        $err = $process.StandardError.ReadToEndAsync()
        if (!$process.WaitForExit(30000)) { $process.Kill(); throw 'ADB timed out after 30 seconds' }
        if (!$out.Wait(2000) -or !$err.Wait(2000)) { throw 'ADB output did not close' }
        if ($process.ExitCode -ne 0) { throw "ADB failed: $($Arguments -join ' ')`n$($err.Result)" }
        return $out.Result.Trim()
    } finally { $process.Dispose() }
}
if ((Invoke-CameraAdb @('shell','getprop','ro.kernel.qemu')) -ne '1') { throw 'Target is not an Android emulator' }
if ((Invoke-CameraAdb @('shell','id','-u')) -ne '0') { throw 'Run adb -s <serial> root on this isolated emulator first' }
$package = Invoke-CameraAdb @('shell','dumpsys','package','com.virtualcamerapro.broker')
if ($package -notmatch '(?m)^\s*(?:userId|appId)=1000(?:\s|$)') { throw 'Install the platform emulator broker first' }
$support = Invoke-RestMethod -Uri ($connection.url.TrimEnd('/')+'/support') -Headers @{Authorization=('Bearer '+$connection.token)} -TimeoutSec 5
if (!$support.supported -or $support.backend -ne $connection.backend -or $support.cameraId -ne $connection.cameraId) { throw 'The local camera host did not confirm this connection' }
Invoke-CameraAdb @('reverse',('tcp:'+$url.Port),('tcp:'+$url.Port)) | Write-Output
$remote = '/data/user/0/com.virtualcamerapro.broker/files/emulator-connection.json'
Invoke-CameraAdb @('shell','mkdir','-p','/data/user/0/com.virtualcamerapro.broker/files') | Out-Null
Invoke-CameraAdb @('push',$ConnectionFile,$remote) | Write-Output
Invoke-CameraAdb @('shell','chown','1000:1000',$remote) | Out-Null
Invoke-CameraAdb @('shell','chmod','600',$remote) | Out-Null
Invoke-CameraAdb @('shell','restorecon','-R','/data/user/0/com.virtualcamerapro.broker/files') | Out-Null
Invoke-CameraAdb @('shell','am','force-stop','com.virtualcamerapro.broker') | Out-Null
Write-Output "Configured emulator camera $($connection.cameraId) for $Serial through the authenticated local host."
