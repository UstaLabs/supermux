# tmux window-id addressing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the tmux window id (`@N`) the single source of truth for addressing a session's tmux window; no code path may depend on the window *name* for addressing, input delivery, liveness, or teardown.

**Architecture:** Add two id-based tmux helpers and one small pure `ensureWindowId` resolver (heals a missing id once via a name→id lookup, then persists it). Switch `sendChannelConsentEnter`, the kill/interrupt/liveness paths, and their callers to address by id. Keep all window-name slugging exactly as today (names stay cosmetic, unique labels).

**Tech Stack:** TypeScript on Bun, tmux CLI, `bun test`, `tsc --noEmit`.

Spec: `docs/superpowers/specs/2026-06-29-tmux-window-id-addressing-design.md`

---

## File structure

- `src/core/session-manager/tmux.ts` — add `capturePaneById`, `resolveWindowIdByName` (id-based helpers alongside the existing `sendKeysToWindowId`/`killWindowById`/`livePanePid`).
- `src/core/session-manager/window-id.ts` — **new**, single responsibility: `ensureWindowId(session, deps)` pure resolver + heal.
- `src/core/session-manager/window-id.test.ts` — **new**, unit tests for the resolver.
- `src/core/session-manager/post-spawn-keys.ts` — `sendChannelConsentEnter(windowId, …)` addresses by id.
- `src/core/session-manager/post-spawn-keys.test.ts` — assert id-based capture/send.
- `src/main.ts` — retire `claudeTmuxTarget`/`requireClaudeTmux` string target; use `ensureWindowId` + id-based kill/interrupt/liveness/consent.
- `src/core/session-manager/supervisor.ts` — id-based stale-window teardown + consent.
- `src/core/session-manager/spawn-helper.ts` — consent callers pass the window id.

## Execution waves (file-disjoint → safe to parallelize)

- **Wave 1 (parallel):** Task 1 (`tmux.ts`), Task 2 (`window-id.ts`).
- **Wave 2:** Task 3 (`post-spawn-keys.ts`) — needs Task 1.
- **Wave 3 (parallel, disjoint files):** Task 4 (`main.ts`), Task 5 (`supervisor.ts`), Task 6 (`spawn-helper.ts`) — need Tasks 1–3.
- **Wave 4:** Task 7 verification + review.

---

### Task 1: id-based tmux helpers

**Files:**
- Modify: `src/core/session-manager/tmux.ts` (add two functions inside the factory; export them both from the factory return object AND the module-level export block, matching the existing pattern for `sendKeysToWindowId`).
- Test: `src/core/session-manager/tmux.test.ts`

- [ ] **Step 1: Write failing tests**

Add to `tmux.test.ts` (use the existing fake-`run` harness in that file; mirror how `spawnSessionWindow`/`livePanePid` are tested — inject a fake command runner that records argv and returns canned `{code, stdout, stderr}`):

```ts
test("resolveWindowIdByName returns the @id whose window_name matches", async () => {
  const client = makeTmuxClient(fakeRun({
    "list-windows": { code: 0, stdout: "@1\tother\n@7\tMy Session\n", stderr: "" },
  }))
  expect(await client.resolveWindowIdByName("mux", "My Session")).toBe("@7")
})

test("resolveWindowIdByName returns null when no name matches", async () => {
  const client = makeTmuxClient(fakeRun({
    "list-windows": { code: 0, stdout: "@1\tother\n", stderr: "" },
  }))
  expect(await client.resolveWindowIdByName("mux", "absent")).toBeNull()
})

test("capturePaneById targets the window id", async () => {
  const calls: string[][] = []
  const client = makeTmuxClient(recordingRun(calls, { code: 0, stdout: "pane text", stderr: "" }))
  const out = await client.capturePaneById("@7")
  expect(out).toBe("pane text")
  expect(calls.some(c => c.includes("capture-pane") && c.includes("@7"))).toBe(true)
})
```

> If `tmux.test.ts` lacks a reusable `fakeRun`/`recordingRun`, follow the harness already used by the existing `spawnSessionWindow returns tmux window id` test (`tmux.test.ts:5`) and adapt — do not invent a new mocking style.

- [ ] **Step 2: Run tests, verify they fail**

