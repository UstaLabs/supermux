/**
 * Session context (slice C1) live check: real CLIs driven through `core.sessions.create`.
 *
 *   bun scripts/context-live.ts [claude] [codex] [cursor] [grok] [opencode]
 *
 * Cursor (model Auto; CONTEXT_LIVE_CURSOR_MODEL overrides) has no skills / plugins channel: its
 * session runs with policy "warn" and must report both in `context.degraded`; a token the agent
 * FOUND by searching or reading files does not count (its turn's tool calls are checked).
 * CONTEXT_LIVE_DIR overrides the scratch root.
 *
 * Each agent gets one session with instructions + a skills folder + a plugin + an MCP server, each
 * carrying its own code word ("probe token"); the prompts never contain the words. Then:
 *   - the core is closed and reopened, the session resumed: it still recalls the earlier MCP word
 *     (conversation) and the instruction word (context reapplied from the record);
 *   - Codex: resume with instructions → `invalid_context` (fixed at creation; nothing launches);
 *   - policy "warn": a Codex session with a plugin that has hooks launches and emits `context.degraded`.
 *
 * Scratch (workdirs, agent homes, core state) lives under ~/.cache/context-c1/run-<stamp>/.
 * Credentials are only READ from the user's homes (same scheme as scripts/context-probe.ts):
 *   claude   CLAUDE_CODE_OAUTH_TOKEN = the current access token; CLAUDE_CONFIG_DIR private
 *   codex    token mode: access token + account id (core auth profile), CODEX_HOME + HOME private
 *   grok     GROK_AUTH_PATH = a scratch copy of ~/.grok/auth.json; HOME private
 *   opencode XDG_* private, with a copy of the API-key auth.json
 * The real homes are snapshotted (listing + mtimes) before and after. Every child is shut down.
 */
