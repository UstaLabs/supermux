$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$Report = 'C:\Windows\Temp\auth-smoke.txt'
function Out([string]$m) { Add-Content -LiteralPath $Report -Value $m; Write-Host $m }
Remove-Item -LiteralPath $Report -Force -ErrorAction SilentlyContinue
Out 'START'

$MuxHome = 'C:\Windows\Temp\supermux-auth-smoke'
$Port = 18798
$Base = "http://127.0.0.1:$Port"
$Broker = 'C:\supermux-source\apps\desktop\resources\windows-x64\supermux-broker.exe'
$Sessiond = 'C:\tools\run-sessiond.cmd'
$Bun = 'C:\tools\bun-x64-baseline\bun-windows-x64-baseline\bun.exe'
$Workdir = 'C:\Windows\Temp\supermux-auth-workdir'
$state = Join-Path $MuxHome 'state'
$Source = 'C:\supermux-source'

Remove-Item -LiteralPath $MuxHome -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $state, $Workdir | Out-Null
$stub = Join-Path $MuxHome 'stubbin'
New-Item -ItemType Directory -Force -Path $stub | Out-Null
Set-Content -LiteralPath (Join-Path $stub 'claude.cmd') -Value "@echo off`r`npowershell.exe -NoProfile -Command `"Start-Sleep -Seconds 600`"`r`n" -Encoding Ascii

$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$bytes = New-Object byte[] 32
$rng.GetBytes($bytes)
$token = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
$sha = [Security.Cryptography.SHA256]::Create()
$hash = ($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($token)) | ForEach-Object { $_.ToString('x2') }) -join ''
$now = (Get-Date).ToUniversalTime().ToString('o')
$devicesJson = "[{`"token_hash`":`"$hash`",`"name`":`"auth-smoke`",`"created_at`":`"$now`",`"last_seen_at`":null}]"
[System.IO.File]::WriteAllText((Join-Path $state 'devices.json'), $devicesJson, [Text.UTF8Encoding]::new($false))
Out 'DEVICE_MINTED'

$env:PATH = "C:\tools\bun-x64-baseline\bun-windows-x64-baseline;$stub;$env:PATH"
$sessPsi = [Diagnostics.ProcessStartInfo]::new()
$sessPsi.FileName = $Bun
$sessPsi.Arguments = "`"C:\supermux-source\src\core\sessiond\main.ts`" --state-dir `"$state`""
$sessPsi.UseShellExecute = $false
$sessPsi.CreateNoWindow = $true
$sessPsi.RedirectStandardOutput = $true
$sessPsi.RedirectStandardError = $true
$sessPsi.WorkingDirectory = $Source
$script:sessiond = [Diagnostics.Process]::Start($sessPsi)
Out ("SESSIOND_PID=" + $script:sessiond.Id)
Start-Sleep -Seconds 3
if ($script:sessiond.HasExited) { throw "sessiond exited $($script:sessiond.ExitCode)" }
Out 'SESSIOND_RUNNING'

function Start-Broker {
  $psi = [Diagnostics.ProcessStartInfo]::new()
  $psi.FileName = $Broker
  $psi.UseShellExecute = $false
  $psi.CreateNoWindow = $true
  $psi.RedirectStandardOutput = $true
  $psi.RedirectStandardError = $true
  $psi.Environment['MUX_HOME'] = $MuxHome
  $psi.Environment['MUX_STATE_DIR'] = $state
  $psi.Environment['MUX_WEB_PORT'] = "$Port"
  $psi.Environment['MUX_WEB_PUBLIC_URL'] = $Base
  $psi.Environment['MUX_SESSIOND_PATH'] = $Sessiond
  $psi.Environment['PATH'] = "$stub;C:\tools\bun-x64-baseline\bun-windows-x64-baseline;$env:PATH"
  return [Diagnostics.Process]::Start($psi)
}
$script:broker = Start-Broker
Out ("BROKER_PID=" + $script:broker.Id)

function Wait-Host([int]$sec = 50) {
  $deadline = [DateTime]::UtcNow.AddSeconds($sec)
  while ([DateTime]::UtcNow -lt $deadline) {
    if ($script:broker.HasExited) { throw "broker exited $($script:broker.ExitCode)" }
    try {
      $r = Invoke-RestMethod -Uri "$Base/host" -TimeoutSec 2
      if ($r.hostId) { return $r }
    } catch {}
    Start-Sleep -Milliseconds 300
  }
  throw 'host not ready'
}
$hostInfo = Wait-Host
Out ("HOST_ID=" + $hostInfo.hostId)
Out 'SUPERMUX_WINDOWS_BOOT_OK'

$seed = @'
import { Database } from "bun:sqlite"
import { randomUUID } from "crypto"
const db = new Database(process.env.DB_PATH!)
const id = randomUUID()
const now = new Date().toISOString()
db.run(
  `INSERT INTO sessions (id, name, status, agent, workdir, mute, can_orchestrate, role, is_default, internal, created_at)
   VALUES (?, 'smoke-sess', 'active', 'claude', ?, 0, 0, 'worker', 0, 0, ?)`,
  [id, process.env.WORKDIR!, now],
)
process.stdout.write(id)
'@
$seedPath = Join-Path $MuxHome 'seed-session.ts'
Set-Content -LiteralPath $seedPath -Value $seed -Encoding UTF8
$env:DB_PATH = (Join-Path $state 'db.sqlite3')
$env:WORKDIR = $Workdir
$sessionId = (& $Bun $seedPath).Trim()
Out ("SESSION_ID=" + $sessionId)
Out 'SESSION_SEEDED'

$env:BASE_URL = $Base
$env:DEVICE_TOKEN = $token
$env:SESSION_ID = $sessionId
Out 'TERM_PROBE_START'
& $Bun 'C:\Windows\Temp\term-ws-probe.ts'
if ($LASTEXITCODE -ne 0) { throw "term probe failed exit=$LASTEXITCODE" }
if (Test-Path C:\Windows\Temp\term-ws.txt) { Get-Content C:\Windows\Temp\term-ws.txt | ForEach-Object { Out $_ } }
Out 'TERM_PROBE_OK'

if ($script:sessiond.HasExited) { throw 'sessiond died early' }
Stop-Process -Id $script:broker.Id -Force
$script:broker.WaitForExit(15000) | Out-Null
Out 'BROKER_STOPPED'
Start-Sleep -Seconds 1
if ($script:sessiond.HasExited) { throw 'sessiond died when broker killed' }
Out 'SESSIOND_SURVIVED_BROKER_KILL'

$script:broker = Start-Broker
Out ("BROKER_RESTARTED_PID=" + $script:broker.Id)
$hostInfo = Wait-Host 50
Out ("HOST_ID2=" + $hostInfo.hostId)

$env:BASE_URL = $Base
$env:DEVICE_TOKEN = $token
$env:SESSION_ID = $sessionId
& $Bun 'C:\Windows\Temp\term-ws-probe.ts'
if ($LASTEXITCODE -ne 0) { throw "term probe after restart failed exit=$LASTEXITCODE" }
Out 'SUPERMUX_WINDOWS_BROKER_RESTART_OK'
Out 'SUPERMUX_WINDOWS_AUTH_E2E_OK'
Out 'DONE'

try { Stop-Process -Id $script:broker.Id -Force } catch {}
try { Stop-Process -Id $script:sessiond.Id -Force } catch {}
try { Get-Process bun -ErrorAction SilentlyContinue | Stop-Process -Force } catch {}
