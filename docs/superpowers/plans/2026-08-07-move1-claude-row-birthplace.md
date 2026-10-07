# Move 1: Claude Row Birthplace — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The claude spawn path creates the session row synchronously. `onRegister` shrinks to attach-only. The four staging maps die.

**Architecture:** This is PR 1 of the spec `docs/superpowers/specs/2026-08-07-agent-adapter-consolidation-spec.md` (revision 5, "the three moves"). The PA path already proves the pattern: `spawnPA` registers the claude row before tmux starts (`spawn-helper.ts:193-226`). We extend the same pattern to `spawnClaudeSession`. Then the register frame from the shim always finds an existing row, so `onRegister`'s "fresh" branch becomes a refusal.

**Tech Stack:** TypeScript on Bun. Tests with `bun test`. No new dependencies.

**Plans for PR 2-6:** Each later PR gets its own plan when we reach it. The moves change what the next PR sees. A plan written now would be stale.

---

## Context for a zero-context engineer

- The broker spawns a claude CLI inside a tmux window. A shim (MCP server) inside claude connects back over a unix socket and sends a `register` frame.
- TODAY: `spawnClaudeSession` only RESERVES a name. The session ROW is created ~1s later by the `onRegister` callback in `main.ts:2471-2602`. Four staging maps bridge the gap (`pendingClaudeSessionId`, `pendingRuntimeTargetId`, `pendingInternal`, `pendingSpawnActive` at `main.ts:573-585`).
- `registry.register` (`registry.ts:69-99`) creates the row and sets `connected=true`.
- `main.ts` `spawnSession` (`main.ts:2985-3144`) polls `waitForRegisteredSession` until the row appears.
- Non-claude agents (codex etc.) already register synchronously. Claude PAs already register synchronously. Only the regular claude spawn path defers.

Run all tests with: `bun test` (from the repo root). Type-check with: `bunx tsc --noEmit` if a `tsconfig` check script exists; otherwise rely on `bun test` + editor. The repo's verify gate is `.mux/verify.sh`.

---

### Task 1: `registry.register` learns `connected: false`

The spawn path will register the row BEFORE the shim connects. The row must not claim `connected=true`. The socket layer flips it to true when the shim's first frame arrives (`socket-server.ts` `markAlive` → `onStatusChange` → `main.ts:2444`).

**Files:**
- Modify: `src/core/session-manager/registry.ts:69-99`
- Test: `src/core/session-manager/registry-register-connected.test.ts` (create)

- [ ] **Step 1: Write the failing test**

```ts
// src/core/session-manager/registry-register-connected.test.ts
import { describe, expect, test } from "bun:test"
import { Registry } from "./registry"

describe("registry.register connected flag", () => {
  test("defaults to connected=true (existing behavior)", () => {
    const registry = new Registry()
    const s = registry.register({ name: "a", workdir: "/tmp", pid: 1 })
    expect(registry.get(s.id)?.connected).toBe(true)
  })

  test("connected:false registers an unconnected row", () => {
    const registry = new Registry()
    const s = registry.register({ name: "b", workdir: "/tmp", pid: 1, connected: false })
    expect(registry.get(s.id)?.connected).toBe(false)
  })

  test("setConnectionStatus flips an unconnected row to connected", () => {
    const registry = new Registry()
    const s = registry.register({ name: "c", workdir: "/tmp", pid: 1, connected: false })
    registry.setConnectionStatus(s.id, true)
    expect(registry.get(s.id)?.connected).toBe(true)
  })
})
```

- [ ] **Step 2: Run the test. Verify it fails.**

Run: `bun test src/core/session-manager/registry-register-connected.test.ts`
Expected: the second test FAILS (`connected` is `true`). TypeScript may also reject the unknown `connected` key.

- [ ] **Step 3: Implement**

In `src/core/session-manager/registry.ts`, change the `register` signature and the connection line:

