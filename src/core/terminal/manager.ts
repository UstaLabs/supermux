import { makeLogger } from "../../shared/log"
import { existsSync, mkdirSync, writeFileSync } from "fs"
import { join } from "path"
import { STATE_DIR } from "../../shared/paths"
import { ptyHelperPath } from "../runtime-assets"
import { createTermTmux, type TmuxRunner, type TerminalSummary } from "./tmux-term"
import { createAgentTmux, attachArgv as agentAttachArgv } from "./agent-tmux"
import { getSessionBackend } from "../runtime"
import type { SessionBackend } from "../runtime/session-backend"
import {
  createSessiondTerm,
  parseSessiondTerminalName,
  sessiondTerminalGroup,
  sessiondTerminalName,
  type FindExecutable,
} from "./sessiond-term"

const log = makeLogger("terminal")

/** Minimal subprocess surface the manager needs (real impl: Bun.spawn). Kept
 * narrow so tests can inject a fake and never spawn real tmux/shell processes. */
export interface TermProc {
  pid?: number
  stdin: { write(data: Uint8Array | string): void | boolean }
  stdout: ReadableStream<Uint8Array>
  exited: Promise<number>
  kill(signal?: number): void
  resize?(cols: number, rows: number): boolean
}
export type SpawnFn = (cmd: string[]) => TermProc

const defaultSpawn: SpawnFn = (cmd) =>
  Bun.spawn(cmd, { stdin: "pipe", stdout: "pipe", stderr: "pipe" }) as unknown as TermProc

// Config for the dedicated web-terminal tmux server. Sourced via `-f` when the
// server starts, so history-limit applies to the very first pane. mouse/status
// also take effect live on later invocations.
const MUXTERM_CONF = `# supermux web-terminal tmux server — managed, do not edit
set -g history-limit 50000
set -g mouse on
set -g status off
set -g destroy-unattached off
set -g window-size latest
set -g default-terminal "tmux-256color"
set -sa terminal-overrides ",*:Tc"
`

function ensureConf(stateDir: string): string {
  const p = join(stateDir, "muxterm.conf")
  try {
    mkdirSync(stateDir, { recursive: true })
    writeFileSync(p, MUXTERM_CONF)
  } catch (err: any) {
    log.error("muxterm_conf_write_failed", { path: p, err: err?.message })
  }
  return p
}

export interface TerminalInstance {
  key: string
  deviceName: string
  sessionName: string
  terminalId: string
  kind: "scratch" | "agent"
  agentTarget?: string
  runtimeTargetId?: string
  proc: TermProc
  /** true once we kill the viewer on purpose (detach/close) — suppresses onExit. */
  intentional: boolean
  createdAt: number
  lastInputAt: number
  onData: (data: Uint8Array) => void | Promise<void>
  onExit: (code: number) => void
}

/**
 * Manages persistent web terminals. POSIX hosts use the dedicated tmux server;
 * Windows hosts use sessiond/ConPTY. The in-memory map tracks viewers, while
 * the backing target survives viewer detach until explicit close or process exit.
 */
export class TerminalManager {
  private terminals = new Map<string, TerminalInstance>()
  private pendingWindowsAttaches = new Map<string, {
    token: symbol
    deviceName: string
    sessionName: string
    terminalId: string
  }>()
  private explicitlyClosedWindowsAttaches = new Set<symbol>()
  private windowsTargetLocks = new Map<string, Promise<void>>()
  private term?: ReturnType<typeof createTermTmux>
  private agentTerm?: ReturnType<typeof createAgentTmux>
  private spawnFn: SpawnFn
  private platform: NodeJS.Platform
  private sessionBackend?: SessionBackend
  private environment?: Readonly<Record<string, string>>
  private findExecutable?: FindExecutable

  constructor(opts?: {
    stateDir?: string
    socket?: string
    run?: TmuxRunner
    agentRun?: TmuxRunner
    spawn?: SpawnFn
    platform?: NodeJS.Platform
    sessionBackend?: SessionBackend
    environment?: Readonly<Record<string, string>>
    findExecutable?: FindExecutable
  }) {
    this.platform = opts?.platform ?? process.platform
    if (this.platform === "win32") {
      this.sessionBackend = opts?.sessionBackend ?? getSessionBackend()
      this.environment = opts?.environment
      this.findExecutable = opts?.findExecutable
    } else {
      const stateDir = opts?.stateDir ?? STATE_DIR
      const confPath = ensureConf(stateDir)
      this.term = createTermTmux({ socket: opts?.socket, confPath, run: opts?.run })
      this.agentTerm = createAgentTmux({ run: opts?.agentRun })
    }
    this.spawnFn = opts?.spawn ?? defaultSpawn
  }

