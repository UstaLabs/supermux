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
 * Refresh the model cache from the given per-agent discoverers.
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
): Promise<void> {
  const entries = Object.entries(discoverers) as [AgentKind, ModelDiscoverer][]
  await Promise.all(
    entries.map(async ([agent, discover]) => {
      let models: ModelInfo[] = []
      try {
        models = await discover()
      } catch {
        models = []
      }
      if (models.length > 0) {
        cache.set(agent, models)
        return
      }
      opts?.onEmpty?.(agent)
      // Only store an empty list when we have nothing yet; never clobber a
      // previously discovered list with a transient empty result.
      if (cache.get(agent).length === 0) cache.set(agent, [])
    }),
  )
}