import { spawnSync } from "node:child_process"
import { chmodSync, copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from "node:fs"
import { homedir } from "node:os"
import { join } from "node:path"
import { createCore } from "../src/index.js"
import type { AgentDriver, Core, CoreEvent, Session } from "../src/index.js"
import { claude } from "../src/claude/index.js"
import { codex } from "../src/codex/index.js"
import { cursor, grok, opencode } from "../src/agents/index.js"
import { prepareCursorEnvironment } from "../src/environment/index.js"
import { codexTokenArgs, CODEX_TOKEN_ENV } from "../src/accounts/adapters/codex.js"
import type { SessionContext } from "../src/context/types.js"

const HOME = homedir()
const RUN = join(process.env.CONTEXT_LIVE_DIR || join(HOME, ".cache", "context-c1"), `run-${new Date().toISOString().replace(/[:.]/g, "-")}`)
const MCP_SERVER = join(HOME, ".cache", "context-c0", "mcp", "probe-mcp-server.mjs")
const LIMITS = { interruptTimeoutMs: 10_000, maxPending: 16, outstandingActivity: 256 }
const KEEPER = { parkedDeadlineMs: 60_000, journalMaxBytes: 8_000_000, connectTimeoutMs: 15_000 }
const TIMEOUTS = { setupTimeoutMs: 120_000, shutdownTimeoutMs: 5_000, maxFrameBytes: 32 << 20 }
const ACP = { ...TIMEOUTS, maxOutstandingActivity: 256, cancelRetryIntervalMs: 500, cancelRetryTimeoutMs: 10_000, inheritEnv: false }
const ALL = ["claude", "codex", "cursor", "grok", "opencode"] as const
type Agent = typeof ALL[number]
const wanted = (process.argv.slice(2).filter(a => (ALL as readonly string[]).includes(a)) as Agent[])
const agents: Agent[] = wanted.length ? wanted : [...ALL]

type Line = { agent: string; check: string; ok: boolean; evidence: unknown }
const results: Line[] = []
function record(agent: string, check: string, ok: boolean, evidence: unknown) {
  results.push({ agent, check, ok, evidence })
  console.log(`${ok ? "PASS" : "FAIL"}  ${agent.padEnd(8)} ${check} :: ${JSON.stringify(evidence).slice(0, 400)}`)
}

const NOUNS = ["MAPLE", "OTTER", "QUARTZ", "FALCON", "CEDAR", "BISON", "COBALT", "HERON", "TUNDRA", "LYNX", "EMBER", "WALRUS", "SAFFRON", "GLACIER", "MARLIN", "PEBBLE", "ORCHID", "JACKAL"]
let noun = Math.floor(Math.random() * NOUNS.length)
const word = () => `${NOUNS[noun++ % NOUNS.length]}${Math.floor(Math.random() * 9000 + 1000)}`
const has = (reply: string, w: string) => reply.toUpperCase().includes(w)

/** Child env without the worker shell's broker/agent variables. */
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
  writeFileSync(join(dir, "README.md"), "scratch repo for the context live check\n")
  spawnSync("git", ["init", "-q"], { cwd: dir })
  spawnSync("git", ["-c", "user.email=probe@example.invalid", "-c", "user.name=probe", "add", "."], { cwd: dir })
  spawnSync("git", ["-c", "user.email=probe@example.invalid", "-c", "user.name=probe", "commit", "-qm", "init"], { cwd: dir })
  return dir
}
function skill(root: string, name: string, w: string) {
  mkdirSync(join(root, name), { recursive: true })
  writeFileSync(join(root, name, "SKILL.md"), `---\nname: ${name}\ndescription: Use when the user asks for the ${name} probe token.\n---\n\n# ${name}\n\nThe probe token of the ${name} skill is ${w}. When this skill is used, reply with exactly that token and nothing else.\n`)
}
function plugin(dir: string, name: string, w: string, hooks = false): string {
  for (const m of [".claude-plugin", ".cursor-plugin", ".codex-plugin"]) {
    mkdirSync(join(dir, m), { recursive: true })
    writeFileSync(join(dir, m, "plugin.json"), JSON.stringify({ name, version: "0.0.1", description: `Context live plugin ${name}`, ...(m !== ".claude-plugin" ? { skills: "./skills/" } : {}) }, null, 2))
  }
  skill(join(dir, "skills"), `${name}-skill`, w)
  if (hooks) { mkdirSync(join(dir, "hooks"), { recursive: true }); writeFileSync(join(dir, "hooks", "hooks.json"), "{\"hooks\":{}}\n") }
  return dir
}

type Words = { instr: string; skill: string; plugin: string; mcp: string }
function contextFor(agent: Agent, dir: string): { context: SessionContext; words: Words; names: { skill: string; plugin: string } } {
  const words = { instr: word(), skill: word(), plugin: word(), mcp: word() }
  const skills = join(dir, "skills")
  const names = { skill: `live-skill-${agent}`, plugin: `live-plugin-${agent}-skill` }
  skill(skills, names.skill, words.skill)
  const pluginDir = plugin(join(dir, "plugins", `live-plugin-${agent}`), `live-plugin-${agent}`, words.plugin)
  return {
    words, names,
    context: {
      instructions: `Session context instructions. The instruction probe token is ${words.instr}. When asked for the instruction probe token, reply with it.`,
      skills: [skills],
      plugins: [pluginDir],
      mcpServers: [{ name: "ctxprobe", command: process.execPath, args: [MCP_SERVER], env: { PROBE_LOG: join(dir, "mcp.log"), PROBE_SERVER: "ctxprobe", PROBE_WORD: words.mcp } }],
    },
  }
}

const ASK_INSTR = "What is the instruction probe token given in your system prompt or instructions? Reply with the token only. Do not use any tools."
const askSkill = (name: string) => `Use the skill named "${name}" (it may appear with a plugin prefix, e.g. "something:${name}") and reply with the probe token it gives. Reply with the token only. Do not run shell commands or read files.`
const ASK_MCP = `Call the MCP tool "get_code_word" of the MCP server "ctxprobe" and reply with the code word it returns, and nothing else. Do not run shell commands.`
const ASK_RECALL = "Reply with exactly two tokens separated by a space: first the instruction probe token from your instructions, then the code word the ctxprobe MCP tool returned earlier in this conversation. Do not use any tools."

