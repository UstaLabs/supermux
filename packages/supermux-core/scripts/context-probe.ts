/**
 * Session-context live probe (slice C0): verify each per-session context channel against the REAL
 * CLIs on this host. Every "yes" is proven by the agent repeating a code word that only the
 * mechanism under test could have given it.
 *
 *   bun scripts/context-probe.ts [claude] [codex] [cursor] [grok] [opencode] [--only N[,N]] [--cursor-model M]
 *
 * Env: CONTEXT_PROBE_CURSOR_MODEL (or --cursor-model) overrides Cursor's model (a free plan only
 * accepts "auto"); CONTEXT_PROBE_DIR overrides the scratch root (default ~/.cache/context-c0).
 *
 * Items: 1 instructions · 2 skills · 3 plugins · 4 MCP (+ initialize log, subagent) ·
 *        5 tools/list_changed · 6 add skill/plugin mid-session · 7 add MCP server mid-session ·
 *        8 change instructions mid-session · 9 reload (new process, session/load) keeps the conversation.
 *
 * Scratch lives under ~/.cache/context-c0/run-<stamp>/ (workdirs are scratch git repos, every
 * agent home is session-private). The MCP probe server (scripts/context-probe/probe-mcp-server.mjs)
 * runs from ~/.cache/context-c0/mcp, where @modelcontextprotocol/server v2 is installed (never in
 * package.json). Credentials are only READ from the user's homes:
 *   claude   CLAUDE_CODE_OAUTH_TOKEN = the current access token (no credential file is written)
 *   codex    token mode: access token + account id in env, custom model provider (no auth.json)
 *   cursor   prepareCursorEnvironment copies auth.json into the session HOME (called once per home)
 *   grok     GROK_AUTH_PATH = a scratch copy of ~/.grok/auth.json
 *   opencode XDG_DATA_HOME = scratch, with a copy of the API-key auth.json
 * Every child is killed at the end. Results: stdout lines + <run>/results.json + per-agent frame logs.
 */
import { spawn, spawnSync, type ChildProcess } from "node:child_process"
import { appendFileSync, chmodSync, copyFileSync, existsSync, mkdirSync, readFileSync, symlinkSync, writeFileSync } from "node:fs"
import { homedir } from "node:os"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"
import {
  prepareClaudeEnvironment, prepareCodexEnvironment, prepareCursorEnvironment, prepareGrokEnvironment, prepareOpenCodeEnvironment,
  type McpServerSpec,
} from "../src/environment/index.js"
import { codexTokenArgs, CODEX_TOKEN_ENV } from "../src/accounts/adapters/codex.js"

// ---------- setup ----------
const HOME = homedir()
const C0 = process.env.CONTEXT_PROBE_DIR || join(HOME, ".cache", "context-c0")
const RUN = join(C0, `run-${new Date().toISOString().replace(/[:.]/g, "-")}`)
const MCP_DIR = join(C0, "mcp")
const HERE = dirname(fileURLToPath(import.meta.url))
const BUN = process.execPath
const argv = process.argv.slice(2)
const onlyArg = argv.find(a => a.startsWith("--only"))
const onlyValue = onlyArg?.includes("=") ? onlyArg.split("=")[1] : onlyArg ? argv[argv.indexOf(onlyArg) + 1] : undefined
const ONLY = onlyValue ? new Set(onlyValue.split(",").map(Number)) : undefined
const AGENTS = ["claude", "codex", "cursor", "grok", "opencode"] as const
type Agent = typeof AGENTS[number]
const wanted = argv.filter(a => (AGENTS as readonly string[]).includes(a)) as Agent[]
const want = (n: number) => !ONLY || ONLY.has(n)

/** The first-prompt instructions block: an embedded resource (default) or a text block, both for the assistant only. */
const shapeArg = argv.find(a => a.startsWith("--instructions-shape"))
const INSTRUCTIONS_SHAPE = (shapeArg?.includes("=") ? shapeArg.split("=")[1] : shapeArg ? argv[argv.indexOf(shapeArg) + 1] : "resource") === "text" ? "text" : "resource"
function instructionsBlock(text: string): unknown {
  return INSTRUCTIONS_SHAPE === "resource"
    ? { type: "resource", resource: { uri: "supermux://instructions", mimeType: "text/markdown", text }, annotations: { audience: ["assistant"] } }
    : { type: "text", text, annotations: { audience: ["assistant"] } }
}
/** Cursor's ACP model picker calls Auto "default[]" ("auto" is refused as an invalid value). */
function cursorAlias(model: string) { return model.toLowerCase() === "auto" ? "default[]" : model }
const cursorModelArg = argv.find(a => a.startsWith("--cursor-model"))
const cursorModel = cursorModelArg?.includes("=") ? cursorModelArg.split("=")[1] : cursorModelArg ? argv[argv.indexOf(cursorModelArg) + 1] : undefined
const MODELS = { claude: "haiku", codex: "gpt-5.6-luna", cursor: cursorAlias(cursorModel || process.env.CONTEXT_PROBE_CURSOR_MODEL || "gemini-3.7-flash[effort=high]"), opencode: "opencode-go/qwen3.7-plus" }

type Status = "yes" | "no" | "unproven" | "live" | "reload" | "impossible" | "info"
type Cell = { agent: Agent; item: string; cell: string; status: Status; mechanism: string; evidence: string }
const cells: Cell[] = []
function record(agent: Agent, item: string, cell: string, status: Status, mechanism: string, evidence: unknown) {
  const ev = typeof evidence === "string" ? evidence : JSON.stringify(evidence)
  // An agent out of quota proves nothing either way.
  if ((status === "no" || status === "reload") && /upgrade your plan|usage limit/i.test(ev)) { status = "unproven"; mechanism += " (agent out of quota)" }
  cells.push({ agent, item, cell, status, mechanism, evidence: ev })
  const mark = status === "yes" || status === "live" ? "PASS" : status === "info" ? "INFO" : status === "reload" ? "RELOAD" : "FAIL"
  console.log(`${mark.padEnd(6)} ${agent.padEnd(8)} ${item.padEnd(3)} ${cell} [${status}] via ${mechanism} :: ${ev.slice(0, 300).replace(/\s+/g, " ")}`)
}

const NOUNS = ["MAPLE", "OTTER", "QUARTZ", "FALCON", "CEDAR", "BISON", "COBALT", "HERON", "TUNDRA", "LYNX", "EMBER", "WALRUS", "SAFFRON", "GLACIER", "MARLIN", "PEBBLE", "ORCHID", "JACKAL", "ZINNIA", "VIOLET"]
let nounIndex = Math.floor(Math.random() * NOUNS.length)
function word(): string { return `${NOUNS[nounIndex++ % NOUNS.length]}${Math.floor(Math.random() * 9000 + 1000)}` }
const has = (reply: string | undefined, w: string) => !!reply && reply.toUpperCase().includes(w)
const sleep = (ms: number) => new Promise(r => setTimeout(r, ms))
/**
 * An ACP turn's token is only attributable to the mechanism when the agent did not find it in a file:
 * any search / shell call taints the turn; a read taints it unless `allowSkillRead` and it read a
 * SKILL.md (how Cursor loads a skill it was told about). Returns undefined when clean.
 */
function tainted(tools: Array<{ kind: string; title: string; path?: string; output: string }>, w: string, allowSkillRead = false): string | undefined {
  const searched = tools.filter(t => t.kind === "search" || t.kind === "execute")
  if (searched.length) return `agent searched (${searched.map(t => t.title || t.kind).slice(0, 4).join("; ")})`
  const reads = tools.filter(t => t.kind === "read" && t.output.toUpperCase().includes(w) && !(allowSkillRead && /SKILL\.md$/.test(t.path ?? "")))
  if (reads.length) return `agent read the token from ${reads.map(t => t.path ?? t.title).join(", ")}`
  return undefined
}

function ensureMcpSdk() {
  mkdirSync(MCP_DIR, { recursive: true })
  if (!existsSync(join(MCP_DIR, "node_modules", "@modelcontextprotocol", "server"))) {
    if (!existsSync(join(MCP_DIR, "package.json"))) writeFileSync(join(MCP_DIR, "package.json"), '{"name":"context-c0-mcp","private":true}\n')
    const r = spawnSync(BUN, ["add", "@modelcontextprotocol/server@^2", "zod@^4"], { cwd: MCP_DIR, stdio: "inherit" })
    if (r.status !== 0) throw new Error("could not install @modelcontextprotocol/server in " + MCP_DIR)
  }
  copyFileSync(join(HERE, "context-probe", "probe-mcp-server.mjs"), join(MCP_DIR, "probe-mcp-server.mjs"))
}
const SERVER = join(MCP_DIR, "probe-mcp-server.mjs")
function mcpSpec(name: string, logFile: string, w: string, late = "NONE"): McpServerSpec {
  return { name, command: BUN, args: [SERVER], env: { PROBE_LOG: logFile, PROBE_SERVER: name, PROBE_WORD: w, PROBE_LATE_WORD: late } }
}
function mcpLog(file: string): any[] {
  if (!existsSync(file)) return []
  return readFileSync(file, "utf8").split("\n").filter(Boolean).map(l => JSON.parse(l))
}
function initializeEvidence(file: string) {
  const log = mcpLog(file)
  const init = log.filter(e => e.event === "in" && e.method === "initialize").map(e => ({ protocolVersion: e.params?.protocolVersion, clientInfo: e.params?.clientInfo, capabilities: e.params?.capabilities }))
  const result = log.filter(e => e.event === "initialize-result").map(e => e.result.protocolVersion)
  const errors = log.filter(e => e.event === "out-error").map(e => e.error)
  const firstMethods = log.filter(e => e.event === "in").slice(0, 3).map(e => e.method)
  return { firstMethods, requests: init, negotiated: result, errors, processes: new Set(log.filter(e => e.event === "start").map(e => e.pid)).size }
}

/** Child env: the worker shell carries the live broker's and the parent Claude's env; strip it. */
function baseEnv(): Record<string, string> {
  const env: Record<string, string> = {}
  for (const [k, v] of Object.entries(process.env)) {
    if (v === undefined) continue
    if (/^(MUX_|CLAUDECODE|CLAUDE_CODE_|CLAUDE_CONFIG_DIR|CODEX_|CURSOR_|GROK_|OPENCODE|XDG_|ANTHROPIC_|OPENAI_|XAI_)/.test(k)) continue
    env[k] = v
  }
  return env
}

