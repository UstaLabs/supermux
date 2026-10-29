// list_models (spec §2): every agent kind with whether it is ready, its permission modes (each with
// a level) and its models, plus the calling session's own agent, model, effort and access.
import { AGENT_KINDS, type AgentKind } from "../../shared/agents"
import { modesFor, type PermissionLevel } from "../agents/permission-modes"
import { supportedReasoningLevels } from "./reasoning-levels"
import type { ModelInfo } from "./discovery"

/** Install and sign-in state of one agent kind (the data behind GET /agents/status). */
export type AgentReadiness = {
  kind: AgentKind
  installed: boolean
  authed: boolean
  /** It works with no credentials (OpenCode's free tier), so not being signed in does not stop it. */
  usableWithoutAuth: boolean
}

/** A per-model gate, where the agent reports one (Codex usage `model_usage`). */
export type ModelGate = { id: string; available: boolean; availableAtIso: string | null }

export type ListModelsYou = { agent: AgentKind; model: string | null; effort: string | null; access: string }

export type ListModelsInput = {
  you: ListModelsYou
  /** Only this agent kind; every kind when absent. */
  agent?: AgentKind
  statuses: AgentReadiness[]
  lookupModels: (agent: AgentKind) => ModelInfo[]
  modelGates: (agent: AgentKind) => ModelGate[]
}

export type ListedModel = { id: string; label: string; efforts: string[]; ready: boolean; why_not?: string }

export type ListedAgent = {
  agent: AgentKind
  ready: boolean
  why_not?: string
  access: { id: string; label: string; level: PermissionLevel }[]
  models: ListedModel[]
}

export function listModels(input: ListModelsInput): { you: ListModelsYou; agents: ListedAgent[] } {
  const kinds: AgentKind[] = input.agent ? [input.agent] : [...AGENT_KINDS]
  return {
    you: input.you,
    agents: kinds.map((kind) => {
      const status = input.statuses.find((s) => s.kind === kind)
      const whyNot = !status?.installed ? "not installed" : !(status.authed || status.usableWithoutAuth) ? "not signed in" : undefined
      const models = input.lookupModels(kind)
      const gates = input.modelGates(kind)
      return {
        agent: kind,
        ready: whyNot === undefined,
        ...(whyNot ? { why_not: whyNot } : {}),
        access: modesFor(kind).map((m) => ({ id: m.id, label: m.label, level: m.level })),
        models: models.map((m): ListedModel => {
          const listed = { id: m.id, label: m.displayName, efforts: supportedReasoningLevels(kind, models, m.id).map((l) => l.id) }
          const gate = gates.find((g) => g.id === m.id)
          if (!gate || gate.available) return { ...listed, ready: true }
          return { ...listed, ready: false, why_not: gate.availableAtIso ? `unavailable until ${gate.availableAtIso}` : "unavailable" }
        }),
      }
    }),
  }
}
