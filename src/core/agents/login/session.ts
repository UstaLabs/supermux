import type { AgentKind } from "../types"

export type LoginPhase = "starting" | "awaiting_user" | "success" | "failed" | "cancelled"
export interface LoginState { kind: AgentKind; phase: LoginPhase; url?: string; code?: string; error?: string; needsCode?: boolean }
export interface LoginProc {
  onStdout: (cb: (chunk: string) => void) => void
  onExit: (cb: (code: number | null) => void) => void
  kill: () => void
  write: (data: string) => void
}
export interface LoginSessionDeps {
  kind: AgentKind
  spawn: () => LoginProc
  parse: (stdout: string) => { url?: string; code?: string } | null
  isAuthed: () => boolean
  onChange: (state: LoginState) => void
  needsCode?: boolean
  alreadyAuthed?: boolean
  pollMs?: number
  setInterval?: (fn: () => void, ms: number) => any
  clearInterval?: (h: any) => void
}

export class LoginSession {
  private state: LoginState
  private acc = ""
  private proc?: LoginProc
  private pollHandle: any
  private done = false
  private cancelling = false
  private readonly setI: (fn: () => void, ms: number) => any
  private readonly clearI: (h: any) => void

  constructor(private readonly deps: LoginSessionDeps) {
    this.state = { kind: deps.kind, phase: "starting" }
    this.setI = deps.setInterval ?? ((fn, ms) => setInterval(fn, ms))
    this.clearI = deps.clearInterval ?? ((h) => clearInterval(h))
  }

  getState(): LoginState { return this.state }

  sendInput(text: string): void { this.proc?.write(text) }

  private emit(patch: Partial<LoginState>) {
    this.state = { ...this.state, ...patch }
    this.deps.onChange(this.state)
  }

  private finish(phase: LoginPhase, error?: string) {
    if (this.done) return
    this.done = true
    if (this.pollHandle) this.clearI(this.pollHandle)
    this.emit({ phase, error })
  }

  start() {
    this.emit({ phase: "starting" })
    this.proc = this.deps.spawn()
    this.proc.onStdout((chunk) => {
      if (this.done) return
      this.acc += chunk
      const parsed = this.deps.parse(this.acc)
      if (parsed && (parsed.url || parsed.code)) {
        this.emit({ phase: "awaiting_user", url: parsed.url, code: parsed.code, needsCode: this.deps.needsCode })
      }
    })
    this.proc.onExit((code) => {
      // If cancel() is already in flight, the cancelling flag is set before
      // kill() is called. The synchronous kill() → onExit path must yield to
      // cancel(), so we bail out here and let cancel() call finish().
      if (this.done || this.cancelling) return
      if (this.deps.alreadyAuthed) {
        if (code === 0) this.finish("success")
        else this.finish("failed", `login exited (${code}). ${this.acc.slice(-400)}`)
      } else {
        if (this.deps.isAuthed()) this.finish("success")
        else this.finish("failed", `login exited (${code}). ${this.acc.slice(-400)}`)
      }
    })
    this.pollHandle = this.setI(() => {
      if (this.done) return
      if (this.deps.alreadyAuthed) return
      if (this.deps.isAuthed()) this.finish("success")
    }, this.deps.pollMs ?? 1500)
  }

  cancel() {
    if (this.done) return
    // Set cancelling BEFORE kill() so the synchronous onExit callback
    // (triggered by kill()) sees the flag and does nothing.
    this.cancelling = true
    this.proc?.kill()
    // Now safely call finish; done is still false, so this wins.
    this.finish("cancelled")
  }
}
