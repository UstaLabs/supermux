# Runs the capture test app on tester's desktop (vm.sh app starts it through a scheduled task) and waits for it.
param(
    [Parameter(Mandatory = $true)][string]$Scenario,
    [string]$Props = '',
    [int]$TimeoutSec = 900
)
$ErrorActionPreference = 'Stop'
$root = 'C:\wv2test'
New-Item -ItemType Directory -Force "$root\results" | Out-Null

# A run an earlier job left behind (a cut SSH session, a stopped task) would still hold the window, the focus and the
# user data folder: end it first. Only this test's JVM and its own WebView2 processes are touched.
Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe' OR Name = 'java.exe'" |
    Where-Object { $_.CommandLine -like '*dev.supermux.webview.wintest.MainKt*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue; "run-app: ended a stray test JVM $($_.ProcessId)" }
Get-CimInstance Win32_Process -Filter "Name = 'msedgewebview2.exe'" |
    Where-Object { $_.CommandLine -like "*$root\udf*" } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$log = "$root\results\$Scenario-$stamp-stdout.log"
$javaArgs = @('-Xmx1g', '-XX:+UseG1GC', "-Dwv2.scenario=$Scenario", "-Dwv2.out=$root\results",
    "-Dwv2.dll=$root\native\out\wv2capture.dll") + @($Props -split ' ' | Where-Object { $_ }) +
    @('-cp', "$root\lib\*", 'dev.supermux.webview.wintest.MainKt')
# javaw: no console window to steal focus from the app.
$process = Start-Process -FilePath "$root\jbr\bin\javaw.exe" -ArgumentList $javaArgs -PassThru `
    -RedirectStandardOutput $log -RedirectStandardError "$log.err"
$null = $process.Handle  # keeps ExitCode readable after the process ends
if (-not $process.WaitForExit($TimeoutSec * 1000)) {
    Stop-Process -Id $process.Id -Force
    "run-app: $Scenario ran past $TimeoutSec s and was killed"
}
"run-app: $Scenario exit $($process.ExitCode), stdout in $log"
Get-Content $log -ErrorAction SilentlyContinue | Where-Object { $_ -like 'WV2 *' }
