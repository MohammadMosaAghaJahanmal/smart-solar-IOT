param(
    [switch]$ShowWindow,
    [switch]$Build,
    [switch]$Test,
    [string]$JavaHome = 'C:\Program Files\Java\jdk-17'
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$avd = 'VirtualCameraProBroker36'
$serial = 'emulator-5556'
$cameraId = '10'
$backend = 'android_emulator_environment'
$connectionFile = Join-Path $root 'artifacts\emulator-host\connection.json'
$logs = Join-Path $root 'artifacts\emulator-launcher'
New-Item -ItemType Directory -Force -Path $logs | Out-Null

function Protect-LauncherText([string]$Text) {
    $Text = $Text -replace '(?i)(Bearer\s+)[A-Za-z0-9._~+/-]+=*', '$1[REDACTED]'
    $Text = $Text -replace '(?i)("(?:token|secret|authorization)"\s*:\s*")[^"]*(")', '$1[REDACTED]$2'
    return $Text -replace '(?i)(\b(?:token|secret|authorization)\s*=\s*)[^\s&,;]+', '$1[REDACTED]'
}
function ConvertTo-LauncherArgument([string]$Value) {
    if ($Value -ne '' -and $Value -notmatch '[\s"]') { return $Value }
    return '"' + (($Value -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"'
}
function Invoke-LauncherProcess {
    param([string]$FilePath, [string[]]$Arguments = @(), [int]$TimeoutSeconds = 30, [switch]$AllowFailure)
    $start = New-Object Diagnostics.ProcessStartInfo
    $start.FileName = $FilePath
    $start.Arguments = ($Arguments | ForEach-Object { ConvertTo-LauncherArgument $_ }) -join ' '
    $start.WorkingDirectory = $root
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = New-Object Diagnostics.Process
    $process.StartInfo = $start
    try {
        if (!$process.Start()) { throw "Could not start $FilePath" }
        $out = $process.StandardOutput.ReadToEndAsync()
        $err = $process.StandardError.ReadToEndAsync()
        if (!$process.WaitForExit($TimeoutSeconds * 1000)) {
            try { $process.Kill() } catch { }
            $null = $process.WaitForExit(2000)
            $result = [pscustomobject]@{ ExitCode = -1; Text = "Command timed out after $TimeoutSeconds seconds" }
        } elseif (!$out.Wait(2000) -or !$err.Wait(2000)) {
            $result = [pscustomobject]@{ ExitCode = -1; Text = 'Command output did not close within two seconds' }
        } else {
            $result = [pscustomobject]@{ ExitCode = $process.ExitCode; Text = (Protect-LauncherText (($out.Result, $err.Result) -join "`n")).Trim() }
        }
        if (!$AllowFailure -and $result.ExitCode -ne 0) { throw "$(Split-Path -Leaf $FilePath) failed: $($result.Text)" }
        return $result
    } finally { $process.Dispose() }
}
function Invoke-LauncherAdb {
    param([string[]]$Arguments, [int]$TimeoutSeconds = 30, [switch]$AllowFailure)
    return Invoke-LauncherProcess -FilePath $adb -Arguments (@('-s', $serial) + $Arguments) -TimeoutSeconds $TimeoutSeconds -AllowFailure:$AllowFailure
}
function Wait-LauncherBoot {
    $clock = [Diagnostics.Stopwatch]::StartNew()
    while ($clock.Elapsed.TotalSeconds -lt 180) {
        $result = Invoke-LauncherAdb -Arguments @('shell','getprop','sys.boot_completed') -TimeoutSeconds 5 -AllowFailure
        if ($result.ExitCode -eq 0 -and $result.Text -eq '1') { return }
        Start-Sleep -Milliseconds 500
    }
    throw "The owned emulator $serial did not finish booting within 180 seconds. Inspect $logs."
}
function Get-LauncherDiscovery {
    $running = Join-Path $env:LOCALAPPDATA 'Temp\avd\running'
    $records = @()
    foreach ($file in @(Get-ChildItem -LiteralPath $running -Filter 'pid_*.ini' -File -ErrorAction SilentlyContinue)) {
        $info = @{}
        foreach ($line in Get-Content -LiteralPath $file.FullName) {
            if ($line -match '^\s*([^#;=]+?)\s*=(.*)$') { $info[$matches[1].Trim()] = $matches[2].Trim() }
        }
        $isOwned = $info['avd.id'] -eq $avd -or $info['avd.name'] -eq $avd
        if ($info['port.serial'] -eq '5556' -and !$isOwned) { throw 'Port 5556 belongs to another AVD; the launcher will not modify or stop it.' }
        if (!$isOwned) { continue }
        if ($info['port.serial'] -ne '5556' -or $info['grpc.port'] -ne '8554' -or !$info['grpc.token']) {
            throw 'The running owned AVD must use emulator-5556 and authenticated gRPC port 8554. Stop it yourself before relaunching with this script.'
        }
        $records += [pscustomobject]@{ Path = $file.FullName; Info = $info }
    }
    if ($records.Count -gt 1) { throw 'Multiple discovery records match the owned AVD; resolve the stale emulator records before continuing.' }
    if ($records.Count -eq 1) { return $records[0] }
    return $null
}
function Get-LauncherHostResponse {
    if (!(Test-Path -LiteralPath $connectionFile -PathType Leaf)) { return $null }
    try {
        $connection = Get-Content -LiteralPath $connectionFile -Raw | ConvertFrom-Json
        if ($connection.url -ne 'http://127.0.0.1:8791' -or $connection.backend -ne $backend -or
            $connection.avd -ne $avd -or $connection.serial -ne $serial -or $connection.cameraId -ne $cameraId -or
            !$connection.token -or $connection.token.Length -lt 32) { return $null }
        $support = Invoke-RestMethod -Uri ($connection.url + '/support') -Headers @{ Authorization = 'Bearer ' + $connection.token } -TimeoutSec 5
        if ($support.backend -ne $backend -or $support.cameraId -ne $cameraId -or $support.avd -ne $avd -or $support.serial -ne $serial) { return $null }
        return $support
    } catch { return $null }
}
function Test-LauncherHost {
    $support = Get-LauncherHostResponse
    return $null -ne $support -and $support.supported -eq $true -and $support.hostProtocol -eq 2
}
function Get-LauncherListener([int]$Port) {
    return @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue).Count -gt 0
}
function Get-LauncherLiteralPattern([string]$Value) {
    $quoted = [regex]::Escape('"' + $Value + '"')
    if ($Value -match '\s') { return $quoted }
    return '(?:' + $quoted + '|' + [regex]::Escape($Value) + ')'
}
function Restart-LauncherStaleHost {
    $support = Get-LauncherHostResponse
    if ($null -eq $support) { return $false }
    $oldProtocol = $null -eq $support.hostProtocol -or $support.hostProtocol -lt 2
    $emulatorRestarted = $support.supported -eq $false -and $support.code -eq 'EMULATOR_RESTARTED'
    if (!$oldProtocol -and !$emulatorRestarted) { return $false }
    # Authenticated HTTP identity alone is insufficient to terminate a process.
    # The sole listener must also be exactly this Python helper and owned AVD.
    $listenerIds = @(Get-NetTCPConnection -State Listen -LocalPort 8791 -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique)
    if ($listenerIds.Count -ne 1) { return $false }
    $helperId = [int]$listenerIds[0]
    $record = Get-CimInstance Win32_Process -Filter "ProcessId=$helperId" -OperationTimeoutSec 5
    if (!$record -or $record.Name -ne 'python.exe' -or !$record.ExecutablePath -or
        ![string]::Equals([IO.Path]::GetFullPath($record.ExecutablePath), [IO.Path]::GetFullPath($python), [StringComparison]::OrdinalIgnoreCase)) { return $false }
    $helper = Join-Path $PSScriptRoot 'emulator-camera-host.py'
    $commandPattern = '^\s*' + (Get-LauncherLiteralPattern $python) + '\s+(?:-u\s+)?' + (Get-LauncherLiteralPattern $helper) +
        '\s+--avd\s+' + (Get-LauncherLiteralPattern $avd) + '(?:\s+--camera-id\s+' + (Get-LauncherLiteralPattern $cameraId) + ')?' +
        '(?:\s+--port\s+' + (Get-LauncherLiteralPattern '8791') + ')?\s*$'
    if ($record.CommandLine -notmatch $commandPattern) { return $false }
    $owned = Get-Process -Id $helperId -ErrorAction Stop
    try {
        $null = $owned.Handle # Pin this process object before rechecking PID reuse.
        $confirmed = Get-CimInstance Win32_Process -Filter "ProcessId=$helperId" -OperationTimeoutSec 5
        if (!$confirmed -or $confirmed.CommandLine -cne $record.CommandLine -or $confirmed.CreationDate -ne $record.CreationDate -or $confirmed.ExecutablePath -cne $record.ExecutablePath) { return $false }
        $owned.Kill()
        if (!$owned.WaitForExit(5000)) { throw 'The verified stale companion did not exit within five seconds.' }
    } finally { $owned.Dispose() }
    $clock = [Diagnostics.Stopwatch]::StartNew()
    while ($clock.Elapsed.TotalSeconds -lt 10 -and (Get-LauncherListener 8791)) { Start-Sleep -Milliseconds 250 }
    if (Get-LauncherListener 8791) { throw 'Port 8791 remained occupied after the verified stale companion exited.' }
    Write-Information 'Restarting the verified owned companion for the current emulator and cancellation protocol.' -InformationAction Continue
    return $true
}

$sdk = $env:ANDROID_HOME
if (!$sdk) {
    $properties = Join-Path $root 'android\local.properties'
    if (Test-Path -LiteralPath $properties -PathType Leaf) {
        $line = Get-Content -LiteralPath $properties | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -Last 1
        if ($line) { $sdk = (($line -replace '^\s*sdk\.dir\s*=\s*','') -replace '\\:',':' -replace '\\\\','\').Trim() }
    }
}
if (!$sdk -or !(Test-Path -LiteralPath $sdk -PathType Container)) { throw 'Set ANDROID_HOME or sdk.dir in android/local.properties to the installed Android SDK.' }
$sdk = (Resolve-Path -LiteralPath $sdk).Path
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emulator = Join-Path $sdk 'emulator\emulator.exe'
$java = Join-Path $JavaHome 'bin\java.exe'
foreach ($path in @($adb, $emulator, $java)) { if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required tool missing: $path" } }
$release = Join-Path $JavaHome 'release'
if (!(Test-Path -LiteralPath $release) -or (Get-Content -LiteralPath $release -Raw) -notmatch '(?m)^JAVA_VERSION="17(?:\.|"|-)') { throw 'The launcher requires JDK 17. Set -JavaHome to its installation directory.' }
$pythonCommand = Get-Command python.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
if (!$pythonCommand) { throw 'Python 3.10 or newer is required; install Python and expose python.exe on PATH.' }
$python = $pythonCommand.Source
$dependencies = Invoke-LauncherProcess -FilePath $python -Arguments @('-c','import sys; assert sys.version_info >= (3, 10); import grpc; from PIL import Image') -TimeoutSeconds 10 -AllowFailure
if ($dependencies.ExitCode -ne 0) { throw 'Python 3.10+, grpcio, and Pillow are required. Install dependencies with: python -m pip install grpcio Pillow' }
$env:ANDROID_HOME = $sdk
$env:JAVA_HOME = $JavaHome
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$root"
$env:PATH = "$(Join-Path $sdk 'platform-tools');$(Join-Path $JavaHome 'bin');$env:PATH"

if ($Build) {
    Write-Output 'Building the controller, emulator broker, and acceptance APKs with JDK 17.'
    $shell = (Get-Process -Id $PID).Path
    $result = Invoke-LauncherProcess -FilePath $shell -Arguments @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'build-android.ps1'),'-JavaHome',$JavaHome) -TimeoutSeconds 1200
    $result.Text | Write-Output
}
$controller = Join-Path $root 'android\app\build\outputs\apk\standalone\app-standalone.apk'
if (!(Test-Path -LiteralPath $controller -PathType Leaf)) { throw 'The standalone controller APK is missing. Run this launcher with -Build once.' }
if ($Test) {
    foreach ($relative in @('android\app\build\outputs\apk\androidTest\standalone\app-standalone-androidTest.apk', 'android\camera-client\build\outputs\apk\debug\camera-client-debug.apk', 'android\camera-client\build\outputs\apk\androidTest\debug\camera-client-debug-androidTest.apk')) {
        if (!(Test-Path -LiteralPath (Join-Path $root $relative) -PathType Leaf)) { throw 'Acceptance APKs are missing. Run this launcher with -Build -Test.' }
    }
}

$discovery = Get-LauncherDiscovery
if (!$discovery) {
    $state = Invoke-LauncherAdb -Arguments @('get-state') -TimeoutSeconds 5 -AllowFailure
    if ($state.ExitCode -eq 0) { throw 'emulator-5556 is already connected without the expected authenticated AVD discovery; no second emulator will be started.' }
    foreach ($port in @(5556,5557,8554)) { if (Get-LauncherListener $port) { throw "Port $port is already in use; no existing emulator or application will be stopped." } }
    $prepared = Invoke-LauncherProcess -FilePath $python -Arguments @((Join-Path $PSScriptRoot 'emulator-environment.py'),'--avd',$avd,'--prepare') -TimeoutSeconds 15
    $prepared.Text | Write-Output
    # Emulator discovery is also published beneath this normal Temp directory.
    $tempRoot = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Temp'
    if (!$tempRoot -or $tempRoot.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) { throw 'A normal LocalAppData temporary directory outside the workspace is required for emulator disk preflight.' }
    New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
    $arguments = @('-avd',$avd,'-port','5556','-grpc','8554','-grpc-use-token','-camera-back','environment','-writable-system','-no-snapshot','-no-audio','-no-boot-anim','-gpu','host','-feature','-Vulkan','-memory','2048','-cores','2')
    if (!$ShowWindow) { $arguments += '-qt-hide-window' }
    $oldTemp = $env:TEMP; $oldTmp = $env:TMP
    try {
        $env:TEMP = $tempRoot; $env:TMP = $tempRoot
        $windowStyle = if ($ShowWindow) { 'Normal' } else { 'Hidden' }
        $started = Start-Process -FilePath $emulator -ArgumentList (($arguments | ForEach-Object { ConvertTo-LauncherArgument $_ }) -join ' ') -WorkingDirectory $root -WindowStyle $windowStyle -RedirectStandardOutput (Join-Path $logs 'emulator.out.log') -RedirectStandardError (Join-Path $logs 'emulator.err.log') -PassThru
        Write-Output "Started the owned $avd emulator (PID $($started.Id))."
    } finally { $env:TEMP = $oldTemp; $env:TMP = $oldTmp }
} else { Write-Output "Reusing the running $avd emulator on $serial." }
Wait-LauncherBoot
$discovery = Get-LauncherDiscovery
if (!$discovery) { throw 'The booted emulator did not publish the expected authenticated discovery record.' }
$qemu = Invoke-LauncherAdb -Arguments @('shell','getprop','ro.kernel.qemu') -TimeoutSeconds 5
$api = Invoke-LauncherAdb -Arguments @('shell','getprop','ro.build.version.sdk') -TimeoutSeconds 5
if ($qemu.Text -ne '1' -or $api.Text -ne '36') { throw 'The selected target is not the owned API 36 Android emulator.' }

if (Test-LauncherHost) { Write-Output 'Reusing the authenticated local emulator camera companion.' }
else {
    if (Get-LauncherListener 8791) {
        $restarted = Restart-LauncherStaleHost
        if (!$restarted) { throw 'Port 8791 is occupied without the exact authenticated stale helper identity. No unrelated process was stopped.' }
    }
    $hostProcess = Start-Process -FilePath $python -ArgumentList ((@((Join-Path $PSScriptRoot 'emulator-camera-host.py'),'--avd',$avd,'--camera-id',$cameraId,'--port','8791') | ForEach-Object { ConvertTo-LauncherArgument $_ }) -join ' ') -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logs 'companion.out.log') -RedirectStandardError (Join-Path $logs 'companion.err.log') -PassThru
    $clock = [Diagnostics.Stopwatch]::StartNew()
    while ($clock.Elapsed.TotalSeconds -lt 60 -and !(Test-LauncherHost)) {
        if ($hostProcess.HasExited) { throw "The emulator companion exited. Inspect $(Join-Path $logs 'companion.err.log')." }
        Start-Sleep -Milliseconds 500
    }
    if (!(Test-LauncherHost)) { throw "The authenticated emulator companion was not ready within 60 seconds. Inspect $logs." }
    Write-Output 'Started the authenticated local emulator camera companion.'
}

$unsigned = Join-Path $root 'android\emulator-broker\build\outputs\apk\debug\emulator-broker-debug.apk'
$signed = Join-Path $root 'artifacts\broker-reference\VirtualCameraProEmulatorBroker-platform.apk'
if (!(Test-Path -LiteralPath $signed -PathType Leaf) -or ((Test-Path -LiteralPath $unsigned -PathType Leaf) -and (Get-Item -LiteralPath $unsigned).LastWriteTimeUtc -gt (Get-Item -LiteralPath $signed).LastWriteTimeUtc)) {
    if (!(Test-Path -LiteralPath $unsigned -PathType Leaf)) { throw 'The emulator broker APK is missing. Run this launcher with -Build once.' }
    $signer = Join-Path $sdk 'build-tools\36.0.0\lib\apksigner.jar'
    $key = Join-Path $root 'tools\test-platform-key\platform.pk8'
    $certificate = Join-Path $root 'tools\test-platform-key\platform.x509.pem'
    foreach ($path in @($signer,$key,$certificate)) { if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "Development signing input missing: $path. Run prepare-broker-reference.py for the AOSP test image first." } }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $signed) | Out-Null
    Invoke-LauncherProcess -FilePath $java -Arguments @('-jar',$signer,'sign','--key',$key,'--cert',$certificate,'--out',$signed,$unsigned) -TimeoutSeconds 30 | Out-Null
}
$package = Invoke-LauncherAdb -Arguments @('shell','dumpsys','package','com.virtualcamerapro.broker') -TimeoutSeconds 10 -AllowFailure
$installedPath = Invoke-LauncherAdb -Arguments @('shell','pm','path','com.virtualcamerapro.broker') -TimeoutSeconds 10 -AllowFailure
$correctBroker = $package.ExitCode -eq 0 -and $package.Text -match '(?m)^\s*versionName=2\.0-emulator\s*$' -and
    $package.Text -match '(?m)^\s*(?:userId|appId)=1000(?:\s|$)' -and $installedPath.Text -eq 'package:/system/priv-app/VirtualCameraProBroker/VirtualCameraProBroker.apk'
