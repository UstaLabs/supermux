import { makeLogger } from "../../shared/log"
import { existsSync, mkdirSync, writeFileSync } from "fs"
import { join } from "path"
import { STATE_DIR } from "../../shared/paths"
import { ptyHelperPath } from "../runtime-assets"
import { createTermTmux, type TmuxRunner, type TerminalSummary } from "./tmux-term"
import { createAgentTmux, attachArgv as agentAttachArgv } from "./agent-tmux"

const log = makeLogger("terminal")

/** Minimal subprocess surface the manager needs (real impl: Bun.spawn). Kept
 * narrow so tests can inject a fake and never spawn real tmux/shell processes. */
export interface TermProc {
  pid?: number
  stdin: { write(data: Uint8Array | string): void }
  stdout: ReadableStream<Uint8Array>
  exited: Promise<number>
  kill(signal?: number): void
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
  proc: TermProc
  /** true once we kill the viewer on purpose (detach/close) — suppresses onExit. */
  intentional: boolean
  createdAt: number
  lastInputAt: number
  onData: (data: Uint8Array) => void
  onExit: (code: number) => void
}

/**
 * Manages web terminals backed by a dedicated tmux server. Each terminal is a
 * tmux session that outlives its viewer: the in-memory map here tracks the
 * VIEWER (a pty-helper running `tmux attach`), keyed by device:session:terminal.
 * Killing a viewer only detaches — the tmux session (and its processes) live on
 * until an explicit close, a session deletion, or the shell exiting.
 */
export class TerminalManager {
  private terminals = new Map<string, TerminalInstance>()
  private term: ReturnType<typeof createTermTmux>
  private agentTerm: ReturnType<typeof createAgentTmux>
  private spawnFn: SpawnFn

  constructor(opts?: { stateDir?: string; socket?: string; run?: TmuxRunner; agentRun?: TmuxRunner; spawn?: SpawnFn }) {
    const stateDir = opts?.stateDir ?? STATE_DIR
    const confPath = ensureConf(stateDir)
    this.term = createTermTmux({ socket: opts?.socket, confPath, run: opts?.run })
    this.agentTerm = createAgentTmux({ run: opts?.agentRun })
    this.spawnFn = opts?.spawn ?? defaultSpawn
  }

  private static key(device: string, session: string, terminal: string): string {
    return `${device}:${session}:${terminal}`
  }

  /**
   * Attach a viewer to the (session, terminalId) tmux session, creating it if
   * needed. Any existing viewer for the same key is replaced (re-attach).
   */
  attach(opts: {
    deviceName: string
    sessionName: string
    terminalId: string
    workdir: string
    cols: number
    rows: number
    onData: (data: Uint8Array) => void
    onExit: (code: number) => void
    kind?: "scratch" | "agent"
    agentTarget?: string
  }): { ok: true } | { ok: false; error: string } {
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
      : this.term.attachArgv({
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

  private async pumpOutput(inst: TerminalInstance): Promise<void> {
    const stdout = inst.proc.stdout as ReadableStream<Uint8Array>
    const reader = stdout.getReader()
    try {
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        try {
          inst.onData(value)
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
      inst.proc.stdin.write(data)
      return true
    } catch {
      return false
    }
  }

  resize(deviceName: string, sessionName: string, terminalId: string, cols: number, rows: number): boolean {
    const inst = this.terminals.get(TerminalManager.key(deviceName, sessionName, terminalId))
    if (!inst) return false
    inst.lastInputAt = Date.now()
    const cmd = `\x00R${cols}:${rows}\n`
    try {
      inst.proc.stdin.write(new TextEncoder().encode(cmd))
      return true
    } catch {
      return false
    }
  }

  /** Disconnect a viewer WITHOUT killing the tmux session (reload / tab switch). */
  detach(deviceName: string, sessionName: string, terminalId: string): void {
    const key = TerminalManager.key(deviceName, sessionName, terminalId)
    const inst = this.terminals.get(key)
    if (!inst) return
    log.info("terminal_detach", { key })
    inst.intentional = true
    this.terminals.delete(key)
    try { inst.proc.kill() } catch {}
    // Agent terminals: also destroy the throwaway grouped viewer session (the
    // agent window itself always survives).
    if (inst.kind === "agent" && inst.agentTarget) {
      void this.agentTerm.killViewer(deviceName, inst.agentTarget)
    }
  }

  /** Permanently destroy a terminal: kill any viewers AND the tmux session. */
  async close(sessionName: string, terminalId: string): Promise<void> {
    for (const [key, inst] of this.terminals) {
      if (inst.sessionName === sessionName && inst.terminalId === terminalId) {
        inst.intentional = true
        this.terminals.delete(key)
        try { inst.proc.kill() } catch {}
      }
    }
    log.info("terminal_close", { sessionName, terminalId })
    try { await this.term.killTerminal(sessionName, terminalId) } catch {}
  }

  /** List the (persisted) terminals for a session — source of truth is tmux. */
  listForSession(sessionName: string): Promise<TerminalSummary[]> {
    return this.term.listTerminals(sessionName)
  }

  /** Session deleted: kill all its viewers AND tmux sessions. */
  async killAllForSession(sessionName: string): Promise<void> {
    for (const [key, inst] of this.terminals) {
      if (inst.sessionName === sessionName) {
        log.info("terminal_killed_session_cleanup", { key })
        inst.intentional = true
        this.terminals.delete(key)
        try { inst.proc.kill() } catch {}
      }
    }
    try { await this.term.killAllTerminals(sessionName) } catch {}
  }

  /** Device disconnect: detach its viewers but keep the tmux sessions alive. */
  detachAllForDevice(deviceName: string): void {
    for (const [key, inst] of this.terminals) {
      if (inst.deviceName === deviceName) {
        inst.intentional = true
        this.terminals.delete(key)
        try { inst.proc.kill() } catch {}
        if (inst.kind === "agent" && inst.agentTarget) {
          void this.agentTerm.killViewer(deviceName, inst.agentTarget)
        }
      }
    }
  }

  has(deviceName: string, sessionName: string, terminalId: string): boolean {
    return this.terminals.has(TerminalManager.key(deviceName, sessionName, terminalId))
  }

  /** Whether the backing tmux session exists (persists across detach). */
  hasSession(sessionName: string, terminalId: string): Promise<boolean> {
    return this.term.hasTerminal(sessionName, terminalId)
  }

  count(): number {
    return this.terminals.size
  }

  /** Broker shutdown: detach all viewers. tmux sessions intentionally survive. */
  shutdown(): void {
    for (const [key, inst] of this.terminals) {
      log.info("terminal_shutdown_detach", { key })
      inst.intentional = true
      try { inst.proc.kill() } catch {}
    }
    this.terminals.clear()
  }
}
