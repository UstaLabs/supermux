/**
 * C3 launch equivalence: what a broker session's agent process actually gets (instructions,
 * skills, plugins, MCP servers, other args, env, generated files), extracted through each agent's
 * real core-host (`prepare` + driver factory) with a capturing fake driver, then mapped through
 * the core's own per-agent context channels (the same functions the real drivers call).
 *
 * The importer must set HOME / MUX_HOME / MUX_STATE_DIR to scratch dirs BEFORE importing this
 * module (shared/paths and the preamble writers read them at import time): see
 * tests/c3-launch-equivalence.test.ts and scripts/c3-launch-capture.ts.
 */
import { existsSync, lstatSync, mkdirSync, readdirSync, readFileSync, readlinkSync, realpathSync, writeFileSync } from "node:fs"
import { join, relative, resolve } from "node:path"
import type { AgentDriver, AgentRuntime, DriverContext } from "../../packages/supermux-core/src/index.js"
import type { LaunchContext } from "../../packages/supermux-core/src/context/types.js"
import { claudeContextArgs, codexContextLaunch, cursorContext, grokContext, opencodeContext } from "../../packages/supermux-core/src/context/agents.js"
import { createClaudeCoreHost } from "../../src/core/agents/claude/core-host"
import { createCodexCoreHost } from "../../src/core/agents/codex/core-host"
import { createCursorCoreHost } from "../../src/core/agents/cursor/core-host"
import { createGrokCoreHost } from "../../src/core/agents/grok/core-host"
import { createOpenCodeCoreHost } from "../../src/core/agents/opencode/core-host"
import { brokerCodexArgs } from "../../src/core/agents/codex/session"
import * as codexDriver from "../../packages/supermux-core/src/codex/index.js"

export type Agent = "claude" | "codex" | "cursor" | "grok" | "opencode"
export const AGENTS: Agent[] = ["claude", "codex", "cursor", "grok", "opencode"]
export type Role = "worker" | "pa"

export type McpEntry = { name: string; command: string; args: string[]; env: Record<string, string> }

/** The effective launch, with scratch paths replaced by placeholders. */
export type EffectiveLaunch = {
  /** The instruction text the agent sees, and through which channel. */
  instructions: { channel: string; text: string } | null
  /** Plugin roots the agent loads (Codex: marketplace ids). */
  plugins: string[]
  /** Extra skill roots (outside plugins). */
  skills: string[]
  mcpServers: McpEntry[]
  /** Everything else on the command line, in order. */
  args: string[]
  env: Record<string, string>
  /** Files the launch wrote outside the core's context folder: relative path → content. */
  files: Record<string, string>
}

const PRESET = {
  sessionId: "sess-c3",
  sessionName: "c3-probe",
}

export type Scratch = { root: string; home: string; muxHome: string; workdir: string; repo: string }

