import { spawnSync } from "node:child_process"
import { afterEach, expect, setDefaultTimeout, test } from "bun:test"
import { chmodSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { fileURLToPath } from "node:url"
import { AGENT_ROWS, TABLE_END, TABLE_START, renderAgentsTable, replaceAgentsTable, rowOf, type AgentKey } from "../scripts/agents-table.js"
import { AGENTS as TRUTH_AGENTS, liveCell } from "../scripts/subagent-truth/matrix.js"
import { antigravityArgs } from "../src/antigravity/index.js"
import { defaults, supermux, type SupermuxAgents } from "../src/index.js"

setDefaultTimeout(30_000)

const fixture = (name: string) => fileURLToPath(new URL(`./fixtures/${name}`, import.meta.url))
const script = fileURLToPath(new URL("../scripts/agents-table.ts", import.meta.url))
const dirs: string[] = []
const temp = (prefix: string) => { const dir = mkdtempSync(join(tmpdir(), prefix)); dirs.push(dir); return dir }
afterEach(() => { for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true }) })

test("README.md's agents table is exactly renderAgentsTable() once it has the markers (passes trivially until the launch README adds them)", () => {
  const readme = readFileSync(new URL("../README.md", import.meta.url), "utf8")
  const start = readme.indexOf(TABLE_START)
  if (start === -1) return
  const end = readme.indexOf(TABLE_END, start)
  expect(end).toBeGreaterThan(start)
  expect(readme.slice(start, end + TABLE_END.length)).toBe(renderAgentsTable())
})

test("AGENT_ROWS has one row for every agent supermux() takes, in the table's order", () => {
  expect(AGENT_ROWS.map(row => row.agent)).toEqual(["claude", "codex", "grok", "opencode", "cursor", "antigravity"])
  // An agent added to `defaults` must get a row (and so a line in the README).
  expect([...AGENT_ROWS.map(row => row.agent)].sort()).toEqual(Object.keys(defaults).filter(key => key !== "core").sort())
})

test("the rendered table has the documented columns and one row per agent, with yes / — for the booleans", () => {
  const lines = renderAgentsTable().split("\n")
  expect(lines[0]).toBe(TABLE_START)
  expect(lines.at(-1)).toBe(TABLE_END)
  const [header, divider, ...rows] = lines.slice(1, -1)
  const cells = (line: string) => line.split("|").slice(1, -1).map(cell => cell.trim())
  expect(cells(header!)).toEqual(["Agent", "CLI", "Protocol", "Permission default", "Resume", "Re-attach", "Steer", "Fork", "Model switch", "History", "Subagents", "Questions"])
  expect(cells(divider!)).toEqual(Array(12).fill("---"))
  expect(rows.map(row => cells(row)[0])).toEqual(["Claude Code", "Codex", "Grok Build", "OpenCode", "Cursor", "Antigravity"])
  for (const row of rows) {
    expect(cells(row)).toHaveLength(12)
    for (const flag of cells(row).slice(4, 10)) expect(["yes", "—"]).toContain(flag)
  }
})

type Fixtured = Exclude<AgentKey, "antigravity">

/** One agent's `supermux()` input, pointing at its fixture. The fixture writes the argv it was launched with to `trace`. */
function inputFor(agent: Fixtured, dir: string, trace: string): SupermuxAgents {
  switch (agent) {
    case "claude": return { claude: { command: process.execPath, args: [fixture("claude-agent.mjs")], env: { ENV_TRACE: trace, EXPECT_DEFAULT_DENY: "0" } } }
    // The args after the fixture are the defaults': what the real `codex` would be launched with.
    case "codex": return { codex: { command: process.execPath, args: [fixture("codex-agent.mjs"), ...defaults.codex.args], env: { ENV_TRACE: trace, EXPECT_POLICY: defaults.codex.approvalPolicy, EXPECT_SANDBOX: defaults.codex.sandbox } } }
    case "grok": return { grok: { command: process.execPath, commandArgs: [fixture("grok-agent.mjs")], env: { ENV_TRACE: trace } } }
    case "opencode": {
      // opencode() has no argv prefix: a shim stands in for the opencode executable.
      const shim = join(dir, "opencode")
      writeFileSync(shim, `#!/bin/sh\nexec "${process.execPath}" "${fixture("acp-agent.mjs")}" "$@"\n`)
      chmodSync(shim, 0o755)
      return { opencode: { command: shim, env: { ENV_TRACE: trace } } }
    }
    case "cursor": return { cursor: { command: process.execPath, commandArgs: [fixture("cursor-agent.mjs")], env: { ENV_TRACE: trace } } }
  }
}

