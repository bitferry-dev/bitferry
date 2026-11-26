[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ImageDir
)

$ErrorActionPreference = 'Stop'
$image = (Resolve-Path $ImageDir).Path

foreach ($relativePath in @(
    'bitferry.exe',
    'bitferry-gui.exe',
    'lib\aot\cli.aot',
    'lib\aot\gui.aot',
    'lib\jnidispatch.dll',
    'runtime\bin\jansi.dll',
    'bin\rclone.exe',
    'bin\restic.exe'
)) {
    if (-not (Test-Path (Join-Path $image $relativePath) -PathType Leaf)) {
        throw "Missing packaged file: $relativePath"
    }
}

$expectedOptions = @{
    'bitferry.cfg' = @(
        'java-options=-XX:AOTCache=$APPDIR/../lib/aot/cli.aot',
        'java-options=-Djna.boot.library.path=$APPDIR/../lib',
        'java-options=-Dbitferry.rclone=$APPDIR/../bin/rclone.exe',
        'java-options=-Dbitferry.restic=$APPDIR/../bin/restic.exe'
    )
    'bitferry-gui.cfg' = @(
        'java-options=-XX:AOTCache=$APPDIR/../lib/aot/gui.aot',
        'java-options=-Djna.boot.library.path=$APPDIR/../lib',
        'java-options=-Dbitferry.rclone=$APPDIR/../bin/rclone.exe',
        'java-options=-Dbitferry.restic=$APPDIR/../bin/restic.exe'
    )
}

foreach ($configName in $expectedOptions.Keys) {
    $config = Get-Content -Raw (Join-Path $image "app\$configName")
    foreach ($option in $expectedOptions[$configName]) {
        if (-not $config.Contains($option)) {
            throw "Missing or invalid option in ${configName}: $option"
        }
    }
}

foreach ($engine in @('rclone', 'restic')) {
    & (Join-Path $image "bin\$engine.exe") version
    if ($LASTEXITCODE -ne 0) {
        throw "Packaged $engine exited with code $LASTEXITCODE"
    }
}

$env:JAVA_TOOL_OPTIONS = '-Xlog:aot'

# Windows PowerShell 5 turns redirected native stderr into a NativeCommandError;
# the JDK writes AOT logs to stderr even when the launch succeeds.
$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = 'SilentlyContinue'
try {
    $output = & (Join-Path $image 'bitferry.exe') -V 2>&1
    $exitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}
$text = $output | Out-String
Write-Output $text
if ($exitCode -ne 0) {
    throw "bitferry.exe exited with code $exitCode"
}
if ($text -match 'Failed to open AOT cache|Loading static archive failed|Unable to map shared spaces') {
    throw 'bitferry.exe could not load its AOT cache'
}
if ($text -notmatch 'Opened AOT cache') {
    throw 'bitferry.exe did not report successful AOT-cache loading'
}

# A GUI-subsystem executable has no console stream to capture. Its config paths
# were asserted above; launch its self-terminating training mode and check exit.
$gui = Start-Process -FilePath (Join-Path $image 'bitferry-gui.exe') `
    -ArgumentList '--train' -Wait -PassThru
if ($gui.ExitCode -ne 0) {
    throw "bitferry-gui.exe --train exited with code $($gui.ExitCode)"
}
Write-Output 'bitferry-gui.exe --train exited successfully'

Write-Output "Windows app-image smoke test passed: $image"
