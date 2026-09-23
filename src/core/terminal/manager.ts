import { makeLogger } from "../../shared/log"
import { existsSync } from "fs"
import { STATE_DIR } from "../../shared/paths"
import { ptyHelperPath } from "../runtime-assets"
import type { TerminalSummary, TmuxRunner } from "./tmux-term"
import { createAgentTmux, attachArgv as agentAttachArgv } from "./agent-tmux"
import { getSessionBackend } from "../runtime"
import type { SessionBackend } from "../runtime/session-backend"
import {
  createSessiondTerm,
  SessiondWorkspaceBackend,
  sessiondTerminalGroup,
  sessiondTerminalName,
  type FindExecutable,
} from "./sessiond-term"
import { ZmxWorkspaceBackend } from "./zmx/backend"
import {
  isWorkspaceTerminalError,
  type WorkspaceTerminalBackend,
  type WorkspaceTerminalEvent,
  type WorkspaceTerminalKey,
  type WorkspaceTerminalViewer,
} from "./workspace-backend"

const log = makeLogger("terminal")

/** Minimal subprocess surface the manager needs (real impl: Bun.spawn). Kept
 * narrow so tests can inject a fake and never spawn real tmux/shell processes.
 *
 * AGENT terminals only. A workspace terminal has no process of the broker's:
 * its shell belongs to a zmx daemon (POSIX) or to sessiond (Windows), and what
 * the manager holds is a viewer, not a child. */
export interface TermProc {
  pid?: number
  stdin: { write(data: Uint8Array | string): void | boolean }
  stdout: ReadableStream<Uint8Array>
  exited: Promise<number>
  viewerFailed?: Promise<string>
  kill(signal?: number): void
  resize?(cols: number, rows: number): boolean
}
export type SpawnFn = (cmd: string[]) => TermProc

const defaultSpawn: SpawnFn = (cmd) =>
  Bun.spawn(cmd, { stdin: "pipe", stdout: "pipe", stderr: "pipe" }) as unknown as TermProc

/**
 * Whether an attachment may CREATE the terminal it is attaching to.
 *
 * `tmux new-session -A` conflated the two, which is why a reconnect to a
 * terminal whose shell had exited silently RESURRECTED it: the tab came back
 * alive and empty and the exit was never reported. The backend contract splits
 * `ensure` from `attachExisting`, and this is the signal that decides which a
 * caller gets:
 *
 *  - `create`           a UI "new terminal". Creates, then attaches.
 *  - `attach`           a reconnect. NEVER creates; a missing target is an
 *                       error the client must handle by closing the tab.
 *  - `create-if-absent` what every client asks for today, because the wire has
 *                       no way to say which of the two it means yet (Plan 4
 *                       Task 1). It attaches FIRST and only creates when there
 *                       is genuinely nothing there — so a live terminal is
 *                       never re-created, which `-A` could not promise either
 *                       way round.
 */
export type TerminalAttachIntent = "create" | "attach" | "create-if-absent"

export interface TerminalInstance {
  key: string
  deviceName: string
  sessionName: string
  terminalId: string
  kind: "scratch" | "agent"
  agentTarget?: string
  runtimeTargetId?: string
  /** AGENT terminals: the pty-helper (POSIX) or SessiondTerm (Windows) process. */
  proc?: TermProc
  /** WORKSPACE terminals: this device's viewer of the backing target. */
  viewer?: WorkspaceTerminalViewer
  /** true once we drop the viewer on purpose (detach/close) — suppresses onExit. */
  intentional: boolean
  createdAt: number
  lastInputAt: number
  onData: (data: Uint8Array) => void | Promise<void>
  onExit: (code: number, detail?: TerminalExitDetail) => void
  onFailure: (reason: string) => void
  onReset?: () => void
}

/** What the backend knew about an exit, for a wire that can carry more than a
 * number. `known: false` means the program ended but no status was reaped —
 * `code` is then 0 only because the frame needs one, not because anybody saw a
 * clean exit. */
export type TerminalExitDetail = { known: boolean; code: number | null; signal: number | null }

interface TerminalFocusClaim {
  instance: TerminalInstance
  cols: number
  rows: number
  order: number
}

