/**
 * The expected subagent truth table: what a client may do with a subagent, per agent and state.
 *
 * ONE source of truth. API.md's per-agent table is rendered from this file (`renderMatrixTable`,
 * kept identical by tests/subagent-truth-matrix.test.ts), and `bun run truth:subagents` drives the
 * real CLIs through every state and asserts every cell (scripts/subagent-truth.ts).
 */
import { REASON } from "../../src/subagent-actions.js"

export const AGENTS = ["claude", "codex", "grok", "opencode", "cursor"] as const
export type AgentId = (typeof AGENTS)[number]

export const STATES = ["running", "finished", "stoppedByClient", "endedByParent", "afterResume"] as const
export type StateId = (typeof STATES)[number]
/** States a subagent can be in when the main phase ends (what "after resume" is checked against). */
export type TerminalStateId = Exclude<StateId, "running" | "afterResume">

export const STATE_TITLES: Record<StateId, string> = {
  running: "running",
  finished: "finished",
  stoppedByClient: "stopped by client",
  endedByParent: "ended by parent",
  afterResume: "after resume",
}

/** An action the library must offer (and that must then work natively) or refuse with `reason`. */
export type ActionExpect = { ok: true; via?: "direct" | "relay" } | { ok: false; reason: string }

export type LiveCell = {
  status: "running" | "completed" | "cancelled"
  endedBy?: "self" | "parent" | "client"
  /** running: tried on the first subagent while it runs. */
  message: ActionExpect
  /** running: tried on a second subagent while it runs (a successful stop yields "stopped by client"). */
  stop: ActionExpect
}

export type Cell = {
  /** The API.md cell text. */
  doc: string
  /** What the live run asserts; absent = documented only (state not reachable, or not exercised live). */
  live?: LiveCell
}

export type MatrixRow = {
  /** Row label in API.md. */
  label: string
  /** The agent this row is checked against live; absent = a documented-only row. */
  agent?: AgentId
  /** `actionsSource` every checked snapshot must carry. */
  actionsSource?: "native" | "derived"
  cells: Record<StateId, Cell>
}

const finishedStop: ActionExpect = { ok: false, reason: REASON.finished }
const stoppedStop: ActionExpect = { ok: false, reason: REASON.stopped }

