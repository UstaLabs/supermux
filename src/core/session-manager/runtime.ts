import type { AgentKind } from "../../shared/agents"
import type { CoreAdapter } from "../agents/core-bridge/core-adapter"
import type { CodexSpawnHandle } from "../agents/codex/spawn"

export type SessionRuntime =
  | { kind: typeof AgentKind.Claude; adapter: CoreAdapter }
  | { kind: typeof AgentKind.Codex; adapter: CoreAdapter; handle?: CodexSpawnHandle }
  | { kind: typeof AgentKind.Cursor; adapter: CoreAdapter }
  | { kind: typeof AgentKind.OpenCode; adapter: CoreAdapter }
  | { kind: typeof AgentKind.Grok; adapter: CoreAdapter }

export class RuntimeRegistry {
  private readonly entries = new Map<string, SessionRuntime>()

  get(sessionId: string): SessionRuntime | undefined {
    return this.entries.get(sessionId)
  }

  set(sessionId: string, runtime: SessionRuntime): void {
    this.entries.set(sessionId, runtime)
  }

  delete(sessionId: string): void {
    this.entries.delete(sessionId)
  }

  has(sessionId: string): boolean {
    return this.entries.has(sessionId)
  }
}
