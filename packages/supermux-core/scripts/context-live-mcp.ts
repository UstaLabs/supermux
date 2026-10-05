/**
 * Host MCP servers (slice C2) live check: real CLIs driven through the core with host servers whose
 * tools are TypeScript functions in this process.
 *
 *   bun scripts/context-live-mcp.ts [claude] [codex] [grok] [opencode]
 *
 * Per agent, one session with two host servers ("orders", "calendar") + one external stdio server
 * ("extprobe", the C0 probe server); every tool returns its own random probe token and the prompts
 * never contain one:
 *   (a) the agent calls a tool on each of the three servers and repeats all three tokens;
 *   (b) mid-session `orders.add("late_token")`: Claude/OpenCode take it live (same keeper agentPid,
 *       list_changed), Codex/Grok through a relaunch at idle; the next turn calls it;
 *   (c) a tool that throws: the agent repeats the error text (a token inside the message);
 *   (d) a subagent calls a host tool (`whoami`); ctx.sessionId must be the session's id, and a
 *       normalized tool event must carry a subagentId;
 *   (e) detached restart: core.close({ agents: "detach" }), a NEW core with new server objects of the
 *       same names (and tool sets), resume → the SAME agent pid (re-attached), and a call reaches the
 *       new host through the reconnected bridge (the new orders object returns a NEW token).
 *
 * Scratch (workdirs, agent homes, core state) under ~/.cache/context-c2/run-<stamp>/; credentials are
 * only READ from the user's homes (same scheme as scripts/context-live.ts). The real homes are
 * snapshotted before and after; every child is shut down and leftover bridges are looked for.
 */
import { spawnSync } from "node:child_process"
import { chmodSync, copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from "node:fs"
import { homedir } from "node:os"
import { join } from "node:path"
import { z } from "zod"
import { createCore } from "../src/index.js"
import type { AgentDriver, Core, CoreEvent, Session } from "../src/index.js"
import { claude } from "../src/claude/index.js"
import { codex } from "../src/codex/index.js"
import { grok, opencode } from "../src/agents/index.js"
import { codexTokenArgs, CODEX_TOKEN_ENV } from "../src/accounts/adapters/codex.js"
import { mcpServer, tool, type HostMcpServer, type ToolContext } from "../src/mcp/index.js"

const HOME = homedir()
const RUN = join(HOME, ".cache", "context-c2", `run-${new Date().toISOString().replace(/[:.]/g, "-")}`)
const MCP_SERVER = join(HOME, ".cache", "context-c0", "mcp", "probe-mcp-server.mjs")
const LIMITS = { interruptTimeoutMs: 10_000, maxPending: 16, outstandingActivity: 256 }
const KEEPER = { parkedDeadlineMs: 60_000, journalMaxBytes: 8_000_000, connectTimeoutMs: 15_000 }
const TIMEOUTS = { setupTimeoutMs: 120_000, shutdownTimeoutMs: 5_000, maxFrameBytes: 32 << 20 }
const ACP = { ...TIMEOUTS, maxOutstandingActivity: 256, cancelRetryIntervalMs: 500, cancelRetryTimeoutMs: 10_000, inheritEnv: false }
const ALL = ["claude", "codex", "grok", "opencode"] as const
type Agent = typeof ALL[number]
const wanted = (process.argv.slice(2).filter(a => (ALL as readonly string[]).includes(a)) as Agent[])
const agents: Agent[] = wanted.length ? wanted : [...ALL]
const LIST_CHANGED: Record<Agent, boolean> = { claude: true, opencode: true, codex: false, grok: false }

type Line = { agent: string; check: string; ok: boolean; evidence: unknown }
const results: Line[] = []
function record(agent: string, check: string, ok: boolean, evidence: unknown) {
  results.push({ agent, check, ok, evidence })
  console.log(`${ok ? "PASS" : "FAIL"}  ${agent.padEnd(8)} ${check} :: ${JSON.stringify(evidence).slice(0, 600)}`)
}

const NOUNS = ["MAPLE", "OTTER", "QUARTZ", "FALCON", "CEDAR", "BISON", "COBALT", "HERON", "TUNDRA", "LYNX", "EMBER", "WALRUS", "SAFFRON", "GLACIER", "MARLIN", "PEBBLE", "ORCHID", "JACKAL"]
let noun = Math.floor(Math.random() * NOUNS.length)
const word = () => `${NOUNS[noun++ % NOUNS.length]}${Math.floor(Math.random() * 9000 + 1000)}`
const has = (reply: string, w: string) => reply.toUpperCase().includes(w)
const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms))