export interface TerminalAttachOptions {
  deviceName: string
  sessionName: string
  terminalId: string
  workdir: string
  cols: number
  rows: number
  onData: (data: Uint8Array) => void | Promise<void>
  onExit: (code: number, detail?: TerminalExitDetail) => void
  onFailure?: (reason: string) => void
  /** The backing target re-synchronised: everything drawn so far is void. */
  onReset?: () => void
  kind?: "scratch" | "agent"
  agentTarget?: string
  intent?: TerminalAttachIntent
}

export type TerminalAttachResult = { ok: true } | { ok: false; error: string }

/**
 * Manages persistent web terminals.
 *
 * TWO BACKINGS, ON PURPOSE:
 *
 *  - WORKSPACE terminals (`kind: "scratch"`) go through a
 *    `WorkspaceTerminalBackend` — zmx on POSIX, sessiond/ConPTY on Windows.
 *    Creation, attachment, the replay boundary and SIZE OWNERSHIP all belong
 *    to that backend; the manager keeps only the per-device viewer.
 *
 *  - AGENT terminals (`kind: "agent"`) still ride the agent tmux server
 *    (agent-tmux.ts), because an agent pane is a window inside a session tmux
 *    owns. They keep the manager's own focus arbitration below, which is why
 *    `focusClaims` is still here: there is no backend under them to hold a
 *    lease, and two authorities over one size would fight.
 */
export class TerminalManager {
  private terminals = new Map<string, TerminalInstance>()
  /** AGENT terminals only. Foreground viewers keyed by backing tmux window;
   * the newest claim owns the size. A workspace terminal's owner is the
   * backend's `focus()`/`owner` and is deliberately not duplicated here. */
  private focusClaims = new Map<string, Map<string, TerminalFocusClaim>>()
  private focusOrder = 0
  private targetResizeTails = new Map<string, Promise<void>>()
  /** Attachments in flight, so a newer one (or a close) can cancel an older. */
  private pendingAttaches = new Map<string, {
    token: symbol
    deviceName: string
    sessionName: string
    terminalId: string
    kind: "scratch" | "agent"
  }>()
  private explicitlyClosedWindowsAttaches = new Set<symbol>()
  private windowsTargetLocks = new Map<string, Promise<void>>()
  private workspace: WorkspaceTerminalBackend
  private agentTerm?: ReturnType<typeof createAgentTmux>
  private spawnFn: SpawnFn
  private platform: NodeJS.Platform
  private stateDir: string
  private sessionBackend?: SessionBackend
  private environment?: Readonly<Record<string, string>>
  private findExecutable?: FindExecutable

  constructor(opts?: {
    stateDir?: string
    agentRun?: TmuxRunner
    spawn?: SpawnFn
    platform?: NodeJS.Platform
    sessionBackend?: SessionBackend
    environment?: Readonly<Record<string, string>>
    findExecutable?: FindExecutable
    /** Injected in tests. Real builds get the platform's own. */
    workspaceBackend?: WorkspaceTerminalBackend
  }) {
    this.platform = opts?.platform ?? process.platform
    this.stateDir = opts?.stateDir ?? STATE_DIR
    this.environment = opts?.environment
    this.findExecutable = opts?.findExecutable
    if (this.platform === "win32") {
      this.sessionBackend = opts?.sessionBackend ?? getSessionBackend()
      this.workspace = opts?.workspaceBackend ?? new SessiondWorkspaceBackend({
        backend: this.sessionBackend,
        environment: this.environment,
        findExecutable: this.findExecutable,
      })
    } else {
      this.workspace = opts?.workspaceBackend ?? new ZmxWorkspaceBackend({ stateDir: this.stateDir })
      this.agentTerm = createAgentTmux({ run: opts?.agentRun })
    }
    this.spawnFn = opts?.spawn ?? defaultSpawn
  }

  private static key(device: string, session: string, terminal: string): string {
    return `${device}:${session}:${terminal}`
  }

  private static targetKey(session: string, terminal: string): string {
    return JSON.stringify([session, terminal])
  }

  private targetKeyFor(inst: TerminalInstance): string {
    return TerminalManager.targetKey(inst.sessionName, inst.terminalId)
  }

  private static workspaceKey(sessionName: string, terminalId: string): WorkspaceTerminalKey {
    // The manager's `sessionName` IS the backend's scope: "w:<workspaceId>" for
    // a workspace, a session name otherwise (core/workspace/scope.ts).
    return { scope: sessionName, terminalId }
  }

