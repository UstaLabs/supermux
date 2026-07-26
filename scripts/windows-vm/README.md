# Native Windows VM validation

These scripts validate the packaged native-Windows host without WSL. The
authoritative development guest is UTM VM
`A18E7828-0D27-4A10-8549-470AA206B787`
(`Windows 11 - Supermux Test`) on the remote Mac.

## Build and install

From a Windows checkout with the three staged files under
`apps\desktop\resources\windows-x64`:

```powershell
cd apps
.\gradlew.bat :desktop:packageMsi --console=plain --no-daemon
$msi = Get-ChildItem .\desktop\build\compose\binaries\main\msi\*.msi |
  Select-Object -First 1
Start-Process msiexec.exe -Wait -ArgumentList "/i `"$($msi.FullName)`" /qn /norestart"
..\scripts\windows-vm\assert-msi.ps1 -MsiPath $msi.FullName
```

`assert-msi.ps1` reports hashes and PE machine types. An x64 MSI exercised on
Windows ARM is an emulation smoke; it does not close the x64-hardware gate.

If this UTM guest's Java runtime aborts TLS to Maven while browsers and Bun
still have network access, run `gradle-http-mirror.py` on the Mac and temporarily
replace the guest checkout's Gradle repositories with its restricted
`/plugin/`, `/central/`, and `/google/` HTTP endpoints. Never commit that
guest-only repository override.

## Runtime probes

Run the low-level ConPTY/Job Object release gate from the repository:

```powershell
.\bun.exe run .\scripts\windows\conpty-smoke.ts
```

Then locate the installed broker/sessiond paths printed by `assert-msi.ps1` and
run the isolated broker probe:

```powershell
.\scripts\windows-vm\smoke.ps1 `
  -BrokerPath "C:\Program Files\supermux\app\resources\supermux-broker.exe" `
  -SessiondPath "C:\Program Files\supermux\app\resources\mux-sessiond.exe"
```

For the authenticated check, create a disposable session/device in the isolated
state and pass `-Session` and `-DeviceToken`. The script writes and observes a
terminal marker, resizes, detaches/reattaches, kills only the broker, requires
the same `mux-sessiond` PID and terminal to survive, then explicitly closes the
terminal.

## Evidence rules

- Record Windows build, hardware architecture, Bun version and Bun executable
  SHA-256.
- Record every packaged executable's PE architecture and SHA-256.
- Keep native and emulated results separate.
- Stop at the first runtime release gate. Do not proceed to onboarding,
  persistence, or real-agent claims when sessiond cannot provide a working
  terminal.
- Never add agent credentials only for this test. Record missing authentication
  as an external gate.


## Host runtime E2E (broker + sessiond + ConPTY)

On an interactive Windows desktop user (not session-0/SYSTEM), with Bun on PATH
or `scripts/windows-vm/run-sessiond.cmd` pointing at Bun + a checkout of this
branch (so `session-store.ts` has **no** `detached: true`):

```bat
set BROKER=C:\path\to\supermux-broker.exe
rem optional: point run-sessiond.cmd at your bun + source
scripts\windows-vm\e2e-host.bat
```

Success markers written to `%TEMP%\e2e-full.txt` (or `C:\Windows\Temp\e2e-full.txt`):

- `SUPERMUX_WINDOWS_BOOT_OK` — `/host` ready and `\\.\pipe\supermux-sessiond-*` present
- `SUPERMUX_TERM_E2E_OK` — PowerShell ConPTY create/write/resize/kill via SessionStore
- `SUPERMUX_WINDOWS_E2E_OK` — both gates

The broker preflight requires at least one agent CLI on PATH; the bat creates a
`claude.cmd` stub that sleeps. Real agent CLIs are required for authenticated
agent sessions.

Validated 2026-07-26 on UTM Windows 11 ARM64 with Bun 1.3.14 **x64-baseline**
under emulation (not native ARM64 Bun; that build still lacks `bun:ffi`).


## Authenticated terminal path (device token + scratch term)

`auth-smoke.ps1` (run as interactive user via `run-auth-smoke.bat` / schtasks):

1. Mints a device into `devices.json` (DeviceStore sha256 scheme)
2. Starts sessiond with Bun **x64-baseline** (`run-sessiond.cmd`) — required on WoA
3. Starts staged `supermux-broker.exe` with agent CLI stubs on PATH
4. Seeds a session row into `db.sqlite3` (avoids agent-spawn wait for shim registration)
5. Opens `/ws/term` with `Authorization: Bearer` and session **UUID**

**Proven on guest (2026-07-26):** boot, device mint, session seed, WebSocket **open**.
**Still open:** terminal I/O markers through the broker WS (SessionStore ConPTY
works when exercised directly; broker-mediated stream needs further diagnosis).
Compiled `mux-sessiond.exe` (non-baseline Bun embed) is unreliable under Windows-on-ARM —
use baseline Bun for sessiond on ARM guests.
