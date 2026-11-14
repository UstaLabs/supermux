// Agents (formerly personal assistants): the client-side name check. The broker validates too; this
// only saves a round trip. The UI words the reason (AgentDialogs.kt agentNameErrorText).
package dev.supermux.state

// Exactly what JS `String.prototype.trim` strips: WhiteSpace + LineTerminator. Kotlin's trim()
// differs (it also strips U+001C–U+001F, which the broker keeps and then refuses).
private fun Char.isJsTrimmed() =
    this == '\t' || this == '\n' || this == '\u000B' || this == '\u000C' || this == '\r' ||
        this == '\uFEFF' || this == '\u2028' || this == '\u2029' || category == CharCategory.SPACE_SEPARATOR

/** [s] trimmed the way the broker trims a name (JS `trim()`). */
fun agentNameTrim(s: String): String = s.trim { it.isJsTrimmed() }

/** Why a name is refused; the UI words it in the app's language. */
enum class AgentNameError { CONTROL_CHARACTER, EMPTY, TOO_LONG, LEADING_DOT, SLASH }

/** Null when [raw] is a usable Agent name; otherwise why not. Mirrors the broker's check. */
fun agentNameError(raw: String): AgentNameError? {
    // Trim first, as the broker does, then refuse any control character left inside.
    val name = agentNameTrim(raw)
    if (name.any { it.code < 0x20 || it.code == 0x7F }) return AgentNameError.CONTROL_CHARACTER
    return when {
        name.isEmpty() -> AgentNameError.EMPTY
        name.length > 80 -> AgentNameError.TOO_LONG
        name.startsWith(".") -> AgentNameError.LEADING_DOT
        name.contains('/') || name.contains('\\') -> AgentNameError.SLASH
        else -> null
    }
}
