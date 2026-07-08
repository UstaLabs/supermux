# Waiting state + background-task visibility — design

**Date:** 2026-07-08 · **Approved by:** Ahmet (chat, "go yolo from here")
**Branch:** `mux/supermux-18` (worktree session `waiting-state-bg-tasks`)

## Problem

Two visibility gaps in session status, both felt on real fleets:

1. **Background tasks are invisible.** When claude launches a background shell,
   subagent, or workflow and ends its turn, the session shows **idle** — it looks
   finished (or broken) even though a 5-minute gradle build is running and the
   harness will wake claude when it completes. This is the main complaint.
2. **Foreground long tool runs read as generic.** The chat stream *does* show a
   running tool row (`▸ Bash: gradlew build…`) on web, iOS and Android — verified —
   but the bottom status line ("Working… 5m") and the session-list spinner never
   name the blocker.

## Decisions made with the user

- **Direction B (mockup-approved):** task chips. One chip per open background task
  with its own elapsed timer; a slim "Waiting" status line; a `⧗ n` badge in the
  session list. Amber pulsing ⧗ = running, green ✓ / red ✕ on finish.
- **Approach 1:** transcript-driven detection in the broker (not hooks, not file
  watching). One implementation feeds all clients.
- **Scope:** all notification-driven background work — bg shells + subagents +
  workflow runs. Unified model.
- **Waiting semantics:** waiting = *turn over, background tasks still open*.
  Foreground tool runs stay "working" (they already render as running tool rows);
  bonus: the working status line may name the current tool ("Working… · Bash · 4m").

### Explicitly out of scope

- Todo-list mirror ("plan progress" panel from TaskCreate/TaskUpdate) — separate feature.
- codex / cursor / opencode detectors — the store is agent-agnostic; detectors can
  plug in later if those protocols expose equivalents.
- Push notification on task completion — the wake→reply push already covers it.
- Tapping a chip for live task output.

## Verified transcript facts (real fixtures, 2026-07-08)

All markers below were captured from live transcripts on this machine:

- **Bg shell start** — `tool_result` (string or text-block) paired to a `Bash`
  `tool_use` whose input has `run_in_background: true`:
  `"Command running in background with ID: b3137swze. Output is being written to: <path>"`
- **Subagent start** — `tool_result` paired to an `Agent` tool_use:
  `"Async agent launched successfully.\nagentId: a2bee0eded79e862d (internal ID - do not mention to user…)"`
- **Completion** — a transcript line `type:"user"` whose `message.content` is a
  **plain string** (today dropped by `parseTranscriptLine`, which requires array
  content):

  ```
  <task-notification>
  <task-id>bxcdg51aa</task-id>
  <tool-use-id>toolu_01AgxTWRTycjkLfksT2v439f</tool-use-id>
  <output-file>…/tasks/bxcdg51aa.output</output-file>
  <status>failed</status>
  <summary>Background command "…" failed with exit code 1</summary>
  </task-notification>
  ```

- Task-id shape: `b…` = shell, `a…` = agent; workflows use `wf_…` run ids
  (launch-marker format to confirm during implementation; detector must tolerate
  unknown prefixes → kind `"task"`).

## Design

### 1 · `BgTaskDetector` (broker, per claude session)

New module `src/core/agents/claude/bg-task-detector.ts`, fed the same raw
transcript lines the tailer already reads (alongside, not inside,
`parseTranscriptLine`, which stays pure).

Per-session state: a bounded FIFO map `callId → {tool, label}` (~50 entries)
recorded from `tool_use` blocks (`Bash`, `Agent`, `Task`, `Workflow`).

- **Open** on a `tool_result` whose text matches
  `/Command running in background with ID:\s*([A-Za-z0-9_-]+)/` or
  `/agentId:\s*([A-Za-z0-9_-]+)/` (with "Async agent launched" context). Label =
  paired tool_use's `description` (Bash/Agent) else first line of `command` /
  clipped prompt; kind from id prefix (`b`→shell, `a`→agent, `wf_`→workflow,
  else task).
- **Close** on a string-content user line matching
  `<task-notification>…<task-id>X</task-id>…<status>S</status>…<summary>…</summary>`.
  Status maps `completed`→completed, anything else→failed. A close for an unseen
  id creates the task already-closed (the ✓/✕ moment still renders).
- **Wake signal:** the same task-notification line is delivery evidence — emit a
  `turn-start` to the agent-state store so the session flips waiting→thinking at
  wake instantly (same transcript-as-signal pattern as interrupt detection).
  *Timing to verify by experiment during implementation.*

### 2 · `BackgroundTaskStore` (broker)

`src/core/session-manager/background-task-store.ts`, in-memory, ephemeral —
deliberately the same lifecycle as `ActivityStore` (dropped on broker restart;
v1 limitation, acceptable).

```ts
interface BackgroundTask {
  id: string
  kind: "shell" | "agent" | "workflow" | "task"
  label: string
  startedAt: number            // epoch ms from transcript timestamp
  status: "running" | "completed" | "failed"
  endedAt?: number
  summary?: string
  callId?: string
}
```

- `upsertOpen` / `close` are idempotent by id (duplicate markers safe; replayed
  tail lines safe).
- Keeps all open tasks + the last 20 closed per session.
- `openCount(session)`; emits `change(sessionId)`.
- Cleared on session kill / archive / dead (no fake "waiting forever" when the
  harness died — liveness already flips the session dead).