```ts
  register(input: { id?: string; name: string; workdir: string; tmux_target?: string; tmux_window_id?: string; pid: number; base_commit?: string; base_commits?: Record<string, string>; role?: SessionRole; is_default?: boolean; internal?: boolean; connected?: boolean } & Partial<Pick<Session, "mute" | "can_orchestrate" | "agent" | "agent_session_id" | "agent_home" | "model" | "reasoningLevel" | "repo_root" | "base_branch" | "session_branch">>): Session {
```

and replace the hardcoded line (`registry.ts:95-96`):

```ts
    // Connected as soon as the shim joins. The claude spawn path registers the
    // row BEFORE the shim exists, so it passes connected:false; the socket
    // layer flips it to true on the shim's first frame.
    this.sessions.setConnectionStatus(session.id, input.connected ?? true)
```

- [ ] **Step 4: Run the test. Verify it passes.**

Run: `bun test src/core/session-manager/registry-register-connected.test.ts`
Expected: 3 pass.

- [ ] **Step 5: Commit**

```bash
git add src/core/session-manager/registry.ts src/core/session-manager/registry-register-connected.test.ts
git commit -m "feat(registry): register() accepts connected:false for pre-shim rows"
```

---

### Task 2: `spawnClaudeSession` creates the row synchronously

**Files:**
- Modify: `src/core/session-manager/spawn-helper.ts:446-484` (`spawnClaudeSession`)
- Modify: `src/core/session-manager/spawn-helper.ts` imports (top of file)
- Test: `src/core/session-manager/spawn-helper-claude.test.ts` (create)

- [ ] **Step 1: Read the cursor test harness for the house pattern**

Read `src/core/session-manager/spawn-helper-cursor.test.ts`. It shows how these tests build a `Registry`, a fake `sessionBackend`, and call the spawn function with seams. Mirror its style.

- [ ] **Step 2: Write the failing test**

```ts
// src/core/session-manager/spawn-helper-claude.test.ts
import { describe, expect, test } from "bun:test"
import { Registry } from "./registry"
import { spawnSession } from "./spawn-helper"
import { AgentKind } from "../../shared/agents"
import type { SessionBackend } from "../runtime/types"
import { mkdtempSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"

function fakeBackend(): SessionBackend {
  return {
    async list() { return [] },
    async create() { return { id: "@42", pid: 4242, name: "w" } },
    async kill() {},
    async livePid() { return 4242 },
    async resolve() { return undefined },
  } as unknown as SessionBackend
}

function deps(registry: Registry) {
  return {
    registry,
    bind: async () => {},
    sessionBackend: fakeBackend(),
    tmuxSession: "mux-test",
    postSpawnReady: async () => {},
  }
}

describe("spawnClaudeSession synchronous registration", () => {
  test("the row exists when spawn resolves, with window id and agent session id", async () => {
    const registry = new Registry()
    const workdir = mkdtempSync(join(tmpdir(), "claude-spawn-"))
    const r = await spawnSession(deps(registry), { workdir, agent: AgentKind.Claude })

    const row = registry.get(r.session_id)
    expect(row).toBeDefined()
    expect(row?.agent).toBe("claude")
    expect(row?.tmux_window_id).toBe("@42")
    expect(row?.agent_session_id).toBeTruthy()   // the generated claude session UUID
    expect(row?.pid).toBe(4242)
    expect(row?.connected).toBe(false)           // shim has not joined yet
  })

  test("internal spawns carry the internal flag at birth", async () => {
    const registry = new Registry()
    const workdir = mkdtempSync(join(tmpdir(), "claude-spawn-"))
    const r = await spawnSession(deps(registry), { workdir, agent: AgentKind.Claude, internal: true })
    expect(registry.get(r.session_id)?.internal).toBe(true)
  })

  test("a failed tmux create leaves no row and no reservation", async () => {
    const registry = new Registry()
    const workdir = mkdtempSync(join(tmpdir(), "claude-spawn-"))
    const backend = fakeBackend()
    ;(backend as { create: unknown }).create = async () => { throw new Error("boom") }
    await expect(
      spawnSession({ ...deps(registry), sessionBackend: backend }, { workdir, agent: AgentKind.Claude }),
    ).rejects.toThrow("boom")
    expect(registry.list().length).toBe(0)
    expect(registry.takenNames().size).toBe(0)
  })
})
```