  private static key(device: string, session: string, terminal: string): string {
    return `${device}:${session}:${terminal}`
  }

  /**
   * Attach a viewer to the persistent target, creating scratch targets when
   * needed. Any existing viewer for the same key is replaced (re-attach).
   */
  attach(opts: {
    deviceName: string
    sessionName: string
    terminalId: string
    workdir: string
    cols: number
    rows: number
    onData: (data: Uint8Array) => void | Promise<void>
    onExit: (code: number) => void
    kind?: "scratch" | "agent"
    agentTarget?: string
  }): { ok: true } | { ok: false; error: string } | Promise<{ ok: true } | { ok: false; error: string }> {
    if (this.platform === "win32") return this.attachWindows(opts)
    const key = TerminalManager.key(opts.deviceName, opts.sessionName, opts.terminalId)

    // Replace a stale viewer for this exact key (e.g. a lingering connection).
    const existing = this.terminals.get(key)
    if (existing) {
      existing.intentional = true
      this.terminals.delete(key)
      try { existing.proc.kill() } catch {}
    }

    const ptyHelper = ptyHelperPath(STATE_DIR)
    // Real spawns need the helper on disk; an injected (test) spawn does not.
    if (this.spawnFn === defaultSpawn && !existsSync(ptyHelper)) {
      log.error("pty_helper_missing", { path: ptyHelper })
      return { ok: false, error: "pty-helper binary not found" }
    }

    const kind = opts.kind ?? "scratch"
    if (kind === "agent" && !opts.agentTarget) {
      return { ok: false, error: "agentTarget is required for kind=agent" }
    }
    const argv = kind === "agent"
      ? agentAttachArgv({ device: opts.deviceName, agentTarget: opts.agentTarget! })
      : this.term!.attachArgv({
          agentSession: opts.sessionName,
          terminalId: opts.terminalId,
          workdir: opts.workdir,
          cols: opts.cols,
          rows: opts.rows,
        })

    const proc = this.spawnFn([ptyHelper, String(opts.cols), String(opts.rows), opts.workdir, ...argv])

    const inst: TerminalInstance = {
      key,
      deviceName: opts.deviceName,
      sessionName: opts.sessionName,
      terminalId: opts.terminalId,
      kind,
      agentTarget: opts.agentTarget,
      proc,
      intentional: false,
      createdAt: Date.now(),
      lastInputAt: Date.now(),
      onData: opts.onData,
      onExit: opts.onExit,
    }

    this.terminals.set(key, inst)
    log.info("terminal_attached", { key, workdir: opts.workdir, pid: proc.pid })

    this.pumpOutput(inst)

    proc.exited.then((code) => {
      // Only clear if WE are still the registered viewer (a replacement may have
      // taken our key already — don't delete the newcomer).
      if (this.terminals.get(key) === inst) this.terminals.delete(key)
      if (inst.intentional) {
        log.info("terminal_detached", { key })
        return
      }
      // Natural exit: the shell quit / tmux session ended. Tell the client so it
      // can drop the tab.
      log.info("terminal_exited", { key, code })
      try { inst.onExit(code) } catch {}
    })

    return { ok: true }
  }

