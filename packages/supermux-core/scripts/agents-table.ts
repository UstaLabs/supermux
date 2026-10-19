/**
 * The agents table of the launch README: one generated block, kept honest by tests.
 *
 *   bun scripts/agents-table.ts                          print the table
 *   bun run docs:agents                                  rewrite the block between TABLE_START and TABLE_END in README.md
 *   bun scripts/agents-table.ts --write --readme FILE    the same, for another file
 *
 * Where each column comes from, and what holds it to the drivers (tests/agents-table.test.ts):
 *   Resume .. History    written here; compared with `session.capabilities()` of a session each agent's fixture opens
 *                        through supermux(). Re-attach is the runtime's `detach`, Model switch its `configure`.
 *   CLI                  written here; compared with the command line each driver launches: `defaults.<agent>.command`
 *                        and the subcommand the driver puts after it.
 *   Permission default   written here; compared with `defaults` (src/supermux.ts).
 *   Subagents            derived from scripts/subagent-truth/matrix.ts, the table in API.md.
 *   Protocol, Questions  read from the drivers (the notes on each row say where); no test pins these two. The
 *                        question handlers behind the "yes" cells are tested in tests/claude.test.ts,
 *                        tests/agents.test.ts and tests/codex.test.ts.
 */
import { readFileSync, realpathSync, writeFileSync } from "node:fs"
import { resolve } from "node:path"
import { fileURLToPath } from "node:url"
import { parseArgs } from "node:util"
import { AGENTS as TRUTH_AGENTS, liveCell } from "./subagent-truth/matrix.js"

export const AGENT_KEYS = ["claude", "codex", "grok", "opencode", "cursor", "antigravity"] as const
export type AgentKey = (typeof AGENT_KEYS)[number]

export const TABLE_START = "<!-- agents-table:start (generated from scripts/agents-table.ts; do not edit by hand) -->"
export const TABLE_END = "<!-- agents-table:end -->"

const NONE = "—"

export type AgentRow = {
  agent: AgentKey
  name: string
  /** The command line the driver launches, up to its first flag. */
  cli: string
  protocol: string
  /** The supermux() permission inputs of the agent with their defaults, in the order shown. */
  permissions: Readonly<Record<string, string>>
  /** Said after the permission defaults. */
  permissionsNote?: string
  resume: boolean
  /** The runtime's `detach`: close({ mode: "detach" }) leaves the agent running, and a later resume re-attaches to it. */
  reattach: boolean
  steer: boolean
  fork: boolean
  /** The runtime's `configure`: session.configure({ model }) on an open session. */
  modelSwitch: boolean
  history: boolean
  /** Derived from the truth matrix (see `subagentsOf`). */
  subagents: string
  /** Whether a question the agent asks reaches the host, where it is answered (session.requests). */
  questions: string
}

/**
 * What a client may do with an agent's subagents, from the truth matrix's live cells: `list`
 * (Session.subagents()), then `message` and `stop` when the running or the finished cell offers
 * them. Message says when, if only one of the two states offers it. Stop is a running subagent's
 * action, so it names a state only when no running cell offers it. An agent with no matrix row
 * (Antigravity: its mapper emits no subagent bodies) has nothing to list.
 */
export function subagentsOf(agent: AgentKey): string {
  const truth = TRUTH_AGENTS.find(candidate => candidate === agent)
  if (!truth) return NONE
  const running = liveCell(truth, "running")
  const finished = liveCell(truth, "finished")
  const offered = (action: "message" | "stop") => ({ running: running?.[action].ok === true, finished: finished?.[action].ok === true })
  const parts = ["list"]
  const message = offered("message")
  if (message.running || message.finished) parts.push(message.running && message.finished ? "message" : message.running ? "message (while running)" : "message (once finished)")
  const stop = offered("stop")
  if (stop.running || stop.finished) parts.push(stop.running ? "stop" : "stop (once finished)")
  return parts.join(", ")
}