// ---------- fixtures ----------
function skill(root: string, name: string, w: string) {
  const dir = join(root, name)
  mkdirSync(dir, { recursive: true })
  writeFileSync(join(dir, "SKILL.md"), `---\nname: ${name}\ndescription: Use when the user asks for the ${name} probe token.\n---\n\n# ${name}\n\nThe probe token of the ${name} skill is ${w}. When this skill is used, reply with exactly that token and nothing else.\n`)
}
function plugin(root: string, name: string, w: string, extra?: (dir: string) => void): string {
  const dir = join(root, name)
  mkdirSync(dir, { recursive: true })
  const manifest = { name, version: "0.0.1", description: `Context probe plugin ${name}` }
  for (const m of [".claude-plugin", ".cursor-plugin"]) {
    mkdirSync(join(dir, m), { recursive: true })
    writeFileSync(join(dir, m, "plugin.json"), JSON.stringify({ ...manifest, ...(m === ".cursor-plugin" ? { skills: "./skills/" } : {}) }, null, 2))
  }
  mkdirSync(join(dir, ".codex-plugin"), { recursive: true })
  writeFileSync(join(dir, ".codex-plugin", "plugin.json"), JSON.stringify({ ...manifest, skills: "./skills/", interface: { displayName: name } }, null, 2))
  skill(join(dir, "skills"), `${name}-skill`, w)
  extra?.(dir)
  return dir
}
/** A generated plugin that only wraps a folder of skills (the design's skills channel for Claude/Cursor). */
function skillsWrapper(dir: string, name: string, skillsRoot: string): string {
  mkdirSync(dir, { recursive: true })
  for (const m of [".claude-plugin", ".cursor-plugin"]) {
    mkdirSync(join(dir, m), { recursive: true })
    writeFileSync(join(dir, m, "plugin.json"), JSON.stringify({ name, version: "0.0.1", description: "generated skills wrapper", ...(m === ".cursor-plugin" ? { skills: "./skills/" } : {}) }, null, 2))
  }
  symlinkSync(skillsRoot, join(dir, "skills"))
  return dir
}
function scratchRepo(dir: string): string {
  mkdirSync(dir, { recursive: true })
  writeFileSync(join(dir, "README.md"), "scratch repo for the context probe\n")
  spawnSync("git", ["init", "-q"], { cwd: dir })
  spawnSync("git", ["-c", "user.email=probe@example.invalid", "-c", "user.name=probe", "add", "."], { cwd: dir })
  spawnSync("git", ["-c", "user.email=probe@example.invalid", "-c", "user.name=probe", "commit", "-qm", "init"], { cwd: dir })
  return dir
}

// ---------- processes ----------
const children = new Set<ChildProcess>()
class Lines {
  child: ChildProcess
  private listeners = new Set<(msg: any) => void>()
  stderr = ""
  exited = false
  constructor(cmd: string, args: string[], opts: { cwd: string; env: Record<string, string>; frames: string }) {
    appendFileSync(opts.frames, JSON.stringify({ spawn: [cmd, ...args].map(a => a.length > 200 ? a.slice(0, 200) + "…" : a), cwd: opts.cwd }) + "\n")
    this.child = spawn(cmd, args, { cwd: opts.cwd, env: opts.env, stdio: ["pipe", "pipe", "pipe"], detached: true })
    children.add(this.child)
    let buf = ""
    this.child.stdout!.on("data", (d: Buffer) => {
      buf += d.toString("utf8")
      let i: number
      while ((i = buf.indexOf("\n")) >= 0) {
        const line = buf.slice(0, i).trim()
        buf = buf.slice(i + 1)
        if (!line) continue
        appendFileSync(opts.frames, "< " + line + "\n")
        let msg: any
        try { msg = JSON.parse(line) } catch { continue }
        this.all.push(msg)
        for (const l of this.listeners) l(msg)
      }
    })
    this.child.stderr!.on("data", (d: Buffer) => { this.stderr = (this.stderr + d.toString("utf8")).slice(-20000); appendFileSync(opts.frames, "! " + d.toString("utf8")) })
    this.child.on("exit", (code, signal) => { this.exited = true; children.delete(this.child); appendFileSync(opts.frames, JSON.stringify({ exit: code, signal }) + "\n") })
    this.frames = opts.frames
  }
  frames: string
  all: any[] = []
  on(fn: (msg: any) => void) { this.listeners.add(fn); return () => this.listeners.delete(fn) }
  write(obj: unknown) { const line = JSON.stringify(obj); appendFileSync(this.frames, "> " + line + "\n"); this.child.stdin!.write(line + "\n") }
  async kill() {
    if (this.exited) return
    try { process.kill(-this.child.pid!, "SIGTERM") } catch { /* gone */ }
    for (let i = 0; i < 30 && !this.exited; i++) await sleep(100)
    if (!this.exited) try { process.kill(-this.child.pid!, "SIGKILL") } catch { /* gone */ }
  }
}

/** JSON-RPC 2.0 over Lines (Codex app-server and ACP). */
class Rpc {
  private id = 0
  private pending = new Map<number, { resolve: (v: any) => void; reject: (e: Error) => void }>()
  notifications: Array<{ method: string; params: any }> = []
  onNotify?: (method: string, params: any) => void
  onRequest: (method: string, params: any) => unknown = () => { throw new Error("unsupported") }
  constructor(public lines: Lines) {
    lines.on(msg => {
      if (msg.id !== undefined && msg.method === undefined) {
        const p = this.pending.get(msg.id)
        if (!p) return
        this.pending.delete(msg.id)
        msg.error ? p.reject(Object.assign(new Error(JSON.stringify(msg.error)), { rpc: msg.error })) : p.resolve(msg.result)
      } else if (msg.method && msg.id !== undefined) {
        Promise.resolve().then(() => this.onRequest(msg.method, msg.params)).then(
          result => lines.write({ jsonrpc: "2.0", id: msg.id, result }),
          error => lines.write({ jsonrpc: "2.0", id: msg.id, error: { code: -32601, message: String(error?.message ?? error) } }),
        )
      } else if (msg.method) {
        this.notifications.push({ method: msg.method, params: msg.params })
        this.onNotify?.(msg.method, msg.params)
      }
    })
  }
  request(method: string, params: unknown, timeoutMs = 120_000): Promise<any> {
    const id = ++this.id
    this.lines.write({ jsonrpc: "2.0", id, method, params })
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error(`${method} timed out; stderr tail: ${this.lines.stderr.slice(-600)}`)) }, timeoutMs)
      this.pending.set(id, { resolve: v => { clearTimeout(timer); resolve(v) }, reject: e => { clearTimeout(timer); reject(e) } })
    })
  }
  notify(method: string, params: unknown) { this.lines.write({ jsonrpc: "2.0", method, params }) }
}

async function step<T>(agent: Agent, item: string, cell: string, mechanism: string, fn: () => Promise<T>): Promise<T | undefined> {
  try { return await fn() } catch (error) {
    record(agent, item, cell, "unproven", mechanism, `error: ${(error as Error).message}`)
    return undefined
  }
}

const ASK_INSTR = "What is the instruction probe token given in your system prompt or instructions? Reply with the token only. Do not use any tools."
const askSkill = (name: string) => `Use the skill named "${name}" (it may appear with a plugin prefix, e.g. "something:${name}") and reply with the probe token it gives. Reply with the token only. Do not run shell commands or read files.`
/** Cursor loads a skill by reading its SKILL.md (it refuses when told not to read files); a search still taints the turn. */
const askSkillByRead = (name: string) => `Use the skill named "${name}" (it may appear with a plugin prefix, e.g. "something:${name}") and reply with the probe token it gives. Only use a skill that is listed in your available skills; you may read that skill's own SKILL.md, but do not search for files, list directories or run shell commands. If it is not in your available skills, reply NONE. Reply with the token only.`
const askTool = (server: string, tool: string) => `Call the MCP tool "${tool}" of the MCP server "${server}" and reply with the code word it returns, and nothing else. Do not run shell commands.`
const ASK_SUB = `Spawn ONE subagent (your subagent / Task / Agent tool) and tell it: "Call the MCP tool get_code_word of the MCP server probesub and report the code word it returns." Do NOT call that tool yourself. Run the subagent in the foreground and wait for its result (do not background it). Then reply with the code word the subagent reported, or NONE if it could not call the tool.`

