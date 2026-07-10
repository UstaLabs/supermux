# Claude Live Model/Effort Switching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Switching a Claude session's model or thinking effort applies live in the running TUI (typed `/model` / `/effort` via tmux send-keys, verified by pane capture) — never a kill+respawn.

**Architecture:** A new standalone sequencer module `src/core/agents/claude/live-switch.ts` (mirrors the proven `post-spawn-keys.ts` pattern: injectable send/capture seams, marker polling) replaces the claude branch of `reapplySessionAgentConfig` in `src/main.ts`. The existing queue-until-idle (`PendingReapply`) and failure-rollback plumbing stay; failure = explicit error, never a restart. Web switchers lose the "restarts the agent" copy and the "Change now" button for claude.

**Tech Stack:** Bun + TypeScript broker, `bun:test`, tmux CLI (`send-keys` / `capture-pane`), Vue 3 web app (`vue-tsc`).

**Spec:** `docs/superpowers/specs/2026-07-10-claude-live-model-effort-switch-design.md`

**Verified facts this plan relies on (tested against Claude Code 2.1.206 in a tmux sandbox on 2026-07-10):**
- `/model <full-api-id>` typed in the TUI prints `Set model to <DisplayName>` and applies instantly (works with conversation history; broker ids like `claude-opus-4-8` accepted).
- `/effort <low|medium|high|xhigh|max>` prints `Set effort level to <level>`; with conversation history it may first show a confirm menu titled `Change effort level?` whose default selection is `❯ 1. Yes, …` — a single `Enter` confirms. A typed `/effort` releases the `--effort` launch pin.
- `C-u` clears the composer's current input (TUI shows "Ctrl+Y to paste deleted text").
- Typed slash commands are local: no hooks fire, no transcript turn, so the broker's pure-reflector state machine is untouched. Never send `Escape` while a turn is running (it interrupts) — this module only runs on idle sessions.
- `capturePaneById` captures with `-S -150` (scrollback included) → success detection MUST be baseline-count-delta, not mere substring presence (an earlier switch's confirmation may sit in scrollback). Safety checks look only at the **tail** of the capture (bottom of the visible pane).

---

### Task 0: Worktree deps + baseline

**Files:** none (environment)

- [ ] **Step 0.1:** Ensure deps are installed (fresh worktrees have empty node_modules):

Run: `cd /home/ahmet/.mux/worktrees/supermux-3962b5bf/4d8e116c-a500-46ef-8327-40642c40ebd7 && [ -d node_modules ] && echo ok || bun install`
Expected: `ok` or install of ~192 packages.

- [ ] **Step 0.2:** Baseline targeted tests pass before changes:

Run: `bun test src/core/session-manager/pending-reapply.test.ts src/core/session-manager/post-spawn-keys.test.ts 2>&1 | tail -5`
Expected: all pass. (If `pending-reapply.test.ts` doesn't exist yet, only post-spawn-keys runs — fine.)

---

### Task 1: `changedSince` helper in pending-reapply

Lets the apply path type only what actually changed (a model-only switch must not also re-type `/effort`, and vice versa).

**Files:**
- Modify: `src/core/session-manager/pending-reapply.ts`
- Test: `src/core/session-manager/pending-reapply.test.ts` (create if absent, else append)

- [ ] **Step 1.1: Write the failing tests** — append to (or create) `src/core/session-manager/pending-reapply.test.ts`:

```ts
import { test, expect } from "bun:test"
import { changedSince } from "./pending-reapply"

test("changedSince: model changed only", () => {
  expect(changedSince({ oldModel: "claude-sonnet-5", oldReasoningLevel: "high" },
                      { model: "claude-opus-4-8", reasoningLevel: "high" }))
    .toEqual({ model: true, effort: false })
})

test("changedSince: effort changed only", () => {
  expect(changedSince({ oldModel: "claude-sonnet-5", oldReasoningLevel: "high" },
                      { model: "claude-sonnet-5", reasoningLevel: "max" }))
    .toEqual({ model: false, effort: true })
})

test("changedSince: both changed (queued model switch then effort switch)", () => {
  expect(changedSince({ oldModel: "claude-sonnet-5", oldReasoningLevel: undefined },
                      { model: "claude-opus-4-8", reasoningLevel: "low" }))
    .toEqual({ model: true, effort: true })
})

test("changedSince: nothing changed", () => {
  expect(changedSince({ oldModel: "m", oldReasoningLevel: "high" },
                      { model: "m", reasoningLevel: "high" }))
    .toEqual({ model: false, effort: false })
})
```

- [ ] **Step 1.2: Run to verify failure**

Run: `bun test src/core/session-manager/pending-reapply.test.ts`
Expected: FAIL — `changedSince` is not exported.

- [ ] **Step 1.3: Implement** — append to `src/core/session-manager/pending-reapply.ts`:

```ts
// Diff the pre-change values against the session's CURRENT stored values so the
// apply path can touch only what the user actually changed. Stored (not
// effective/resolved) values on both sides — consistent comparison.
export function changedSince(
  olds: PreChangeConfig,
  current: { model?: string; reasoningLevel?: string },
): { model: boolean; effort: boolean } {
  return {
    model: olds.oldModel !== current.model,
    effort: olds.oldReasoningLevel !== current.reasoningLevel,
  }
}
```

- [ ] **Step 1.4: Run to verify pass**

Run: `bun test src/core/session-manager/pending-reapply.test.ts`
Expected: PASS (4 new tests).

- [ ] **Step 1.5: Commit**

```bash
git add src/core/session-manager/pending-reapply.ts src/core/session-manager/pending-reapply.test.ts
git commit -m "feat(session): changedSince helper for partial model/effort reapply"
```

---

### Task 2: `live-switch` sequencer module

The core. Types `/model` / `/effort` into an idle claude TUI and verifies by pane capture. Injectable seams exactly like `post-spawn-keys.ts`.

**Files:**
- Create: `src/core/agents/claude/live-switch.ts`
- Test: `src/core/agents/claude/live-switch.test.ts`

**Behavior contract:**
1. No-op target (`{}` / both undefined) → `{ ok: true }`, nothing sent.
2. Pane safety gate (on the capture **tail**, last 15 lines): needs an unindented composer line (`/^❯/m`); rejects dialog markers (`Enter to confirm`, `Bypass Permissions mode`, `Resume from summary`) and select-menu pointer lines (`/^\s+❯ \d+\./m`). Polls up to `safetyWaitMs` (default 2s), then fails.
3. Clears any draft with `C-u`, then RE-CAPTURES and requires an EMPTY composer (`/^❯\s*$/m` in tail). One retry `C-u`, then fail **without typing** — guarantees we can never submit `"<leftover draft>/model x"` as a chat message to the agent.
4. Types the command literally (`send-keys -l`), waits `typeDelayMs` (500ms, lets slash-autocomplete settle — verified cadence), sends `Enter`.
5. Verifies success by **marker-count delta**: occurrences of `Set model to` / `Set effort level to` in the full capture must exceed the pre-type baseline (immune to stale confirmations in the `-S -150` scrollback).
6. While polling an effort switch, if the confirm menu (`Change effort level?`) is in the tail, sends `Enter` (default = "Yes"), re-sent at most every `menuRetryMs` (1s) — over-sending Enter on a cleared menu is a harmless empty submit (established post-spawn-keys fact).
7. Timeout (`confirmTimeoutMs`, 10s per part) → cleanup (`Escape` if the menu is up — safe, session is idle — else `C-u`) → `{ ok: false, error }`.
8. `{ model, effort }` both present → model first, then effort, each individually verified; first failure aborts the second.
9. Any `null` capture → fail (`window gone`).

- [ ] **Step 2.1: Write the failing tests** — create `src/core/agents/claude/live-switch.test.ts`:

```ts
import { test, expect } from "bun:test"
import { applyClaudeLiveSwitch } from "./live-switch"

const WID = "@42"

const IDLE_PANE = [
  "  some earlier transcript output",
  "────────",
  "❯ ",
  "────────",
  "  ⏸ manual mode on · ? for shortcuts",
].join("\n")

const IDLE_WITH_DRAFT = IDLE_PANE.replace("❯ ", "❯ some stray draft")

const MODEL_OK = IDLE_PANE + "\n  ⎿  Set model to Opus 4.8 and saved as your default for new sessions"
const EFFORT_OK = IDLE_PANE + "\n  ⎿  Set effort level to low (saved as your default for new sessions)"

const EFFORT_MENU = [
  "   Change effort level?",
  "   Your next response will be slower and use more tokens",
  "   ❯ 1. Yes, switch to low",
  "     2. No, go back",
].join("\n")

const PERMISSION_DIALOG = [
  "  Do you want to run this command?",
  "   ❯ 1. Yes",
  "     2. No",
].join("\n")

/** Seams: instant timings, scripted captures, recorded sends. */
function seams(captures: (string | null)[], opts?: { safetyWaitMs?: number; confirmTimeoutMs?: number }) {
  const sent: string[][] = []
  let i = 0
  return {
    seams: {
      pollIntervalMs: 0,
      typeDelayMs: 0,
      menuRetryMs: 0,
      safetyWaitMs: opts?.safetyWaitMs ?? 5_000,
      confirmTimeoutMs: opts?.confirmTimeoutMs ?? 5_000,
      sendKeysFn: async (_wid: string, keys: string[]) => { sent.push(keys) },
      capturePane: async () => {
        const r = captures[i]
        if (i < captures.length - 1) i++ // hold last frame (deadline decides)
        return r === undefined ? null : r
      },
    },
    sent,
  }
}

test("no-op target: ok, nothing sent or captured", async () => {
  const { seams: s, sent } = seams([IDLE_PANE])
  const r = await applyClaudeLiveSwitch(WID, {}, s)
  expect(r).toEqual({ ok: true })
  expect(sent).toHaveLength(0)
})

test("model switch happy path: C-u, literal command, Enter, success on marker", async () => {
  const { seams: s, sent } = seams([
    IDLE_PANE,   // safety check
    IDLE_PANE,   // post C-u empty-composer check
    MODEL_OK,    // verify poll → marker appeared
  ])
  const r = await applyClaudeLiveSwitch(WID, { model: "claude-opus-4-8" }, s)
  expect(r).toEqual({ ok: true })
  expect(sent).toEqual([["C-u"], ["-l", "/model claude-opus-4-8"], ["Enter"]])
})

test("stale confirmation in scrollback does NOT count (baseline delta)", async () => {
  // Pane already contains an old "Set model to" from a previous switch.
  const stale = MODEL_OK
  const fresh = MODEL_OK + "\n  ⎿  Set model to Sonnet 5 and saved as your default for new sessions"
  const { seams: s } = seams([
    stale,  // safety check (baseline = 1)
    stale,  // post C-u check
    stale,  // poll: no NEW marker yet
    fresh,  // poll: delta → success
  ])
  const r = await applyClaudeLiveSwitch(WID, { model: "claude-sonnet-5" }, s)
  expect(r).toEqual({ ok: true })
})

test("effort switch with confirm menu: Enter confirms, then success", async () => {
  const { seams: s, sent } = seams([
    IDLE_PANE,    // safety
    IDLE_PANE,    // post C-u
    EFFORT_MENU,  // poll → menu up → Enter
    EFFORT_OK,    // poll → marker → success
  ])
  const r = await applyClaudeLiveSwitch(WID, { effort: "low" }, s)
  expect(r).toEqual({ ok: true })
  expect(sent).toEqual([["C-u"], ["-l", "/effort low"], ["Enter"], ["Enter"]])
})

test("model then effort sequentially, each verified", async () => {
  const MODEL_THEN_BASE = MODEL_OK
  const BOTH_OK = MODEL_OK + "\n  ⎿  Set effort level to max (saved as your default for new sessions)"
  const { seams: s, sent } = seams([
    IDLE_PANE,        // model: safety
    IDLE_PANE,        // model: post C-u
    MODEL_THEN_BASE,  // model: success
    MODEL_THEN_BASE,  // effort: safety
    MODEL_THEN_BASE,  // effort: post C-u
    BOTH_OK,          // effort: success
  ])
  const r = await applyClaudeLiveSwitch(WID, { model: "claude-opus-4-8", effort: "max" }, s)
  expect(r).toEqual({ ok: true })
  expect(sent).toEqual([
    ["C-u"], ["-l", "/model claude-opus-4-8"], ["Enter"],
    ["C-u"], ["-l", "/effort max"], ["Enter"],
  ])
})

test("unsafe pane (permission dialog) fails without typing", async () => {
  const { seams: s, sent } = seams([PERMISSION_DIALOG], { safetyWaitMs: 0 })
  const r = await applyClaudeLiveSwitch(WID, { model: "claude-opus-4-8" }, s)
  expect(r.ok).toBe(false)
  expect(sent).toHaveLength(0)
})

test("unsafe pane that clears within the safety window proceeds", async () => {
  const { seams: s } = seams([
    PERMISSION_DIALOG, // unsafe
    IDLE_PANE,         // safe now
    IDLE_PANE,         // post C-u
    MODEL_OK,          // success
  ])
  const r = await applyClaudeLiveSwitch(WID, { model: "claude-opus-4-8" }, s)
  expect(r).toEqual({ ok: true })
})

test("draft that C-u cannot clear fails WITHOUT typing the command", async () => {
  const { seams: s, sent } = seams([
    IDLE_PANE,        // safety ok
    IDLE_WITH_DRAFT,  // post C-u: draft still there
    IDLE_WITH_DRAFT,  // post retry C-u: still there
  ])
  const r = await applyClaudeLiveSwitch(WID, { model: "claude-opus-4-8" }, s)
  expect(r.ok).toBe(false)
  // Two C-u attempts, but the /model command was never typed.
  expect(sent).toEqual([["C-u"], ["C-u"]])
})

test("timeout without confirmation cleans up with C-u and fails", async () => {
  const { seams: s, sent } = seams([
    IDLE_PANE, // safety
    IDLE_PANE, // post C-u
    IDLE_PANE, // poll: never confirms (held frame)
  ], { confirmTimeoutMs: 0 })
  const r = await applyClaudeLiveSwitch(WID, { model: "claude-opus-4-8" }, s)
  expect(r.ok).toBe(false)
  expect(sent[sent.length - 1]).toEqual(["C-u"]) // cleanup
})

test("timeout with menu still up cleans up with Escape and fails", async () => {
  const { seams: s, sent } = seams([
    IDLE_PANE,    // safety
    IDLE_PANE,    // post C-u
    EFFORT_MENU,  // poll: menu up forever (held frame)
  ], { confirmTimeoutMs: 0 })
  const r = await applyClaudeLiveSwitch(WID, { effort: "low" }, s)
  expect(r.ok).toBe(false)
  expect(sent[sent.length - 1]).toEqual(["Escape"]) // cancel the menu
})

test("null capture fails with window-gone error", async () => {
  const { seams: s } = seams([null])
  const r = await applyClaudeLiveSwitch(WID, { model: "claude-opus-4-8" }, s)
  expect(r.ok).toBe(false)
  if (!r.ok) expect(r.error).toContain("window")
})

test("first part failing aborts the second (no effort typing after model failure)", async () => {
  const { seams: s, sent } = seams([
    IDLE_PANE, // model: safety
    IDLE_PANE, // model: post C-u
    IDLE_PANE, // model: never confirms
  ], { confirmTimeoutMs: 0 })
  const r = await applyClaudeLiveSwitch(WID, { model: "claude-opus-4-8", effort: "low" }, s)
  expect(r.ok).toBe(false)
  expect(sent.some((k) => k[1] === "/effort low")).toBe(false)
})
```

- [ ] **Step 2.2: Run to verify failure**

Run: `bun test src/core/agents/claude/live-switch.test.ts`
Expected: FAIL — module doesn't exist.

- [ ] **Step 2.3: Implement** — create `src/core/agents/claude/live-switch.ts`:

```ts
import { sendKeysToWindowId, capturePaneById } from "../../session-manager/tmux"
import { makeLogger } from "../../../shared/log"

const log = makeLogger("claude-live-switch")

export const POLL_INTERVAL_MS = 500
export const CONFIRM_TIMEOUT_MS = 10_000
export const SAFETY_WAIT_MS = 2_000
// Pause between typing the command and Enter so the slash-autocomplete settles
// (verified cadence against 2.1.206; instant Enter can race the popup).
export const TYPE_TO_ENTER_DELAY_MS = 500
export const MENU_RETRY_MS = 1_000

export const MODEL_SUCCESS_MARKER = "Set model to"
export const EFFORT_SUCCESS_MARKER = "Set effort level to"
export const EFFORT_CONFIRM_MARKER = "Change effort level?"
// TUI dialog states we must never type into. The select-menu pointer regex
// catches any Ink menu ("❯ 1. …" indented), incl. ones we don't know about.
const UNSAFE_MARKERS = ["Enter to confirm", "Bypass Permissions mode", "Resume from summary"]
const MENU_POINTER_RE = /^\s+❯ \d+\./m
// Composer prompt renders unindented at the bottom of the pane. Menus indent
// their pointer, so /^❯/ can't false-positive on them; old composer echoes in
// scrollback can't either because safety only looks at the capture TAIL.
const COMPOSER_RE = /^❯/m
const EMPTY_COMPOSER_RE = /^❯\s*$/m
const TAIL_LINES = 15

export type LiveSwitchTarget = { model?: string; effort?: string }

export type LiveSwitchSeams = {
  pollIntervalMs?: number
  confirmTimeoutMs?: number
  safetyWaitMs?: number
  typeDelayMs?: number
  menuRetryMs?: number
  sendKeysFn?: (windowId: string, keys: string[]) => Promise<void>
  capturePane?: (windowId: string) => Promise<string | null>
}

type Result = { ok: true } | { ok: false; error: string }

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))

function tail(text: string): string {
  const lines = text.split("\n")
  return lines.slice(Math.max(0, lines.length - TAIL_LINES)).join("\n")
}

function paneIsSafe(text: string): boolean {
  const t = tail(text)
  if (UNSAFE_MARKERS.some((m) => t.includes(m))) return false
  if (MENU_POINTER_RE.test(t)) return false
  return COMPOSER_RE.test(t)
}

function composerEmpty(text: string): boolean {
  return EMPTY_COMPOSER_RE.test(tail(text))
}

function count(haystack: string, needle: string): number {
  let n = 0
  let i = haystack.indexOf(needle)
  while (i !== -1) { n++; i = haystack.indexOf(needle, i + needle.length) }
  return n
}

// Live model/effort switch on an IDLE claude TUI: type /model and/or /effort
// into the composer via tmux send-keys and verify the TUI's confirmation line
// by pane capture. Never restarts the process; any uncertainty is an explicit
// failure so the caller can roll back. Callers guarantee idleness (the
// pending-reapply queue drains on the idle transition), so Escape/C-u here can
// never interrupt a running turn.
export async function applyClaudeLiveSwitch(
  windowId: string,
  target: LiveSwitchTarget,
  seams?: LiveSwitchSeams,
): Promise<Result> {
  const parts: { command: string; marker: string; confirmMenu: boolean; label: string }[] = []
  if (target.model) parts.push({ command: `/model ${target.model}`, marker: MODEL_SUCCESS_MARKER, confirmMenu: false, label: "model" })
  if (target.effort) parts.push({ command: `/effort ${target.effort}`, marker: EFFORT_SUCCESS_MARKER, confirmMenu: true, label: "effort" })
  if (parts.length === 0) return { ok: true }

  for (const part of parts) {
    const r = await typeAndVerify(windowId, part, seams)
    if (!r.ok) {
      log.warn("live_switch_failed", { windowId, part: part.label, err: r.error })
      return r
    }
    log.info("live_switch_applied", { windowId, part: part.label })
  }
  return { ok: true }
}

async function typeAndVerify(
  windowId: string,
  part: { command: string; marker: string; confirmMenu: boolean; label: string },
  seams?: LiveSwitchSeams,
): Promise<Result> {
  const poll = seams?.pollIntervalMs ?? POLL_INTERVAL_MS
  const confirmTimeout = seams?.confirmTimeoutMs ?? CONFIRM_TIMEOUT_MS
  const safetyWait = seams?.safetyWaitMs ?? SAFETY_WAIT_MS
  const typeDelay = seams?.typeDelayMs ?? TYPE_TO_ENTER_DELAY_MS
  const menuRetry = seams?.menuRetryMs ?? MENU_RETRY_MS
  const send = seams?.sendKeysFn ?? sendKeysToWindowId
  const capture = seams?.capturePane ?? capturePaneById

  // 1. Pane safety gate: composer visible, no dialog/menu. Poll briefly — a
  //    transient repaint can hide the prompt for a frame.
  let text: string | null = null
  const safetyDeadline = Date.now() + safetyWait
  while (true) {
    text = await capture(windowId)
    if (text === null) return { ok: false, error: "session window gone (no pane to capture)" }
    if (paneIsSafe(text)) break
    if (Date.now() >= safetyDeadline) {
      return { ok: false, error: `pane not ready for ${part.label} switch (dialog or menu open)` }
    }
    await sleep(poll)
  }

  // 2. Clear any stray composer draft, and PROVE it's empty before typing —
  //    a leftover draft would turn our command into a chat message.
  const baseline = count(text, part.marker)
  for (let attempt = 0; ; attempt++) {
    await send(windowId, ["C-u"])
    const after = await capture(windowId)
    if (after === null) return { ok: false, error: "session window gone (no pane to capture)" }
    if (composerEmpty(after)) break
    if (attempt >= 1) return { ok: false, error: "composer draft could not be cleared; switch aborted" }
    await sleep(poll)
  }

  // 3. Type the command literally, let autocomplete settle, submit.
  await send(windowId, ["-l", part.command])
  await sleep(typeDelay)
  await send(windowId, ["Enter"])

  // 4. Verify by marker-count delta; confirm the effort menu if it appears.
  const deadline = Date.now() + confirmTimeout
  let lastMenuEnterAt = 0
  let menuWasUp = false
  while (true) {
    const now = Date.now()
    const cur = await capture(windowId)
    if (cur === null) return { ok: false, error: "session window gone (no pane to capture)" }
    if (count(cur, part.marker) > baseline) return { ok: true }
    menuWasUp = part.confirmMenu && tail(cur).includes(EFFORT_CONFIRM_MARKER)
    if (menuWasUp && (lastMenuEnterAt === 0 || now - lastMenuEnterAt >= menuRetry)) {
      await send(windowId, ["Enter"]) // default selection is "Yes" (verified)
      lastMenuEnterAt = now
    }
    if (now >= deadline) break
    await sleep(poll)
  }

  // 5. Timeout: clean up whatever half-state remains. Escape cancels a stuck
  //    menu (safe: session is idle, nothing to interrupt); C-u clears typed text.
  await send(windowId, [menuWasUp ? "Escape" : "C-u"])
  return { ok: false, error: `no confirmation for ${part.label} switch within ${confirmTimeout}ms` }
}
```

- [ ] **Step 2.4: Run to verify pass**

Run: `bun test src/core/agents/claude/live-switch.test.ts`
Expected: PASS (12 tests). If the "timeout" tests flake on `confirmTimeoutMs: 0` racing the first poll, the deadline check `now >= deadline` runs AFTER the marker/menu checks — first iteration always completes, so they must be deterministic.

- [ ] **Step 2.5: Commit**

```bash
git add src/core/agents/claude/live-switch.ts src/core/agents/claude/live-switch.test.ts
git commit -m "feat(claude): live model/effort switch via TUI /model + /effort (no restart)"
```

---

### Task 3: Wire into main.ts (replace the claude respawn branch)

**Files:**
- Modify: `src/main.ts` — `reapplySessionAgentConfig` (~line 2571), `applyOrDeferReapply` (~2670), `switchSessionModel` (~2689), `switchSessionReasoningLevel` (~2718), pending-reapply drain (~2975)

- [ ] **Step 3.1:** Add imports near the other claude agent imports in `src/main.ts`:

```ts
import { applyClaudeLiveSwitch } from "./core/agents/claude/live-switch"
```

and extend the existing pending-reapply import:

```ts
import { PendingReapply, shouldDeferReapply, changedSince } from "./core/session-manager/pending-reapply"
```

- [ ] **Step 3.2:** Replace the ENTIRE `if (session.agent === "claude") { ... }` branch of `reapplySessionAgentConfig` (the kill-window + `buildClaudeSpawnCommand` + `spawnSessionWindow` block, main.ts:2577-2611) with:

```ts
  if (session.agent === "claude") {
    // Live switch: type /model and/or /effort into the running TUI — never a
    // kill+respawn (user decision 2026-07-10). Failure is an explicit error;
    // callers roll the registry back.
    const wid = await widOf(session)
    if (!wid) return { ok: false, error: "session window not found" }
    const result = await applyClaudeLiveSwitch(wid, {
      model: changed?.model === false ? undefined : session.model,
      effort: changed?.effort === false ? undefined : effort,
    })
    if (!result.ok) return result
    webChannel?.broadcastToAll({
      type: "session_state",
      session: session.id,
      model: session.model,
      reasoningLevel: effort,
    })
    return { ok: true }
  }
```

and change the function signature to accept the optional partial-apply hint (codex branch ignores it — its respawn always carries the full config):

```ts
async function reapplySessionAgentConfig(sessionId: string, changed?: { model: boolean; effort: boolean }): Promise<{ ok: true } | { ok: false; error: string }> {
```

- [ ] **Step 3.3:** In `applyOrDeferReapply`, compute the delta and pass it through (replace the `const result = await reapplySessionAgentConfig(sessionId)` line):

```ts
  const s = registry.get(sessionId)
  const result = await reapplySessionAgentConfig(sessionId, s ? changedSince(olds, s) : undefined)
```

- [ ] **Step 3.4:** Force queue-until-idle for claude (user decision: never type mid-turn). In `switchSessionModel`, change the final call to:

```ts
  const applyNow = session.agent === "claude" ? false : opts?.applyNow ?? false
  return applyOrDeferReapply(sessionId, { oldModel, oldReasoningLevel }, applyNow)
```

In `switchSessionReasoningLevel`, same:

```ts
  const applyNow = session.agent === "claude" ? false : opts?.applyNow ?? false
  return applyOrDeferReapply(sessionId, { oldModel: session.model, oldReasoningLevel }, applyNow)
```

- [ ] **Step 3.5:** In the pending-reapply drain (`agentStateStore.on("change", …)` idle block, ~line 2977), pass the delta:

```ts
    const drainSession = registry.get(sessionId)
    void reapplySessionAgentConfig(sessionId, drainSession ? changedSince(olds, drainSession) : undefined).then((r) => {
```

(keep the existing rollback/notify body unchanged).

- [ ] **Step 3.6:** Check for now-dead imports in main.ts — `buildClaudeSpawnCommand`, `spawnSessionWindow`, `stopClaudeTailer`, `killWindowById`, `deleteRuntime`, `server.bind` are all still used by OTHER call sites (spawn, kill, codex reapply); verify nothing became unused:

Run: `bunx tsc --noEmit -p . 2>&1 | grep -v "node_modules" | head -20`
Expected: only the ~3 pre-existing typecheck errors (none mentioning live-switch, reapply, or unused imports). Also run: `grep -n "buildClaudeSpawnCommand" src/main.ts` — remaining uses (if zero, delete the import).

- [ ] **Step 3.7:** Run the broker test suite for the touched areas:

Run: `bun test src/core/session-manager src/core/agents/claude src/core/models 2>&1 | tail -5`
Expected: PASS (no regressions; spawn-command tests untouched and green).

- [ ] **Step 3.8: Commit**

```bash
git add src/main.ts
git commit -m "feat(broker): claude model/effort switches apply live in the TUI, never respawn"
```

---

### Task 4: Web switcher copy (no more "restarts the agent" for claude)

**Files:**
- Modify: `src/web-app/src/components/ModelSwitcher.vue` (restart copy line ~88; "Change now" block ~110-120)
- Modify: `src/web-app/src/components/EffortSwitcher.vue` (restart copy line ~89; "Change now" block ~111-118)

Both components already hold `agent` (from their fetch responses), so this is pure template work.

- [ ] **Step 4.1:** In `ModelSwitcher.vue`, gate the restart copy (codex still restarts; claude no longer does):

```html
          <p v-if="agent === 'claude'" class="text-xs text-muted-foreground mt-0.5">Applies live — no restart.</p>
          <p v-else class="text-xs text-muted-foreground mt-0.5">Changing the model restarts the agent for this session.</p>
```

and gate the "Change now (ends current turn)" button in the `pendingModel` row (keep the "Will apply after this turn" label for both):

```html
            <button
              v-if="agent !== 'claude'"
              class="text-xs font-medium px-2 py-1 rounded-md hover:bg-accent transition-colors"
              :disabled="switching !== null"
              @click="applyNow"
            >
              Change now (ends current turn)
            </button>
```

- [ ] **Step 4.2:** Same two edits in `EffortSwitcher.vue` ("Changing the thinking level restarts the agent for this session." → same v-if/v-else pair with "Applies live — no restart." for claude; gate its "Change now (ends current turn)" button with `v-if="agent !== 'claude'"`).

- [ ] **Step 4.3:** Typecheck the web app:

Run: `cd src/web-app && bunx vue-tsc --noEmit 2>&1 | tail -5; cd ../..`
Expected: only pre-existing errors, if any (digest notes ~3 broker-side; web is normally clean).

- [ ] **Step 4.4: Commit**

```bash
git add src/web-app/src/components/ModelSwitcher.vue src/web-app/src/components/EffortSwitcher.vue
git commit -m "feat(web): claude switchers read 'applies live', hide Change-now (no restart anymore)"
```

---

### Task 5: Live verification against a real Claude TUI

Proves the exact shipped module against the real CLI — the module is standalone (windowId + tmux), so no broker swap is needed.

**Files:**
- Create (scratch, not committed): `/tmp/claude-live-switch-verify.ts` — actually use the session scratchpad dir; any path outside the repo works since it imports from the worktree by absolute path.

- [ ] **Step 5.1:** Spawn a scratch claude TUI and capture its window id:

```bash
mkdir -p ~/.cache/live-switch-verify && cd ~/.cache/live-switch-verify
tmux new-session -d -P -F '#{window_id}' -s lsverify -x 200 -y 50 'claude --model claude-sonnet-5 --effort high'
sleep 6 && tmux capture-pane -t lsverify -p | tail -3
```

Expected: window id printed (e.g. `@57`), pane shows the composer `❯`.

- [ ] **Step 5.2:** Write the verify script (scratchpad) — `verify-live-switch.ts`:

```ts
import { applyClaudeLiveSwitch } from "<WORKTREE>/src/core/agents/claude/live-switch"
const wid = process.argv[2]!
const model = process.argv[3]
const effort = process.argv[4]
const r = await applyClaudeLiveSwitch(wid, { model: model || undefined, effort: effort || undefined })
console.log(JSON.stringify(r))
```

(replace `<WORKTREE>` with the absolute worktree path.)

- [ ] **Step 5.3:** Model + effort switch on a fresh session (no history → no menu):

Run: `bun run <scratchpad>/verify-live-switch.ts '<window-id>' claude-opus-4-8 low`
Expected: `{"ok":true}`; `tmux capture-pane -t lsverify -p | grep -c "Set model to\|Set effort level"` ≥ 2.

- [ ] **Step 5.4:** Exercise the confirm-menu path: send one tiny real turn (creates history), then switch effort down:

```bash
tmux send-keys -t lsverify -l 'Reply with just the word ok.' && sleep 1 && tmux send-keys -t lsverify Enter
sleep 20   # let the turn finish (idle again)
bun run <scratchpad>/verify-live-switch.ts '<window-id>' '' medium
```

Expected: `{"ok":true}` — the module confirmed the "Change effort level?" menu itself; capture shows `Set effort level to medium`.

- [ ] **Step 5.5:** Failure path: open the effort menu manually (`tmux send-keys -t lsverify -l '/effort low'`, `Enter` — menu up), then run the script for a model switch:

Run: `bun run <scratchpad>/verify-live-switch.ts '<window-id>' claude-sonnet-5`
Expected: `{"ok":false,...}` with "pane not ready" (safety gate refused the open menu) — and the TUI is NOT restarted. Then Escape the menu manually: `tmux send-keys -t lsverify Escape`.

- [ ] **Step 5.6:** Teardown:

```bash
tmux kill-session -t lsverify && rm -rf ~/.cache/live-switch-verify
```

- [ ] **Step 5.7:** Record the verification outcome in the commit that follows (Task 6) — no code change.

---

### Task 6: Full suite, docs, wrap-up

- [ ] **Step 6.1:** Full broker suite:

Run: `bun test 2>&1 | tail -5`
Expected: green except the ~2 known pre-existing failures (`no-legacy-names` false-positive on `agentmux-shim`; a `spawn-command` reply-fallback test) — verify no NEW failures vs the digest's baseline.

- [ ] **Step 6.2:** Commit any stragglers; append a dated finding to `~/.mux/domains/claudemux.md` documenting: the live-switch mechanism, the `/effort` confirm-menu + launch-pin behavior, the "saved as your default" side effect, and that `CLAUDE_CODE_EFFORT_LEVEL` must never be set on spawns.

- [ ] **Step 6.3:** Reply to the user: what shipped, what was verified live, and that deploying needs the usual broker restart permission (the branch merges via the normal Finish flow; note "Skip tests" is the correct Finish call per the known pre-existing failures).

---

## Self-review checklist (run after writing, before executing)

1. **Spec coverage:** live switch (Task 2-3) ✓; queue-until-idle preserved + applyNow ignored for claude (3.4) ✓; failure = explicit error + rollback (existing callers, unchanged) ✓; deferred-failure rollback + notify (existing drain body, unchanged) ✓; web copy (Task 4) ✓; unit tests (2.1) ✓; live verify (Task 5) ✓; side effects documented (6.2) ✓.
2. **Placeholder scan:** none — all code inline.
3. **Type consistency:** `applyClaudeLiveSwitch(windowId, {model?, effort?}, seams?)` used identically in Tasks 2, 3, 5; `changedSince(olds, current)` defined in Task 1, used in Task 3; `reapplySessionAgentConfig(sessionId, changed?)` signature matches all three call sites.
