# Waiting State + Background-Task Visibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sessions show a "waiting" state (with per-task chips) when claude's turn is over but background shells/subagents/workflows are still running, instead of showing idle.

**Architecture:** A `BgTaskDetector` taps the existing per-session claude transcript tailer to open tasks on background-launch tool-results and close them on `<task-notification>` lines (which also emit a `turn-start` wake signal). An in-memory `BackgroundTaskStore` feeds a new additive `bg_tasks` frame + `waiting`/`bgOpen` fields on `agent_state`. The 4-state hook machine is untouched; waiting is derived at the frame layer. All clients render chips + a session-list badge.

**Tech Stack:** Bun + TypeScript broker (`bun test`), Vue 3 + Pinia web app (vitest), KMP `Frames.kt` (kotlinx.serialization, `:shared:jvmTest`), Jetpack Compose, SwiftUI (build-blind on Linux, Mac later).

**Spec:** `docs/superpowers/specs/2026-07-08-waiting-state-bg-tasks-design.md`

**Repo gotchas:** run `bun install` first in a fresh worktree; full suite has ~2 known pre-existing failures (`no-legacy-names` false-positive, a `spawn-command` reply-fallback test) + 3 pre-existing `tsc` errors; gradle needs `ANDROID_HOME=/home/ahmet/Android/Sdk TMPDIR=/home/ahmet/.cache/gt GRADLE_OPTS="-Xmx2g"` and `--no-daemon`; commit trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

## File structure

**Broker (new):**
- `src/core/session-manager/background-task-store.ts` — task registry, one responsibility: hold + mutate per-session task lists, emit `change`.
- `src/core/agents/claude/bg-task-detector.ts` — pure line-fed parser: transcript line in → open/close/wake callbacks out. No store knowledge.

**Broker (modify):**
- `src/core/agents/claude/transcript-tailer.ts` — optional `onLine` tap.
- `src/core/session-manager/agent-state-frame.ts` — `waiting` + `bgOpen` derivation.
- `src/main.ts` — instantiate store/detectors, wire tailer + broadcasts + clears (`ensureClaudeTailer` ~:373, `stopClaudeTailer` ~:392, archive ~:640, kill ~:1590, dead ~:1903, `getSessionAgentState` getter ~:1071, broadcast block ~:2931).
- `src/channels/web/index.ts` — `getSessionBgTasks` opt (~:130), snapshot key (~:738-755).
- `src/channels/web/watch-sessions-route.ts` — expose `waiting`/`bgOpen`.

**Web app:** new `src/web-app/src/stores/bgTasks.ts` + `src/web-app/src/components/BgTaskChips.vue`; modify `stores/agentState.ts`, `api/ws.ts` (:96-127), `views/ChatView.vue` (status block ~:608-641), `components/SessionRow.vue` (~:100).

**KMP:** `apps/shared/src/commonMain/kotlin/dev/supermux/proto/Frames.kt` (AgentState ~:136, Snapshot ~:120) + `apps/shared/src/commonTest/kotlin/dev/supermux/proto/ChatFramesTest.kt`.

**Android:** `apps/android/src/main/kotlin/dev/supermux/android/AppViewModel.kt` (frame when ~:184-291), new `chat/BgTaskChips.kt`, modify `chat/ChatScreen.kt`/`chat/ChatPanel.kt` (status line), session list row (locate the working-spinner composable).

**iOS:** `apps/iosApp/Supermux/Broker/BrokerSession.swift` (frame switch), new `Chat/BgTaskChipsView.swift`, modify `Chat/ChatPane.swift` (status), `Sessions/SessionsListView.swift` + `Sessions/SessionsRailView.swift` (badge).

---

### Task 1: `BackgroundTaskStore`

**Files:**
- Create: `src/core/session-manager/background-task-store.ts`
- Test: `src/core/session-manager/background-task-store.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
// src/core/session-manager/background-task-store.test.ts
import { describe, expect, test } from "bun:test"
import { BackgroundTaskStore } from "./background-task-store"

const open = (id: string, ts = 1000) => ({ id, kind: "shell" as const, label: `run ${id}`, ts })

describe("BackgroundTaskStore", () => {
  test("upsertOpen adds a running task and emits change", () => {
    const store = new BackgroundTaskStore()
    let changed = 0
    store.on("change", () => changed++)
    store.upsertOpen("s1", open("b1"))
    expect(store.get("s1")).toEqual([
      { id: "b1", kind: "shell", label: "run b1", startedAt: 1000, status: "running" },
    ])
    expect(store.openCount("s1")).toBe(1)
    expect(changed).toBe(1)
  })

  test("upsertOpen is idempotent by id (replay-safe)", () => {
    const store = new BackgroundTaskStore()
    store.upsertOpen("s1", open("b1"))
    let changed = 0
    store.on("change", () => changed++)
    store.upsertOpen("s1", open("b1", 2000))
    expect(store.get("s1")).toHaveLength(1)
    expect(store.get("s1")[0]!.startedAt).toBe(1000)   // first sighting wins
    expect(changed).toBe(0)                             // no spurious broadcast
  })

  test("close marks completed/failed with summary and endedAt", () => {
    const store = new BackgroundTaskStore()
    store.upsertOpen("s1", open("b1"))
    store.close("s1", { id: "b1", status: "failed", summary: "exit 1", ts: 5000 })
    expect(store.get("s1")[0]).toMatchObject({ status: "failed", summary: "exit 1", endedAt: 5000 })
    expect(store.openCount("s1")).toBe(0)
  })

  test("close for an unseen id creates it already-closed (kind from prefix)", () => {
    const store = new BackgroundTaskStore()
    store.close("s1", { id: "a9", status: "completed", ts: 5000 })
    expect(store.get("s1")[0]).toMatchObject({ id: "a9", kind: "agent", status: "completed", label: "a9" })
  })

  test("close on already-closed id is a no-op (no re-emit)", () => {
    const store = new BackgroundTaskStore()
    store.upsertOpen("s1", open("b1"))
    store.close("s1", { id: "b1", status: "completed", ts: 5000 })
    let changed = 0
    store.on("change", () => changed++)
    store.close("s1", { id: "b1", status: "completed", ts: 6000 })
    expect(changed).toBe(0)
  })

  test("keeps all open + last 20 closed", () => {
    const store = new BackgroundTaskStore()
    for (let i = 0; i < 30; i++) {
      store.upsertOpen("s1", open(`b${i}`, i))
      store.close("s1", { id: `b${i}`, status: "completed", ts: i + 100 })
    }
    store.upsertOpen("s1", open("live", 999))
    const tasks = store.get("s1")
    expect(tasks.filter((t) => t.status !== "running")).toHaveLength(20)
    expect(tasks.find((t) => t.id === "live")).toBeDefined()
    expect(tasks.find((t) => t.id === "b0")).toBeUndefined() // oldest closed evicted
  })

  test("clear drops the session and emits change only if something existed", () => {
    const store = new BackgroundTaskStore()
    store.upsertOpen("s1", open("b1"))
    let changed = 0
    store.on("change", () => changed++)
    store.clear("s1")
    expect(store.get("s1")).toEqual([])
    expect(changed).toBe(1)
    store.clear("s1")
    expect(changed).toBe(1)   // clearing nothing does not emit
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bun test src/core/session-manager/background-task-store.test.ts`
Expected: FAIL — cannot resolve `./background-task-store`