// =====================================================================================
// Claude: stream-json headless, control requests on stdin.
// =====================================================================================
async function probeClaude() {
  const agent: Agent = "claude"
  const creds = JSON.parse(readFileSync(join(HOME, ".claude", ".credentials.json"), "utf8"))
  const token: string | undefined = creds?.claudeAiOauth?.accessToken
  if (!token) return record(agent, "-", "setup", "unproven", "credentials", "no claudeAiOauth.accessToken")
  const dir = join(RUN, agent)
  const work = scratchRepo(join(dir, "work"))
  const frames = join(dir, "frames.log")
  const W = { instr: word(), skill: word(), pluginA: word(), pluginB: word(), mcp: word(), sub: word(), late: word(), skillNew: word(), pluginC: word(), mcp2: word(), instrFlag: word(), instrFile: word() }
  const skillsRoot = join(dir, "skills")
  skill(skillsRoot, "probe-skill-alpha", W.skill)
  const wrap = skillsWrapper(join(dir, "gen", "probe-skills"), "probe-skills", skillsRoot)
  const src = join(dir, "plugins-src")
  const pluginA = plugin(src, "probe-plugin-a", W.pluginA)
  plugin(src, "probe-plugin-b", W.pluginB)
  plugin(src, "probe-plugin-c", W.pluginC)
  const folder = join(dir, "gen", "plugins")
  mkdirSync(folder, { recursive: true })
  symlinkSync(join(src, "probe-plugin-b"), join(folder, "probe-plugin-b"))
  const log = join(dir, "mcp-probe.log"), subLog = join(dir, "mcp-probesub.log"), log2 = join(dir, "mcp-probe2.log")
  const instrFile = join(dir, "session", "instructions.md")
  mkdirSync(dirname(instrFile), { recursive: true })
  writeFileSync(instrFile, `Session probe instructions. The instruction probe token is ${W.instr}. When asked for the instruction probe token, reply with it.`)
  const prepared = await prepareClaudeEnvironment({
    home: join(dir, "session"), workdir: work, mcpServers: [mcpSpec("probe", log, W.mcp, W.late), mcpSpec("probesub", subLog, W.sub)], skillsPaths: [],
    pluginDirs: [wrap, pluginA, folder], addDirs: [], systemPromptFiles: [instrFile], strictMcp: true, nativeMemory: false,
  })
  const env = { ...baseEnv(), ...prepared.env, CLAUDE_CONFIG_DIR: join(dir, "config"), CLAUDE_CODE_OAUTH_TOKEN: token }
  mkdirSync(env.CLAUDE_CONFIG_DIR, { recursive: true })
  const args = ["-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose", "--model", MODELS.claude, "--permission-mode", "bypassPermissions", ...prepared.args!]
  const proc = new Lines("claude", args, { cwd: work, env, frames })
  let reqId = 0
  const control = (request: Record<string, unknown>, timeoutMs = 60_000): Promise<any> => new Promise((resolve, reject) => {
    const id = `probe-${++reqId}`
    const timer = setTimeout(() => { off(); reject(new Error(`control ${request.subtype} timed out`)) }, timeoutMs)
    const off = proc.on(msg => {
      if (msg.type !== "control_response" || msg.response?.request_id !== id) return
      clearTimeout(timer); off()
      msg.response.subtype === "success" ? resolve(msg.response.response ?? {}) : reject(new Error(`control ${request.subtype}: ${msg.response.error}`))
    })
    proc.write({ type: "control_request", request_id: id, request })
  })
  const turnFrames: any[][] = []
  const ask = (text: string, timeoutMs = 180_000): Promise<string> => new Promise((resolve, reject) => {
    const texts: string[] = []
    const seen: any[] = []
    const timer = setTimeout(() => { off(); reject(new Error("turn timed out; stderr: " + proc.stderr.slice(-400))) }, timeoutMs)
    const off = proc.on(msg => {
      seen.push(msg)
      if (msg.type === "assistant" && !msg.parent_tool_use_id) for (const b of msg.message?.content ?? []) if (b.type === "text") texts.push(b.text)
      if (msg.type === "result") { clearTimeout(timer); off(); turnFrames.push(seen); resolve(texts.join("\n").trim() || String(msg.result ?? "")) }
    })
    proc.write({ type: "user", message: { role: "user", content: [{ type: "text", text }] }, parent_tool_use_id: null, session_id: "" })
  })
  try {
    const init = await step(agent, "-", "initialize", "control initialize", () => control({ subtype: "initialize" }))
    if (init) record(agent, "-", "initialize", "info", "control_request initialize", { commands: (init.commands ?? []).map((c: any) => c.name).filter((n: string) => n.includes("probe")) })
    if (want(1)) { const r = await step(agent, "1", "instructions", "--append-system-prompt-file", () => ask(ASK_INSTR)); if (r !== undefined) record(agent, "1", "instructions", has(r, W.instr) ? "yes" : "no", "--append-system-prompt-file <session>/instructions.md", r) }
    if (want(2)) { const r = await step(agent, "2", "skills", "generated plugin wrapping the skills folder → --plugin-dir", () => ask(askSkill("probe-skill-alpha"))); if (r !== undefined) record(agent, "2", "skills", has(r, W.skill) ? "yes" : "no", "generated plugin (manifest + skills → symlink to the skills folder) via --plugin-dir", r) }
    if (want(3)) {
      const a = await step(agent, "3", "plugin (--plugin-dir <plugin>)", "--plugin-dir", () => ask(askSkill("probe-plugin-a-skill")))
      if (a !== undefined) record(agent, "3", "plugin (--plugin-dir <plugin>)", has(a, W.pluginA) ? "yes" : "no", "--plugin-dir <plugin folder>", a)
      const b = await step(agent, "3", "plugin (--plugin-dir <folder of plugins>)", "--plugin-dir", () => ask(askSkill("probe-plugin-b-skill")))
      if (b !== undefined) record(agent, "3", "plugin (--plugin-dir <folder of plugins>)", has(b, W.pluginB) ? "yes" : "no", "--plugin-dir <folder containing a symlinked child plugin>", b)
    }
    if (want(4)) {
      const r = await step(agent, "4", "MCP", "--mcp-config", () => ask(askTool("probe", "get_code_word")))
      if (r !== undefined) record(agent, "4", "MCP", has(r, W.mcp) ? "yes" : "no", "--mcp-config + --strict-mcp-config", r)
      record(agent, "4", "MCP initialize", initializeEvidence(log).negotiated.length ? "info" : "no", "probe server log", initializeEvidence(log))
      const mark = proc.all.length
      const s = await step(agent, "4", "MCP in subagent", "Task subagent", () => ask(ASK_SUB, 240_000))
      if (s !== undefined) {
        // A backgrounded subagent finishes after the turn's result: wait for its frames.
        const subResult = () => proc.all.slice(mark).some(m => m.type === "user" && m.parent_tool_use_id && JSON.stringify(m.message?.content ?? "").includes(W.sub))
        for (let i = 0; i < 120 && !subResult(); i++) await sleep(500)
        const frames = proc.all.slice(mark)
        const subCall = frames.some(m => m.type === "assistant" && m.parent_tool_use_id && (m.message?.content ?? []).some((b: any) => b.type === "tool_use" && String(b.name).includes("probesub")))
        const parentCall = frames.some(m => m.type === "assistant" && !m.parent_tool_use_id && (m.message?.content ?? []).some((b: any) => b.type === "tool_use" && String(b.name).includes("probesub")))
        record(agent, "4", "MCP in subagent", subResult() && subCall && !parentCall ? "yes" : "no", "subagent inherits the session's MCP servers", { reply: s, subagentToolUse: subCall, subagentGotWord: subResult(), parentToolUse: parentCall })
        if (!has(s, W.sub)) {
          // The backgrounded agent's completion starts a wake-up turn of its own; let it end first.
          const idx = proc.all.findIndex((m, i) => i >= mark && m.type === "user" && m.parent_tool_use_id && JSON.stringify(m.message?.content ?? "").includes(W.sub))
          for (let i = 0; i < 60 && !proc.all.slice(idx).some(m => m.type === "result"); i++) await sleep(500)
        }
      }
    }
    if (want(5)) {
      if (!mcpLog(log).some(e => e.method === "tools/list")) await ask("Reply with OK only. Do not use tools.") // MCP servers may start lazily
      writeFileSync(log + ".late", "")
      await sleep(2500)
      const r = await step(agent, "5", "tools/list_changed", "notifications/tools/list_changed", () => ask(askTool("probe", "get_late_code_word")))
      const relisted = mcpLog(log).filter(e => e.event === "in" && e.method === "tools/list").length
      if (r !== undefined) record(agent, "5", "tools/list_changed", has(r, W.late) ? "live" : "no", "server registers a tool + sends notifications/tools/list_changed", { reply: r, toolsListCalls: relisted })
    }
    if (want(6)) {
      skill(skillsRoot, "probe-skill-delta", W.skillNew)
      const rs = await step(agent, "6", "add skill", "reload_skills", () => control({ subtype: "reload_skills" }))
      if (rs) {
        const names = JSON.stringify(rs).match(/probe-skill-delta/g)?.length ?? 0
        const r = await step(agent, "6", "add skill", "reload_skills", () => ask(askSkill("probe-skill-delta")))
        if (r !== undefined) record(agent, "6", "add skill (new dir in a --plugin-dir plugin's skills)", has(r, W.skillNew) ? "live" : "no", "write skill → control_request {subtype:\"reload_skills\"}", { reply: r, listedInResponse: names })
      }
      symlinkSync(join(src, "probe-plugin-c"), join(folder, "probe-plugin-c"))
      const rp = await step(agent, "6", "add plugin", "reload_plugins", () => control({ subtype: "reload_plugins" }))
      if (rp) {
        const plugins = (rp.plugins ?? []).map((p: any) => p.name ?? p)
        const r = await step(agent, "6", "add plugin", "reload_plugins", () => ask(askSkill("probe-plugin-c-skill")))
        if (r !== undefined) record(agent, "6", "add plugin (new symlink in the --plugin-dir folder)", has(r, W.pluginC) ? "live" : "no", "symlink into the folder of plugins → control_request {subtype:\"reload_plugins\"}", { reply: r, plugins })
      }
    }
    if (want(7)) {
      const spec = mcpSpec("probe2", log2, W.mcp2)
      const res = await step(agent, "7", "add MCP server", "mcp_set_servers", () => control({ subtype: "mcp_set_servers", servers: { probe2: { type: "stdio", command: spec.command, args: spec.args, env: spec.env } } }))
      if (res) {
        await sleep(1500)
        const r = await step(agent, "7", "add MCP server", "mcp_set_servers", () => ask(`Call the MCP tool "get_code_word" of the MCP server "probe2" and reply with that code word. Then, on the same line, also repeat the instruction probe token from your instructions. Reply with the two tokens only.`))
        if (r !== undefined) record(agent, "7", "add MCP server", has(r, W.mcp2) && has(r, W.instr) ? "live" : "no", "control_request {subtype:\"mcp_set_servers\", servers:{probe2:{type:\"stdio\",command,args,env}}}", { reply: r, response: res, conversationKept: has(r, W.instr) })
      }
    }
    if (want(8)) {
      writeFileSync(instrFile, `Session probe instructions. The instruction probe token is ${W.instrFile}. When asked for the instruction probe token, reply with it.`)
      const res = await step(agent, "8", "change instructions", "apply_flag_settings", () => control({ subtype: "apply_flag_settings", settings: { appendSystemPrompt: `Updated probe instructions: the NEW instruction probe token is ${W.instrFlag}.` } }))
      const r = await step(agent, "8", "change instructions", "apply_flag_settings", () => ask("List every instruction probe token that appears in your system prompt or instructions right now, newest last. Reply with the tokens only. Do not use tools."))
      if (r !== undefined) {
        const status: Status = has(r, W.instrFlag) || has(r, W.instrFile) ? "live" : "reload"
        record(agent, "8", "change instructions", status, has(r, W.instrFlag) ? "apply_flag_settings {appendSystemPrompt}" : has(r, W.instrFile) ? "rewritten --append-system-prompt-file" : "neither apply_flag_settings {appendSystemPrompt} nor rewriting the file is seen → relaunch", { reply: r, applyFlagResponse: res ?? "error", flagWord: has(r, W.instrFlag), fileWord: has(r, W.instrFile), oldWord: has(r, W.instr) })
      }
    }
  } finally {
    proc.write({ type: "control_request", request_id: "end", request: { subtype: "end_session" } })
    await sleep(300)
    await proc.kill()
  }
}

