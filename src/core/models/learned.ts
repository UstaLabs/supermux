// Model lists an agent itself reported at a session start (#102: Cursor's ACP options), kept across broker restarts
// in one JSON file per agent. Model discovery prefers them to a CLI listing.
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "fs"
import { join } from "path"
import type { AgentKind } from "../agents/types"
import { AgentKind as Agent } from "../../shared/agents"
import { cursorModelsFromAcp, type ModelInfo } from "./discovery"

export class LearnedModels {
  private readonly cache = new Map<AgentKind, ModelInfo[] | undefined>()

  constructor(private readonly dir: string) {}

  private file(agent: AgentKind): string {
    return join(this.dir, `${agent}.json`)
  }

  /** The list last learned for [agent]; undefined before its first start reported one. */
  get(agent: AgentKind): ModelInfo[] | undefined {
    if (this.cache.has(agent)) return this.cache.get(agent)
    let models: ModelInfo[] | undefined
    try {
      if (existsSync(this.file(agent))) {
        const parsed = JSON.parse(readFileSync(this.file(agent), "utf8")) as { models?: unknown }
        if (Array.isArray(parsed.models) && parsed.models.length) models = parsed.models as ModelInfo[]
      }
    } catch { /* unreadable: learned again at the next start */ }
    this.cache.set(agent, models)
    return models
  }

  /** Keeps [models] as [agent]'s list. Returns true when it changed; an empty list is never kept. */
  learn(agent: AgentKind, models: ModelInfo[]): boolean {
    if (!models.length) return false
    if (JSON.stringify(this.get(agent)) === JSON.stringify(models)) return false
    mkdirSync(this.dir, { recursive: true })
    const file = this.file(agent)
    writeFileSync(`${file}.tmp`, JSON.stringify({ models, learnedAt: new Date().toISOString() }, null, 2) + "\n")
    renameSync(`${file}.tmp`, file)
    this.cache.set(agent, models)
    return true
  }
}

/**
 * Makes the models a Cursor session's agent reports (#102) Cursor's catalog: the list its adapter already holds (a
 * session opens, and reports, before the resume arms wire the adapter's events) and every later models-update.
 * [onLearned] gets each list that changed what was learned.
 */
export function followAdapterModels(o: {
  agent: AgentKind | undefined
  adapter: { offeredModels?: { value: string; name: string }[]; on(event: "models-update", listener: () => void): unknown }
  learned: LearnedModels
  onLearned: (agent: AgentKind, models: ModelInfo[]) => void
}): void {
  if (o.agent !== Agent.Cursor) return
  const learn = () => {
    const entries = o.adapter.offeredModels
    if (!entries?.length) return
    const models = cursorModelsFromAcp(entries)
    if (o.learned.learn(Agent.Cursor, models)) o.onLearned(Agent.Cursor, models)
  }
  o.adapter.on("models-update", learn)
  learn()
}