Adjust imports to the real exported names if they differ (`spawnSession` is the exported dispatcher at `spawn-helper.ts:126`; `SessionBackend` type location: check `src/core/runtime/`). Check `SpawnArgs` for the `internal` field name.

- [ ] **Step 3: Run the test. Verify it fails.**

Run: `bun test src/core/session-manager/spawn-helper-claude.test.ts`
Expected: FAIL — `registry.get(r.session_id)` is `undefined` (today only the name is reserved).

- [ ] **Step 4: Implement**

Replace `spawnClaudeSession` (`spawn-helper.ts:446-484`) with:

```ts
async function spawnClaudeSession(deps: SpawnDeps, args: SpawnArgs): Promise<SpawnResult> {
  const backend = deps.sessionBackend ?? getSessionBackend()
  const base = args.requestedName ?? deriveName(args.workdir)
  // (keep the existing window-name-collision comment block here verbatim)
  const existingWindows = (await backend.list(deps.tmuxSession)).map(target => target.name)
  const name = ensureUnique(base, new Set([...deps.registry.takenNames(), ...existingWindows]))
  const id = randomUUID()
  const claudeSessionId = randomUUID()
  deps.registry.reserveName(name)
  preAcceptTrust(args.workdir)
  try {
    await deps.bind(id)
    const spec = buildClaudeSpawnSpec({ name, model: args.model, effort: args.effort, sessionId: id, claudeSessionId, workdir: args.workdir, rpcMcpConfig: args.rpcMcpConfig })
    const target = await backend.create({
      group: deps.tmuxSession,
      name,
      cwd: args.workdir,
      ...spec,
      cols: 80,
      rows: 24,
    })
    // The row is born HERE, synchronously — not in onRegister. The shim's
    // register frame later ATTACHES to this row (main.ts onRegister).
    // connected:false — the socket layer flips it when the shim joins.
    deps.registry.register({
      id,
      name,
      agent: AgentKind.Claude,
      workdir: args.workdir,
      tmux_target: `${deps.tmuxSession}:${name}`,
      tmux_window_id: target.id,
      pid: target.pid ?? process.pid,
      agent_session_id: claudeSessionId,
      internal: args.internal,
      connected: false,
      base_commits: captureBaseCommits(args.workdir),
    })
    await (deps.postSpawnReady ?? ((targetId) => sendChannelConsentEnter(targetId, { backend })))(target.id)
  } catch (err) {
    // Free the reservation AND any row so a retry can reclaim the name.
    deps.registry.releaseName(name)
    if (deps.registry.get(id)) deps.registry.sessions.deleteById(id)
    throw err
  }
  return { name, session_id: id, model: args.model }
}
```

Add the `captureBaseCommits` helper near the top of `spawn-helper.ts` (it moves the inline scan from `main.ts:2546-2552`):

```ts
import { scanRepos } from "../worktree/repos"   // adjust to the real scanRepos location (grep "scanRepos" in main.ts imports)
import { execSync } from "child_process"

/** HEAD of every repo under the workdir at session birth. Used for diff bases. */
export function captureBaseCommits(workdir: string): Record<string, string> {
  const out: Record<string, string> = {}
  for (const repo of scanRepos(workdir)) {
    try { out[repo.relPath] = execSync("git rev-parse HEAD", { cwd: repo.absPath, encoding: "utf-8", timeout: 5000 }).trim() } catch {}
  }
  return out
}
```

Check the real import path of `scanRepos` by reading `main.ts` imports (grep `scanRepos`). Check `SessionStore` for `deleteById` (used by the draft flow, `main.ts:2082`); if the method has another name, use that.

Remove the now-dead `deps.onRuntimeTargetId?.(id, target.id)` and `deps.onClaudeSessionId?.(name, claudeSessionId)` calls from this function. Do NOT delete the `SpawnDeps` members yet — Task 5 does that after all callers are clean.