// =====================================================================================
// Codex: app-server JSON-RPC, session-private CODEX_HOME and HOME, token-mode auth.
// =====================================================================================
async function probeCodex() {
  const agent: Agent = "codex"
  const auth = JSON.parse(readFileSync(join(HOME, ".codex", "auth.json"), "utf8"))
  const tokens = auth?.tokens
  if (!tokens?.access_token || !tokens?.account_id) return record(agent, "-", "setup", "unproven", "credentials", "no ChatGPT tokens")
  const dir = join(RUN, agent)
  const work = scratchRepo(join(dir, "work"))
  const frames = join(dir, "frames.log")
  const codexHome = join(dir, "codex-home"), fakeHome = join(dir, "home")
  mkdirSync(fakeHome, { recursive: true })
  const W = { instr: word(), skillExtra: word(), skillHome: word(), pluginA: word(), mcp: word(), sub: word(), late: word(), skillNew: word(), pluginC: word(), mcp2: word(), instrNew: word() }
  const log = join(dir, "mcp-probe.log"), subLog = join(dir, "mcp-probesub.log"), log2 = join(dir, "mcp-probe2.log")
  await prepareCodexEnvironment({
    home: codexHome, workdir: work, mcpServers: [mcpSpec("probe", log, W.mcp, W.late), mcpSpec("probesub", subLog, W.sub)], skillsPaths: [],
    credentials: { apiKey: null, canonicalHome: join(HOME, ".codex"), account: true }, nativeMemory: false,
  })
  // MCP tool calls need approval unless the policy can ask; child threads read the policy from config.
  const cfg = join(codexHome, "config.toml")
  writeFileSync(cfg, `approval_policy = "on-request"\nsandbox_mode = "read-only"\n` + readFileSync(cfg, "utf8"))
  const rootA = join(dir, "skills-a"), rootB = join(dir, "skills-b")
  skill(rootA, "probe-skill-alpha", W.skillExtra)
  skill(join(codexHome, "skills"), "probe-skill-home", W.skillHome)
  skill(rootB, "probe-skill-echo", W.skillNew)
  // A local marketplace in the session's own scratch tree: <root>/.agents/plugins/marketplace.json,
  // entry name == plugin manifest name, source path relative to <root>.
  const market = join(dir, "market")
  plugin(join(market, "plugins"), "probe-plugin-a", W.pluginA)
  plugin(join(market, "plugins"), "probe-plugin-c", W.pluginC)
  mkdirSync(join(market, ".agents", "plugins"), { recursive: true })
  const marketJson = join(market, ".agents", "plugins", "marketplace.json")
  writeFileSync(marketJson, JSON.stringify({
    name: "probe-mkt", interface: { displayName: "probe marketplace" },
    plugins: ["probe-plugin-a", "probe-plugin-c"].map(name => ({ name, source: { source: "local", path: `./plugins/${name}` }, policy: { installation: "AVAILABLE" } })),
  }, null, 2))
  const env = { ...baseEnv(), HOME: fakeHome, CODEX_HOME: codexHome, [CODEX_TOKEN_ENV]: tokens.access_token }
  const tokenArgs = codexTokenArgs(tokens.account_id)
  const cli = (args: string[]) => { const r = spawnSync("codex", args, { cwd: work, env, encoding: "utf8", timeout: 60_000 }); appendFileSync(frames, `$ codex ${args.join(" ")}\n${r.stdout}${r.stderr}\n`); return r }
  if (want(3)) {
    const m = cli(["plugin", "marketplace", "add", market])
    const a = cli(["plugin", "add", "probe-plugin-a@probe-mkt"])
    record(agent, "3", "plugin install (session CODEX_HOME)", m.status === 0 && a.status === 0 ? "info" : "no", "codex plugin marketplace add <local> + codex plugin add probe-plugin-a@probe-mkt (CODEX_HOME + HOME = session)", { marketplace: (m.stdout + m.stderr).trim().slice(0, 300), add: (a.stdout + a.stderr).trim().slice(0, 300) })
  }
  const approvals: string[] = []
  const launch = () => {
    const p = new Lines("codex", ["app-server", ...tokenArgs], { cwd: work, env, frames })
    const r = new Rpc(p)
    r.onRequest = (method, params) => {
      approvals.push(method)
      if (method === "mcpServer/elicitation/request") return { action: "accept", content: {} }
      if (method.endsWith("requestApproval")) return { decision: "accept" }
      throw new Error("unsupported " + method)
    }
    return { p, r }
  }
  let { p: proc, r: rpc } = launch()
  let threadId = ""
  const ask = async (text: string, timeoutMs = 180_000): Promise<string> => {
    const start = rpc.notifications.length
    await rpc.request("turn/start", { threadId, input: [{ type: "text", text, text_elements: [] }], effort: "low" })
    const t0 = Date.now()
    while (Date.now() - t0 < timeoutMs) {
      const done = rpc.notifications.slice(start).find(n => n.method === "turn/completed" && n.params?.threadId === threadId)
      if (done) {
        const texts = rpc.notifications.slice(start).filter(n => n.method === "item/completed" && n.params?.threadId === threadId && n.params?.item?.type === "agentMessage").map(n => n.params.item.text)
        if (done.params?.turn?.status === "failed") throw new Error("turn failed: " + JSON.stringify(done.params.turn.error))
        return texts.join("\n").trim()
      }
      await sleep(200)
    }
    throw new Error("turn timed out")
  }
  try {
    await rpc.request("initialize", { clientInfo: { name: "context-probe", version: "0.0.0" }, capabilities: { experimentalApi: true } })
    rpc.notify("initialized", {})
    if (want(2)) await step(agent, "2", "skills extraRoots", "skills/extraRoots/set", () => rpc.request("skills/extraRoots/set", { extraRoots: [rootA] }))
    const started = await rpc.request("thread/start", { cwd: work, model: MODELS.codex, approvalPolicy: "on-request", sandbox: "read-only", developerInstructions: `Session probe instructions. The instruction probe token is ${W.instr}. When asked for the instruction probe token, reply with it.` })
    threadId = started.thread.id
    if (want(1)) { const r = await step(agent, "1", "instructions", "developerInstructions", () => ask(ASK_INSTR)); if (r !== undefined) record(agent, "1", "instructions", has(r, W.instr) ? "yes" : "no", "app-server thread/start {developerInstructions}", r) }
    if (want(2)) {
      const listed = await step(agent, "2", "skills/list", "skills/list", () => rpc.request("skills/list", { cwds: [work], forceReload: true }))
      const names = JSON.stringify(listed ?? {}).match(/probe-[a-z-]+/g) ?? []
      const a = await step(agent, "2", "skills (extraRoots)", "skills/extraRoots/set", () => ask(askSkill("probe-skill-alpha")))
      if (a !== undefined) record(agent, "2", "skills (skills/extraRoots/set)", has(a, W.skillExtra) ? "yes" : "no", "app-server skills/extraRoots/set {extraRoots:[<folder>]} before thread/start", { reply: a, skillsList: [...new Set(names)] })
      const b = await step(agent, "2", "skills (CODEX_HOME/skills)", "CODEX_HOME/skills", () => ask(askSkill("probe-skill-home")))
      if (b !== undefined) record(agent, "2", "skills (<CODEX_HOME>/skills)", has(b, W.skillHome) ? "yes" : "no", "<session CODEX_HOME>/skills/<name>/SKILL.md", b)
    }
    if (want(3)) { const r = await step(agent, "3", "plugin", "codex plugin add", () => ask(askSkill("probe-plugin-a-skill"))); if (r !== undefined) record(agent, "3", "plugin", has(r, W.pluginA) ? "yes" : "no", "plugin installed into the session CODEX_HOME from a local marketplace", r) }
    if (want(4)) {
      const r = await step(agent, "4", "MCP", "config.toml", () => ask(askTool("probe", "get_code_word")))
      if (r !== undefined) record(agent, "4", "MCP", has(r, W.mcp) ? "yes" : "no", "session CODEX_HOME/config.toml [mcp_servers.*]", r)
      record(agent, "4", "MCP initialize", initializeEvidence(log).negotiated.length ? "info" : "no", "probe server log", initializeEvidence(log))
      const before = initializeEvidence(subLog).processes
      const s = await step(agent, "4", "MCP in subagent", "spawnAgent", () => ask(ASK_SUB, 300_000))
      if (s !== undefined) {
        const spawned = rpc.notifications.some(n => n.method === "thread/started" && n.params?.thread?.id !== threadId)
        const calls = mcpLog(subLog).filter(e => e.event === "call").length
        const childCall = rpc.notifications.some(n => n.method === "item/completed" && n.params?.threadId !== threadId && n.params?.item?.type === "mcpToolCall" && n.params?.item?.server === "probesub" && JSON.stringify(n.params.item.result ?? "").includes(W.sub))
        const parentCall = rpc.notifications.some(n => n.method === "item/started" && n.params?.threadId === threadId && n.params?.item?.type === "mcpToolCall" && n.params?.item?.server === "probesub")
        record(agent, "4", "MCP in subagent", childCall && !parentCall ? "yes" : "no", "child thread (spawnAgent) uses the session's MCP servers", { reply: s, childThreadStarted: spawned, childToolCallReturnedWord: childCall, parentToolCall: parentCall, subServerProcesses: initializeEvidence(subLog).processes - before, calls, approvals: [...new Set(approvals)] })
      }
    }
    if (want(5)) {
      if (!mcpLog(log).some(e => e.method === "tools/list")) await ask("Reply with OK only. Do not use tools.") // MCP servers may start lazily
      writeFileSync(log + ".late", "")
      await sleep(2500)
      const r = await step(agent, "5", "tools/list_changed", "list_changed", () => ask(askTool("probe", "get_late_code_word")))
      const relisted = mcpLog(log).filter(e => e.event === "in" && e.method === "tools/list").length
      if (r !== undefined) record(agent, "5", "tools/list_changed", has(r, W.late) ? "live" : "no", "server registers a tool + sends notifications/tools/list_changed", { reply: r, toolsListCalls: relisted })
      if (r !== undefined && !has(r, W.late)) {
        // Fallback without restarting Codex: config/mcpServer/reload restarts the MCP servers.
        const rl = await step(agent, "5", "tools changed via config/mcpServer/reload", "config/mcpServer/reload", () => rpc.request("config/mcpServer/reload", {}))
        if (rl !== undefined) {
          await sleep(2500)
          const r2 = await step(agent, "5", "tools changed via config/mcpServer/reload", "config/mcpServer/reload", () => ask(askTool("probe", "get_late_code_word")))
          if (r2 !== undefined) record(agent, "5", "tools changed via config/mcpServer/reload", has(r2, W.late) ? "live" : "no", "config/mcpServer/reload (re-spawns MCP servers, same thread)", { reply: r2, probeProcesses: initializeEvidence(log).processes })
        }
      }
    }
    if (want(6)) {
      const set = await step(agent, "6", "add skill", "skills/extraRoots/set", () => rpc.request("skills/extraRoots/set", { extraRoots: [rootA, rootB] }))
      if (set !== undefined) {
        const r = await step(agent, "6", "add skill", "skills/extraRoots/set", () => ask(askSkill("probe-skill-echo")))
        if (r !== undefined) record(agent, "6", "add skill (new extra root)", has(r, W.skillNew) ? "live" : "no", "skills/extraRoots/set {extraRoots:[old, new]} on the running app-server", r)
      }
      const inst = await step(agent, "6", "add plugin", "plugin/install", () => rpc.request("plugin/install", { pluginName: "probe-plugin-c", marketplacePath: marketJson }))
      if (inst !== undefined) {
        const r = await step(agent, "6", "add plugin", "plugin/install", () => ask(askSkill("probe-plugin-c-skill")))
        if (r !== undefined) record(agent, "6", "add plugin", has(r, W.pluginC) ? "live" : "no", "app-server plugin/install {pluginName, marketplacePath}", { reply: r, install: inst })
      }
    }
    if (want(7)) {
      const spec = mcpSpec("probe2", log2, W.mcp2)
      const wrote = await step(agent, "7", "add MCP server", "config/value/write", () => rpc.request("config/value/write", { keyPath: "mcp_servers.probe2", value: { command: spec.command, args: spec.args, env: spec.env }, mergeStrategy: "upsert" }))
      const reloaded = wrote !== undefined ? await step(agent, "7", "add MCP server", "config/mcpServer/reload", () => rpc.request("config/mcpServer/reload", {})) : undefined
      if (reloaded !== undefined) {
        const r = await step(agent, "7", "add MCP server", "config/mcpServer/reload", () => ask(`Call the MCP tool "get_code_word" of the MCP server "probe2" and reply with that code word. Then, on the same line, also repeat the instruction probe token from your instructions. Reply with the two tokens only.`))
        if (r !== undefined) record(agent, "7", "add MCP server", has(r, W.mcp2) && has(r, W.instr) ? "live" : "no", "config/value/write {keyPath:\"mcp_servers.probe2\", mergeStrategy:\"upsert\"} + config/mcpServer/reload", { reply: r, write: wrote, conversationKept: has(r, W.instr), probe2ReadyBeforeTurn: rpc.notifications.some(n => n.method === "mcpServer/startupStatus/updated" && n.params?.name === "probe2") })
        if (r !== undefined && !has(r, W.mcp2)) {
          // The new server starts at the next turn/start; ask once more now that it is up.
          const r2 = await step(agent, "7", "add MCP server (next turn)", "config/mcpServer/reload", () => ask(askTool("probe2", "get_code_word")))
          if (r2 !== undefined) record(agent, "7", "add MCP server (next turn)", has(r2, W.mcp2) ? "live" : "no", "same, asked again one turn later (server started during the previous turn)", r2)
        }
      }
    }
    if (want(8)) {
      const resumed = await step(agent, "8", "change instructions", "thread/resume", () => rpc.request("thread/resume", { threadId, developerInstructions: `Updated probe instructions. The NEW instruction probe token is ${W.instrNew}.` }))
      if (resumed !== undefined) {
        const r = await step(agent, "8", "change instructions", "thread/resume", () => ask("List every instruction probe token that appears in your developer instructions right now, oldest first. Reply with the tokens only. Do not use tools."))
        if (r !== undefined) record(agent, "8", "change instructions", has(r, W.instrNew) ? "live" : "no", "thread/resume {threadId, developerInstructions} on the same app-server", { reply: r, newWord: has(r, W.instrNew), oldWord: has(r, W.instr), semantics: has(r, W.instrNew) ? (has(r, W.instr) ? "adds (old one still visible)" : "replaces") : "ignored" })
      }
      // Per-turn channel: turn/start {additionalContext: {<source>: {kind, value}}}.
      const X = word(), Y = word()
      const askCtx = async (ctx: Record<string, unknown> | undefined) => {
        const start = rpc.notifications.length
        await rpc.request("turn/start", { threadId, input: [{ type: "text", text: "List every instruction probe token you can see right now in your instructions or in any application context, oldest first. Reply with the tokens only. Do not use tools.", text_elements: [] }], effort: "low", ...(ctx ? { additionalContext: ctx } : {}) })
        for (let i = 0; i < 900; i++) {
          if (rpc.notifications.slice(start).some(n => n.method === "turn/completed" && n.params?.threadId === threadId)) break
          await sleep(200)
        }
        return rpc.notifications.slice(start).filter(n => n.method === "item/completed" && n.params?.threadId === threadId && n.params?.item?.type === "agentMessage").map(n => n.params.item.text).join("\n").trim()
      }
      const c1 = await step(agent, "8", "per-turn additionalContext", "turn/start additionalContext", () => askCtx({ "supermux-instructions": { kind: "application", value: `Application probe instructions: the context instruction probe token is ${X}.` } }))
      const c2 = await step(agent, "8", "per-turn additionalContext", "turn/start additionalContext", () => askCtx(undefined))
      const c3 = await step(agent, "8", "per-turn additionalContext", "turn/start additionalContext", () => askCtx({ "supermux-instructions": { kind: "application", value: `Application probe instructions: the context instruction probe token is ${Y}.` } }))
      if (c1 !== undefined) record(agent, "8", "per-turn additionalContext", has(c1, X) ? "live" : "no", "turn/start {additionalContext:{\"supermux-instructions\":{kind:\"application\",value}}}", { withContext: c1, nextTurnWithout: c2, sameKeyNewValue: c3, seenFirst: has(c1, X), stillSeenWithout: has(c2 ?? "", X), secondSeen: has(c3 ?? "", Y) })
      // Reload: a new app-server process resumes the thread with new developerInstructions.
      await proc.kill()
      ;({ p: proc, r: rpc } = launch())
      await rpc.request("initialize", { clientInfo: { name: "context-probe", version: "0.0.0" }, capabilities: { experimentalApi: true } })
      rpc.notify("initialized", {})
      const W2 = word()
      const re = await step(agent, "8", "change instructions (new process)", "thread/resume", () => rpc.request("thread/resume", { threadId, cwd: work, model: MODELS.codex, approvalPolicy: "on-request", sandbox: "read-only", developerInstructions: `Reloaded probe instructions. The RELOADED instruction probe token is ${W2}.` }))
      if (re !== undefined) {
        const r = await step(agent, "8", "change instructions (new process)", "thread/resume", () => ask("List every instruction probe token that appears in your developer instructions right now, oldest first. Also say what code word the probe server's get_code_word tool returned earlier in this conversation. Reply with the tokens only. Do not use tools."))
        if (want(5)) {
          const r5 = await step(agent, "5", "tools changed after app-server restart", "thread/resume", () => ask(askTool("probe", "get_late_code_word")))
          if (r5 !== undefined) record(agent, "5", "tools changed after app-server restart", has(r5, W.late) ? "reload" : "no", "new app-server process + thread/resume (MCP servers re-spawned)", r5)
        }
        if (r !== undefined) record(agent, "8", "change instructions (new process)", has(r, W2) ? "reload" : "no", "new app-server process + thread/resume {threadId, developerInstructions}", { reply: r, reloadedWord: has(r, W2), firstWord: has(r, W.instr), sameProcessWord: has(r, W.instrNew), conversationKept: has(r, W.mcp), semantics: has(r, W2) ? (has(r, W.instr) || has(r, W.instrNew) ? "adds (earlier ones still visible)" : "replaces") : "ignored" })
      }
    }
  } finally {
    await proc.kill()
  }
}

