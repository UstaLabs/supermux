import * as claude from "./claude/session"
import * as codex from "./codex/session"
import * as cursor from "./cursor/session"
import * as opencode from "./opencode/session"
import * as grok from "./grok/session"
import type { SpawnDeps, SpawnArgs, SpawnResult } from "../session-manager/spawn-helper"
import type { CommandContextCtx, ResumeCtx, ResumeRow, ApplyConfigCtx, ApplyConfigRow, ApplyConfigChange, ApplyConfigResult } from "./session-types"
import type { AgentAdapter } from "./types"
import type { AgentKind } from "../../shared/agents"

type AgentModule = {
  spawn(deps: SpawnDeps, args: SpawnArgs): Promise<SpawnResult>
  /** Dialect half of resume; the SessionManager registers + wires the result. */
  resume?(ctx: ResumeCtx, session: ResumeRow, name: string): Promise<{ adapter: AgentAdapter; handle?: unknown }>
  /** Dialect half of a live model/effort change; the SessionManager owns the
   *  queueing, the registry writes, and the runtime swap for restart-style
   *  kinds. An undefined leaf means "the kind does not support it". */
  applyConfig?(ctx: ApplyConfigCtx, session: ApplyConfigRow, name: string, change: ApplyConfigChange): Promise<ApplyConfigResult>
  /** Opaque slash-command discovery context; undefined = the kind needs none. */
  commandContext?(ctx: CommandContextCtx): unknown
}

export const agents = { claude, codex, cursor, opencode, grok } satisfies Record<AgentKind, AgentModule>

/** Kind-indexed view of the same map for generic dispatch: the explicit
 * annotation widens each module to AgentModule (and cuts type-inference
 * cycles through the module graph). */
export const agentModules: Record<AgentKind, AgentModule> = agents