- [ ] **Step 3: Implement the store**

```ts
// src/core/session-manager/background-task-store.ts
import { EventEmitter } from "events"

export type BgTaskKind = "shell" | "agent" | "workflow" | "task"
export type BgTaskStatus = "running" | "completed" | "failed"

export interface BackgroundTask {
  id: string
  kind: BgTaskKind
  label: string
  startedAt: number      // epoch ms (transcript timestamp)
  status: BgTaskStatus
  endedAt?: number
  summary?: string
  callId?: string        // launching tool_use id
}

export interface BgTaskOpen { id: string; kind: BgTaskKind; label: string; ts: number; callId?: string }
export interface BgTaskClose { id: string; status: "completed" | "failed"; summary?: string; ts: number }

export function kindFromId(id: string): BgTaskKind {
  if (id.startsWith("wf_")) return "workflow"
  if (id.startsWith("a")) return "agent"
  if (id.startsWith("b")) return "shell"
  return "task"
}

const CLOSED_KEEP = 20

// Per-session background-task registry. In-memory and ephemeral by design —
// same lifecycle as ActivityStore (dropped on broker restart / session exit).
export class BackgroundTaskStore extends EventEmitter {
  private readonly bySession = new Map<string, BackgroundTask[]>()

  upsertOpen(sessionId: string, t: BgTaskOpen): void {
    const list = this.bySession.get(sessionId) ?? []
    if (list.some((x) => x.id === t.id)) return   // replayed start marker
    list.push({ id: t.id, kind: t.kind, label: t.label, startedAt: t.ts, status: "running", ...(t.callId ? { callId: t.callId } : {}) })
    this.bySession.set(sessionId, this.evict(list))
    this.emit("change", sessionId)
  }

  close(sessionId: string, c: BgTaskClose): void {
    const list = this.bySession.get(sessionId) ?? []
    const existing = list.find((x) => x.id === c.id)
    if (existing) {
      if (existing.status !== "running") return   // replayed notification
      existing.status = c.status
      existing.endedAt = c.ts
      if (c.summary) existing.summary = c.summary
    } else {
      // Notification for a task we never saw start (missed tail) — still show the ✓/✕ moment.
      list.push({ id: c.id, kind: kindFromId(c.id), label: c.id, startedAt: c.ts, status: c.status, endedAt: c.ts, ...(c.summary ? { summary: c.summary } : {}) })
    }
    this.bySession.set(sessionId, this.evict(list))
    this.emit("change", sessionId)
  }

  get(sessionId: string): BackgroundTask[] {
    return this.bySession.get(sessionId)?.slice() ?? []
  }

  openCount(sessionId: string): number {
    return this.bySession.get(sessionId)?.filter((t) => t.status === "running").length ?? 0
  }

  clear(sessionId: string): void {
    if (!this.bySession.delete(sessionId)) return
    this.emit("change", sessionId)
  }

  private evict(list: BackgroundTask[]): BackgroundTask[] {
    const closed = list.filter((t) => t.status !== "running")
    if (closed.length <= CLOSED_KEEP) return list
    const drop = new Set(closed.slice(0, closed.length - CLOSED_KEEP).map((t) => t.id))
    return list.filter((t) => !drop.has(t.id))
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bun test src/core/session-manager/background-task-store.test.ts`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add src/core/session-manager/background-task-store.ts src/core/session-manager/background-task-store.test.ts
git commit -m "feat(broker): BackgroundTaskStore — per-session background-task registry

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: `BgTaskDetector`

**Files:**
- Create: `src/core/agents/claude/bg-task-detector.ts`
- Test: `src/core/agents/claude/bg-task-detector.test.ts`

Fixtures below are faithful reductions of real transcript lines captured 2026-07-08 (session `797faedf`, this machine).

- [ ] **Step 1: Write the failing test**

```ts
// src/core/agents/claude/bg-task-detector.test.ts
import { describe, expect, test } from "bun:test"
import { BgTaskDetector } from "./bg-task-detector"
import type { BgTaskOpen, BgTaskClose } from "../../session-manager/background-task-store"

function harness() {
  const opens: BgTaskOpen[] = []
  const closes: BgTaskClose[] = []
  let wakes = 0
  const d = new BgTaskDetector({
    onOpen: (t) => opens.push(t),
    onClose: (c) => closes.push(c),
    onWake: () => wakes++,
  })
  return { d, opens, closes, wakes: () => wakes }
}

const TS = "2026-07-08T05:00:00.000Z"
const TS_MS = Date.parse(TS)

const bashToolUse = JSON.stringify({
  type: "assistant", timestamp: TS,
  message: { content: [{ type: "tool_use", id: "toolu_01AAA", name: "Bash",
    input: { command: "gradlew :android:compileDebugKotlin\nmore", description: "Recompile Android Kotlin", run_in_background: true } }] },
})
const bashResult = JSON.stringify({
  type: "user", timestamp: TS,
  message: { content: [{ type: "tool_result", tool_use_id: "toolu_01AAA",
    content: "Command running in background with ID: bxcdg51aa. Output is being written to: /tmp/x/tasks/bxcdg51aa.output" }] },
})
const agentToolUse = JSON.stringify({
  type: "assistant", timestamp: TS,
  message: { content: [{ type: "tool_use", id: "toolu_01BBB", name: "Agent",
    input: { description: "Research WebKit quirks", prompt: "Find out about..." } }] },
})
const agentResult = JSON.stringify({
  type: "user", timestamp: TS,
  message: { content: [{ type: "tool_result", tool_use_id: "toolu_01BBB",
    content: [{ type: "text", text: "Async agent launched successfully.\nagentId: a2bee0eded79e862d (internal ID - do not mention to user)" }] }] },
})
const failNotification = JSON.stringify({
  type: "user", timestamp: TS,
  message: { content: "<task-notification>\n<task-id>bxcdg51aa</task-id>\n<tool-use-id>toolu_01AAA</tool-use-id>\n<output-file>/tmp/x/tasks/bxcdg51aa.output</output-file>\n<status>failed</status>\n<summary>Background command \"Recompile Android Kotlin\" failed with exit code 1</summary>\n</task-notification>" },
})
const doneNotification = JSON.stringify({
  type: "user", timestamp: TS,
  message: { content: "<task-notification>\n<task-id>a2bee0eded79e862d</task-id>\n<tool-use-id>toolu_01BBB</tool-use-id>\n<output-file>/tmp/x/tasks/a2bee0eded79e862d.output</output-file>\n<status>completed</status>\n<summary>Agent finished</summary>\n</task-notification>" },
})

describe("BgTaskDetector", () => {
  test("opens a shell task with label from the paired tool_use description", () => {
    const { d, opens } = harness()
    d.feedLine(bashToolUse)
    d.feedLine(bashResult)
    expect(opens).toEqual([{ id: "bxcdg51aa", kind: "shell", label: "Recompile Android Kotlin", ts: TS_MS, callId: "toolu_01AAA" }])
  })

  test("falls back to first command line when Bash has no description", () => {
    const { d, opens } = harness()
    const noDesc = bashToolUse.replace(`"description": "Recompile Android Kotlin", `, "").replace(`"description":"Recompile Android Kotlin",`, "")
    d.feedLine(noDesc)
    d.feedLine(bashResult)
    expect(opens[0]!.label).toBe("gradlew :android:compileDebugKotlin")
  })

  test("opens an agent task from the Async-agent-launched result", () => {
    const { d, opens } = harness()
    d.feedLine(agentToolUse)
    d.feedLine(agentResult)
    expect(opens).toEqual([{ id: "a2bee0eded79e862d", kind: "agent", label: "Research WebKit quirks", ts: TS_MS, callId: "toolu_01BBB" }])
  })

  test("unmatched tool_result without pending tool_use still opens with id as label", () => {
    const { d, opens } = harness()
    d.feedLine(bashResult)
    expect(opens).toEqual([{ id: "bxcdg51aa", kind: "shell", label: "bxcdg51aa", ts: TS_MS, callId: "toolu_01AAA" }])
  })

  test("task-notification closes with status + summary and emits wake", () => {
    const { d, closes, wakes } = harness()
    d.feedLine(failNotification)
    d.feedLine(doneNotification)
    expect(closes).toEqual([
      { id: "bxcdg51aa", status: "failed", summary: 'Background command "Recompile Android Kotlin" failed with exit code 1', ts: TS_MS },
      { id: "a2bee0eded79e862d", status: "completed", summary: "Agent finished", ts: TS_MS },
    ])
    expect(wakes()).toBe(2)
  })

  test("garbage and irrelevant lines are ignored", () => {
    const { d, opens, closes, wakes } = harness()
    d.feedLine("not json")
    d.feedLine(JSON.stringify({ type: "assistant", message: { content: [{ type: "text", text: "hello" }] } }))
    d.feedLine(JSON.stringify({ type: "user", message: { content: "plain reply" } }))
    expect(opens).toHaveLength(0)
    expect(closes).toHaveLength(0)
    expect(wakes()).toBe(0)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bun test src/core/agents/claude/bg-task-detector.test.ts`
