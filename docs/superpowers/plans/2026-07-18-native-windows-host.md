# Native Windows Host Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the existing Compose Windows application install and run the same Supermux broker natively, with all five agents, named-pipe shim IPC, persistent ConPTY sessions, and no WSL dependency.

**Architecture:** `src/main.ts` remains the sole broker. Platform-neutral runtime and endpoint facades select tmux/Unix sockets on POSIX or a subordinate `mux-sessiond.exe`/named pipes on Windows; structured agent adapters remain broker-owned. The desktop MSI bundles the broker, sessiond, and frpc, and an idempotent per-user Scheduled Task starts the broker at logon.

**Tech Stack:** TypeScript, Bun compiled executables, Bun.Terminal/Windows ConPTY, `@xterm/headless`, Node-compatible named pipes, Kotlin/JVM Compose Desktop, Windows Task Scheduler, GitHub Actions, Bun test, Gradle test, and the existing Windows 11 ARM64 UTM/QEMU VM on the remote Mac.

---

## File map

- `src/core/runtime/session-backend.ts` — platform-neutral session runtime contract.
- `src/core/runtime/index.ts` — process-wide runtime selection and compatibility exports.
- `src/core/runtime/tmux-backend.ts` — wraps the existing tmux implementation without changing POSIX behavior.
- `src/core/sessiond/protocol.ts` — versioned request/response/event schema and validation.
- `src/core/sessiond/screen.ts` — canonical VT screen, raw scrollback, and capture snapshots.
- `src/core/sessiond/job-object.ts` — Windows Job Object ownership through kernel32.
- `src/core/sessiond/session-store.ts` — ConPTY/Job lifetime and viewer fan-out.
- `src/core/sessiond/server.ts` — authenticated named-pipe RPC server.
- `src/core/sessiond/main.ts` — standalone sessiond entrypoint.
- `src/core/sessiond/client.ts` — reconnecting broker-side RPC client and session backend.
- `src/core/local-endpoint.ts` — Unix socket versus Windows named-pipe address calculation.
- `src/core/process/launcher.ts` — native executable discovery and `.cmd`-safe Windows launch.
- `src/core/terminal/manager.ts` — select tmux viewers on POSIX or sessiond viewers on Windows.
- `src/core/session-manager/socket-server.ts`, `src/shim/index.ts`, `src/shared/paths.ts` — share platform-correct shim endpoints.
- `src/core/session-manager/spawn-command.ts` — structured Claude argv/env specification alongside legacy POSIX shell string.
- `src/main.ts`, Claude live-switch/post-spawn/supervisor modules — consume the runtime facade instead of importing tmux directly.
- `apps/desktop/.../HostBinaries.kt` — bundle/materialize broker, sessiond, and frpc on Windows.
- `apps/desktop/.../KeepAlive.kt` — Scheduled Task XML/install/remove support.
- `apps/desktop/.../DesktopHostBootstrap.kt`, `Main.kt` — enable the existing host wizard on Windows and remove preview routing.
- `scripts/build-binary.sh`, `scripts/build-sessiond.sh`, `scripts/stage-desktop-binaries.sh` — cross-platform compiled artifacts.
- `.github/workflows/release.yml` — complete Windows MSI lane and artifact assertions.
- `scripts/windows-vm/*` — repeatable VM copy/install/smoke checks.

### Task 1: Platform-correct local endpoints

**Files:**
- Create: `src/core/local-endpoint.ts`
- Modify: `src/shared/paths.ts`
- Modify: `src/core/session-manager/socket-server.ts`
- Modify: `src/shim/index.ts`
- Test: `src/core/local-endpoint.test.ts`
- Test: `tests/socket-transport.test.ts`

- [ ] **Step 1: Write endpoint address tests**

```ts
import { describe, expect, test } from "bun:test"
import { localEndpoint, safePipeComponent } from "./local-endpoint"

describe("localEndpoint", () => {
  test("uses a filesystem socket on POSIX", () => {
    expect(localEndpoint("abc", { platform: "linux", socketsDir: "/state/sockets" }))
      .toBe("/state/sockets/abc.sock")
  })
  test("uses a deterministic named pipe on Windows", () => {
    expect(localEndpoint("A session/1", { platform: "win32", socketsDir: "ignored" }))
      .toBe("\\\\.\\pipe\\supermux-session-A_session_1")
  })
  test("sanitizes pipe components", () => {
    expect(safePipeComponent("a:b/c\\d")).toBe("a_b_c_d")
  })
})
```

- [ ] **Step 2: Run the test and verify the missing-module failure**

Run: `bun test src/core/local-endpoint.test.ts`

Expected: FAIL because `src/core/local-endpoint.ts` does not exist.

