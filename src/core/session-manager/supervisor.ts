import { Registry } from "./registry"
import { isProcessAlive } from "./pid-file"
import { normalizeName } from "../../shared/slug"
import { AgentKind } from "../../shared/agents"
import { isPersistentRuntimeSession, type Session } from "./types"
import { makeLogger } from "../../shared/log"
import { getSessionBackend } from "../runtime"
import type { SessionBackend } from "../runtime/session-backend"

const TMUX_SESSION = process.env.MUX_TMUX_SESSION ?? "mux"
const log = makeLogger("supervisor")

/** Drafts are cached (status='active') but have no process — never suspend them. */
export function isDraftSession(s: Pick<Session, "user_status">): boolean {
  return s.user_status === "draft"
}

export type Supervisor = {
  /** Agents are kept alive: a live one is left alone; a crashed one is revived through its adapter;
   *  one without an adapter goes through the normal resume path (a failed resume of a saved
   *  conversation waits for the next tick); one that never had a conversation starts fresh under the same id (its memory is keyed by that id). Same path for every provider. */
  superviseAgents: () => Promise<void>
  reconcile: () => Promise<void>
  stop: () => void
}

type LiveAdapter = { isAlive(): boolean; resume(): Promise<void> }
function asLiveAdapter(a: unknown): LiveAdapter | undefined {
  return a && typeof (a as LiveAdapter).isAlive === "function" && typeof (a as LiveAdapter).resume === "function" ? a as LiveAdapter : undefined
}

export type SupervisorOpts = {
  registry: Registry
  bindSocket: (session_id: string) => Promise<void>
  sessionManager?: SessionManagerLike
  /** spawnPA with every dependency (resolveAttachment included) for a row that cannot resume. */
  startAgentFresh?: (session: Session) => Promise<void>
  /** The 30 s tick leaves Agents alone until superviseAgents() is called directly once (main.ts
   *  does so after resumeAtBoot), so a tick never races the boot resume for a host registration. */
  awaitBootPass?: boolean
  reapInternalWorkers?: () => Promise<void>
}

/** The slice of SessionManager the supervisor needs (type-only, avoids a
 *  runtime import cycle: manager.ts imports isDraftSession from this file). */
export type SessionManagerLike = {
  registerSpawnedAdapter(name: string, adapter: unknown, handle?: unknown): void
  adapterFor?(sessionId: string): unknown
  resumeSuspended?(session: Session): Promise<boolean>
  /** A boot resume still running (e.g. past boot_resume_timeout) owns the session. */
  bootResumeInFlight?(sessionId: string): boolean
  /** Bring back a live session's archived workspace (a live Agent must be visible). */
  reviveArchivedWorkspaceOf?(sessionId: string): boolean
}

export function createSupervisor(opts: SupervisorOpts): Supervisor {
  let stopped = false
  let timer: ReturnType<typeof setInterval> | null = null
  const sessionBackend = getSessionBackend()
  let running: Promise<void> | undefined
  let bootPassDone = !opts.awaitBootPass

  async function runtimeTargetId(session: Session): Promise<string | null> {
    if (session.tmux_window_id) return session.tmux_window_id
    const targetId = await sessionBackend.resolve(TMUX_SESSION, normalizeName(session.name))
    if (targetId) opts.registry.sessions.setTmuxWindowId(session.id, targetId)
    return targetId
  }

  /** The row as it is now: an Agent archived or killed mid-pass must not be brought back. */
  function stillSupervised(id: string): boolean {
    const row = opts.registry.sessions.getById(id)
    return !!row && row.status !== "archived" && !row.killed_at
  }

  async function superviseOne(agent: Session): Promise<void> {
    if (opts.sessionManager?.bootResumeInFlight?.(agent.id)) return
    if (!stillSupervised(agent.id)) return
    const adapter = asLiveAdapter(opts.sessionManager?.adapterFor?.(agent.id))
    if (adapter) {
      // isAlive covers every provider (pid is 0 for Core sessions; never judge an Agent by it).
      if (adapter.isAlive()) return
      if (!stillSupervised(agent.id)) return
      log.info("agent_revive", { id: agent.id, name: agent.name, agent: agent.agent })
      await adapter.resume()
      return
    }
    if (!stillSupervised(agent.id)) return
    if (await opts.sessionManager?.resumeSuspended?.(agent)) return
    if (!stillSupervised(agent.id)) return
    // A saved native conversation (Claude session id, Codex thread id, ...) is never discarded: a
    // failed resume may be transient, and a fresh start would overwrite the id. The next tick retries.
    const saved = opts.registry.get(agent.id)?.agent_session_id || agent.agent_session_id
    if (saved) {
      log.warn("agent_resume_failed_kept", { id: agent.id, name: agent.name, agent: agent.agent })
      return
    }
    if (opts.startAgentFresh) {
      log.info("agent_start_fresh", { id: agent.id, name: agent.name, agent: agent.agent })
      await opts.startAgentFresh(agent)
    }
  }

  async function superviseAgentsOnce(): Promise<void> {
    for (const agent of opts.registry.listPAs()) {
      if (isDraftSession(agent)) continue
      // A live Agent is never hidden behind an archived workspace (a session-only restore once left them so).
      try { opts.sessionManager?.reviveArchivedWorkspaceOf?.(agent.id) } catch (err) { log.warn("agent_workspace_revive_failed", { id: agent.id, err: String(err) }) }
      try { await superviseOne(agent) } catch (err) { log.warn("agent_supervise_failed", { id: agent.id, name: agent.name, err: String(err) }) }
    }
    const agents = opts.registry.listPAs()
    if (agents.length > 0 && !agents.some(s => s.is_default)) opts.registry.reassignDefault()
  }

  // One pass at a time: the 30 s tick must not start a second revive of the same Agent.
  function superviseAgents(): Promise<void> {
    bootPassDone = true
    running ??= superviseAgentsOnce().finally(() => { running = undefined })
    return running
  }

  async function reconcile() {
    for (const s of opts.registry.list()) {
      if (s.role === "personal_assistant") continue // superviseAgents below
      if (isDraftSession(s)) continue
      if (s.status === "suspended") continue
      if (!isPersistentRuntimeSession(s)) continue
      const targetId = await runtimeTargetId(s)
      const alive = targetId ? await sessionBackend.livePid(targetId) !== null : isProcessAlive(s.pid)
      if (!alive) {
        log.info("session_suspended", { id: s.id, name: s.name, pid: s.pid, reason: "process_dead" })
        opts.registry.sessions.suspend(s.id)
      }
    }
    if (bootPassDone) await superviseAgents()
    await opts.reapInternalWorkers?.()
  }

  timer = setInterval(() => { if (!stopped) { void reconcile().catch(() => {}) } }, 30_000)

  return {
    superviseAgents,
    reconcile,
    stop() { stopped = true; if (timer) clearInterval(timer) },
  }
}

