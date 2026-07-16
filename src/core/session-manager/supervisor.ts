import { randomUUID } from "crypto"
import { mkdirSync, existsSync } from "fs"
import { isWorktreeReclaimable } from "../worktree/gc"
import { removeWorktree } from "../worktree/manager"
import { Registry } from "./registry"
import { spawnSessionWindow, killWindowById } from "./tmux"
import { isProcessAlive } from "./pid-file"
import { buildClaudeSpawnCommand } from "./spawn-command"
import { preAcceptTrust } from "./trust"
import { sendChannelConsentEnter } from "./post-spawn-keys"
import { seedSoulName } from "../memory/init"
import { home } from "../../shared/home"
import { normalizeName } from "../../shared/slug"
import { AgentKind } from "../../shared/agents"
import { spawnPA } from "./spawn-helper"
import type { Session } from "./types"
import { makeLogger } from "../../shared/log"

const TMUX_SESSION = process.env.MUX_TMUX_SESSION ?? "mux"
const log = makeLogger("supervisor")

export type Supervisor = {
  ensurePersonalAssistants: () => Promise<void>
  bootstrapPA: (name: string, bootstrapOpts?: BootstrapPAOpts) => Promise<void>
  reconcile: () => Promise<void>
  stop: () => void
}

export type BootstrapPAOpts = {
  agent?: AgentKind
  model?: string
  reasoningLevel?: string
}

export type SupervisorOpts = {
  registry: Registry
  // Bind the unix socket for a session BEFORE claude spawns; otherwise the
  // shim hits ENOENT on connect and the session dies on arrival.
  bindSocket: (session_id: string) => Promise<void>
  // Store the Claude Code session ID so resumed sessions can pass --resume.
  onClaudeSessionId?: (brokerSessionId: string, claudeSessionId: string) => void
  // Override for tests; defaults to the real tmux helper.
  spawnTmux?: (opts: { session: string; window: string; workdir: string; command: string }) => Promise<{ windowId?: string } | void>
  // PA workdir, resolved from the config store by the caller. When omitted
  // (e.g. tests), falls back to the historical default.
  paWorkdir?: string
  resolveEffort?: (session: Pick<Session, "agent" | "model" | "reasoningLevel">) => string | undefined
  // Non-Claude PA spawn dependencies (injected by main.ts; optional for tests).
  onCodexSessionId?: (brokerSessionId: string, sessionId: string) => void
  onCursorSessionId?: (name: string, sessionId: string) => void
  onOpenCodeSessionId?: (name: string, sessionId: string) => void
  registerAdapter?: Parameters<typeof spawnPA>[0]["registerAdapter"]
  codexResolveAuth?: Parameters<typeof spawnPA>[0]["codexResolveAuth"]
  codexSpawnAppServer?: Parameters<typeof spawnPA>[0]["codexSpawnAppServer"]
  codexAdapterFactory?: Parameters<typeof spawnPA>[0]["codexAdapterFactory"]
  cursorResolveAuth?: Parameters<typeof spawnPA>[0]["cursorResolveAuth"]
  cursorSmokeAgent?: Parameters<typeof spawnPA>[0]["cursorSmokeAgent"]
  cursorRunnerFactory?: Parameters<typeof spawnPA>[0]["cursorRunnerFactory"]
  cursorAdapterFactory?: Parameters<typeof spawnPA>[0]["cursorAdapterFactory"]
  opencodeSpawnServer?: Parameters<typeof spawnPA>[0]["opencodeSpawnServer"]
  opencodeAdapterFactory?: Parameters<typeof spawnPA>[0]["opencodeAdapterFactory"]
  reapInternalWorkers?: () => Promise<void>
}

