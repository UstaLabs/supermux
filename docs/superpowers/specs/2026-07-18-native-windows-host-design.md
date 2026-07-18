# Native Windows Host — Single-Broker Architecture

**Date:** 2026-07-18
**Status:** Architecture approved by Ahmet; awaiting review of this written spec
**Supersedes:** The deferred Windows-host summary in `2026-07-11-desktop-host-multihost-design.md`

## 1. Goal and boundaries

The existing Windows Compose desktop app becomes a first-class **native host** without WSL. Installing the MSI starts or adopts the same Supermux broker used on macOS and Linux, shows the existing host QR wizard, and supports every broker agent: Claude, Codex, Cursor, OpenCode, and Grok.

There is exactly **one broker codebase, one broker process, one database, and one control plane**. Windows runs the existing `src/main.ts` broker compiled as `supermux-broker.exe`. No Windows broker fork, duplicated HTTP API, duplicated session registry, or Windows-only business logic is allowed.

WSL remains usable only as an independently paired advanced host. The native Windows host never launches WSL, converts paths to `/mnt/*`, or copies credentials into a Linux environment.

## 2. Locked decisions

| Area | Decision |
|---|---|
| Architecture | Mirror the existing broker architecture; replace platform primitives only |
| Broker | Same TypeScript/Bun broker on every host OS |
| POSIX sessions | Existing tmux backend remains unchanged |
| Windows sessions | `mux-sessiond.exe` replaces tmux for Claude and persistent terminals |
| Structured agents | Codex, Cursor, OpenCode, and Grok keep their existing adapters |
| Agent scope | All five agents ship in the native Windows host |
| Shell | PowerShell is the default interactive shell; no hidden WSL fallback |
| Persistence | Per-user Scheduled Task at login, never a Windows Service |
| IPC | User-scoped named pipes replace Unix-domain sockets on Windows |
| Desktop UX | Remove the preview card after gates pass; reuse the existing host wizard |
| Baseline | Windows 10 1809+ x64; Windows 11 is the primary verified target |

Cursor's old WSL-only assumption is obsolete. Its current official installer provides native Windows x64/ARM64 packages and installs `cursor-agent.exe` plus the `agent.exe` alias:

```powershell
irm 'https://cursor.com/install?win32=true' | iex
```

