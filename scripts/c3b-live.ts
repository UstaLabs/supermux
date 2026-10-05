/**
 * C3b live check: mux-shim as the broker's HOST MCP server ("host" mode), through each agent's
 * BROKER core-host (createXCoreHost: prepare → context → the core's real drivers and bridge), with
 * the broker's REAL shared tool handlers (SessionManager.outbound / orchestration over a scratch
 * registry) bound to the host server. NOT the live broker, NOT the preview.
 *
 *   bun scripts/c3b-live.ts [claude] [codex] [grok] [opencode] [--no-restart] [--no-foreign]
 *
 * Everything lives under ~/.cache/context-c3b/live-<stamp>/ (HOME, MUX_HOME, MUX_STATE_DIR, XDG).
 * Real homes are only READ for credentials (as scripts/c3-live.ts). Per agent:
 *   PA session      calls rename_session, list_sessions and reply (with a file): all succeed
 *   worker session  the same three: list_sessions gets the PA gate error, the other two succeed
 *   processes       no external shim (src/shim) and no mux-channel process for either session;
 *                   the core's bridge (--server mux-shim) is what the agent started
 *   restart         host.close({ agents: "detach" }), a NEW host (new core) with the same broker
 *                   registry: the PA session resumes on the SAME agent pid, its bridge reconnects,
 *                   and a reply call lands
 * Claude also: the scratch ~/.claude.json first holds this broker's own mux-shim / mux-channel
 * entries (as an "external" broker writes them) and removeBrokerShimEntries runs as at boot; a
 * third Claude session ("foreign") runs with entries pointing at ANOTHER shim path (the preview's
 * real situation: the live broker's entries) to see what Claude starts.
 */
import { chmodSync, copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs"
import { homedir } from "node:os"
import { join } from "node:path"

const REAL_HOME = homedir()
const RUN = join(REAL_HOME, ".cache", "context-c3b", `live-${new Date().toISOString().replace(/[:.]/g, "-")}`)
const HOME = join(RUN, "home"), MUX = join(RUN, "mux")
for (const d of [HOME, MUX, join(HOME, ".grok"), join(HOME, ".local", "share", "opencode"), join(HOME, ".config")]) mkdirSync(d, { recursive: true })
const claudeToken: string = JSON.parse(readFileSync(join(REAL_HOME, ".claude", ".credentials.json"), "utf8")).claudeAiOauth.accessToken
const codexTokens = JSON.parse(readFileSync(join(REAL_HOME, ".codex", "auth.json"), "utf8")).tokens
copyFileSync(join(REAL_HOME, ".grok", "auth.json"), join(HOME, ".grok", "auth.json")); chmodSync(join(HOME, ".grok", "auth.json"), 0o600)
copyFileSync(join(REAL_HOME, ".local", "share", "opencode", "auth.json"), join(HOME, ".local", "share", "opencode", "auth.json")); chmodSync(join(HOME, ".local", "share", "opencode", "auth.json"), 0o600)
process.env.HOME = HOME
process.env.MUX_HOME = MUX
process.env.MUX_STATE_DIR = join(MUX, "state")
process.env.XDG_CONFIG_HOME = join(HOME, ".config")
process.env.XDG_DATA_HOME = join(HOME, ".local", "share")
process.env.XDG_STATE_HOME = join(HOME, ".local", "state")
process.env.XDG_CACHE_HOME = join(HOME, ".cache")
for (const k of Object.keys(process.env)) if (/^(MUX_SESSION|MUX_DISPLAY|MUX_AGENT|MUX_SOCKETS|MUX_SHIM|CLAUDECODE|CLAUDE_CODE_|CODEX_|OPENAI_|CURSOR_|GROK_|OPENCODE|SUPERMUX_MCP)/.test(k)) delete process.env[k]

const NOUNS = ["MAPLE", "OTTER", "QUARTZ", "FALCON", "CEDAR", "BISON", "COBALT", "HERON", "TUNDRA", "LYNX", "EMBER", "WALRUS", "SAFFRON", "GLACIER", "MARLIN", "PEBBLE"]
let noun = Math.floor(Math.random() * NOUNS.length)
const word = () => `${NOUNS[noun++ % NOUNS.length]}${Math.floor(Math.random() * 9000 + 1000)}`
const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms))

