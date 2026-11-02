param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$')]
    [string]$Serial,
    [string]$OutputDirectory = '',
    [switch]$SkipInstall
)
$ErrorActionPreference = 'Stop'
if ($Serial -match '^emulator-') { throw 'Select an explicit physical-device serial. This runner rejects Android emulators.' }
$repo = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

function Find-PhysicalTestAdb {
    $sdkCandidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
    $localProperties = Join-Path $repo 'android\local.properties'
    if (Test-Path -LiteralPath $localProperties -PathType Leaf) {
        $sdkLine = Get-Content -LiteralPath $localProperties | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -Last 1
        if ($sdkLine) {
            $sdkCandidates += (($sdkLine -replace '^\s*sdk\.dir\s*=\s*', '') -replace '\\:', ':' -replace '\\\\', '\')
        }
    }
    if ($env:LOCALAPPDATA) { $sdkCandidates += Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    foreach ($sdk in $sdkCandidates) {
        if (!$sdk) { continue }
        $candidate = Join-Path $sdk 'platform-tools\adb.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return [System.IO.Path]::GetFullPath($candidate) }
    }
    $command = Get-Command adb -CommandType Application -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw 'ADB was not found. Set ANDROID_HOME to the installed Android SDK.'
}
function ConvertTo-PhysicalTestArgument([string]$Value) {
    # Windows ProcessStartInfo arguments; no intermediate command shell.
    if ($Value -ne '' -and $Value -notmatch '[\s"]') { return $Value }
    return '"' + (($Value -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"'
}
function Protect-PhysicalTestText([string]$Text) {
    $Text = $Text -replace '(?i)(Bearer\s+)[A-Za-z0-9._~+/-]+=*', '$1[REDACTED]'
    return $Text -replace '(?i)("(?:token|secret|authorization)"\s*:\s*")[^"]*(")', '$1[REDACTED]$2'
}
function Invoke-PhysicalTestAdbProcess {
    param([Parameter(Mandatory = $true)][string[]]$Arguments, [ValidateRange(1, 120)][int]$TimeoutSeconds = 30)
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $adbPath
    # Always select this serial, including read-only queries and failure cleanup.
    $start.Arguments = (@('-s', $Serial) + $Arguments | ForEach-Object { ConvertTo-PhysicalTestArgument $_ }) -join ' '
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
        $timedOut = !$process.WaitForExit($TimeoutSeconds * 1000)
        if ($timedOut) {
            try { $process.Kill() } catch { }
            $null = $process.WaitForExit(2000)
        }
        $output = @()
        foreach ($reader in @($stdout, $stderr)) {
            if ($reader.Wait(2000) -and !$reader.IsFaulted) { $output += $reader.Result }
            else { $output += 'ADB output pipe did not close within two seconds' }
        }
        if ($timedOut) { $output += "ADB timed out after $TimeoutSeconds seconds" }
        $exitCode = -1
        if (!$timedOut -and $process.HasExited) { $exitCode = $process.ExitCode }
        return [pscustomobject]@{
            ExitCode = $exitCode
            TimedOut = $timedOut
            Text = (Protect-PhysicalTestText ($output -join "`n")).Trim()
        }
    } finally { $process.Dispose() }
}
function Invoke-PhysicalTestAdb {
    param([Parameter(Mandatory = $true)][string[]]$Arguments, [ValidateRange(1, 120)][int]$TimeoutSeconds = 30)
    $response = Invoke-PhysicalTestAdbProcess -Arguments $Arguments -TimeoutSeconds $TimeoutSeconds
    if ($response.ExitCode -ne 0) { throw "ADB failed: $($Arguments -join ' ')`n$($response.Text)" }
    return $response.Text
}
function Write-PhysicalTestJson([object]$Value, [string]$Path) {
    $Value | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $Path -Encoding utf8
}

$adbPath = Find-PhysicalTestAdb
if (!$OutputDirectory) {
    $safeSerial = $Serial -replace '[^A-Za-z0-9._-]', '_'
    $OutputDirectory = Join-Path $repo "artifacts\physical-device-tests\$safeSerial"
}
$evidence = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$startedAt = [DateTime]::UtcNow.ToString('o')
$runId = [Guid]::NewGuid().ToString('N')
$reportPath = Join-Path $evidence 'runner-result.json'
$compatibilityPath = Join-Path $evidence 'device-compatibility.json'
$remoteCompatibility = '/sdcard/Android/data/com.virtualcamerapro/files/device-compatibility.json'
$device = [ordered]@{}
$installations = @()
$results = @()
$collection = @()
$failure = $null
$compatibility = $null

# Fresh placeholders ensure previous passing evidence cannot pass this run.
Write-PhysicalTestJson ([ordered]@{
    serial = $Serial; runId = $runId; startedAt = $startedAt
    passed = $false; virtualCameraVerified = $false; failure = 'Tests have not completed in this run'
}) $reportPath
Write-PhysicalTestJson ([ordered]@{ collected = $false; runId = $runId }) $compatibilityPath
foreach ($name in @('media-engine.txt', 'compatibility-tests.txt')) {
    'Instrumentation was not started in this run' | Set-Content -LiteralPath (Join-Path $evidence $name) -Encoding utf8
}

try {
    if ((Invoke-PhysicalTestAdb -Arguments @('get-state') -TimeoutSeconds 10).Trim() -ne 'device') {
        throw 'The selected physical device is not connected and authorized.'
    }
    foreach ($property in @('ro.kernel.qemu', 'ro.boot.qemu', 'ro.build.version.sdk', 'ro.build.version.release', 'ro.product.manufacturer', 'ro.product.model', 'ro.product.cpu.abilist', 'ro.product.cpu.abi', 'ro.build.fingerprint')) {
        $device[$property] = (Invoke-PhysicalTestAdb -Arguments @('shell', 'getprop', $property) -TimeoutSeconds 10).Trim()
    }
    if ($device['ro.kernel.qemu'] -eq '1' -or $device['ro.boot.qemu'] -eq '1') {
        throw 'The selected serial is an emulator. This runner only accepts physical devices.'
    }
    $sdk = 0
    if (![int]::TryParse($device['ro.build.version.sdk'], [ref]$sdk) -or $sdk -lt 28) {
        throw 'The controller requires Android 9 (API 28) or newer.'
    }
    $abis = @(($device['ro.product.cpu.abilist'] -split ',') | Where-Object { $_ })
    if ($abis.Count -eq 0) { $abis = @($device['ro.product.cpu.abi']) }
    if (@($abis | Where-Object { $_ -in @('arm64-v8a', 'x86_64') }).Count -eq 0) {
        throw "The controller requires a supported 64-bit Android ABI. Device reports: $($abis -join ', ')"
    }
    Write-PhysicalTestJson $device (Join-Path $evidence 'device.json')

    $apks = @(
        (Join-Path $repo 'android\app\build\outputs\apk\standalone\app-standalone.apk'),
        (Join-Path $repo 'android\app\build\outputs\apk\androidTest\standalone\app-standalone-androidTest.apk'),
        (Join-Path $repo 'android\camera-client\build\outputs\apk\debug\camera-client-debug.apk'),
        (Join-Path $repo 'android\camera-client\build\outputs\apk\androidTest\debug\camera-client-debug-androidTest.apk')
    )
    if (!$SkipInstall) {
        foreach ($apk in $apks) {
            if (!(Test-Path -LiteralPath $apk -PathType Leaf)) { throw "Build the controller, camera-client, and their instrumentation APKs first: $apk" }
        }
        # Check the actual controller's packaged native libraries before installing.
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $archive = [System.IO.Compression.ZipFile]::OpenRead($apks[0])
        try {
            $packagedAbis = @($archive.Entries | ForEach-Object {
                if ($_.FullName -match '^lib/([^/]+)/[^/]+\.so$') { $Matches[1] }
            } | Select-Object -Unique)
        } finally { $archive.Dispose() }
        if (@($abis | Where-Object { $_ -in $packagedAbis }).Count -eq 0) {
            throw "The built controller APK has no native library for this device. Device: $($abis -join ', '); APK: $($packagedAbis -join ', ')"
        }
        foreach ($apk in $apks) {
            $installation = Invoke-PhysicalTestAdbProcess -Arguments @('install', '-t', '-r', $apk) -TimeoutSeconds 120
            $installations += [ordered]@{
                apk = [System.IO.Path]::GetFileName($apk)
                sha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
                exitCode = $installation.ExitCode; output = $installation.Text
            }
            $installation.Text | Write-Output
            if ($installation.ExitCode -ne 0 -or $installation.Text -notmatch '(?m)^Success\s*$') {
                throw 'App update failed. This runner preserves existing app data and will never uninstall to bypass a signature or version mismatch.'
            }
        }
    } else {
        foreach ($package in @('com.virtualcamerapro', 'com.virtualcamerapro.test', 'com.virtualcamerapro.client', 'com.virtualcamerapro.client.test')) {
            $packagePath = Invoke-PhysicalTestAdb -Arguments @('shell', 'pm', 'path', $package) -TimeoutSeconds 10
            if ($packagePath -notmatch '(?m)^package:') { throw "-SkipInstall requires the existing APK for $package" }
        }
    }

    # Delete only this project's generated diagnostic JSON, never user media.
    Invoke-PhysicalTestAdb -Arguments @('shell', 'rm', '-f', $remoteCompatibility) -TimeoutSeconds 10 | Write-Output
    $testRuns = @(
        @{ Name = 'media-engine'; Class = 'com.virtualcamerapro.client.FrameEngineTest'; Runner = 'com.virtualcamerapro.client.test/androidx.test.runner.AndroidJUnitRunner'; Package = 'com.virtualcamerapro.client'; Count = 3; File = 'media-engine.txt' },
        @{ Name = 'compatibility'; Class = 'com.virtualcamerapro.camera.DeviceCompatibilityTest'; Runner = 'com.virtualcamerapro.test/androidx.test.runner.AndroidJUnitRunner'; Package = 'com.virtualcamerapro'; Count = 2; File = 'compatibility-tests.txt' }
    )
    foreach ($testRun in $testRuns) {
        $result = Invoke-PhysicalTestAdbProcess -Arguments @('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', $testRun.Class, $testRun.Runner) -TimeoutSeconds 120
        $result.Text | Tee-Object -FilePath (Join-Path $evidence $testRun.File) | Write-Output
        $passed = $result.ExitCode -eq 0 -and !$result.TimedOut -and
            $result.Text -match (('OK \({0} tests\)' -f $testRun.Count)) -and
            $result.Text -notmatch 'FAILURES|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED'
        $results += [ordered]@{
            name = $testRun.Name; class = $testRun.Class; expectedTests = $testRun.Count
            exitCode = $result.ExitCode; timedOut = $result.TimedOut; passed = [bool]$passed
        }
        if ($result.TimedOut) {
            # Stop only the application whose instrumentation exceeded its limit.
            $cleanup = Invoke-PhysicalTestAdbProcess -Arguments @('shell', 'am', 'force-stop', $testRun.Package) -TimeoutSeconds 10
            $collection += "Timeout cleanup $($testRun.Package) exit=$($cleanup.ExitCode): $($cleanup.Text)"
        }
    }

    $pull = Invoke-PhysicalTestAdbProcess -Arguments @('pull', $remoteCompatibility, $compatibilityPath) -TimeoutSeconds 20
    $collection += "Diagnostic JSON pull exit=$($pull.ExitCode): $($pull.Text)"
    if ($pull.ExitCode -ne 0) { throw 'Fresh physical-device compatibility JSON could not be collected.' }
    $compatibility = Get-Content -LiteralPath $compatibilityPath -Raw | ConvertFrom-Json
    if (!$compatibility.device -or !$compatibility.support -or $compatibility.device.androidSdk -ne $sdk -or
        $compatibility.device.model -cne $device['ro.product.model']) {
        throw 'Collected compatibility JSON does not match the selected physical device.'
    }
    if (@($results | Where-Object { !$_.passed }).Count -gt 0) { throw 'One or more physical-device media or compatibility tests failed.' }
} catch {
    $failure = Protect-PhysicalTestText $_.Exception.Message
} finally {
    $collection | Set-Content -LiteralPath (Join-Path $evidence 'collection.txt') -Encoding utf8
    Write-PhysicalTestJson $installations (Join-Path $evidence 'installations.json')
    $mediaPassed = @($results | Where-Object { $_.name -eq 'media-engine' -and $_.passed }).Count -eq 1
    $diagnosticsPassed = @($results | Where-Object { $_.name -eq 'compatibility' -and $_.passed }).Count -eq 1 -and $null -ne $compatibility
    Write-PhysicalTestJson ([ordered]@{
        serial = $Serial; runId = $runId; startedAt = $startedAt; finishedAt = [DateTime]::UtcNow.ToString('o')
        device = $device; skipInstall = [bool]$SkipInstall; tests = $results
        mediaEngineVerified = [bool]$mediaPassed; diagnosticsVerified = [bool]$diagnosticsPassed
        passed = [bool](!$failure -and $mediaPassed -and $diagnosticsPassed)
        virtualCameraVerified = $false
        virtualCameraVerification = 'Not tested. Native media rendering and compatibility diagnostics do not prove system-wide camera replacement.'
        failure = $failure
    }) $reportPath
}
if ($failure) { throw "Physical-device tests failed. Evidence: $evidence`n$failure" }
Write-Output "Physical-device media engine (3 tests) and compatibility diagnostics (2 tests) passed. Evidence: $evidence"
Write-Output 'System-wide virtual camera replacement remains unverified; these tests do not capture the physical camera or prove a camera route.'