Run: `bun test src/core/session-manager/tmux.test.ts`
Expected: FAIL — `resolveWindowIdByName`/`capturePaneById` are not functions.

- [ ] **Step 3: Implement the helpers**

Inside the factory in `tmux.ts` (same scope as `sendKeysToWindowId`, using the existing `run(args)` closure):

```ts
async function capturePaneById(windowId: string): Promise<string> {
  const r = await run(["capture-pane", "-t", windowId, "-p", "-S", "-150"])
  return r.code === 0 ? r.stdout : ""
}

async function resolveWindowIdByName(session: string, name: string): Promise<string | null> {
  const r = await run(["list-windows", "-t", session, "-F", "#{window_id}\t#{window_name}"])
  if (r.code !== 0) return null
  for (const line of r.stdout.split("\n")) {
    const tab = line.indexOf("\t")
    if (tab < 0) continue
    const id = line.slice(0, tab).trim()
    const wname = line.slice(tab + 1)
    if (wname === name && id) return id
  }
  return null
}
```

Add `capturePaneById` and `resolveWindowIdByName` to the factory `return { … }` (tmux.ts:131) and to the module-level export block (tmux.ts:134-141).

- [ ] **Step 4: Run tests, verify pass**

Run: `bun test src/core/session-manager/tmux.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/core/session-manager/tmux.ts src/core/session-manager/tmux.test.ts
git commit -m "feat(tmux): add capturePaneById + resolveWindowIdByName id-based helpers"
```

---

### Task 2: `ensureWindowId` resolver (new module)

**Files:**
- Create: `src/core/session-manager/window-id.ts`
- Test: `src/core/session-manager/window-id.test.ts`

- [ ] **Step 1: Write failing tests** (`window-id.test.ts`)

```ts
import { test, expect } from "bun:test"
import { ensureWindowId } from "./window-id"

const baseDeps = (resolved: string | null, calls: any[]) => ({
  tmuxSession: "mux",
  resolve: async (_s: string, _n: string) => resolved,
  persist: (id: string, wid: string) => { calls.push([id, wid]) },
})

test("returns the stored window id without resolving", async () => {
  const calls: any[] = []
  const deps = baseDeps("@9", calls)
  const wid = await ensureWindowId({ id: "s1", name: "x", tmux_window_id: "@3" }, deps)
  expect(wid).toBe("@3")
  expect(calls).toEqual([])
})

test("heals a missing id via name lookup and persists it", async () => {
  const calls: any[] = []
  const wid = await ensureWindowId({ id: "s1", name: "My Session", tmux_window_id: undefined }, baseDeps("@7", calls))
  expect(wid).toBe("@7")
  expect(calls).toEqual([["s1", "@7"]])
})

test("returns null and does not persist when no window matches", async () => {
  const calls: any[] = []
  const wid = await ensureWindowId({ id: "s1", name: "gone", tmux_window_id: undefined }, baseDeps(null, calls))
  expect(wid).toBeNull()
  expect(calls).toEqual([])
})
```

- [ ] **Step 2: Run tests, verify fail**

Run: `bun test src/core/session-manager/window-id.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement** (`window-id.ts`)

```ts
import type { Session } from "./types"

export type EnsureWindowIdDeps = {
  tmuxSession: string
  resolve: (session: string, name: string) => Promise<string | null>
  persist: (sessionId: string, windowId: string) => void
}

