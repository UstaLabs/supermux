import type { AgentKind } from "../agents/types"
import type { Session } from "../session-manager/types"
import type { ModelInfo } from "./discovery"
import { clampReasoningLevel, effectiveReasoningLevel } from "./reasoning-levels"

export type ModelLookup = (agent: AgentKind) => ModelInfo[]

export function resolveSessionEffort(
  session: Pick<Session, "agent" | "model" | "reasoningLevel">,
  getModels: ModelLookup,
): string | undefined {
  return effectiveReasoningLevel(
    session.agent,
    getModels(session.agent),
    session.model,
    session.reasoningLevel,
  )
}

export function clampSessionReasoningLevel(
  session: Pick<Session, "agent" | "model" | "reasoningLevel">,
  newModel: string | undefined,
  getModels: ModelLookup,
): string | undefined {
  if (!session.reasoningLevel) return session.reasoningLevel
  return clampReasoningLevel(session.agent, getModels(session.agent), newModel, session.reasoningLevel)
}
