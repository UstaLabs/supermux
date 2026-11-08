# Installs the Japanese typing components (the Microsoft IME's Language.Basic capability) that adding ja-JP to the
# language list doesn't: without them Windows says "Japanese IME is not ready yet". Run it with vm.sh ps. Servicing
# refuses the SSH session's token ("Access is denied"), so the install runs as a SYSTEM scheduled task (wv2-ime), as
# the Build Tools install does; this script starts it, waits up to 25 minutes and prints its log. Idempotent.
param([switch]$AsSystem)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$name = 'Language.Basic~~~ja-JP~0.0.1.0'
$log = 'C:\wv2test\ui\ime-capability.log'

if ($AsSystem) {
    try {
        $state = (Get-WindowsCapability -Online -Name $name).State
        if ($state -ne 'Installed') {
            "ime: installing $name ($state)" | Out-File -Append -Encoding ascii $log
            Add-WindowsCapability -Online -Name $name | Out-Null
        }
        "ime: $name $((Get-WindowsCapability -Online -Name $name).State)" | Out-File -Append -Encoding ascii $log
    } catch {
        "ime: Add-WindowsCapability failed: $($_.Exception.Message)" | Out-File -Append -Encoding ascii $log
        # The language-pack route (Windows 11's LanguagePackManagement module): the pack plus its features.
        try {
            "ime: trying Install-Language ja-JP" | Out-File -Append -Encoding ascii $log
            Install-Language -Language ja-JP | Out-Null
            "ime: Install-Language done; $name $((Get-WindowsCapability -Online -Name $name).State)" |
                Out-File -Append -Encoding ascii $log
        } catch {
            "ime: Install-Language failed: $($_.Exception.Message)" | Out-File -Append -Encoding ascii $log
        }
    }
    'ime: done' | Out-File -Append -Encoding ascii $log
    return
}

$state = (Get-WindowsCapability -Online -Name $name).State
if ($state -eq 'Installed') { "ime: $name Installed" } else {
    Remove-Item $log -ErrorAction SilentlyContinue
    $script = 'C:\wv2test\vm\ime-capability.ps1'
    New-Item -ItemType Directory -Force C:\wv2test\vm | Out-Null
    Copy-Item $PSCommandPath $script -Force
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -ExecutionPolicy Bypass -File $script -AsSystem"
    $principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
    $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit (New-TimeSpan -Hours 1) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
    Register-ScheduledTask -TaskName wv2-ime -Action $action -Principal $principal -Settings $settings -Force | Out-Null
    Start-ScheduledTask -TaskName wv2-ime
    $start = Get-Date
    Start-Sleep -Seconds 3
    while ((Get-ScheduledTask -TaskName wv2-ime).State -eq 'Running' -and ((Get-Date) - $start).TotalMinutes -lt 25) {
        Start-Sleep -Seconds 5
    }
    Get-Content $log -ErrorAction SilentlyContinue
}
Get-WindowsCapability -Online -Name 'Language.*ja-JP*' | ForEach-Object { "ime: available $($_.Name) $($_.State)" }
"disk C: free {0:N1} GB" -f ((Get-Volume -DriveLetter C).SizeRemaining / 1GB)