// =====================================================================================
// ACP agents (cursor, grok, opencode): phase A in one process; phase B = new process,
// session/load with changed config (reload), which also proves 7/9 and the reload fallback.
// =====================================================================================
type AcpServer = { name: string; command: string; args: string[]; env: Array<{ name: string; value: string }> }
const acpMcp = (s: McpServerSpec): AcpServer => ({ name: s.name, command: s.command, args: s.args, env: Object.entries(s.env).map(([name, value]) => ({ name, value })) })

class AcpClient {
  rpc: Rpc
  sessionId = ""
  constructor(public proc: Lines) {
    this.rpc = new Rpc(proc)
    this.rpc.onRequest = (method, params) => {
      if (method === "session/request_permission") {
        const options: any[] = params?.options ?? []
        const pick = options.find(o => o.kind === "allow_always") ?? options.find(o => String(o.kind).startsWith("allow")) ?? options[0]
        return pick ? { outcome: { outcome: "selected", optionId: pick.optionId } } : { outcome: { outcome: "cancelled" } }
      }
      throw new Error("unsupported " + method)
    }
  }
  async init() { return this.rpc.request("initialize", { protocolVersion: 1, clientCapabilities: { fs: { readTextFile: false, writeTextFile: false }, terminal: false }, clientInfo: { name: "context-probe", version: "0.0.0" } }, 120_000) }
  async newSession(cwd: string, mcpServers: AcpServer[], meta?: Record<string, unknown>) { const r = await this.rpc.request("session/new", { cwd, mcpServers, ...(meta ? { _meta: meta } : {}) }, 180_000); this.sessionId = r.sessionId; return r }
  async load(sessionId: string, cwd: string, mcpServers: AcpServer[], meta?: Record<string, unknown>) { const r = await this.rpc.request("session/load", { sessionId, cwd, mcpServers, ...(meta ? { _meta: meta } : {}) }, 180_000); this.sessionId = sessionId; return r }
  async setModel(model: string) { return this.rpc.request("session/set_config_option", { sessionId: this.sessionId, configId: "model", value: model }) }
  /** Tool calls of the last turn (any session): kind, title, and every output text, to tell a token
   *  the agent was GIVEN from one it FOUND by reading or searching files. */
  turnTools: Array<{ kind: string; title: string; path?: string; output: string }> = []
  /** A separate content block sent before the next prompt's text (the first-prompt instructions). */
  preamble?: unknown
  async ask(text: string, timeoutMs = 240_000): Promise<string> {
    const start = this.rpc.notifications.length
    const prompt = [...(this.preamble ? [this.preamble] : []), { type: "text", text }]
    this.preamble = undefined
    await this.rpc.request("session/prompt", { sessionId: this.sessionId, prompt }, timeoutMs)
    await sleep(300)
    const updates = this.rpc.notifications.slice(start).filter(n => n.method === "session/update").map(n => n.params?.update ?? {})
    const byId = new Map<string, { kind: string; title: string; path?: string; output: string }>()
    for (const u of updates) {
      if (u.sessionUpdate !== "tool_call" && u.sessionUpdate !== "tool_call_update") continue
      const t = byId.get(u.toolCallId) ?? { kind: "", title: "", output: "" }
      if (u.kind) t.kind = u.kind
      if (u.title) t.title = u.title
      const path = u.rawInput?.path ?? u.locations?.[0]?.path
      if (path) t.path = path
      if (u.rawOutput !== undefined || u.content !== undefined) t.output += JSON.stringify([u.rawOutput, u.content])
      byId.set(u.toolCallId, t)
    }
    this.turnTools = [...byId.values()]
    return this.rpc.notifications.slice(start)
      .filter(n => n.method === "session/update" && n.params?.sessionId === this.sessionId && n.params?.update?.sessionUpdate === "agent_message_chunk")
      .map(n => n.params.update.content?.text ?? "").join("").trim()
  }
  updatesSince(start: number) { return this.rpc.notifications.slice(start).filter(n => n.method === "session/update").map(n => n.params) }
}