Expected: FAIL — cannot resolve `./bg-task-detector`

- [ ] **Step 3: Implement the detector**

```ts
// src/core/agents/claude/bg-task-detector.ts
// Stateful per-session detector for claude background-task lifecycle markers.
// Fed raw transcript lines by the tailer (alongside parseTranscriptLine, which
// stays pure/stateless). Emits open/close/wake — no store or broker knowledge.
import { kindFromId, type BgTaskClose, type BgTaskOpen } from "../../session-manager/background-task-store"

export interface BgTaskDetectorOpts {
  onOpen: (t: BgTaskOpen) => void
  onClose: (c: BgTaskClose) => void
  // A task-notification line is delivery evidence: the harness is waking claude.
  onWake?: (ts: number) => void
}

const PENDING_CAP = 50
const LABEL_MAX = 80
const LAUNCH_TOOLS = new Set(["Bash", "Agent", "Task", "Workflow"])

const SHELL_START_RE = /Command running in background with ID:\s*([A-Za-z0-9_-]+)/
const AGENT_START_RE = /Async agent launched[\s\S]{0,200}?agentId:\s*([A-Za-z0-9_.-]+)/
const WORKFLOW_START_RE = /\b(wf_[a-z0-9-]{6,})\b/
const NOTIFICATION_RE = /<task-notification>([\s\S]*?)<\/task-notification>/g

function firstLine(s: string): string {
  for (const ln of s.split("\n")) { const t = ln.trim(); if (t) return t }
  return s.trim()
}

function clip(s: string): string {
  return s.length <= LABEL_MAX ? s : s.slice(0, LABEL_MAX - 1) + "…"
}

function labelFor(name: string, input: Record<string, unknown>): string {
  const desc = typeof input.description === "string" ? input.description : ""
  if (desc) return clip(firstLine(desc))
  const cmd = typeof input.command === "string" ? input.command : ""
  if (cmd) return clip(firstLine(cmd))
  const prompt = typeof input.prompt === "string" ? input.prompt : ""
  if (prompt) return clip(firstLine(prompt))
  const wf = typeof input.name === "string" ? input.name : ""
  if (wf) return clip(firstLine(wf))
  return name.toLowerCase()
}

function resultText(content: unknown): string {
  if (typeof content === "string") return content
  if (Array.isArray(content)) {
    return content.map((b) => (b && typeof b === "object" && typeof (b as any).text === "string" ? (b as any).text : "")).join("")
  }
  return ""
}

function tag(body: string, name: string): string {
  const m = body.match(new RegExp(`<${name}>([\\s\\S]*?)</${name}>`))
  return m?.[1]?.trim() ?? ""
}

export class BgTaskDetector {
  private readonly pending = new Map<string, { tool: string; label: string }>()

  constructor(private readonly opts: BgTaskDetectorOpts) {}

  feedLine(line: string): void {
    let obj: any
    try { obj = JSON.parse(line) } catch { return }
    if (!obj || (obj.type !== "assistant" && obj.type !== "user")) return
    const ts = typeof obj.timestamp === "string" ? (Date.parse(obj.timestamp) || Date.now()) : Date.now()
    const content = obj?.message?.content

    if (typeof content === "string") {
      this.scanNotifications(content, ts)
      return
    }
    if (!Array.isArray(content)) return

    for (const b of content) {
      if (!b || typeof b !== "object") continue
      if (b.type === "tool_use" && typeof b.name === "string" && LAUNCH_TOOLS.has(b.name) && typeof b.id === "string") {
        const input = b.input && typeof b.input === "object" ? b.input as Record<string, unknown> : {}
        this.pending.set(b.id, { tool: b.name, label: labelFor(b.name, input) })
        if (this.pending.size > PENDING_CAP) {
          const oldest = this.pending.keys().next().value
          if (oldest !== undefined) this.pending.delete(oldest)
        }
        continue
      }
      if (b.type === "tool_result") {
        const text = resultText(b.content)
        if (!text) continue
        const callId = typeof b.tool_use_id === "string" ? b.tool_use_id : undefined
        const shell = text.match(SHELL_START_RE)
        const agent = text.match(AGENT_START_RE)
        const wf = !shell && !agent && /[Ww]orkflow/.test(text) ? text.match(WORKFLOW_START_RE) : null
        const id = shell?.[1] ?? agent?.[1] ?? wf?.[1]
        if (!id) continue
        const kind = shell ? "shell" as const : agent ? "agent" as const : "workflow" as const
        const label = (callId ? this.pending.get(callId)?.label : undefined) ?? id
        if (callId) this.pending.delete(callId)
        this.opts.onOpen({ id, kind: kind === "workflow" ? kindFromId(id) : kind, label, ts, ...(callId ? { callId } : {}) })
        // string-content notifications never share a line with tool_results; array
        // user messages can in principle carry both, so also scan the text.
        this.scanNotifications(text, ts, /* wake */ false)
      }
    }
  }

  private scanNotifications(text: string, ts: number, wake = true): void {
    if (!text.includes("<task-notification>")) return
    let matched = false
    for (const m of text.matchAll(NOTIFICATION_RE)) {
      const body = m[1] ?? ""
      const id = tag(body, "task-id")
      if (!id) continue
      matched = true
      const status = tag(body, "status") === "completed" ? "completed" as const : "failed" as const
      const summary = tag(body, "summary")
      this.opts.onClose({ id, status, ...(summary ? { summary } : {}), ts })
    }
    if (matched && wake) this.opts.onWake?.(ts)
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bun test src/core/agents/claude/bg-task-detector.test.ts`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add src/core/agents/claude/bg-task-detector.ts src/core/agents/claude/bg-task-detector.test.ts
git commit -m "feat(broker): BgTaskDetector — background-task lifecycle from claude transcripts

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Tailer `onLine` tap