export function createSupervisor(opts: SupervisorOpts): Supervisor {
  let stopped = false
  let timer: ReturnType<typeof setInterval> | null = null
  const spawnTmux = opts.spawnTmux ?? spawnSessionWindow
  // Prefer caller-supplied values (from config store); fall back to the built-in
  // default. The env var MUX_PA_WORKDIR is now read by the caller (main.ts via
  // SettingsStore.getAppConfig) and forwarded as opts.paWorkdir.
  const resolvedPaWorkdir = opts.paWorkdir || `${home()}/.mux/workspace`

  async function respawnPA(pa: Session) {
    // Kill the prior window by id. A legacy PA with no persisted tmux_window_id
    // skips teardown; any orphan window is harmless (we address by id, never name)
    // and is reclaimed on the next reconcile cycle.
    if (pa.agent === AgentKind.Claude && pa.tmux_window_id) {
      await killWindowById(pa.tmux_window_id).catch(() => {})
    }

    if (pa.agent === AgentKind.Claude) {
      const tmuxWindowName = normalizeName(pa.name)
      // tmux silently falls back to $HOME when -c points at a missing dir, so the
      // PA would run in /root instead of its workspace. Create it first.
      mkdirSync(pa.workdir, { recursive: true })
      preAcceptTrust(pa.workdir)

      const claudeSessionId = pa.agent_session_id ?? randomUUID()
      await opts.bindSocket(pa.id)

      const tmuxWindow = await spawnTmux({
        session: TMUX_SESSION,
        window: tmuxWindowName,
        workdir: pa.workdir,
        command: buildClaudeSpawnCommand({
          name: pa.name,
          sessionId: pa.id,
          claudeSessionId,
          resume: !!pa.agent_session_id,
          sessionRole: "personal_assistant",
          model: pa.model,
          effort: opts.resolveEffort?.(pa),
          workdir: pa.workdir,
        }),
      })
      if (tmuxWindow?.windowId) opts.registry.sessions.setTmuxWindowId(pa.id, tmuxWindow.windowId)
      if (!pa.agent_session_id) opts.onClaudeSessionId?.(pa.id, claudeSessionId)
      opts.registry.sessions.activate(pa.id, process.pid)
      if (tmuxWindow?.windowId) void sendChannelConsentEnter(tmuxWindow.windowId)
    } else {
      await spawnPA({
        registry: opts.registry,
        name: pa.name,
        agent: pa.agent,
        workdir: pa.workdir,
        model: pa.model,
        reasoningLevel: pa.reasoningLevel,
        bind: opts.bindSocket,
        spawnTmux,
        tmuxSession: TMUX_SESSION,
        id: pa.id,
        onClaudeSessionId: opts.onClaudeSessionId,
        resolveEffort: opts.resolveEffort,
        onCodexSessionId: opts.onCodexSessionId,
        onCursorSessionId: opts.onCursorSessionId,
        onOpenCodeSessionId: opts.onOpenCodeSessionId,
        registerAdapter: opts.registerAdapter,
        codexResolveAuth: opts.codexResolveAuth,
        codexSpawnAppServer: opts.codexSpawnAppServer,
        codexAdapterFactory: opts.codexAdapterFactory,
        cursorResolveAuth: opts.cursorResolveAuth,
        cursorSmokeAgent: opts.cursorSmokeAgent,
        cursorRunnerFactory: opts.cursorRunnerFactory,
        cursorAdapterFactory: opts.cursorAdapterFactory,
        opencodeSpawnServer: opts.opencodeSpawnServer,
        opencodeAdapterFactory: opts.opencodeAdapterFactory,
      })
    }
  }

  async function bootstrapPA(name: string, bootstrapOpts?: BootstrapPAOpts) {
    const agent = bootstrapOpts?.agent ?? AgentKind.Claude
    const workdir = resolvedPaWorkdir
    const id = randomUUID()

    // Personalize a fresh soul.md so the PA knows its name the way an established
    // PA does (it's told to read soul.md). No-op if soul.md was customized;
    // non-fatal (never block a spawn over a soul.md write).
    try { seedSoulName(name) } catch {}

    // No stale-window cleanup by name: windows are addressed by id now, so a
    // same-named orphan from a previous run is harmless and is reclaimed on
    // reconcile/restart.

    try {
      await spawnPA({
        registry: opts.registry,
        name,
        agent,
        workdir,
        id,
        model: bootstrapOpts?.model,
        reasoningLevel: bootstrapOpts?.reasoningLevel,
        bind: opts.bindSocket,
        spawnTmux,
        tmuxSession: TMUX_SESSION,
        onClaudeSessionId: opts.onClaudeSessionId,
        resolveEffort: opts.resolveEffort,
        onCodexSessionId: opts.onCodexSessionId,
        onCursorSessionId: opts.onCursorSessionId,
        onOpenCodeSessionId: opts.onOpenCodeSessionId,
        registerAdapter: opts.registerAdapter,
        codexResolveAuth: opts.codexResolveAuth,
        codexSpawnAppServer: opts.codexSpawnAppServer,
        codexAdapterFactory: opts.codexAdapterFactory,
        cursorResolveAuth: opts.cursorResolveAuth,
        cursorSmokeAgent: opts.cursorSmokeAgent,
        cursorRunnerFactory: opts.cursorRunnerFactory,
        cursorAdapterFactory: opts.cursorAdapterFactory,
        opencodeSpawnServer: opts.opencodeSpawnServer,
        opencodeAdapterFactory: opts.opencodeAdapterFactory,
      })
    } catch (err) {
      opts.registry.unregister(id)
      throw err
    }
  }

  async function ensurePersonalAssistants() {
    const pas = opts.registry.listPAs()

    if (pas.length === 0) {
      // PAs are opt-in. A fresh install, or a user who removed their last PA,
      // must stay at zero across broker restarts. Explicit creation goes through
      // bootstrapPA/spawnPA; once created, the supervisor keeps it alive below.
      return
    } else {
      // Supervise existing PAs: respawn any whose process is dead.
      for (const pa of pas) {
        if (isProcessAlive(pa.pid)) continue
        try {
          await respawnPA(pa)
        } catch (err) {
          console.error(`respawnPA failed for ${pa.name}:`, err)
        }
      }
    }

    // Guarantee exactly one default exists among the live PAs.
    if (!opts.registry.listPAs().some(s => s.is_default)) {
      opts.registry.reassignDefault()
    }
  }

  async function reconcile() {
    for (const s of opts.registry.list()) {
      if (s.status === "suspended") continue
      if (s.agent !== "claude") continue
      if (!isProcessAlive(s.pid)) {
        if (s.role === "personal_assistant") await ensurePersonalAssistants()
        else {
          log.info("session_suspended", { id: s.id, name: s.name, pid: s.pid, reason: "process_dead" })
          opts.registry.sessions.suspend(s.id)
        }
      }
    }
    await opts.reapInternalWorkers?.()
  }

  async function sweepArchivedWorktrees() {
    for (const w of opts.registry.sessions.listArchivedWorktrees()) {
      if (!existsSync(w.workdir)) continue                                   // already cleaned → skip (no churn)
      if (!isWorktreeReclaimable(w.workdir, w.session_branch, w.base_branch)) continue
      await removeWorktree(w.repo_root, w.workdir, w.session_branch).catch(() => {})
    }
  }

  timer = setInterval(() => { if (!stopped) { void reconcile().catch(() => {}); void sweepArchivedWorktrees().catch(() => {}) } }, 30_000)

  return {
    ensurePersonalAssistants,
    bootstrapPA,
    reconcile,
    stop() { stopped = true; if (timer) clearInterval(timer) },
  }
}

