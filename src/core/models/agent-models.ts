// The launcher's per-host model catalog (GET /agents/models): every INSTALLED agent with its
// models and the reasoning levels for its default and for each model, in one answer. Clients
// cache it per host and refetch only on `agent_models_changed`, instead of asking /models and
// /reasoning-levels every time the New Session screen opens.
import type { AgentKind } from "../agents/types"
import type { ModelInfo } from "./discovery"
import { shouldShowReasoningControl, supportedReasoningLevels, type ReasoningLevelInfo } from "./reasoning-levels"

export type ReasoningOptions = { levels: ReasoningLevelInfo[]; visible: boolean }

export type AgentModelsEntry = {
  kind: AgentKind
  models: { id: string; displayName: string }[]
  /** Levels with no model picked (the agent's Default). */
  reasoning: ReasoningOptions
  /** Levels per model id — the same answer GET /reasoning-levels?agent=&model= gives. */
  modelReasoning: Record<string, ReasoningOptions>
}

/** Same visibility rule as GET /reasoning-levels: a picker only when there is a real choice. */
export function reasoningOptions(agent: AgentKind, models: ModelInfo[], modelId?: string): ReasoningOptions {
  const visible = shouldShowReasoningControl(agent, models, modelId)
  return { levels: visible ? supportedReasoningLevels(agent, models, modelId) : [], visible }
}

export function buildAgentModels(
  installed: AgentKind[],
  lookup: (agent: AgentKind) => ModelInfo[],
): { agents: AgentModelsEntry[] } {
  return {
    agents: installed.map((kind) => {
      const models = lookup(kind)
      return {
        kind,
        models: models.map((m) => ({ id: m.id, displayName: m.displayName })),
        reasoning: reasoningOptions(kind, models),
        modelReasoning: Object.fromEntries(models.map((m) => [m.id, reasoningOptions(kind, models, m.id)])),
      }
    }),
  }
}