- [ ] **Step 3: Implement address calculation and route both peers through it**

```ts
import { join } from "path"

export function safePipeComponent(value: string): string {
  return value.replace(/[^A-Za-z0-9._-]/g, "_").slice(0, 120)
}

export function localEndpoint(id: string, opts: { platform?: NodeJS.Platform; socketsDir: string }): string {
  return (opts.platform ?? process.platform) === "win32"
    ? `\\\\.\\pipe\\supermux-session-${safePipeComponent(id)}`
    : join(opts.socketsDir, `${id}.sock`)
}
```

Change `socketPathForSession()` to call `localEndpoint()`. In `socket-server.ts`, skip directory creation, unlink, and chmod on Windows, and bind `localEndpoint(session_id, { socketsDir })`. The shim must call `socketPathForSession(sessionId)` rather than joining its own `.sock` path.

- [ ] **Step 4: Run endpoint and transport tests**

Run: `bun test src/core/local-endpoint.test.ts tests/socket-transport.test.ts src/core/session-manager/socket-server.test.ts`

Expected: PASS; POSIX transport tests retain their filesystem sockets.

- [ ] **Step 5: Commit**

```bash
git add src/core/local-endpoint.ts src/core/local-endpoint.test.ts src/shared/paths.ts src/core/session-manager/socket-server.ts src/shim/index.ts tests/socket-transport.test.ts
git commit -m "feat: support Windows named-pipe shim endpoints"
```

### Task 2: Define the session runtime contract

**Files:**
- Create: `src/core/runtime/session-backend.ts`
- Create: `src/core/runtime/tmux-backend.ts`
- Create: `src/core/runtime/index.ts`
- Test: `src/core/runtime/session-backend.test.ts`

- [ ] **Step 1: Write a contract test using an in-memory backend**

```ts
import { expect, test } from "bun:test"
import { verifySessionBackendContract } from "./session-backend.test-support"
import { createMemorySessionBackend } from "./session-backend.test-support"

test("memory backend satisfies the runtime contract", async () => {
  await verifySessionBackendContract(createMemorySessionBackend())
})
```

The test support must create a target, verify list/liveness, write input, resize, capture plain/raw output, attach/detach a viewer, interrupt it, and kill it.

- [ ] **Step 2: Run the contract test and verify it fails**

Run: `bun test src/core/runtime/session-backend.test.ts`

Expected: FAIL because the runtime contract is absent.

- [ ] **Step 3: Add the exact platform-neutral interface**

```ts
export type RuntimeTarget = { id: string; name: string; pid: number | null; alive: boolean }
export type RuntimeViewer = { close(): void; write(data: Uint8Array): boolean; resize(cols: number, rows: number): boolean }

export interface SessionBackend {
  create(opts: { group: string; name: string; cwd: string; argv: string[]; env: Record<string, string>; cols?: number; rows?: number }): Promise<RuntimeTarget>
  list(group?: string): Promise<RuntimeTarget[]>
  resolve(group: string, name: string): Promise<string | null>
  livePid(targetId: string): Promise<number | null>
  write(targetId: string, data: Uint8Array): Promise<void>
  sendKeys(targetId: string, keys: string[]): Promise<void>
  resize(targetId: string, cols: number, rows: number): Promise<void>
  capture(targetId: string, raw?: boolean): Promise<string | null>
  attach(targetId: string, viewerId: string, onData: (data: Uint8Array) => void | Promise<void>): Promise<RuntimeViewer>
  interrupt(targetId: string): Promise<void>
  kill(targetId: string): Promise<void>
}
```

`tmux-backend.ts` wraps the current tmux functions, converts `{ argv }` to the pre-existing POSIX command string only at its boundary, and preserves the current window IDs. `runtime/index.ts` exports `getSessionBackend()` and test-only `setSessionBackendForTests()`.

- [ ] **Step 4: Run the runtime and existing tmux suites**

Run: `bun test src/core/runtime/session-backend.test.ts src/core/session-manager/tmux.test.ts src/core/session-manager/tmux-scope.test.ts tests/tmux.test.ts`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/core/runtime
git commit -m "refactor: define platform session backend"
```

### Task 3: Version and validate the sessiond protocol

**Files:**
- Create: `src/core/sessiond/protocol.ts`
- Test: `src/core/sessiond/protocol.test.ts`

- [ ] **Step 1: Write parse and authentication tests**

```ts
import { expect, test } from "bun:test"
import { parseRequest, PROTOCOL_VERSION } from "./protocol"