/** Creates the scratch layout an extraction needs (call before importing this module's users). */
export function scratchLayout(root: string): Scratch {
  const home = join(root, "home")
  const muxHome = join(root, "mux")
  const workdir = join(root, "work")
  mkdirSync(join(workdir, ".git", "info"), { recursive: true })
  // Fake credentials: the prepare steps copy / point at these; nothing real is read.
  mkdirSync(join(home, ".codex"), { recursive: true })
  writeFileSync(join(home, ".codex", "auth.json"), JSON.stringify({ tokens: { access_token: "x", account_id: "a" } }))
  mkdirSync(join(home, ".grok"), { recursive: true })
  writeFileSync(join(home, ".grok", "auth.json"), JSON.stringify({ access_token: "x" }))
  mkdirSync(join(home, ".cursor"), { recursive: true })
  writeFileSync(join(home, ".cursor", "cli-config.json"), "{}")
  mkdirSync(join(home, ".config", "cursor"), { recursive: true })
  writeFileSync(join(home, ".config", "cursor", "auth.json"), JSON.stringify({ accessToken: "x" }))
  // Two plugins like production: mux-core (every manifest, hooks, an OpenCode JS plugin) and a
  // third-party one; both enabled for every agent.
  const plugin = (name: string, manifestName: string, js: boolean) => {
    const dir = join(muxHome, "plugins", name)
    for (const m of [".claude-plugin", ".codex-plugin", ".cursor-plugin"]) {
      mkdirSync(join(dir, m), { recursive: true })
      writeFileSync(join(dir, m, "plugin.json"), JSON.stringify({ name: manifestName, version: "0.0.0", ...(m === ".codex-plugin" ? { skills: "./skills/" } : {}) }))
    }
    mkdirSync(join(dir, "skills", `${name}-skill`), { recursive: true })
    writeFileSync(join(dir, "skills", `${name}-skill`, "SKILL.md"), `---\nname: ${name}-skill\ndescription: probe\n---\nbody\n`)
    mkdirSync(join(dir, "hooks"), { recursive: true })
    writeFileSync(join(dir, "hooks", "session-start"), "#!/bin/sh\n", { mode: 0o755 })
    writeFileSync(join(dir, "hooks", "hooks.json"), "{}")
    if (js) {
      mkdirSync(join(dir, ".opencode", "plugins"), { recursive: true })
      writeFileSync(join(dir, ".opencode", "plugins", `${name}.js`), "export const P = async () => ({})\n")
    }
    return dir
  }
  const core = plugin("mux-core", "mux", true)
  const third = plugin("thirdparty", "thirdparty", false)
  writeFileSync(join(muxHome, "plugins.json"), JSON.stringify({
    version: 1,
    plugins: [
      { name: "mux-core", source: { type: "local", path: core }, enabled: true, scopes: ["claude", "codex", "cursor", "opencode", "grok"] },
      { name: "thirdparty", source: { type: "local", path: third }, enabled: true, scopes: ["claude", "codex", "cursor", "opencode", "grok"] },
    ],
  }))
  return { root, home, muxHome, workdir, repo: resolve(import.meta.dirname, "..", "..") }
}

function fakeDriver(id: string, onOpen: (ctx: DriverContext) => void): AgentDriver {
  return {
    id,
    async open(ctx) {
      onOpen(ctx)
      const runtime: AgentRuntime = {
        agentSessionId: "native-1",
        capabilities: { resume: true, steer: false, fork: false, detach: false },
        async prompt() { return { stopReason: "end_turn" } },
        async interrupt() {},
        async close() {},
      }
      return runtime
    },
  }
}

type Captured = { options: Record<string, unknown>; ctx: DriverContext }

async function capture(agent: Agent, role: Role, s: Scratch): Promise<Captured & { sessionHome: string; state: string; workdir: string }> {
  const sessionHome = join(s.root, "agents", agent, role, PRESET.sessionName)
  const state = join(s.root, "core", agent, role)
  // One workdir per launch: Grok and Cursor write into the repo (before C3).
  const workdir = join(s.root, `work-${agent}-${role}`)
  mkdirSync(join(workdir, ".git", "info"), { recursive: true })
  let captured: Captured | undefined
  const factory = (options: unknown) => fakeDriver(agent, (ctx) => { captured = { options: options as Record<string, unknown>, ctx } })
  const base = { stateDirectory: state, driverFactory: factory as never }
  const host = agent === "claude" ? createClaudeCoreHost(base)
    : agent === "codex" ? createCodexCoreHost(base)
      : agent === "cursor" ? createCursorCoreHost({ ...base, smoke: async () => {}, sharedRuntime: null })
        : agent === "grok" ? createGrokCoreHost(base)
          : createOpenCodeCoreHost(base)
  const extra: Record<string, unknown> = {
    sessionHome, sessionName: PRESET.sessionName, sessionId: PRESET.sessionId, workdir, cwd: workdir,
    ...(role === "pa" && agent === "claude" ? { pa: true } : {}),
  }
  const registration = agent === "codex"
    ? { id: PRESET.sessionId, env: {}, command: "codex", args: brokerCodexArgs(PRESET.sessionName), extra }
    : { id: PRESET.sessionId, env: {}, extra }
  try {
    await host.register(registration).start({ cwd: workdir })
  } finally {
    await host.close({ agents: "shutdown" })
  }
  if (!captured) throw new Error(`${agent}: the driver never opened`)
  return { ...captured, sessionHome, state, workdir }
}

