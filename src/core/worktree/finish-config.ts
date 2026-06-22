import { existsSync, readFileSync } from "fs"
import { join } from "path"

export interface FinishConfig {
  defaultAction: "auto" | "merge" | "pr"
  archiveOnMerge: boolean
  prRequiresGreen: boolean
}

const DEFAULTS: FinishConfig = { defaultAction: "auto", archiveOnMerge: true, prRequiresGreen: false }

/** Load .mux/finish.json from a repo root, merged over defaults. Tolerant:
 *  missing file, bad JSON, or invalid values all fall back to defaults. */
export function loadFinishConfig(repoRoot: string): FinishConfig {
  try {
    const p = join(repoRoot, ".mux", "finish.json")
    if (!existsSync(p)) return { ...DEFAULTS }
    const raw = JSON.parse(readFileSync(p, "utf-8")) as Record<string, unknown>
    return {
      defaultAction: raw.defaultAction === "merge" || raw.defaultAction === "pr" || raw.defaultAction === "auto" ? raw.defaultAction : DEFAULTS.defaultAction,
      archiveOnMerge: typeof raw.archiveOnMerge === "boolean" ? raw.archiveOnMerge : DEFAULTS.archiveOnMerge,
      prRequiresGreen: typeof raw.prRequiresGreen === "boolean" ? raw.prRequiresGreen : DEFAULTS.prRequiresGreen,
    }
  } catch { return { ...DEFAULTS } }
}
