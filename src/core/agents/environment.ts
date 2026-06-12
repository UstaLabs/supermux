import { environmentMdContent } from "../runtime-assets"

// NOTE: the Claude-only skills preamble (prompts/claude-skills.md) was retired
// in Phase 4 — skills now reach Claude via the supermux plugin host
// (`--plugin-dir`), so the hand-managed ~/.claude/skills workflow it documented
// no longer applies.

// Single-importer rule: prompts/environment.md is owned by runtime-assets.ts
// (imported there `with { type: "file" }`). We must NOT re-import it here
// `with { type: "text" }` — bun dedupes by specifier and ignores the attribute,
// so the two bindings would collapse to whichever resolved first, silently
// breaking the other. Content readers go through environmentMdContent().
export function readEnvironmentMd(): string {
  return environmentMdContent()
}