if ($correctBroker) {
    $remoteHash = Invoke-LauncherAdb -Arguments @('shell','sha256sum','/system/priv-app/VirtualCameraProBroker/VirtualCameraProBroker.apk') -TimeoutSeconds 10 -AllowFailure
    $correctBroker = $remoteHash.ExitCode -eq 0 -and $remoteHash.Text.Split(' ')[0] -eq (Get-FileHash -LiteralPath $signed -Algorithm SHA256).Hash.ToLowerInvariant()
}
if (!$correctBroker) {
    if (!(Test-Path -LiteralPath $unsigned -PathType Leaf)) { throw 'Installing the emulator broker requires its unsigned APK. Run this launcher with -Build once.' }
    & (Join-Path $PSScriptRoot 'install-reference-broker.ps1') -Serial $serial -JavaHome $JavaHome -BrokerVariant emulator
} else { Write-Output 'The matching emulator broker APK is already installed; preserving its system installation.' }

Invoke-LauncherAdb -Arguments @('root') -TimeoutSeconds 10 | Out-Null
$clock = [Diagnostics.Stopwatch]::StartNew()
$rootReady = $false
while ($clock.Elapsed.TotalSeconds -lt 45) {
    $uid = Invoke-LauncherAdb -Arguments @('shell','id','-u') -TimeoutSeconds 5 -AllowFailure
    if ($uid.ExitCode -eq 0 -and $uid.Text -eq '0') { $rootReady = $true; break }
    Start-Sleep -Milliseconds 500
}
if (!$rootReady) { throw 'The owned emulator did not reconnect with adb root within 45 seconds.' }
& (Join-Path $PSScriptRoot 'connect-emulator-camera.ps1') -Serial $serial -ConnectionFile $connectionFile
Invoke-LauncherAdb -Arguments @('install','-t','-r',$controller) -TimeoutSeconds 60 | Out-Null
if ($Test) { & (Join-Path $PSScriptRoot 'test-emulator-backend.ps1') -Serial $serial -CameraId $cameraId }
Invoke-LauncherAdb -Arguments @('shell','am','start','-W','-n','com.virtualcamerapro/.MainActivity') -TimeoutSeconds 20 | Out-Null
Write-Output 'Virtual Camera Pro is open on the prepared Android emulator. Select media and activate camera 10.'
if (!$ShowWindow) { Write-Output 'The emulator window is hidden. Use -ShowWindow when starting a stopped AVD to display it.' }