- [ ] **Step 5: Run the tests. Verify all pass.**

Run: `bun test src/core/session-manager/`
Expected: the new test passes; `spawn-window-collision.test.ts` and `spawn-registration.test.ts` may fail if they assert the OLD behavior. Read any failure. If a test asserts "row does not exist until onRegister", update that assertion to the new truth (row exists, `connected=false`). Do not weaken unrelated assertions.

- [ ] **Step 6: Commit**

```bash
git add src/core/session-manager/spawn-helper.ts src/core/session-manager/spawn-helper-claude.test.ts
git commit -m "feat(spawn): claude session row is born in the spawn path"
```

---

### Task 3: `spawnSession` waits for connection, not registration

**Files:**
- Modify: `src/main.ts:3084-3112`

- [ ] **Step 1: Replace the wait block**

The row now exists when `spawnSessionHelper` returns. The wait's job becomes: prove the shim came up (same survival guarantee as today). Replace `main.ts:3084-3112` with:

```ts
  // Claude's row now exists synchronously. Wait for the shim to CONNECT —
  // proof the window survived and the agent came up — while polling window
  // liveness so an instant death fast-fails.
  let registered = registry.get(r.session_id)
  if ((args.agent ?? "claude") === "claude") {
    registered = await waitForRegisteredSession({
      id: r.session_id,
      name: r.name,
      lookup: (id) => {
        const s = registry.get(id)
        return s?.connected ? s : undefined
      },
      stillAlive: async () => {
        const wid = registry.get(r.session_id)?.tmux_window_id
        return wid ? (await sessionBackend.livePid(wid)) !== null : false
      },
    }).catch((err) => {
      log.warn("spawn_post_check_failed", { name: r.name, workdir })
      throw err
    })
  }
```

Delete the `pendingInternal.add(...)` line (`main.ts:3087` — the flag now travels through `SpawnArgs.internal` into the register call). Delete the `liveWindowId` import if this was its last caller (check: `grep -n "liveWindowId" src/`).

- [ ] **Step 2: Broadcast from the spawn path**

`onRegister`'s fresh branch broadcast (`main.ts:2555-2558`) dies in Task 4. Add the broadcast right after the wait block above:

```ts
  const bornRow = registry.get(r.session_id)
  if (bornRow && !bornRow.internal && (args.agent ?? "claude") === "claude") {
    webChannel?.broadcastToAll({
      type: "session_added",
      session: { id: bornRow.id, name: bornRow.name, workdir: bornRow.workdir, mute: false, connected: bornRow.connected, agent: bornRow.agent, user_status: bornRow.user_status, sort_order: bornRow.sort_order, draft_payload: bornRow.draft_payload },
    })
    await refreshTelegramMenu()
  }
```

Duplicate `session_added` frames are tolerated: web `sessions.applyAdded` upserts by id, and the web spawn opt at `main.ts:1453` already double-broadcast with onRegister today. Verify the upsert claim by reading the web store's handler (`grep -n "session_added" src/web-app/src/`); if it appends blindly, dedupe there first.

- [ ] **Step 3: Run the full session-manager suite**

Run: `bun test src/core/session-manager/ src/core/routing/ 2>&1 | tail -20`
Expected: green.

- [ ] **Step 4: Commit**

```bash
git add src/main.ts
git commit -m "feat(spawn): wait for shim connection instead of row registration"
```

---

### Task 4: `onRegister` becomes attach-only

**Files:**
- Modify: `src/main.ts:2471-2602`

- [ ] **Step 1: Merge the fresh branch into a refusal**

The reconnect branch (`main.ts:2479-2515`) is already the attach path. It becomes the ONLY path. Replace the whole fresh branch (`main.ts:2517-2601`) with:

```ts
      // No row → nothing to attach to. The spawn path creates every legitimate
      // row before claude starts. An unknown id is a manually-started claude
      // (shim loaded from global config, random session id) or a session that
      // was killed in the ~1s startup gap. Refuse both; never create rows here.
      log.warn("register_unknown_session", { session_id: sessionUuid, requested, workdir })
      throw new Error(`unknown session: ${sessionUuid} — the broker did not spawn this session`)
```