type AcpWords = { mcp: string; sub: string; late: string; mcp2: string; skill: string; skillNew: string; pluginA: string; pluginB: string; pluginC: string }
type AcpPlan = {
  agent: Agent
  /** Launch (command, args, env) for a phase; phase B gets the changed context. */
  launch: (phase: "A" | "B") => { command: string; args: string[]; env: Record<string, string> }
  /** Instruction channels: label → word, checked at startup (A) and after the change (B). */
  instructions: (phase: "A" | "B") => Array<{ label: string; word: string }>
  skills: Array<{ label: string; name: string; word: string; phase: "A" | "B"; mechanism: string }>
  mcp: { A: McpServerSpec[]; B: McpServerSpec[] }
  model?: string
  /** The skill question (default askSkill). */
  askSkill?: (name: string) => string
  /** Text prefixed to the first prompt of phase A (a first-prompt instructions preamble). */
  preamble?: string
  /** ACP `_meta` for session/new (A) and session/load (B). */
  meta?: (phase: "A" | "B") => Record<string, unknown> | undefined
  /** Optional live attempt for item 6 before phase B (Grok: rewrite [skills] paths). */
  live6?: () => Promise<{ name: string; word: string; mechanism: string } | undefined>
  work: string
  dir: string
  W: AcpWords
  logs: { probe: string; sub: string; probe2: string }
}

async function runAcp(plan: AcpPlan) {
  const { agent, work, dir, W, logs } = plan
  const frames = join(dir, "frames.log")
  const askInstr = (labels: Array<{ label: string; word: string }>) => labels.length > 1
    ? "List every probe token that appears anywhere in your system prompt, rules, user rules, workspace rules or instructions. Reply with the tokens only, space separated. Do not use any tools."
    : ASK_INSTR
  let sessionId = ""
  /** Records a cell, downgraded to "unproven" when the agent found the token itself (see tainted). */
  const judge = (acp: AcpClient, item: string, cell: string, reply: string, w: string, ok: Status, mechanism: string, evidence: unknown = reply, allowSkillRead = false) => {
    const t = has(reply, w) ? tainted(acp.turnTools, w, allowSkillRead) : undefined
    record(agent, item, cell, !has(reply, w) ? "no" : t ? "unproven" : ok, t ? `${mechanism} (${t})` : mechanism, evidence)
  }
  // ---- phase A ----
  {
    const l = plan.launch("A")
    const proc = new Lines(l.command, l.args, { cwd: work, env: l.env, frames })
    const acp = new AcpClient(proc)
    try {
      const init = await acp.init()
      record(agent, "-", "ACP initialize", "info", "initialize", { protocolVersion: init.protocolVersion, loadSession: init.agentCapabilities?.loadSession, mcp: init.agentCapabilities?.mcpCapabilities, agentInfo: init.agentInfo })
      await acp.newSession(work, plan.mcp.A.map(acpMcp), plan.meta?.("A"))
      sessionId = acp.sessionId
      if (plan.model) await step(agent, "-", "model", "session/set_config_option", () => acp.setModel(plan.model!))
      if (plan.preamble && (want(1) || want(8))) {
        acp.preamble = instructionsBlock(plan.preamble)
        const ok = await step(agent, "1", "preamble turn", `first prompt, ${INSTRUCTIONS_SHAPE} block`, () => acp.ask("Reply with OK only. Do not use any tools."))
        if (ok !== undefined) record(agent, "1", `instructions block accepted (${INSTRUCTIONS_SHAPE})`, "info", "session/prompt [block, text]", ok)
      }
      if (want(1)) {
        const channels = plan.instructions("A")
        const r = await step(agent, "1", "instructions", "see channels", () => acp.ask(askInstr(channels)))
        if (r !== undefined) for (const c of channels) judge(acp, "1", `instructions: ${c.label}`, r, c.word, "yes", c.label)
      }
      for (const s of plan.skills.filter(s => s.phase === "A")) {
        const n = s.label.startsWith("plugin") ? 3 : 2
        if (!want(n)) continue
        const r = await step(agent, String(n), s.label, s.mechanism, () => acp.ask((plan.askSkill ?? askSkill)(s.name)))
        if (r !== undefined) judge(acp, String(n), s.label, r, s.word, "yes", s.mechanism, { reply: r, tools: acp.turnTools.map(t => `${t.kind}:${t.title}`) }, true)
      }
      if (want(4)) {
        const r = await step(agent, "4", "MCP", "ACP mcpServers", () => acp.ask(askTool("probe", "get_code_word")))
        if (r !== undefined) judge(acp, "4", "MCP", r, W.mcp, "yes", "ACP session/new {mcpServers:[{name,command,args,env}]}")
        record(agent, "4", "MCP initialize", initializeEvidence(logs.probe).negotiated.length ? "info" : "no", "probe server log", initializeEvidence(logs.probe))
        const start = acp.rpc.notifications.length
        const s = await step(agent, "4", "MCP in subagent", "subagent", () => acp.ask(ASK_SUB, 300_000))
        if (s !== undefined) {
          await sleep(3000)
          const notes = acp.rpc.notifications.slice(start)
          const otherSessions = new Set(notes.map(n => n.params?.sessionId).filter(id => id && id !== sessionId))
          const childCall = notes.some(n => n.params?.sessionId && n.params.sessionId !== sessionId && /probesub/.test(JSON.stringify(n.params?.update ?? n.params ?? "")) && /tool_call/.test(JSON.stringify(n.params)))
          const parentDirect = notes.some(n => n.params?.sessionId === sessionId && n.params?.update?.sessionUpdate === "tool_call" && /probesub/.test(String(n.params.update.title ?? "")) && !/spawn|subagent|task/i.test(String(n.params.update.title ?? "") + String(n.params.update.kind ?? "")))
          const calls = mcpLog(logs.sub).filter(e => e.event === "call").length
          // OpenCode does not forward a child session's updates over ACP: then the proof is that the main
          // session's only tool calls were the subagent tool while the probesub server logged a call.
          const mainTitles = notes.filter(n => n.params?.sessionId === sessionId && n.params?.update?.sessionUpdate === "tool_call").map(n => String(n.params.update.title ?? n.params.update.kind ?? ""))
          const inferred = calls >= 1 && mainTitles.length > 0 && mainTitles.every(t => /^(task|spawn_subagent|agent|subagent)$/i.test(t) || /^task:/i.test(t) || /subagent/i.test(t))
          record(agent, "4", "MCP in subagent", has(s, W.sub) && (childCall || inferred) ? "yes" : has(s, W.sub) ? "unproven" : "no", "ask the agent to delegate the probesub call to a subagent", { reply: s, childSessionToolCallOnProbesub: childCall, inferredFromMainToolCalls: inferred, parentDirectCall: parentDirect, otherSessionIds: [...otherSessions], probesubCalls: calls, toolCalls: notes.filter(n => n.params?.update?.sessionUpdate === "tool_call").map(n => `${n.params.sessionId === sessionId ? "main" : "child"}:${n.params.update.title ?? n.params.update.kind}`).slice(0, 10) })
        }
      }
      if (want(5)) {
        if (!mcpLog(logs.probe).some(e => e.method === "tools/list")) await acp.ask("Reply with OK only. Do not use tools.") // MCP servers may start lazily
        writeFileSync(logs.probe + ".late", "")
        await sleep(2500)
        const r = await step(agent, "5", "tools/list_changed", "list_changed", () => acp.ask(askTool("probe", "get_late_code_word")))
        const relisted = mcpLog(logs.probe).filter(e => e.event === "in" && e.method === "tools/list").length
        if (r !== undefined) record(agent, "5", "tools/list_changed", has(r, W.late) ? "live" : "no", "server registers a tool + sends notifications/tools/list_changed", { reply: r, toolsListCalls: relisted })
      }
      if (want(6) && plan.live6) {
        const live = await plan.live6()
        if (live) {
          const r = await step(agent, "6", "add skill live", live.mechanism, () => acp.ask((plan.askSkill ?? askSkill)(live.name)))
          if (r !== undefined) judge(acp, "6", "add skill (live, same process)", r, live.word, "live", live.mechanism, r, true)
        }
      }
    } finally { await proc.kill() }
  }
  if (!sessionId || !(want(6) || want(7) || want(8) || want(9))) return
  // ---- phase B: new process, session/load with the changed context ----
  {
    const l = plan.launch("B")
    const proc = new Lines(l.command, l.args, { cwd: work, env: l.env, frames })
    const acp = new AcpClient(proc)
    try {
      await acp.init()
      const replayStart = acp.rpc.notifications.length
      const loaded = await step(agent, "9", "session/load", "session/load", () => acp.load(sessionId, work, plan.mcp.B.map(acpMcp), plan.meta?.("B")))
      if (loaded === undefined) return
      const echoed = acp.updatesSince(replayStart).filter(u => u?.update?.sessionUpdate === "user_message_chunk").map(u => u.update.content)
      record(agent, "9", "session/load replays the user's messages (content blocks)", "info", "user_message_chunk during session/load", echoed.map(c => ({ type: c?.type, uri: c?.resource?.uri, annotations: c?.annotations, text: String(c?.text ?? c?.resource?.text ?? "").slice(0, 80) })))
      if (plan.model) await step(agent, "-", "model", "session/set_config_option", () => acp.setModel(plan.model!))
      if (want(9) || want(7)) {
        const r = await step(agent, "9", "reload keeps conversation", "session/load", () => acp.ask(`Earlier in this conversation an MCP tool "get_code_word" of the server "probe" returned a code word. What was it? Do not call any tools; answer from the conversation history. Reply with the word only.`))
        if (r !== undefined) judge(acp, "9", "reload keeps conversation", r, W.mcp, "yes", "new process + ACP session/load {sessionId, cwd, mcpServers}")
      }
      if (want(7)) {
        const r = await step(agent, "7", "add MCP server", "session/load mcpServers", () => acp.ask(askTool("probe2", "get_code_word")))
        if (r !== undefined) judge(acp, "7", "add MCP server", r, W.mcp2, "reload", "new process + session/load with mcpServers [probe, probe2]", { reply: r, initialize: initializeEvidence(logs.probe2) })
      }
      if (want(6)) for (const s of plan.skills.filter(s => s.phase === "B")) {
        const r = await step(agent, "6", s.label, s.mechanism, () => acp.ask((plan.askSkill ?? askSkill)(s.name)))
        if (r !== undefined) judge(acp, "6", s.label, r, s.word, "reload", s.mechanism, { reply: r, tools: acp.turnTools.map(t => `${t.kind}:${t.title}`) }, true)
      }
      if (want(8)) {
        const channels = plan.instructions("B")
        if (channels.length) {
          const r = await step(agent, "8", "change instructions", "reload", () => acp.ask(askInstr(channels.length > 1 ? channels : [...channels, ...channels])))
          if (r !== undefined) for (const c of channels) judge(acp, "8", `change instructions: ${c.label}`, r, c.word, "reload", `${c.label} changed + new process + session/load`)
        }
      }
    } finally { await proc.kill() }
  }
}

