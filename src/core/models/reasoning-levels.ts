import type { AgentKind } from "../agents/types"
import { AgentKind as Agent } from "../../shared/agents"
import type { ModelInfo } from "./discovery"

export type ReasoningLevelInfo = {
  id: string
  description?: string
}

/** Claude `--effort` values, low → high. */
export const CLAUDE_EFFORT_ORDER = ["low", "medium", "high", "xhigh", "max"] as const

const CLAUDE_DESCRIPTIONS: Record<string, string> = {
  low: "Fast responses with lighter reasoning",
  medium: "Balanced speed and depth",
  high: "Greater reasoning depth",
  xhigh: "Extra high reasoning depth",
  max: "Maximum reasoning depth",
}

const GENERIC_ORDER = ["minimal", "low", "medium", "high", "xhigh", "max", "extra_high"]

function rank(level: string): number {
  const claudeIdx = CLAUDE_EFFORT_ORDER.indexOf(level as typeof CLAUDE_EFFORT_ORDER[number])
  if (claudeIdx >= 0) return claudeIdx
  const genericIdx = GENERIC_ORDER.indexOf(level)
  if (genericIdx >= 0) return 100 + genericIdx
  return 50
}

export function highestReasoningLevel(levels: ReasoningLevelInfo[]): string | undefined {
  if (levels.length === 0) return undefined
  return levels.reduce((best, l) => (rank(l.id) >= rank(best.id) ? l : best)).id
}

export function claudeReasoningLevels(): ReasoningLevelInfo[] {
  return CLAUDE_EFFORT_ORDER.map((id) => ({ id, description: CLAUDE_DESCRIPTIONS[id] }))
}

export function codexReasoningLevelsForModel(models: ModelInfo[], modelId?: string): ReasoningLevelInfo[] {
  if (!modelId) return []
  const m = models.find((x) => x.id === modelId)
  return m?.reasoningLevels ?? []
}

export function supportedReasoningLevels(
  agent: AgentKind,
  models: ModelInfo[],
  modelId?: string,
): ReasoningLevelInfo[] {
  if (agent === Agent.Cursor || agent === Agent.OpenCode) return []
  if (agent === Agent.Claude) return claudeReasoningLevels()
  return codexReasoningLevelsForModel(models, modelId)
}

export function effectiveReasoningLevel(
  agent: AgentKind,
  models: ModelInfo[],
  modelId: string | undefined,
  stored: string | undefined,
): string | undefined {
  const levels = supportedReasoningLevels(agent, models, modelId)
  if (levels.length === 0) return undefined
  const ids = levels.map((l) => l.id)
  if (stored && ids.includes(stored)) return stored
  return highestReasoningLevel(levels)
}

export function clampReasoningLevel(
  agent: AgentKind,
  models: ModelInfo[],
  modelId: string | undefined,
  level: string | undefined,
): string | undefined {
  if (!level) return undefined
  const levels = supportedReasoningLevels(agent, models, modelId)
  const ids = levels.map((l) => l.id)
  if (ids.includes(level)) return level
  return highestReasoningLevel(levels)
}

export function shouldShowReasoningControl(
  agent: AgentKind,
  models: ModelInfo[],
  modelId?: string,
): boolean {
  return supportedReasoningLevels(agent, models, modelId).length > 1
}
