/**
 * Session context in flight (slice C1b) live check: real CLIs driven through `session.updateContext`.
 *
 *   bun scripts/context-live-update.ts [claude] [codex] [cursor] [grok] [opencode]
 *
 * Cursor (model Auto; CONTEXT_LIVE_CURSOR_MODEL overrides) has no skills / plugins channel: its
 * session starts without skills, and adding a skills folder or a plugin must be refused
 * (`context_unsupported`). CONTEXT_LIVE_DIR overrides the scratch root.
 *
 * Per agent: a session starts with context A (a skills folder + an MCP server), answers one turn,
 * then MID-SESSION gets a second skills folder and a plugin (the next turn must return both
 * tokens), then a second MCP server (the next turn must return its token plus the earlier MCP
 * word: the conversation is kept); one updateContext per item, each with its own probe token. The `how` of each change is checked
 * against the table (EXPECTED below). Then the added MCP server is removed again and must be gone
 * (no new call in its own log, its process stopped where the agent stops it). Instructions are
 * fixed at creation: the session's original instruction token must still answer after the
 * changes (reloads included) and after a core restart + resume.
 *
 *   --quick: only that instruction check (create with instructions, add a skills folder, ask the
 *   instruction token, restart the core, resume, ask again).
 * Claude: a live change must NOT restart the process (same keeper agentPid).
 *
 * Scratch (workdirs, agent homes, core state) under ~/.cache/context-c1b/run-<stamp>/; credentials
 * are only READ from the user's homes (same scheme as scripts/context-live.ts). The real homes are
 * snapshotted before and after. Every child is shut down.
 */