Sources: [Cursor installation](https://cursor.com/tr/docs/cli/installation) and [Windows installer](https://cursor.com/install?win32=true).

## 3. Platform seams

The broker selects implementations at runtime behind narrow interfaces:

| Interface | macOS/Linux | Windows |
|---|---|---|
| `SessionBackend` | tmux adapter | sessiond client |
| `LocalEndpoint` | Unix socket | named pipe |
| `CommandLauncher` | POSIX argv/shell launch | direct Windows argv/PowerShell-aware launch |
| `HostKeepAlive` | launchd/systemd-user | Scheduled Task |

Existing protocol and storage fields that still say `tmux_*` migrate to platform-neutral runtime target fields. Legacy rows remain readable and are backfilled without changing user-visible session IDs.

`mux-sessiond.exe` is a subordinate runtime helper analogous to tmux, not a second broker. It owns no HTTP listener, SQLite database, pairing, relay, host identity, project scanning, agent registry, or client protocol.

## 4. Sessiond responsibilities and protocol

Sessiond is a per-user process started or re-adopted by the broker. At logon, the Scheduled Task launches the broker, and the broker ensures that sessiond is running. Sessiond owns Windows pseudoconsole resources that must survive broker restarts:

- ConPTY creation, resize, input, and output.
- One Job Object per session for explicit full-tree termination.
- Canonical VT screen and bounded scrollback used by capture/snapshot operations.
- Viewer attach/detach without killing the underlying process.
- Runtime enumeration and reconciliation after a broker restart.

The versioned broker↔sessiond protocol exposes opaque target IDs and the same operations the broker currently obtains from tmux: create, list, liveness, write bytes, send semantic keys, resize, capture text, capture styled text, attach output, interrupt, and kill.

Sessiond is implemented in TypeScript in this repository and compiled as `mux-sessiond.exe`. A real-Windows spike decides its ConPTY transport. Bun's PTY documentation is currently contradictory: the runtime guide describes Windows ConPTY while the API reference says terminal spawning is POSIX-only. If Bun fails the release gates, only the ConPTY transport becomes a small native helper; the broker interfaces and sessiond protocol do not change.

## 5. Agent behavior

### Claude

The broker asks sessiond to launch native Claude Code in a ConPTY with explicit argv, environment, working directory, and dimensions. Existing consent keystrokes, interrupt handling, live model/effort switching, capture logic, hooks, and shim registration are redirected through `SessionBackend`; their higher-level behavior is unchanged. Claude may use Git for Windows internally when required by Claude Code, but Supermux launches a native Windows process and never invokes WSL.

### Codex

The existing app-server adapter remains broker-owned. Windows uses the official native `codex.exe`, the existing session-home isolation, configuration, structured events, interruption, and persisted upstream thread IDs.

### Cursor

The existing per-turn stream-JSON adapter remains broker-owned and launches native `cursor-agent.exe` (falling back to the official `agent.exe` alias during executable discovery). No WSL wrapper or path translation is permitted.

### OpenCode

The existing HTTP server adapter remains broker-owned and launches the native Windows OpenCode command directly. Its Windows-native installation is supported even though upstream may recommend WSL for some workflows.

### Grok

The existing ACP adapter remains broker-owned and launches native Grok Build using its Windows command/config locations.

All agent availability and authentication states continue to come from the existing per-host `/agents/status` flow. The wizard shows platform-appropriate official installation guidance; no parallel capability matrix is added.

## 6. Lifecycle and data flow

1. The Compose desktop app starts or adopts `supermux-broker.exe` using the existing `HostProbe` and `BrokerSidecar` ownership rules.
2. The broker starts or connects to the user-scoped sessiond named pipe.
3. The existing local-host bootstrap mints the one-time claim, auto-pairs "This computer", and shows the same QR wizard as Linux/macOS.
4. Claude and persistent-terminal creation goes through `WindowsSessionBackend`; structured agents use their existing adapters.
5. Agent shims connect to the single broker through per-session named pipes instead of Unix sockets.
6. Closing a terminal view detaches it. Explicit session deletion terminates its Job Object.
7. Broker restart reconnects to sessiond and reconciles surviving runtime targets. Structured agents resume through their existing persisted upstream session IDs.
8. `frpc.exe`, relay registration, pairing, host identity, push, REST, WebSocket, and client behavior remain unchanged.

## 7. Security, upgrades, and failures

- Broker and sessiond run as the logged-in user, without elevation.
- Named pipes are local-only and restricted to the current logon SID with explicit DACLs.
- The control pipe requires an installation secret and protocol-version handshake; pipe-name knowledge is insufficient.
- Per-session opaque capabilities prevent one session connection from controlling another.
- State remains under the existing `~/.mux` layout; agent credentials stay in their native Windows locations.
- Broker failure does not kill sessiond-owned Claude sessions or terminals.
- Sessiond failure is reflected honestly as unavailable runtime targets; the broker does not synthesize liveness.
- Broker and sessiond ship at the same version. An incompatible sessiond upgrade waits until it is idle or requires explicit user confirmation; it never destroys live sessions automatically.
- Missing or unauthenticated CLIs produce actionable native installation/login guidance through existing status surfaces.
- Existing adopt-don't-duplicate and foreign-port behavior remains authoritative.

## 8. Packaging and desktop integration

The Windows staging/release lane bundles:

- `supermux-broker.exe`
- `mux-sessiond.exe`
- `frpc.exe`

The MSI no longer stages `frpc.exe` alone. `HostBinaries`, `DesktopHostBootstrap`, `KeepAlive`, and the host wizard treat Windows as a native host platform. Keep-alive installs/removes an idempotent per-user Scheduled Task that launches the broker at logon; the broker then starts or adopts sessiond. The preview card is removed only when the release gates below pass.

Agent CLIs are detected, not silently bundled. The wizard links to their official Windows installers and verifies installed/authenticated state through `/agents/status`.

## 9. Verification and release gates

### Contract and CI tests

- The same `SessionBackend` contract suite runs against tmux and a Windows sessiond test server.
- Windows CI compiles and tests the broker, sessiond, Compose app, Scheduled Task integration, named-pipe authentication/ACLs, and complete MSI contents.
- Cross-platform broker tests prove POSIX behavior remains unchanged.
- Recorded VT fixtures cover Unicode, ANSI styling, alternate screen, resize/reflow, cursor state, and Claude composer/capture behavior.

### Real Windows gates

A Windows 11 VM must pass all of the following before the preview card is removed:

1. Fresh MSI install and uninstall without WSL.
2. Host wizard, local adoption, QR pairing, direct connection, and relay connection.
3. PowerShell terminal input, resize, scrollback, detach/reattach, and explicit close.
4. Native Claude, Codex, Cursor, OpenCode, and Grok session creation and one real authenticated turn each.
5. Interrupt and resume for every agent.
6. Broker restart while Claude and terminal processes survive and reconcile.
7. App close, user logoff/login, Scheduled Task restart, and host availability.
8. In-place upgrade with active sessions and protocol compatibility handling.
9. Named-pipe cross-user access rejection and bad-secret rejection.

ARM64 packaging follows after x64 is green; it does not delay the initial native-host release.

## 10. Out of scope

- Replacing tmux on macOS/Linux.
- A second Windows broker or Windows-specific client protocol.
- WSL path/credential bridging.
- Bundling third-party agent CLIs inside the Supermux MSI.
- Rewriting structured agents as terminal scrapers.
- Windows Service/session-0 hosting.
- Windows display mirroring beyond the broker's existing provider model; it receives a separate design if native capture is later requested.

## 11. Completion definition

The Windows work is complete when the released MSI turns a clean Windows machine into a Supermux host through the existing onboarding flow, all five native agents pass the real-Windows gates, active Claude/terminal sessions survive broker restarts, and no Windows-specific broker fork exists.
