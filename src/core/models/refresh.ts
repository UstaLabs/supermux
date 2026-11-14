import type { AgentKind } from "../agents/types"
import type { ModelCache } from "./cache"
import type { ModelInfo } from "./discovery"

export type ModelDiscoverer = () => Promise<ModelInfo[]>
export type ModelDiscoverers = Partial<Record<AgentKind, ModelDiscoverer>>

export type RefreshModelCacheOptions = {
  /** Called for every agent whose discovery returned no models (or threw). */
  onEmpty?: (agent: AgentKind) => void
}

/**
 * Refresh the model cache from the given per-agent discoverers. Resolves to the agents whose
 * cached list actually CHANGED, so the caller can announce `agent_models_changed` only then.
 *
 * Each discoverer runs independently. A non-empty result replaces the cached
 * list. An empty result (no models, or the discoverer threw) is reported via
 * `onEmpty` and does NOT overwrite a previously good list — so a transient
 * failure (expired/not-yet-refreshed OAuth token, network not ready at boot)
 * recovers on the next refresh instead of leaving the cache stuck empty.
 */
export async function refreshModelCache(
  cache: ModelCache,
  discoverers: ModelDiscoverers,
  opts?: RefreshModelCacheOptions,
): Promise<AgentKind[]> {
  const entries = Object.entries(discoverers) as [AgentKind, ModelDiscoverer][]
  const changed: AgentKind[] = []
  await Promise.all(
    entries.map(async ([agent, discover]) => {
      let models: ModelInfo[] = []
      try {
        models = await discover()
      } catch {
        models = []
      }
      if (models.length > 0) {
        if (JSON.stringify(cache.get(agent)) !== JSON.stringify(models)) changed.push(agent)
        cache.set(agent, models)
        return
      }
      opts?.onEmpty?.(agent)
      // Only store an empty list when we have nothing yet; never clobber a
      // previously discovered list with a transient empty result.
      if (cache.get(agent).length === 0) cache.set(agent, [])
    }),
  )
  return changed
}

/**
 * Fills the empty cache entries from the lists learned on an earlier run (any age), so GET /models answers at once
 * instead of waiting for discovery (Claude's runs Claude Code, ~2.5 s). The entries are stored as expired, so the next
 * refresh still asks the agent. Resolves to the agents seeded.
 */
export function seedModelCache(cache: ModelCache, learned: { get(agent: AgentKind): ModelInfo[] | undefined }, agents: readonly AgentKind[]): AgentKind[] {
  const seeded: AgentKind[] = []
  for (const agent of agents) {
    const models = learned.get(agent)
    if (!models?.length || cache.get(agent).length > 0) continue
    cache.set(agent, models, 0)
    seeded.push(agent)
  }
  return seeded
}

/** One discovery per agent at a time: a refresh asked for while one runs joins it (the boot refresh and a GET /models). */
export class SharedRefreshes<T = void> {
  private running = new Map<AgentKind, Promise<T>>()

  run(agent: AgentKind, start: () => Promise<T>): Promise<T> {
    const running = this.running.get(agent)
    if (running) return running
    const refresh = start().finally(() => this.running.delete(agent))
    this.running.set(agent, refresh)
    return refresh
  }
}