// Imports that read HOME / MUX_HOME / STATE_DIR at load time.
const { AccountRegistry, UsageStore, memoryVault } = await import("../packages/supermux-core/src/accounts/index.js")
const { SOCKETS_DIR } = await import("../src/shared/paths")
const { setMuxShimMode } = await import("../src/core/mux-tools/mode")
const { bindMuxTools } = await import("../src/core/mux-tools/server")
const { muxServersInInit } = await import("../src/core/mux-tools/duplicates")
const { createClaudeCoreHost } = await import("../src/core/agents/claude/core-host")
const { createCodexCoreHost } = await import("../src/core/agents/codex/core-host")
const { createGrokCoreHost } = await import("../src/core/agents/grok/core-host")
const { createOpenCodeCoreHost } = await import("../src/core/agents/opencode/core-host")
const { preAcceptTrust, removeBrokerShimEntries } = await import("../src/core/session-manager/trust")
const { shimSpawnSpec } = await import("../src/core/session-manager/shim-spawn")
const { openDb, runMigrations } = await import("../src/core/storage/db")
const { Registry } = await import("../src/core/session-manager/registry")
const { SessionManager } = await import("../src/core/session-manager/manager")
const { fakePorts } = await import("../tests/helpers/session-manager-ports")
type CoreEvent = import("../packages/supermux-core/src/index.js").CoreEvent
type Session = import("../packages/supermux-core/src/index.js").Session
type Host = import("../packages/supermux-core/src/index.js").Host
mkdirSync(SOCKETS_DIR, { recursive: true })
setMuxShimMode("host")

const registry = new AccountRegistry(join(RUN, "accounts"), ["claude", "codex", "cursor", "grok", "opencode"], {
  vault: memoryVault(), homes: { claudeRoot: join(RUN, "homes", "claude"), codexRoot: join(RUN, "homes", "codex"), grokRoot: join(RUN, "homes", "grok"), cursorRoot: join(RUN, "homes", "cursor") },
})
const usage = new UsageStore(join(registry.directory, "usage.json"))
await registry.add({ id: "claude-tok", agent: "claude", method: "token", secret: claudeToken })
await registry.add({ id: "codex-tok", agent: "codex", method: "token", secret: JSON.stringify({ access_token: codexTokens.access_token, account_id: codexTokens.account_id }) })
const accounts = { registry, usage }

// ---------------------------------------------------------------- the broker side (scratch)
const db = openDb(join(RUN, "db.sqlite3"))
runMigrations(db, join(import.meta.dirname, "..", "src", "core", "storage", "migrations"))
const ports = fakePorts(db)
const replies: Array<{ session: string; text: string; files?: string[] }> = []
ports.outbound.onAssistantMessage = async (session, ev) => { replies.push({ session, text: ev.text, files: ev.files }); return { ok: true as const, delivered: 1 } }
const brokerRegistry = new Registry(db)
const manager = new SessionManager(brokerRegistry, ports)
const calls: Array<{ session: string; op: string; ok: boolean; error?: string; value?: unknown }> = []
bindMuxTools({
  outbound: async (session, op) => { const r = await manager.outbound(session, op); calls.push({ session, op: op.name, ok: r.ok, error: r.error }); return r },
  orchestration: async (session, op) => { const r = await manager.orchestration(session, op); calls.push({ session, op: op.name, ok: r.ok, error: r.error, value: r.value }); return r },
})

type Line = { agent: string; check: string; ok: boolean; evidence: unknown }
const results: Line[] = []
function record(agent: string, check: string, ok: boolean, evidence: unknown) {
  results.push({ agent, check, ok, evidence })
  console.log(`${ok ? "PASS" : "FAIL"}  ${agent.padEnd(8)} ${check} :: ${JSON.stringify(evidence).slice(0, 500)}`)
}

