/**
 * Migration dry run, step 2: the broker's boot logic on a MIGRATED COPY of the live DB.
 * No broker process, no agent process, no tmux command.
 *
 *   tmux list-windows -a -F '#{session_name}\t#{window_id}\t#{window_name}\t#{pane_pid}\t#{pane_dead}\t#{pane_current_command}' > <dir>/tmux-windows.tsv
 *   bun scripts/migration-dry-run/02-boot-sim.ts <dir> [--live-homes]
 *
 * Real Registry + SessionManager + supervisor over <dir>/db-sim.sqlite3 (a fresh copy of
 * <dir>/db.sqlite3, the step-1 output). Every agent host is the REAL broker core-host
 * (createXCoreHost: prepare, context, the core) with a fake driver that records each open
 * instead of starting an agent. The session backend answers livePid / resolve from the tmux
 * window list captured BEFORE the run (read-only `tmux list-windows`), and its kill() only
 * records. Then, as main.ts: reconcileOnStartup, then resumeAtBoot.
 *
 * Agent homes: by default every live agent_home under ~/.mux/state is rewritten (in the sim copy
 * only) to the same path under <dir>/sim, so prepare writes land in scratch. --live-homes keeps
 * the live paths; run it only inside a read-only sandbox (see run-boot-sim.sh), where every write
 * a prepare attempts fails with EROFS and is reported.
 */
