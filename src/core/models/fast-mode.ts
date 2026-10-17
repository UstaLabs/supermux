import type { AgentKind } from "../agents/types"
import { AgentKind as Agent } from "../../shared/agents"
import type { ModelInfo } from "./discovery"

/** Codex's catalog id for its "Fast" service tier (`codex debug models` → `service_tiers`). */
export const CODEX_FAST_SERVICE_TIER = "priority"

/**
 * Mirrors Claude Code's own check (2.1.291): fast mode is offered on Opus 4.8 and Opus 5+.
 * The CLI's default model is Opus, so no model (or the bare `opus` alias) counts too.
 */
export function claudeModelSupportsFast(modelId?: string): boolean {
  if (!modelId) return true
  const id = modelId.toLowerCase()
  if (id === "opus" || id.startsWith("opus[")) return true
  return id.includes("opus-4-8") || id.includes("opus-5")
}

/** Whether a session on this agent and model can run in fast mode. */
export function supportsFastMode(agent: AgentKind, models: ModelInfo[], modelId?: string): boolean {
  if (agent === Agent.Claude) {
    const known = modelId ? models.find((m) => m.id === modelId) : undefined
    return known ? known.supportsFast === true : claudeModelSupportsFast(modelId)
  }
  if (agent === Agent.Codex) {
    if (!modelId) return false
    return models.find((m) => m.id === modelId)?.supportsFast === true
  }
  return false
}

/** The fast mode a session actually runs with: asked for AND supported by its model. */
export function resolveSessionFast(
  session: { agent: AgentKind; model?: string; fastMode?: boolean },
  getModels: (agent: AgentKind) => ModelInfo[],
): boolean {
  return session.fastMode === true && supportsFastMode(session.agent, getModels(session.agent), session.model)
}