export const AGENT_ROWS: readonly AgentRow[] = [
  {
    // src/claude/index.ts: runtime capabilities { resume, detach, permissions }; the model is a launch option only.
    // AskUserQuestion is routed through context.requestAnswers (with the default permissionPrompts "host").
    agent: "claude", name: "Claude Code", cli: "claude", protocol: "Claude stream-json",
    permissions: { permissionMode: "default" },
    resume: true, reattach: true, steer: false, fork: false, modelSwitch: false, history: false,
    subagents: subagentsOf("claude"),
    questions: "yes (`AskUserQuestion`)",
  },
  {
    // src/codex/index.ts: every capability. The launch is `codex app-server -c sandbox_mode=… -c approval_policy=…`.
    // An inline agentMessage.questions item leaves the turn running: the driver asks through requestAnswers
    // and steers the answer into that turn (askInlineQuestions, tests/codex.test.ts).
    agent: "codex", name: "Codex", cli: "codex app-server", protocol: "Codex app-server JSON-RPC",
    permissions: { sandbox: "workspace-write", approvalPolicy: "on-request" },
    resume: true, reattach: true, steer: true, fork: true, modelSwitch: true, history: true,
    subagents: subagentsOf("codex"),
    questions: "yes (inline; the answer is steered into the running turn)",
  },
  {
    // src/agents/index.ts grok(): configure respawns the child with --model; the launch is `grok agent stdio`.
    // The resume capability is the child ACP runtime's (the CLI advertises loadSession).
    agent: "grok", name: "Grok Build", cli: "grok agent stdio", protocol: "ACP",
    permissions: { policy: "ask" },
    resume: true, reattach: true, steer: false, fork: false, modelSwitch: true, history: false,
    subagents: subagentsOf("grok"),
    questions: "yes (`_x.ai/ask_user_question`)",
  },
  {
    // src/acp/index.ts: { resume: the CLI's loadSession or resume capability, detach }; the model is a launch option.
    // The driver answers any ACP method it does not know with "Method not found", and OpenCode has no question tool.
    agent: "opencode", name: "OpenCode", cli: "opencode acp", protocol: "ACP",
    permissions: { policy: "ask" },
    resume: true, reattach: true, steer: false, fork: false, modelSwitch: false, history: false,
    subagents: subagentsOf("opencode"),
    questions: NONE,
  },
  {
    // src/acp/index.ts, as OpenCode; the mode and model are launch options (session config).
    // Cursor has no question tool the driver could answer.
    agent: "cursor", name: "Cursor", cli: "cursor-agent acp", protocol: "ACP",
    permissions: { policy: "ask", mode: "agent" },
    resume: true, reattach: true, steer: false, fork: false, modelSwitch: false, history: false,
    subagents: subagentsOf("cursor"),
    questions: NONE,
  },
  {
    // src/antigravity/index.ts: one `agy --input-format stream-json --output-format stream-json` process per session,
    // NDJSON over stdio (init, step_update and result events out, a user event in). It never asks the host.
    agent: "antigravity", name: "Antigravity", cli: "agy", protocol: "agy stream-json (`init`, `step_update`, `result`)",
    permissions: { mode: "default" },
    permissionsNote: "no prompt channel: agy denies risky actions itself",
    resume: true, reattach: true, steer: false, fork: false, modelSwitch: true, history: false,
    subagents: subagentsOf("antigravity"),
    questions: NONE,
  },
]

export function rowOf(agent: AgentKey): AgentRow {
  const row = AGENT_ROWS.find(candidate => candidate.agent === agent)
  if (!row) throw new Error(`no agents-table row for ${agent}`)
  return row
}

const COLUMNS = ["Agent", "CLI", "Protocol", "Permission default", "Resume", "Re-attach", "Steer", "Fork", "Model switch", "History", "Subagents", "Questions"]

const yes = (flag: boolean) => (flag ? "yes" : NONE)
const code = (text: string) => `\`${text}\``

function permissionCell(row: AgentRow): string {
  const given = code(Object.entries(row.permissions).map(([input, value]) => `${input}: ${value}`).join(", "))
  return row.permissionsNote ? `${given} (${row.permissionsNote})` : given
}

/** The README's agents table (between the markers). */
export function renderAgentsTable(): string {
  const lines = [
    `| ${COLUMNS.join(" | ")} |`,
    `|${COLUMNS.map(() => "---").join("|")}|`,
    ...AGENT_ROWS.map(row => `| ${[
      row.name, code(row.cli), row.protocol, permissionCell(row),
      yes(row.resume), yes(row.reattach), yes(row.steer), yes(row.fork), yes(row.modelSwitch), yes(row.history),
      row.subagents, row.questions,
    ].join(" | ")} |`),
  ]
  return [TABLE_START, ...lines, TABLE_END].join("\n")
}

/** `readme` with the block between the markers (markers included) replaced by the current table. */
export function replaceAgentsTable(readme: string, label = "README.md"): string {
  const start = readme.indexOf(TABLE_START)
  if (start === -1) throw new Error(`${label} has no agents table start marker: put this line where the table goes, with ${TABLE_END} after it\n${TABLE_START}`)
  const end = readme.indexOf(TABLE_END, start)
  if (end === -1) throw new Error(`${label} has no agents table end marker after the start marker: add this line after it\n${TABLE_END}`)
  return readme.slice(0, start) + renderAgentsTable() + readme.slice(end + TABLE_END.length)
}

const README = fileURLToPath(new URL("../README.md", import.meta.url))
const USAGE = "usage: bun scripts/agents-table.ts [--write [--readme FILE]]"

function main(argv: string[]): number {
  let options: { write?: boolean; readme?: string }
  try {
    options = parseArgs({ args: argv, options: { write: { type: "boolean" }, readme: { type: "string" } }, allowPositionals: false }).values
    if (options.readme !== undefined && !options.write) throw new Error("--readme goes with --write")
  } catch (error) {
    console.error(`docs:agents: ${error instanceof Error ? error.message : String(error)}\n${USAGE}`)
    return 2
  }
  if (!options.write) {
    console.log(renderAgentsTable())
    return 0
  }
  const file = options.readme === undefined ? README : resolve(options.readme)
  try {
    const before = readFileSync(file, "utf8")
    const after = replaceAgentsTable(before, file)
    if (after !== before) writeFileSync(file, after)
    console.log(`${file}: agents table ${after === before ? "already up to date" : "updated"}`)
    return 0
  } catch (error) {
    console.error(`docs:agents: ${error instanceof Error ? error.message : String(error)}`)
    return 1
  }
}

// Only when run as a script: the tests import this module for the table.
if (process.argv[1] && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url))) process.exitCode = main(process.argv.slice(2))