// ---------------------------------------------------------------- processes
function procs(): Array<{ pid: number; ppid: number; args: string; env: string[] }> {
  const out = []
  for (const name of readdirSync("/proc")) {
    if (!/^\d+$/.test(name) || Number(name) === process.pid) continue
    try {
      const env = readFileSync(`/proc/${name}/environ`, "utf8").split("\0")
      if (!env.some(e => e.startsWith("MUX_SESSION_ID=") || e.startsWith("SUPERMUX_MCP_SESSION="))) continue
      const args = readFileSync(`/proc/${name}/cmdline`, "utf8").split("\0").join(" ").trim()
      const ppid = Number(readFileSync(`/proc/${name}/stat`, "utf8").split(") ")[1]!.split(" ")[1])
      out.push({ pid: Number(name), ppid, args, env })
    } catch { /* gone or not ours */ }
  }
  return out
}
/** Processes of session `id`: its bridges (SUPERMUX_MCP_SESSION) and any external shim / mux-channel (MUX_SESSION_ID + src/shim). */
function sessionProcs(id: string) {
  const all = procs()
  const bridges = all.filter(p => p.env.includes(`SUPERMUX_MCP_SESSION=${id}`) && p.args.includes("--server"))
  const shims = all.filter(p => p.env.includes(`MUX_SESSION_ID=${id}`) && /shim\/index\.ts|\sshim$/.test(p.args))
  return {
    bridges: bridges.map(p => ({ pid: p.pid, ppid: p.ppid, server: p.args.split("--server ")[1] })),
    shims: shims.map(p => ({ pid: p.pid, channel: p.env.includes("MUX_CHANNEL_ONLY=1"), args: p.args.slice(-80) })),
  }
}

// ---------------------------------------------------------------- one agent
type Agent = "claude" | "codex" | "grok" | "opencode"
const ALL: Agent[] = ["claude", "codex", "grok", "opencode"]
const argv = process.argv.slice(2)
const wanted = argv.filter((a): a is Agent => (ALL as string[]).includes(a))
const doRestart = !argv.includes("--no-restart")
const doForeign = !argv.includes("--no-foreign")

function makeHost(agent: Agent, state: string): Host {
  const base = { stateDirectory: state, accounts }
  return agent === "claude" ? createClaudeCoreHost(base) : agent === "codex" ? createCodexCoreHost(base) : agent === "grok" ? createGrokCoreHost(base) : createOpenCodeCoreHost(base)
}
const account = (agent: Agent) => agent === "claude" ? "claude-tok" : agent === "codex" ? "codex-tok" : undefined
const configuration = (agent: Agent) => agent === "codex" ? { model: "gpt-5.6-luna", reasoningEffort: "low" } : agent === "grok" ? { reasoningEffort: "low" } : undefined
function extraFor(agent: Agent, id: string, name: string, work: string, pa: boolean): Record<string, unknown> {
  return {
    sessionHome: join(RUN, "agents", agent, name), sessionName: name, sessionId: id, workdir: work, cwd: work,
    ...(agent === "claude" ? { model: "haiku", ...(pa ? { pa: true } : {}) } : agent === "opencode" ? { model: "opencode-go/qwen3.7-plus" } : {}),
  }
}

async function ask(session: Session, events: CoreEvent[], text: string, timeoutMs = 300_000): Promise<string> {
  const start = events.length
  const receipt = await session.send({ content: [{ type: "text", text }], whenBusy: "reject" })
  const completion = await Promise.race([receipt.completed, new Promise<never>((_, reject) => setTimeout(() => reject(new Error("turn timed out")), timeoutMs))])
  if (completion.status !== "completed") throw new Error(`turn ${completion.status}: ${completion.status === "failed" ? completion.error.message : ""}`)
  await sleep(300)
  return events.slice(start).flatMap(e => e.type === "session.event" && e.sessionId === session.id && e.event.kind === "assistant-message" && !(e.event as { subagentId?: string }).subagentId ? [e.event.text] : []).join("\n").trim()
}

const NO_SHELL = "Do not run shell commands and do not read or write files yourself."
const threeCalls = (newName: string, replyWord: string, file: string) =>
  `Use the tools of the MCP server named "mux-shim" for each step, in this order, one call each, even if a step fails: ` +
  `(1) call rename_session with name "${newName}"; (2) call list_sessions with no arguments; ` +
  `(3) call reply with text "${replyWord}" and files ["${file}"]. ` +
  `Then answer with one line per step: the step number and the exact text the tool returned. ${NO_SHELL}`

