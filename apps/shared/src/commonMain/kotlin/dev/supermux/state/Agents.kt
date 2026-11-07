// Agents (formerly personal assistants): the client-side name check and the text a screen shows
// for a refused list/create/kill. The broker validates too; this only saves a round trip.
package dev.supermux.state

// Exactly what JS `String.prototype.trim` strips: WhiteSpace + LineTerminator. Kotlin's trim()
// differs (it also strips U+001C–U+001F, which the broker keeps and then refuses).
private fun Char.isJsTrimmed() =
    this == '\t' || this == '\n' || this == '\u000B' || this == '\u000C' || this == '\r' ||
        this == '\uFEFF' || this == '\u2028' || this == '\u2029' || category == CharCategory.SPACE_SEPARATOR

/** [s] trimmed the way the broker trims a name (JS `trim()`). */
fun agentNameTrim(s: String): String = s.trim { it.isJsTrimmed() }

/** Null when [raw] is a usable Agent name; otherwise why not. Mirrors the broker's check. */
fun agentNameError(raw: String): String? {
    // Trim first, as the broker does, then refuse any control character left inside.
    val name = agentNameTrim(raw)
    if (name.any { it.code < 0x20 || it.code == 0x7F }) return "A name can't contain control characters"
    return when {
        name.isEmpty() -> "Enter a name"
        name.length > 80 -> "A name can be at most 80 characters"
        name.startsWith(".") -> "A name can't start with a dot"
        name.contains('/') || name.contains('\\') -> "A name can't contain / or \\"
        else -> null
    }
}

/**
 * What to show for a refused Agents call. The broker's own message first, then copy for the
 * codes it is known to send, then a generic line naming [action] ("create the agent").
 */
fun agentFailureText(failure: AccountResult.Failed, action: String): String =
    failure.message?.takeIf { it.isNotBlank() }
        ?: when (failure.code) {
            "name_invalid" -> "That name can't be used."
            "name_taken" -> "An agent with that name already exists."
            "agent_not_installed" -> "That provider isn't installed. Install it in Settings ▸ Providers."
            else -> null
        }
        ?: "Couldn't $action — check the connection and try again."
