# Which processes use which GPU adapter right now: Windows' per-process GPU counters (instances carry the PID and the
# adapter's LUID), summed over each process's engines across a 5 s sample, plus its dedicated GPU memory. Adapter names
# come from gpu-probe.exe (vm/gpu-probe.ps1 builds it). Run it with `vm.sh ps` while the test app runs: it shows whether
# dwm, msedgewebview2 (WebView2's GPU process) and javaw (skiko and the capture device) are on the NVIDIA GPU.
$names = @{}
foreach ($line in (& C:\wv2test\vm\gpu-probe.exe)) {
    if ($line -match '^\s+\[\d+\] (.+?)\s+ven .* luid 0x([0-9a-f]+):0x([0-9a-f]+)') {
        $names["0x$($Matches[2])_0x$($Matches[3])".ToLower()] = $Matches[1]
    }
}
$processes = @{}
Get-Process | ForEach-Object { $processes[$_.Id] = $_.ProcessName }
function Key($instance) {
    if ($instance -match 'pid_(\d+)_luid_(0x[0-9a-f]+_0x[0-9a-f]+)') { return @([int]$Matches[1], $Matches[2].ToLower()) }
    return $null
}
$util = @{}
(Get-Counter '\GPU Engine(*)\Utilization Percentage' -SampleInterval 5 -MaxSamples 1).CounterSamples | ForEach-Object {
    $k = Key $_.InstanceName
    if ($k) { $id = "$($k[0])|$($k[1])"; $util[$id] = [double]($util[$id]) + $_.CookedValue }
}
$memory = @{}
(Get-Counter '\GPU Process Memory(*)\Dedicated Usage').CounterSamples | ForEach-Object {
    $k = Key $_.InstanceName
    if ($k) { $id = "$($k[0])|$($k[1])"; $memory[$id] = [double]($memory[$id]) + $_.CookedValue }
}
$rows = foreach ($id in ($util.Keys + $memory.Keys | Sort-Object -Unique)) {
    $pidText, $luid = $id -split '\|'
    $mb = [double]($memory[$id]) / 1MB
    $busy = [double]($util[$id])
    if ($busy -lt 0.05 -and $mb -lt 1) { continue }
    [pscustomobject]@{
        Process = $processes[[int]$pidText]; Pid = [int]$pidText
        Adapter = $(if ($names[$luid]) { "$($names[$luid]) ($luid)" } else { $luid })
        'Busy%' = [math]::Round($busy, 1); 'DedicatedMB' = [math]::Round($mb, 0)
    }
}
$rows | Sort-Object Adapter, Process | Format-Table -AutoSize | Out-String -Width 220
