import { RuntimeRegistry, type SessionRuntime } from "./runtime"
import type { Registry } from "./registry"
import type { AgentAdapter } from "../agents/types"
import type { ClaudeCodeAdapter } from "../agents/claude"
import type { CodexAdapter } from "../agents/codex/adapter"
import type { CodexSpawnHandle } from "../agents/codex/spawn"
import type { CursorAdapter } from "../agents/cursor/adapter"
import type { OpenCodeAdapter } from "../agents/opencode/adapter"
import type { OpenCodeSpawnHandle } from "../agents/opencode/spawn"
import type { GrokAdapter } from "../agents/grok/adapter"
import { AgentKind } from "../../shared/agents"
import { makeLogger } from "../../shared/log"

const log = makeLogger("session-manager")

/**
 * The component that owns per-session runtime state (Move 2 of the
 * session-consolidation spec). It grows stage by stage: today it owns the ONE
 * runtime store; the socket handlers and kill flow move in next; spawn/resume
 * arrive with the resume unification (PR 3).
 *
 * Rules: no agent-kind checks leak OUT of this layer into services, and no
 * shared broker state lives anywhere else. Collaborators are injected once at
 * construction — never per-call deps bags.
 */
export class SessionManager {
  readonly registry: Registry
  readonly runtimes = new RuntimeRegistry()

  constructor(registry: Registry) {
    this.registry = registry
  }

  adapterFor(sessionId: string): AgentAdapter | undefined {
    return this.runtimes.get(sessionId)?.adapter
  }

  registerRuntime(sessionId: string, runtime: SessionRuntime): void {
    this.runtimes.set(sessionId, runtime)
  }

  deleteRuntime(sessionId: string): void {
    this.runtimes.delete(sessionId)
  }

  registerClaudeRuntime(sessionId: string, adapter: ClaudeCodeAdapter): void {
    this.registerRuntime(sessionId, { kind: AgentKind.Claude, adapter })
  }

  registerCodexRuntime(sessionId: string, name: string, adapter: CodexAdapter, handle: CodexSpawnHandle): void {
    this.registerRuntime(sessionId, { kind: AgentKind.Codex, adapter, handle })
    handle.onExit?.((code: number | null) => {
      log.info("codex_app_server_exited", { name, code })
      this.deleteRuntime(sessionId)
    })
  }

  registerCursorRuntime(sessionId: string, adapter: CursorAdapter): void {
    this.registerRuntime(sessionId, { kind: AgentKind.Cursor, adapter })
  }

  // grok's stdio child is owned by the adapter (no separate handle), so unlike
  // opencode there's no handle.onExit to unregister on — adapter.stop() is the kill.
  registerGrokRuntime(sessionId: string, adapter: GrokAdapter): void {
    this.registerRuntime(sessionId, { kind: AgentKind.Grok, adapter })
  }

  registerOpenCodeRuntime(sessionId: string, name: string, adapter: OpenCodeAdapter, handle: OpenCodeSpawnHandle): void {
    this.registerRuntime(sessionId, { kind: AgentKind.OpenCode, adapter, handle })
    handle.onExit?.((code: number | null) => {
      log.info("opencode_serve_exited", { name, code })
      this.deleteRuntime(sessionId)
    })
  }
}
