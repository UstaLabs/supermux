import type { AgentKind } from "../types"
import { AgentKind as Agent } from "../../../shared/agents"
import { authCredPath, type DetectPaths } from "../detect"
import { LoginSession, type LoginState, type LoginProc } from "./session"
import { parseCodexDeviceAuth, parseCursorLoginUrl } from "./parse"

export interface LoginManagerDeps {
  paths: DetectPaths
  fileExists: (p: string) => boolean
  spawnLogin: (kind: AgentKind) => LoginProc
  onChange: (kind: AgentKind, state: LoginState) => void
  setInterval?: (fn: () => void, ms: number) => any
  clearInterval?: (h: any) => void
}

function parserFor(kind: AgentKind): (out: string) => { url?: string; code?: string } | null {
  if (kind === Agent.Codex) return (out) => parseCodexDeviceAuth(out)
  if (kind === Agent.Cursor) return (out) => { const url = parseCursorLoginUrl(out); return url ? { url } : null }
  if (kind === Agent.Claude) return (out) => { const url = parseCursorLoginUrl(out); return url ? { url } : null }
  return () => null
}

export class LoginManager {
  private sessions = new Map<AgentKind, LoginSession>()
  private states = new Map<AgentKind, LoginState>()

  constructor(private readonly deps: LoginManagerDeps) {}

  get(kind: AgentKind): LoginState | undefined { return this.states.get(kind) }

  start(kind: AgentKind): LoginState {
    this.cancel(kind)
    const session = new LoginSession({
      kind,
      spawn: () => this.deps.spawnLogin(kind),
      parse: parserFor(kind),
      isAuthed: () => this.deps.fileExists(authCredPath(kind, this.deps.paths)),
      onChange: (st) => { this.states.set(kind, st); this.deps.onChange(kind, st) },
      needsCode: kind === Agent.Claude,
      setInterval: this.deps.setInterval,
      clearInterval: this.deps.clearInterval,
    })
    this.sessions.set(kind, session)
    session.start()
    return this.states.get(kind)!
  }

  cancel(kind: AgentKind) {
    this.sessions.get(kind)?.cancel()
  }

  sendCode(kind: AgentKind, code: string): void {
    this.sessions.get(kind)?.sendInput(code.trim() + "\n")
  }
}