// Startup-time reconcile: drop dead sessions from the registry, re-bind
// sockets for the survivors (so any still-running shim has a path to
// reconnect through), then ensure personal assistants. Distinct from the
// timer-driven `supervisor.reconcile()` — this one runs once, before
// bot.start(), and covers the case where the broker died but tmux + claude
// kept running.
export async function reconcileOnStartup(deps: {
  registry: Registry
  bindSocket: (session_id: string) => Promise<void>
  supervisor: Supervisor
  isAlive?: (pid: number) => boolean
  livePanePid?: (windowId: string) => Promise<number | null>
}): Promise<void> {
  const alive = deps.isAlive ?? isProcessAlive
  const livePanePid = deps.livePanePid ?? (async () => null)
  for (const s of deps.registry.list()) {
    if (alive(s.pid)) continue
    // PA special-case: leave the stale row in place so ensurePersonalAssistants'
    // own respawn path runs (single source of truth for the PA lifecycle).
    if (s.role === "personal_assistant") continue
    // Non-Claude sessions (codex, cursor): their PIDs are broker-child
    // processes that die WITH the broker. Dead PID ≠ dead session — the
    // session should be RESUMED (re-spawn app-server or rebuild adapter)
    // by resumeNonClaudeAdapters(), not dropped here. Cursor sessions
    // use pid=0 (no persistent process) and would survive isProcessAlive
    // by accident, but codex sessions use a real PID that's now dead.
    if ((s as any).agent && (s as any).agent !== "claude") continue
    // The stored pid is dead, but a Claude pane survives in its OWN systemd scope
    // across a broker restart. After a restart the pid is unreliable (a dead
    // broker pid from a lazy-resume's `|| process.pid`, or pid=0 from a DB-only
    // load), so trust the tmux pane: if it is still alive, adopt its pid and keep
    // the session ACTIVE. Otherwise the live session is false-suspended and the
    // next message would kill-and-respawn it, losing its in-progress state.
    if (s.tmux_window_id) {
      const panePid = await livePanePid(s.tmux_window_id)
      if (panePid) {
        deps.registry.sessions.activate(s.id, panePid)
        log.info("session_pane_survived", { id: s.id, name: s.name, pane_pid: panePid, window: s.tmux_window_id })
        continue
      }
    }
    // Claude user session with a dead PID and no surviving pane: mark suspended
    // (not dropped). It keeps its history and lazily resumes on the next message.
    log.info("session_suspended", { id: s.id, name: s.name, pid: s.pid, reason: "dead_on_startup" })
    deps.registry.sessions.suspend(s.id)
  }
  for (const s of deps.registry.list()) {
    if (s.role === "personal_assistant") continue
    await deps.bindSocket(s.id)
  }
  await deps.supervisor.ensurePersonalAssistants()
}
