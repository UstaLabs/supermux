// Model lists an agent itself reported at a session start (#102: Cursor's ACP options), kept across broker restarts
// in one JSON file per agent. Model discovery prefers them to a CLI listing.
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "fs"
import { join } from "path"
import type { AgentKind } from "../agents/types"
import { AgentKind as Agent } from "../../shared/agents"
import { cursorModelsFromAcp, type ModelInfo } from "./discovery"
import { claudeModelsFromAgent } from "./claude-models"
import type { AgentModelEntry } from "../../../packages/supermux-core/src/events/normalized.js"

export class LearnedModels {
  private readonly cache = new Map<AgentKind, ModelInfo[] | undefined>()
  /** When each agent's list was last learned or confirmed (ms), for a freshness check. */
  private readonly at = new Map<AgentKind, number>()

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
        const parsed = JSON.parse(readFileSync(this.file(agent), "utf8")) as { models?: unknown; learnedAt?: unknown }
        if (Array.isArray(parsed.models) && parsed.models.length) models = parsed.models as ModelInfo[]
        const when = typeof parsed.learnedAt === "string" ? Date.parse(parsed.learnedAt) : NaN
        if (models && Number.isFinite(when) && !this.at.has(agent)) this.at.set(agent, when)
      }
    } catch { /* unreadable: learned again at the next start */ }
    this.cache.set(agent, models)
    return models
  }

  /** When [agent]'s list was last learned or confirmed (ms); undefined before the first. */
  learnedAt(agent: AgentKind): number | undefined {
    this.get(agent)
    return this.at.get(agent)
  }

  /**
   * Keeps [models] as [agent]'s list, learned or confirmed at [now]. Returns true when it changed; an empty list is
   * never kept.
   */
  learn(agent: AgentKind, models: ModelInfo[], now: number = Date.now()): boolean {
    if (!models.length) return false
    this.at.set(agent, now)
    if (JSON.stringify(this.get(agent)) === JSON.stringify(models)) return false
    mkdirSync(this.dir, { recursive: true })
    const file = this.file(agent)
    writeFileSync(`${file}.tmp`, JSON.stringify({ models, learnedAt: new Date(now).toISOString() }, null, 2) + "\n")
    renameSync(`${file}.tmp`, file)
    this.cache.set(agent, models)
    return true
  }
}

/**
 * Makes the models a session's agent reports its catalog: Cursor's ACP options (#102), Claude's initialize reply
 * (#129). The list its adapter already holds (a session opens, and reports, before the resume arms wire the adapter's
 * events) and every later models-update. [onLearned] gets each list that changed what was learned.
 */
export function followAdapterModels(o: {
  agent: AgentKind | undefined
  adapter: { offeredModels?: AgentModelEntry[]; on(event: "models-update", listener: () => void): unknown }
  learned: LearnedModels
  onLearned: (agent: AgentKind, models: ModelInfo[]) => void
}): void {
  const agent = o.agent
  if (agent !== Agent.Cursor && agent !== Agent.Claude) return
  const learn = () => {
    const entries = o.adapter.offeredModels
    if (!entries?.length) return
    const models = agent === Agent.Claude ? claudeModelsFromAgent(entries) : cursorModelsFromAcp(entries)
    if (o.learned.learn(agent, models)) o.onLearned(agent, models)
  }
  o.adapter.on("models-update", learn)
  learn()
}