async function check(agent: Agent) {
  const state = join(RUN, "core", agent)
  const work = join(RUN, `work-${agent}`)
  mkdirSync(join(work, ".git", "info"), { recursive: true })
  const file = join(work, "c3b-report.txt")
  writeFileSync(file, `c3b ${agent} file\n`)
  const stamp = Date.now()
  const paId = `c3b-${agent}-pa-${stamp}`, workerId = `c3b-${agent}-worker-${stamp}`
  const paName = `pa-${agent}-${stamp}`, workerName = `worker-${agent}-${stamp}`
  brokerRegistry.registerPA({ id: paId, name: paName, workdir: work, pid: 0, agent: agent as never, core: true })
  brokerRegistry.register({ id: workerId, name: workerName, workdir: work, pid: 0, agent: agent as never, connected: false, core: true })
  // The reply handler reads the session's adapter kind from the runtime registry.
  manager.registerRuntime(paId, { kind: agent as never, adapter: { kind: agent } as never })
  manager.registerRuntime(workerId, { kind: agent as never, adapter: { kind: agent } as never })

  if (agent === "claude") {
    // As an "external" broker leaves it: this broker's own entries in ~/.claude.json, then the
    // "host" boot cleanup, then the workdir trust (host mode: no mux entries).
    const spec = shimSpawnSpec()
    writeFileSync(join(HOME, ".claude.json"), JSON.stringify({ mcpServers: {
      "mux-shim": { type: "stdio", command: spec.shimCommand, args: spec.shimArgs, env: {} },
      "mux-channel": { type: "stdio", command: spec.shimCommand, args: spec.shimArgs, env: { MUX_CHANNEL_ONLY: "1" } },
    } }, null, 2))
    const removed = removeBrokerShimEntries()
    preAcceptTrust(work)
    const after = JSON.parse(readFileSync(join(HOME, ".claude.json"), "utf8"))
    record(agent, "~/.claude.json: the broker's two entries removed at boot, none re-added", removed.join(",") === "mux-shim,mux-channel" && !after.mcpServers?.["mux-shim"] && !after.mcpServers?.["mux-channel"] && after.projects?.[work]?.hasTrustDialogAccepted === true, { removed, mcpServers: after.mcpServers })
  }

  let host = makeHost(agent, state)
  const events: CoreEvent[] = []
  let unsubscribe = host.core.subscribe(e => { events.push(e) })
  const pa = host.register({ id: paId, env: {}, ...(agent === "codex" ? { command: "codex", args: ["app-server"] } : {}), extra: extraFor(agent, paId, paName, work, true), ...(account(agent) ? { account: account(agent) } : {}) })
  const worker = host.register({ id: workerId, env: {}, ...(agent === "codex" ? { command: "codex", args: ["app-server"] } : {}), extra: extraFor(agent, workerId, workerName, work, false), ...(account(agent) ? { account: account(agent) } : {}) })
  try {
    const paSession = await pa.start({ cwd: work, ...(configuration(agent) ? { configuration: configuration(agent) } : {}) })
    const workerSession = await worker.start({ cwd: work, ...(configuration(agent) ? { configuration: configuration(agent) } : {}) })
    const rec = await host.core.sessions.get(paId)
    record(agent, "context names the host server mux-shim", JSON.stringify(rec?.context?.mcpServers) === JSON.stringify([{ kind: "host", name: "mux-shim" }]), { mcpServers: rec?.context?.mcpServers })

    for (const [role, session, id, newName] of [["pa", paSession, paId, `Renamed PA ${agent} ${stamp}`], ["worker", workerSession, workerId, `Renamed Worker ${agent} ${stamp}`]] as const) {
      const replyWord = word()
      const before = calls.length
      let answer = ""
      try { answer = await ask(session, events, threeCalls(newName, replyWord, file)) } catch (error) { answer = `ERROR ${(error as Error).message}` }
      const mine = calls.slice(before).filter(c => c.session === id)
      const rename = mine.find(c => c.op === "rename_session")
      const list = mine.find(c => c.op === "list_sessions")
      const reply = replies.find(r => r.session === id && r.text.includes(replyWord))
      record(agent, `${role}: rename_session renames the caller (no gate)`, !!rename?.ok && brokerRegistry.get(id)?.name === newName, { call: rename, name: brokerRegistry.get(id)?.name })
      if (role === "pa") record(agent, "pa: list_sessions succeeds", !!list?.ok && Array.isArray(list.value), { ok: list?.ok, names: Array.isArray(list?.value) ? (list!.value as Array<{ name: string }>).map(s => s.name).slice(0, 6) : list })
      else record(agent, "worker: list_sessions gets the gate error", !!list && !list.ok && list.error === "permission denied (can_orchestrate=false)" && /permission denied/i.test(answer), { call: list, answer: answer.slice(0, 300) })
      record(agent, `${role}: reply with a file is delivered by the broker`, !!reply && reply.files?.[0] === file, { reply, answer: answer.slice(0, 300) })
    }

    const paProcs = sessionProcs(paId), workerProcs = sessionProcs(workerId)
    record(agent, "no external shim / mux-channel process; the agent runs the core's bridge", paProcs.shims.length === 0 && workerProcs.shims.length === 0 && paProcs.bridges.some(b => b.server === "mux-shim") && workerProcs.bridges.some(b => b.server === "mux-shim"), { pa: paProcs, worker: workerProcs })
    if (agent === "claude") {
      const inits = events.flatMap(e => e.type === "session.update" && e.update.protocol === "native" ? [{ id: e.sessionId, found: muxServersInInit(e.update.value) }] : []).filter(x => x.found)
      record(agent, "Claude init frame: exactly one mux-shim, no mux-channel", inits.length > 0 && inits.every(x => x.found!.muxShim === 1 && x.found!.muxChannel === 0), { inits })
    }

    await worker.stop({ mode: "shutdown" }).catch(() => {})
    if (doRestart) {
      // Detached restart: the agent survives the host; a NEW host (new core) with the same broker registry.
      const before = sessionProcs(paId)
      const agentPid = before.bridges[0]?.ppid
      await host.close({ agents: "detach" })
      unsubscribe = (() => {}) as never
      const alive = agentPid !== undefined && (() => { try { process.kill(agentPid, 0); return true } catch { return false } })()
      host = makeHost(agent, state)
      host.core.subscribe(e => { events.push(e) })
      const again = host.register({ id: paId, env: {}, ...(agent === "codex" ? { command: "codex", args: ["app-server"] } : {}), extra: extraFor(agent, paId, brokerRegistry.get(paId)!.name, work, true), ...(account(agent) ? { account: account(agent) } : {}) })
      const resumed = await again.start({ cwd: work, ...(configuration(agent) ? { configuration: configuration(agent) } : {}) })
      const after = sessionProcs(paId)
      record(agent, "restart: the detached agent is re-attached (same agent pid), bridges reconnect", agentPid !== undefined && alive && after.bridges.some(b => b.ppid === agentPid), { agentPid, aliveWhileDown: alive, bridgesBefore: before.bridges, bridgesAfter: after.bridges })
      const replyWord = word()
      let answer = ""
      try { answer = await ask(resumed, events, `Call the tool reply of the MCP server "mux-shim" with text "${replyWord}" and files ["${file}"], then answer with the exact text it returned. ${NO_SHELL}`) } catch (error) { answer = `ERROR ${(error as Error).message}` }
      const reply = replies.find(r => r.session === paId && r.text.includes(replyWord))
      record(agent, "restart: reply works through the reconnected bridge (new core)", !!reply, { reply, answer: answer.slice(0, 200), samePid: sessionProcs(paId).bridges.some(b => b.ppid === agentPid) })
    }
  } catch (error) {
    record(agent, "launch / run", false, { error: (error as Error).message, stack: (error as Error).stack?.split("\n").slice(0, 4) })
  } finally {
    await host.close({ agents: "shutdown" }).catch(() => {})
    void unsubscribe
  }
}

