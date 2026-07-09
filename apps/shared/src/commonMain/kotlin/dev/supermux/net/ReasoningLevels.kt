package dev.supermux.net

// Client-side helpers for the New Session launcher's thinking-level picker. The
// available levels come from the broker (GET /reasoning-levels?agent=&model=,
// BrokerApi.getReasoningLevels) so there's one source of truth across agents
// (Claude static, Codex per-model, Cursor/OpenCode none). These only decide what
// to show and which level a new session defaults to. Mirrors the web helpers in
// src/web-app/src/lib/reasoning-levels.ts so all clients behave identically.

/** Show the control only when there's a real choice to make (>1 level). */
fun showReasoningPicker(levels: List<ReasoningLevel>): Boolean = levels.size > 1

/**
 * The effective level for a new session given a possibly-stale/absent stored
 * choice: keep the stored value when it's still offered, otherwise pick the
 * default — High when available (a deliberately lighter default than the
 * broker's own "highest"), else the highest level on offer. null when there are
 * no levels to pick. [levels] is expected low→high ordered (as the broker
 * returns them).
 */
fun resolveReasoningLevel(levels: List<ReasoningLevel>, stored: String?): String? {
    if (levels.isEmpty()) return null
    val ids = levels.map { it.id }
    if (stored != null && ids.contains(stored)) return stored
    if (ids.contains("high")) return "high"
    return ids.last()
}
