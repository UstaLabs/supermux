# Runs a PowerShell script as a fresh local STANDARD user (in Users only: no administrator, and the Windows lane
# turns Developer Mode off first), prints its output, and exits with its exit code.
#
# Why: GitHub's Windows runners run as an administrator with UAC off, so on the bare runner every symlink succeeds
# and the whole "EPERM: operation not permitted, symlink" class (#37) stays invisible. A Scheduled Task holding the
# user's password is the one dependable way to start a process as another user from a non-interactive job.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Script,
    [string[]]$ScriptArgs = @(),
    # Folders the user must read: the checkout, the Bun copy, Bun's install cache. Read+execute, inherited.
    [string[]]$ReadPaths = @(),
    [string]$UserName = "supermux-ci",
    [int]$TimeoutSeconds = 900
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Quote([string]$Text) { "'" + $Text.Replace("'", "''") + "'" }

$tempRoot = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [IO.Path]::GetTempPath() }
$work = Join-Path $tempRoot "std-user-run"
New-Item -ItemType Directory -Force -Path $work | Out-Null
$out = Join-Path $work "out.log"
$rc = Join-Path $work "exit-code.txt"
Remove-Item -LiteralPath $out, $rc -ErrorAction SilentlyContinue

# A throwaway password: the user and the task live for this job only.
$password = "Aa1!" + [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$secure = ConvertTo-SecureString $password -AsPlainText -Force
if (Get-LocalUser -Name $UserName -ErrorAction SilentlyContinue) {
    Set-LocalUser -Name $UserName -Password $secure
} else {
    New-LocalUser -Name $UserName -Password $secure -PasswordNeverExpires -AccountNeverExpires | Out-Null
}
Add-LocalGroupMember -Group "Users" -Member $UserName -ErrorAction SilentlyContinue
$admins = @(Get-LocalGroupMember -Group "Administrators" | Where-Object { $_.Name -like "*\$UserName" })
if ($admins.Count -gt 0) { throw "$UserName must not be an administrator" }

# A task that stores a password logs on as a batch job, a right Windows grants only to administrators by default.
$sid = (Get-LocalUser -Name $UserName).SID.Value
$inf = Join-Path $work "rights.inf"
$db = Join-Path $work "rights.sdb"
secedit /export /cfg $inf /areas USER_RIGHTS | Out-Null
$rights = Get-Content -LiteralPath $inf
if ($rights -match '^SeBatchLogonRight\s*=') {
    $rights = $rights | ForEach-Object { if ($_ -match '^SeBatchLogonRight\s*=') { "$_,*$sid" } else { $_ } }
} else {
    $rights = $rights | ForEach-Object { if ($_ -eq '[Privilege Rights]') { $_; "SeBatchLogonRight = *$sid" } else { $_ } }
}
Set-Content -LiteralPath $inf -Value $rights -Encoding Unicode
secedit /configure /db $db /cfg $inf /areas USER_RIGHTS | Out-Null

foreach ($path in $ReadPaths) { icacls $path /grant "${UserName}:(OI)(CI)RX" /Q | Out-Null }
icacls $work /grant "${UserName}:(OI)(CI)M" /Q | Out-Null

# The task runs a wrapper that captures the script's output and exit code into files this job can read.
$argList = ($ScriptArgs | ForEach-Object { Quote $_ }) -join " "
$wrapper = Join-Path $work "run.ps1"
@"
`$ErrorActionPreference = 'Continue'
`$code = 1
try {
    & $(Quote $Script) $argList *> $(Quote $out)
    `$code = if (`$null -ne `$LASTEXITCODE) { `$LASTEXITCODE } else { 0 }
} catch {
    `$_ | Out-File -Append -LiteralPath $(Quote $out)
    `$code = 1
}
Set-Content -LiteralPath $(Quote $rc) -Value ([int]`$code)
"@ | Set-Content -LiteralPath $wrapper -Encoding UTF8

$pwsh = (Get-Command pwsh).Source
$action = New-ScheduledTaskAction -Execute $pwsh -Argument "-NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"$wrapper`""
$taskName = "supermux-standard-user-run"
Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
Register-ScheduledTask -TaskName $taskName -Action $action -User $UserName -Password $password -RunLevel Limited | Out-Null
Start-ScheduledTask -TaskName $taskName
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
while (-not (Test-Path -LiteralPath $rc) -and (Get-Date) -lt $deadline) { Start-Sleep -Seconds 2 }
if (Test-Path -LiteralPath $out) { Get-Content -LiteralPath $out }
Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
if (-not (Test-Path -LiteralPath $rc)) { throw "the standard-user run did not finish within $TimeoutSeconds s" }
exit ([int](Get-Content -LiteralPath $rc -Raw).Trim())
