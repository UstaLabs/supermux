// Client-side helpers for the New Session launcher's thinking-level picker.
// The available levels themselves come from the broker
// (GET /reasoning-levels?agent=&model=) so there's one source of truth across
// agents (Claude static, Codex per-model, Cursor/OpenCode none). These helpers
// only decide what to *show* and which level a new session defaults to.

export interface ReasoningLevelOption {
  id: string
  description?: string
}

/** Show the control only when there's a real choice to make (>1 level). */
export function showReasoningPicker(levels: ReasoningLevelOption[]): boolean {
  return levels.length > 1
}

/**
 * The effective level for a new session given a possibly-stale/absent stored
 * choice: keep the stored value when it's still offered, otherwise pick the
 * default — High when available (a deliberately lighter default than the
 * broker's own "highest"), else the highest level on offer. `undefined` when
 * there are no levels to pick.
 *
 * `levels` is expected low→high ordered (as the broker returns them).
 */
export function resolveReasoningLevel(
  levels: ReasoningLevelOption[],
  stored: string | undefined,
): string | undefined {
  if (levels.length === 0) return undefined
  const ids = levels.map((l) => l.id)
  if (stored && ids.includes(stored)) return stored
  if (ids.includes("high")) return "high"
  return ids[ids.length - 1]
}
