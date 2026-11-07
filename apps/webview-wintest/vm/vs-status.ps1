# Where the Build Tools install stands: the task, its exit code, the installer processes, the instance, the log, disk.
$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
$task = Get-ScheduledTask -TaskName wv2-vs -ErrorAction SilentlyContinue
"task: $(if ($task) { $task.State } else { 'none' })"
$result = 'C:\wv2test\ui\vs-install.txt'
if (Test-Path $result) { "result: $((Get-Content $result) -join ' ')  (0 = done, 3010 = done but wants a reboot)" }
$names = 'vs_BuildTools', 'vs_installer', 'vs_installershell', 'setup'
"installer processes: $((Get-Process -Name $names -ErrorAction SilentlyContinue | ForEach-Object { $_.Name }) -join ', ')"
if (Test-Path $vswhere) { & $vswhere -all -products * -format text | Select-String 'installationPath|isComplete|installationVersion' }
$newest = Get-ChildItem 'C:\Windows\SystemTemp\dd_setup_*.log', 'C:\Windows\Temp\dd_setup_*.log' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime | Select-Object -Last 1
if ($newest) { "log: $($newest.FullName)"; Get-Content $newest -Tail 5 }
"disk C: free {0:N1} GB" -f ((Get-Volume -DriveLetter C).SizeRemaining / 1GB)