### 3 · State derivation + protocol (additive only)

The hook-driven 4-state machine (`idle|thinking|running|dead`) is **untouched**.
"Waiting" is derived at the frame layer:

- `toAgentStateFrame(session, st, bgOpen)`:
  `waiting = st.phase === "idle" && bgOpen > 0`. `state` stays `"idle"` and
  `working` stays `false` — legacy clients keep today's exact behavior; dead
  beats waiting by construction.
- `agent_state` frame gains `waiting: boolean` + `bgOpen: number`.
- New frame `bg_tasks` `{type, session, tasks: BackgroundTask[]}` — full list on
  any change (lists are tiny; no delta protocol). Snapshot gains
  `bgTasks: Record<session, BackgroundTask[]>`.
- Any store change also re-emits that session's `agent_state` (covers open→0
  transitions while idle).
- **Frames.kt gets both serializers in the same commit** (`AgentState` new fields
  with defaults; `BgTasks` frame + `BgTask` type; snapshot map with `emptyMap()`
  default) — the `session_state` lesson: natives silently drop frames they can't
  decode.
- Watch REST route (`watch-sessions-route`) exposes `waiting`/`bgOpen` too.

### 4 · Clients (web + Android + iOS + watch)

Shared vocabulary (from the approved mockups):

- **Chips row** above the composer, one chip per task: pulsing amber `⧗` +
  mono label + per-chip elapsed while running; `✓` (green) / `✕` (red, tinted
  chip) when closed. Closed chips clear when the session next goes idle with no
  open tasks (i.e. they linger only while claude reacts).
- **Status line** when waiting: pulsing amber `⧗` + "Waiting on background
  tasks" (no line-level timer — chips carry their own). No Stop button (claude
  is idle; there is nothing to interrupt).
- **Session list**: mono `⧗ n` badge (amber, pulsing while any task runs) shown
  whenever open tasks exist; the working spinner keeps its current meaning and
  may appear alongside.
- **Bonus (small):** while working with a known tool, status line reads
  "Working… · Bash · 4m" (tool name already in the frame today).
- Web = Vue components in `ChatView` / `SessionRow`; Android = Compose (Geist
  Mono for labels, amber/red semantic roles per the design language); iOS =
  SwiftUI (decoding free via shared KMP). Watch shows the word "waiting".
- iOS/mac cannot be runtime-verified on this Linux box — build-blind with KMP
  parity tests, Mac verification later (same precedent as notif-group-clear).

## Edge cases

- Duplicate start markers / tailer replay → idempotent upsert by id.
- Notification for unseen id → create closed (✓/✕ still shown).
- Broker restart → open tasks lost (ephemeral; documented v1 limitation).
- Session dead/killed/archived → tasks cleared, waiting cannot stick.
- User sends a message while waiting → normal turn; chips persist alongside
  "Working…".
- Unknown `<status>` values → failed (conservative).
- Task killed via TaskStop → only if a cheap transcript marker exists; otherwise
  the task stays open until session end (accepted v1).

## Testing

- **Broker:** detector unit tests from the captured real fixtures (bg shell,
  agent, failed + completed notifications, unseen-id close, duplicate markers);
  store tests (idempotency, cap, clear, openCount); frame tests — waiting
  derivation + legacy fields byte-stable when feature idle; web-channel
  snapshot/`bg_tasks`/watch-route tests.
- **Web:** pinia store tests (`bgTasks`, `agentState.waiting`); chips component
  test; ws ingest test.
- **KMP:** `ChatFramesTest` parity — decode `bg_tasks`, `agent_state` with and
  without the new fields (unknown-field tolerance both directions).
- **Live verification:** replay a captured transcript through the tailer against
  a dev broker; then a real bg shell in a spawned session (`sleep 90 &`-style)
  observing frames. Full PWA/native deploy is a separate step (broker restart
  requires explicit permission; PWA static rebuild is restart-free).

## Open items to verify during implementation

1. Task-notification line timing vs actual wake — **RESOLVED (2026-07-08).** Confirmed
   first-hand: a real background shell (`byvz3l2sf`) logged its "Command running in
   background with ID" tool-result at launch (transcript ts `07:39:39.721Z`) and its
   `<task-notification>` (`status=completed`) arrived only when the command finished,
   waking the agent at that moment — not earlier. So `onWake` on the notification line
   reflects the wake correctly. The end-to-end `bg-task-integration.test.ts` locks the
   pipeline behavior (waiting on idle-with-open, thinking on wake).
2. Workflow launch-marker text + `wf_` id confirmation.
3. Tailer behavior on session attach/resume (replay window vs end-seek) —
   detector must tolerate both (idempotent upsert already covers it).
4. Whether kotlinx `ignoreUnknownKeys` is already on for ServerFrame decoding
   (expected yes — existing pattern).

## Deployment notes

- Broker changes need a restart → **never without explicit permission**; use the
  `preview-broker` skill for a live trial.
- Web UI alone is restart-free (static rebuild into the served dir).
- Android needs an APK build; iOS/mac need the remote Mac.

## Artifacts

- Mockups (direction B + lifecycle): `.superpowers/brainstorm/2733708-1783489500/content/`
  (gitignored, ephemeral; served during the session at brainstorm.ustalabs.com).
