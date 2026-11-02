param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [string]$JavaHome = 'C:\Program Files\Java\jdk-17',
    [ValidateSet('aosp','emulator')][string]$BrokerVariant = 'aosp',
    [string]$PlatformKey = '',
    [string]$PlatformCertificate = ''
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ($Serial -notmatch '^emulator-\d+$') { throw 'This installer accepts an isolated Android emulator only; it never modifies a physical phone.' }
if (!$env:ANDROID_HOME) { throw 'Set ANDROID_HOME' }
$env:JAVA_HOME = $JavaHome
if (!$PlatformKey) { $PlatformKey = Join-Path $root 'tools\test-platform-key\platform.pk8' }
if (!$PlatformCertificate) { $PlatformCertificate = Join-Path $root 'tools\test-platform-key\platform.x509.pem' }
foreach ($path in @($PlatformKey,$PlatformCertificate)) { if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing development signing input: $path" } }
$adbPath = (Get-Command adb -CommandType Application -ErrorAction Stop).Source
function ConvertTo-ProcessArgument([string]$Value) {
    # ProcessStartInfo.Arguments uses Windows command-line quoting, not shell quoting.
    if ($Value -ne '' -and $Value -notmatch '[\s"]') { return $Value }
    return '"' + (($Value -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"'
}
function Invoke-ReferenceAdbProcess {
    param([Parameter(Mandatory=$true)][string[]]$Arguments, [int]$TimeoutSeconds = 30)
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $adbPath
    $start.Arguments = (@('-s', $Serial) + $Arguments | ForEach-Object { ConvertTo-ProcessArgument $_ }) -join ' '
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
            # Do not wait indefinitely for a broken ADB client or inherited pipe.
            $null = $process.WaitForExit(2000)
            return [pscustomobject]@{ ExitCode = -1; Text = "ADB timed out after $TimeoutSeconds seconds: $($Arguments -join ' ')" }
        }
        if (!$stdout.Wait(2000) -or !$stderr.Wait(2000)) {
            return [pscustomobject]@{ ExitCode = -1; Text = 'ADB output pipes did not close after the command exited' }
        }
        return [pscustomobject]@{ ExitCode = $process.ExitCode; Text = (($stdout.Result, $stderr.Result) -join "`n").Trim() }
    } finally { $process.Dispose() }
}
function Invoke-ReferenceAdb {
    param([Parameter(Mandatory=$true)][string[]]$Arguments, [int]$RetrySeconds = 0, [int]$TimeoutSeconds = 30)
    $clock = [System.Diagnostics.Stopwatch]::StartNew()
    do {
        $remaining = if ($RetrySeconds -gt 0) { [Math]::Max(1, [Math]::Ceiling($RetrySeconds - $clock.Elapsed.TotalSeconds)) } else { $TimeoutSeconds }
        $result = Invoke-ReferenceAdbProcess -Arguments $Arguments -TimeoutSeconds ([Math]::Min($TimeoutSeconds, $remaining))
        if ($result.ExitCode -eq 0) { return $result.Text }
        $transient = $result.Text -match '(?i)device offline|device .+ not found|no devices/emulators found|transport error|protocol fault|connection reset|cannot connect|error: closed|ADB timed out'
        if (!$transient -or $clock.Elapsed.TotalSeconds -ge $RetrySeconds) { throw "ADB failed: $($Arguments -join ' ')`n$($result.Text)" }
        Start-Sleep -Milliseconds 500
    } while ($clock.Elapsed.TotalSeconds -lt $RetrySeconds)
    throw "ADB did not reconnect within $RetrySeconds seconds: $($Arguments -join ' ')`n$($result.Text)"
}
function Wait-ReferenceBoot {
    param([string]$Reason, [string]$PreviousBootId = '', [int]$TimeoutSeconds = 180)
    $clock = [System.Diagnostics.Stopwatch]::StartNew()
    $last = ''
    while ($clock.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        $remaining = [Math]::Max(1, [Math]::Ceiling($TimeoutSeconds - $clock.Elapsed.TotalSeconds))
        $boot = Invoke-ReferenceAdbProcess -Arguments @('shell','getprop','sys.boot_completed') -TimeoutSeconds ([Math]::Min(5, $remaining))
        $last = $boot.Text
        if ($boot.ExitCode -eq 0 -and $boot.Text -eq '1') {
            if (!$PreviousBootId) { return }
            $remaining = [Math]::Max(1, [Math]::Ceiling($TimeoutSeconds - $clock.Elapsed.TotalSeconds))
            $identity = Invoke-ReferenceAdbProcess -Arguments @('shell','cat','/proc/sys/kernel/random/boot_id') -TimeoutSeconds ([Math]::Min(5, $remaining))
            if ($identity.ExitCode -eq 0 -and $identity.Text -match '^[0-9a-fA-F-]{36}$' -and $identity.Text -ne $PreviousBootId) { return }
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Emulator did not finish booting within $TimeoutSeconds seconds ($Reason). Last ADB response: $last"
}
function Enable-ReferenceRoot {
    $response = Invoke-ReferenceAdb -Arguments @('root') -RetrySeconds 15 -TimeoutSeconds 10
    $response | Write-Output
    if ($response -match '(?i)cannot run as root|not allowed') { throw 'The isolated emulator does not permit adb root' }
    $clock = [System.Diagnostics.Stopwatch]::StartNew()
    while ($clock.Elapsed.TotalSeconds -lt 45) {
        $uid = Invoke-ReferenceAdbProcess -Arguments @('shell','id','-u') -TimeoutSeconds 5
        if ($uid.ExitCode -eq 0 -and $uid.Text -eq '0') { return }
        Start-Sleep -Milliseconds 500
    }
    throw 'The isolated emulator did not reconnect as root within 45 seconds'
}
function Restart-ReferenceEmulator([string]$Reason) {
    $previous = Invoke-ReferenceAdb -Arguments @('shell','cat','/proc/sys/kernel/random/boot_id') -RetrySeconds 10 -TimeoutSeconds 5
    if ($previous -notmatch '^[0-9a-fA-F-]{36}$') { throw "Cannot verify emulator reboot identity: $previous" }
    Invoke-ReferenceAdb -Arguments @('reboot') -TimeoutSeconds 10 | Out-Null
    Wait-ReferenceBoot -Reason $Reason -PreviousBootId $previous
}
function Assert-ReferenceTarget {
    if ((Invoke-ReferenceAdb -Arguments @('shell','getprop','ro.kernel.qemu') -RetrySeconds 10 -TimeoutSeconds 5) -ne '1') { throw 'Target is not an emulator' }
    if ((Invoke-ReferenceAdb -Arguments @('shell','getprop','ro.build.version.sdk')) -ne '36') { throw 'API 36 reference emulator required' }
    if ((Invoke-ReferenceAdb -Arguments @('shell','getprop','ro.debuggable')) -ne '1') { throw 'A debuggable AOSP image is required' }
}
Assert-ReferenceTarget
$outputDir = Join-Path $root 'artifacts\broker-reference'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$signer = Join-Path $env:ANDROID_HOME 'build-tools\36.0.0\apksigner.bat'
$unsigned = if ($BrokerVariant -eq 'emulator') {
    Join-Path $root 'android\emulator-broker\build\outputs\apk\debug\emulator-broker-debug.apk'
} else { Join-Path $root 'android\broker\build\outputs\apk\debug\broker-debug.apk' }
if (!(Test-Path -LiteralPath $unsigned)) { throw "Build the $BrokerVariant broker APK first" }
$signed = Join-Path $outputDir $(if ($BrokerVariant -eq 'emulator') { 'VirtualCameraProEmulatorBroker-platform.apk' } else { 'VirtualCameraProBroker-platform.apk' })
& $signer sign --key $PlatformKey --cert $PlatformCertificate --out $signed $unsigned
if ($LASTEXITCODE -ne 0) { throw 'Broker signing failed' }
function Get-SigningDigest([string]$Apk) {
    $info = & $signer verify --print-certs $Apk
    if ($LASTEXITCODE -ne 0) { throw "APK verification failed: $Apk" }
    $match = [regex]::Match(($info -join "`n"),'Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})')
    if (!$match.Success) { throw 'Signing certificate digest was not reported' }
    return $match.Groups[1].Value.ToLowerInvariant()
}
$platformPath = (Invoke-ReferenceAdb -Arguments @('shell','pm','path','android')) -replace '^package:',''
if ($platformPath -notmatch '^/system/framework/[^\s]+\.apk$') { throw "Unexpected platform APK path: $platformPath" }
$platformApk = Join-Path $outputDir 'framework-res.apk'
Invoke-ReferenceAdb -Arguments @('pull',$platformPath,$platformApk) | Write-Output
$digest = Get-SigningDigest $signed
if ($digest -ne (Get-SigningDigest $platformApk)) { throw 'Signing key does not match this emulator platform. No device modification was performed.' }
Enable-ReferenceRoot
# Fresh test emulator only. Restarting it is reversible and does not touch the phone.
$verity = Invoke-ReferenceAdb -Arguments @('disable-verity')
$verity | Write-Output
if ($verity -notmatch '(?i)successfully disabled verity|verity is already disabled' -and $verity -match '(?i)failed|error:') { throw "Could not disable verity on the isolated test image: $verity" }
if ($verity -match '(?i)reboot') {
    Restart-ReferenceEmulator -Reason 'disabling test-image verity'
    Assert-ReferenceTarget
    Enable-ReferenceRoot
}
$mounted = $false
for ($attempt = 1; $attempt -le 3; $attempt++) {
    if ((Invoke-ReferenceAdb -Arguments @('shell','id','-u')) -ne '0') { throw 'Root identity was lost before remount' }
    $remount = Invoke-ReferenceAdbProcess -Arguments @('remount') -TimeoutSeconds 45
    $remount.Text | Write-Output
    if ($remount.Text -match '(?i)(?:reboot your device|please reboot|reboot the device|reboot.*(?:take effect|required|settings))') {
        if ($attempt -eq 3) { throw "Remount still requires a reboot after three attempts: $($remount.Text)" }
        Restart-ReferenceEmulator -Reason 'applying emulator overlayfs remount'
        Assert-ReferenceTarget
        Enable-ReferenceRoot
        continue
    }
    if ($remount.ExitCode -ne 0 -or $remount.Text -match '(?i)remount failed|failed to remount|remount of .+ failed|operation not permitted|read-only file system|not running as root|error:') {
        throw "Emulator remount failed; restart this test AVD with -writable-system: $($remount.Text)"
    }
    # An exit-zero remount response alone does not prove /system is writable.
    Invoke-ReferenceAdb -Arguments @('shell','touch','/system/.vcamera-remount-probe') | Out-Null
    Invoke-ReferenceAdb -Arguments @('shell','rm','/system/.vcamera-remount-probe') | Out-Null
    $mounted = $true
    break
}
if (!$mounted) { throw 'No writable system mount was established' }
Invoke-ReferenceAdb -Arguments @('push',(Join-Path $root 'aosp\build-integration\privapp-permissions.xml'),'/system/etc/permissions/privapp-permissions-virtualcamerapro.xml') | Write-Output
Invoke-ReferenceAdb -Arguments @('shell','chmod','644','/system/etc/permissions/privapp-permissions-virtualcamerapro.xml') | Out-Null
Invoke-ReferenceAdb -Arguments @('shell','restorecon','/system/etc/permissions/privapp-permissions-virtualcamerapro.xml') | Out-Null
Invoke-ReferenceAdb -Arguments @('shell','mkdir','-p','/system/priv-app/VirtualCameraProBroker') | Out-Null
Invoke-ReferenceAdb -Arguments @('push',$signed,'/system/priv-app/VirtualCameraProBroker/VirtualCameraProBroker.apk') | Write-Output
Invoke-ReferenceAdb -Arguments @('shell','chmod','644','/system/priv-app/VirtualCameraProBroker/VirtualCameraProBroker.apk') | Out-Null
Invoke-ReferenceAdb -Arguments @('shell','restorecon','-R','/system/priv-app/VirtualCameraProBroker') | Out-Null
Restart-ReferenceEmulator -Reason 'scanning the system broker'
Assert-ReferenceTarget
Invoke-ReferenceAdb -Arguments @('shell','pm','grant','com.virtualcamerapro.broker','android.permission.CAMERA') | Out-Null
Invoke-ReferenceAdb -Arguments @('shell','pm','grant','com.virtualcamerapro.broker','android.permission.POST_NOTIFICATIONS') | Out-Null
$installed = Invoke-ReferenceAdb -Arguments @('shell','pm','path','com.virtualcamerapro.broker')
if ($installed -notmatch '^package:/system/priv-app/VirtualCameraProBroker/') { throw "Broker was not scanned as a system app: $installed" }
$package = Invoke-ReferenceAdb -Arguments @('shell','dumpsys','package','com.virtualcamerapro.broker')
if ($package -notmatch '(?m)^\s*(?:userId|appId)=1000(?:\s|$)') { throw 'Installed broker did not receive system UID 1000' }
$package | Set-Content -LiteralPath (Join-Path $outputDir 'installed-package.txt') -Encoding utf8
@{ serial=$Serial; platformCertificateSha256=$digest; brokerPath=$installed; installedAt=(Get-Date).ToUniversalTime().ToString('o'); cameraIntegrationInstalled=$false; brokerVariant=$BrokerVariant } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $outputDir 'installation.json') -Encoding utf8
Write-Output 'Production broker installed with platform signature and system UID. Run BrokerConnectionTest to verify Binder; this step does not install the CameraService routing patch.'
if ($BrokerVariant -eq 'emulator') { Write-Output 'The emulator broker requires the authenticated local camera host and connection configuration before activation.' }
