import type { AgentKind } from "../agents/types"
import type { ModelInfo } from "./discovery"

export class ModelCache {
  private data = new Map<AgentKind, { models: ModelInfo[]; fetchedAt: number }>()

  get(agent: AgentKind): ModelInfo[] {
    return this.data.get(agent)?.models ?? []
  }

  set(agent: AgentKind, models: ModelInfo[], fetchedAt?: number): void {
    this.data.set(agent, { models, fetchedAt: fetchedAt ?? Date.now() })
  }

  isExpired(agent: AgentKind, ttlMs: number): boolean {
    const entry = this.data.get(agent)
    if (!entry) return true
    return Date.now() - entry.fetchedAt > ttlMs
  }
}