// Startup-time reconcile: drop dead sessions from the registry, re-bind
// sockets for the survivors (so any still-running shim has a path to
// reconnect through). Agents are left to resumeAtBoot, then supervisor.superviseAgents (main.ts),
// so nothing here races the boot resume for a host registration. Distinct from the
// timer-driven `supervisor.reconcile()` — this one runs once, before
// bot.start(), and covers the case where the broker died but tmux + claude
// kept running.
export async function reconcileOnStartup(deps: {
  registry: Registry
  bindSocket: (session_id: string) => Promise<void>
  supervisor: Supervisor
  isAlive?: (pid: number) => boolean
  livePanePid?: (windowId: string) => Promise<number | null>
  sessionBackend?: SessionBackend
}): Promise<void> {
  const alive = deps.isAlive ?? isProcessAlive
  const sessionBackend = deps.sessionBackend ?? getSessionBackend()
  const liveRuntimePid = deps.livePanePid ?? ((targetId: string) => sessionBackend.livePid(targetId))
  for (const s of deps.registry.list()) {
    if (alive(s.pid)) continue
    if (isDraftSession(s)) continue
    // Already suspended: stays so (it wakes on its next message). Never revive it through a stored
    // window id, since tmux reuses ids after its server restarts and the pane may be another session's.
    if (s.status === "suspended") continue
    // An Agent row is never suspended or dropped here: resumeAtBoot owns it, and
    // supervisor.superviseAgents (after resumeAtBoot) starts what that could not.
    if (s.role === "personal_assistant") continue
    // Non-Claude sessions (codex, cursor): their PIDs are broker-child
    // processes that die WITH the broker. Dead PID ≠ dead session — the
    // session should be RESUMED (re-spawn app-server or rebuild adapter)
    // by resumeNonClaudeAdapters(), not dropped here. Cursor sessions
    // use pid=0 (no persistent process) and would survive isProcessAlive
    // by accident, but codex sessions use a real PID that's now dead.
    // A falsy `agent` on a legacy row means claude (the only kind that predates the field).
    // Core Claude rows (core=1) are not persistent runtimes either: pid 0, no pane, their agent
    // lives under a keeper. They keep their status and resumeAtBoot re-attaches the active ones.
    if (!isPersistentRuntimeSession({ agent: ((s as any).agent || AgentKind.Claude) as AgentKind, core: s.core })) continue
    // The stored pid is dead, but a Claude pane survives in its OWN systemd scope
    // across a broker restart. After a restart the pid is unreliable (a dead
    // broker pid from a lazy-resume's `|| process.pid`, or pid=0 from a DB-only
    // load), so trust the tmux pane: if it is still alive, adopt its pid and keep
    // the session ACTIVE. Otherwise the live session is false-suspended and the
    // next message would kill-and-respawn it, losing its in-progress state.
    if (s.tmux_window_id) {
      const panePid = await liveRuntimePid(s.tmux_window_id)
      if (panePid) {
        deps.registry.sessions.activate(s.id, panePid)
        log.info("session_pane_survived", { id: s.id, name: s.name, pane_pid: panePid, window: s.tmux_window_id })
        continue
      }
    }
    // Legacy rows can predate persisted runtime target IDs. Their recorded PID
    // remains the only liveness evidence until a later resolve/heal succeeds.
    if (!s.tmux_window_id && alive(s.pid)) continue
    // Claude user session with a dead PID and no surviving pane: mark suspended
    // (not dropped). It keeps its history and lazily resumes on the next message.
    log.info("session_suspended", { id: s.id, name: s.name, pid: s.pid, reason: "dead_on_startup" })
    deps.registry.sessions.suspend(s.id)
  }
  // Agents bind like every session (server.bind is idempotent).
  for (const s of deps.registry.list()) {
    await deps.bindSocket(s.id)
  }
}