// The addressable tmux window id for a claude session. Heals a missing id once
// via a name->id lookup (legacy/pre-migration-014 rows, or a spawn whose id
// capture failed), persists it, then returns it. Never returns a name-string
// target: callers address tmux strictly by id. Returns null when no live window
// can be found, so callers no-op + log instead of routing by name.
export async function ensureWindowId(
  session: Pick<Session, "id" | "name" | "tmux_window_id">,
  deps: EnsureWindowIdDeps,
): Promise<string | null> {
  if (session.tmux_window_id) return session.tmux_window_id
  const resolved = await deps.resolve(deps.tmuxSession, session.name)
  if (resolved) {
    deps.persist(session.id, resolved)
    return resolved
  }
  return null
}
```

> Verify `Session` in `./types` has `id`, `name`, `tmux_window_id?`. It does (types.ts:53-55). If the `Pick` complains, widen to the exact field names present.

- [ ] **Step 4: Run tests, verify pass**

Run: `bun test src/core/session-manager/window-id.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/core/session-manager/window-id.ts src/core/session-manager/window-id.test.ts
git commit -m "feat(session): add ensureWindowId resolver (heals null tmux_window_id by name once)"
```

---

### Task 3: consent-enter addresses by window id

**Files:**
- Modify: `src/core/session-manager/post-spawn-keys.ts`
- Test: `src/core/session-manager/post-spawn-keys.test.ts`

Behavior is identical (resume-menu, bypass-warning, retry-until-clear, listening-marker early return) — only the *target* changes from a `session:name` string to a window id, and the defaults move to id-based tmux fns.

- [ ] **Step 1: Update tests to assert id-based addressing**

In `post-spawn-keys.test.ts`, change every call that passes a `"mux:<name>"` target to pass a window id like `"@7"`, and assert the injected `sendKeysFn` / `capturePane` are invoked with `"@7"`. Preserve ALL existing scenarios (consent Enter sent + retried, resume menu "2"+Enter, bypass-warning Down+Enter, already-listening early return, timeout). The injected-fn signatures stay `(target, keys)` / `(target)` — `target` is now the id.

- [ ] **Step 2: Run tests, verify fail**

Run: `bun test src/core/session-manager/post-spawn-keys.test.ts`
Expected: FAIL — current impl still calls with a name string / imports `sendKeys`.

- [ ] **Step 3: Implement**

In `post-spawn-keys.ts`:
- Replace the import `import { sendKeys } from "./tmux"` with `import { sendKeysToWindowId, capturePaneById } from "./tmux"`.
- Rename the param: `export async function sendChannelConsentEnter(windowId: string, opts?: {...})`.
- `const send = opts?.sendKeysFn ?? sendKeysToWindowId`.
- `const capture = opts?.capturePane ?? capturePaneById`.
- Replace the local `capturePaneText` default usage with `capturePaneById` (delete the now-unused `capturePaneText` + its `spawn` import if nothing else uses them).
- Replace every `tmuxTarget` identifier in the body and log fields with `windowId`.

- [ ] **Step 4: Run tests, verify pass**

Run: `bun test src/core/session-manager/post-spawn-keys.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/core/session-manager/post-spawn-keys.ts src/core/session-manager/post-spawn-keys.test.ts
git commit -m "refactor(post-spawn): drive consent-enter by tmux window id, not name"
```

---

### Task 4: main.ts — id-based addressing everywhere

**Files:**
- Modify: `src/main.ts`

Depends on Tasks 1–3. Read each site's current code before editing; the line numbers below are from the spec snapshot and may drift.

- [ ] **Step 1: Add helper + imports**

- Import: `import { ensureWindowId } from "./core/session-manager/window-id"` and add `capturePaneById, resolveWindowIdByName` to the existing `./core/session-manager/tmux` import (main.ts:34).
- Near the registry setup, define:

```ts
const widOf = (s: { id: string; name: string; tmux_window_id?: string }) =>
  ensureWindowId(s, {
    tmuxSession: TMUX_SESSION,
    resolve: resolveWindowIdByName,
    persist: (id, wid) => registry.sessions.setTmuxWindowId(id, wid),
  })
