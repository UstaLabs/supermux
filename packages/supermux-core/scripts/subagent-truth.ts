/**
 * Live subagent truth table: drives the REAL agent CLIs through every subagent state and asserts
 * every cell of scripts/subagent-truth/matrix.ts (the table in API.md).
 *
 *   bun run truth:subagents [--agent codex[,grok]] [--model codex=gpt-5.6-luna] [--effort grok=low]
 *                           [--command grok=/path/to/grok] [--if-changed] [--versions-file F]
 *                           [--out DIR] [--serial]
 *
 * Each agent runs in a scratch directory under the OS temp dir (its own core state and keeper; the
 * user's supermux state is never touched): a "main" process walks running → finished → stopped by
 * client → ended by parent, then a NEW process resumes the session and checks every subagent again.
 * Exit 1 on any mismatch. Writes report.json + report.md (CLI versions, per-cell pass/fail).
 *
 * `--if-changed`: skip agents whose CLI version is the one that last passed (state file, default
 * ~/.cache/supermux-core/truth-versions.json), so it is cheap to run on a schedule.
 */
import { spawn } from "node:child_process"
import { createWriteStream, existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join, resolve } from "node:path"
import { fileURLToPath } from "node:url"
import { judge, type CellCheck, type Observation } from "./subagent-truth/judge.js"
import { AGENTS, STATE_TITLES, type AgentId } from "./subagent-truth/matrix.js"
import type { PhaseConfig } from "./subagent-truth/phase.js"
import { cliVersion, defaultVersionsFile, readVersionState, recordPass, skipReason, writeVersionState } from "./subagent-truth/versions.js"

/** Cheap defaults; override with --model / --effort. */
const DEFAULT_MODELS: Record<AgentId, { model: string; effort?: string }> = {
  claude: { model: "haiku" },
  codex: { model: "gpt-5.6-luna", effort: "low" },
  grok: { model: "grok-4.7", effort: "low" },
  opencode: { model: "opencode-go/qwen3.8-flash" },
  cursor: { model: "auto" },
}
const DEFAULT_COMMANDS: Record<AgentId, string> = { claude: "claude", codex: "codex", grok: "grok", opencode: "opencode", cursor: "cursor-agent" }
const PHASE_TIMEOUT_MS = 40 * 60_000

type Args = {
  agents: AgentId[]
  models: Partial<Record<AgentId, string>>
  efforts: Partial<Record<AgentId, string>>
  commands: Partial<Record<AgentId, string>>
  ifChanged: boolean
  versionsFile: string
  out?: string
  serial: boolean
}

function usage(message?: string): never {
  if (message) console.error(`subagent-truth: ${message}`)
  console.error("usage: bun run truth:subagents [--agent a[,b]] [--model agent=model] [--effort agent=level] [--command agent=path] [--if-changed] [--versions-file F] [--out DIR] [--serial]")
  process.exit(2)
}

function parseArgs(argv: string[]): Args {
  const args: Args = { agents: [], models: {}, efforts: {}, commands: {}, ifChanged: false, versionsFile: defaultVersionsFile(), serial: false }
  const isAgent = (a: string): a is AgentId => (AGENTS as readonly string[]).includes(a)
  const pair = (value: string | undefined, flag: string): [AgentId, string] => {
    const at = value?.indexOf("=") ?? -1
    const agent = value?.slice(0, at) ?? ""
    if (at <= 0 || !isAgent(agent) || !value!.slice(at + 1)) usage(`${flag} wants agent=value with agent one of ${AGENTS.join(", ")}`)
    return [agent, value!.slice(at + 1)]
  }
  for (let i = 0; i < argv.length; i++) {
    const flag = argv[i]!
    const value = () => { const v = argv[++i]; if (v === undefined) usage(`${flag} needs a value`); return v }
    if (flag === "--agent") for (const a of value().split(",").map(s => s.trim()).filter(Boolean)) { if (!isAgent(a)) usage(`unknown agent ${a}`); args.agents.push(a) }
    else if (flag === "--model") { const [a, v] = pair(value(), flag); args.models[a] = v }
    else if (flag === "--effort") { const [a, v] = pair(value(), flag); args.efforts[a] = v }
    else if (flag === "--command") { const [a, v] = pair(value(), flag); args.commands[a] = v }
    else if (flag === "--if-changed") args.ifChanged = true
    else if (flag === "--versions-file") args.versionsFile = resolve(value())
    else if (flag === "--out") args.out = resolve(value())
    else if (flag === "--serial") args.serial = true
    else if (flag === "--help" || flag === "-h") usage()
    else usage(`unknown flag ${flag}`)
  }
  if (!args.agents.length) args.agents = [...AGENTS]
  args.agents = [...new Set(args.agents)]
  return args
}

