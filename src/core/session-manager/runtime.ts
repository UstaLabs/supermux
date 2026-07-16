import type { AgentKind } from "../../shared/agents"
import type { ClaudeCodeAdapter } from "../agents/claude"
import type { CodexAdapter } from "../agents/codex/adapter"
import type { CodexSpawnHandle } from "../agents/codex/spawn"
import type { CursorAdapter } from "../agents/cursor/adapter"
import type { OpenCodeAdapter } from "../agents/opencode/adapter"
import type { OpenCodeSpawnHandle } from "../agents/opencode/spawn"
import type { GrokAdapter } from "../agents/grok/adapter"

export type SessionRuntime =
  | { kind: typeof AgentKind.Claude; adapter: ClaudeCodeAdapter }
  | { kind: typeof AgentKind.Codex; adapter: CodexAdapter; handle: CodexSpawnHandle }
  | { kind: typeof AgentKind.Cursor; adapter: CursorAdapter }
  | { kind: typeof AgentKind.OpenCode; adapter: OpenCodeAdapter; handle: OpenCodeSpawnHandle }
  // grok owns its `grok agent stdio` child inside the adapter (adapter.stop()
  // kills it), so unlike codex/opencode there's no separate spawn handle.
  | { kind: typeof AgentKind.Grok; adapter: GrokAdapter }

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