**Files:**
- Modify: `src/core/agents/claude/transcript-tailer.ts`
- Test: `src/core/agents/claude/transcript-tailer.test.ts` (append)

- [ ] **Step 1: Write the failing test** — append to the existing describe block in `transcript-tailer.test.ts` (read the file first; follow its construction pattern):

```ts
test("onLine receives every complete raw line before parsing", () => {
  const lines: string[] = []
  const tailer = new TranscriptTailer({ path: "/nonexistent", onEvent: () => {}, onLine: (l) => lines.push(l) })
  tailer.ingest('{"type":"user","message":{"content":"<task-notification>x</task-notification>"}}\npartial')
  expect(lines).toEqual(['{"type":"user","message":{"content":"<task-notification>x</task-notification>"}}'])
  tailer.ingest(" tail\n")
  expect(lines).toHaveLength(2)
})
```

- [ ] **Step 2: Run to verify it fails** — `bun test src/core/agents/claude/transcript-tailer.test.ts` → type error / lines empty.

- [ ] **Step 3: Implement** — in `transcript-tailer.ts`: add `onLine?: (line: string) => void` to `TranscriptTailerOpts`; store `private readonly onLine?: (line: string) => void` (`this.onLine = opts.onLine`); in `ingest()` inside the while-loop, before the parse loop:

```ts
      this.onLine?.(line)
      for (const ev of parseTranscriptLine(line)) this.onEvent(ev)
```

- [ ] **Step 4: Run to verify it passes** — `bun test src/core/agents/claude/transcript-tailer.test.ts` → PASS.

- [ ] **Step 5: Commit**

```bash
git add src/core/agents/claude/transcript-tailer.ts src/core/agents/claude/transcript-tailer.test.ts
git commit -m "feat(broker): transcript tailer exposes raw-line tap for detectors

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Frame derivation — `waiting` + `bgOpen`

**Files:**
- Modify: `src/core/session-manager/agent-state-frame.ts`
- Test: `src/core/session-manager/agent-state-frame.test.ts` (append)

- [ ] **Step 1: Write the failing tests** (append; match the file's existing style after reading it):

```ts
test("idle with open bg tasks derives waiting", () => {
  const f = toAgentStateFrame("s1", { phase: "idle", since: 5 }, 2)
  expect(f.waiting).toBe(true)
  expect(f.bgOpen).toBe(2)
  expect(f.state).toBe("idle")      // legacy clients keep seeing idle
  expect(f.working).toBe(false)
  expect(f.phase).toBe("idle")
})

test("working/dead never derive waiting even with open tasks", () => {
  expect(toAgentStateFrame("s1", { phase: "running", tool: "Bash", since: 5 }, 3).waiting).toBe(false)
  expect(toAgentStateFrame("s1", { phase: "dead", since: 5 }, 3).waiting).toBe(false)
})

test("bgOpen defaults to 0 when omitted", () => {
  const f = toAgentStateFrame("s1", { phase: "idle", since: 5 })
  expect(f.waiting).toBe(false)
  expect(f.bgOpen).toBe(0)
})
```

- [ ] **Step 2: Run to verify failure** — `bun test src/core/session-manager/agent-state-frame.test.ts` → FAIL (unknown fields / arity).

- [ ] **Step 3: Implement** — in `agent-state-frame.ts`, add to `AgentStateFrame`:

```ts
  waiting: boolean   // idle but background tasks still open (turn will resume)
  bgOpen: number     // open background-task count (session-list badges)
```

and change the builder:

```ts
export function toAgentStateFrame(session: string, st: AgentState, bgOpen = 0): AgentStateFrame {
  const working = st.phase === "thinking" || st.phase === "running"
  const state = st.phase === "idle" ? "idle" : st.phase === "dead" ? "dead" : "working"
  const detail = working ? (st.phase as "thinking" | "running") : null
  const phase = st.phase === "dead" ? "stalled" : st.phase
  const waiting = st.phase === "idle" && bgOpen > 0
  return { type: "agent_state", session, state, working, detail, tool: st.tool, since: st.since, workingSince: st.workingSince, waiting, bgOpen, phase }
}
```

- [ ] **Step 4: Run to verify pass** — `bun test src/core/session-manager/agent-state-frame.test.ts` → PASS. Also `bun test src/core/session-manager/` (callers with 2 args unaffected).

- [ ] **Step 5: Commit**

```bash
git add src/core/session-manager/agent-state-frame.ts src/core/session-manager/agent-state-frame.test.ts
git commit -m "feat(broker): agent_state frame derives waiting from open background tasks

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Broker wiring + web channel + watch route

**Files:**
- Modify: `src/main.ts` (anchors below), `src/channels/web/index.ts`, `src/channels/web/watch-sessions-route.ts`
- Test: `src/channels/web/watch-sessions-route.test.ts` (append), plus a wiring smoke via existing channel tests

- [ ] **Step 1: main.ts — store + detectors.** Near `const activityStore = new ActivityStore()` (~:334):

```ts
const bgTaskStore = new BackgroundTaskStore()
const bgDetectors = new Map<string, BgTaskDetector>()   // keyed by session UUID
```

Imports: `import { BackgroundTaskStore } from "./core/session-manager/background-task-store"` and `import { BgTaskDetector } from "./core/agents/claude/bg-task-detector"`.

- [ ] **Step 2: main.ts — detector per tailer.** In `ensureClaudeTailer` (~:373), before `const tailer = new TranscriptTailer({`:

```ts
  const detector = new BgTaskDetector({
    onOpen: (t) => bgTaskStore.upsertOpen(sessionUuid, t),
    onClose: (c) => bgTaskStore.close(sessionUuid, c),
    // Notification delivery = the harness waking claude; reflect it immediately
    // (same transcript-as-signal channel as interrupt detection).
    onWake: () => agentStateStore.applyEvent(sessionUuid, "turn-start"),
  })
  bgDetectors.set(sessionUuid, detector)
```