const phaseScript = fileURLToPath(new URL("./subagent-truth/phase.ts", import.meta.url))

function runPhase(agent: AgentId, phase: "main" | "resume", configPath: string, dir: string): Promise<number> {
  return new Promise(done => {
    const log = createWriteStream(join(dir, `${phase}.out`))
    const child = spawn(process.execPath, [phaseScript, phase, configPath], { stdio: ["ignore", "pipe", "pipe"], env: process.env })
    const forward = (chunk: Buffer) => { log.write(chunk); process.stdout.write(chunk) }
    child.stdout.on("data", forward)
    child.stderr.on("data", forward)
    const timer = setTimeout(() => { console.error(`[${agent}/${phase}] timed out after ${PHASE_TIMEOUT_MS / 60_000} min`); child.kill("SIGTERM") }, PHASE_TIMEOUT_MS)
    child.on("close", code => { clearTimeout(timer); log.end(); done(code ?? 1) })
  })
}

type AgentReport = {
  agent: AgentId
  version?: string
  model?: string
  effort?: string
  skipped?: string
  pass: boolean
  dir?: string
  exitCodes?: { main: number; resume?: number }
  /** The agent reported a usage / plan limit during the run (failures are then likely the account's). */
  quota?: string
  checks: CellCheck[]
}

async function runAgent(agent: AgentId, args: Args, version: string | undefined, root: string): Promise<AgentReport> {
  const dir = join(root, agent)
  mkdirSync(dir, { recursive: true })
  const model = args.models[agent] ?? DEFAULT_MODELS[agent].model
  const effort = args.efforts[agent] ?? DEFAULT_MODELS[agent].effort
  const base = { agent, ...(version ? { version } : {}), model, ...(effort ? { effort } : {}), dir }
  if (!version) return { ...base, pass: false, checks: [{ state: "running", check: "reached", expected: `${args.commands[agent] ?? DEFAULT_COMMANDS[agent]} --version works`, actual: "CLI not found or failed", pass: false }] }
  const config: PhaseConfig = { agent, dir, command: args.commands[agent] ?? DEFAULT_COMMANDS[agent], model, ...(effort ? { effort } : {}) }
  const configPath = join(dir, "config.json")
  writeFileSync(configPath, JSON.stringify(config, null, 2))
  const main = await runPhase(agent, "main", configPath, dir)
  const resumeCode = existsSync(join(dir, "ids.json")) ? await runPhase(agent, "resume", configPath, dir) : undefined
  const observations: Observation[] = []
  for (const phase of ["main", "resume"] as const) {
    const file = join(dir, `observations-${phase}.json`)
    if (existsSync(file)) observations.push(...JSON.parse(readFileSync(file, "utf8")) as Observation[])
  }
  if (resumeCode === undefined) observations.push({ kind: "unreached", state: "afterResume", reason: "the main phase did not finish (no ids.json)" })
  const checks = judge(agent, observations)
  if (main !== 0) checks.push({ state: "running", check: "reached", expected: "main phase exits 0", actual: `exit ${main}`, pass: false })
  if (resumeCode !== undefined && resumeCode !== 0) checks.push({ state: "afterResume", check: "reached", expected: "resume phase exits 0", actual: `exit ${resumeCode}`, pass: false })
  const quota = ["main", "resume"].flatMap(phase => {
    const file = join(dir, `actions-${phase}.ndjson`)
    return existsSync(file) ? readFileSync(file, "utf8").split("\n").filter(line => line.includes('"kind":"quota"')).map(line => String(JSON.parse(line).text).trim()) : []
  })[0]
  return { ...base, ...(quota ? { quota } : {}), pass: checks.every(c => c.pass), exitCodes: { main, ...(resumeCode !== undefined ? { resume: resumeCode } : {}) }, checks }
}