/** The command line up to its first flag: the executable and the subcommand the driver puts after it. */
const commandLine = (agent: AgentKey, argv: readonly string[]) => {
  const flag = argv.findIndex(arg => arg.startsWith("-"))
  return [defaults[agent].command, ...(flag === -1 ? argv : argv.slice(0, flag))].join(" ")
}

/** The capabilities columns of a row, as `session.capabilities()` spells them. */
const abilities = (c: { resume: boolean; detach: boolean; steer: boolean; fork: boolean; configure?: boolean; history?: boolean }) =>
  ({ resume: c.resume, reattach: c.detach, steer: c.steer, fork: c.fork, modelSwitch: c.configure === true, history: c.history === true })
const abilitiesOf = (row: ReturnType<typeof rowOf>) =>
  ({ resume: row.resume, reattach: row.reattach, steer: row.steer, fork: row.fork, modelSwitch: row.modelSwitch, history: row.history })

for (const agent of ["claude", "codex", "grok", "opencode", "cursor"] as const) {
  test(`${agent}: a session opened through supermux() has the capabilities and launch command of its row`, async () => {
    const dir = temp(`agents-table-${agent}-`)
    const trace = join(dir, "argv.json")
    const core = supermux({ stateDirectory: join(dir, "state"), agents: inputFor(agent, dir, trace) })
    try {
      const session = await core.sessions.create({ id: `${agent}-1`, agent, cwd: dir })
      const { argv } = JSON.parse(readFileSync(trace, "utf8")) as { argv: string[] }
      expect(abilities(session.capabilities())).toEqual(abilitiesOf(rowOf(agent)))
      expect(commandLine(agent, argv)).toBe(rowOf(agent).cli)
    } finally {
      await core.close({ agents: "shutdown" })
    }
  })
}

test("antigravity (the fixture's safe mode refuses at open, so this reads the capability literal in src/antigravity/index.ts at test time): capabilities, launch command and the missing prompt channel match its row", () => {
  const row = rowOf("antigravity")
  const read = (file: string) => readFileSync(new URL(`../src/antigravity/${file}`, import.meta.url), "utf8")
  const literals = [...read("index.ts").matchAll(/\bcapabilities:\s*\{([^}]*)\}/g)]
  expect(literals).toHaveLength(1)
  const body = literals[0]![1]!
  expect(body.replace(/\w+:\s*(true|false)/g, "").replace(/[\s,]/g, "")).toBe("") // booleans only: nothing is computed
  const flags = Object.fromEntries([...body.matchAll(/(\w+):\s*(true|false)/g)].map(([, key, value]) => [key!, value === "true"]))
  expect(abilities(flags as Parameters<typeof abilities>[0])).toEqual(abilitiesOf(row))
  expect(commandLine("antigravity", antigravityArgs({ prefix: [], permissions: { kind: "antigravity", mode: defaults.antigravity.mode } }))).toBe(row.cli)
  // "No prompt channel": the driver never asks the host for a permission or an answer.
  for (const file of ["index.ts", "normalize.ts", "transport.ts"]) expect(read(file)).not.toMatch(/\.request(Permission|Answers)\b/)
})

