# The Windows lane's broker smoke, run as a STANDARD user (run-as-standard-user.ps1). The broker and sessiond run
# from source with Bun, in a throwaway profile:
#   1. A file symlink fails with EPERM and a junction works: the lane sees what users without Developer Mode see.
#   2. sessiond and the broker boot, and GET /host answers.
#   3. A paired device types into a session's scratch terminal over the WebSocket and reads the output (ConPTY).
#   4. The broker is killed; sessiond survives; a restarted broker reattaches to the same terminal.
# Later steps of the platform plan add their regression checks here (#36 one broker, #37 links, account sign-in).
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Repo,
    [Parameter(Mandatory = $true)][string]$Bun,
    [int]$Port = 18791
)
$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$base = "http://127.0.0.1:$Port"
$muxHome = Join-Path ([IO.Path]::GetTempPath()) ("supermux-ci-smoke-" + [Guid]::NewGuid().ToString("N").Substring(0, 8))
$state = Join-Path $muxHome "state"
$work = Join-Path $muxHome "work"
New-Item -ItemType Directory -Force -Path $state, $work | Out-Null
Write-Output ("SMOKE_USER=" + [Security.Principal.WindowsIdentity]::GetCurrent().Name)
Write-Output "SMOKE_MUX_HOME=$muxHome"

# 1. What a standard user without Developer Mode gets.
$probe = Join-Path $muxHome "link-probe"
New-Item -ItemType Directory -Force -Path (Join-Path $probe "dir") | Out-Null
Set-Content -LiteralPath (Join-Path $probe "file.txt") -Value "x"
$probeScript = Join-Path $muxHome "link-probe.cjs"
@'
const fs = require("fs"), path = require("path"), dir = process.argv[2]
const attempt = (label, fn) => { try { fn(); console.log(label + "=ok") } catch (e) { console.log(label + "=" + (e.code ?? e.message)) } }
attempt("FILE_SYMLINK", () => fs.symlinkSync(path.join(dir, "file.txt"), path.join(dir, "file-link.txt"), "file"))
attempt("JUNCTION", () => fs.symlinkSync(path.join(dir, "dir"), path.join(dir, "dir-junction"), "junction"))
'@ | Set-Content -LiteralPath $probeScript -Encoding UTF8
$links = @(& $Bun $probeScript $probe)
Write-Output ("SMOKE_LINKS " + ($links -join " "))
if ($links -notcontains "FILE_SYMLINK=EPERM") { throw "a file symlink should fail with EPERM for a standard user without Developer Mode; got: $links" }
if ($links -notcontains "JUNCTION=ok") { throw "a junction should work for a standard user; got: $links" }
Write-Output "SMOKE_STANDARD_USER_OK"

