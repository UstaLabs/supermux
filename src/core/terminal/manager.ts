import { makeLogger } from "../../shared/log"
import { resolve as resolvePath } from "path"
import { existsSync } from "fs"

const log = makeLogger("terminal")

const PTY_HELPER = resolvePath(import.meta.dirname, "pty-helper")
const IDLE_TIMEOUT_MS = 30 * 60 * 1000

export interface TerminalInstance {
  key: string
  deviceName: string
  sessionName: string
  proc: ReturnType<typeof Bun.spawn>
  createdAt: number
  lastInputAt: number
  onData: (data: Uint8Array) => void
  onExit: (code: number) => void
}

export class TerminalManager {
  private terminals = new Map<string, TerminalInstance>()
  private idleTimer?: NodeJS.Timeout

  constructor() {
    this.idleTimer = setInterval(() => this.sweepIdle(), 60_000)
  }

  private static key(device: string, session: string): string {
    return `${device}:${session}`
  }

  spawn(opts: {
    deviceName: string
    sessionName: string
    workdir: string
    cols: number
    rows: number
    onData: (data: Uint8Array) => void
    onExit: (code: number) => void
  }): { ok: true } | { ok: false; error: string } {
    const key = TerminalManager.key(opts.deviceName, opts.sessionName)

    if (this.terminals.has(key)) {
      return { ok: false, error: "terminal already open for this session" }
    }

    if (!existsSync(PTY_HELPER)) {
      log.error("pty_helper_missing", { path: PTY_HELPER })
      return { ok: false, error: "pty-helper binary not found" }
    }

    const shell = process.env.SHELL ?? "/bin/bash"

    const proc = Bun.spawn(
      [PTY_HELPER, String(opts.cols), String(opts.rows), opts.workdir, shell],
      { stdin: "pipe", stdout: "pipe", stderr: "pipe" },
    )

    const inst: TerminalInstance = {
      key,
      deviceName: opts.deviceName,
      sessionName: opts.sessionName,
      proc,
      createdAt: Date.now(),
      lastInputAt: Date.now(),
      onData: opts.onData,
      onExit: opts.onExit,
    }

    this.terminals.set(key, inst)
    log.info("terminal_spawned", { key, workdir: opts.workdir, pid: proc.pid })

    this.pumpOutput(inst)

    proc.exited.then((code) => {
      log.info("terminal_exited", { key, code })
      this.terminals.delete(key)
      opts.onExit(code)
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

  write(deviceName: string, sessionName: string, data: Uint8Array): boolean {
    const inst = this.terminals.get(TerminalManager.key(deviceName, sessionName))
    if (!inst) return false
    inst.lastInputAt = Date.now()
    try {
      const stdin = inst.proc.stdin as import("bun").FileSink
      stdin.write(data)
      return true
    } catch {
      return false
    }
  }

  resize(deviceName: string, sessionName: string, cols: number, rows: number): boolean {
    const inst = this.terminals.get(TerminalManager.key(deviceName, sessionName))
    if (!inst) return false
    inst.lastInputAt = Date.now()
    const cmd = `\x00R${cols}:${rows}\n`
    try {
      const stdin = inst.proc.stdin as import("bun").FileSink
      stdin.write(new TextEncoder().encode(cmd))
      return true
    } catch {
      return false
    }
  }

  kill(deviceName: string, sessionName: string): void {
    const key = TerminalManager.key(deviceName, sessionName)
    const inst = this.terminals.get(key)
    if (!inst) return
    log.info("terminal_killed", { key })
    this.terminals.delete(key)
    try {
      inst.proc.kill()
    } catch {}
  }

  killAllForSession(sessionName: string): void {
    for (const [key, inst] of this.terminals) {
      if (inst.sessionName === sessionName) {
        log.info("terminal_killed_session_cleanup", { key })
        this.terminals.delete(key)
        try {
          inst.proc.kill()
        } catch {}
      }
    }
  }

  killAllForDevice(deviceName: string): void {
    for (const [key, inst] of this.terminals) {
      if (inst.deviceName === deviceName) {
        log.info("terminal_killed_device_cleanup", { key })
        this.terminals.delete(key)
        try {
          inst.proc.kill()
        } catch {}
      }
    }
  }

  has(deviceName: string, sessionName: string): boolean {
    return this.terminals.has(TerminalManager.key(deviceName, sessionName))
  }

  count(): number {
    return this.terminals.size
  }

  private sweepIdle(): void {
    const now = Date.now()
    for (const [key, inst] of this.terminals) {
      if (now - inst.lastInputAt > IDLE_TIMEOUT_MS) {
        log.info("terminal_idle_timeout", { key, idleMs: now - inst.lastInputAt })
        this.terminals.delete(key)
        try {
          inst.proc.kill()
        } catch {}
      }
    }
  }

  shutdown(): void {
    if (this.idleTimer) {
      clearInterval(this.idleTimer)
      this.idleTimer = undefined
    }
    for (const [key, inst] of this.terminals) {
      log.info("terminal_shutdown_kill", { key })
      try {
        inst.proc.kill()
      } catch {}
    }
    this.terminals.clear()
  }
}
