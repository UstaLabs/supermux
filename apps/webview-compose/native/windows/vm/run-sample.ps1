# Runs the webview sample on tester's desktop (winvm.sh checks|lab start it through a scheduled task) and waits for it.
param(
    [Parameter(Mandatory = $true)][ValidateSet('checks', 'lab')][string]$Mode,
    [Parameter(Mandatory = $true)][string]$Run,
    [string]$Only = '',
    [string]$Props = '',
    [int]$TimeoutSec = 2400
)
$ErrorActionPreference = 'Stop'
$root = 'C:\wvc'
New-Item -ItemType Directory -Force "$root\results\$Run" | Out-Null
# A run this helper left behind (a cut session) still holds the window, the focus and the profile: end it first. Only this
# helper's JVMs (their command line carries -Dwvc.run=) and the WebView2 processes of the sample's own data folder.
Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe' OR Name = 'java.exe'" |
    Where-Object { $_.CommandLine -like '*-Dwvc.run=*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue; "run-sample: ended a stray run $($_.ProcessId)" }
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
}
$javaArgs = @('-Xms256m', '-Xmx2g', '-XX:+UseG1GC') + $runProps + @($Props -split ' ' | Where-Object { $_ }) +
    @('-cp', "$root\lib\*", 'dev.supermux.webview.sample.MainKt')
# javaw: no console window to take the focus from the app.
$process = Start-Process -FilePath 'C:\wv2test\jbr\bin\javaw.exe' -ArgumentList $javaArgs -PassThru `
    -RedirectStandardOutput $log -RedirectStandardError "$log.err"
$null = $process.Handle  # keeps ExitCode readable after the process ends
if (-not $process.WaitForExit($TimeoutSec * 1000)) {
    Stop-Process -Id $process.Id -Force
    "run-sample: $Mode ran past $TimeoutSec s and was killed"
}
"run-sample: $Mode exit $($process.ExitCode), log $log"
Get-Content $log -ErrorAction SilentlyContinue | Where-Object { $_ -match '^(CHECK|CHECKS|LAB|perf-lab)' }
