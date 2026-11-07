# The Windows glue's cache and the app's processes in a release build (plan Task 30, Step 5), on tester's desktop
# (winvm.sh ui): the DLL extracted to its hash-named folder and re-hashed (D19), replaced when tampered with, two
# instances of the app at once, and the ephemeral slots' locks (V3). javaw on C:\wvc\<Lib> as run-sample.ps1 starts it.
# Prints "CACHE <n> PASS|FAIL <detail>" for each step, then "CACHE DONE failed=<count>".
# For the helper's own VM only: it ends every run of the helper (any -Dwvc.run=, not this run's alone) and the sample's
# WebView2 processes, and removes tester's glue cache under %LOCALAPPDATA%, outside C:\wvc.
param([string]$Lib = 'lib-release', [string]$App = 'wvc-sample')
$ErrorActionPreference = 'Stop'
$root = 'C:\wvc'
$run = 'cache-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
$out = "$root\results\$run"
New-Item -ItemType Directory -Force $out | Out-Null
$script:failed = 0
function Report([int]$n, [bool]$ok, [string]$detail) {
    if (-not $ok) { $script:failed++ }
    "CACHE $n $(if ($ok) { 'PASS' } else { 'FAIL' }) $detail"
}
function Start-Sample([string[]]$props, [string]$name) {
    $log = "$out\$name.log"
    $javaArgs = @('-Xms256m', '-Xmx1g', "-Dwvc.run=$run", "-Ddev.supermux.webview.app=$App", "-Dwebview.sample.out=$out") +
        $props + @('-cp', "$root\$Lib\*", 'dev.supermux.webview.sample.MainKt')
    $p = Start-Process -FilePath 'C:\wv2test\jbr\bin\javaw.exe' -ArgumentList $javaArgs -PassThru -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    $null = $p.Handle  # keeps ExitCode readable after the process ends
    return $p
}
function Wait-Sample($p, [int]$secs) {
    if (-not $p.WaitForExit($secs * 1000)) { Stop-Process -Id $p.Id -Force; return -1 }
    return $p.ExitCode
}
function Probe-Line([string]$name) { Get-Content "$out\$name.log" -ErrorAction SilentlyContinue | Where-Object { $_ -like 'PROBE*' } | Select-Object -First 1 }
$cache = Join-Path $env:LOCALAPPDATA 'dev.supermux.webview\cache'
function Cached { @(Get-ChildItem $cache -Recurse -Filter 'webview_windows.dll' -File -ErrorAction SilentlyContinue) }
function HashOf([string]$path) { (Get-FileHash $path -Algorithm SHA256).Hash.ToLower() }

# 1. Stray runs end (as run-sample.ps1 ends them), then the cache goes.
Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe' OR Name = 'java.exe'" | Where-Object { $_.CommandLine -like '*-Dwvc.run=*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Get-CimInstance Win32_Process -Filter "Name = 'msedgewebview2.exe'" | Where-Object { $_.CommandLine -like "*dev.supermux.webview\webview2\$App*" } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 2
Remove-Item -Recurse -Force $cache -ErrorAction SilentlyContinue
Report 1 (-not (Test-Path $cache)) "stray runs ended, the cache removed ($cache)"

# The hash the jar records for its DLL (native.properties, staged by :webview-compose:stageWindowsGlue).
Add-Type -AssemblyName System.IO.Compression.FileSystem
$recorded = ''
foreach ($jar in Get-ChildItem "$root\$Lib" -Filter '*.jar') {
    $zip = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
    try {
        $entry = $zip.GetEntry('dev/supermux/webview/native/windows-x64/native.properties')
        if ($entry) {
            $reader = New-Object IO.StreamReader($entry.Open())
            $recorded = [regex]::Match($reader.ReadToEnd(), 'sha256=([0-9a-f]{64})').Groups[1].Value
            $reader.Close()
        }
    } finally { $zip.Dispose() }
    if ($recorded) { break }
}