function placeholders(s: Scratch, value: string): string {
  return value.split(s.repo).join("<REPO>").split(s.root).join("<ROOT>")
}

function normalize<T>(s: Scratch, value: T): T {
  return JSON.parse(placeholders(s, JSON.stringify(value)))
}

/** Every file under `dir` (relative path → content), skipping `.git` and the core's own state. */
function filesUnder(dir: string, skip: (rel: string) => boolean): Record<string, string> {
  const out: Record<string, string> = {}
  const walk = (current: string) => {
    let names: string[]
    try { names = readdirSync(current) } catch { return }
    for (const name of names.sort()) {
      const path = join(current, name)
      const rel = relative(dir, path)
      if (skip(rel)) continue
      const st = lstatSync(path)
      if (st.isSymbolicLink()) out[rel] = `-> ${readlinkSync(path)}`
      else if (st.isDirectory()) walk(path)
      else out[rel] = readFileSync(path, "utf8")
    }
  }
  walk(dir)
  return out
}

/** `--plugin-dir` entries: a core session folder expands to its plugins / generated skill wrappers. */
function expandPluginDir(dir: string): { plugins: string[]; skills: string[] } {
  const plugins: string[] = []
  const skills: string[] = []
  if (!existsSync(dir)) return { plugins, skills }
  const names = readdirSync(dir)
  const own = names.length > 0 && names.every(name => /^\d+-/.test(name) || /^supermux-skills-\d+$/.test(name))
  if (!own && !(dir.includes(`${join("context", "")}`) && names.length === 0)) {
    // A plugin folder itself (or a core folder entry).
    if (lstatSync(dir).isSymbolicLink()) plugins.push(realpathSync(dir))
    else if (/supermux-skills-\d+$/.test(dir)) skills.push(realpathSync(join(dir, "skills")))
    else plugins.push(dir)
    return { plugins, skills }
  }
  for (const name of names.sort()) {
    const entry = join(dir, name)
    if (/^\d+-/.test(name)) plugins.push(realpathSync(entry))
    else skills.push(realpathSync(join(entry, "skills")))
  }
  return { plugins, skills }
}

function takeFlag(args: string[], flag: string): { values: string[]; rest: string[] } {
  const values: string[] = []
  const rest: string[] = []
  for (let i = 0; i < args.length; i++) {
    if (args[i] === flag && i + 1 < args.length) { values.push(args[i + 1]!); i++ }
    else rest.push(args[i]!)
  }
  return { values, rest }
}

function mcpFromJson(path: string): McpEntry[] {
  const raw = JSON.parse(readFileSync(path, "utf8")) as { mcpServers?: Record<string, { command: string; args?: string[]; env?: Record<string, string> }> }
  return Object.entries(raw.mcpServers ?? {}).map(([name, v]) => ({ name, command: v.command, args: v.args ?? [], env: v.env ?? {} }))
}

function sortServers(servers: McpEntry[]): McpEntry[] {
  return [...servers].sort((a, b) => a.name.localeCompare(b.name))
}