  /** The program a new workspace terminal runs. On Windows this is empty and
   * the sessiond adapter discovers PowerShell, which is what it always did. */
  private workspaceShell(): string {
    if (this.platform === "win32") return ""
    return this.environment?.SHELL || process.env.SHELL || "/bin/bash"
  }

  // ---- agent focus arbitration (tmux only) --------------------------------

  private latestFocusClaim(targetKey: string): TerminalFocusClaim | undefined {
    let latest: TerminalFocusClaim | undefined
    for (const claim of this.focusClaims.get(targetKey)?.values() ?? []) {
      if (!latest || claim.order > latest.order) latest = claim
    }
    return latest
  }

  private resizeViewer(inst: TerminalInstance, cols: number, rows: number): boolean {
    inst.lastInputAt = Date.now()
    if (!inst.proc) return false
    if (inst.proc.resize) {
      try { return inst.proc.resize(cols, rows) } catch { return false }
    }
    const cmd = `\x00R${cols}:${rows}\n`
    try {
      inst.proc.stdin.write(new TextEncoder().encode(cmd))
      return true
    } catch {
      return false
    }
  }

  /**
   * An agent tmux window may be linked into several warm viewer sessions. Its
   * normal `window-size latest` policy only follows terminal activity, not
   * application focus, so explicitly size the shared window when focus changes.
   *
   * WORKSPACE terminals never come through here: their size belongs to the
   * backend's lease, which is the whole point of the `owner` event.
   */
  private forceTargetSize(claim: TerminalFocusClaim): boolean {
    const { instance: inst, cols, rows } = claim
    const ok = this.resizeViewer(inst, cols, rows)
    if (!this.agentTerm || !inst.agentTarget) return ok

    const targetKey = this.targetKeyFor(inst)
    const previous = this.targetResizeTails.get(targetKey) ?? Promise.resolve()
    const next = previous.catch(() => undefined).then(async () => {
      await this.agentTerm!.resizeWindow(inst.agentTarget!, cols, rows)
    }).catch((err: any) => {
      log.debug("terminal_focus_resize_failed", { key: inst.key, cols, rows, err: err?.message })
    })
    this.targetResizeTails.set(targetKey, next)
    void next.finally(() => {
      if (this.targetResizeTails.get(targetKey) === next) this.targetResizeTails.delete(targetKey)
    })
    return ok
  }

  private restoreAutomaticTargetSize(inst: TerminalInstance): void {
    if (!this.agentTerm || !inst.agentTarget) return
    const targetKey = this.targetKeyFor(inst)
    const previous = this.targetResizeTails.get(targetKey) ?? Promise.resolve()
    const next = previous.catch(() => undefined).then(async () => {
      await this.agentTerm!.restoreAutomaticSize(inst.agentTarget!)
    }).catch((err: any) => {
      log.debug("terminal_focus_restore_failed", { key: inst.key, err: err?.message })
    })
    this.targetResizeTails.set(targetKey, next)
    void next.finally(() => {
      if (this.targetResizeTails.get(targetKey) === next) this.targetResizeTails.delete(targetKey)
    })
  }

  private removeFocusClaim(inst: TerminalInstance, applyFallback = true): void {
    if (inst.kind !== "agent") return
    const targetKey = this.targetKeyFor(inst)
    const claims = this.focusClaims.get(targetKey)
    const claim = claims?.get(inst.key)
    if (!claims || claim?.instance !== inst) return
    const wasLatest = this.latestFocusClaim(targetKey) === claim
    claims.delete(inst.key)
    if (claims.size === 0) this.focusClaims.delete(targetKey)
    if (applyFallback && wasLatest) {
      const fallback = this.latestFocusClaim(targetKey)
      if (fallback) this.forceTargetSize(fallback)
      else this.restoreAutomaticTargetSize(inst)
    }
  }

  // ---- attach -------------------------------------------------------------