function markdown(report: { startedAt: string; finishedAt: string; pass: boolean; agents: AgentReport[] }): string {
  const esc = (s: string) => s.replace(/\|/g, "\\|").replace(/\n/g, " ")
  const lines = [
    `# Subagent truth table — ${report.pass ? "PASS" : "FAIL"}`,
    "",
    `Run ${report.startedAt} → ${report.finishedAt}. Expected values: scripts/subagent-truth/matrix.ts (API.md).`,
    "",
    "| agent | CLI version | model | result |",
    "|---|---|---|---|",
    ...report.agents.map(a => `| ${a.agent} | ${a.version ?? "?"} | ${a.model ?? ""}${a.effort ? ` (${a.effort})` : ""} | ${a.skipped ? `skipped: ${esc(a.skipped)}` : a.pass ? "PASS" : `FAIL (${a.checks.filter(c => !c.pass).length} of ${a.checks.length})`} |`),
  ]
  for (const a of report.agents) {
    if (a.skipped) continue
    lines.push("", `## ${a.agent} — ${a.pass ? "PASS" : "FAIL"}`)
    if (a.quota) lines.push("", `> The agent hit a usage / plan limit during the run ("${esc(a.quota)}"); failures after it are likely the account's, not the library's. Rerun when the limit resets.`)
    lines.push("", "| state | check | subagent | expected | actual | |", "|---|---|---|---|---|---|")
    for (const c of a.checks) {
      const state = c.resumeOf ? `${STATE_TITLES[c.state]} (was ${STATE_TITLES[c.resumeOf]})` : STATE_TITLES[c.state]
      lines.push(`| ${state} | ${c.check} | ${c.subagentId ? `\`${c.subagentId.slice(0, 18)}\`` : ""} | ${esc(c.expected)} | ${esc(c.actual)}${c.note ? ` _(${esc(c.note)})_` : ""} | ${c.pass ? "pass" : "**FAIL**"} |`)
    }
  }
  return lines.join("\n") + "\n"
}

async function main() {
  const args = parseArgs(process.argv.slice(2))
  const startedAt = new Date().toISOString()
  const versions = Object.fromEntries(await Promise.all(args.agents.map(async a => [a, await cliVersion(args.commands[a] ?? DEFAULT_COMMANDS[a])] as const))) as Record<AgentId, string | undefined>
  let state = readVersionState(args.versionsFile)
  const toRun: AgentId[] = []
  const reports: AgentReport[] = []
  for (const agent of args.agents) {
    const skipped = args.ifChanged ? skipReason(state, agent, versions[agent]) : undefined
    if (skipped) { reports.push({ agent, ...(versions[agent] ? { version: versions[agent] } : {}), skipped, pass: true, checks: [] }); console.log(`[${agent}] skipped: ${skipped}`) }
    else toRun.push(agent)
  }
  const root = mkdtempSync(join(tmpdir(), "supermux-truth-"))
  if (toRun.length) console.log(`subagent truth table: ${toRun.join(", ")} (scratch ${root})`)
  const run = (agent: AgentId) => runAgent(agent, args, versions[agent], root)
  const ran = args.serial
    ? await toRun.reduce<Promise<AgentReport[]>>(async (acc, agent) => [...await acc, await run(agent)], Promise.resolve([]))
    : await Promise.all(toRun.map(run))
  reports.push(...ran)
  for (const r of ran) if (r.pass && r.version) state = recordPass(state, r.agent, r.version)
  if (ran.some(r => r.pass)) writeVersionState(args.versionsFile, state)
  const order = (a: AgentReport) => args.agents.indexOf(a.agent)
  reports.sort((a, b) => order(a) - order(b))
  const report = { startedAt, finishedAt: new Date().toISOString(), pass: reports.every(r => r.pass), versions, versionsFile: args.versionsFile, scratch: root, agents: reports }
  const out = args.out ?? root
  mkdirSync(out, { recursive: true })
  writeFileSync(join(out, "report.json"), JSON.stringify(report, null, 2) + "\n")
  const md = markdown(report)
  writeFileSync(join(out, "report.md"), md)
  console.log("\n" + md)
  console.log(`report: ${join(out, "report.md")} (+ report.json)`)
  process.exit(report.pass ? 0 : 1)
}

await main()
