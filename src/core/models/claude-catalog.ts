import type { ModelInfo } from "./discovery"
import { AgentKind as Agent } from "../../shared/agents"
import { claudeModelSupportsFast } from "./fast-mode"

/** Claude Code's signed model catalog. This is the list the CLI's picker uses. */
export const CLAUDE_MODEL_CATALOG_URL = "https://downloads.claude.ai/model-catalog/v1/catalog.json"

const EFFORT_ORDER = ["low", "medium", "high", "xhigh", "max"]
const EFFORT_RANK = new Map<string, number>(EFFORT_ORDER.map((id, i) => [id, i]))

/** Numeric compare for Claude Code versions (`2.1.319`). Non-numeric suffixes are ignored. */
export function claudeVersionAtLeast(installed: string, minimum: string): boolean {
  const parts = (v: string) => v.split(/[^0-9]+/).filter(Boolean).map((n) => Number(n))
  const a = parts(installed)
  const b = parts(minimum)
  const n = Math.max(a.length, b.length)
  for (let i = 0; i < n; i++) {
    const d = (a[i] ?? 0) - (b[i] ?? 0)
    if (d !== 0) return d > 0
  }
  return true
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : undefined
}

function modelRows(doc: unknown): unknown[] {
  const root = asRecord(doc)
  if (!root) return []
  const surfaces = asRecord(root.surfaces) ?? asRecord(asRecord(root.document)?.surfaces)
  const cc = asRecord(surfaces?.cc)
  const configs = cc?.model_selector_config
  if (Array.isArray(configs)) {
    for (const config of configs) {
      const models = asRecord(config)?.models
      if (Array.isArray(models)) return models
    }
  }
  const catalogModels = asRecord(asRecord(root.catalog)?.config)?.models
  return Array.isArray(catalogModels) ? catalogModels : []
}

function orderEfforts(ids: string[]): string[] {
  const unique = [...new Set(ids)]
  unique.sort((a, b) => (EFFORT_RANK.get(a) ?? 50) - (EFFORT_RANK.get(b) ?? 50))
  return unique
}

function effortIds(model: Record<string, unknown>): string[] {
  const thinking = asRecord(model.thinking)
  const type = typeof thinking?.type === "string" ? thinking.type : ""
  const fromOptions = Array.isArray(thinking?.effort_options)
    ? thinking.effort_options.flatMap((option) => {
        const id = asRecord(option)?.id
        return typeof id === "string" && id ? [id] : []
      })
    : []
  if (fromOptions.length > 0 && (type === "effort" || type === "effort_and_mode" || type === "")) return orderEfforts(fromOptions)
  if (type === "none" || type === "mode") return []
  const runtime = asRecord(model.runtime)
  const levels = Array.isArray(runtime?.effort_levels)
    ? runtime.effort_levels.filter((id): id is string => typeof id === "string" && id.length > 0)
    : []
  return orderEfforts(levels)
}

function displayName(name: string, id: string): string {
  const label = name.trim() || id
  return label.startsWith("Claude ") ? label : `Claude ${label}`
}

/**
 * Models Claude Code offers, with each model's own effort levels.
 * `cliVersion` drops rows whose `min_claude_code_version` is newer than the installed CLI.
 * Absent version keeps every row.
 */
export function claudeModelsFromCatalog(doc: unknown, cliVersion?: string): ModelInfo[] {
  const out: ModelInfo[] = []
  const seen = new Set<string>()
  for (const row of modelRows(doc)) {
    const model = asRecord(row)
    if (!model) continue
    const id = typeof model.id === "string" ? model.id : ""
    if (!id.startsWith("claude-") || seen.has(id)) continue
    const minimum = typeof model.min_claude_code_version === "string" ? model.min_claude_code_version : undefined
    if (cliVersion && minimum && !claudeVersionAtLeast(cliVersion, minimum)) continue
    seen.add(id)
    const name = typeof model.name === "string" ? model.name : id
    const efforts = effortIds(model)
    const fast = asRecord(model.fast_mode) !== undefined || claudeModelSupportsFast(id)
    out.push({
      id,
      displayName: displayName(name, id),
      agent: Agent.Claude,
      ...(efforts.length ? { reasoningLevels: efforts.map((effort) => ({ id: effort })) } : {}),
      ...(fast ? { supportsFast: true as const } : {}),
    })
  }
  return out
}