- [ ] **Step 2: Check the error path in socket-server and the shim**

Read `src/core/session-manager/socket-server.ts:200-215` (the `onRegister` call site). If a thrown error is not already caught and answered as an error frame, wrap the call:

```ts
      // inside the register frame handler
      try {
        const result = await opts.handler.onRegister({ ...m, session_id })
        // existing success write of {kind:"registered", ...result}
      } catch (err) {
        log.warn("register_refused", { session_id, error: String(err) })
        socket.end()   // the shim retries or gives up; a refused session gets no channel
        return
      }
```

Read `src/shim/socket-client.ts` to confirm a closed socket does not crash claude (it should degrade to "channel unavailable"). If the shim loops on reconnect, confirm the retry is bounded or harmless (log-only).

- [ ] **Step 3: Complete the attach branch**

The attach branch must now also cover the FIRST connect of a fresh spawn. Compare it against the old fresh branch and keep these behaviors (most already exist at `main.ts:2479-2514`):

- `setAgentSessionId` from `msg.agent_session_id` — exists (2481-2483).
- Drop the `pendingRuntimeTargetId` drain (2484-2488) — the row has its window id from birth.
- Adapter build + `registerClaudeRuntime` + `wireClaudeStateEvents` — exists (2490-2505).
- `activate` when suspended — exists (2506-2508).
- `ensureClaudeTailer` — exists (2509).
- `commandRegistry.refresh` — exists (2510).
- Soul-setup auto-send for default PAs — exists (2511-2513).

No `session_added` broadcast here (Task 3 moved it). No `refreshTelegramMenu` (same). No `pendingSpawnActive` flip (Task 5 removes the map).

- [ ] **Step 4: Run the full suite**

Run: `bun test 2>&1 | tail -20`
Expected: green. `socket-server.test.ts` exercises `onRegister` handlers — read and update any test that registers an unknown session and expects a created row: it must now expect refusal.

- [ ] **Step 5: Commit**

```bash
git add src/main.ts src/core/session-manager/socket-server.ts
git commit -m "feat(register): onRegister attaches to existing rows only"
```

---

### Task 5: Delete the staging maps

**Files:**
- Modify: `src/main.ts:573-585` (map declarations), `src/main.ts:3050-3078` (deps callbacks), `src/main.ts:3365` (`pendingSpawnActive` producer), `src/main.ts:3754-3756` (supervisor `onClaudeSessionId`)
- Modify: `src/core/session-manager/spawn-helper.ts` (`SpawnDeps`/`spawnPA` members, `spawnPA:226`)
- Modify: `src/core/session-manager/supervisor.ts:119` (`respawnPA`), `supervisor.ts:47` (opts type)

- [ ] **Step 1: Replace the callback bodies with direct writes**

- `spawn-helper.ts:226` (`spawnPA` claude branch): replace `opts.onClaudeSessionId?.(id, claudeSessionId)` with `registry.sessions.setAgentSessionId(id, claudeSessionId)` (the row exists — `registerPA` ran above). Actually simpler: pass `agent_session_id: claudeSessionId` into the `registerPA` call at `spawn-helper.ts:195-204` and delete line 226. `registerPA` forwards unknown fields? Read it (`registry.ts:101-128`) — it does NOT forward `agent_session_id`; add the field to `registerPA`'s input type and its inner `register` call.
- `supervisor.ts:119`: replace `opts.onClaudeSessionId?.(pa.id, claudeSessionId)` with `opts.registry.sessions.setAgentSessionId(pa.id, claudeSessionId)`.
- `main.ts:3365`: replace `pendingSpawnActive.set(r.name, msg.chat_id)` with `registry.setActive(msg.chat_id, r.session_id)` — read the surrounding lines first; `r.session_id` must be in scope (the /spawn handler has the spawn result).

- [ ] **Step 2: Delete the members and the maps**