  /**
   * Attach a viewer to the persistent target. Any existing viewer for the same
   * key is replaced (re-attach). Whether a MISSING target is created is
   * `opts.intent`'s decision, not this function's.
   */
  attach(opts: TerminalAttachOptions): Promise<TerminalAttachResult> {
    const kind = opts.kind ?? "scratch"
    if (kind === "agent" && !opts.agentTarget) {
      return Promise.resolve({ ok: false, error: "agentTarget is required for kind=agent" })
    }
    if (kind === "agent") {
      return this.platform === "win32" ? this.attachAgentWindows(opts) : this.attachAgentPosix(opts)
    }
    return this.attachWorkspace(opts)
  }

  /** Drop whatever viewer currently holds this key, without touching the target. */
  private replaceExisting(key: string): void {
    const existing = this.terminals.get(key)
    if (!existing) return
    this.removeFocusClaim(existing)
    existing.intentional = true
    this.terminals.delete(key)
    this.dropViewer(existing)
  }

  private dropViewer(inst: TerminalInstance): void {
    if (inst.viewer) { void inst.viewer.detach().catch(() => undefined) }
    if (inst.proc) { try { inst.proc.kill() } catch {} }
  }

  /** A workspace terminal, on whichever backend this platform has. */
  private async attachWorkspace(opts: TerminalAttachOptions): Promise<TerminalAttachResult> {
    const key = TerminalManager.key(opts.deviceName, opts.sessionName, opts.terminalId)
    const workspaceKey = TerminalManager.workspaceKey(opts.sessionName, opts.terminalId)
    this.replaceExisting(key)

    const token = Symbol(key)
    this.pendingAttaches.set(key, {
      token,
      deviceName: opts.deviceName,
      sessionName: opts.sessionName,
      terminalId: opts.terminalId,
      kind: "scratch",
    })

    const inst: TerminalInstance = {
      key,
      deviceName: opts.deviceName,
      sessionName: opts.sessionName,
      terminalId: opts.terminalId,
      kind: "scratch",
      intentional: false,
      createdAt: Date.now(),
      lastInputAt: Date.now(),
      onData: opts.onData,
      onExit: opts.onExit,
      onFailure: opts.onFailure ?? (() => {}),
      onReset: opts.onReset,
    }

    const ensure = () => this.workspace.ensure(workspaceKey, {
      cwd: opts.workdir,
      shell: this.workspaceShell(),
      env: {},
      cols: opts.cols,
      rows: opts.rows,
    })

    try {
      const intent = opts.intent ?? "create-if-absent"
      if (intent === "create") await ensure()
      let viewer: WorkspaceTerminalViewer
      try {
        viewer = await this.workspace.attachExisting(workspaceKey, opts.deviceName,
          event => this.onWorkspaceEvent(inst, event))
      } catch (error) {
        // The legacy intent: attach FIRST, and create only when there is
        // genuinely nothing there. A live terminal is never re-created, and a
        // caller that says `attach` gets the error instead.
        if (intent !== "create-if-absent" || !isWorkspaceTerminalError(error, "target-not-found")) throw error
        await ensure()
        viewer = await this.workspace.attachExisting(workspaceKey, opts.deviceName,
          event => this.onWorkspaceEvent(inst, event))
      }

      if (this.pendingAttaches.get(key)?.token !== token) {
        // A newer attach (or a close) took this key while we were in flight.
        this.explicitlyClosedWindowsAttaches.delete(token)
        await viewer.detach().catch(() => undefined)
        return { ok: false, error: "terminal attachment was replaced" }
      }
      this.pendingAttaches.delete(key)
      inst.viewer = viewer
      this.terminals.set(key, inst)
      log.info("terminal_attached", { key, workdir: opts.workdir, backend: "workspace" })
      return { ok: true }
    } catch (error) {
      this.explicitlyClosedWindowsAttaches.delete(token)
      if (this.pendingAttaches.get(key)?.token === token) this.pendingAttaches.delete(key)
      const message = error instanceof Error ? error.message : String(error)
      log.error("terminal_attach_failed", { key, err: message })
      return { ok: false, error: message }
    }
  }