export const MATRIX: MatrixRow[] = [
  {
    label: "Claude (derived)",
    agent: "claude",
    actionsSource: "derived",
    cells: {
      running: { doc: "Message (relay, queued for its next tool round) + Stop (`stop_task`)", live: { status: "running", message: { ok: true, via: "relay" }, stop: { ok: true } } },
      finished: { doc: "Message (resumes)", live: { status: "completed", endedBy: "self", message: { ok: true, via: "relay" }, stop: finishedStop } },
      stoppedByClient: { doc: "**no Message** (\"Stopped by you — Claude can't resume it\"; Claude refuses, even after a restart)", live: { status: "cancelled", endedBy: "client", message: { ok: false, reason: REASON.claudeClientStopped }, stop: stoppedStop } },
      endedByParent: { doc: "Message (resumes)", live: { status: "cancelled", endedBy: "parent", message: { ok: true, via: "relay" }, stop: stoppedStop } },
      afterResume: { doc: "as before the restart (registry)" },
    },
  },
  {
    label: "Codex (native)",
    agent: "codex",
    actionsSource: "native",
    cells: {
      running: { doc: "Message (`turn/steer`) + Stop (child turn active)", live: { status: "running", message: { ok: true, via: "direct" }, stop: { ok: true } } },
      finished: { doc: "Message (`turn/start`)", live: { status: "completed", endedBy: "self", message: { ok: true, via: "direct" }, stop: finishedStop } },
      stoppedByClient: { doc: "Message", live: { status: "cancelled", endedBy: "client", message: { ok: true, via: "direct" }, stop: stoppedStop } },
      endedByParent: { doc: "Message (`thread/resume` first: close_agent unloads it)", live: { status: "cancelled", endedBy: "parent", message: { ok: true, via: "direct" }, stop: stoppedStop } },
      afterResume: { doc: "Message (`thread/resume`)" },
    },
  },
  {
    label: "Codex v2 child",
    cells: {
      running: { doc: "no Message (\"Codex doesn't accept messages for this subagent\", `canAcceptDirectInput: false`)" },
      finished: { doc: "—" },
      stoppedByClient: { doc: "—" },
      endedByParent: { doc: "—" },
      afterResume: { doc: "—" },
    },
  },
  {
    label: "Grok ≥ 1.0.46 (derived)",
    agent: "grok",
    actionsSource: "derived",
    cells: {
      running: { doc: "**no Message** (\"Grok can message it once it finishes\": loading a running child breaks it) + Stop (`_x.ai/subagent/cancel`)", live: { status: "running", message: { ok: false, reason: REASON.grokRunning }, stop: { ok: true } } },
      finished: { doc: "Message (`session/load` + `session/prompt`)", live: { status: "completed", endedBy: "self", message: { ok: true, via: "direct" }, stop: finishedStop } },
      stoppedByClient: { doc: "Message", live: { status: "cancelled", endedBy: "client", message: { ok: true, via: "direct" }, stop: stoppedStop } },
      endedByParent: { doc: "Message", live: { status: "cancelled", endedBy: "parent", message: { ok: true, via: "direct" }, stop: stoppedStop } },
      afterResume: { doc: "Message (registry keeps the child session)" },
    },
  },
  {
    label: "OpenCode (derived)",
    agent: "opencode",
    actionsSource: "derived",
    cells: {
      running: { doc: "Message + Stop (HTTP abort) once the child session is known", live: { status: "running", message: { ok: true, via: "direct" }, stop: { ok: true } } },
      finished: { doc: "Message", live: { status: "completed", endedBy: "self", message: { ok: true, via: "direct" }, stop: finishedStop } },
      stoppedByClient: { doc: "Message", live: { status: "cancelled", endedBy: "client", message: { ok: true, via: "direct" }, stop: stoppedStop } },
      endedByParent: { doc: "— (no parent kill tool)" },
      afterResume: { doc: "Message (registry)" },
    },
  },
  {
    label: "Cursor",
    agent: "cursor",
    actionsSource: "derived",
    cells: {
      running: { doc: "Message (relay, derived; queued until the parent's Task returns)", live: { status: "running", message: { ok: true, via: "relay" }, stop: { ok: false, reason: REASON.cursorNoStop } } },
      finished: { doc: "Message", live: { status: "completed", endedBy: "self", message: { ok: true, via: "relay" }, stop: { ok: false, reason: REASON.cursorNoStop } } },
      stoppedByClient: { doc: "—" },
      endedByParent: { doc: "—" },
      afterResume: { doc: "Message; Stop never (\"Cursor can't stop subagents\": `subagent_spawned.capabilities` is `{}`)" },
    },
  },
]

export function rowFor(agent: AgentId): MatrixRow {
  const row = MATRIX.find(r => r.agent === agent)
  if (!row) throw new Error(`no truth-table row for ${agent}`)
  return row
}

/** The live expectation for a cell, or undefined when the state is not reachable for that agent. */
export function liveCell(agent: AgentId, state: StateId): LiveCell | undefined {
  return rowFor(agent).cells[state].live
}

/** The state a subagent's final registry entry stands for (what it is checked as after a restart). */
export function terminalStateOf(snapshot: { status?: string; endedBy?: string } | undefined): TerminalStateId | undefined {
  if (!snapshot) return
  if (snapshot.status === "completed") return "finished"
  if (snapshot.status === "cancelled" && snapshot.endedBy === "client") return "stoppedByClient"
  if (snapshot.status === "cancelled" && snapshot.endedBy === "parent") return "endedByParent"
  return
}

export const TABLE_START = "<!-- subagent-truth-table:start (generated from scripts/subagent-truth/matrix.ts; do not edit by hand) -->"
export const TABLE_END = "<!-- subagent-truth-table:end -->"

/** API.md's per-agent table (between the markers). */
export function renderMatrixTable(): string {
  const lines = [
    `| | ${STATES.map(s => STATE_TITLES[s]).join(" | ")} |`,
    `|---|${STATES.map(() => "---").join("|")}|`,
    ...MATRIX.map(row => `| ${row.label} | ${STATES.map(s => row.cells[s].doc).join(" | ")} |`),
  ]
  return [TABLE_START, ...lines, TABLE_END].join("\n")
}
