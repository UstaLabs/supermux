// How the run-settings panel labels a model. Pure, so the rule is pinned without a composition.
package dev.supermux.ui.chat

import dev.supermux.net.ModelInfo

/**
 * The agent's own name, dropped where every model repeats it — the logo already says "Claude", so
 * "Claude Opus 5.5" reads as "Opus 5.5". Kept when only some names carry the word.
 */
internal fun shortModelName(displayName: String, all: List<ModelInfo>): String {
    val first = displayName.substringBefore(' ', missingDelimiterValue = "")
    if (first.isEmpty() || all.size < 2) return displayName
    val shared = all.all { it.displayName.startsWith("$first ") }
    return if (shared) displayName.removePrefix("$first ").ifBlank { displayName } else displayName
}