# 2. A process of the app makes an engine: exactly one DLL in the cache, in a folder named by its hash, the jar's.
$code = Wait-Sample (Start-Sample @('-Dwebview.sample.probe=shared-check') 'probe-1') 300
$line = Probe-Line 'probe-1'
$dlls = Cached
$hash = if ($dlls.Count -eq 1) { HashOf $dlls[0].FullName } else { '' }
$folder = if ($dlls.Count -eq 1) { $dlls[0].Directory.Name } else { '' }
Report 2 ($line -eq 'PROBE Created' -and $dlls.Count -eq 1 -and $hash -eq $recorded -and $folder -eq $recorded) `
    "probe '$line' exit $code; $($dlls.Count) DLL(s) $($dlls.FullName -join ', '); its hash $hash; the jar's $recorded"

# 3. One byte of it flipped: the next process copies a fresh one in, and loads that.
$tampered = ''
if ($dlls.Count -eq 1) {
    $bytes = [IO.File]::ReadAllBytes($dlls[0].FullName)
    $at = [int]($bytes.Length / 2)
    $bytes[$at] = $bytes[$at] -bxor 0xFF
    [IO.File]::WriteAllBytes($dlls[0].FullName, $bytes)
    $tampered = HashOf $dlls[0].FullName
}
$code = Wait-Sample (Start-Sample @('-Dwebview.sample.probe=shared-check') 'probe-2') 300
$line = Probe-Line 'probe-2'
$dlls = Cached
$after = if ($dlls.Count -eq 1) { HashOf $dlls[0].FullName } else { '' }
Report 3 ($tampered -and $tampered -ne $recorded -and $line -eq 'PROBE Created' -and $dlls.Count -eq 1 -and $after -eq $recorded) `
    "tampered to $tampered; probe '$line' exit $code; then $($dlls.Count) DLL(s), hash $after"

# 4. Two instances at once: A runs the ephemeral check (an engine, a slot, a probe of its own) and the shared-profile
#    one (another engine, another probe); B starts once A's checks have begun, and must be done before A is: A's whole
#    run is about 4 s, so a fixed wait either misses it or leaves B nothing to overlap.
$a = Start-Sample @('-Dwebview.sample.checks=windows',
    '-Dwebview.sample.only=two-processes-never-share-an-ephemeral-store,a-second-process-of-the-app-shares-its-profiles') 'instance-a'
$begun = { [bool](Select-String -Path "$out\instance-a.log" -Pattern '^CHECKS START' -Quiet -ErrorAction SilentlyContinue) }
$deadline = (Get-Date).AddSeconds(120)
while (-not $a.HasExited -and -not (& $begun) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 50 }
$runningAtStart = -not $a.HasExited
$codeB = Wait-Sample (Start-Sample @('-Dwebview.sample.probe=shared-check') 'instance-b') 300
$runningAtEnd = -not $a.HasExited
$codeA = Wait-Sample $a 600
$lineB = Probe-Line 'instance-b'
$passA = [bool](Select-String -Path "$out\instance-a.log" -Pattern '^CHECK PASS two-processes-never-share-an-ephemeral-store' -Quiet)
$dlls = Cached
Report 4 ($runningAtStart -and $runningAtEnd -and $lineB -eq 'PROBE Created' -and $passA -and $codeA -eq 0 -and $codeB -eq 0 -and $dlls.Count -eq 1) `
    "A running when B started: $runningAtStart, when B ended: $runningAtEnd; B '$lineB' exit $codeB; A passed: $passA, exit $codeA; $($dlls.Count) DLL(s)"

# 5. What InPrivate profiles left in the app's data folder (V3), and no slot lock outlives its process.
$data = Join-Path $env:LOCALAPPDATA "dev.supermux.webview\webview2\$App"
$profiles = @(Get-ChildItem "$data\EBWebView" -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -like 'WV2Profile_*' } | ForEach-Object Name)
$locks = @(Get-ChildItem "$data\ephemeral" -Filter 'slot-*.lock' -File -ErrorAction SilentlyContinue)
$held = @()
foreach ($lock in $locks) {
    try { ([IO.File]::Open($lock.FullName, 'Open', 'ReadWrite', 'None')).Close() } catch { $held += $lock.Name }
}
Report 5 ($locks.Count -gt 0 -and $held.Count -eq 0) "$($locks.Count) slot lock(s) ($($locks.Name -join ', ')), held: $($held.Count); profile folders: $($profiles -join ', ')"
"CACHE DONE failed=$script:failed"
