package dev.supermux.android.chat

import dev.supermux.proto.SlashCommand

// Slash-command matching, shared by the chat composer (ChatPanel) and the New Session launcher
// (SessionLauncherScreen). Kept as pure functions (no Compose) so the contract is unit-tested
// framework-free and both composers stay in lock-step — the logic used to live inline in ChatPanel,
// which is why the launcher shipped without it (iOS ComposerModel.slashMatches parity).

/** Active "/token" at the end of the draft: start-of-line or after whitespace, so mid-word slashes
 *  (e.g. a "http://" URL) never trigger the menu. Group 1 is the token including its leading '/'. */
private val slashTokenRegex = Regex("""(?:^|\s)(/\S*)$""")

/** The active slash query — the end-of-draft token minus its leading '/', lowercased — or null if
 *  the caret isn't on a slash token. An empty string (bare "/") means "match everything". */
fun activeSlashQuery(text: String): String? =
    slashTokenRegex.find(text)?.groupValues?.get(1)?.drop(1)?.lowercase()

/** Commands whose name OR family contains the active slash query, capped at 8 (iOS parity). Empty
 *  when there's no active token. A bare "/" (empty query) returns the first 8 commands. */
fun slashCommandMatches(text: String, commands: List<SlashCommand>): List<SlashCommand> {
    val q = activeSlashQuery(text) ?: return emptyList()
    return commands.filter {
        q.isEmpty() || it.name.contains(q, ignoreCase = true) || it.family.contains(q, ignoreCase = true)
    }.take(8)
}

/** Replace the active "/token" in [text] with [insert], preserving any leading whitespace so
 *  "do it /re" → "do it <insert>" (not "do it<insert>"). No token → the whole draft becomes [insert]. */
fun replaceSlashToken(text: String, insert: String): String {
    val m = slashTokenRegex.find(text) ?: return insert
    val lead = m.value.takeWhile { it == ' ' || it == '\n' || it == '\t' }
    return text.substring(0, m.range.first) + lead + insert
}

/** Text an insert-only command drops into the draft: its explicit insertText, else "/name ". */
fun slashInsertText(cmd: SlashCommand): String =
    cmd.insertText?.ifEmpty { null } ?: "${cmd.sigil}${cmd.name} "
