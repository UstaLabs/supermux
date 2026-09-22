import type { AgentKind } from "../../shared/agents"
import type { ClaudeCodeAdapter } from "../agents/claude"
import type { CodexAdapter } from "../agents/codex/adapter"
import type { CoreCodexAdapter } from "../agents/codex/core-adapter"
import type { CodexSpawnHandle } from "../agents/codex/spawn"
import type { CoreCursorAdapter } from "../agents/cursor/core-adapter"
import type { CoreOpenCodeAdapter } from "../agents/opencode/core-adapter"
import type { GrokAdapter } from "../agents/grok/adapter"
import type { CoreGrokAdapter } from "../agents/grok/core-adapter"

export type GrokRuntimeAdapter = CoreGrokAdapter | GrokAdapter
export type CodexRuntimeAdapter = CoreCodexAdapter | CodexAdapter

export type SessionRuntime =
  | { kind: typeof AgentKind.Claude; adapter: ClaudeCodeAdapter }
  | { kind: typeof AgentKind.Codex; adapter: CodexRuntimeAdapter; handle?: CodexSpawnHandle }
  | { kind: typeof AgentKind.Cursor; adapter: CoreCursorAdapter }
  | { kind: typeof AgentKind.OpenCode; adapter: CoreOpenCodeAdapter }
  // grok/opencode own the child inside the adapter (adapter.stop() kills it).
  | { kind: typeof AgentKind.Grok; adapter: GrokRuntimeAdapter }

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
