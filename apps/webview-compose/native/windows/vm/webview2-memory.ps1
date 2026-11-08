# The memory of one app's WebView2 processes: its browser process (the one whose command line names the app's user
# data folder and has no --type=) and every process below it. Prints "<private bytes> <working set> <processes>
# <the GPU process's private bytes> <its working set>" (the GPU process: --type=gpu-process; its share of the sum).
param([Parameter(Mandatory = $true)][string]$App)
$folder = "dev.supermux.webview\webview2\$App"
$all = @(Get-CimInstance Win32_Process -Filter "Name = 'msedgewebview2.exe'")
$ids = New-Object 'System.Collections.Generic.HashSet[int]'
foreach ($p in $all) { if ($p.CommandLine -like "*$folder*" -and $p.CommandLine -notlike '*--type=*') { [void]$ids.Add([int]$p.ProcessId) } }
do {
    $added = 0
    foreach ($p in $all) {
        if (-not $ids.Contains([int]$p.ProcessId) -and $ids.Contains([int]$p.ParentProcessId)) { [void]$ids.Add([int]$p.ProcessId); $added++ }
    }
} while ($added -gt 0)
$gpuIds = New-Object 'System.Collections.Generic.HashSet[int]'
foreach ($p in $all) { if ($ids.Contains([int]$p.ProcessId) -and $p.CommandLine -like '*--type=gpu-process*') { [void]$gpuIds.Add([int]$p.ProcessId) } }
$private = 0L; $ws = 0L; $n = 0; $gpuPrivate = 0L; $gpuWs = 0L
foreach ($id in $ids) {
    $q = Get-Process -Id $id -ErrorAction SilentlyContinue
    if ($q) {
        $private += $q.PrivateMemorySize64; $ws += $q.WorkingSet64; $n++
        if ($gpuIds.Contains($id)) { $gpuPrivate += $q.PrivateMemorySize64; $gpuWs += $q.WorkingSet64 }
    }
}
"{0} {1} {2} {3} {4}" -f $private, $ws, $n, $gpuPrivate, $gpuWs