import { paneSessionId } from "../../src/core/session-manager/pane-owner"
import { copyFileSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { homedir } from "node:os"
import { join, resolve } from "node:path"

const REAL_HOME = homedir()
const LIVE_STATE = join(REAL_HOME, ".mux", "state")
const dir = resolve(process.argv[2] ?? join(REAL_HOME, ".cache", "migration-dry"))
const liveHomes = process.argv.includes("--live-homes")
if (dir.startsWith(join(REAL_HOME, ".mux"))) throw new Error("refusing to work inside ~/.mux")
const SIM = join(dir, liveHomes ? "sim-live-homes" : "sim")
rmSync(SIM, { recursive: true, force: true })
mkdirSync(join(SIM, "home"), { recursive: true })
mkdirSync(join(SIM, "tmp"), { recursive: true })
// Everything the broker code resolves from env goes to scratch.
process.env.MUX_HOME = join(SIM, "muxhome")
process.env.MUX_STATE_DIR = join(SIM, "state")
process.env.HOME = join(SIM, "home")
process.env.TMPDIR = join(SIM, "tmp")
process.env.XDG_CONFIG_HOME = join(SIM, "home", ".config")
process.env.XDG_DATA_HOME = join(SIM, "home", ".local", "share")
process.env.OPENAI_API_KEY = "sim-key" // Codex prepare takes the api-key branch: no auth.json copy
for (const k of Object.keys(process.env)) if (/^(MUX_SESSION|MUX_DISPLAY|MUX_AGENT|MUX_SOCKETS|MUX_SHIM|CLAUDECODE|CLAUDE_CODE_|SUPERMUX_MCP)/.test(k)) delete process.env[k]
mkdirSync(process.env.MUX_STATE_DIR, { recursive: true })

const dbPath = join(SIM, "db-sim.sqlite3")
copyFileSync(join(dir, "db.sqlite3"), dbPath)

const { Database } = await import("bun:sqlite")
{
  // Rewrite live agent homes to scratch (sim copy only).
  const raw = new Database(dbPath)
  if (!liveHomes) {
    raw.run("UPDATE sessions SET agent_home = ? || substr(agent_home, ?) WHERE agent_home LIKE ? || '%'", [process.env.MUX_STATE_DIR, LIVE_STATE.length + 1, LIVE_STATE])
  }
  raw.close()
}

const { openDb, runMigrations } = await import("../../src/core/storage/db")
const { MIGRATIONS } = await import("../../src/core/storage/migrations")
const { Registry } = await import("../../src/core/session-manager/registry")
const { SessionManager } = await import("../../src/core/session-manager/manager")
const { createSupervisor, reconcileOnStartup } = await import("../../src/core/session-manager/supervisor")
const { ensureWindowId } = await import("../../src/core/session-manager/window-id")
const { setSessionBackendForTests } = await import("../../src/core/runtime")
const { fakePorts } = await import("../../tests/helpers/session-manager-ports")
const { createClaudeCoreHost } = await import("../../src/core/agents/claude/core-host")
const { createCodexCoreHost } = await import("../../src/core/agents/codex/core-host")
const { createCursorCoreHost } = await import("../../src/core/agents/cursor/core-host")
const { createGrokCoreHost } = await import("../../src/core/agents/grok/core-host")
const { createOpenCodeCoreHost } = await import("../../src/core/agents/opencode/core-host")
const { setClaudeCoreHostFactoryForTests } = await import("../../src/core/agents/claude/core-host-provider")
const { setCodexCoreHostFactoryForTests } = await import("../../src/core/agents/codex/core-host-provider")
const { setCursorCoreHostFactoryForTests } = await import("../../src/core/agents/cursor/core-host-provider")
const { setGrokCoreHostFactoryForTests } = await import("../../src/core/agents/grok/core-host-provider")
const { setOpenCodeCoreHostFactoryForTests } = await import("../../src/core/agents/opencode/core-host-provider")
type AgentDriver = import("../../packages/supermux-core/src/index.js").AgentDriver
type AgentRuntime = import("../../packages/supermux-core/src/index.js").AgentRuntime

// ------------------------------------------------------------------ the real tmux, as captured
type Win = { session: string; id: string; name: string; pid: number; dead: boolean; cmd: string }
const windows: Win[] = readFileSync(join(dir, "tmux-windows.tsv"), "utf8").split("\n").filter(Boolean).map((l) => {
  const [session, id, name, pid, dead, cmd] = l.split("\t")
  return { session: session!, id: id!, name: name!, pid: Number(pid), dead: dead === "1", cmd: cmd! }
})
const killed: Array<{ window: string; heldBy: string }> = []
const backendCalls: string[] = []
const backend = {
  async livePid(id: string) { backendCalls.push(`livePid ${id}`); const w = windows.find((x) => x.id === id && !x.dead && !killed.some((k) => k.window === id)); return w ? w.pid : null },
  async resolve(group: string, name: string) { backendCalls.push(`resolve ${group}/${name}`); return windows.find((x) => x.session === group && x.name === name && !killed.some((k) => k.window === x.id))?.id ?? null },
  async list(group?: string) { return windows.filter((x) => !group || x.session === group).map((x) => ({ id: x.id, name: x.name, pid: x.pid, alive: !x.dead })) },
  async kill(id: string) {
    backendCalls.push(`kill ${id}`)
    const w = windows.find((x) => x.id === id && x.session === "mux")
    killed.push({ window: id, heldBy: w ? w.name : "(no live window)" })
  },
  async create() { throw new Error("sim: create refused") },
  async write() { throw new Error("sim: write refused") },
  async sendKeys() { throw new Error("sim: sendKeys refused") },
  async resize() {},
  async capture() { return null },
  async attach() { throw new Error("sim: attach refused") },
  async interrupt() { throw new Error("sim: interrupt refused") },
}
setSessionBackendForTests(backend as never)

// ------------------------------------------------------------------ fake drivers, real hosts
type Open = { agent: string; sessionId: string; resumeId?: string; at: number }
const opens: Open[] = []
const prepareErrors: Array<{ agent: string; err: string }> = []
function fakeFactory(agent: string) {
  return (): AgentDriver => ({
    id: agent,
    async open(ctx) {
      opens.push({ agent, sessionId: ctx.sessionId, resumeId: ctx.resumeId, at: Date.now() })
      const runtime: AgentRuntime = {
        agentSessionId: ctx.resumeId ?? `sim-${agent}-${ctx.sessionId}`,
        capabilities: { resume: true, steer: false, fork: false, detach: agent === "claude", configure: agent !== "claude", history: false },
        async prompt() { return { stopReason: "end_turn" } },
        async interrupt() {},
        async close() {},
        ...(agent !== "claude" ? { async configure() {}, configuration: () => ({}) } : {}),
      } as AgentRuntime
      return runtime
    },
  }) as AgentDriver
}
const hostState = (agent: string) => join(SIM, "core", agent)
const hosts = {
  claude: createClaudeCoreHost({ stateDirectory: hostState("claude"), driverFactory: fakeFactory("claude") as never }),
  codex: createCodexCoreHost({ stateDirectory: hostState("codex"), driverFactory: fakeFactory("codex") as never }),
  cursor: createCursorCoreHost({ stateDirectory: hostState("cursor"), driverFactory: fakeFactory("cursor") as never, sharedRuntime: null } as never),
  grok: createGrokCoreHost({ stateDirectory: hostState("grok"), driverFactory: fakeFactory("grok") as never }),
  opencode: createOpenCodeCoreHost({ stateDirectory: hostState("opencode"), driverFactory: fakeFactory("opencode") as never }),
}
setClaudeCoreHostFactoryForTests(() => hosts.claude)
setCodexCoreHostFactoryForTests(() => hosts.codex)
setCursorCoreHostFactoryForTests(() => hosts.cursor)
setGrokCoreHostFactoryForTests(() => hosts.grok)
setOpenCodeCoreHostFactoryForTests(() => hosts.opencode)

// ------------------------------------------------------------------ the broker side
const db = openDb(dbPath)
runMigrations(db, MIGRATIONS) // no-op: already migrated in step 1; as main.ts does on every boot
const registry = new Registry(db)
const ports = fakePorts(db)
ports.backend = {
  runtimeTargetIdOf: (s) => ensureWindowId(s, { tmuxSession: "mux", resolve: (g, n) => backend.resolve(g, n), persist: (id, wid) => registry.sessions.setTmuxWindowId(id, wid) }),
  kill: (id) => backend.kill(id),
  // As main.ts: the pane's MUX_SESSION_ID (read-only /proc of the REAL pane pid from the snapshot).
  windowOwner: async (id) => { const pid = await backend.livePid(id); return pid ? paneSessionId(pid) : null },
}
ports.resume.sessionBackend = backend as never
ports.resume.tmuxSession = "mux"
const binds: string[] = []
ports.resume.bind = async (id) => { binds.push(id) }
const manager = new SessionManager(registry, ports)
manager.bootResumeTimeoutMs = 20_000
const supervisor = createSupervisor({ registry, bindSocket: async (id) => { binds.push(id) }, sessionManager: manager })

type Row = { id: string; name: string; agent: string; status: string; core: number; tmux_window_id: string | null; agent_session_id: string | null; agent_home: string | null; user_status: string | null; role: string }
const rows = (): Row[] => db.prepare("SELECT id, name, agent, status, core, tmux_window_id, agent_session_id, agent_home, user_status, role FROM sessions").all() as Row[]
const archivedHash = () => String(Bun.hash(JSON.stringify(db.prepare("SELECT * FROM sessions WHERE status = 'archived' ORDER BY id").all())))
const before = new Map(rows().map((r) => [r.id, r]))
const archivedBefore = archivedHash()
const archivedCount = rows().filter((r) => r.status === "archived").length

// ------------------------------------------------------------------ boot, as main.ts
const t0 = Date.now()
await reconcileOnStartup({ registry, bindSocket: async (id) => { binds.push(id) }, supervisor, sessionBackend: backend as never })
const afterReconcile = new Map(rows().map((r) => [r.id, r]))
await manager.resumeAtBoot()
const bootMs = Date.now() - t0
supervisor.stop()
const after = new Map(rows().map((r) => [r.id, r]))

// ------------------------------------------------------------------ report
const short = (r: Row) => `${r.agent}/${r.name} [${r.id.slice(0, 8)}]`
const statusChanges = [...before.values()].flatMap((b) => {
  const m = afterReconcile.get(b.id)!, a = after.get(b.id)!
  return b.status !== m.status || m.status !== a.status ? [{ session: short(b), before: b.status, afterReconcile: m.status, afterBoot: a.status, window: b.tmux_window_id }] : []
})
const opened = new Map(opens.map((o) => [o.sessionId, o]))
const resumed = [...after.values()].filter((r) => opened.has(r.id))
const notResumedActive = [...after.values()].filter((r) => r.status === "active" && !opened.has(r.id))
const stillSuspended = [...after.values()].filter((r) => r.status === "suspended" && !opened.has(r.id))
const wokenSuspended = [...before.values()].filter((b) => b.status === "suspended" && opened.has(b.id))
const resumeIdMismatch = resumed.filter((r) => r.agent !== "cursor" && (opened.get(r.id)!.resumeId ?? null) !== (before.get(r.id)!.agent_session_id ?? null))
const byAgent = (list: Row[]) => list.reduce<Record<string, number>>((acc, r) => { acc[r.agent] = (acc[r.agent] ?? 0) + 1; return acc }, {})
const windowOwners = (wid: string | null) => [...before.values()].filter((r) => r.tmux_window_id === wid && r.status !== "archived").map(short)

const report = {
  db: dbPath,
  agentHomes: liveHomes ? "LIVE paths (sandboxed read-only)" : "rewritten to scratch",
  bootMs,
  rowsBefore: { active: byAgent([...before.values()].filter((r) => r.status === "active")), suspended: byAgent([...before.values()].filter((r) => r.status === "suspended")), archived: archivedCount },
  resumedAtBoot: { count: resumed.length, byAgent: byAgent(resumed), sessions: resumed.map((r) => ({ session: short(r), wasStatus: before.get(r.id)!.status, resumeId: opened.get(r.id)!.resumeId ?? "(fresh)", coreAfter: r.core })) },
  activeButNotResumed: notResumedActive.map((r) => ({ session: short(r), user_status: r.user_status, agent_session_id: r.agent_session_id, agent_home: r.agent_home })),
  suspendedWokenAtBoot: wokenSuspended.map((r) => ({ session: short(r), staleWindow: r.tmux_window_id, sameWindowIdHeldBy: windowOwners(r.tmux_window_id).filter((s) => !s.includes(r.id.slice(0, 8))), liveWindowName: windows.find((w) => w.id === r.tmux_window_id)?.name })),
  stillSuspended: { count: stillSuspended.length, byAgent: byAgent(stillSuspended) },
  statusChanges,
  tmuxWindowsRetired: killed,
  tmuxWindowsInSessionMux: windows.filter((w) => w.session === "mux").map((w) => `${w.id} ${w.name}`),
  muxWindowsNotRetired: windows.filter((w) => w.session === "mux" && !killed.some((k) => k.window === w.id)).map((w) => `${w.id} ${w.name}`),
  resumeIdMismatch: resumeIdMismatch.map(short),
  coreFlags: { claudeCore1: [...after.values()].filter((r) => r.agent === "claude" && r.core === 1).length, claudeCore0: [...after.values()].filter((r) => r.agent === "claude" && r.core === 0 && r.status !== "archived").length },
  archived: { count: archivedCount, untouched: archivedHash() === archivedBefore },
  agentProcessesBootWouldStart: opens.length,
  backendCalls: backendCalls.length,
  prepareErrors,
}
writeFileSync(join(SIM, "report.json"), JSON.stringify(report, null, 2))
console.log(JSON.stringify(report, null, 2))
for (const h of Object.values(hosts)) await h.close({ agents: "shutdown" }).catch(() => {})
setSessionBackendForTests(undefined)
process.exit(0)
