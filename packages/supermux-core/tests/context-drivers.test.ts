import { afterEach, expect, setDefaultTimeout, test } from "bun:test"
import { chmodSync, existsSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, readlinkSync, writeFileSync } from "node:fs"
import { rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { fileURLToPath } from "node:url"
import { acp } from "../src/acp/index.js"
import { cursor, grok, opencode } from "../src/agents/index.js"
import { claude } from "../src/claude/index.js"
import { codex } from "../src/codex/index.js"
import type { ContextDrop, LaunchContext } from "../src/context/types.js"
import type { AgentDriver, AuthProfile, DriverContext } from "../src/types.js"
import { TEST_ACP_PERMISSIONS, TEST_CLAUDE_PERMISSIONS, TEST_CODEX_PERMISSIONS } from "./helpers.js"

setDefaultTimeout(20_000)
const dirs: string[] = []
afterEach(async () => { await Promise.all(dirs.splice(0).map(dir => rm(dir, { recursive: true, force: true }))) })
function scratch(): string { const dir = mkdtempSync(join(tmpdir(), "context-drivers-")); dirs.push(dir); return dir }
const keeper = () => ({ stateDirectory: scratch(), limits: { parkedDeadlineMs: 15_000, journalMaxBytes: 1_000_000, connectTimeoutMs: 4000 } })
const fixture = (name: string) => fileURLToPath(new URL(`./fixtures/${name}`, import.meta.url))
const FIXED = { setupTimeoutMs: 5000, shutdownTimeoutMs: 500, maxFrameBytes: 16 * 1024 * 1024 }
const ACP = { ...FIXED, maxOutstandingActivity: 256, cancelRetryIntervalMs: 250, cancelRetryTimeoutMs: 10_000, inheritEnv: true }

function opencodeShim(): string {
  const dir = scratch(), path = join(dir, "opencode")
  writeFileSync(path, `#!/bin/sh\nexec "${process.execPath}" "${fixture("acp-agent.mjs")}" "$@"\n`)
  chmodSync(path, 0o755)
  return path
}

const AGENTS: Record<string, (env: Record<string, string>) => AgentDriver> = {
  claude: env => claude({
    id: "claude", command: process.execPath, args: [fixture("claude-agent.mjs")], env, inheritEnv: true, tools: [], permissionPrompts: "none",
    permissions: TEST_CLAUDE_PERMISSIONS, partialMessages: false, ...FIXED, requestTimeoutMs: 3000, keeper: keeper(),
  }),
  codex: env => codex({
    id: "codex", command: process.execPath, args: [fixture("codex-agent.mjs")], env, inheritEnv: true, sandbox: "read-only", approvalPolicy: "never",
    permissionPrompts: "none", permissions: TEST_CODEX_PERMISSIONS, ...FIXED, requestTimeoutMs: 3000, keeper: keeper(),
  }),
  grok: env => grok({
    id: "grok", command: process.execPath, commandArgs: [fixture("grok-agent.mjs")], env, noLeader: true, mcpServers: [],
    permissions: TEST_ACP_PERMISSIONS, ...ACP, keeper: keeper(),
  }),
  cursor: env => cursor({
    id: "cursor", command: process.execPath, commandArgs: [fixture("cursor-agent.mjs")], env, mcpServers: [],
    permissions: TEST_ACP_PERMISSIONS, ...ACP, keeper: keeper(),
  }),
  opencode: env => opencode({
    id: "opencode", command: opencodeShim(), env, mcpServers: [], permissions: TEST_ACP_PERMISSIONS, ...ACP, keeper: keeper(),
  }),
  acp: env => acp({
    id: "acp", command: process.execPath, args: [fixture("acp-agent.mjs")], env, mcpServers: [], permissions: TEST_ACP_PERMISSIONS,
    ...ACP, keeper: keeper(), captureStderr: false,
  }),
}

type Launched = { argv: string[]; env: Record<string, string>; params: Array<{ method: string; params: any }>; codex: any[] }

async function launch(agent: string, options: { sessionContext?: LaunchContext; resumeId?: string; profile?: AuthProfile; env?: Record<string, string> } = {}): Promise<Launched> {
  const dir = scratch()
  const envTrace = join(dir, "env.json"), paramsTrace = join(dir, "params.jsonl"), trace = join(dir, "trace.jsonl")
  const driver = AGENTS[agent]!({ ENV_TRACE: envTrace, PARAMS_TRACE: paramsTrace, ...(agent === "codex" ? { TRACE: trace } : {}), ...options.env })
  const context: DriverContext = {
    sessionId: "ctx-1", cwd: process.cwd(), signal: new AbortController().signal,
    ...(options.resumeId ? { resumeId: options.resumeId } : {}),
    ...(options.profile ? { profile: options.profile } : {}),
    ...(options.sessionContext ? { sessionContext: options.sessionContext } : {}),
    onUpdate() {}, onExit() {},
    requestPermission: async () => ({ outcome: { outcome: "cancelled" } }), requestAnswers: async () => ({ outcome: "cancelled" as const }),
  }
  const runtime = await driver.open(context)
  try {
    const { argv, env } = JSON.parse(readFileSync(envTrace, "utf8"))
    const lines = (file: string) => existsSync(file) ? readFileSync(file, "utf8").trim().split("\n").filter(Boolean).map(line => JSON.parse(line)) : []
    return { argv, env, params: lines(paramsTrace), codex: lines(trace) }
  } finally { await runtime.close({ mode: "shutdown" }) }
}

/** argv with the scratch-dependent parts made stable. */
function stable(argv: string[]): string[] {
  return argv.map(arg => arg.replace(/^ctx-[0-9a-f-]+$/, "<id>").replace(/--session-id=.*/, "--session-id=<uuid>").replace(/^\d{4,5}$/, "<port>"))
}

const cwdless = <T>(value: T): T => JSON.parse(JSON.stringify(value).replaceAll(JSON.stringify(process.cwd()).slice(1, -1), "<cwd>"))

// Captured from the drivers BEFORE session context existed (commit 699c50bb): a launch without
// context must stay exactly this.
const NEW = [{ method: "session/new", params: { cwd: "<cwd>", mcpServers: [] } }]
const BASELINE: Record<string, unknown> = {
  claude: { argv: ["--print", "--output-format", "stream-json", "--verbose", "--input-format", "stream-json", "--await-initialize", "--tools", "", "--permission-prompts", "none", "--permission-mode", "dontAsk", "--session-id=<uuid>"], params: [] },
  // C3: the policy is per process (launch -c args), never config/batchWrite into CODEX_HOME.
  codex: { argv: ["-c", "sandbox_mode=\"read-only\"", "-c", "approval_policy=\"never\""], params: [], codexMethods: ["initialize", "initialized", "thread/start"], threadStart: { cwd: "<cwd>", approvalPolicy: "never", sandbox: "read-only" } },
  grok: { argv: ["agent", "--no-leader", "stdio"], params: NEW },
  cursor: { argv: ["acp"], params: NEW },
  opencode: { argv: ["acp", "--print-logs", "--log-level", "ERROR", "--port", "<port>"], params: NEW },
  acp: { argv: [], params: NEW },
}

const ENV_KEYS = ["OPENCODE_CONFIG", "XDG_CONFIG_HOME", "CODEX_HOME", "CLAUDE_CONFIG_DIR", "HOME"]

for (const agent of Object.keys(AGENTS)) {
  test(`${agent}: no session context → the launch is byte-for-byte the baseline`, async () => {
    const launched = await launch(agent)
    const shape = cwdless({
      argv: stable(launched.argv),
      params: launched.params,
      ...(agent === "codex" ? { codexMethods: launched.codex.map(line => line.method).filter(Boolean), threadStart: launched.codex.find(line => line.method === "thread/start")?.params } : {}),
    })
    if (process.env.PRINT_BASELINE) { console.log(`BASELINE ${agent} ${JSON.stringify(shape)}`); return }
    expect(shape).toEqual(BASELINE[agent] as never)
    for (const key of ENV_KEYS) expect(launched.env[key]).toBe(process.env[key] as never)
  })
}

// ------------------------------------------------------------ with a context

type Fixture = { root: string; skills: string; plugin: string; launch: (extra?: Partial<LaunchContext>) => LaunchContext }

function contextFixture(): Fixture {
  const root = scratch()
  const skills = join(root, "skills"); mkdirSync(join(skills, "alpha"), { recursive: true })
  writeFileSync(join(skills, "alpha", "SKILL.md"), "---\nname: alpha\ndescription: a\n---\n")
  const plugin = join(root, "my-plugin")
  mkdirSync(join(plugin, ".claude-plugin"), { recursive: true })
  writeFileSync(join(plugin, ".claude-plugin", "plugin.json"), JSON.stringify({ name: "my-plugin" }))
  mkdirSync(join(plugin, "skills", "beta"), { recursive: true })
  writeFileSync(join(plugin, "skills", "beta", "SKILL.md"), "---\nname: beta\ndescription: b\n---\n")
  mkdirSync(join(plugin, "hooks")); writeFileSync(join(plugin, "hooks", "hooks.json"), "{}")
  writeFileSync(join(plugin, ".mcp.json"), JSON.stringify({ mcpServers: {
    plugsrv: { command: "${CLAUDE_PLUGIN_ROOT}/bin/srv", args: ["--root", "${CLAUDE_PLUGIN_ROOT}"], env: { P: "1" } },
    remote: { type: "http", url: "https://example.invalid/mcp" },
  } }))
  mkdirSync(join(plugin, ".opencode", "plugins"), { recursive: true })
  writeFileSync(join(plugin, ".opencode", "plugins", "x.js"), "export const X = async () => ({})\n")
  const launch = (extra: Partial<LaunchContext> = {}): LaunchContext => {
    const directory = join(root, "state", "context", "ctx-1")
    mkdirSync(directory, { recursive: true })
    return {
      instructions: "Probe instructions.", skills: [skills], plugins: [plugin],
      mcpServers: [{ name: "ctx", command: "/bin/echo", args: ["a b"], env: { K: "v \"q\"" } }],
      directory, launch: "create", dropped: [], fingerprint: "fixture", ...extra,
    }
  }
  return { root, skills, plugin, launch }
}

const afterFlag = (argv: string[], flag: string) => argv.flatMap((arg, i) => arg === flag ? [argv[i + 1]!] : [])

test("claude: instructions file, one --plugin-dir folder of plugins, --mcp-config; no --strict-mcp-config", async () => {
  const f = contextFixture()
  const context = f.launch()
  const { argv } = await launch("claude", { sessionContext: context })
  const dir = context.directory
  expect(argv.slice(-6)).toEqual(["--append-system-prompt-file", join(dir, "instructions.md"), "--plugin-dir", join(dir, "plugins"), "--mcp-config", join(dir, "mcp.json")])
  expect(argv).not.toContain("--strict-mcp-config")
  expect(readFileSync(join(dir, "instructions.md"), "utf8")).toBe("Probe instructions.")
  expect(readdirSync(join(dir, "plugins")).sort()).toEqual(["01-my-plugin", "supermux-skills-1"])
  expect(readlinkSync(join(dir, "plugins", "01-my-plugin"))).toBe(f.plugin)
  expect(readlinkSync(join(dir, "plugins", "supermux-skills-1", "skills"))).toBe(f.skills)
  expect(JSON.parse(readFileSync(join(dir, "plugins", "supermux-skills-1", ".claude-plugin", "plugin.json"), "utf8")).name).toBe("supermux-skills-1")
  expect(JSON.parse(readFileSync(join(dir, "mcp.json"), "utf8"))).toEqual({ mcpServers: { ctx: { type: "stdio", command: "/bin/echo", args: ["a b"], env: { K: "v \"q\"" } } } })
})

test("claude: a host's own --append-system-prompt-file is carried in first (Claude keeps only the last one)", async () => {
  const f = contextFixture()
  const host = join(f.root, "host.md"); writeFileSync(host, "Host prompt.")
  const context = f.launch({ skills: [], plugins: [], mcpServers: [] })
  const { argv } = await launch("claude", { sessionContext: context, profile: { agent: "claude", args: ["--append-system-prompt-file", host] } })
  // The plugin folder is always passed (empty here) so skills and plugins can be added live later.
  expect(argv.slice(-4)).toEqual(["--append-system-prompt-file", join(context.directory, "instructions.md"), "--plugin-dir", join(context.directory, "plugins")])
  expect(readFileSync(join(context.directory, "instructions.md"), "utf8")).toBe("Host prompt.\n\nProbe instructions.")
})

test("claude: dropped items are not applied", async () => {
  const f = contextFixture()
  const dropped: ContextDrop[] = [{ kind: "instructions", item: "instructions", reason: "x" }, { kind: "mcpServers", item: "ctx", reason: "x" }]
  const context = f.launch({ dropped, skills: [], plugins: [] })
  const { argv } = await launch("claude", { sessionContext: context })
  expect(argv).not.toContain("--append-system-prompt-file")
  expect(argv).not.toContain("--mcp-config")
})

test("codex: -c mcp_servers per process (pre-approved), extraRoots before thread/start, developerInstructions on create", async () => {
  const f = contextFixture()
  const context = f.launch()
  const { argv, codex: lines, env } = await launch("codex", { sessionContext: context })
  const c = afterFlag(argv, "-c")
  expect(c).toContain('mcp_servers.ctx.command="/bin/echo"')
  expect(c).toContain('mcp_servers.ctx.args=["a b"]')
  expect(c).toContain('mcp_servers.ctx.env={ "K" = "v \\"q\\"" }')
  expect(c).toContain('mcp_servers.ctx.default_tools_approval_mode="approve"')
  expect(c).toContain(`mcp_servers.plugsrv.command=${JSON.stringify(join(f.plugin, "bin/srv"))}`)
  expect(c).toContain(`mcp_servers.plugsrv.args=["--root", ${JSON.stringify(f.plugin)}]`)
  expect(c.some(entry => entry.startsWith("mcp_servers.remote"))).toBe(false)
  const methods = lines.map(line => line.method).filter(Boolean)
  expect(methods.indexOf("skills/extraRoots/set")).toBeGreaterThan(-1)
  expect(methods.indexOf("skills/extraRoots/set")).toBeLessThan(methods.indexOf("thread/start"))
  expect(lines.find(line => line.method === "skills/extraRoots/set").params).toEqual({ extraRoots: [f.skills, join(f.plugin, "skills")] })
  expect(lines.find(line => line.method === "thread/start").params.developerInstructions).toBe("Probe instructions.")
  expect(env.CODEX_HOME).toBe(process.env.CODEX_HOME as never)
})

test("codex: resume sends no developerInstructions; drops name plugin parts it cannot map", async () => {
  const f = contextFixture()
  const { codex: lines } = await launch("codex", { sessionContext: f.launch({ launch: "resume" }), resumeId: "native-1" })
  const resume = lines.find(line => line.method === "thread/resume")
  expect(resume).toBeDefined()
  expect("developerInstructions" in resume.params).toBe(false)
  const driver = AGENTS.codex!({})
  const drops = driver.context!.drops!(f.launch())
  expect(drops.map(drop => drop.item).sort()).toEqual([`${f.plugin} (.opencode/plugins)`, `${f.plugin} (hooks)`, `${f.plugin} (mcpServers.remote)`].sort())
})

test("grok: --plugin-dir per plugin and skills wrapper before stdio, _meta.rules + mcpServers on session/new only", async () => {
  const f = contextFixture()
  const context = f.launch()
  const created = await launch("grok", { sessionContext: context })
  const dir = context.directory
  expect(created.argv).toEqual(["agent", "--no-leader", "--plugin-dir", join(dir, "plugins", "01-my-plugin"), "--plugin-dir", join(dir, "plugins", "supermux-skills-1"), "stdio"])
  const created1 = created.params.find(p => p.method === "session/new")!.params
  expect(created1._meta).toEqual({ rules: "Probe instructions." })
  expect(created1.mcpServers).toEqual([{ name: "ctx", command: "/bin/echo", args: ["a b"], env: [{ name: "K", value: "v \"q\"" }] }])
  const resumed = await launch("grok", { sessionContext: f.launch({ launch: "resume" }), resumeId: "grok-1" })
  const load = resumed.params.find(p => p.method === "session/load")!.params
  expect(load._meta).toBeUndefined()
  expect(load.mcpServers.map((server: { name: string }) => server.name)).toEqual(["ctx"])
})

test("cursor: no --plugin-dir (ignored by its ACP server), mcpServers on session/new, instructions as the first prompt's preamble", async () => {
  const f = contextFixture()
  const context = f.launch()
  const { argv, params } = await launch("cursor", { sessionContext: context })
  expect(argv).toEqual(["acp"])
  expect(existsSync(join(context.directory, "plugins"))).toBe(false)
  const created = params.find(p => p.method === "session/new")!.params
  expect(created.mcpServers.map((s: { name: string }) => s.name)).toEqual(["ctx"])
  expect(created._meta).toBeUndefined()
  const caps = AGENTS.cursor!({}).context!.capabilities
  expect(caps.instructions.support).toBe("supported")
  expect(caps.skills.support).toBe("unsupported")
  expect(caps.plugins.support).toBe("unsupported")
  expect(caps.mcpServers.support).toBe("supported")
  expect(AGENTS.cursor!({}).context!.mcpListChanged).toBeFalsy()
})

/** Opens cursor with a context, sends two prompts, and returns the prompt blocks it received. */
async function cursorPrompts(sessionContext: LaunchContext, options: { resumeId?: string; env?: Record<string, string> } = {}) {
  const dir = scratch(), paramsTrace = join(dir, "params.jsonl")
  const driver = AGENTS.cursor!({ ENV_TRACE: join(dir, "env.json"), PARAMS_TRACE: paramsTrace, ...options.env })
  const runtime = await driver.open({
    sessionId: "ctx-1", cwd: process.cwd(), signal: new AbortController().signal, sessionContext,
    ...(options.resumeId ? { resumeId: options.resumeId } : {}),
    onUpdate() {}, onExit() {},
    requestPermission: async () => ({ outcome: { outcome: "cancelled" } }), requestAnswers: async () => ({ outcome: "cancelled" as const }),
  })
  try {
    await runtime.prompt([{ type: "text", text: "hello" }], new AbortController().signal)
    await runtime.prompt([{ type: "text", text: "again" }], new AbortController().signal)
  } finally { await runtime.close({ mode: "shutdown" }) }
  return readFileSync(paramsTrace, "utf8").trim().split("\n").map(line => JSON.parse(line)).filter(line => line.method === "session/prompt").map(line => line.params.prompt)
}

test("cursor: the instructions preamble leads the conversation's first prompt only", async () => {
  const f = contextFixture()
  const [first, second] = await cursorPrompts(f.launch())
  expect(first).toHaveLength(2)
  expect(first[0].type).toBe("text")
  expect(first[0].text).toContain("<session-instructions>")
  expect(first[0].text).toContain("Probe instructions.")
  expect(first[1]).toEqual({ type: "text", text: "hello" })
  expect(second).toEqual([{ type: "text", text: "again" }])
})

test("cursor: a loaded conversation that already has a turn gets no preamble; one without a turn still does", async () => {
  const f = contextFixture()
  const withTurn = await cursorPrompts(f.launch({ launch: "resume" }), { resumeId: "agent-1", env: { REPLAY_USER: "1" } })
  expect(withTurn[0]).toEqual([{ type: "text", text: "hello" }])
  const noTurn = await cursorPrompts(f.launch({ launch: "resume" }), { resumeId: "agent-1" })
  expect(noTurn[0]).toHaveLength(2)
  expect(noTurn[0][0].text).toContain("Probe instructions.")
  expect(noTurn[1]).toEqual([{ type: "text", text: "again" }])
})

test("opencode: a session OPENCODE_CONFIG (instructions, skills.paths kept with the global ones, JS plugin) + ACP mcpServers", async () => {
  const f = contextFixture()
  const xdg = join(f.root, "xdg"); mkdirSync(join(xdg, "opencode"), { recursive: true })
  writeFileSync(join(xdg, "opencode", "opencode.json"), JSON.stringify({ skills: { paths: ["/global/skills"] } }))
  const context = f.launch()
  const { env, params, argv } = await launch("opencode", { sessionContext: context, env: { XDG_CONFIG_HOME: xdg } })
  expect(stable(argv)).toEqual(["acp", "--print-logs", "--log-level", "ERROR", "--port", "<port>"])
  expect(env.OPENCODE_CONFIG).toBe(join(context.directory, "opencode.json"))
  expect(env.XDG_CONFIG_HOME).toBe(xdg)
  const config = JSON.parse(readFileSync(env.OPENCODE_CONFIG!, "utf8"))
  expect(config.instructions).toEqual([join(context.directory, "instructions.md")])
  expect(config.skills.paths).toEqual(["/global/skills", f.skills, join(f.plugin, "skills")])
  expect(config.plugin).toEqual([`file://${join(f.plugin, ".opencode", "plugins", "x.js")}`])
  expect(params.find(p => p.method === "session/new")!.params.mcpServers.map((s: { name: string }) => s.name)).toEqual(["ctx", "plugsrv"])
  expect(lstatSync(xdg).isDirectory()).toBe(true)
  expect(readdirSync(join(xdg, "opencode"))).toEqual(["opencode.json"])
})

test("opencode: a host's own OPENCODE_CONFIG is carried into the session file", async () => {
  const f = contextFixture()
  const host = join(f.root, "host.json"); writeFileSync(host, JSON.stringify({ instructions: ["/host.md"], model: "x/y" }))
  const context = f.launch({ plugins: [], mcpServers: [], skills: [] })
  const { env } = await launch("opencode", { sessionContext: context, env: { OPENCODE_CONFIG: host } })
  const config = JSON.parse(readFileSync(env.OPENCODE_CONFIG!, "utf8"))
  expect(config).toEqual({ instructions: ["/host.md", join(context.directory, "instructions.md")], model: "x/y" })
})

test("generic acp: mcpServers only (unverified); collisions with driver servers are drops", async () => {
  const f = contextFixture()
  const { params } = await launch("acp", { sessionContext: f.launch({ instructions: undefined, skills: [], plugins: [] }) })
  expect(params.find(p => p.method === "session/new")!.params.mcpServers.map((s: { name: string }) => s.name)).toEqual(["ctx"])
  const driver = acp({ id: "acp", command: "x", args: [], mcpServers: [{ name: "ctx", command: "y", args: [], env: [] }], permissions: TEST_ACP_PERMISSIONS, ...ACP, keeper: keeper(), captureStderr: false })
  expect(driver.context!.capabilities.instructions.support).toBe("unsupported")
  expect(driver.context!.drops!(f.launch())).toEqual([{ kind: "mcpServers", item: "ctx", reason: "The driver already passes an MCP server named ctx" }])
})

// ------------------------------------------------------------ in flight (C1b): live control on the running process

async function openLive(agent: "claude" | "codex", sessionContext: LaunchContext, env: Record<string, string> = {}) {
  const dir = scratch()
  const control = join(dir, "control.jsonl"), trace = join(dir, "trace.jsonl")
  const driver = AGENTS[agent]!({ CONTROL_TRACE: control, TRACE: trace, ...env })
  const runtime = await driver.open({
    sessionId: "ctx-live", cwd: process.cwd(), signal: new AbortController().signal, sessionContext,
    onUpdate() {}, onExit() {},
    requestPermission: async () => ({ outcome: { outcome: "cancelled" } }), requestAnswers: async () => ({ outcome: "cancelled" as const }),
  })
  const lines = (file: string) => existsSync(file) ? readFileSync(file, "utf8").trim().split("\n").filter(Boolean).map(line => JSON.parse(line)) : []
  return { runtime, control: () => lines(control), trace: () => lines(trace) }
}

test("claude live: skills/plugins edit the launched folder + reload_plugins; MCP add/remove via mcp_set_servers (dynamic set only)", async () => {
  const f = contextFixture()
  const extra = join(f.root, "skills-2"); mkdirSync(join(extra, "gamma"), { recursive: true })
  const launched = f.launch({ instructions: undefined })
  const { runtime, control } = await openLive("claude", launched)
  try {
    const live = runtime.context!
    expect(live.live({ kind: "skills", op: "add", item: extra }, launched)).toBe(true)
    expect(live.live({ kind: "mcpServers", op: "add", item: "dyn" }, launched)).toBe(true)
    // A launch server (--mcp-config) cannot be removed live.
    expect(live.live({ kind: "mcpServers", op: "remove", item: "ctx" }, launched)).toBe(false)
    const next = { ...launched, skills: [f.skills, extra], mcpServers: [...launched.mcpServers, { name: "dyn", command: "/bin/dyn", args: [], env: {} }] }
    expect(await live.apply(next, [{ kind: "skills", op: "add", item: extra }, { kind: "mcpServers", op: "add", item: "dyn" }], {})).toEqual([])
    const plugins = join(launched.directory, "plugins")
    expect(readdirSync(plugins).sort()).toEqual(["01-my-plugin", "supermux-skills-1", "supermux-skills-2"])
    expect(readlinkSync(join(plugins, "supermux-skills-2", "skills"))).toBe(extra)
    expect(control()).toEqual([{ subtype: "reload_plugins" }, { subtype: "mcp_set_servers", servers: { dyn: { type: "stdio", command: "/bin/dyn", args: [], env: {} } } }])
    // Now dyn is dynamic: removable live; removing it sends the set without it.
    expect(live.live({ kind: "mcpServers", op: "remove", item: "dyn" }, next)).toBe(true)
    const after = { ...next, skills: [extra], mcpServers: launched.mcpServers }
    await live.apply(after, [{ kind: "skills", op: "remove", item: f.skills }, { kind: "mcpServers", op: "remove", item: "dyn" }], {})
    expect(readdirSync(plugins).sort()).toEqual(["01-my-plugin", "supermux-skills-2"])
    expect(control().slice(2)).toEqual([{ subtype: "reload_plugins" }, { subtype: "mcp_set_servers", servers: {} }])
  } finally { await runtime.close({ mode: "shutdown" }) }
})

test("claude live: holdOnCacheImpact passes hold_on_cache_impact; a held reload is refused and the folder restored", async () => {
  const f = contextFixture()
  const launched = f.launch({ instructions: undefined, plugins: [], mcpServers: [] })
  const { runtime, control } = await openLive("claude", launched, { HOLD: "1" })
  try {
    const before = readdirSync(join(launched.directory, "plugins")).sort()
    const change = { kind: "plugins" as const, op: "add" as const, item: f.plugin }
    const refused = await runtime.context!.apply({ ...launched, plugins: [f.plugin] }, [change], { holdOnCacheImpact: true })
    expect(refused).toEqual([{ change, reason: expect.stringContaining("held the plugin reload") }])
    expect(control()).toEqual([{ subtype: "reload_plugins", hold_on_cache_impact: true }])
    expect(readdirSync(join(launched.directory, "plugins")).sort()).toEqual(before)
  } finally { await runtime.close({ mode: "shutdown" }) }
})

test("codex live: skills/extraRoots/set with the full new list; plugins with MCP servers and MCP changes are not live", async () => {
  const f = contextFixture()
  const launched = f.launch({ plugins: [], mcpServers: [] })
  const { runtime, trace } = await openLive("codex", launched)
  try {
    const live = runtime.context!
    const extra = join(f.root, "skills-2"); mkdirSync(extra)
    expect(live.live({ kind: "skills", op: "add", item: extra }, launched)).toBe(true)
    expect(live.live({ kind: "plugins", op: "add", item: f.plugin }, launched)).toBe(false) // it has .mcp.json servers
    expect(live.live({ kind: "mcpServers", op: "add", item: "x" }, launched)).toBe(false)
    await live.apply({ ...launched, skills: [extra] }, [{ kind: "skills", op: "remove", item: f.skills }, { kind: "skills", op: "add", item: extra }], {})
    expect(trace().filter(line => line.method === "skills/extraRoots/set").map(line => line.params)).toEqual([{ extraRoots: [f.skills] }, { extraRoots: [extra] }])
    // Instructions never change after creation: no additionalContext on later turns.
    await runtime.prompt([{ type: "text", text: "one" }], new AbortController().signal)
    expect(trace().filter(line => line.method === "turn/start").map(line => line.params.additionalContext)).toEqual([undefined])
  } finally { await runtime.close({ mode: "shutdown" }) }
})

test("keeper: resume with a changed context on a DETACHED Claude session starts a new process; an unchanged one re-attaches", async () => {
  const { createCore } = await import("../src/core.js")
  const f = contextFixture()
  const state = join(f.root, "core-state"), pidFile = join(f.root, "pid")
  const driver = AGENTS.claude!({ PID_FILE: pidFile })
  const open = () => createCore({ stateDirectory: state, agents: [driver], limits: { interruptTimeoutMs: 30, maxPending: 16, outstandingActivity: 256 } })
  const pid = () => Number(readFileSync(pidFile, "utf8"))
  const alive = (n: number) => { try { process.kill(n, 0); return true } catch { return false } }
  let core = open()
  await core.sessions.create({ agent: "claude", cwd: process.cwd(), id: "kp", context: { skills: [f.skills] } })
  const first = pid()
  await core.close({ agents: "detach" })
  core = open()
  await core.sessions.resume("kp")
  expect(pid()).toBe(first)
  await core.close({ agents: "detach" })
  core = open()
  await core.sessions.resume("kp", { context: { skills: [f.skills], plugins: [f.plugin] } })
  const second = pid()
  expect(second).not.toBe(first)
  expect(alive(first)).toBe(false)
  await core.close({ agents: "shutdown" })
  expect(alive(second)).toBe(false)
})