test("every row's permission defaults are the supermux() defaults of that agent's permission inputs", () => {
  const inputs: Record<AgentKey, string[]> = {
    claude: ["permissionMode"],
    codex: ["sandbox", "approvalPolicy"],
    grok: ["policy"],
    opencode: ["policy"],
    cursor: ["policy", "mode"],
    antigravity: ["mode"],
  }
  for (const row of AGENT_ROWS) {
    expect(Object.keys(row.permissions)).toEqual(inputs[row.agent])
    const given: Record<string, unknown> = defaults[row.agent]
    expect(row.permissions).toEqual(Object.fromEntries(inputs[row.agent].map(key => [key, given[key]])))
  }
})

test("the Subagents column is derived from the truth matrix: list, then Message and Stop where its running / finished cells offer them", () => {
  for (const row of AGENT_ROWS) {
    const agent = TRUTH_AGENTS.find(candidate => candidate === row.agent)
    if (!agent) {
      expect(row.subagents).toBe("—") // no matrix row: the agent's mapper emits no subagent bodies
      continue
    }
    const running = liveCell(agent, "running")!
    const finished = liveCell(agent, "finished")!
    const parts = row.subagents.split(", ")
    expect(parts[0]).toBe("list")
    const message = parts.find(part => part.startsWith("message"))
    const stop = parts.find(part => part.startsWith("stop"))
    expect(parts).toHaveLength(1 + (message ? 1 : 0) + (stop ? 1 : 0))
    // Message: offered when the running or the finished cell offers it, and says when if only one does.
    expect(message === undefined).toBe(!running.message.ok && !finished.message.ok)
    if (message) expect(message).toBe(running.message.ok && finished.message.ok ? "message" : running.message.ok ? "message (while running)" : "message (once finished)")
    // Stop: a running subagent's action, so it only names a state when no running cell offers it.
    expect(stop === undefined).toBe(!running.stop.ok && !finished.stop.ok)
    if (stop) expect(stop).toBe(running.stop.ok ? "stop" : "stop (once finished)")
  }
  // Two examples of the format on today's matrix; update them when the matrix changes.
  expect(rowOf("grok").subagents).toBe("list, message (once finished), stop") // Grok cannot be messaged while it runs
  expect(rowOf("cursor").subagents).toBe("list, message") // Cursor cannot stop subagents
})

test("replaceAgentsTable swaps the block between the markers and leaves the rest alone, or says which marker is missing", () => {
  const readme = `# Title\n\nintro\n\n${TABLE_START}\nstale\n${TABLE_END}\n\noutro\n`
  const written = replaceAgentsTable(readme)
  expect(written).toBe(`# Title\n\nintro\n\n${renderAgentsTable()}\n\noutro\n`)
  expect(replaceAgentsTable(written)).toBe(written)
  expect(() => replaceAgentsTable("# no table here\n")).toThrow("agents table start marker")
  expect(() => replaceAgentsTable(`intro\n${TABLE_START}\nstale\n`)).toThrow("agents table end marker")
  expect(() => replaceAgentsTable(`${TABLE_END}\n${TABLE_START}\n`)).toThrow("agents table end marker")
})

test("bun scripts/agents-table.ts prints the table; --write --readme updates that file in place and refuses one without the markers", () => {
  const dir = temp("agents-table-script-")
  const file = join(dir, "README.md")
  const run = (...args: string[]) => spawnSync(process.execPath, [script, ...args], { encoding: "utf8" })

  expect(run().stdout.trimEnd()).toBe(renderAgentsTable())

  writeFileSync(file, `intro\n\n${TABLE_START}\nstale\n${TABLE_END}\n\noutro\n`)
  const written = run("--write", "--readme", file)
  expect(written.status).toBe(0)
  expect(readFileSync(file, "utf8")).toBe(`intro\n\n${renderAgentsTable()}\n\noutro\n`)

  writeFileSync(file, "no markers here\n")
  const refused = run("--write", "--readme", file)
  expect(refused.status).toBe(1)
  expect(refused.stderr).toContain("agents table start marker")
  expect(refused.stderr).toContain(TABLE_START)
  expect(readFileSync(file, "utf8")).toBe("no markers here\n")

  expect(run("--bogus").status).toBe(2)
})