function acpFixtures(agent: Agent) {
  const dir = join(RUN, agent)
  const work = scratchRepo(join(dir, "work"))
  const W: AcpWords = { mcp: word(), sub: word(), late: word(), mcp2: word(), skill: word(), skillNew: word(), pluginA: word(), pluginB: word(), pluginC: word() }
  const logs = { probe: join(dir, "mcp-probe.log"), sub: join(dir, "mcp-probesub.log"), probe2: join(dir, "mcp-probe2.log") }
  const mcp = { A: [mcpSpec("probe", logs.probe, W.mcp, W.late), mcpSpec("probesub", logs.sub, W.sub)], B: [] as McpServerSpec[] }
  mcp.B = [mcp.A[0]!, mcp.A[1]!, mcpSpec("probe2", logs.probe2, W.mcp2)]
  const skillsRoot = join(dir, "skills"), skillsRootB = join(dir, "skills-b")
  skill(skillsRoot, "probe-skill-alpha", W.skill)
  skill(skillsRootB, "probe-skill-echo", W.skillNew)
  const src = join(dir, "plugins-src")
  const pluginA = plugin(src, "probe-plugin-a", W.pluginA)
  const pluginB = plugin(src, "probe-plugin-b", W.pluginB)
  const pluginC = plugin(src, "probe-plugin-c", W.pluginC)
  return { dir, work, W, logs, mcp, skillsRoot, skillsRootB, pluginA, pluginB, pluginC }
}

// ---------- Cursor ----------
async function probeCursor() {
  const agent: Agent = "cursor"
  const f = acpFixtures(agent)
  const W = f.W
  const I = { plugRule: word(), homeRule: word(), homeAgents: word(), addDir: word(), meta: word(), hookStart: word(), hookPrompt: word(), preamble: word(), plugRuleB: word(), homeRuleB: word(), metaB: word(), hookStartB: word(), hookPromptB: word() }
  const homeSkillWord = word(), linkedSkillWord = word(), agentsSkillWord = word()
  const home = join(f.dir, "home")
  await prepareCursorEnvironment({
    home, workdir: f.work, mcpServers: [], skillsPaths: [], sharedRuntime: null, platform: process.platform,
    credentials: { apiKey: null, userCursorDir: join(HOME, ".cursor"), userConfigDir: join(HOME, ".config") },
  })
  // Instruction channels that are not the repo: a plugin carrying rules/, user rules in the session HOME,
  // AGENTS.md in the session HOME, and an --add-dir root with AGENTS.md.
  const instrPlugin = join(f.dir, "gen", "probe-instructions")
  const writeInstr = (pw: string, hw: string) => {
    mkdirSync(join(instrPlugin, ".cursor-plugin"), { recursive: true })
    mkdirSync(join(instrPlugin, "rules"), { recursive: true })
    writeFileSync(join(instrPlugin, ".cursor-plugin", "plugin.json"), JSON.stringify({ name: "probe-instructions", version: "0.0.1", description: "session instructions", rules: "./rules/" }))
    writeFileSync(join(instrPlugin, "rules", "probe.mdc"), `---\ndescription: session probe rules\nalwaysApply: true\n---\n\nPlugin rule: the plugin-rule probe token is ${pw}.\n`)
    mkdirSync(join(home, ".cursor", "rules"), { recursive: true })
    writeFileSync(join(home, ".cursor", "rules", "probe.mdc"), `---\ndescription: user probe rules\nalwaysApply: true\n---\n\nUser rule: the user-rule probe token is ${hw}.\n`)
  }
  writeInstr(I.plugRule, I.homeRule)
  writeFileSync(join(home, "AGENTS.md"), `Home AGENTS.md: the home-agents probe token is ${I.homeAgents}.\n`)
  skill(join(home, ".cursor", "skills"), "probe-skill-home", homeSkillWord)
  // A skill folder symlinked into $HOME/.cursor/skills (how a core would map a session's skills there),
  // and $HOME/.agents/skills (Cursor's other user-level skills root).
  skill(join(f.dir, "linked-skills"), "probe-skill-linked", linkedSkillWord)
  symlinkSync(join(f.dir, "linked-skills", "probe-skill-linked"), join(home, ".cursor", "skills", "probe-skill-linked"))
  skill(join(home, ".agents", "skills"), "probe-skill-agents", agentsSkillWord)
  // Hooks in the session HOME: sessionStart / beforeSubmitPrompt returning additional_context.
  const writeHooks = (start: string, prompt: string) => {
    const hook = (name: string, text: string) => {
      const file = join(f.dir, "gen", `${name}.sh`)
      mkdirSync(dirname(file), { recursive: true })
      writeFileSync(file, `#!/bin/sh\ncat >/dev/null\nprintf '%s\\n' '${JSON.stringify({ additional_context: text, continue: true })}'\n`, { mode: 0o755 })
      return file
    }
    writeFileSync(join(home, ".cursor", "hooks.json"), JSON.stringify({ version: 1, hooks: {
      sessionStart: [{ command: hook("session-start", `Session start hook: the session-start-hook probe token is ${start}.`) }],
      beforeSubmitPrompt: [{ command: hook("before-submit", `Prompt hook: the prompt-hook probe token is ${prompt}.`) }],
    } }, null, 2))
  }
  writeHooks(I.hookStart, I.hookPrompt)
  const addDir = join(f.dir, "extra-root")
  mkdirSync(addDir, { recursive: true })
  writeFileSync(join(addDir, "AGENTS.md"), `Extra root AGENTS.md: the add-dir probe token is ${I.addDir}.\n`)
  const wrapRoot = join(f.dir, "gen", "skills-root")
  mkdirSync(wrapRoot, { recursive: true })
  symlinkSync(join(f.skillsRoot, "probe-skill-alpha"), join(wrapRoot, "probe-skill-alpha"))
  const wrap = skillsWrapper(join(f.dir, "gen", "probe-skills"), "probe-skills", wrapRoot)
  const env = { ...baseEnv(), ...{ HOME: home } }
  await runAcp({
    agent, work: f.work, dir: f.dir, W, logs: f.logs, mcp: f.mcp, model: MODELS.cursor,
    meta: phase => ({ rules: `Session meta rules: the meta-rules probe token is ${phase === "A" ? I.meta : I.metaB}.` }),
    askSkill: askSkillByRead,
    preamble: `Session instructions (first-prompt preamble): the preamble probe token is ${I.preamble}.`,
    launch: phase => {
      if (phase === "B") {
        writeInstr(I.plugRuleB, I.homeRuleB)
        writeHooks(I.hookStartB, I.hookPromptB)
        if (!existsSync(join(wrapRoot, "probe-skill-echo"))) symlinkSync(join(f.skillsRootB, "probe-skill-echo"), join(wrapRoot, "probe-skill-echo"))
      }
      const plugins = phase === "A" ? [wrap, f.pluginA, instrPlugin] : [wrap, f.pluginA, instrPlugin, f.pluginC]
      return { command: "cursor-agent", args: [...plugins.flatMap(p => ["--plugin-dir", p]), "--add-dir", addDir, "--approve-mcps", "acp"], env }
    },
    instructions: phase => phase === "A"
      ? [{ label: "plugin rules/ (--plugin-dir)", word: I.plugRule }, { label: "$HOME/.cursor/rules/*.mdc (session HOME)", word: I.homeRule }, { label: "$HOME/AGENTS.md (session HOME)", word: I.homeAgents }, { label: "--add-dir <root>/AGENTS.md", word: I.addDir }, { label: "ACP _meta.rules", word: I.meta },
        { label: "$HOME/.cursor/hooks.json sessionStart additional_context", word: I.hookStart }, { label: "$HOME/.cursor/hooks.json beforeSubmitPrompt additional_context", word: I.hookPrompt }, { label: "first-prompt preamble", word: I.preamble }]
      : [{ label: "plugin rules/ (--plugin-dir)", word: I.plugRuleB }, { label: "$HOME/.cursor/rules/*.mdc (session HOME)", word: I.homeRuleB }, { label: "ACP _meta.rules", word: I.metaB },
        { label: "$HOME/.cursor/hooks.json sessionStart additional_context", word: I.hookStartB }, { label: "$HOME/.cursor/hooks.json beforeSubmitPrompt additional_context", word: I.hookPromptB }, { label: "first-prompt preamble (kept by session/load)", word: I.preamble }],
    skills: [
      { label: "skills", name: "probe-skill-alpha", word: W.skill, phase: "A", mechanism: "generated plugin wrapping the skills (symlinks) → --plugin-dir" },
      { label: "skills ($HOME/.cursor/skills)", name: "probe-skill-home", word: homeSkillWord, phase: "A", mechanism: "$HOME/.cursor/skills in the session HOME" },
      { label: "skills ($HOME/.cursor/skills symlink)", name: "probe-skill-linked", word: linkedSkillWord, phase: "A", mechanism: "a skill folder symlinked into $HOME/.cursor/skills" },
      { label: "skills ($HOME/.agents/skills)", name: "probe-skill-agents", word: agentsSkillWord, phase: "A", mechanism: "$HOME/.agents/skills in the session HOME" },
      { label: "plugin (--plugin-dir)", name: "probe-plugin-a-skill", word: W.pluginA, phase: "A", mechanism: "--plugin-dir <plugin> before `acp`" },
      { label: "add skill (reload)", name: "probe-skill-echo", word: W.skillNew, phase: "B", mechanism: "symlink added to the wrapper plugin's skills + new process + session/load" },
      { label: "add plugin (reload)", name: "probe-plugin-c-skill", word: W.pluginC, phase: "B", mechanism: "extra --plugin-dir + new process + session/load" },
    ],
  })
}