function baseEnv(): Record<string, string> {
  const env: Record<string, string> = {}
  for (const [k, v] of Object.entries(process.env)) {
    if (v === undefined || /^(MUX_|CLAUDECODE|CLAUDE_CODE_|CLAUDE_CONFIG_DIR|CODEX_|CURSOR_|GROK_|OPENCODE|XDG_|ANTHROPIC_|OPENAI_|XAI_|SUPERMUX_)/.test(k)) continue
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

function scratchRepo(dir: string): string {
  mkdirSync(dir, { recursive: true })
  writeFileSync(join(dir, "README.md"), "scratch repo for the host MCP live check\n")
  spawnSync("git", ["init", "-q"], { cwd: dir })
  spawnSync("git", ["-c", "user.email=probe@example.invalid", "-c", "user.name=probe", "add", "."], { cwd: dir })
  spawnSync("git", ["-c", "user.email=probe@example.invalid", "-c", "user.name=probe", "commit", "-qm", "init"], { cwd: dir })
  return dir
}

// ---------------------------------------------------------------- host servers
type Words = { orders: string; calendar: string; ext: string; late: string; fail: string; whoami: string; restart: string }
type Calls = Array<{ server: string; tool: string; ctx: Omit<ToolContext, "signal"> }>

/** The two host servers (a fresh pair per core; `ordersWord` is what lookup returns). */
function hostServers(words: Words, ordersWord: string, calls: Calls, late: boolean): { orders: HostMcpServer; calendar: HostMcpServer } {
  const log = (server: string, toolName: string, ctx: ToolContext) => calls.push({ server, tool: toolName, ctx: { sessionId: ctx.sessionId, agent: ctx.agent, server: ctx.server, ...(ctx.account ? { account: ctx.account } : {}) } })
  const orders = mcpServer({
    name: "orders",
    instructions: "Order lookup for the probe shop.",
    tools: {
      lookup: tool({
        description: "Look up an order by id. Returns the order probe token.",
        input: z.object({ id: z.string().describe("Order id") }),
        run: ({ id }, ctx) => { log("orders", "lookup", ctx); return `Order ${id}: the order probe token is ${ordersWord}.` },
      }),
      fail_probe: tool({
        description: "A tool that always fails (for testing error reporting).",
        run: (_args, ctx) => { log("orders", "fail_probe", ctx); throw new Error(`Warehouse offline: failure probe token ${words.fail}`) },
      }),
      whoami: tool({
        description: "Returns the whoami probe token.",
        run: (_args, ctx) => { log("orders", "whoami", ctx); return `The whoami probe token is ${words.whoami}.` },
      }),
    },
  })
  if (late) orders.add("late_token", lateTool(words, calls))
  const calendar = mcpServer({
    name: "calendar",
    tools: {
      today: tool({ description: "Returns today's calendar probe token.", run: (_args, ctx) => { log("calendar", "today", ctx); return `The calendar probe token is ${words.calendar}.` } }),
    },
  })
  return { orders, calendar }
}

function lateTool(words: Words, calls: Calls) {
  return tool({
    description: "Returns the late probe token.",
    run: (_args, ctx) => { calls.push({ server: "orders", tool: "late_token", ctx: { sessionId: ctx.sessionId, agent: ctx.agent, server: ctx.server } }); return `The late probe token is ${words.late}.` },
  })
}

// ---------------------------------------------------------------- drivers (same scheme as context-live.ts)
type Setup = { driver: AgentDriver; profiles?: Record<string, { agent: string; env?: Record<string, string>; args?: string[] }>; authProfile?: string; keeperDir: string }

function setup(agent: Agent, dir: string): Setup {
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

// ---------------------------------------------------------------- prompts
const NO_SHELL = "Do not run shell commands and do not read files."
const ASK_A = `You have MCP servers named "orders", "calendar" and "extprobe". Call these three tools: the tool "lookup" of the orders server (with id "A1"), the tool "today" of the calendar server, and the tool "get_code_word" of the extprobe server. Each returns a probe token (or code word). Reply with the three tokens separated by spaces and nothing else. ${NO_SHELL}`
const ASK_LATE = `The MCP server "orders" has a tool named "late_token" (it may be new). Call it and reply with the probe token it returns, nothing else. ${NO_SHELL}`
const ASK_FAIL = `Call the tool "fail_probe" of the MCP server "orders". It returns an error. Reply with the exact error text it returned, nothing else. ${NO_SHELL}`
const ASK_SUB = `Start ONE subagent (use your subagent / Agent / Task / spawn-agent tool) and instruct it to call the tool "whoami" of the MCP server "orders" and report the probe token it returns. Do NOT call that tool yourself. When the subagent is done, reply with the probe token it reported and nothing else. ${NO_SHELL}`
const ASK_RESTART = `Call the tool "lookup" of the MCP server "orders" with id "E5" and reply with the probe token it returns, nothing else. ${NO_SHELL}`

function assistantText(events: CoreEvent[], start: number, id: string, main = true): string {
  return events.slice(start).flatMap(e => e.type === "session.event" && e.sessionId === id && e.event.kind === "assistant-message" && (!main || !(e.event as { subagentId?: string }).subagentId) ? [e.event.text] : []).join("\n").trim()
}

async function ask(session: Session, events: CoreEvent[], text: string, timeoutMs = 300_000): Promise<string> {
  const start = events.length
  const receipt = await session.send({ content: [{ type: "text", text }], whenBusy: "reject" })
  const completion = await Promise.race([receipt.completed, new Promise<never>((_, reject) => setTimeout(() => reject(new Error("turn timed out")), timeoutMs))])
  if (completion.status !== "completed") throw new Error(`turn ${completion.status}: ${completion.status === "failed" ? completion.error.message : ""}`)
  await sleep(200)
  return assistantText(events, start, session.id)
}

/** Every pid (other than ours) whose environment points a bridge at `socket`. */
function bridgesFor(socket: string | undefined): number[] {
  if (!socket) return []
  const out: number[] = []
  for (const name of readdirSync("/proc")) {
    if (!/^\d+$/.test(name) || Number(name) === process.pid) continue
    try { if (readFileSync(`/proc/${name}/environ`, "utf8").split("\0").includes(`SUPERMUX_MCP_SOCKET=${socket}`)) out.push(Number(name)) } catch { /* gone or not ours */ }
  }
  return out
}

// ---------------------------------------------------------------- one agent
async function check(agent: Agent) {
  const dir = join(RUN, agent)
  const work = scratchRepo(join(dir, "work"))
  const state = join(dir, "state")
  const s = setup(agent, dir)
  const id = `mcp-${agent}`
  const words: Words = { orders: word(), calendar: word(), ext: word(), late: word(), fail: word(), whoami: word(), restart: word() }
  const calls: Calls = []
  const events: CoreEvent[] = []
  const external = { name: "extprobe", command: process.execPath, args: [MCP_SERVER], env: { PROBE_LOG: join(dir, "extprobe.log"), PROBE_SERVER: "extprobe", PROBE_WORD: words.ext } }
  let servers = hostServers(words, words.orders, calls, false)
  const open = (registered: HostMcpServer[]): Core => {
    const core = createCore({ stateDirectory: state, agents: [s.driver], limits: LIMITS, mcpServers: registered, ...(s.profiles ? { profiles: s.profiles } : {}) })
    core.subscribe(e => { events.push(e) })
    return core
  }
  const agentPid = (): number | undefined => { try { return JSON.parse(readFileSync(join(s.keeperDir, "keepers", id, "status.json"), "utf8")).agentPid } catch { return undefined } }
  const live = (core: Core) => core.sessions.live(id)!
  let core = open([servers.orders, servers.calendar])
  let socket: string | undefined
  try {
    record(agent, "capabilities", true, core.capabilities(agent))
    await core.sessions.create({
      id, agent, cwd: work, ...(s.authProfile ? { authProfile: s.authProfile } : {}),
      context: { mcpServers: [servers.orders, { kind: "host", name: "calendar" }, external] },
    })
    socket = core.mcp.socket()
    record(agent, "record stores host servers by name", true, (await core.sessions.get(id))!.context)

    // (a) two host servers + one external
    try {
      const reply = await ask(live(core), events, ASK_A)
      const hostCalls = calls.filter(call => call.ctx.sessionId === id).map(call => `${call.server}/${call.tool}`)
      record(agent, "(a) a tool on each of 2 host + 1 external server: all three tokens", has(reply, words.orders) && has(reply, words.calendar) && has(reply, words.ext), { words: [words.orders, words.calendar, words.ext], reply, hostCalls, toolEvents: events.filter(e => e.type === "tool.finished").map(e => e.type === "tool.finished" && `${e.server}/${e.tool} ok=${e.ok}`) })
    } catch (error) { record(agent, "(a) three servers", false, { error: (error as Error).message }) }

    // (b) mid-session add
    try {
      const pidBefore = agentPid()
      const mark = events.length
      servers.orders.add("late_token", lateTool(words, calls))
      const expected = LIST_CHANGED[agent] ? "live" : "reload"
      const start = Date.now()
      while (!events.slice(mark).some(e => e.type === "context.updated" && e.sessionId === id) && Date.now() - start < 180_000) await sleep(100)
      const applied = events.slice(mark).flatMap(e => e.type === "context.updated" && e.sessionId === id ? e.applied : [])
      record(agent, `(b) orders.add → context.updated how = ${expected}`, applied.length === 1 && applied[0]!.how === expected, { applied, pidBefore, pidAfterUpdate: agentPid() })
      const reply = await ask(live(core), events, ASK_LATE)
      const pidAfter = agentPid()
      const samePid = pidBefore !== undefined && pidAfter === pidBefore
      record(agent, `(b) next turn calls the new tool (${expected}: ${LIST_CHANGED[agent] ? "same pid" : "new pid"})`, has(reply, words.late) && (LIST_CHANGED[agent] ? samePid : !samePid), { word: words.late, reply, pidBefore, pidAfter, lateCalls: calls.filter(c => c.tool === "late_token").length })
    } catch (error) { record(agent, "(b) mid-session add", false, { error: (error as Error).message }) }

    // (c) a tool that throws
    try {
      const reply = await ask(live(core), events, ASK_FAIL)
      const finished = events.filter(e => e.type === "tool.finished" && e.tool === "fail_probe")
      record(agent, "(c) a throwing tool: the agent sees the error text", has(reply, words.fail), { word: words.fail, reply, finished })
    } catch (error) { record(agent, "(c) throwing tool", false, { error: (error as Error).message }) }

    // (d) a subagent calls a host tool
    if (agent !== "opencode") {
      try {
        const start = events.length
        const before = calls.filter(call => call.tool === "whoami").length
        await ask(live(core), events, ASK_SUB, 400_000)
        // A backgrounded subagent may finish after the turn (Claude then runs a wake-up turn).
        const deadline = Date.now() + 180_000
        while (Date.now() < deadline && !(calls.filter(call => call.tool === "whoami").length > before && has(assistantText(events, start, id), words.whoami))) await sleep(500)
        const whoami = calls.filter(call => call.tool === "whoami").slice(before)
        const subTool = events.slice(start).filter(e => e.type === "session.event" && e.sessionId === id && (e.event as { subagentId?: string }).subagentId
          && (e.event.kind === "mcp-tool" || e.event.kind === "tool-call") && JSON.stringify(e.event).includes("whoami"))
          .map(e => e.type === "session.event" && { kind: e.event.kind, subagentId: (e.event as { subagentId?: string }).subagentId, tool: (e.event as { tool?: string }).tool })
        const reply = assistantText(events, start, id)
        const ok = whoami.length > 0 && whoami.every(call => call.ctx.sessionId === id) && subTool.length > 0 && has(reply, words.whoami)
        record(agent, "(d) a subagent calls a host tool; ctx.sessionId is the session's", ok, { word: words.whoami, reply, whoamiCtx: whoami.map(call => call.ctx), subagentToolEvents: subTool.slice(0, 4) })
      } catch (error) { record(agent, "(d) subagent", false, { error: (error as Error).message }) }
    }

    // (e) detached restart
    try {
      const pidBefore = agentPid()
      const bridgesBefore = bridgesFor(socket)
      await core.close({ agents: "detach" })
      const alive = pidBefore !== undefined && (() => { try { process.kill(pidBefore, 0); return true } catch { return false } })()
      // A new core, new server objects with the same names and tool sets; lookup now returns a NEW token.
      servers = hostServers(words, words.restart, calls, true)
      core = open([servers.orders, servers.calendar])
      const bridgesWhileDown = bridgesFor(socket)
      await core.sessions.resume(id)
      const pidAfter = agentPid()
      record(agent, "(e) detached restart: resume re-attaches the SAME agent pid", pidBefore !== undefined && pidAfter === pidBefore && alive, { pidBefore, pidAfter, agentAliveWhileHostDown: alive, bridgesBefore, bridgesWhileDown })
      const reply = await ask(live(core), events, ASK_RESTART)
      const restartCalls = calls.filter(call => call.tool === "lookup").slice(-1)
      record(agent, "(e) a tool call works through the reconnected bridge (new host's token)", has(reply, words.restart), { word: words.restart, oldWord: words.orders, reply, lastLookupCtx: restartCalls.map(call => call.ctx), bridgesNow: bridgesFor(socket), samePidAfterCall: agentPid() === pidBefore })
    } catch (error) { record(agent, "(e) detached restart", false, { error: (error as Error).message }) }

    record(agent, "claude server/discover note", true, agent === "claude" ? "Claude sends server/discover first; the SDK answers -32601 and Claude falls back to initialize (not special-cased)" : "n/a")
  } finally {
    await core.close({ agents: "shutdown" }).catch(error => console.error("close failed", error))
    await sleep(1500)
    const left = bridgesFor(socket)
    const pid = agentPid()
    const agentAlive = pid !== undefined && (() => { try { process.kill(pid, 0); return true } catch { return false } })()
    record(agent, "all children gone after shutdown (agent + bridges)", left.length === 0 && !agentAlive, { leftoverBridges: left, agentPid: pid, agentAlive })
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
const ours = changed.filter(path => /context-c2|extprobe|supermux-mcp|orders|calendar/.test(path) || (() => { try { return statSync(path).isFile() && statSync(path).size < 1_000_000 && /context-c2|extprobe|SUPERMUX_MCP/.test(readFileSync(path, "utf8")) } catch { return false } })())
record("all", "real agent homes untouched by this run", ours.length === 0, { changedDuringRun: changed, removed, mentioningThisRun: ours })
writeFileSync(join(RUN, "results.json"), JSON.stringify(results, null, 2))
const failed = results.filter(r => !r.ok)
console.log(`\n${results.length - failed.length}/${results.length} checks passed. Results: ${join(RUN, "results.json")}`)
process.exit(failed.length ? 1 : 0)
