import * as claude from "./claude/session"
import * as codex from "./codex/session"
import * as cursor from "./cursor/session"
import * as opencode from "./opencode/session"
import * as grok from "./grok/session"
import type { SpawnDeps, SpawnArgs, SpawnResult } from "../session-manager/spawn-helper"
import type { ResumeCtx, ResumeRow } from "./session-types"
import type { AgentAdapter } from "./types"
import type { AgentKind } from "../../shared/agents"

type AgentModule = {
  spawn(deps: SpawnDeps, args: SpawnArgs): Promise<SpawnResult>
  /** Dialect half of resume; the SessionManager registers + wires the result. */
  resume?(ctx: ResumeCtx, session: ResumeRow, name: string): Promise<{ adapter: AgentAdapter; handle?: unknown }>
}

export const agents = { claude, codex, cursor, opencode, grok } satisfies Record<AgentKind, AgentModule>