import { spawnSync } from "node:child_process"
import { chmodSync, copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from "node:fs"
import { homedir } from "node:os"
import { join } from "node:path"
import { createCore } from "../src/index.js"
import type { AgentDriver, Core, CoreEvent, UpdateContextResult } from "../src/index.js"
import { claude } from "../src/claude/index.js"
import { codex } from "../src/codex/index.js"
import { cursor, grok, opencode } from "../src/agents/index.js"
import { prepareCursorEnvironment } from "../src/environment/index.js"
import { codexTokenArgs, CODEX_TOKEN_ENV } from "../src/accounts/adapters/codex.js"
import type { ContextUpdateHow, ExternalMcpServer } from "../src/context/types.js"

const HOME = homedir()
const RUN = join(process.env.CONTEXT_LIVE_DIR || join(HOME, ".cache", "context-c1b"), `run-${new Date().toISOString().replace(/[:.]/g, "-")}`)
const MCP_SERVER = join(HOME, ".cache", "context-c0", "mcp", "probe-mcp-server.mjs")
const LIMITS = { interruptTimeoutMs: 10_000, maxPending: 16, outstandingActivity: 256 }
const KEEPER = { parkedDeadlineMs: 60_000, journalMaxBytes: 8_000_000, connectTimeoutMs: 15_000 }
const TIMEOUTS = { setupTimeoutMs: 120_000, shutdownTimeoutMs: 5_000, maxFrameBytes: 32 << 20 }
const ACP = { ...TIMEOUTS, maxOutstandingActivity: 256, cancelRetryIntervalMs: 500, cancelRetryTimeoutMs: 10_000, inheritEnv: false }
const ALL = ["claude", "codex", "cursor", "grok", "opencode"] as const
type Agent = typeof ALL[number]
const wanted = (process.argv.slice(2).filter(a => (ALL as readonly string[]).includes(a)) as Agent[])
const agents: Agent[] = wanted.length ? wanted : [...ALL]

/** The table under test: how each change must be applied, per agent. */
const EXPECTED: Record<Agent, { skill: ContextUpdateHow; plugin: ContextUpdateHow; mcp: ContextUpdateHow; removeMcp: ContextUpdateHow }> = {
  claude: { skill: "live", plugin: "live", mcp: "live", removeMcp: "live" },
  codex: { skill: "live", plugin: "live", mcp: "reload", removeMcp: "reload" },
  cursor: { skill: "unsupported", plugin: "unsupported", mcp: "reload", removeMcp: "reload" },
  grok: { skill: "reload", plugin: "reload", mcp: "reload", removeMcp: "reload" },
  opencode: { skill: "reload", plugin: "reload", mcp: "reload", removeMcp: "reload" },
}
const QUICK = process.argv.includes("--quick")
const ASK_INSTR = "What is the instruction probe token given in your system prompt or instructions? Reply with the token only. Do not use any tools."

type Line = { agent: string; check: string; ok: boolean; evidence: unknown }
const results: Line[] = []
function record(agent: string, check: string, ok: boolean, evidence: unknown) {
  results.push({ agent, check, ok, evidence })
  console.log(`${ok ? "PASS" : "FAIL"}  ${agent.padEnd(8)} ${check} :: ${JSON.stringify(evidence).slice(0, 500)}`)
}

const NOUNS = ["MAPLE", "OTTER", "QUARTZ", "FALCON", "CEDAR", "BISON", "COBALT", "HERON", "TUNDRA", "LYNX", "EMBER", "WALRUS", "SAFFRON", "GLACIER", "MARLIN", "PEBBLE", "ORCHID", "JACKAL"]
let noun = Math.floor(Math.random() * NOUNS.length)
const word = () => `${NOUNS[noun++ % NOUNS.length]}${Math.floor(Math.random() * 9000 + 1000)}`
const has = (reply: string, w: string) => reply.toUpperCase().includes(w)

function baseEnv(): Record<string, string> {
  const env: Record<string, string> = {}
  for (const [k, v] of Object.entries(process.env)) {
    if (v === undefined || /^(MUX_|CLAUDECODE|CLAUDE_CODE_|CLAUDE_CONFIG_DIR|CODEX_|CURSOR_|GROK_|OPENCODE|XDG_|ANTHROPIC_|OPENAI_|XAI_)/.test(k)) continue
    env[k] = v
  }
  return env
}

// ---------------------------------------------------------------- snapshots of the real homes
const REAL = [".claude", ".codex", ".cursor", ".grok", ".config/opencode", ".local/share/opencode", ".config/cursor", ".agents"].map(p => join(HOME, p))
function snapshot(): Map<string, number> {
  const out = new Map<string, number>()
  const walk = (dir: string, depth: number) => {
    let entries: string[]
    try { entries = readdirSync(dir) } catch { return }
    for (const name of entries) {
      const path = join(dir, name)
      if (/\/(projects|sessions|log|logs|shell-snapshots|todos|statsig|file-history|debug|telemetry)(\/|$)/.test(path)) continue
      try { const st = statSync(path); out.set(path, st.mtimeMs); if (st.isDirectory() && depth < 3) walk(path, depth + 1) } catch { /* vanished */ }
    }
  }
  for (const root of REAL) walk(root, 1)
  return out
}

// ---------------------------------------------------------------- fixtures
function scratchRepo(dir: string): string {
  mkdirSync(dir, { recursive: true })
  writeFileSync(join(dir, "README.md"), "scratch repo for the context update live check\n")
  spawnSync("git", ["init", "-q"], { cwd: dir })
  spawnSync("git", ["-c", "user.email=probe@example.invalid", "-c", "user.name=probe", "add", "."], { cwd: dir })
  spawnSync("git", ["-c", "user.email=probe@example.invalid", "-c", "user.name=probe", "commit", "-qm", "init"], { cwd: dir })
  return dir
}
function skill(root: string, name: string, w: string) {
  mkdirSync(join(root, name), { recursive: true })
  writeFileSync(join(root, name, "SKILL.md"), `---\nname: ${name}\ndescription: Use when the user asks for the ${name} probe token.\n---\n\n# ${name}\n\nThe probe token of the ${name} skill is ${w}. When this skill is used, reply with exactly that token and nothing else.\n`)
}
function plugin(dir: string, name: string, w: string): string {
  for (const m of [".claude-plugin", ".cursor-plugin", ".codex-plugin"]) {
    mkdirSync(join(dir, m), { recursive: true })
    writeFileSync(join(dir, m, "plugin.json"), JSON.stringify({ name, version: "0.0.1", description: `Context update plugin ${name}`, ...(m !== ".claude-plugin" ? { skills: "./skills/" } : {}) }, null, 2))
  }
  skill(join(dir, "skills"), `${name}-skill`, w)
  return dir
}
const server = (dir: string, name: string, w: string): ExternalMcpServer => ({ name, command: process.execPath, args: [MCP_SERVER], env: { PROBE_LOG: join(dir, `mcp-${name}.log`), PROBE_SERVER: name, PROBE_WORD: w } })
function mcpLog(dir: string, name: string): Array<{ event: string; pid: number; method?: string }> {
  const file = join(dir, `mcp-${name}.log`)
  return existsSync(file) ? readFileSync(file, "utf8").trim().split("\n").filter(Boolean).map(line => JSON.parse(line)) : []
}
const alive = (pid: number) => { try { process.kill(pid, 0); return true } catch { return false } }

const askSkill = (name: string) => `Use the skill named "${name}" (it may appear with a plugin prefix, e.g. "something:${name}") and reply with the probe token it gives.`
const askTool = (name: string) => `Call the MCP tool "get_code_word" of the MCP server "${name}" and reply with the code word it returns.`

// ---------------------------------------------------------------- drivers (as in context-live.ts)
type Setup = { driver: AgentDriver; profiles?: Record<string, { agent: string; env?: Record<string, string>; args?: string[] }>; authProfile?: string; keeperDir: string }

async function setup(agent: Agent, dir: string): Promise<Setup> {
  const keeperDir = join(dir, "keeper")
  const keeper = { stateDirectory: keeperDir, limits: KEEPER }
  if (agent === "claude") {
    const token = JSON.parse(readFileSync(join(HOME, ".claude", ".credentials.json"), "utf8"))?.claudeAiOauth?.accessToken
    if (!token) throw new Error("no Claude access token")
    const config = join(dir, "claude-config"); mkdirSync(config, { recursive: true })
    return {
      keeperDir,
      driver: claude({
        id: "claude", command: "claude", args: [], inheritEnv: false, env: { ...baseEnv(), CLAUDE_CONFIG_DIR: config, CLAUDE_CODE_OAUTH_TOKEN: token },
        tools: "default", permissionPrompts: "none", permissions: { kind: "claude", permissionMode: "bypassPermissions" }, partialMessages: false,
        model: "haiku", ...TIMEOUTS, requestTimeoutMs: 60_000, keeper,
      }),
    }
  }
  if (agent === "codex") {
    const tokens = JSON.parse(readFileSync(join(HOME, ".codex", "auth.json"), "utf8"))?.tokens
    if (!tokens?.access_token || !tokens?.account_id) throw new Error("no Codex ChatGPT tokens")
    const codexHome = join(dir, "codex-home"), home = join(dir, "home")
    mkdirSync(codexHome, { recursive: true }); mkdirSync(home, { recursive: true })
    return {
      keeperDir,
      driver: codex({
        id: "codex", command: "codex", args: ["app-server"], inheritEnv: false, env: { ...baseEnv(), HOME: home, CODEX_HOME: codexHome },
        sandbox: "read-only", approvalPolicy: "never", permissionPrompts: "host", permissions: { kind: "codex", approvalPolicy: "never", sandbox: "read-only" },
        model: "gpt-5.6-luna", reasoningEffort: "low", ...TIMEOUTS, requestTimeoutMs: 120_000, keeper,
      }),
      profiles: { "codex-token": { agent: "codex", env: { [CODEX_TOKEN_ENV]: tokens.access_token }, args: codexTokenArgs(tokens.account_id) } },
      authProfile: "codex-token",
    }
  }
  if (agent === "grok") {
    const home = join(dir, "home"); mkdirSync(join(home, ".grok"), { recursive: true })
    writeFileSync(join(home, ".grok", "config.toml"), "[cli]\nauto_update = false\n\n[claude_compat]\nimported = true\n")
    const auth = join(dir, "grok-auth.json"); copyFileSync(join(HOME, ".grok", "auth.json"), auth); chmodSync(auth, 0o600)
    return {
      keeperDir,
      driver: grok({
        id: "grok", command: "grok", commandArgs: [], noLeader: true, reasoningEffort: "low", authPath: auth, env: { ...baseEnv(), HOME: home },
        mcpServers: [], permissions: { kind: "acp", policy: "auto-approve", nativeMode: null }, ...ACP, keeper,
      }),
    }
  }
  if (agent === "cursor") {
    // A session-private HOME with a copy of the Cursor credentials (never --model against the real HOME).
    const home = join(dir, "home")
    await prepareCursorEnvironment({
      home, workdir: join(dir, "work"), mcpServers: [], skillsPaths: [], instructions: null, sharedRuntime: null, platform: process.platform,
      credentials: { apiKey: null, userCursorDir: join(HOME, ".cursor"), userConfigDir: join(HOME, ".config") },
    })
    return {
      keeperDir,
      driver: cursor({
        id: "cursor", command: "cursor-agent", commandArgs: [], env: { ...baseEnv(), HOME: home }, model: process.env.CONTEXT_LIVE_CURSOR_MODEL || "auto",
        mcpServers: [], permissions: { kind: "acp", policy: "auto-approve", nativeMode: null }, ...ACP, keeper,
      }),
    }
  }
  const xdg = { XDG_CONFIG_HOME: join(dir, "xdg-config"), XDG_DATA_HOME: join(dir, "xdg-data"), XDG_STATE_HOME: join(dir, "xdg-state"), XDG_CACHE_HOME: join(dir, "xdg-cache") }
  for (const path of Object.values(xdg)) mkdirSync(path, { recursive: true })
  mkdirSync(join(xdg.XDG_DATA_HOME, "opencode"), { recursive: true })
  copyFileSync(join(HOME, ".local", "share", "opencode", "auth.json"), join(xdg.XDG_DATA_HOME, "opencode", "auth.json"))
  chmodSync(join(xdg.XDG_DATA_HOME, "opencode", "auth.json"), 0o600)
  return {
    keeperDir,
    driver: opencode({
      id: "opencode", command: "opencode", env: { ...baseEnv(), ...xdg, HOME: join(dir, "home") }, model: "opencode-go/qwen3.7-plus",
      mcpServers: [], permissions: { kind: "acp", policy: "auto-approve", nativeMode: null }, ...ACP, keeper,
    }),
  }
}

// ---------------------------------------------------------------- one agent
async function check(agent: Agent) {
  const dir = join(RUN, agent)
  const work = scratchRepo(join(dir, "work"))
  const state = join(dir, "state")
  const s = await setup(agent, dir)
  const id = `upd-${agent}`
  const W = { skillA: word(), mcpA: word(), skillB: word(), plugin: word(), mcp2: word(), instr: word() }
  const names = { skillA: `upd-skill-a-${agent}`, skillB: `upd-skill-b-${agent}`, plugin: `upd-plugin-${agent}` }
  const skillsA = join(dir, "skills-a"), skillsB = join(dir, "skills-b")
  skill(skillsA, names.skillA, W.skillA)
  skill(skillsB, names.skillB, W.skillB)
  const pluginDir = plugin(join(dir, "plugins", names.plugin), names.plugin, W.plugin)
  const events: CoreEvent[] = []
  const open = (): Core => {
    const created = createCore({ stateDirectory: state, agents: [s.driver], limits: LIMITS, ...(s.profiles ? { profiles: s.profiles } : {}) })
    created.subscribe(e => { events.push(e) })
    return created
  }
  let core = open()
  const agentPid = (): number | undefined => { try { return JSON.parse(readFileSync(join(s.keeperDir, "keepers", id, "status.json"), "utf8")).agentPid } catch { return undefined } }
  const ask = async (text: string, timeoutMs = 300_000): Promise<string> => {
    const session = core.sessions.live(id)!
    const start = events.length
    const receipt = await session.send({ content: [{ type: "text", text }], whenBusy: "reject" })
    const completion = await Promise.race([receipt.completed, new Promise<never>((_, reject) => setTimeout(() => reject(new Error("turn timed out")), timeoutMs))])
    if (completion.status !== "completed") throw new Error(`turn ${completion.status}: ${completion.status === "failed" ? completion.error.message : ""}`)
    await new Promise(resolve => setTimeout(resolve, 200))
    return events.slice(start).flatMap(e => e.type === "session.event" && e.sessionId === id && e.event.kind === "assistant-message" && !(e.event as { subagentId?: string }).subagentId ? [e.event.text] : []).join("\n").trim()
  }
  const update = async (label: string, run: () => Promise<UpdateContextResult>, expected: ContextUpdateHow) => {
    const pidBefore = agentPid()
    try {
      const result = await run()
      const hows = result.applied.map(entry => entry.how)
      record(agent, `${label}: how = ${expected}`, hows.length > 0 && hows.every(how => how === expected), { applied: result.applied, effective: result.effective, pidBefore, pidAfter: agentPid() })
      return result
    } catch (error) {
      const code = (error as { code?: string }).code
      record(agent, `${label}: how = ${expected}`, expected === "unsupported" && code === "context_unsupported", { code, message: (error as Error).message })
      return undefined
    }
  }
  try {
    record(agent, "capabilities.contextUpdate", true, core.capabilities(agent).contextUpdate)
    const instructions = `Session instructions. The instruction probe token is ${W.instr}. When asked for the instruction probe token, reply with it.`
    await core.sessions.create({ id, agent, cwd: work, context: { instructions, ...(agent === "cursor" ? {} : { skills: [skillsA] }), mcpServers: [server(dir, "ctxprobe", W.mcpA)] }, ...(s.authProfile ? { authProfile: s.authProfile } : {}) })
    const live = () => core.sessions.live(id)!
    const askInstructions = async (label: string) => {
      try {
        const start = events.length
        const reply = await ask(ASK_INSTR)
        // A token the agent searched for or read from a file does not count.
        const tools = events.slice(start).flatMap(e => e.type === "session.event" && e.event.kind === "tool-call" && e.event.category !== "mcp" ? [`${e.event.category}:${e.event.title ?? e.event.tool}`] : [])
        record(agent, label, has(reply, W.instr) && tools.length === 0, { word: W.instr, reply, pid: agentPid(), ...(tools.length ? { toolCalls: tools } : {}) })
      } catch (error) { record(agent, label, false, { error: (error as Error).message }) }
    }
    const restartAndAsk = async () => {
      await core.close({ agents: "shutdown" })
      core = open()
      await core.sessions.resume(id)
      await askInstructions("after a core restart + resume: the ORIGINAL instruction token")
    }
    if (QUICK) {
      // The first turn never shows the token, so a later answer can only come from the instructions.
      try { await ask("Reply with OK only. Do not use any tools.") } catch (error) { record(agent, "first turn", false, { error: (error as Error).message }) }
      await update("add skills folder", () => live().updateContext({ skills: { add: [skillsB] } }), EXPECTED[agent].skill)
      await askInstructions("after the change: the ORIGINAL instruction token")
      await restartAndAsk()
      return
    }
    try {
      const reply = await ask(`${askTool("ctxprobe")} Reply with the code word only. Do not run shell commands.`)
      record(agent, "context A: MCP word before any change", has(reply, W.mcpA), { word: W.mcpA, reply })
    } catch (error) { record(agent, "context A: first turn", false, { error: (error as Error).message }) }
    const pidStart = agentPid()
    await update("add skills folder", () => live().updateContext({ skills: { add: [skillsB] } }), EXPECTED[agent].skill)
    await update("add plugin", () => live().updateContext({ plugins: { add: [pluginDir] } }), EXPECTED[agent].plugin)
    // Asked BEFORE the MCP change, so a live skill/plugin is not masked by a later relaunch (one
    // turn each: a small model asked for both in one turn sometimes repeats one token).
    for (const [label, name, w] of agent === "cursor" ? [] : [["skill", names.skillB, W.skillB], ["plugin", `${names.plugin}-skill`, W.plugin]] as const) {
      try {
        const reply = await ask(`${askSkill(name)} Reply with the token only. Do not run shell commands or read files.`)
        record(agent, `next turn returns the new ${label} token`, has(reply, w), { word: w, reply, pidStart, pidNow: agentPid(), samePid: agentPid() === pidStart })
      } catch (error) { record(agent, `next turn with the new ${label}`, false, { error: (error as Error).message }) }
    }
    await update("add MCP server", () => live().updateContext({ mcpServers: { add: [server(dir, "probe2", W.mcp2)] } }), EXPECTED[agent].mcp)
    if (agent === "claude") record(agent, "live changes kept the same process (keeper agentPid)", pidStart !== undefined && agentPid() === pidStart && alive(pidStart), { pidStart, pidNow: agentPid() })
    try {
      const reply = await ask([
        "Do these two things and then reply with exactly two tokens separated by a space, in this order, and nothing else:",
        `1. ${askTool("probe2")}`,
        "2. Repeat the code word the ctxprobe MCP tool returned earlier in this conversation (do not call it again).",
        "Do not run shell commands or read files.",
      ].join("\n"))
      const got = { mcp: has(reply, W.mcp2), recall: has(reply, W.mcpA) }
      record(agent, "next turn returns the new MCP token (+ earlier word: conversation kept)", got.mcp && got.recall, { words: [W.mcp2, W.mcpA], got, reply, probe2Calls: mcpLog(dir, "probe2").filter(e => e.event === "call").length })
    } catch (error) { record(agent, "next turn with the new MCP server", false, { error: (error as Error).message }) }
    // Removal: the MCP server added mid-session.
    const before = mcpLog(dir, "probe2")
    const probe2Pids = [...new Set(before.filter(e => e.event === "start").map(e => e.pid))]
    const pidBeforeRemove = agentPid()
    await update("remove MCP server", () => live().updateContext({ mcpServers: { remove: ["probe2"] } }), EXPECTED[agent].removeMcp)
    await new Promise(resolve => setTimeout(resolve, 2000))
    const stopped = probe2Pids.map(pid => !alive(pid))
    try {
      const reply = await ask(`Call the MCP tool "get_code_word" of the MCP server "probe2" right now (a fresh call is required, an earlier result does not count). If that server or tool is not available to you, reply with the single word NONE. Do not run shell commands.`)
      const callsAfter = mcpLog(dir, "probe2").filter(e => e.event === "call").length - before.filter(e => e.event === "call").length
      record(agent, "removed MCP server is gone (no new call; its process stopped)", callsAfter === 0 && stopped.every(Boolean), { reply, callsAfterRemoval: callsAfter, probe2Pids, stopped, startsAfterRemoval: mcpLog(dir, "probe2").filter(e => e.event === "start").length - before.filter(e => e.event === "start").length })
    } catch (error) { record(agent, "removed MCP server", false, { error: (error as Error).message }) }
    if (agent === "claude") record(agent, "live removal kept the same process", agentPid() === pidBeforeRemove, { pidBeforeRemove, pidNow: agentPid() })
    await askInstructions("after the changes (and any reloads): the ORIGINAL instruction token")
    record(agent, "context.updated events", events.filter(e => e.type === "context.updated").length >= (agent === "cursor" ? 2 : 4), events.filter(e => e.type === "context.updated").map(e => (e as { applied: unknown }).applied))
    await restartAndAsk()
  } finally {
    await core.close({ agents: "shutdown" }).catch(error => console.error("close failed", error))
  }
}

mkdirSync(RUN, { recursive: true })
if (!existsSync(MCP_SERVER)) throw new Error(`MCP probe server missing: ${MCP_SERVER} (run scripts/context-probe.ts once to install it)`)
const before = snapshot()
for (const agent of agents) {
  try { await check(agent) } catch (error) { record(agent, "setup", false, { error: (error as Error).stack }) }
}
const after = snapshot()
const changed = [...after].filter(([path, mtime]) => before.get(path) !== mtime).map(([path]) => path)
const removed = [...before.keys()].filter(path => !after.has(path))
const ours = changed.filter(path => /context-c1b|upd-plugin|upd-skill|ctxprobe|probe2|supermux-skills/.test(path) || (() => { try { return statSync(path).isFile() && statSync(path).size < 1_000_000 && /context-c1b|upd-plugin|upd-skill/.test(readFileSync(path, "utf8")) } catch { return false } })())
record("all", "real agent homes untouched by this run", ours.length === 0, { changedDuringRun: changed, removed, mentioningThisRun: ours })
const leftovers = spawnSync("pgrep", ["-af", RUN], { encoding: "utf8" }).stdout.split("\n").filter(line => line.trim() && !line.includes("pgrep"))
const mcpPids = agents.flatMap(agent => ["ctxprobe", "probe2"].flatMap(name => mcpLog(join(RUN, agent), name).filter(e => e.event === "start").map(e => e.pid)))
const mcpAlive = mcpPids.filter(alive)
record("all", "no child process left running", leftovers.length === 0 && mcpAlive.length === 0, { leftovers, mcpServerPids: mcpPids.length, mcpAlive })
writeFileSync(join(RUN, "results.json"), JSON.stringify(results, null, 2))
const failed = results.filter(r => !r.ok)
console.log(`\n${results.length - failed.length}/${results.length} checks passed. Results: ${join(RUN, "results.json")}`)
process.exit(failed.length ? 1 : 0)