# 2. Boot, with a paired device minted straight into the state folder.
$bytes = [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
$token = [Convert]::ToBase64String($bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_")
$hash = ([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($token)) | ForEach-Object { $_.ToString("x2") }) -join ""
$now = (Get-Date).ToUniversalTime().ToString("o")
[IO.File]::WriteAllText((Join-Path $state "devices.json"), "[{`"token_hash`":`"$hash`",`"name`":`"ci-smoke`",`"created_at`":`"$now`",`"last_seen_at`":null}]", [Text.UTF8Encoding]::new($false))

$sessiondCmd = Join-Path $muxHome "run-sessiond.cmd"
Set-Content -LiteralPath $sessiondCmd -Value "@echo off`r`n`"$Bun`" `"$Repo\src\core\sessiond\main.ts`" %*`r`n" -Encoding Ascii
$stub = Join-Path $muxHome "stubbin"
New-Item -ItemType Directory -Force -Path $stub | Out-Null
Set-Content -LiteralPath (Join-Path $stub "claude.cmd") -Value "@echo off`r`nexit /b 0`r`n" -Encoding Ascii

$env:MUX_HOME = $muxHome
$env:MUX_STATE_DIR = $state
$env:MUX_WEB_PORT = "$Port"
$env:MUX_WEB_PUBLIC_URL = $base
$env:MUX_SESSIOND_PATH = $sessiondCmd
$env:DO_NOT_TRACK = "1"
$env:PATH = "$stub;" + (Split-Path -Parent $Bun) + ";$env:PATH"

function Start-Logged([string[]]$ArgumentList, [string]$Name) {
    $quoted = $ArgumentList | ForEach-Object { '"' + $_ + '"' }
    Start-Process -FilePath $Bun -ArgumentList $quoted -WorkingDirectory $Repo -NoNewWindow -PassThru `
        -RedirectStandardOutput (Join-Path $muxHome "$Name.out.log") -RedirectStandardError (Join-Path $muxHome "$Name.err.log")
}
function Show-Log([string]$Name) {
    foreach ($suffix in "out", "err") {
        $path = Join-Path $muxHome "$Name.$suffix.log"
        if (Test-Path -LiteralPath $path) { Get-Content -LiteralPath $path -Tail 40 | ForEach-Object { "  [$Name.$suffix] $_" } }
    }
}
function Wait-Host($Broker, [int]$Seconds = 180) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        if ($Broker.HasExited) { Show-Log "broker"; throw "the broker exited with $($Broker.ExitCode)" }
        try { $info = Invoke-RestMethod -Uri "$base/host" -TimeoutSec 2; if ($info.hostId) { return $info } } catch {}
        Start-Sleep -Milliseconds 500
    }
    Show-Log "broker"
    throw "GET $base/host did not answer within $Seconds s"
}
function Invoke-Probe([string]$Mode) {
    $env:BASE_URL = $base; $env:DEVICE_TOKEN = $token; $env:SESSION_ID = $script:sessionId; $env:MODE = $Mode
    & $Bun (Join-Path $Repo "scripts\windows-vm\ci-term-probe.ts")
    if ($LASTEXITCODE -ne 0) { Show-Log "broker"; Show-Log "sessiond"; throw "the terminal probe ($Mode) failed with $LASTEXITCODE" }
}

# What this user can read in the checkout, three ways, so a failure below says whose it is.
$entry = Join-Path $Repo "src\core\sessiond\main.ts"
$psRead = try { $null -ne (Get-Content -LiteralPath $entry -TotalCount 1) } catch { $_.Exception.Message }
Write-Output "SMOKE_DIAG powershell-read=$psRead"
Write-Output ("SMOKE_DIAG acl=" + ((icacls $entry) -join " ; "))
$env:DIAG_PATH = $entry
$bunRead = & $Bun -e "try { require('fs').readFileSync(process.env.DIAG_PATH, 'utf8'); console.log('ok') } catch (e) { console.log(String(e.code) + ' ' + e.message) }" 2>&1
Write-Output ("SMOKE_DIAG bun-read=" + ($bunRead -join " "))

$sessiond = $null
$broker = $null
try {
    $sessiond = Start-Logged @("$Repo\src\core\sessiond\main.ts", "--state-dir", $state) "sessiond"
    Start-Sleep -Seconds 3
    if ($sessiond.HasExited) { Show-Log "sessiond"; throw "sessiond exited with $($sessiond.ExitCode)" }
    $broker = Start-Logged @("$Repo\src\main.ts") "broker"
    $info = Wait-Host $broker
    Write-Output "SMOKE_BOOT_OK host=$($info.hostId)"

    # 2b. A second broker on the same state directory refuses cleanly (#36): it exits non-zero saying it is already
    # running, broker.pid still names the first, and the first still answers.
    $pidFile = Join-Path $state "broker.pid"
    $firstPid = (Get-Content -LiteralPath $pidFile -Raw).Trim()
    $second = Start-Logged @("$Repo\src\main.ts") "broker-second"
    $null = $second.Handle # keep the handle, or PowerShell reports no ExitCode once it has exited
    if (-not $second.WaitForExit(60000)) {
        Stop-Process -Id $second.Id -Force; Show-Log "broker-second"; throw "a second broker did not stop within 60 s"
    }
    $secondLog = @("out", "err") | ForEach-Object { Get-Content -LiteralPath (Join-Path $muxHome "broker-second.$_.log") -Raw }
    if ($second.ExitCode -eq 0 -or (($secondLog -join "`n") -notmatch "already running")) {
        Show-Log "broker-second"; throw "a second broker exited with $($second.ExitCode) without saying it is already running"
    }
    if ((Get-Content -LiteralPath $pidFile -Raw).Trim() -ne $firstPid) { throw "the second broker changed broker.pid" }
    Wait-Host $broker 30 | Out-Null
    Write-Output "SMOKE_SECOND_BROKER_REFUSED"

    # 3. A session, and its scratch terminal through the WebSocket.
    $seed = Join-Path $muxHome "seed-session.ts"
    @'
import { Database } from "bun:sqlite"
import { randomUUID } from "crypto"
const db = new Database(process.env.DB_PATH!)
const id = randomUUID()
db.run(
  `INSERT INTO sessions (id, name, status, agent, workdir, mute, can_orchestrate, role, is_default, internal, created_at)
   VALUES (?, 'ci-smoke', 'active', 'claude', ?, 0, 0, 'worker', 0, 0, ?)`,
  [id, process.env.WORKDIR!, new Date().toISOString()],
)
process.stdout.write(id)
'@ | Set-Content -LiteralPath $seed -Encoding UTF8
    $env:DB_PATH = Join-Path $state "db.sqlite3"
    $env:WORKDIR = $work
    $script:sessionId = (& $Bun $seed).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $script:sessionId) { throw "seeding a session failed" }
    Invoke-Probe "write"
    Write-Output "SMOKE_TERMINAL_OK"

    # 4. Kill the broker: sessiond and the terminal survive, and a new broker reattaches.
    Stop-Process -Id $broker.Id -Force
    $broker.WaitForExit(15000) | Out-Null
    Start-Sleep -Seconds 1
    if ($sessiond.HasExited) { Show-Log "sessiond"; throw "sessiond died with the broker" }
    $broker = Start-Logged @("$Repo\src\main.ts") "broker-restart"
    Wait-Host $broker | Out-Null
    Invoke-Probe "reattach"
    Write-Output "SMOKE_RESTART_OK"
} finally {
    foreach ($process in @($broker, $sessiond)) {
        if ($process -and -not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
    }
}
Write-Output "SMOKE_DONE"