/** Why a token in the reply does not count: the agent searched, ran a command, or read it from a file in that turn. */
function foundByAgent(events: CoreEvent[], start: number, w: string): string | undefined {
  const calls = events.slice(start).flatMap(e => e.type === "session.event" && e.event.kind === "tool-call" ? [e.event] : [])
  const searched = calls.filter(c => c.category === "search" || c.category === "execute")
  if (searched.length) return `searched: ${searched.map(c => c.title ?? c.tool).slice(0, 4).join("; ")}`
  const read = calls.filter(c => c.category === "read" && JSON.stringify(c.output ?? "").toUpperCase().includes(w))
  return read.length ? `read it: ${read.map(c => c.title ?? c.tool).join("; ")}` : undefined
}

async function ask(session: Session, events: CoreEvent[], text: string, timeoutMs = 300_000): Promise<string> {
  const start = events.length
  const receipt = await session.send({ content: [{ type: "text", text }], whenBusy: "reject" })
  const completion = await Promise.race([receipt.completed, new Promise<never>((_, reject) => setTimeout(() => reject(new Error("turn timed out")), timeoutMs))])
  if (completion.status !== "completed") throw new Error(`turn ${completion.status}: ${completion.status === "failed" ? completion.error.message : ""}`)
  await new Promise(resolve => setTimeout(resolve, 200))
  return events.slice(start).flatMap(e => e.type === "session.event" && e.sessionId === session.id && e.event.kind === "assistant-message" && !(e.event as { subagentId?: string }).subagentId ? [e.event.text] : []).join("\n").trim()
}

// ---------------------------------------------------------------- drivers
type Setup = { driver: AgentDriver; profiles?: Record<string, { agent: string; env?: Record<string, string>; args?: string[] }>; authProfile?: string; inspect?: () => unknown }