and add to the tailer opts: `onLine: (line) => bgDetectors.get(sessionUuid)?.feedLine(line),`

In `stopClaudeTailer` (~:392) add:

```ts
  bgDetectors.delete(sessionUuid)
  bgTaskStore.clear(sessionUuid)
```

- [ ] **Step 3: main.ts — clears.** At the archive path (~:640, next to `agentStateStore.clear(id)`) add `bgTaskStore.clear(id)`. At the kill path (~:1590, next to `agentStateStore.clear(s.id)`) add `bgTaskStore.clear(s.id)`. At the liveness dead path (~:1903, after `applyEvent(session_id, "dead")`) add `bgTaskStore.clear(session_id)`.

- [ ] **Step 4: main.ts — broadcasts.** Modify the existing `agentStateStore.on("change")` broadcast (~:2934) to pass the count:

```ts
  webChannel?.broadcastToAll(toAgentStateFrame(sessionId, state, bgTaskStore.openCount(sessionId)))
```

Add below the activityStore broadcast block:

```ts
bgTaskStore.on("change", (sessionId: string) => {
  webChannel?.broadcastToAll({ type: "bg_tasks", session: sessionId, tasks: bgTaskStore.get(sessionId) })
  // waiting/bgOpen live on agent_state — re-derive whenever tasks move.
  webChannel?.broadcastToAll(toAgentStateFrame(sessionId, agentStateStore.get(sessionId), bgTaskStore.openCount(sessionId)))
})
```

- [ ] **Step 5: main.ts — snapshot getters.** The `getSessionAgentState` opt (~:1071) builds a frame — pass `bgTaskStore.openCount(s?.id ?? id)` as the third arg. Next to it add:

```ts
    getSessionBgTasks: (id) => {
      const s = registry.get(id)
      return s ? bgTaskStore.get(s.id) : []
    },
```

- [ ] **Step 6: web channel.** In `src/channels/web/index.ts` add to `WebChannelOpts` (near `getSessionActivity`): `getSessionBgTasks?: (name: string) => unknown[]`. In the `subscribe` handler (~:738) add `const bgTasks: Record<string, unknown[]> = {}`, inside the loop `bgTasks[sessionKey] = this.opts.getSessionBgTasks?.(sessionKey) ?? []`, and include `bgTasks` in the snapshot send (~:755).

- [ ] **Step 7: watch route.** Read `src/channels/web/watch-sessions-route.ts`; where each session's agent state is serialized (it consumes the same `getSessionAgentState` payload), the new `waiting`/`bgOpen` fields flow through automatically if it spreads the frame — verify and, if it cherry-picks fields, add both. Append a test to `watch-sessions-route.test.ts` asserting a waiting session serializes `waiting: true, bgOpen: 1`.

- [ ] **Step 8: Run broker suites**

Run: `bun test src/core src/channels 2>&1 | tail -5`
Expected: green apart from the 2 known pre-existing failures (`no-legacy-names`, `spawn-command` reply-fallback). `bunx tsc --noEmit 2>&1 | tail -5` — only the 3 known pre-existing errors.

- [ ] **Step 9: Commit**

```bash
git add src/main.ts src/channels/web/index.ts src/channels/web/watch-sessions-route.ts src/channels/web/watch-sessions-route.test.ts
git commit -m "feat(broker): wire background tasks — detector per tailer, bg_tasks frame, waiting in snapshots

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: Web app — stores + ws ingest

**Files:**
- Create: `src/web-app/src/stores/bgTasks.ts`
- Test: `src/web-app/src/stores/bgTasks.test.ts`
- Modify: `src/web-app/src/stores/agentState.ts`, `src/web-app/src/api/ws.ts`
- Test: append to `src/web-app/src/stores/agentState.test.ts`

- [ ] **Step 1: Failing store test**

```ts
// src/web-app/src/stores/bgTasks.test.ts
import { beforeEach, describe, expect, it } from "vitest"
import { createPinia, setActivePinia } from "pinia"
import { useBgTasks } from "./bgTasks"

const task = (id: string, status = "running") => ({ id, kind: "shell", label: "build", startedAt: 1000, status })

describe("bgTasks store", () => {
  beforeEach(() => setActivePinia(createPinia()))

  it("set/get round-trips and openCount counts running", () => {
    const store = useBgTasks()
    store.set("s1", [task("b1"), task("b2", "failed")] as any)
    expect(store.get("s1")).toHaveLength(2)
    expect(store.openCount("s1")).toBe(1)
    expect(store.get("unknown")).toEqual([])
  })

  it("clear drops a session", () => {
    const store = useBgTasks()
    store.set("s1", [task("b1")] as any)
    store.clear("s1")
    expect(store.get("s1")).toEqual([])
  })
})
```

- [ ] **Step 2: Run to fail** — `cd src/web-app && bun run test -- --run stores/bgTasks` → module not found.

- [ ] **Step 3: Implement store**

```ts
// src/web-app/src/stores/bgTasks.ts
import { defineStore } from "pinia"
import { ref } from "vue"

export interface BgTask {
  id: string
  kind: "shell" | "agent" | "workflow" | "task"
  label: string
  startedAt: number
  status: "running" | "completed" | "failed"
  endedAt?: number
  summary?: string
}

export const useBgTasks = defineStore("bgTasks", () => {
  const bySession = ref<Record<string, BgTask[]>>({})

  function set(session: string, tasks: BgTask[] | undefined) {
    if (Array.isArray(tasks)) bySession.value[session] = tasks
  }
  function get(session: string): BgTask[] {
    return bySession.value[session] ?? []
  }
  function openCount(session: string): number {
    return get(session).filter((t) => t.status === "running").length
  }
  function clear(session: string) {
    delete bySession.value[session]
  }

  return { bySession, set, get, openCount, clear }
})
```

- [ ] **Step 4: agentState fields.** In `stores/agentState.ts` add to `AgentStateEntry`: `waiting?: boolean` and `bgOpen?: number`. Append test in `agentState.test.ts`: setting an entry with `waiting: true` preserves it (follow existing test style).

- [ ] **Step 5: ws ingest.** In `api/ws.ts`: import + instantiate `useBgTasks` where the other stores are created; snapshot branch (~:102) add `if (frame.bgTasks) for (const [s, list] of Object.entries(frame.bgTasks)) bgTasks.set(s, list as any)`; agent_state line (~:127) add `waiting: frame.waiting, bgOpen: frame.bgOpen` to the object literal; new branch after activity_append: `else if (frame.type === "bg_tasks") bgTasks.set(frame.session, frame.tasks)`; in the `session_removed` branch add `bgTasks.clear(frame.id)`.

- [ ] **Step 6: Run web tests** — `cd src/web-app && bun run test -- --run` → green; `bun run typecheck` (or `bunx vue-tsc --noEmit`) → only pre-existing errors.

- [ ] **Step 7: Commit**

```bash
git add src/web-app/src/stores/bgTasks.ts src/web-app/src/stores/bgTasks.test.ts src/web-app/src/stores/agentState.ts src/web-app/src/stores/agentState.test.ts src/web-app/src/api/ws.ts
git commit -m "feat(web): ingest bg_tasks frames + waiting agent state

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: Web app — chips, status line, list badge

