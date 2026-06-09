import { resolve } from "path"
import { readFileSync } from "fs"

// prompts/ lives at the repo root. This file is at src/core/agents/, so the
// root is three levels up.
export const ENVIRONMENT_MD_PATH = resolve(
  import.meta.dirname,
  "..",
  "..",
  "..",
  "prompts",
  "environment.md",
)

// NOTE: the Claude-only skills preamble (prompts/claude-skills.md) was retired
// in Phase 4 — skills now reach Claude via the supermux plugin host
// (`--plugin-dir`), so the hand-managed ~/.claude/skills workflow it documented
// no longer applies.

export function readEnvironmentMd(): string {
  return readFileSync(ENVIRONMENT_MD_PATH, "utf8")
}