  /**
   * The contract's events, on today's wire.
   *
   * `reset` becomes the `{type:"reset"}` frame clients already understand, so
   * a mid-stream re-sync clears the screen instead of drawing over it. The
   * replay boundary and `owner` have no frame yet (Plan 4 Task 1 adds them);
   * they are the backend's business until then, and dropping them here is
   * correct rather than lossy — nothing downstream can act on them.
   */
  private async onWorkspaceEvent(inst: TerminalInstance, event: WorkspaceTerminalEvent): Promise<void> {
    switch (event.type) {
      case "output":
        await inst.onData(event.bytes)
        return
      case "reset":
        try { inst.onReset?.() } catch {}
        return
      case "exit": {
        if (this.terminals.get(inst.key) === inst) this.terminals.delete(inst.key)
        if (inst.intentional) return
        inst.intentional = true
        log.info("terminal_exited", { key: inst.key, code: event.code, known: event.known })
        // The frame carries a number, so an unreaped exit reports 0 and says
        // so in `detail` — a client that looks only at `code` closes the tab
        // either way, which is the right outcome for both.
        const code = event.code ?? (event.signal !== null ? 128 + event.signal : 0)
        try { inst.onExit(code, { known: event.known, code: event.code, signal: event.signal }) } catch {}
        return
      }
      case "failure": {
        if (this.terminals.get(inst.key) === inst) this.terminals.delete(inst.key)
        if (inst.intentional) return
        inst.intentional = true
        log.warn("terminal_viewer_failed", { key: inst.key, code: event.code, reason: event.message })
        try { inst.onFailure(event.message) } catch {}
        return
      }
      default:
        // replay-start / replay-end / owner: no wire frame yet.
        return
    }
  }

  /** An agent pane on POSIX: a grouped viewer session on the agent tmux server. */
  private attachAgentPosix(opts: TerminalAttachOptions): Promise<TerminalAttachResult> {
    const key = TerminalManager.key(opts.deviceName, opts.sessionName, opts.terminalId)
    this.replaceExisting(key)

    const ptyHelper = ptyHelperPath(this.stateDir)
    // Real spawns need the helper on disk; an injected (test) spawn does not.
    if (this.spawnFn === defaultSpawn && !existsSync(ptyHelper)) {
      log.error("pty_helper_missing", { path: ptyHelper })
      return Promise.resolve({ ok: false, error: "pty-helper binary not found" })
    }

    const argv = agentAttachArgv({ device: opts.deviceName, agentTarget: opts.agentTarget! })
    const proc = this.spawnFn([ptyHelper, String(opts.cols), String(opts.rows), opts.workdir, ...argv])

    const inst: TerminalInstance = {
      key,
      deviceName: opts.deviceName,
      sessionName: opts.sessionName,
      terminalId: opts.terminalId,
      kind: "agent",
      agentTarget: opts.agentTarget,
      proc,
      intentional: false,
      createdAt: Date.now(),
      lastInputAt: Date.now(),
      onData: opts.onData,
      onExit: opts.onExit,
      onFailure: opts.onFailure ?? (() => {}),
      onReset: opts.onReset,
    }

    this.terminals.set(key, inst)
    log.info("terminal_attached", { key, workdir: opts.workdir, pid: proc.pid, backend: "agent-tmux" })
    this.pumpOutput(inst)

    proc.exited.then((code) => {
      // Only clear if WE are still the registered viewer (a replacement may
      // have taken our key already — don't delete the newcomer).
      if (this.terminals.get(key) === inst) {
        this.terminals.delete(key)
        this.removeFocusClaim(inst)
      }
      if (inst.intentional) {
        log.info("terminal_detached", { key })
        return
      }
      log.info("terminal_exited", { key, code })
      try { inst.onExit(code) } catch {}
    })

    return Promise.resolve({ ok: true })
  }

