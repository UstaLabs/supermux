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
 * Canonical low → high rank for effort/reasoning ids (matches
 * `src/core/models/reasoning-levels.ts` CLAUDE_EFFORT_ORDER + GENERIC_ORDER).
 * Broker lists *should* already be low→high, but some agents / models reverse or
 * scramble them — clients must re-sort before display and speedometer mapping.
 */
private val EFFORT_RANK_LOW_TO_HIGH: List<String> = listOf(
    "minimal",
    "low",
    "medium",
    "high",
    "xhigh",
    "max",
    "extra_high",
)

/** Rank for sort: known ids get 0..n; unknown ids sort after known ones, by id. */
fun effortRank(id: String): Int {
    val idx = EFFORT_RANK_LOW_TO_HIGH.indexOf(id)
    return if (idx >= 0) idx else 1_000
}

/**
 * Sort levels **low → high** using [effortRank], independent of broker array order.
 * Stable for unknown ids (secondary sort by id).
 */
fun sortEffortLevelsLowToHigh(levels: List<ReasoningLevel>): List<ReasoningLevel> =
    levels.sortedWith(compareBy({ effortRank(it.id) }, { it.id }))

/**
 * Map a session's effort catalog + current id into Speedometer inputs.
 *
 * @return [Pair] of `(levels, value)` where `levels` is the step count and
 *   `value` is **1-based** (1 = lowest effort, `levels` = highest). Unknown /
 *   missing current → middle step. Empty catalog → `(1, 1)`.
 */
fun effortSpeedometerParams(current: String?, levels: List<ReasoningLevel>): Pair<Int, Int> {
    val ordered = sortEffortLevelsLowToHigh(levels)
    val n = ordered.size
    if (n == 0) return 1 to 1
    val idx = current?.takeIf { it.isNotBlank() }?.let { id ->
        ordered.indexOfFirst { it.id == id }
    }?.takeIf { it >= 0 }
    val value1Based = if (idx != null) idx + 1 else (n + 1) / 2
    return n to value1Based
}

/**
 * The effective level for a new session given a possibly-stale/absent stored
 * choice: keep the stored value when it's still offered, otherwise pick the
 * default — High when available (a deliberately lighter default than the
 * broker's own "highest"), else the highest level on offer. null when there are
 * no levels to pick. Uses [sortEffortLevelsLowToHigh] so reverse broker order
 * still defaults correctly.
 */
fun resolveReasoningLevel(levels: List<ReasoningLevel>, stored: String?): String? {
    if (levels.isEmpty()) return null
    val ordered = sortEffortLevelsLowToHigh(levels)
    val ids = ordered.map { it.id }
    if (stored != null && ids.contains(stored)) return stored
    if (ids.contains("high")) return "high"
    return ids.last() // highest after low→high sort
}