**Files:**
- Create: `src/web-app/src/components/BgTaskChips.vue`
- Modify: `src/web-app/src/views/ChatView.vue` (script ~:87-95 area + template ~:608-641), `src/web-app/src/components/SessionRow.vue` (~:100)

- [ ] **Step 1: Chips component** (visibility rule from the spec: closed chips show only while claude reacts — i.e. while any task is still open or the agent is working):

```vue
<!-- src/web-app/src/components/BgTaskChips.vue -->
<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue"
import { HourglassIcon, CheckIcon, XIcon } from "lucide-vue-next"
import { useBgTasks, type BgTask } from "@/stores/bgTasks"
import { useAgentState } from "@/stores/agentState"

const props = defineProps<{ session: string }>()
const bgTasks = useBgTasks()
const agentState = useAgentState()

const now = ref(Date.now())
let timer: ReturnType<typeof setInterval> | undefined
onMounted(() => { timer = setInterval(() => { now.value = Date.now() }, 1000) })
onUnmounted(() => { if (timer) clearInterval(timer) })

const visible = computed<BgTask[]>(() => {
  const tasks = bgTasks.get(props.session)
  const open = tasks.filter((t) => t.status === "running")
  if (open.length > 0 || agentState.get(props.session).working) return tasks
  return []   // idle with nothing open → closed chips have had their moment
})

function elapsed(t: BgTask): string {
  const ms = (t.endedAt ?? now.value) - t.startedAt
  const s = Math.max(0, Math.floor(ms / 1000))
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  return m < 60 ? `${m}m ${s % 60}s` : `${Math.floor(m / 60)}h ${m % 60}m`
}
</script>

<template>
  <div v-if="visible.length" class="flex flex-wrap gap-1.5 px-1 py-1 ml-2" data-testid="bg-task-chips">
    <span
      v-for="t in visible" :key="t.id"
      class="inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 font-mono text-[11px]"
      :class="t.status === 'failed'
        ? 'border-destructive/40 bg-destructive/10 text-destructive'
        : t.status === 'completed'
          ? 'border-border bg-muted/40 text-muted-foreground'
          : 'border-border bg-muted/40 text-foreground/80'"
      :title="t.summary ?? t.label"
    >
      <HourglassIcon v-if="t.status === 'running'" class="size-3 shrink-0 text-amber-500 animate-pulse" />
      <CheckIcon v-else-if="t.status === 'completed'" class="size-3 shrink-0 text-emerald-500" />
      <XIcon v-else class="size-3 shrink-0" />
      <span class="max-w-[16rem] truncate">{{ t.label }}</span>
      <span class="opacity-60">· {{ t.status === 'running' ? elapsed(t) : t.status }}</span>
    </span>
  </div>
</template>
```