  /** An agent pane on Windows: a viewer of a sessiond target somebody else owns. */
  private async attachAgentWindows(opts: TerminalAttachOptions): Promise<TerminalAttachResult> {
    const key = TerminalManager.key(opts.deviceName, opts.sessionName, opts.terminalId)
    this.replaceExisting(key)

    const token = Symbol(key)
    this.pendingAttaches.set(key, {
      token,
      deviceName: opts.deviceName,
      sessionName: opts.sessionName,
      terminalId: opts.terminalId,
      kind: "agent",
    })
    const targetLock = `agent\0${opts.agentTarget}`

    try {
      const result = await this.withWindowsTargetLock(targetLock, async () => {
        const attached = await createSessiondTerm({
          backend: this.sessionBackend!,
          kind: "agent",
          deviceName: opts.deviceName,
          sessionName: opts.sessionName,
          terminalId: opts.terminalId,
          agentTarget: opts.agentTarget,
          workdir: opts.workdir,
          cols: opts.cols,
          rows: opts.rows,
          environment: this.environment,
          findExecutable: this.findExecutable,
        })
        if (this.pendingAttaches.get(key)?.token !== token) {
          attached.proc.kill()
          // An agent target is never ours to kill, whoever asked.
          this.explicitlyClosedWindowsAttaches.delete(token)
          return { attached, canceled: true as const }
        }
        return { attached, canceled: false as const }
      })
      if (result.canceled) return { ok: false, error: "terminal attachment was replaced" }
      const { attached } = result
      this.pendingAttaches.delete(key)
      const inst: TerminalInstance = {
        key,
        deviceName: opts.deviceName,
        sessionName: opts.sessionName,
        terminalId: opts.terminalId,
        kind: "agent",
        agentTarget: opts.agentTarget,
        runtimeTargetId: attached.targetId,
        proc: attached.proc,
        intentional: false,
        createdAt: Date.now(),
        lastInputAt: Date.now(),
        onData: opts.onData,
        onExit: opts.onExit,
        onFailure: opts.onFailure ?? (() => {}),
        onReset: opts.onReset,
      }
      this.terminals.set(key, inst)
      log.info("terminal_attached", { key, workdir: opts.workdir, pid: attached.proc.pid, backend: "sessiond-agent" })
      this.pumpOutput(inst)
      attached.proc.exited.then(code => {
        if (this.terminals.get(key) === inst) {
          this.terminals.delete(key)
          this.removeFocusClaim(inst)
        }
        if (inst.intentional) {
          log.info("terminal_detached", { key })
          return
        }
        log.info("terminal_exited", { key, code })
        try { inst.onExit(code) } catch {}
      }).catch(() => undefined)
      void attached.proc.viewerFailed.then(reason => {
        if (this.terminals.get(key) !== inst) return
        this.terminals.delete(key)
        this.removeFocusClaim(inst)
        if (inst.intentional) return
        log.warn("terminal_viewer_failed", { key, reason })
        try { inst.onFailure(reason) } catch {}
      })
      return { ok: true }
    } catch (error) {
      this.explicitlyClosedWindowsAttaches.delete(token)
      if (this.pendingAttaches.get(key)?.token === token) this.pendingAttaches.delete(key)
      const message = error instanceof Error ? error.message : String(error)
      log.error("terminal_attach_failed", { key, err: message })
      return { ok: false, error: message }
    }
  }

  private async withWindowsTargetLock<T>(key: string, operation: () => Promise<T>): Promise<T> {
    const previous = this.windowsTargetLocks.get(key) ?? Promise.resolve()
    let release!: () => void
    const turn = new Promise<void>(resolve => { release = resolve })
    const tail = previous.catch(() => undefined).then(() => turn)
    this.windowsTargetLocks.set(key, tail)
    await previous.catch(() => undefined)
    try {
      return await operation()
    } finally {
      release()
      if (this.windowsTargetLocks.get(key) === tail) this.windowsTargetLocks.delete(key)
    }
  }