// ---------- Grok ----------
async function probeGrok() {
  const agent: Agent = "grok"
  const f = acpFixtures(agent)
  const W = f.W
  const I = { rules: word(), rulesB: word(), meta: word(), metaB: word() }
  const foxtrot = word()
  const skillsRootC = join(f.dir, "skills-c")
  skill(skillsRootC, "probe-skill-foxtrot", foxtrot)
  const home = join(f.dir, "home")
  mkdirSync(join(home, ".grok"), { recursive: true })
  const authCopy = join(f.dir, "grok-auth.json")
  copyFileSync(join(HOME, ".grok", "auth.json"), authCopy)
  chmodSync(authCopy, 0o600)
  const spec = (skillsPaths: string[]) => ({
    home, workdir: f.work, mcpServers: [], skillsPaths,
    credentials: { canonicalAuthPath: authCopy }, autoUpdate: false, importClaudeConfig: false, platform: process.platform,
  })
  const prepared = await prepareGrokEnvironment(spec([f.skillsRoot]))
  const env = { ...baseEnv(), ...prepared.env, GROK_AUTH_PATH: authCopy }
  const configPath = join(home, ".grok", "config.toml")
  if (want(3)) {
    const r = spawnSync("grok", ["plugin", "install", f.pluginA, "--trust"], { cwd: f.work, env, encoding: "utf8", timeout: 90_000 })
    appendFileSync(join(f.dir, "frames.log"), `$ grok plugin install ${f.pluginA} --trust\n${r.stdout}${r.stderr}\n`)
    record(agent, "3", "grok plugin install (HOME = session)", r.status === 0 ? "info" : "no", "grok plugin install <local> --trust", (r.stdout + r.stderr).trim().slice(0, 400))
  }
  await runAcp({
    agent, work: f.work, dir: f.dir, W, logs: f.logs, mcp: f.mcp,
    meta: phase => ({ rules: `Session meta rules: the meta-rules probe token is ${phase === "A" ? I.meta : I.metaB}.` }),
    launch: phase => {
      if (phase === "B") writeFileSync(configPath, readFileSync(configPath, "utf8").replace(/paths = \[[^\]]*\]/, `paths = [${[f.skillsRoot, f.skillsRootB, skillsRootC].map(p => JSON.stringify(p)).join(", ")}]`))
      const rules = phase === "A" ? I.rules : I.rulesB
      const plugins = phase === "A" ? [f.pluginB] : [f.pluginB, f.pluginC]
      return { command: "grok", args: ["--rules", `Session probe rules: the rules probe token is ${rules}.`, "agent", "--no-leader", "--reasoning-effort", "low", ...plugins.flatMap(p => ["--plugin-dir", p]), "stdio"], env }
    },
    instructions: phase => [{ label: "--rules (top-level flag before `agent stdio`)", word: phase === "A" ? I.rules : I.rulesB }, { label: "ACP _meta.rules on session/new (A) / session/load (B)", word: phase === "A" ? I.meta : I.metaB }],
    skills: [
      { label: "skills", name: "probe-skill-alpha", word: W.skill, phase: "A", mechanism: "session config.toml [skills] paths" },
      { label: "plugin (grok plugin install)", name: "probe-plugin-a-skill", word: W.pluginA, phase: "A", mechanism: "grok plugin install --trust with HOME = session home" },
      { label: "plugin (agent --plugin-dir)", name: "probe-plugin-b-skill", word: W.pluginB, phase: "A", mechanism: "grok agent --plugin-dir <plugin> stdio (per process)" },
      { label: "add plugin (reload)", name: "probe-plugin-c-skill", word: W.pluginC, phase: "B", mechanism: "extra --plugin-dir + new process + session/load" },
      { label: "add skill (reload)", name: "probe-skill-foxtrot", word: foxtrot, phase: "B", mechanism: "[skills] paths rewritten + new process + session/load" },
    ],
    live6: async () => {
      writeFileSync(configPath, readFileSync(configPath, "utf8").replace(/paths = \[[^\]]*\]/, `paths = [${JSON.stringify(f.skillsRoot)}, ${JSON.stringify(f.skillsRootB)}]`))
      await sleep(3000)
      return { name: "probe-skill-echo", word: W.skillNew, mechanism: "rewrite session config.toml [skills] paths while running" }
    },
  })
}

// ---------- OpenCode ----------
async function probeOpenCode() {
  const agent: Agent = "opencode"
  const f = acpFixtures(agent)
  const W = f.W
  const I = { instr: word(), instrB: word() }
  const data = join(f.dir, "xdg-data")
  mkdirSync(join(data, "opencode"), { recursive: true })
  copyFileSync(join(HOME, ".local", "share", "opencode", "auth.json"), join(data, "opencode", "auth.json"))
  chmodSync(join(data, "opencode", "auth.json"), 0o600)
  const configHome = join(f.dir, "xdg-config")
  // Instructions the way the core's OpenCode adapter delivers them: config `instructions` in a
  // session-private OPENCODE_CONFIG file (the environment library no longer writes instructions).
  const instrFile = join(f.dir, "session", "instructions.md"), instrConfig = join(f.dir, "session", "opencode.json")
  const prepare = (instr: string, skillsPaths: string[]) => {
    // Written synchronously: phase B calls this right before spawning the new process.
    mkdirSync(dirname(instrFile), { recursive: true })
    writeFileSync(instrFile, `Session probe instructions. The instruction probe token is ${instr}.`)
    writeFileSync(instrConfig, JSON.stringify({ instructions: [instrFile] }, null, 2) + "\n")
    return prepareOpenCodeEnvironment({
      home: join(f.dir, "session"), workdir: f.work, mcpServers: [], skillsPaths,
      configHome, provider: null, pluginPaths: [], permissions: { edit: "allow", bash: "allow", webfetch: "allow" },
    })
  }
  const prepared = await prepare(I.instr, [f.skillsRoot, join(f.pluginA, "skills")])
  const env = { ...baseEnv(), ...prepared.env, OPENCODE_CONFIG: instrConfig, XDG_DATA_HOME: data, XDG_STATE_HOME: join(f.dir, "xdg-state"), XDG_CACHE_HOME: join(f.dir, "xdg-cache") }
  await runAcp({
    agent, work: f.work, dir: f.dir, W, logs: f.logs, mcp: f.mcp, model: MODELS.opencode,
    launch: phase => {
      if (phase === "B") void prepare(I.instrB, [f.skillsRoot, join(f.pluginA, "skills"), f.skillsRootB, join(f.pluginC, "skills")])
      return { command: "opencode", args: ["acp", "--print-logs", "--log-level", "ERROR"], env }
    },
    instructions: phase => [{ label: "config `instructions` (session OPENCODE_CONFIG)", word: phase === "A" ? I.instr : I.instrB }],
    skills: [
      { label: "skills", name: "probe-skill-alpha", word: W.skill, phase: "A", mechanism: "config skills.paths" },
      { label: "plugin (mapped: skills/ → skills.paths)", name: "probe-plugin-a-skill", word: W.pluginA, phase: "A", mechanism: "plugin's skills/ added to skills.paths" },
      { label: "add skill (reload)", name: "probe-skill-echo", word: W.skillNew, phase: "B", mechanism: "skills.paths rewritten + new process + session/load" },
      { label: "add plugin (reload)", name: "probe-plugin-c-skill", word: W.pluginC, phase: "B", mechanism: "plugin's skills/ added + new process + session/load" },
    ],
  })
}

// ---------- main ----------
async function main() {
  mkdirSync(RUN, { recursive: true })
  ensureMcpSdk()
  console.log(`run dir: ${RUN}`)
  const probes: Record<Agent, () => Promise<unknown>> = { claude: probeClaude, codex: probeCodex, cursor: probeCursor, grok: probeGrok, opencode: probeOpenCode }
  for (const agent of AGENTS) {
    if (wanted.length && !wanted.includes(agent)) continue
    try { await probes[agent]() } catch (error) { record(agent, "-", "probe", "unproven", "probe crashed", (error as Error).stack ?? String(error)) }
  }
  writeFileSync(join(RUN, "results.json"), JSON.stringify(cells, null, 2))
  console.log(`\n${cells.length} cells; results: ${join(RUN, "results.json")}`)
}

const cleanup = () => { for (const c of children) try { process.kill(-c.pid!, "SIGKILL") } catch { /* gone */ } }
process.on("SIGINT", () => { cleanup(); process.exit(130) })
process.on("exit", cleanup)
try { await main() } finally { cleanup() }