test("accepts a versioned authenticated create request", () => {
  expect(parseRequest({ id: "1", version: PROTOCOL_VERSION, secret: "s", op: "create", args: { group: "mux", name: "n", cwd: "C:\\repo", argv: ["pwsh.exe"], env: {}, cols: 80, rows: 24 } }).op).toBe("create")
})
test("rejects unknown operations and protocol versions", () => {
  expect(() => parseRequest({ id: "1", version: 999, secret: "s", op: "erase", args: {} })).toThrow()
})
```

- [ ] **Step 2: Run and observe the missing implementation failure**

Run: `bun test src/core/sessiond/protocol.test.ts`

Expected: FAIL because the protocol module does not exist.

- [ ] **Step 3: Implement discriminated request/response/event types**

Define `PROTOCOL_VERSION = 1`, common `{ id, version, secret }`, request operations `hello`, `create`, `list`, `resolve`, `livePid`, `write`, `sendKeys`, `resize`, `capture`, `attach`, `detach`, `interrupt`, and `kill`; response `{ id, ok, value?, error? }`; event `{ event: "data" | "exit", targetId, viewerId?, dataBase64?, code? }`. Validate every scalar, dimension (`1..1000`), argv non-empty, and base64 input before dispatch.

- [ ] **Step 4: Run protocol tests**

Run: `bun test src/core/sessiond/protocol.test.ts`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/core/sessiond/protocol.ts src/core/sessiond/protocol.test.ts
git commit -m "feat: define authenticated sessiond protocol"
```

### Task 4: Build canonical VT capture

**Files:**
- Modify: `package.json`
- Create: `src/core/sessiond/screen.ts`
- Test: `src/core/sessiond/screen.test.ts`

- [ ] **Step 1: Add capture tests for ANSI, Unicode, alternate screen, and bounded raw data**

```ts
import { expect, test } from "bun:test"
import { SessionScreen } from "./screen"

test("captures rendered text and preserves raw ANSI", async () => {
  const s = new SessionScreen(12, 3, 100)
  await s.write(new TextEncoder().encode("hello\r\n\x1b[31mred\x1b[0m"))
  expect(s.captureText()).toContain("hello\nred")
  expect(s.captureRaw()).toContain("\x1b[31mred")
})
test("resize updates the canonical screen", async () => {
  const s = new SessionScreen(4, 2, 100)
  s.resize(20, 4)
  expect(s.dimensions()).toEqual({ cols: 20, rows: 4 })
})
```

- [ ] **Step 2: Install the headless terminal dependency and verify the test fails on the missing class**

Run: `bun add @xterm/headless && bun test src/core/sessiond/screen.test.ts`

Expected: FAIL because `SessionScreen` is absent.

- [ ] **Step 3: Implement `SessionScreen`**

Use `Terminal` from `@xterm/headless` with `scrollback`, feed writes through the callback form of `terminal.write`, serialize `buffer.active` from `baseY - viewportY` through `baseY + rows`, trim only right-side blank cells, preserve interior blank lines, and keep a byte-bounded raw ring. `resize()` calls `terminal.resize()`; `dispose()` releases it.

- [ ] **Step 4: Run the screen suite**

