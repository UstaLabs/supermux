# Runs the webview sample on tester's desktop (winvm.sh checks|lab start it through a scheduled task) and waits for it.
# -Lib: the classpath folder under C:\wvc (lib, or lib-release for the shrunk build). -Launcher: a packaged app's .exe
# to start instead of javaw (its options go into its app\<name>.cfg for the run, the file restored after).
param(
    [Parameter(Mandatory = $true)][ValidateSet('checks', 'lab')][string]$Mode,
    [Parameter(Mandatory = $true)][string]$Run,
    [string]$Only = '',
    [string]$Props = '',
    [string]$Lib = 'lib',
    [string]$Launcher = '',
    [int]$TimeoutSec = 2400
)
$ErrorActionPreference = 'Stop'
$root = 'C:\wvc'
New-Item -ItemType Directory -Force "$root\results\$Run" | Out-Null
# A run this helper left behind (a cut session) still holds the window, the focus and the profile: end it first. Only this
# helper's JVMs (their command line carries -Dwvc.run=), a packaged sample under C:\wvc\app (its options are in its .cfg),
# and the WebView2 processes of the sample's own data folder.
Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe' OR Name = 'java.exe'" |
    Where-Object { $_.CommandLine -like '*-Dwvc.run=*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue; "run-sample: ended a stray run $($_.ProcessId)" }
Get-CimInstance Win32_Process -Filter "Name = 'webview-sample.exe'" |
    Where-Object { $_.ExecutablePath -like "$root\app\*" } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue; "run-sample: ended a stray packaged run $($_.ProcessId)" }
Get-CimInstance Win32_Process -Filter "Name = 'msedgewebview2.exe'" |
    Where-Object { $_.CommandLine -like '*dev.supermux.webview\webview2\wvc-sample*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
$log = "$root\results\$Mode-$Run.log"
# The run's own properties. Not $props: PowerShell's names are case-insensitive, so that is the [string] parameter
# $Props, and an array assigned to it, or added to it, becomes one string with no spaces between its items.
$runProps = @("-Dwvc.run=$Run", '-Ddev.supermux.webview.app=wvc-sample', "-Dwebview.sample.out=$root\results\$Run")
if ($Mode -eq 'checks') {
    $runProps += '-Dwebview.sample.checks=windows'
    if ($Only) { $runProps += "-Dwebview.sample.only=$Only" }
} else {
    $runProps += '-Dwebview.sample.engine=webview2'
    $runProps += "-Dwvc.memoryScript=$root\vm\webview2-memory.ps1"
}
$jvmArgs = @('-Xms256m', '-Xmx2g', '-XX:+UseG1GC') + $runProps + @($Props -split ' ' | Where-Object { $_ })
$cfg = $null
$saved = $null
try {
    if ($Launcher) {
        # jpackage's launcher reads its JVM options from app\<name>.cfg, [JavaOptions], one java-options= line each.
        $name = [IO.Path]::GetFileNameWithoutExtension($Launcher)
        $cfg = Join-Path (Split-Path $Launcher) "app\$name.cfg"
        $saved = [IO.File]::ReadAllText($cfg)
        $options = ($jvmArgs | ForEach-Object { "java-options=$_" }) -join "`r`n"
        $text = if ($saved -match '\[JavaOptions\]') { $saved -replace '\[JavaOptions\]', "[JavaOptions]`r`n$options" } else { "$saved`r`n[JavaOptions]`r`n$options`r`n" }
        [IO.File]::WriteAllText($cfg, $text)
        $process = Start-Process -FilePath $Launcher -PassThru -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    } else {
        $javaArgs = $jvmArgs + @('-cp', "$root\$Lib\*", 'dev.supermux.webview.sample.MainKt')
        # javaw: no console window to take the focus from the app.
        $process = Start-Process -FilePath 'C:\wv2test\jbr\bin\javaw.exe' -ArgumentList $javaArgs -PassThru `
            -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    }
    $null = $process.Handle  # keeps ExitCode readable after the process ends
    if (-not $process.WaitForExit($TimeoutSec * 1000)) {
        Stop-Process -Id $process.Id -Force
        "run-sample: $Mode ran past $TimeoutSec s and was killed"
    }
} finally {
    if ($cfg -and $null -ne $saved) { [IO.File]::WriteAllText($cfg, $saved) }
}
"run-sample: $Mode exit $($process.ExitCode), log $log"
Get-Content $log -ErrorAction SilentlyContinue | Where-Object { $_ -match '^(CHECK|CHECKS|LAB|perf-lab)' }