/** TOML `[mcp_servers.<name>]` tables as written by the environment helpers (Codex / Grok). */
function mcpFromToml(text: string): McpEntry[] {
  const out: McpEntry[] = []
  let current: McpEntry | undefined
  let inEnv = false
  for (const line of text.split("\n")) {
    const header = /^\[mcp_servers\.([A-Za-z0-9_-]+)(\.env)?\]$/.exec(line.trim())
    if (header) {
      if (!header[2]) { current = { name: header[1]!, command: "", args: [], env: {} }; out.push(current); inEnv = false }
      else inEnv = true
      continue
    }
    if (/^\[/.test(line.trim())) { current = undefined; continue }
    if (!current) continue
    const kv = /^([A-Za-z0-9_.]+)\s*=\s*(.*)$/.exec(line.trim())
    if (!kv) continue
    const [, key, value] = kv
    if (inEnv) current.env[key!] = JSON.parse(value!)
    else if (key === "command") current.command = JSON.parse(value!)
    else if (key === "args") current.args = JSON.parse(value!)
    else if (key!.startsWith("env.")) current.env[key!.slice(4)] = JSON.parse(value!)
  }
  return out
}

/** Codex `-c key=value` pairs → MCP servers (mcp_servers.*), the rest kept as `-c` args. */
function codexArgs(args: string[]): { servers: McpEntry[]; rest: string[] } {
  const servers = new Map<string, McpEntry>()
  const rest: string[] = []
  for (let i = 0; i < args.length; i++) {
    const m = args[i] === "-c" ? /^mcp_servers\.([A-Za-z0-9_-]+)\.([a-z_]+)=(.*)$/.exec(args[i + 1] ?? "") : null
    if (!m) { rest.push(args[i]!); continue }
    i++
    const [, name, key, value] = m
    const server = servers.get(name!) ?? { name: name!, command: "", args: [], env: {} }
    servers.set(name!, server)
    if (key === "command") server.command = JSON.parse(value!)
    else if (key === "args") server.args = JSON.parse(value!)
    else if (key === "env") {
      for (const pair of value!.replace(/^\{\s*|\s*\}$/g, "").split(/,\s*(?=")/)) {
        const kv = /^"(.*?)"\s*=\s*"(.*)"$/.exec(pair.trim())
        if (kv) server.env[kv[1]!] = kv[2]!
      }
    } else rest.push("-c", `mcp_servers.${name}.${key}=${value}`)
  }
  return { servers: [...servers.values()], rest }
}

/** The core's per-session folder for this launch (generated files live there, not in `files`). */
function ctxOf(c: Captured): LaunchContext | undefined {
  return c.ctx.sessionContext
}

export async function effectiveLaunch(agent: Agent, role: Role, s: Scratch): Promise<EffectiveLaunch> {
  const c = await capture(agent, role, s)
  const sc = ctxOf(c)
  const options = c.options
  const skipCore = (rel: string) => rel === ".git" || rel.startsWith(".git/")
  const files: Record<string, string> = {}
  for (const [rel, content] of Object.entries(filesUnder(c.sessionHome, () => false))) files[`<home>/${rel}`] = content
  for (const [rel, content] of Object.entries(filesUnder(c.workdir, skipCore))) files[`<work>/${rel}`] = content
  if (existsSync(join(c.workdir, ".git", "info", "exclude"))) files["<work>/.git/info/exclude"] = readFileSync(join(c.workdir, ".git", "info", "exclude"), "utf8")
  let out: EffectiveLaunch
  if (agent === "claude") {
    const hostArgs = options.args as string[]
    const all = sc ? [...hostArgs, ...claudeContextArgs(sc, hostArgs)] : hostArgs
    const prompts = takeFlag(all, "--append-system-prompt-file")
    const pluginDirs = takeFlag(prompts.rest, "--plugin-dir")
    const mcp = takeFlag(pluginDirs.rest, "--mcp-config")
    const last = prompts.values.at(-1)
    const plugins: string[] = []
    const skills: string[] = []
    for (const dir of pluginDirs.values) { const e = expandPluginDir(dir); plugins.push(...e.plugins); skills.push(...e.skills) }
    out = {
      instructions: last ? { channel: "--append-system-prompt-file (Claude keeps the last one)", text: readFileSync(last, "utf8") } : null,
      plugins, skills,
      mcpServers: sortServers(mcp.values.flatMap(mcpFromJson)),
      args: mcp.rest,
      env: options.env as Record<string, string>,
      files,
    }
  } else if (agent === "codex") {
    const launch = sc ? codexContextLaunch(sc) : undefined
    // What the driver itself adds per process (C3: the session's policy), when it does.
    const policyArgs = (codexDriver as { codexPolicyArgs?: (spec: unknown) => string[] }).codexPolicyArgs
    const all = [...(options.args as string[]), ...(policyArgs ? policyArgs(options.permissions) : []), ...(launch?.args ?? [])]
    const parsed = codexArgs(all)
    const configToml = files["<home>/config.toml"] ?? ""
    const agentsMd = files["<home>/AGENTS.md"]
    const plugins = parsed.rest.flatMap((arg) => { const m = /^plugins\."(.+)"\.enabled=true$/.exec(arg); return m ? [`codex-plugin:${m[1]}`] : [] })
    const rest = parsed.rest.filter((arg, i, list) => !/^plugins\./.test(arg) && !(arg === "-c" && /^plugins\./.test(list[i + 1] ?? "")))
    out = {
      instructions: launch?.developerInstructions !== undefined
        ? { channel: "thread/start developerInstructions", text: launch.developerInstructions }
        : agentsMd !== undefined ? { channel: "<CODEX_HOME>/AGENTS.md", text: agentsMd } : null,
      plugins: [...plugins, ...(sc ? sc.plugins.map(p => realpathSync(p)) : [])],
      skills: launch?.extraRoots ?? [],
      mcpServers: sortServers([...mcpFromToml(configToml), ...parsed.servers]),
      args: rest,
      env: options.env as Record<string, string>,
      files,
    }
  } else if (agent === "grok" || agent === "cursor") {
    const adapter = agent === "grok" ? grokContext((options.mcpServers as never) ?? []) : cursorContext((options.mcpServers as never) ?? [])
    const launch = sc ? adapter.launch(sc, options.env as Record<string, string>) : undefined
    const commandArgs = [...(options.commandArgs as string[]), ...(launch?.args ?? [])]
    const pluginDirs = takeFlag(commandArgs, "--plugin-dir")
    const plugins: string[] = []
    const skills: string[] = []
    for (const dir of pluginDirs.values) { const e = expandPluginDir(dir); plugins.push(...e.plugins); skills.push(...e.skills) }
    const configToml = files["<home>/.grok/config.toml"] ?? ""
    const tomlSkills = /\[skills\]\npaths = (\[.*\])/.exec(configToml)
    if (tomlSkills) skills.push(...JSON.parse(tomlSkills[1]!))
    const fileMcp = agent === "cursor" && files["<home>/.cursor/mcp.json"] ? mcpFromJson(join(c.sessionHome, ".cursor", "mcp.json")) : mcpFromToml(configToml)
    const acp = ([...((options.mcpServers as never[]) ?? []), ...(launch?.mcpServers ?? [])] as Array<{ name: string; command: string; args: string[]; env: Array<{ name: string; value: string }> }>).map((server) => ({
      name: server.name, command: server.command, args: server.args, env: Object.fromEntries(server.env.map(e => [e.name, e.value])),
    }))
    const repoRule = Object.keys(files).find(k => /^<work>\/(AGENTS(\.override)?\.md|\.cursor\/rules\/mux\.mdc)$/.test(k))
    const rules = launch?.newSessionMeta?.rules as string | undefined
    const block = launch?.firstPromptBlock as { type: string; resource?: { uri: string; text: string } } | undefined
    const preamble = block?.type === "resource" ? block.resource!.text : undefined
    out = {
      instructions: rules !== undefined ? { channel: "ACP session/new _meta.rules", text: rules }
        : preamble !== undefined ? { channel: "first-prompt embedded resource supermux://instructions (ACP session/prompt)", text: preamble }
        : repoRule ? { channel: `repo file ${repoRule}`, text: files[repoRule]! } : null,
      plugins, skills,
      mcpServers: sortServers([...fileMcp, ...acp]),
      args: pluginDirs.rest,
      env: { ...(options.env as Record<string, string>), ...(launch?.env ?? {}) },
      files,
    }
  } else {
    const env = options.env as Record<string, string>
    const launch = sc ? opencodeContext((options.mcpServers as never) ?? []).launch(sc, env) : undefined
    const xdgFile = join(env.XDG_CONFIG_HOME ?? "", "opencode", "opencode.json")
    const xdg = existsSync(xdgFile) ? JSON.parse(readFileSync(xdgFile, "utf8")) as Record<string, unknown> : {}
    const session = launch?.env.OPENCODE_CONFIG ? JSON.parse(readFileSync(launch.env.OPENCODE_CONFIG, "utf8")) as Record<string, unknown> : {}
    const instructionFiles = [...((xdg.instructions as string[]) ?? []), ...((session.instructions as string[]) ?? [])]
    const pluginEntries = [...((xdg.plugin as string[]) ?? []), ...((session.plugin as string[]) ?? [])]
    const skillPaths = [...new Set([...(((xdg.skills as { paths?: string[] })?.paths) ?? []), ...(((session.skills as { paths?: string[] })?.paths) ?? [])])]
    const fileMcp = Object.entries((xdg.mcp as Record<string, { command: string[]; environment: Record<string, string> }>) ?? {}).map(([name, v]) => ({ name, command: v.command[0]!, args: v.command.slice(1), env: v.environment }))
    const acp = ([...((options.mcpServers as never[]) ?? []), ...(launch?.mcpServers ?? [])] as Array<{ name: string; command: string; args: string[]; env: Array<{ name: string; value: string }> }>).map((server) => ({
      name: server.name, command: server.command, args: server.args, env: Object.fromEntries(server.env.map(e => [e.name, e.value])),
    }))
    out = {
      instructions: instructionFiles.length ? { channel: "opencode config instructions", text: instructionFiles.map(f => readFileSync(f, "utf8")).join("\n---\n") } : null,
      plugins: pluginEntries,
      skills: skillPaths,
      mcpServers: sortServers([...fileMcp, ...acp]),
      args: [],
      env: { ...env, ...(launch?.env ?? {}) },
      files,
    }
    // The session-level config keys that are not context (provider, permissions) stay comparable.
    const { instructions: _i, plugin: _p, skills: _s, mcp: _m, ...rest } = xdg
    out.files["<opencode.json non-context keys>"] = JSON.stringify(rest, null, 2)
  }
  const normalized = JSON.parse(JSON.stringify(normalize(s, out)).split(`<ROOT>/work-${agent}-${role}`).join("<WORK>").split(`<ROOT>/agents/${agent}/${role}/`).join(`<ROOT>/agents/${agent}/`))
  if (sc) {
    const dir = placeholders(s, sc.directory)
    return JSON.parse(JSON.stringify(normalized).split(dir).join("<CTX>"))
  }
  return normalized
}

/** Paths (dot-joined) where two launches differ. */
/** What changed for Cursor relative to the C3a launch since the C0 cursor cells (2026-10-05). */
export const CURSOR_C3 = ["files.<work>/.cursor/rules/mux.mdc", "files.<work>/.git/info/exclude", "instructions.channel", "instructions.text", "plugins"]
/** The body of the old repo rule (`.cursor/rules/mux.mdc` without its front matter). */
export function cursorRuleBody(mdc: string): string {
  return mdc.replace(/^---\ndescription: supermux session rules\nalwaysApply: true\n---\n\n/, "")
}

export function diffKeys(a: unknown, b: unknown, prefix = ""): string[] {
  if (JSON.stringify(a) === JSON.stringify(b)) return []
  if (a && b && typeof a === "object" && typeof b === "object" && !Array.isArray(a) && !Array.isArray(b)) {
    const keys = [...new Set([...Object.keys(a as object), ...Object.keys(b as object)])].sort()
    return keys.flatMap(key => diffKeys((a as Record<string, unknown>)[key], (b as Record<string, unknown>)[key], prefix ? `${prefix}.${key}` : key))
  }
  return [prefix || "<root>"]
}
