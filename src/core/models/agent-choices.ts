// The access levels and efforts each agent takes, from the broker's live catalog: the enums of the delegate /
// council / council_add tool schemas (decided 2026-10-31: the agent read them only through list_models and guessed,
// always picking the most restrictive level), and the check of a call's values.
import { AGENT_KINDS, type AgentKind } from "../../shared/agents"
import type { ModelInfo } from "./discovery"
import { modesFor } from "../agents/permission-modes"
import { supportedReasoningLevels } from "./reasoning-levels"

export type AgentChoices = Record<string, {
  access: Array<{ id: string; level: string; description: string }>
  /** Every effort any of the agent's models takes; empty: it has no effort setting; null: not known yet (no catalog). */
  efforts: string[] | null
}>

/** Their effort comes from the model catalog (Codex, Grok): without one yet, it isn't known. */
const EFFORT_FROM_CATALOG = new Set<string>(["codex", "grok"])

export function agentChoices(lookupModels: (agent: AgentKind) => ModelInfo[]): AgentChoices {
  const out: AgentChoices = {}
  for (const agent of AGENT_KINDS) {
    const models = lookupModels(agent)
    const efforts = new Set<string>()
    for (const m of [undefined, ...models.map((x) => x.id)]) for (const l of supportedReasoningLevels(agent, models, m)) efforts.add(l.id)
    out[agent] = {
      access: modesFor(agent).map((m) => ({ id: m.id, level: m.level, description: m.description })),
      // Right after a broker start the catalog can still be empty: then don't forbid what isn't known.
      efforts: models.length === 0 && EFFORT_FROM_CATALOG.has(agent) ? null : [...efforts],
    }
  }
  return out
}

/** Undefined when `effort` is absent or one of the agent's efforts; else why not. */
export function effortError(choices: AgentChoices, agent: AgentKind, effort: string | undefined): string | undefined {
  if (effort === undefined) return undefined
  const efforts = choices[agent]?.efforts
  if (efforts === null || efforts === undefined) return undefined
  if (efforts.length === 0) return `${agent} has no effort setting: leave effort out`
  return efforts.includes(effort) ? undefined : `effort must be one of ${efforts.join(", ")} for ${agent}`
}