/** The preview's real situation: ~/.claude.json holds ANOTHER broker's mux-shim / mux-channel (not ours, so kept). */
async function foreign() {
  const agent: Agent = "claude"
  const work = join(RUN, "work-claude-foreign")
  mkdirSync(join(work, ".git", "info"), { recursive: true })
  const other = ["run", join(RUN, "other-broker", "src", "shim", "index.ts")]
  mkdirSync(join(RUN, "other-broker", "src", "shim"), { recursive: true })
  // A stand-in for the other broker's shim: a stdio MCP server whose tool says where it came from.
  writeFileSync(join(RUN, "other-broker", "src", "shim", "index.ts"), `
let buf = ""
process.stdin.on("data", d => { buf += d; let i; while ((i = buf.indexOf("\\n")) >= 0) { const line = buf.slice(0, i); buf = buf.slice(i + 1); if (!line.trim()) continue; const m = JSON.parse(line)
  const out = (r: any) => process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id: m.id, ...r }) + "\\n")
  if (m.method === "initialize") out({ result: { protocolVersion: m.params.protocolVersion, capabilities: { tools: {} }, serverInfo: { name: "other", version: "1" } } })
  else if (m.method === "tools/list") out({ result: { tools: process.env.MUX_CHANNEL_ONLY === "1" ? [] : [{ name: "list_sessions", description: "FOREIGN list_sessions", inputSchema: { type: "object", properties: {} } }] } })
  else if (m.method === "tools/call") out({ result: { content: [{ type: "text", text: "FOREIGN-SHIM" }] } })
  else if (m.id !== undefined) out({ result: {} })
} })
`)
  writeFileSync(join(HOME, ".claude.json"), JSON.stringify({ mcpServers: {
    "mux-shim": { type: "stdio", command: "bun", args: other, env: {} },
    "mux-channel": { type: "stdio", command: "bun", args: other, env: { MUX_CHANNEL_ONLY: "1" } },
  } }, null, 2))
  const removed = removeBrokerShimEntries()
  preAcceptTrust(work)
  const stamp = Date.now()
  const id = `c3b-claude-foreign-${stamp}`, name = `foreign-${stamp}`
  brokerRegistry.registerPA({ id, name, workdir: work, pid: 0, agent: "claude" as never, core: true })
  manager.registerRuntime(id, { kind: "claude" as never, adapter: { kind: "claude" } as never })
  const host = makeHost(agent, join(RUN, "core", "claude-foreign"))
  const events: CoreEvent[] = []
  host.core.subscribe(e => { events.push(e) })
  try {
    const session = await host.register({ id, env: {}, extra: extraFor(agent, id, name, work, true), account: "claude-tok" }).start({ cwd: work })
    const before = calls.length
    const answer = await ask(session, events, `Call the tool list_sessions of the MCP server "mux-shim" and answer with the exact text it returned. ${NO_SHELL}`)
    const inits = events.flatMap(e => e.type === "session.update" && e.update.protocol === "native" ? [{ found: muxServersInInit(e.update.value), servers: (e.update.value as { mcp_servers?: unknown }).mcp_servers }] : []).filter(x => x.found)
    const otherProcs = procs().filter(p => p.args.includes(join(RUN, "other-broker")))
    const hostCall = calls.slice(before).find(c => c.session === id && c.op === "list_sessions")
    record("claude", "foreign ~/.claude.json entries are kept (not this broker's)", removed.length === 0, { removed })
    record("claude", "foreign mux-shim is shadowed: one mux-shim, the host one answers", inits.every(x => x.found!.muxShim === 1) && !!hostCall?.ok && !/FOREIGN/.test(answer), { inits, hostCall: !!hostCall, answer: answer.slice(0, 200) })
    record("claude", "foreign entries: what Claude started (informational)", true, { otherShimProcesses: otherProcs.map(p => ({ pid: p.pid, channel: p.env.includes("MUX_CHANNEL_ONLY=1") })) })
  } catch (error) {
    record("claude", "foreign run", false, { error: (error as Error).message })
  } finally {
    await host.close({ agents: "shutdown" }).catch(() => {})
  }
}

console.log("run:", RUN)
for (const agent of wanted.length ? wanted : ALL) await check(agent)
if (doForeign && (wanted.length === 0 || wanted.includes("claude"))) await foreign()
await sleep(1500)
const leftovers = procs().filter(p => p.env.some(e => e.startsWith("SUPERMUX_MCP_SESSION=c3b-") || e.startsWith("MUX_SESSION_ID=c3b-")))
record("all", "no agent / bridge process left after shutdown", leftovers.length === 0, leftovers.map(p => ({ pid: p.pid, args: p.args.slice(0, 120) })))
writeFileSync(join(RUN, "results.json"), JSON.stringify({ results, calls, replies }, null, 2))
const failed = results.filter(r => !r.ok)
console.log(`\n${results.length - failed.length}/${results.length} passed  (${RUN})`)
process.exit(failed.length ? 1 : 0)