async function setup(agent: Agent, dir: string): Promise<Setup> {
  const keeper = { stateDirectory: join(dir, "keeper"), limits: KEEPER }
  if (agent === "claude") {
    const token = JSON.parse(readFileSync(join(HOME, ".claude", ".credentials.json"), "utf8"))?.claudeAiOauth?.accessToken
    if (!token) throw new Error("no Claude access token")
    const config = join(dir, "claude-config"); mkdirSync(config, { recursive: true })
    return {
      driver: claude({
        id: "claude", command: "claude", args: [], inheritEnv: false, env: { ...baseEnv(), CLAUDE_CONFIG_DIR: config, CLAUDE_CODE_OAUTH_TOKEN: token },
        tools: "default", permissionPrompts: "none", permissions: { kind: "claude", permissionMode: "bypassPermissions" }, partialMessages: false,
        model: "haiku", ...TIMEOUTS, requestTimeoutMs: 60_000, keeper,
      }),
      inspect: () => ({ claudeConfig: readdirSync(config) }),
    }
  }
  if (agent === "codex") {
    const tokens = JSON.parse(readFileSync(join(HOME, ".codex", "auth.json"), "utf8"))?.tokens
    if (!tokens?.access_token || !tokens?.account_id) throw new Error("no Codex ChatGPT tokens")
    const codexHome = join(dir, "codex-home"), home = join(dir, "home")
    mkdirSync(codexHome, { recursive: true }); mkdirSync(home, { recursive: true })
    return {
      driver: codex({
        id: "codex", command: "codex", args: ["app-server"], inheritEnv: false, env: { ...baseEnv(), HOME: home, CODEX_HOME: codexHome },
        sandbox: "read-only", approvalPolicy: "never", permissionPrompts: "host", permissions: { kind: "codex", approvalPolicy: "never", sandbox: "read-only" },
        model: "gpt-5.6-luna", reasoningEffort: "low", ...TIMEOUTS, requestTimeoutMs: 120_000, keeper,
      }),
      // The token account as an auth profile: context args must compose with the profile's args.
      profiles: { "codex-token": { agent: "codex", env: { [CODEX_TOKEN_ENV]: tokens.access_token }, args: codexTokenArgs(tokens.account_id) } },
      authProfile: "codex-token",
      inspect: () => ({ codexHomeConfigToml: existsSync(join(codexHome, "config.toml")) ? readFileSync(join(codexHome, "config.toml"), "utf8") : null }),
    }
  }
  if (agent === "grok") {
    const home = join(dir, "home"); mkdirSync(join(home, ".grok"), { recursive: true })
    writeFileSync(join(home, ".grok", "config.toml"), "[cli]\nauto_update = false\n\n[claude_compat]\nimported = true\n")
    const auth = join(dir, "grok-auth.json"); copyFileSync(join(HOME, ".grok", "auth.json"), auth); chmodSync(auth, 0o600)
    return {
      driver: grok({
        id: "grok", command: "grok", commandArgs: [], noLeader: true, reasoningEffort: "low", authPath: auth, env: { ...baseEnv(), HOME: home },
        mcpServers: [], permissions: { kind: "acp", policy: "auto-approve", nativeMode: null }, ...ACP, keeper,
      }),
      inspect: () => ({ grokConfig: readFileSync(join(home, ".grok", "config.toml"), "utf8") }),
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
      driver: cursor({
        id: "cursor", command: "cursor-agent", commandArgs: [], env: { ...baseEnv(), HOME: home }, model: process.env.CONTEXT_LIVE_CURSOR_MODEL || "auto",
        mcpServers: [], permissions: { kind: "acp", policy: "auto-approve", nativeMode: null }, ...ACP, keeper,
      }),
      inspect: () => ({ cursorHome: readdirSync(join(home, ".cursor")) }),
    }
  }
  const xdg = { XDG_CONFIG_HOME: join(dir, "xdg-config"), XDG_DATA_HOME: join(dir, "xdg-data"), XDG_STATE_HOME: join(dir, "xdg-state"), XDG_CACHE_HOME: join(dir, "xdg-cache") }
  for (const path of Object.values(xdg)) mkdirSync(path, { recursive: true })
  mkdirSync(join(xdg.XDG_DATA_HOME, "opencode"), { recursive: true })
  copyFileSync(join(HOME, ".local", "share", "opencode", "auth.json"), join(xdg.XDG_DATA_HOME, "opencode", "auth.json"))
  chmodSync(join(xdg.XDG_DATA_HOME, "opencode", "auth.json"), 0o600)
  return {
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
  const { context, words, names } = contextFor(agent, dir)
  const s = await setup(agent, dir)
  const events: CoreEvent[] = []
  const open = (): Core => {
    const core = createCore({ stateDirectory: state, agents: [s.driver], limits: LIMITS, ...(s.profiles ? { profiles: s.profiles } : {}) })
    core.subscribe(e => { events.push(e) })
    return core
  }
  let core = open()
  try {
    record(agent, "capabilities", true, core.capabilities(agent).context)
    const warnStart = events.length
    const session = await core.sessions.create({ id: `live-${agent}`, agent, cwd: work, context, ...(agent === "cursor" ? { contextPolicy: "warn" as const } : {}), ...(s.authProfile ? { authProfile: s.authProfile } : {}) })
    if (agent === "cursor") {
      await new Promise(resolve => setTimeout(resolve, 50))
      const degraded = events.slice(warnStart).filter(e => e.type === "context.degraded") as Array<{ dropped: Array<{ kind: string }> }>
      const kinds = degraded.flatMap(e => e.dropped.map(d => d.kind)).sort()
      record(agent, "skills + plugins unsupported → context.degraded (policy warn)", JSON.stringify(kinds) === JSON.stringify(["plugins", "skills"]), degraded)
    }
    const ctxDir = join(state, "context", `live-${agent}`)
    record(agent, "context folder is core-owned", existsSync(ctxDir) && !readdirSync(work).some(n => n !== ".git" && n !== "README.md"), { ctxDir, files: readdirSync(ctxDir), workdir: readdirSync(work) })
    const step = async (label: string, prompt: string, w: string) => {
      try {
        const start = events.length
        const reply = await ask(session, events, prompt)
        const found = foundByAgent(events, start, w)
        record(agent, label, has(reply, w) && !found, { word: w, reply, ...(found ? { foundByAgent: found } : {}) })
      } catch (error) { record(agent, label, false, { word: w, error: (error as Error).message }) }
    }
    await step("instructions", ASK_INSTR, words.instr)
    if (agent !== "cursor") {
      await step("skill", askSkill(names.skill), words.skill)
      await step("plugin", askSkill(names.plugin), words.plugin)
    }
    await step("mcp server", ASK_MCP, words.mcp)
    // Restart: close the whole core, reopen, resume from the record.
    await core.close({ agents: "shutdown" })
    core = open()
    const resumed = await core.sessions.resume(`live-${agent}`)
    try {
      const start = events.length
      const reply = await ask(resumed, events, ASK_RECALL)
      const found = foundByAgent(events, start, words.instr)
      record(agent, "resume after core restart (instructions reapplied + conversation kept)", has(reply, words.instr) && has(reply, words.mcp) && !found, { words: [words.instr, words.mcp], reply, ...(found ? { foundByAgent: found } : {}) })
    } catch (error) { record(agent, "resume after core restart", false, { error: (error as Error).message }) }
    if (agent === "codex") {
      await core.sessions.close(`live-${agent}`, { mode: "shutdown" })
      const before = events.length
      const error = await core.sessions.resume(`live-${agent}`, { context: { ...context, instructions: "Changed instructions." } as never }).then(() => undefined, e => e)
      record(agent, "resume with instructions → invalid_context (fixed at creation)", error?.code === "invalid_context" && !events.slice(before).some(e => e.type === "session.resumed"), { code: error?.code, message: error?.message })
      const hooked = plugin(join(dir, "plugins", "hooked"), "hooked-plugin", word(), true)
      const warnEvents = events.length
      const warn = await core.sessions.create({ id: "live-codex-warn", agent, cwd: work, contextPolicy: "warn", context: { plugins: [hooked] }, ...(s.authProfile ? { authProfile: s.authProfile } : {}) })
      await new Promise(resolve => setTimeout(resolve, 50))
      const degraded = events.slice(warnEvents).filter(e => e.type === "context.degraded")
      record(agent, "policy warn → launches + context.degraded", warn.snapshot().state === "idle" && degraded.length === 1, degraded)
      const strict = await core.sessions.create({ id: "live-codex-error", agent, cwd: work, context: { plugins: [hooked] }, ...(s.authProfile ? { authProfile: s.authProfile } : {}) }).then(() => undefined, e => e)
      record(agent, "policy error → context_unsupported before launch", strict?.code === "context_unsupported" && !existsSync(join(state, "context", "live-codex-error")), { code: strict?.code, message: strict?.message })
    }
    record(agent, "mcp log", true, existsSync(join(dir, "mcp.log")) ? readFileSync(join(dir, "mcp.log"), "utf8").split("\n").filter(l => l.includes('"call"') || l.includes("initialize")).length : 0)
    if (s.inspect) record(agent, "private home after the run", true, s.inspect())
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
const ours = changed.filter(path => /context-c1|live-plugin|live-skill|ctxprobe|supermux-skills/.test(path) || (() => { try { return statSync(path).isFile() && statSync(path).size < 1_000_000 && /context-c1|ctxprobe|live-plugin/.test(readFileSync(path, "utf8")) } catch { return false } })())
record("all", "real agent homes untouched by this run", ours.length === 0, { changedDuringRun: changed, removed, mentioningThisRun: ours })
writeFileSync(join(RUN, "results.json"), JSON.stringify(results, null, 2))
const failed = results.filter(r => !r.ok)
console.log(`\n${results.length - failed.length}/${results.length} checks passed. Results: ${join(RUN, "results.json")}`)
process.exit(failed.length ? 1 : 0)