(Check `lucide-vue-next` exports `HourglassIcon` — if absent, use `TimerIcon`; ChatView's existing imports show the convention.)

- [ ] **Step 2: ChatView.** In the script: `import BgTaskChips from "@/components/BgTaskChips.vue"`. In the template, insert the chips + waiting line inside the same `<template>` that holds the Sending…/Working… blocks (~:600): chips row **above** the status lines, and a waiting branch after the working branch:

```html
              <BgTaskChips v-if="!isArchived && sessionId" :session="sessionId" />
```

(use the same session identifier variable the Working block's `liveState` derives from — read the surrounding script for its exact name)

```html
              <div
                v-else-if="!isArchived && liveState.waiting"
                class="flex items-center gap-1.5 px-1 py-0.5 text-xs text-muted-foreground ml-2"
              >
                <HourglassIcon class="size-3.5 shrink-0 text-amber-500 animate-pulse" />
                Waiting on background tasks
              </div>
```

as a new `v-else-if` chained AFTER the `liveState.working` div (so working wins while claude reacts). Bonus line in the working div: change the text node `Working…` to also show the tool: `Working…<span v-if="liveState.tool" class="opacity-60"> · {{ liveState.tool }}</span>`.

- [ ] **Step 3: SessionRow badge.** At ~:100 next to the working spinner:

```html
        <span
          v-if="bgOpen > 0"
          class="inline-flex items-center gap-0.5 font-mono text-[11px] text-amber-500"
          :class="{ 'animate-pulse': !working }"
          aria-label="background tasks"
        >⧗{{ bgOpen }}</span>
```

with `const bgOpen = computed(() => useBgTasks().openCount(props.id))` in the script (import it). Check `SidebarRail.vue` for a similar working indicator and mirror the badge there if present.

- [ ] **Step 4: Run web tests + typecheck** — `cd src/web-app && bun run test -- --run && bunx vue-tsc --noEmit` → green / pre-existing only. Manual smoke happens in Task 11.

- [ ] **Step 5: Commit**

```bash
git add src/web-app/src/components/BgTaskChips.vue src/web-app/src/views/ChatView.vue src/web-app/src/components/SessionRow.vue src/web-app/src/components/SidebarRail.vue
git commit -m "feat(web): waiting status line, background-task chips, session-list badge

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 8: KMP frames + parity tests

**Files:**
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/proto/Frames.kt`
- Test: `apps/shared/src/commonTest/kotlin/dev/supermux/proto/ChatFramesTest.kt` (append)

- [ ] **Step 1: Failing tests** (append; follow the file's existing decode-helper style):

```kotlin
@Test
fun decodesBgTasksFrame() {
    val json = """{"type":"bg_tasks","session":"s1","tasks":[{"id":"b1","kind":"shell","label":"gradle build","startedAt":1000,"status":"running"},{"id":"a2","kind":"agent","label":"research","startedAt":2000,"status":"failed","endedAt":3000,"summary":"exit 1"}]}"""
    val frame = decodeFrame(json) as ServerFrame.BgTasks
    assertEquals("s1", frame.session)
    assertEquals(2, frame.tasks.size)
    assertEquals("running", frame.tasks[0].status)
    assertEquals("exit 1", frame.tasks[1].summary)
}

@Test
fun agentStateDecodesWaitingFields() {
    val json = """{"type":"agent_state","session":"s1","phase":"idle","state":"idle","working":false,"waiting":true,"bgOpen":2,"since":5}"""
    val frame = decodeFrame(json) as ServerFrame.AgentState
    assertEquals(true, frame.waiting)
    assertEquals(2, frame.bgOpen)
}

@Test
fun agentStateWithoutWaitingFieldsDefaultsFalseZero() {
    val json = """{"type":"agent_state","session":"s1","phase":"idle","state":"idle","working":false,"since":5}"""
    val frame = decodeFrame(json) as ServerFrame.AgentState
    assertEquals(false, frame.waiting)
    assertEquals(0, frame.bgOpen)
}

@Test
fun snapshotDecodesBgTasksMap() {
    val json = """{"type":"snapshot","sessions":[],"bgTasks":{"s1":[{"id":"b1","kind":"shell","label":"x","startedAt":1,"status":"running"}]}}"""
    val frame = decodeFrame(json) as ServerFrame.Snapshot
    assertEquals(1, frame.bgTasks["s1"]?.size)
}
```

(`decodeFrame` = whatever helper ChatFramesTest already uses to parse ServerFrame; reuse it.)

- [ ] **Step 2: Run to fail**

Run: `ROOT=$(git rev-parse --show-toplevel); TMPDIR=/home/ahmet/.cache/gt GRADLE_OPTS="-Xmx2g" "$ROOT/apps/gradlew" -p "$ROOT/apps" :shared:jvmTest --tests "dev.supermux.proto.ChatFramesTest" --no-daemon --console=plain > /home/ahmet/.cache/jvmtest.txt 2>&1; tail -20 /home/ahmet/.cache/jvmtest.txt`
Expected: compile error (unresolved BgTasks/waiting).

- [ ] **Step 3: Implement in Frames.kt.** Extend `AgentState` (after `workingSince`):

```kotlin
        val waiting: Boolean = false,      // idle but background tasks still open
        val bgOpen: Int = 0,               // open background-task count
```

Add near ActivityAppend:

```kotlin
    @Serializable
    data class BgTask(
        val id: String,
        val kind: String = "task",
        val label: String = "",
        val startedAt: Long = 0,
        val status: String = "running",
        val endedAt: Long? = null,
        val summary: String? = null,
    )

    @Serializable @SerialName("bg_tasks")
    data class BgTasks(val session: String, val tasks: List<BgTask> = emptyList()) : ServerFrame
```

Extend `Snapshot`: `val bgTasks: Map<String, List<BgTask>> = emptyMap(),`

- [ ] **Step 4: Run to pass** — same gradle command → `BUILD SUCCESSFUL`, new tests green.

- [ ] **Step 5: Commit**

```bash
git add apps/shared/src/commonMain/kotlin/dev/supermux/proto/Frames.kt apps/shared/src/commonTest/kotlin/dev/supermux/proto/ChatFramesTest.kt
git commit -m "feat(shared): bg_tasks frame + waiting/bgOpen on agent_state (KMP)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 9: Android — ingest + chips + status + badge

**Files:**
- Modify: `apps/android/src/main/kotlin/dev/supermux/android/AppViewModel.kt` (Snapshot branch ~:184, AgentState branch ~:245; add BgTasks branch)
- Create: `apps/android/src/main/kotlin/dev/supermux/android/chat/BgTaskChips.kt`
- Modify: `apps/android/src/main/kotlin/dev/supermux/android/chat/ChatScreen.kt` (+`ChatPanel.kt` if the status line lives there — read both; the "Working…" composable is the anchor), session-list row composable (find the working spinner: `rg -n "working" apps/android/src/main/kotlin/dev/supermux/android --include "*.kt" -l` outside chat/)

- [ ] **Step 1: State.** In AppViewModel add `private val _bgTasks = MutableStateFlow<Map<String, List<ServerFrame.BgTask>>>(emptyMap())` + exposed `val bgTasks: StateFlow<...>`. Snapshot branch: `_bgTasks.value = f.bgTasks`. New branch: `is ServerFrame.BgTasks -> _bgTasks.update { it + (f.session to f.tasks) }`. SessionRemoved: also `_bgTasks.update { it - f.id }`. Wherever the AgentState branch stores the per-session status object, carry `waiting` + `bgOpen` through (extend the app-side state holder type the same way web's `AgentStateEntry` grew).

- [ ] **Step 2: Chips composable** (mirror the web rules; use the app's existing tokens — Geist Mono text style, amber/red semantic colors from the theme, `animateFloat` pulse):

```kotlin
// apps/android/src/main/kotlin/dev/supermux/android/chat/BgTaskChips.kt
@Composable
fun BgTaskChips(tasks: List<ServerFrame.BgTask>, agentWorking: Boolean, modifier: Modifier = Modifier) {
    val open = tasks.filter { it.status == "running" }
    val visible = if (open.isNotEmpty() || agentWorking) tasks else emptyList()
    if (visible.isEmpty()) return
    val pulse by rememberInfiniteTransition(label = "bgpulse").animateFloat(
        initialValue = 1f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "bgpulse",
    )
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        visible.forEach { t ->
            val failed = t.status == "failed"
            val running = t.status == "running"
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .border(1.dp, if (failed) MaterialTheme.colorScheme.error.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text(
                    text = if (running) "⧗" else if (failed) "✕" else "✓",
                    color = when { running -> AmberStatus; failed -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.tertiary },
                    modifier = if (running) Modifier.graphicsLayer { alpha = pulse } else Modifier,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = t.label + " · " + if (running) elapsedLabel(t.startedAt) else t.status,
                    style = MonoLabelStyle,   // use the app's existing mono label text style token
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
```

`elapsedLabel(startedAt)`: same s/m/h math as web, ticking from a `produceState`-driven now (1 s). `AmberStatus`/`MonoLabelStyle`: substitute the app's actual token names — read `ui/theme` for the semantic amber added in the design-system work; if absent on dev, use `Color(0xFFFBBF24)` with a `// TODO(design-tokens)` comment.

- [ ] **Step 3: Status line + placement.** In the chat screen where "Working…" renders: add the chips row directly above the status line (`BgTaskChips(tasks = bgTasksForSession, agentWorking = status.working)`), and a waiting branch when `!working && waiting`: pulsing "⧗ Waiting on background tasks" (amber, labelSmall). Append tool name to the working label when present (`"Working… · ${status.tool}"`).

- [ ] **Step 4: List badge.** In the session-list row composable next to its working indicator: when `bgOpen > 0` show mono `"⧗$bgOpen"` in amber (pulse only when `!working`).

- [ ] **Step 5: Compile check** (background; watch with Monitor):

Run: `ROOT=$(git rev-parse --show-toplevel); ANDROID_HOME=/home/ahmet/Android/Sdk TMPDIR=/home/ahmet/.cache/gt GRADLE_OPTS="-Xmx2g" "$ROOT/apps/gradlew" -p "$ROOT/apps" :android:compileDebugKotlin --no-daemon --console=plain > /home/ahmet/.cache/androidcompile.txt 2>&1; echo EXIT=$?`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add apps/android/src/main/kotlin/dev/supermux/android/
git commit -m "feat(android): waiting state + background-task chips and badge

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 10: iOS — ingest + chips + status + badge (build-blind)

**Files:**
- Modify: `apps/iosApp/Supermux/Broker/BrokerSession.swift` (frame switch — find the `onEnum(of:)`/switch over ServerFrame; add BgTasks case + published `bgTasks: [String: [Shared.ServerFrameBgTask]]` map, seed from Snapshot, drop on SessionRemoved; extend the agent-state holder with `waiting`/`bgOpen`)
- Create: `apps/iosApp/Supermux/Chat/BgTaskChipsView.swift`
- Modify: `apps/iosApp/Supermux/Chat/ChatPane.swift` (status line), `apps/iosApp/Supermux/Sessions/SessionsListView.swift` + `SessionsRailView.swift` (badge)

Protocol safety is already proven by Task 8's KMP tests; Swift cannot compile on this Linux box — write carefully, mirror existing patterns exactly (SKIE sealed-class handling copied from the ActivityAppend case), and flag as Mac-unverified in the commit body.

- [ ] **Step 1: Chips view** (same visibility + elapsed rules as web; `TimelineView(.periodic(from:by:1))` for the ticker):

```swift
// apps/iosApp/Supermux/Chat/BgTaskChipsView.swift
import SwiftUI
import Shared

struct BgTaskChipsView: View {
    let tasks: [ServerFrame.BgTask]
    let agentWorking: Bool

    private var visible: [ServerFrame.BgTask] {
        let open = tasks.filter { $0.status == "running" }
        return (open.isEmpty && !agentWorking) ? [] : tasks
    }

    var body: some View {
        if !visible.isEmpty {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                FlowLayout(spacing: 6) {   // reuse the app's existing wrap layout helper if present; else a simple HStack in a horizontal ScrollView
                    ForEach(visible, id: \.id) { task in
                        chip(task, now: context.date)
                    }
                }
            }
        }
    }

    @ViewBuilder private func chip(_ t: ServerFrame.BgTask, now: Date) -> some View {
        let failed = t.status == "failed"
        let running = t.status == "running"
        HStack(spacing: 5) {
            Text(running ? "⧗" : failed ? "✕" : "✓")
                .foregroundStyle(running ? Color.orange : failed ? Color.red : Color.green)
                .opacity(running ? 0.9 : 1)
                .modifier(PulseIfRunning(running: running))
            Text("\(t.label) · \(running ? Self.elapsed(from: t.startedAt, now: now) : t.status)")
                .font(.system(size: 11, design: .monospaced))
                .lineLimit(1)
                .foregroundStyle(failed ? Color.red : Color.secondary)
        }
        .padding(.horizontal, 10).padding(.vertical, 3)
        .overlay(Capsule().stroke(failed ? Color.red.opacity(0.4) : Color.secondary.opacity(0.3), lineWidth: 1))
    }

    static func elapsed(from startedAtMs: Int64, now: Date) -> String {
        let s = max(0, Int(now.timeIntervalSince1970) - Int(startedAtMs / 1000))
        if s < 60 { return "\(s)s" }
        let m = s / 60
        return m < 60 ? "\(m)m \(s % 60)s" : "\(m / 60)h \(m % 60)m"
    }
}

private struct PulseIfRunning: ViewModifier {
    let running: Bool
    @State private var dim = false
    func body(content: Content) -> some View {
        content
            .opacity(running ? (dim ? 0.35 : 1) : 1)
            .animation(running ? .easeInOut(duration: 0.8).repeatForever(autoreverses: true) : .default, value: dim)
            .onAppear { if running { dim = true } }
    }
}
```

(Exact SKIE type name for the nested `BgTask` may be `ServerFrameBgTask` — mirror how ChatActivity.swift refers to `ActivityEvent`.)

- [ ] **Step 2: ChatPane status line.** Where the working/"Not responding" status renders: chips row above it; add a waiting branch (`!working && waiting`): orange pulsing "⧗ Waiting on background tasks"; append tool name to the working label when present.

- [ ] **Step 3: List + rail badges.** Next to the working spinner in `SessionsListView` and `SessionsRailView`: `Text("⧗\(bgOpen)")` mono footnote in orange when `bgOpen > 0`.

- [ ] **Step 4: Watch.** `rg -n "working|phase" apps/iosApp/SupermuxWatch` — where status words render, map `waiting` → "waiting". If the watch payload comes from the phone relay's agent-state, the fields flow through; otherwise skip with a code comment.

- [ ] **Step 5: Commit** (flag unverified):

```bash
git add apps/iosApp/
git commit -m "feat(ios): waiting state + background-task chips and badges

Mac-unverified: written blind on Linux; protocol locked by shared KMP tests.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 11: End-to-end verification + docs

- [ ] **Step 1: Full broker suite** — `bun test 2>&1 | tail -5`; compare failures against the 2 known pre-existing ones. `bunx tsc --noEmit` → pre-existing 3 only.

- [ ] **Step 2: Web** — `cd src/web-app && bun run test -- --run 2>&1 | tail -3 && bunx vue-tsc --noEmit 2>&1 | tail -5`.

- [ ] **Step 3: KMP** — the Task 8 gradle command stays green.

- [ ] **Step 4: Transcript replay integration test** (new `src/core/agents/claude/bg-task-integration.test.ts`): build a `TranscriptTailer` + `BgTaskDetector` + `BackgroundTaskStore` + `AgentStateStore` exactly as `ensureClaudeTailer` wires them, `tailer.ingest()` the Task-2 fixture lines end-to-end, assert: after launch lines `openCount==2` and idle→`toAgentStateFrame(...).waiting === true`; after both notifications `openCount==0`, state flipped to thinking (wake), both tasks closed with statuses. This proves the whole broker pipeline without a live session.

- [ ] **Step 5: Live experiment (evidence for the wake-timing open item).** In a scratch dir, run a standalone claude with the mux hooks settings ABSENT (plain `claude -p 'run "sleep 20 && echo done" in the background with run_in_background, then end your turn'`) and watch its transcript JSONL: confirm the `<task-notification>` line lands when the wake happens (not earlier). Record the finding in the spec's Open Items section (edit the doc) and in `~/.mux/domains/claudemux.md` at finish time.

- [ ] **Step 6: Optional live demo** — requires user OK: `preview-broker` skill to run this branch on :9898 + rebuild PWA static, spawn a session, have it run a 60s background sleep, watch the chip + waiting state appear on the phone. DO NOT restart the live broker without explicit permission.

- [ ] **Step 7: Final commit + report** — commit any docs updates; summarize to the user (reply tool): what shipped, test counts, what's Mac-gated, deploy options.

---

## Self-review (run after writing, fixed inline)

- **Spec coverage:** detector (Task 2), store (1), tailer tap (3), waiting derivation + legacy compat (4), wiring/clears/broadcasts/snapshot/watch (5), web ingest (6), web UI incl. bonus tool-name (7), KMP frames+tests (8), Android (9), iOS+watch (10), verification incl. wake-timing experiment (11). Out-of-scope items from the spec have no tasks — correct.
- **Placeholder scan:** native Tasks 9/10 reference "the app's token names / wrap-layout helper" — bounded look-up instructions with concrete fallbacks, not TBDs. OK.
- **Type consistency:** `BgTaskOpen`/`BgTaskClose`/`BackgroundTask` defined once in the store (Task 1) and imported by the detector (Task 2); `kindFromId` exported from the store; frame fields `waiting`/`bgOpen` named identically across broker/web/KMP; web store method names (`set/get/openCount/clear`) consistent between Tasks 6 and 7.