  /** AGENT terminals only: a workspace viewer is push-based through its events. */
  private async pumpOutput(inst: TerminalInstance): Promise<void> {
    if (!inst.proc) return
    const stdout = inst.proc.stdout as ReadableStream<Uint8Array>
    const reader = stdout.getReader()
    try {
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        try {
          await inst.onData(value)
        } catch {}
      }
    } catch (err: any) {
      log.debug("terminal_stdout_ended", { key: inst.key, err: err?.message })
    }
  }

  // ---- viewer traffic -----------------------------------------------------

  write(deviceName: string, sessionName: string, terminalId: string, data: Uint8Array): boolean {
    const inst = this.terminals.get(TerminalManager.key(deviceName, sessionName, terminalId))
    if (!inst) return false
    inst.lastInputAt = Date.now()
    if (inst.viewer) return inst.viewer.write(data)
    try {
      return inst.proc!.stdin.write(data) !== false
    } catch {
      return false
    }
  }

  /**
   * A terminal REPLY the client's emulator produced (a DA/DSR answer), as
   * opposed to typing. Workspace terminals keep the two apart because only the
   * size owner's answer may reach the pty; an agent terminal has no such split
   * and the bytes go where typing goes.
   */
  reply(deviceName: string, sessionName: string, terminalId: string, data: Uint8Array): boolean {
    const inst = this.terminals.get(TerminalManager.key(deviceName, sessionName, terminalId))
    if (!inst) return false
    if (inst.viewer) return inst.viewer.reply(data)
    return this.write(deviceName, sessionName, terminalId, data)
  }

  resize(deviceName: string, sessionName: string, terminalId: string, cols: number, rows: number): boolean {
    const inst = this.terminals.get(TerminalManager.key(deviceName, sessionName, terminalId))
    if (!inst) return false
    if (inst.viewer) {
      // Whether this geometry reaches the pty is the BACKEND's decision: it
      // holds the lease, and a background viewer's reflow must not take it.
      inst.lastInputAt = Date.now()
      void inst.viewer.resize(cols, rows).catch((err: any) => {
        log.debug("terminal_resize_failed", { key: inst.key, cols, rows, err: err?.message })
      })
      return true
    }
    const targetKey = this.targetKeyFor(inst)
    const claims = this.focusClaims.get(targetKey)
    if (!claims?.size) return this.resizeViewer(inst, cols, rows)

    // Warm/background clients can continue reporting layout changes. Remember a
    // focused viewer's latest geometry, but only the newest focus claim may apply it.
    const ownClaim = claims.get(inst.key)
    if (!ownClaim || ownClaim.instance !== inst) return true
    ownClaim.cols = cols
    ownClaim.rows = rows
    return this.latestFocusClaim(targetKey) === ownClaim ? this.forceTargetSize(ownClaim) : true
  }

  /** Make this viewer authoritative for the shared terminal size, or release it. */
  focus(
    deviceName: string,
    sessionName: string,
    terminalId: string,
    focused: boolean,
    cols?: number,
    rows?: number,
  ): boolean {
    const inst = this.terminals.get(TerminalManager.key(deviceName, sessionName, terminalId))
    if (!inst) return false
    const geometryOk = Number.isInteger(cols) && Number.isInteger(rows) && cols! >= 1 && rows! >= 1
    if (inst.viewer) {
      if (focused && !geometryOk) return false
      void inst.viewer.focus(focused, cols ?? 0, rows ?? 0).catch((err: any) => {
        log.debug("terminal_focus_failed", { key: inst.key, focused, err: err?.message })
      })
      return true
    }
    if (!focused) {
      this.removeFocusClaim(inst)
      return true
    }
    if (!geometryOk) return false
    const targetKey = this.targetKeyFor(inst)
    const claims = this.focusClaims.get(targetKey) ?? new Map<string, TerminalFocusClaim>()
    const claim = { instance: inst, cols: cols!, rows: rows!, order: ++this.focusOrder }
    claims.set(inst.key, claim)
    this.focusClaims.set(targetKey, claims)
    return this.forceTargetSize(claim)
  }

  // ---- lifecycle ----------------------------------------------------------

  /** Disconnect a viewer WITHOUT killing its persistent target (reload / tab switch). */
  detach(deviceName: string, sessionName: string, terminalId: string): void {
    const key = TerminalManager.key(deviceName, sessionName, terminalId)
    this.pendingAttaches.delete(key)
    const inst = this.terminals.get(key)
    if (!inst) return
    log.info("terminal_detach", { key })
    inst.intentional = true
    this.terminals.delete(key)
    this.removeFocusClaim(inst)
    this.dropViewer(inst)
    // Agent terminals: also destroy the throwaway grouped viewer session (the
    // agent window itself always survives).
    if (this.agentTerm && inst.kind === "agent" && inst.agentTarget) {
      void this.agentTerm.killViewer(deviceName, inst.agentTarget)
    }
  }

  /** Destroy a terminal. For workspace terminals this drops viewers AND the
   * backing target. For AGENT terminals "close" == detach: only the grouped
   * viewer session is destroyed; the agent window always survives. */
  async close(sessionName: string, terminalId: string): Promise<void> {
    // Cancel any attach still in flight for this terminal, so it cannot
    // register a viewer of a target we are about to destroy.
    // An AGENT terminal's backing window is never ours to destroy, and an
    // attach that has not registered yet is the only thing that knows which
    // kind this terminal is. So the pending entries decide it too.
    let agent = false
    for (const [key, pending] of this.pendingAttaches) {
      if (pending.sessionName !== sessionName || pending.terminalId !== terminalId) continue
      this.pendingAttaches.delete(key)
      if (pending.kind !== "agent") continue
      agent = true
      if (this.platform === "win32") this.explicitlyClosedWindowsAttaches.add(pending.token)
    }
    const agentViewers: Array<{ device: string; target: string }> = []
    for (const [key, inst] of this.terminals) {
      if (inst.sessionName === sessionName && inst.terminalId === terminalId) {
        inst.intentional = true
        this.terminals.delete(key)
        this.removeFocusClaim(inst, false)
        this.dropViewer(inst)
        if (inst.kind === "agent") {
          agent = true
          if (inst.agentTarget) agentViewers.push({ device: inst.deviceName, target: inst.agentTarget })
        }
      }
    }
    log.info("terminal_close", { sessionName, terminalId })
    if (agent) {
      if (!this.agentTerm) return // Windows: an agent target is never ours to kill.
      for (const v of agentViewers) {
        try { await this.agentTerm.killViewer(v.device, v.target) } catch {}
      }
      return
    }
    await this.workspace.close(TerminalManager.workspaceKey(sessionName, terminalId))
  }

  /** List persisted workspace terminals from the platform's backend. */
  async listForSession(sessionName: string): Promise<TerminalSummary[]> {
    const summaries = await this.workspace.list(sessionName)
    return summaries.map(summary => ({ id: summary.terminalId, createdAt: summary.createdAt }))
  }

  /** Session deleted: kill all its viewers AND its backing targets. */
  async killAllForSession(sessionName: string): Promise<void> {
    for (const [key, pending] of this.pendingAttaches) {
      if (pending.sessionName !== sessionName) continue
      this.pendingAttaches.delete(key)
      if (this.platform === "win32" && pending.kind === "agent") {
        this.explicitlyClosedWindowsAttaches.add(pending.token)
      }
    }
    for (const [key, inst] of this.terminals) {
      if (inst.sessionName === sessionName) {
        log.info("terminal_killed_session_cleanup", { key })
        inst.intentional = true
        this.terminals.delete(key)
        this.removeFocusClaim(inst, false)
        this.dropViewer(inst)
        if (this.agentTerm && inst.kind === "agent" && inst.agentTarget) {
          void this.agentTerm.killViewer(inst.deviceName, inst.agentTarget)
        }
      }
    }
    await this.workspace.closeScope(sessionName)
  }

  /** Device disconnect: detach its viewers but keep the targets alive. */
  detachAllForDevice(deviceName: string): void {
    for (const [key, pending] of this.pendingAttaches) {
      if (pending.deviceName === deviceName) this.pendingAttaches.delete(key)
    }
    for (const [key, inst] of this.terminals) {
      if (inst.deviceName === deviceName) {
        inst.intentional = true
        this.terminals.delete(key)
        this.removeFocusClaim(inst)
        this.dropViewer(inst)
        if (this.agentTerm && inst.kind === "agent" && inst.agentTarget) {
          void this.agentTerm.killViewer(deviceName, inst.agentTarget)
        }
      }
    }
  }

  has(deviceName: string, sessionName: string, terminalId: string): boolean {
    return this.terminals.has(TerminalManager.key(deviceName, sessionName, terminalId))
  }

  /** Whether the backing target exists (it persists across viewer detach). */
  hasSession(sessionName: string, terminalId: string): Promise<boolean> {
    return this.workspace.exists(TerminalManager.workspaceKey(sessionName, terminalId)).catch(() => false)
  }

  count(): number {
    return this.terminals.size
  }

  /** Broker shutdown: detach all viewers. The backing targets survive — that is
   * the entire reason they are not our children. */
  shutdown(): void {
    this.pendingAttaches.clear()
    for (const [key, inst] of this.terminals) {
      log.info("terminal_shutdown_detach", { key })
      inst.intentional = true
      this.removeFocusClaim(inst, false)
      this.dropViewer(inst)
    }
    this.terminals.clear()
    this.focusClaims.clear()
    void this.workspace.shutdownViewers().catch((err: any) => {
      log.debug("terminal_shutdown_viewers_failed", { err: err?.message })
    })
  }
}