- Delete from `SpawnDeps` (`spawn-helper.ts`): `onClaudeSessionId`, `onRuntimeTargetId`, `onCodexSessionId` (the dead one — verify with `grep -n "deps.onCodexSessionId" src/`).
- Delete from `spawnPA` opts and `SupervisorOpts`: `onClaudeSessionId`.
- Delete from `main.ts`: the four map declarations (`pendingClaudeSessionId`, `pendingRuntimeTargetId`, `pendingInternal`, `pendingSpawnActive`, lines 573-585) and every remaining reference. `grep -n "pendingClaudeSessionId\|pendingRuntimeTargetId\|pendingInternal\|pendingSpawnActive" src/` must return zero hits.
- Delete the deps-bag entries in `main.ts:3069-3078` (the `onClaudeSessionId`/`onRuntimeTargetId` closures) and `main.ts:3754-3756`.

- [ ] **Step 3: Run everything**

Run: `bun test 2>&1 | tail -20` and `grep -rn "pendingClaude\|pendingRuntime\|pendingInternal\|pendingSpawnActive" src/ | grep -v test`
Expected: tests green; grep silent.

- [ ] **Step 4: Commit**

```bash
git add -A src/
git commit -m "refactor(spawn): delete the four staging maps and their callbacks"
```

---

### Task 6: Simplify `liveWindowId` or delete it

**Files:**
- Modify or delete: `src/core/session-manager/live-window.ts`, `src/core/session-manager/live-window.test.ts`

- [ ] **Step 1: Find the remaining callers**

Run: `grep -rn "liveWindowId" src/ | grep -v test`
Task 3 removed the spawn-wait caller. If callers remain, remove the second lookup parameter (the pending-map probe) and update them. If none remain, delete the file and its test.

- [ ] **Step 2: Run tests, commit**

```bash
bun test src/core/session-manager/ 2>&1 | tail -5
git add -A src/
git commit -m "refactor(spawn): liveWindowId loses its staging-map probe"
```

---

### Task 7: Full verify + live smoke

- [ ] **Step 1: Full suite**

Run: `bun test 2>&1 | tail -10`
Expected: green, zero failures.

- [ ] **Step 2: Type-check the whole tree**

Run: `bunx tsc --noEmit 2>&1 | head -20` (or the repo's check script if one exists in `package.json`)
Expected: no errors.

- [ ] **Step 3: Verify gate**

Run: `.mux/verify.sh`
Expected: exits 0.

- [ ] **Step 4: Manual smoke note**

A real end-to-end claude spawn needs a live broker. Do NOT restart the user's live broker. Options: the `mux:preview-broker` skill (worktree broker swap with auto-revert) at the user's request, or leave the smoke to the Finish flow. Record in the final report which option ran.

- [ ] **Step 5: Final commit if anything is uncommitted**

```bash
git status --short
git add -A && git commit -m "chore: move-1 cleanup" || true
```

---

## Self-review checklist (run after the last task)

1. Spec coverage: Move 1's deletion list = 4 staging maps ✓ (Task 5), registration poll → connected wait ✓ (Task 3), double-keyed drain ✓ (Task 4), both irreducible callbacks ✓ (Task 5). Row-missing refusal ✓ (Task 4).
2. Placeholder scan: none — every step has code or an exact command.
3. Type consistency: `captureBaseCommits` (Task 2) is exported from spawn-helper and used only there; `connected?: boolean` (Task 1) is used by Task 2's register call; `waitForRegisteredSession`'s `lookup` signature (id, name) is unchanged — Task 3 ignores the name argument.

## Known behavior changes (flag in the PR description)

1. A manually-started claude (global shim config, no broker spawn) can no longer register a phantom session row. It gets a refusal log line instead.
2. `session_added` for claude sessions now broadcasts after the shim connects (spawn path) rather than from inside onRegister — same ordering, single source.
3. A claude row exists (connected:false) during the ~1s startup window. Inbound messages during that window queue in the socket layer and flush on connect — previously the row did not exist and routing could not target it at all.