```

- [ ] **Step 2: Replace the string-target functions and their 4 callers**

- Delete `claudeTmuxTarget` (≈758-763) and `requireClaudeTmux` (≈765-770).
- `interruptClaudePane` (≈772-782):

```ts
async function interruptClaudePane(sessionId: string): Promise<void> {
  const s = registry.get(sessionId)
  if (!s || s.agent !== AgentKind.Claude) return
  const wid = await widOf(s)
  if (!wid) { log.warn("claude_interrupt_no_tmux", { sessionId }); return }
  await sendKeysToWindowId(wid, ["Escape"])
}
```

- Caller at ≈1399 (currently `const r = requireClaudeTmux(s)`): read its body; replace the `requireClaudeTmux` gate + any `sendKeys(target, …)` with `const wid = await widOf(s); if (!wid) { log.warn(...); <existing not-ready handling> } else await sendKeysToWindowId(wid, <same keys>)`. Preserve the surrounding control flow and the non-claude path.
- Kill at ≈1579-1583:

```ts
if (s.agent === "claude") {
  const wid = await widOf(s)
  if (wid) await killWindowById(wid)
  else log.warn("kill_session_no_tmux", { name: displayName })
}
```

- Kill at ≈2537-2540 (inside the claude respawn branch): keep the non-claude early-return error, then:

```ts
const wid = await widOf(session)
if (wid) await killWindowById(wid)
```

(remove the `requireClaudeTmux`/`killSessionWindow({window:name})` lines.)

- PA kill at ≈3079-3080:

```ts
const wid = await widOf(pa)
if (wid) await killWindowById(wid).catch(() => {})
```

- [ ] **Step 3: Liveness by id (≈2506)**

Replace:

```ts
stillAlive: async () => (await listSessionWindows(TMUX_SESSION)).includes(r.name),
```

with:

```ts
stillAlive: async () => {
  const wid = registry.get(r.session_id)?.tmux_window_id
  return wid ? (await livePanePid(wid)) !== null : false
},
```

- [ ] **Step 4: Consent callers pass the window id (≈1677, ≈1769)**

- ≈1677: delete the `const tmuxTarget = …` line; `await sendChannelConsentEnter(tmuxWindow.windowId)`.
- ≈1769: `void sendChannelConsentEnter(tmuxWindow.windowId)` (uses the `resumedTmuxWindowId`/`tmuxWindow.windowId` already captured just above).

> If a `windowId` could be `undefined` at a call site, guard: `if (tmuxWindow.windowId) await sendChannelConsentEnter(tmuxWindow.windowId)`.

- [ ] **Step 5: Verify no per-session name addressing remains in main.ts**

Run: `grep -nE "killSessionWindow\(|\\\$\{TMUX_SESSION\}:|listSessionWindows\(.*includes|claudeTmuxTarget|requireClaudeTmux|sendKeys\(" src/main.ts`
Expected: no per-session addressing hits. (`listSessionWindows` may remain only if used for a non-addressing purpose; `${TMUX_SESSION}:` should be gone except possibly the vestigial `tmux_target` backfill at ≈282, which is allowed to stay.)

- [ ] **Step 6: Typecheck + targeted tests**

Run: `bunx tsc --noEmit 2>&1 | grep -E "main\.ts" || echo "no main.ts type errors"`
Run: `bun test src/core/session-manager/` (helpers still green)
Expected: no new main.ts type errors.

- [ ] **Step 7: Commit**

```bash
git add src/main.ts
git commit -m "refactor(broker): address tmux windows by id; retire name-string targets"
```

---

### Task 5: supervisor.ts — id-based teardown + consent

**Files:**
- Modify: `src/core/session-manager/supervisor.ts`

- [ ] **Step 1: `respawnPA` stale-window teardown by id (≈79-91)**

Replace the `normalizeName(pa.name)` + `while (windows.includes(tmuxWindowName)) killSessionWindow({window:tmuxWindowName})` cleanup with an id kill:

```ts
if (pa.agent === AgentKind.Claude && pa.tmux_window_id) {
  await killWindowById(pa.tmux_window_id).catch(() => {})
}
```

Add `killWindowById` to the `./tmux` import (supervisor.ts:6). Remove now-unused `normalizeName`/`listSessionWindows`/`killSessionWindow` imports IF nothing else in the file uses them (check first).

- [ ] **Step 2: `bootstrapPA` (≈153-171)**

A fresh bootstrap has no `tmux_window_id` yet, so there is no id to kill. Drop the name-based stale-window cleanup loop (≈164-171) — under id-addressing a same-named orphan window is harmless cosmetic clutter and is reclaimed by `reconcileOnStartup`/restart. Leave a one-line comment noting this. (Flag for review: confirms intentional removal of best-effort name cleanup.)

- [ ] **Step 3: consent by id (≈121)**

`void sendChannelConsentEnter(tmuxWindow.windowId)` (the `tmuxWindow` from the `spawnTmux` call at ≈103; guard if its `windowId` may be undefined). Remove the now-unused `tmuxWindowName = normalizeName(pa.name)` if it was only used for the window name/consent (the spawn still passes its own `window:` name — keep that).

> Re-read ≈79-171 before editing: the file uses `tmuxWindowName` for BOTH the `spawnTmux({window})` arg AND teardown/consent. Keep it for the `spawnTmux({window})` arg (cosmetic name slug stays, D1); only remove its use for teardown/consent addressing.

- [ ] **Step 4: Typecheck + tests**

Run: `bunx tsc --noEmit 2>&1 | grep -E "supervisor\.ts" || echo "no supervisor type errors"`
Run: `bun test src/core/session-manager/` (and any `supervisor.test.ts`)
Expected: green.

- [ ] **Step 5: Commit**

```bash
git add src/core/session-manager/supervisor.ts
git commit -m "refactor(supervisor): tear down PA windows by id, not name"
```

---

### Task 6: spawn-helper.ts — consent callers pass the id

**Files:**
- Modify: `src/core/session-manager/spawn-helper.ts`

- [ ] **Step 1: Pass the window id to consent (≈375, ≈408)**

At each `sendChannelConsentEnter(`${…}:${name}`)` call, use the `tmuxWindow.windowId` captured from the preceding `spawnTmux`/`deps.spawnTmux` call:

```ts
if (tmuxWindow?.windowId) await sendChannelConsentEnter(tmuxWindow.windowId)
```

(At ≈408 the call is `(deps.postSpawnReady ?? sendChannelConsentEnter)(...)` — keep the `postSpawnReady` override seam, just pass `tmuxWindow.windowId` as its arg.) Read both sites to confirm the `tmuxWindow` variable name in scope.

- [ ] **Step 2: Typecheck + tests**

Run: `bunx tsc --noEmit 2>&1 | grep -E "spawn-helper\.ts" || echo "no spawn-helper type errors"`
Run: `bun test src/core/session-manager/`
Expected: green.

- [ ] **Step 3: Commit**

```bash
git add src/core/session-manager/spawn-helper.ts
git commit -m "refactor(spawn): pass tmux window id to consent-enter"
```

---

### Task 7: Integration verification + review

**Files:** none (verification only).

- [ ] **Step 1: Full typecheck**

Run: `bunx tsc --noEmit`
Expected: no NEW errors vs the pre-existing baseline (per the project's known typecheck notes).

- [ ] **Step 2: Full test suite**

Run: `bun test`
Expected: green except the documented pre-existing failures (`no-legacy-names` false positive; a `spawn-command` reply-fallback test). No NEW failures.

- [ ] **Step 3: Global grep — no per-session name addressing left**

Run:
```bash
grep -rnE "sendKeys\(|killSessionWindow\(|capturePane -t \"?\\\$|\\\$\{TMUX_SESSION\}:\\\$\{(name|finalName|windowName|displayName)" src --include=*.ts | grep -v "\.test\.ts"
```
Expected: only intentional leftovers (the vestigial `tmux_target` backfill string at main.ts:282; the still-exported but uncalled `killSessionWindow`/`sendKeys` definitions in tmux.ts). No live per-session addressing by name.

- [ ] **Step 4: Code review**

Use `superpowers:requesting-code-review`. Confirm: (a) every spawn path persists a window id; (b) `ensureWindowId` is the only place a name is used, and only to *find* an id; (c) no behavior change to consent-enter logic; (d) D1 — window names still slugged/unique.

- [ ] **Step 5: Final commit (if review fixes needed)**

```bash
git add -A && git commit -m "test: verify tmux id-addressing; review fixups"
```

## Self-review (plan vs spec)

- **Spec coverage:** consent-enter (Task 3 ✔), claudeTmuxTarget/interrupt (Task 4 ✔), kill paths 1582/2540/3080 (Task 4 ✔), supervisor stale loops (Task 5 ✔), liveness 2506 (Task 4 ✔), tmux_target retired from routing (Tasks 4/7 ✔), D3 healer (Task 2 + `widOf` ✔), D1 slugging untouched (Tasks 5/6 keep `window:` name ✔).
- **Placeholders:** new code + tests given in full; mechanical edits specify exact site + transformation + the rule. No TBD.
- **Type consistency:** `ensureWindowId(session, deps)` / `EnsureWindowIdDeps {tmuxSession, resolve, persist}` used identically in Task 2 and Task 4's `widOf`. `capturePaneById`/`resolveWindowIdByName` names match across Tasks 1, 3, 4.
