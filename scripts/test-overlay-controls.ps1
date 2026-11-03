$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$cache = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
$packages = @(
    'org.jetbrains.kotlin\kotlin-compiler-embeddable\2.1.20',
    'org.jetbrains.kotlin\kotlin-stdlib\2.1.20',
    'org.jetbrains.kotlin\kotlin-script-runtime\2.1.20',
    'org.jetbrains.kotlin\kotlin-reflect',
    'org.jetbrains.kotlin\kotlin-daemon-embeddable\2.1.20',
    'org.jetbrains.intellij.deps\trove4j',
    'org.jetbrains\annotations',
    'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm'
)
$compilerJars = foreach ($package in $packages) {
    Get-ChildItem -LiteralPath (Join-Path $cache $package) -Filter '*.jar' -File -Recurse -ErrorAction SilentlyContinue |
        Sort-Object FullName | Select-Object -Last 1 -ExpandProperty FullName
}
$stdlib = $compilerJars | Where-Object { $_ -match 'kotlin-stdlib-' } | Select-Object -First 1
$annotations = $compilerJars | Where-Object { $_ -match 'annotations-' } | Select-Object -First 1
if (-not $stdlib -or -not ($compilerJars | Where-Object { $_ -match 'kotlin-compiler-embeddable-' })) {
    throw 'Run the Android build once to populate its Kotlin compiler dependencies.'
}
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'C:\Program Files\Java\jdk-17\bin\java.exe' }
$output = Join-Path $workspace 'artifacts\unified\overlay-controls-tests'
$sources = @(
    (Join-Path $workspace 'android\app\src\main\java\com\virtualcamerapro\overlay\SourceTransform.kt'),
    (Join-Path $workspace 'android\app\src\main\java\com\virtualcamerapro\overlay\TransformDispatcher.kt'),
    (Join-Path $workspace 'native\overlay-controls\OverlayControlsTests.kt')
)
& $java -cp ($compilerJars -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath ($stdlib + ';' + $annotations) -d $output $sources
if ($LASTEXITCODE -ne 0) { throw 'Overlay controls tests did not compile.' }
& $java -cp ($output + ';' + $stdlib) com.virtualcamerapro.overlay.OverlayControlsTestsKt
if ($LASTEXITCODE -ne 0) { throw 'Overlay controls tests failed.' }