Run: `bun test src/core/sessiond/screen.test.ts`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add package.json bun.lock src/core/sessiond/screen.ts src/core/sessiond/screen.test.ts
git commit -m "feat: add sessiond terminal screen capture"
```

### Task 5: Own ConPTY sessions in sessiond

**Files:**
- Create: `src/core/sessiond/job-object.ts`
- Create: `src/core/sessiond/session-store.ts`
- Test: `src/core/sessiond/session-store.test.ts`
- Test: `src/core/sessiond/job-object.test.ts`
- Create: `scripts/windows/conpty-smoke.ts`

- [ ] **Step 1: Write tests against an injected terminal factory**

The fake terminal records writes/resizes; the fake process exposes `pid`, `exited`, `kill()`, and output injection; the fake Job Object records assignment, termination, and close. Assert opaque IDs, duplicate-name rejection within one group, viewer fan-out, detach without kill, `\x03` interrupt, explicit Job Object termination, and exited target enumeration.

- [ ] **Step 2: Run and verify failure**

Run: `bun test src/core/sessiond/session-store.test.ts`

Expected: FAIL because `SessionStore` is absent.

- [ ] **Step 3: Implement Bun.Terminal ownership**

Create `SessionStore` around injected terminal and Job Object factories. `job-object.ts` opens `kernel32.dll` with `bun:ffi` on Windows and binds `CreateJobObjectW`, `OpenProcess`, `AssignProcessToJobObject`, `TerminateJobObject`, and `CloseHandle` using `u64` for Windows HANDLE values. Its public surface is:

```ts
export interface ProcessJob {
  assign(pid: number): void
  terminate(exitCode?: number): void
  close(): void
}
export function createProcessJob(): ProcessJob
```

It creates one unnamed Job Object per runtime target, assigns the spawned root process with `PROCESS_SET_QUOTA | PROCESS_TERMINATE`, terminates the whole job on explicit kill, and closes handles on natural exit. A fake implementation runs on non-Windows unit tests. Create `SessionStore` around that boundary; its production terminal factory calls:

```ts
const proc = Bun.spawn(opts.argv, {
  cwd: opts.cwd,
  env: opts.env,
  detached: true,
  windowsHide: true,
  terminal: {
    cols: opts.cols,
    rows: opts.rows,
    data: (_term, data) => store.acceptOutput(targetId, data),
  },
})
```

The store retains the subprocess, terminal, and Job Object, fans each output chunk to attached viewers, updates `SessionScreen`, calls `job.terminate()` before closing ConPTY (required before Windows 11 24H2), and reports actual `proc.exited`. If the kernel32 binding cannot load, sessiond fails its startup health check instead of silently weakening full-tree termination.

- [ ] **Step 4: Add and compile the real-Windows ConPTY smoke**

`scripts/windows/conpty-smoke.ts` launches `powershell.exe -NoLogo -NoProfile`, writes `Write-Output SUPERMUX_CONPTY_OK\r`, resizes, waits for the marker, starts a nested long-running child, terminates its Job Object, and exits nonzero unless both root and nested child are gone within ten seconds.

Run on Windows: `bun scripts/windows/conpty-smoke.ts`

Expected: `SUPERMUX_CONPTY_OK` and exit 0.

- [ ] **Step 5: Commit**

```bash
git add src/core/sessiond/session-store.ts src/core/sessiond/session-store.test.ts scripts/windows/conpty-smoke.ts
git commit -m "feat: own persistent ConPTY sessions"
```

### Task 6: Add the authenticated sessiond server and client

**Files:**
- Create: `src/core/sessiond/secret.ts`
- Create: `src/core/sessiond/server.ts`
- Create: `src/core/sessiond/client.ts`
- Create: `src/core/sessiond/main.ts`
- Test: `src/core/sessiond/server.test.ts`
- Test: `src/core/sessiond/client.test.ts`

- [ ] **Step 1: Write integration tests over a temporary Unix socket**

Start a real server with injected endpoint/secret/store, connect the client, verify bad-secret rejection, version rejection, create/list/write/capture/attach/detach/kill, and reconnect after closing the broker-side socket.

- [ ] **Step 2: Run and verify failure**

Run: `bun test src/core/sessiond/server.test.ts src/core/sessiond/client.test.ts`

Expected: FAIL because server/client modules are absent.

- [ ] **Step 3: Implement secret storage and framed RPC**

Store 32 random bytes as base64 in `STATE_DIR/sessiond.secret`, create with mode `0600`, and use timing-safe equality. Use the existing length-prefixed JSON frame codec over `net.createServer()`/`net.createConnection()`. Production endpoint is `\\.\pipe\supermux-sessiond-<hash of absolute state dir>` on Windows and `STATE_DIR/sessiond.sock` on POSIX tests. Listen with `{ path, exclusive: true, readableAll: false, writableAll: false }`, preserving the Windows current-user default DACL rather than granting all-user access. The server sends viewer output as data events; the client maps responses by request ID and viewers by viewer ID.

- [ ] **Step 4: Implement the Windows backend and startup adoption**

`SessiondBackend implements SessionBackend`. Its `ensureConnected()` tries the pipe, and on `ENOENT` spawns `mux-sessiond.exe --state-dir <STATE_DIR>` detached with ignored stdio, then polls for ten seconds. `main.ts` handles `--state-dir`, obtains a single-instance lock, starts `SessionStore`, and listens until shutdown.

- [ ] **Step 5: Run the sessiond suites**

Run: `bun test src/core/sessiond/*.test.ts src/core/runtime/session-backend.test.ts`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/core/sessiond src/core/runtime
git commit -m "feat: connect broker to persistent sessiond"
```

### Task 7: Route Claude lifecycle through the runtime facade

**Files:**
- Modify: `src/core/session-manager/spawn-command.ts`
- Modify: `src/core/session-manager/supervisor.ts`
- Modify: `src/core/session-manager/post-spawn-keys.ts`
- Modify: `src/core/agents/claude/live-switch.ts`
- Modify: `src/main.ts`
- Test: `tests/spawn-command.test.ts`
- Test: `src/core/session-manager/supervisor-suspend-log.test.ts`
- Test: `src/core/agents/claude/live-switch.test.ts`

- [ ] **Step 1: Add argv/env tests for native Claude spawning**

```ts
test("buildClaudeSpawnSpec does not invoke a shell", () => {
  const spec = buildClaudeSpawnSpec({ name: "win worker", sessionId: "s1", workdir: "C:\\repo" })
  expect(spec.argv[0]).toBe("claude")
  expect(spec.argv).not.toContain("bash")
  expect(spec.env.MUX_SESSION_ID).toBe("s1")
  expect(spec.env.MUX_DISPLAY_NAME).toBe("win worker")
})
```

- [ ] **Step 2: Run focused tests and observe failure**

Run: `bun test tests/spawn-command.test.ts src/core/agents/claude/live-switch.test.ts`

Expected: FAIL because `buildClaudeSpawnSpec` is absent.

- [ ] **Step 3: Build a structured spawn spec**

Add `buildClaudeSpawnSpec()` returning `{ argv: string[], env: Record<string,string> }`, using the same flags/files/plugin decisions as `buildClaudeSpawnCommand()`. Keep the legacy shell-string function as a POSIX adapter that quotes the structured spec, so there remains one flag source.

- [ ] **Step 4: Replace direct tmux imports**

Route spawn, resolve, list, live PID, write/semantic keys, capture, interrupt, and kill through `getSessionBackend()`. Preserve stored IDs in `tmux_window_id` during this compatibility phase; add a comment that the column is a legacy storage name containing a platform runtime target ID. `isTmuxBackedSession()` becomes `isPersistentRuntimeSession()` and continues to return true only for Claude.

- [ ] **Step 5: Run Claude lifecycle and startup reconciliation tests**

Run: `bun test tests/spawn-command.test.ts src/core/session-manager/post-spawn-keys.test.ts src/core/session-manager/supervisor-suspend-log.test.ts src/core/agents/claude/live-switch.test.ts tests/reconcile-startup.test.ts`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/core/session-manager src/core/agents/claude src/main.ts tests/spawn-command.test.ts tests/reconcile-startup.test.ts
git commit -m "refactor: route Claude through session backend"
```

### Task 8: Route persistent terminals through sessiond on Windows

**Files:**
- Modify: `src/core/terminal/manager.ts`
- Create: `src/core/terminal/sessiond-term.ts`
- Test: `tests/terminal-manager.test.ts`
- Test: `src/core/terminal/sessiond-term.test.ts`

- [ ] **Step 1: Add Windows terminal behavior tests**

Inject a fake `SessionBackend`; assert scratch attach creates `powershell.exe -NoLogo`, output reaches `onData`, resize calls backend resize, disconnect detaches without kill, explicit close kills scratch, and agent attach targets the Claude runtime without creating another process.

- [ ] **Step 2: Run and verify the tests fail**

Run: `bun test tests/terminal-manager.test.ts src/core/terminal/sessiond-term.test.ts`

Expected: FAIL because the sessiond terminal adapter is absent.

- [ ] **Step 3: Implement the Windows terminal adapter**

`SessiondTerm` derives an opaque scratch name from broker session/terminal IDs, resolves or creates it with PowerShell argv, and calls backend `attach`. For agent terminals it attaches to the existing runtime target. Its viewer implements the existing `TermProc` surface with a `ReadableStream`, stdin write, resize control, and detach close so `TerminalManager`'s WebSocket behavior is unchanged.

- [ ] **Step 4: Select the adapter by platform**

Keep the current tmux/pty-helper path byte-for-byte for non-Windows. On Windows, do not resolve or execute `pty-helper`; use `SessiondTerm` and select `powershell.exe` or `pwsh.exe` from executable discovery.

- [ ] **Step 5: Run terminal tests**

Run: `bun test tests/terminal-manager.test.ts src/core/terminal/*.test.ts`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/core/terminal tests/terminal-manager.test.ts
git commit -m "feat: back Windows terminals with sessiond"
```

### Task 9: Make structured agent launching Windows-native

**Files:**
- Create: `src/core/process/launcher.ts`
- Test: `src/core/process/launcher.test.ts`
- Modify: `src/core/agents/detect.ts`
- Modify: `src/core/agents/codex/spawn.ts`
- Modify: `src/core/agents/cursor/runner.ts`
- Modify: `src/core/agents/opencode/spawn.ts`
- Modify: `src/core/agents/grok/runner.ts`
- Modify: agent auth/config path modules and their tests

- [ ] **Step 1: Add executable resolution and Windows path tests**

Test discovery order `name.exe`, `name.cmd`, `name.ps1`, Cursor fallback `cursor-agent` then `agent`, `.cmd` invocation through `cmd.exe /d /s /c`, `.ps1` through `powershell.exe -NoProfile -File`, and Windows credential roots using `APPDATA`/`LOCALAPPDATA` when upstream uses them.

- [ ] **Step 2: Run and verify failure**

Run: `bun test src/core/process/launcher.test.ts tests/agent-detect.test.ts tests/agents`

Expected: FAIL on missing launcher and Windows expectations.

- [ ] **Step 3: Implement argument-safe command launching**

Expose `resolveCommand(names, env, platform)` and `spawnCommand(command, args, options)`. Native `.exe` uses direct argv; `.cmd` uses `ComSpec` with Windows quoting; `.ps1` uses PowerShell `-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File`. Never concatenate user text into a shell string.

- [ ] **Step 4: Wire all four structured adapters and status detection**

Codex, Cursor, OpenCode, and Grok use the launcher while preserving their current stdio/protocol adapters. Status reports Cursor installed when either official executable alias exists. Add platform-correct credential discovery without changing POSIX paths.

- [ ] **Step 5: Run agent suites**

Run: `bun test tests/agent-detect.test.ts tests/agents tests/agent-api`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/core/process src/core/agents tests/agent-detect.test.ts tests/agents tests/agent-api
git commit -m "feat: launch all agents natively on Windows"
```

### Task 10: Enable Windows in desktop host onboarding

**Files:**
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/host/HostBinaries.kt`
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/host/DesktopHostBootstrap.kt`
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/Main.kt`
- Delete: `apps/desktop/src/main/kotlin/dev/supermux/desktop/host/WindowsHostPreviewCard.kt`
- Modify/Delete: corresponding preview tests
- Test: `apps/desktop/src/test/kotlin/dev/supermux/desktop/host/HostBinariesTest.kt`
- Test: `apps/desktop/src/test/kotlin/dev/supermux/desktop/host/DesktopHostPersistenceTest.kt`

- [ ] **Step 1: Change desktop tests to expect native Windows hosting**

Assert `isNativeHostPlatform(WINDOWS)` is true; Windows bundles Broker, Sessiond, and Frpc but not Tmux; packaged resolution returns all three `.exe` files; the preview-card source is absent from the production route.

- [ ] **Step 2: Run desktop host tests and verify failure**

Run: `cd apps && ./gradlew :desktop:test --tests 'dev.supermux.desktop.host.*' --console=plain --no-daemon`

Expected: FAIL because Windows remains client-only.

- [ ] **Step 3: Extend binary policy and sidecar environment**

Add `Binary.Sessiond`, `sessiondPath`, and `.exe` naming. Windows `isBundled()` returns true for Broker, Sessiond, Frpc and false for Tmux. Add `MUX_SESSIOND_PATH=<materialized path>` to the sidecar environment. Keep POSIX resolution unchanged.

- [ ] **Step 4: Enable the existing wizard and remove preview UI**

Add `WINDOWS` to `KeepAlive.Os`, return true from `isNativeHostPlatform()`, delete preview rendering/imports, and update comments so the same first-run host wizard runs on all supported desktop OSes.

- [ ] **Step 5: Run desktop host tests**

Run: `cd apps && ./gradlew :desktop:test --tests 'dev.supermux.desktop.host.*' --console=plain --no-daemon`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add apps/desktop/src/main apps/desktop/src/test
git commit -m "feat: enable Windows desktop host onboarding"
```

### Task 11: Install per-user Windows keep-alive

**Files:**
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/host/KeepAlive.kt`
- Modify: `apps/desktop/src/test/kotlin/dev/supermux/desktop/host/KeepAliveTest.kt`

- [ ] **Step 1: Add Scheduled Task XML and command tests**

Assert XML contains LogonTrigger, InteractiveToken, LeastPrivilege, escaped executable/arguments, working directory, `SUPERMUX_KEEP_ALIVE=1` passed through the broker launcher command, and restart-on-failure. Assert install writes `%LOCALAPPDATA%\\Supermux\\supermux-host-task.xml`, runs `schtasks.exe /Create /TN Supermux Host /XML <file> /F`, and remove runs `/Delete /TN Supermux Host /F`.

- [ ] **Step 2: Run and verify failure**

Run: `cd apps && ./gradlew :desktop:test --tests 'dev.supermux.desktop.host.KeepAliveTest' --console=plain --no-daemon`

Expected: FAIL because Windows returns Unsupported.

- [ ] **Step 3: Implement Windows task generation/install/remove**

Add `WINDOWS_TASK_NAME = "Supermux Host"`, generate Task Scheduler 1.4 XML, quote command arguments using Windows command-line rules, and use the injected environment for file writes and `schtasks.exe`. Install/remove remain current-user operations without `/RU SYSTEM`, `/RL HIGHEST`, service creation, or elevation.

- [ ] **Step 4: Run keep-alive tests**

Run: `cd apps && ./gradlew :desktop:test --tests 'dev.supermux.desktop.host.KeepAliveTest' --console=plain --no-daemon`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/desktop/src/main/kotlin/dev/supermux/desktop/host/KeepAlive.kt apps/desktop/src/test/kotlin/dev/supermux/desktop/host/KeepAliveTest.kt
git commit -m "feat: keep Windows host alive at logon"
```

### Task 12: Build and stage Windows broker/sessiond binaries

**Files:**
- Modify: `scripts/build-binary.sh`
- Create: `scripts/build-sessiond.sh`
- Modify: `scripts/stage-desktop-binaries.sh`
- Modify: `apps/desktop/resources/windows-x64/.gitignore`
- Test: `tests/windows-staging.test.ts`

- [ ] **Step 1: Add staging policy tests**

Run the staging script with temporary executable overrides and `SUPERMUX_SKIP_BROKER=1`; assert Windows accepts `SUPERMUX_BROKER` and `SUPERMUX_SESSIOND`, stages exact `supermux-broker.exe`, `mux-sessiond.exe`, `frpc.exe`, and never stages tmux/pty-helper.

- [ ] **Step 2: Run and verify failure**

Run: `bun test tests/windows-staging.test.ts`

Expected: FAIL because Windows staging omits broker/sessiond.

- [ ] **Step 3: Add canonical compilation paths**

`build-sessiond.sh <outfile>` runs `bun build --compile --minify src/core/sessiond/main.ts --outfile <outfile>`. Refactor `build-binary.sh` so Windows builds skip the C `pty-helper` compile and select `windows-x64` frpc; accept `SUPERMUX_TARGET` for deterministic cross/host builds. Staging is override-first for broker/sessiond and otherwise invokes the build scripts on the Windows runner.

- [ ] **Step 4: Run staging and Linux binary regression tests**

Run: `bun test tests/windows-staging.test.ts src/core/runtime-assets.test.ts && shellcheck scripts/build-binary.sh scripts/build-sessiond.sh scripts/stage-desktop-binaries.sh`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts apps/desktop/resources/windows-x64/.gitignore tests/windows-staging.test.ts
git commit -m "build: package native Windows host binaries"
```

### Task 13: Complete Windows release CI

**Files:**
- Modify: `.github/workflows/release.yml`
- Test: `tests/release-workflow.test.ts`

- [ ] **Step 1: Add workflow assertions**

Parse the YAML as text and assert the Windows job installs Bun, stages all three helpers, checks each file exists and is non-empty, runs Bun tests/typecheck plus desktop host tests before `packageMsi`, and preserves the stable MSI/checksum names.

- [ ] **Step 2: Run and verify failure**

Run: `bun test tests/release-workflow.test.ts`

Expected: FAIL because the Windows job currently stages only frpc.

- [ ] **Step 3: Update the Windows job**

Add `oven-sh/setup-bun@v2`, `bun install`, `bun test` focused Windows-safe suites, `bun run typecheck`, full host-helper staging, explicit PowerShell `Get-Item` size checks, desktop host Gradle tests, MSI build, and a post-build archive listing proving the three `.exe` resources exist in the application image.

- [ ] **Step 4: Run workflow and static validation tests**

Run: `bun test tests/release-workflow.test.ts tests/windows-staging.test.ts && git diff --check`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/release.yml tests/release-workflow.test.ts
git commit -m "ci: verify complete Windows host MSI"
```

### Task 14: Validate on the existing Windows 11 VM on the remote Mac

**Files:**
- Create: `scripts/windows-vm/README.md`
- Create: `scripts/windows-vm/smoke.ps1`
- Create: `scripts/windows-vm/assert-msi.ps1`
- Create: `docs/superpowers/verification/2026-07-18-native-windows-host.md`

- [ ] **Step 1: Start or adopt the Mac-hosted UTM VM**

Use `ssh mac` and `/Applications/UTM.app/Contents/MacOS/utmctl`. The existing VM is UUID `A18E7828-0D27-4A10-8549-470AA206B787`, name `Windows 11 - Supermux Test`, ARM64, 8 GiB, with its preserved disk at `~/Library/Containers/com.utmapp.UTM/Data/Documents/Windows.utm`. Start it by UUID if `utmctl list` reports `stopped`. Do not recreate or overwrite its disk. Discover its guest IP from the Mac ARP table/UTM DHCP lease, then use its configured Windows OpenSSH service; if SSH is not configured, use UTM's shared directory `~/Downloads/supermux-windows-test` plus the VM console only to enable the built-in OpenSSH Server once. Confirm PowerShell reports Windows 11 ARM64.

- [ ] **Step 2: Run the ConPTY spike before installing the MSI**

Sync the whole branch to `mac:~/Downloads/supermux-windows-test/source` with tar-over-SSH, expose that directory through the VM's existing WebDAV share, install the official Windows ARM64 Bun build in the guest, and run `bun scripts/windows/conpty-smoke.ts`.

Expected: marker `SUPERMUX_CONPTY_OK`, resize success, root-and-child Job Object termination, clean exit.

- [ ] **Step 3: Build and install the MSI**

On the guest, run staging and `apps\\gradlew.bat :desktop:packageMsi --console=plain --no-daemon`, then install silently with `msiexec /i <msi> /qn /norestart`. `assert-msi.ps1` locates the installed image and proves `supermux-broker.exe`, `mux-sessiond.exe`, and `frpc.exe` are present and non-empty. This ARM64 guest is authoritative for native ConPTY and Windows behavior; an x64 MSI running under Windows ARM emulation is recorded as an emulation smoke, not mislabeled as the spec's x64 hardware gate.

- [ ] **Step 4: Run native host smoke checks**

`smoke.ps1` starts the installed broker with an isolated `MUX_HOME`, waits for `/host`, asserts sessiond pipe creation, creates a PowerShell scratch terminal through broker REST/WebSocket, writes `Write-Output SUPERMUX_TERM_OK`, verifies output, resizes, detaches/reattaches, and explicitly closes it. It then kills only the broker PID, restarts it, and proves a separate long-running ConPTY target survived and reconciled.

- [ ] **Step 5: Verify onboarding and persistence**

Launch the installed desktop app normally; verify the host wizard appears, complete local pairing, check `schtasks.exe /Query /TN "Supermux Host" /XML`, close the app, run the task, and verify `/host` becomes available. Log off/on once and repeat the probe.

- [ ] **Step 6: Verify installed agents without fabricating credentials**

Run `/agents/status` and record native discovery for Claude, Codex, Cursor/agent, OpenCode, and Grok. For CLIs already authenticated in the VM, execute one real turn plus interrupt/resume. For missing credentials, verify exact actionable login/install guidance and record the external-auth gate rather than inserting secrets.

- [ ] **Step 7: Record evidence and commit the harness**

The verification document records VM UUID/architecture, Windows build, Bun version, MSI SHA-256, native-versus-emulated architecture for every executable, commands, pass/fail table, broker/sessiond PIDs before/after restart, Scheduled Task XML location, and any auth-gated cases. It leaves the x64-hardware gate open unless separately proven on an x64 Windows runner/VM.

```bash
git add scripts/windows-vm docs/superpowers/verification/2026-07-18-native-windows-host.md
git commit -m "test: verify native Windows host in VM"
```

### Task 15: Full regression and release-readiness audit

**Files:**
- Modify: `README.md`
- Modify: `SETUP.md`
- Modify: `docs/superpowers/specs/2026-07-18-native-windows-host-design.md`
- Modify: `docs/superpowers/plans/2026-07-18-native-windows-host.md`

- [ ] **Step 1: Run broker checks**

Run: `bun run typecheck && bun test`

Expected: PASS with no skipped Windows contract test that can run through fakes.

- [ ] **Step 2: Run desktop checks**

Run: `cd apps && ./gradlew :desktop:test --console=plain --no-daemon`

Expected: PASS.

- [ ] **Step 3: Re-run focused POSIX runtime checks**

Run: `bun test src/core/session-manager/tmux.test.ts src/core/session-manager/tmux-scope.test.ts tests/tmux.test.ts tests/terminal-manager.test.ts tests/socket-transport.test.ts`

Expected: PASS, proving tmux/Unix behavior was preserved.

- [ ] **Step 4: Update user documentation with native Windows commands**

Document MSI native hosting, PowerShell default, no WSL requirement, current Cursor installer `irm 'https://cursor.com/install?win32=true' | iex`, other official agent install/login commands, the Scheduled Task, state location `%USERPROFILE%\\.mux`, and uninstall behavior.

- [ ] **Step 5: Mark verified plan items and design status**

Change design status to `Implemented` only for gates that passed. In this plan, check completed boxes and leave any external-auth or ARM64 release gates unchecked with concrete evidence in the verification document; do not claim them complete.

- [ ] **Step 6: Inspect final diff and commit documentation**

Run: `git diff --check && git status --short && git log --oneline --decorate -20`

```bash
git add README.md SETUP.md docs/superpowers/specs/2026-07-18-native-windows-host-design.md docs/superpowers/plans/2026-07-18-native-windows-host.md
git commit -m "docs: document native Windows hosting"
```
