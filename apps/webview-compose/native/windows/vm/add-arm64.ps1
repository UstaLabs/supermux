# Adds the MSVC ARM64 cross tools (and the Windows SDK's ARM64 desktop libraries) to C:\BuildTools, as SYSTEM through a
# one-shot scheduled task: servicing refuses the SSH session's token (W20). Safe to repeat: the installer adds nothing that
# is there. Needs about 3 GB free on C:. (winvm.sh ps; plan Task 32)
$ErrorActionPreference = 'Stop'
if ((Get-Volume -DriveLetter C).SizeRemaining -lt 6GB) { 'arm64: under 6 GB free on C:'; exit 1 }
$installer = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\setup.exe"
$log = 'C:\wvc\ui\add-arm64.log'
New-Item -ItemType Directory -Force C:\wvc\ui | Out-Null
# --wait: without it setup.exe hands the work to a process of its own and returns at once (exit 0, nothing added yet).
$command = "& '$installer' modify --installPath C:\BuildTools --add Microsoft.VisualStudio.Component.VC.Tools.ARM64 " +
    "--quiet --norestart --nocache --wait *> '$log'; exit `$LASTEXITCODE"
$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -ExecutionPolicy Bypass -Command `"$command`""
Register-ScheduledTask -TaskName 'wvc-add-arm64' -Action $action -User 'SYSTEM' -RunLevel Highest -Force | Out-Null
Start-ScheduledTask -TaskName 'wvc-add-arm64'
$start = Get-Date
do { Start-Sleep 10 } while ((Get-ScheduledTask -TaskName 'wvc-add-arm64').State -eq 'Running' -and ((Get-Date) - $start).TotalMinutes -lt 60)
$result = (Get-ScheduledTaskInfo -TaskName 'wvc-add-arm64').LastTaskResult
Unregister-ScheduledTask -TaskName 'wvc-add-arm64' -Confirm:$false
"arm64: installer exit $result"
Get-Content $log -Tail 20 -ErrorAction SilentlyContinue
$vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
"arm64: tools $(if (& $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.ARM64 -property installationPath) { 'present' } else { 'MISSING' })"
"arm64: SDK libs $(if (Test-Path 'C:\Program Files (x86)\Windows Kits\10\Lib\10.0.26100.0\um\arm64\kernel32.lib') { 'present' } else { 'MISSING' })"
"arm64: C: free {0:N1} GB" -f ((Get-Volume -DriveLetter C).SizeRemaining / 1GB)