  private async attachWindows(opts: {
    deviceName: string
    sessionName: string
    terminalId: string
    workdir: string
    cols: number
    rows: number
    onData: (data: Uint8Array) => void | Promise<void>
    onExit: (code: number) => void
    kind?: "scratch" | "agent"
    agentTarget?: string
  }): Promise<{ ok: true } | { ok: false; error: string }> {
    const key = TerminalManager.key(opts.deviceName, opts.sessionName, opts.terminalId)
    const existing = this.terminals.get(key)
    if (existing) {
      existing.intentional = true
      this.terminals.delete(key)
      try { existing.proc.kill() } catch {}
    }

    const kind = opts.kind ?? "scratch"
    if (kind === "agent" && !opts.agentTarget) return { ok: false, error: "agentTarget is required for kind=agent" }
    const token = Symbol(key)
    this.pendingWindowsAttaches.set(key, {
      token,
      deviceName: opts.deviceName,
      sessionName: opts.sessionName,
      terminalId: opts.terminalId,
    })
    const targetLock = kind === "scratch"
      ? `${sessiondTerminalGroup(opts.sessionName)}\0${sessiondTerminalName(opts.terminalId)}`
      : `agent\0${opts.agentTarget}`

    try {
      const result = await this.withWindowsTargetLock(targetLock, async () => {
        const attached = await createSessiondTerm({
          backend: this.sessionBackend!,
          kind,
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
        if (this.pendingWindowsAttaches.get(key)?.token !== token) {
          attached.proc.kill()
          const explicitlyClosed = this.explicitlyClosedWindowsAttaches.delete(token)
          if (explicitlyClosed && kind === "scratch") {
            try {
              if (await this.sessionBackend!.livePid(attached.targetId) !== null) {
                await this.sessionBackend!.kill(attached.targetId)
              }
            } catch {}
          }
          return { attached, canceled: true as const }
        }
        return { attached, canceled: false as const }
      })
      if (result.canceled) {
        return { ok: false, error: "terminal attachment was replaced" }
      }
      const { attached } = result
      this.pendingWindowsAttaches.delete(key)
      const inst: TerminalInstance = {
        key,
        deviceName: opts.deviceName,
        sessionName: opts.sessionName,
        terminalId: opts.terminalId,
        kind,
        agentTarget: opts.agentTarget,
        runtimeTargetId: attached.targetId,
        proc: attached.proc,
        intentional: false,
        createdAt: Date.now(),
        lastInputAt: Date.now(),
        onData: opts.onData,
        onExit: opts.onExit,
      }
      this.terminals.set(key, inst)
      log.info("terminal_attached", { key, workdir: opts.workdir, pid: attached.proc.pid })
      this.pumpOutput(inst)
      attached.proc.exited.then(code => {
        if (this.terminals.get(key) === inst) this.terminals.delete(key)
        if (inst.intentional) {
          log.info("terminal_detached", { key })
          return
        }
        log.info("terminal_exited", { key, code })
        try { inst.onExit(code) } catch {}
      }).catch(() => undefined)
      return { ok: true }
    } catch (error) {
      this.explicitlyClosedWindowsAttaches.delete(token)
      if (this.pendingWindowsAttaches.get(key)?.token === token) this.pendingWindowsAttaches.delete(key)
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

  private async pumpOutput(inst: TerminalInstance): Promise<void> {
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

  write(deviceName: string, sessionName: string, terminalId: string, data: Uint8Array): boolean {
    const inst = this.terminals.get(TerminalManager.key(deviceName, sessionName, terminalId))
    if (!inst) return false
    inst.lastInputAt = Date.now()
    try {
      return inst.proc.stdin.write(data) !== false
    } catch {
      return false
    }
  }

  resize(deviceName: string, sessionName: string, terminalId: string, cols: number, rows: number): boolean {
    const inst = this.terminals.get(TerminalManager.key(deviceName, sessionName, terminalId))
    if (!inst) return false
    inst.lastInputAt = Date.now()
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

  /** Disconnect a viewer WITHOUT killing its persistent target (reload / tab switch). */
  detach(deviceName: string, sessionName: string, terminalId: string): void {
    const key = TerminalManager.key(deviceName, sessionName, terminalId)
    if (this.platform === "win32") this.pendingWindowsAttaches.delete(key)
    const inst = this.terminals.get(key)
    if (!inst) return
    log.info("terminal_detach", { key })
    inst.intentional = true
    this.terminals.delete(key)
    try { inst.proc.kill() } catch {}
    // Agent terminals: also destroy the throwaway grouped viewer session (the
    // agent window itself always survives).
    if (this.platform !== "win32" && inst.kind === "agent" && inst.agentTarget) {
      void this.agentTerm!.killViewer(deviceName, inst.agentTarget)
    }
  }

  /** Destroy a terminal. For scratch terminals this kills viewers AND the backing
   * tmux session. For AGENT terminals "close" == detach: only the grouped viewer
   * session is destroyed; the agent window always survives. */
  async close(sessionName: string, terminalId: string): Promise<void> {
    if (this.platform === "win32") {
      for (const [key, pending] of this.pendingWindowsAttaches) {
        if (pending.sessionName === sessionName && pending.terminalId === terminalId) {
          this.pendingWindowsAttaches.delete(key)
          this.explicitlyClosedWindowsAttaches.add(pending.token)
        }
      }
    }
    const agentViewers: Array<{ device: string; target: string }> = []
    const scratchTargets = new Set<string>()
    for (const [key, inst] of this.terminals) {
      if (inst.sessionName === sessionName && inst.terminalId === terminalId) {
        inst.intentional = true
        this.terminals.delete(key)
        try { inst.proc.kill() } catch {}
        if (inst.kind === "agent" && inst.agentTarget) {
          agentViewers.push({ device: inst.deviceName, target: inst.agentTarget })
        } else if (inst.runtimeTargetId) {
          scratchTargets.add(inst.runtimeTargetId)
        }
      }
    }
    log.info("terminal_close", { sessionName, terminalId })
    if (this.platform === "win32") {
      if (agentViewers.length > 0) return
      const group = sessiondTerminalGroup(sessionName)
      const name = sessiondTerminalName(terminalId)
      await this.withWindowsTargetLock(`${group}\0${name}`, async () => {
        if (scratchTargets.size === 0) {
          const targetId = await this.sessionBackend!.resolve(group, name)
          if (targetId) scratchTargets.add(targetId)
        }
        for (const targetId of scratchTargets) await this.sessionBackend!.kill(targetId)
      })
      return
    }
    if (agentViewers.length > 0) {
      for (const v of agentViewers) {
        try { await this.agentTerm!.killViewer(v.device, v.target) } catch {}
      }
    } else {
      try { await this.term!.killTerminal(sessionName, terminalId) } catch {}
    }
  }

  /** List persisted scratch terminals from the platform session backend. */
  async listForSession(sessionName: string): Promise<TerminalSummary[]> {
    if (this.platform === "win32") {
      const targets = await this.sessionBackend!.list(sessiondTerminalGroup(sessionName))
      return targets
        .map(target => parseSessiondTerminalName(target.name))
        .filter((id): id is string => id !== null)
        .map(id => ({ id, createdAt: 0 }))
        .sort((a, b) => a.id.localeCompare(b.id))
    }
    return this.term!.listTerminals(sessionName)
  }

  /** Session deleted: kill all its viewers AND tmux sessions. */
  async killAllForSession(sessionName: string): Promise<void> {
    if (this.platform === "win32") {
      for (const [key, pending] of this.pendingWindowsAttaches) {
        if (pending.sessionName === sessionName) {
          this.pendingWindowsAttaches.delete(key)
          this.explicitlyClosedWindowsAttaches.add(pending.token)
        }
      }
    }
    for (const [key, inst] of this.terminals) {
      if (inst.sessionName === sessionName) {
        log.info("terminal_killed_session_cleanup", { key })
        inst.intentional = true
        this.terminals.delete(key)
        try { inst.proc.kill() } catch {}
        if (this.platform !== "win32" && inst.kind === "agent" && inst.agentTarget) {
          void this.agentTerm!.killViewer(inst.deviceName, inst.agentTarget)
        }
      }
    }
    if (this.platform === "win32") {
      const targets = await this.sessionBackend!.list(sessiondTerminalGroup(sessionName))
      for (const target of targets) await this.sessionBackend!.kill(target.id)
    } else {
      try { await this.term!.killAllTerminals(sessionName) } catch {}
    }
  }

  /** Device disconnect: detach its viewers but keep the tmux sessions alive. */
  detachAllForDevice(deviceName: string): void {
    if (this.platform === "win32") {
      for (const [key, pending] of this.pendingWindowsAttaches) {
        if (pending.deviceName === deviceName) this.pendingWindowsAttaches.delete(key)
      }
    }
    for (const [key, inst] of this.terminals) {
      if (inst.deviceName === deviceName) {
        inst.intentional = true
        this.terminals.delete(key)
        try { inst.proc.kill() } catch {}
        if (this.platform !== "win32" && inst.kind === "agent" && inst.agentTarget) {
          void this.agentTerm!.killViewer(deviceName, inst.agentTarget)
        }
      }
    }
  }

  has(deviceName: string, sessionName: string, terminalId: string): boolean {
    return this.terminals.has(TerminalManager.key(deviceName, sessionName, terminalId))
  }

  /** Whether the backing tmux session exists (persists across detach). */
  hasSession(sessionName: string, terminalId: string): Promise<boolean> {
    if (this.platform === "win32") {
      return this.sessionBackend!.resolve(sessiondTerminalGroup(sessionName), sessiondTerminalName(terminalId))
        .then(async targetId => targetId !== null && await this.sessionBackend!.livePid(targetId) !== null)
        .catch(() => false)
    }
    return this.term!.hasTerminal(sessionName, terminalId)
  }

  count(): number {
    return this.terminals.size
  }

  /** Broker shutdown: detach all viewers. tmux sessions intentionally survive. */
  shutdown(): void {
    this.pendingWindowsAttaches.clear()
    for (const [key, inst] of this.terminals) {
      log.info("terminal_shutdown_detach", { key })
      inst.intentional = true
      try { inst.proc.kill() } catch {}
    }
    this.terminals.clear()
  }
}
