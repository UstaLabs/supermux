package dev.supermux.ui.chat

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

/**
 * Commands whose name OR family contains the active slash query, capped at 8 (iOS parity). Empty
 * when there's no active token. A bare "/" (empty query) returns the first 8 commands.
 *
 * [isActionHandled] drops CONTROL commands the host has not wired: offering `/rename` on a surface
 * that cannot rename would clear the typed token and then do nothing, which reads as the command
 * having silently failed. Insert-only commands (no `action`) are always offered — they only ever
 * put text in the draft.
 */
fun slashCommandMatches(
    text: String,
    commands: List<SlashCommand>,
    isActionHandled: (String) -> Boolean = { true },
): List<SlashCommand> {
    val q = activeSlashQuery(text) ?: return emptyList()
    return commands
        .filter { cmd -> cmd.action?.kind?.let(isActionHandled) ?: true }
        .filter {
            q.isEmpty() || it.name.contains(q, ignoreCase = true) || it.family.contains(q, ignoreCase = true)
        }
        .take(8)
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

// ── The `/` menu (card #56): sections on an empty "/", a ranked list while typing ─────────────

/** Where the active "/token" starts in [text], or null when the caret isn't on one (see [activeSlashQuery]). */
fun activeSlashTokenStart(text: String): Int? =
    slashTokenRegex.find(text)?.groups?.get(1)?.range?.first

/** One command row: the command and what in it matched the query. */
data class SlashRow(
    val command: SlashCommand,
    /** Indexes into [SlashCommand.name] that matched. */
    val nameHits: List<Int> = emptyList(),
    /** The alias the query matched, when it beat the name. */
    val alias: String? = null,
    /** Indexes into [alias] that matched. */
    val aliasHits: List<Int> = emptyList(),
    /** Set when only the description matched: the matched indexes in it. */
    val descriptionHits: List<Int>? = null,
)

/** A labelled run of rows. A null [title] draws no header (the ranked list's best matches). */
data class SlashSection(val key: String, val title: String?, val rows: List<SlashRow>, val showCount: Boolean = true)

data class SlashMenuModel(val query: String, val sections: List<SlashSection>) {
    /** Every row in display order; the highlight is an index into this. */
    val rows: List<SlashRow> = sections.flatMap { it.rows }
}

/** Recent holds at most this many commands. */
const val SLASH_RECENTS_MAX = 5

/** Description matches are a fallback, so they never crowd out the names. */
private const val DESCRIPTION_MATCHES_MAX = 5

/**
 * The `/` menu for [text], or null when there is no active "/token" or nothing to offer.
 *
 * An empty "/" lists everything in sections: Recent, supermux's own commands, then the agent's by
 * where they come from (this project, your skills, each plugin, built-in) and, last, the ones nothing
 * says anything about. A query ranks one list (see [matchSlashCommand]) and puts commands that only
 * matched in their description under their own label.
 *
 * [isActionHandled] drops CONTROL commands the host has not wired, as [slashCommandMatches] does.
 */
fun slashMenu(
    text: String,
    commands: List<SlashCommand>,
    recents: List<String>,
    agentLabel: String,
    isActionHandled: (String) -> Boolean = { true },
): SlashMenuModel? {
    val q = activeSlashQuery(text) ?: return null
    val offered = commands.filter { cmd -> cmd.action?.kind?.let(isActionHandled) ?: true }
    if (offered.isEmpty()) return null
    val model = if (q.isEmpty()) sectionedSlashMenu(offered, recents, agentLabel) else rankedSlashMenu(q, offered, recents)
    // Nothing matches → no menu: "/where is the apk" is a message, not a failed search.
    return model.takeIf { it.rows.isNotEmpty() }
}

private fun sectionedSlashMenu(commands: List<SlashCommand>, recents: List<String>, agentLabel: String): SlashMenuModel {
    val agent = commands.filter { it.action == null }
    fun group(key: String, title: String, pick: (SlashCommand) -> Boolean) =
        agent.filter(pick).sortedBy { shortName(it.name).lowercase() }.map { SlashRow(it) }
            .takeIf { it.isNotEmpty() }?.let { SlashSection(key, title, it) }
    val recent = recents.take(SLASH_RECENTS_MAX).mapNotNull { name -> agent.firstOrNull { it.name == name } }
    val sections = buildList {
        if (recent.isNotEmpty()) add(SlashSection("recent", "Recent", recent.map { SlashRow(it) }))
        commands.filter { it.action != null }.map { SlashRow(it) }.takeIf { it.isNotEmpty() }?.let { add(SlashSection("supermux", "supermux", it)) }
        group("project", "This project") { it.source == SlashCommand.SOURCE_PROJECT }?.let(::add)
        group("user", "Your skills") { it.source == SlashCommand.SOURCE_USER }?.let(::add)
        agent.filter { it.source == SlashCommand.SOURCE_PLUGIN }.mapNotNull { it.plugin }.distinct().sortedBy { it.lowercase() }.forEach { plugin ->
            group("plugin:$plugin", plugin) { it.source == SlashCommand.SOURCE_PLUGIN && it.plugin == plugin }?.let(::add)
        }
        group("builtin", "$agentLabel built-in") { it.source == SlashCommand.SOURCE_BUILTIN }?.let(::add)
        val known = setOf(SlashCommand.SOURCE_PROJECT, SlashCommand.SOURCE_USER, SlashCommand.SOURCE_BUILTIN)
        group("other", "Other") { it.source !in known && !(it.source == SlashCommand.SOURCE_PLUGIN && it.plugin != null) }?.let(::add)
    }
    return SlashMenuModel("", sections)
}

private fun rankedSlashMenu(q: String, commands: List<SlashCommand>, recents: List<String>): SlashMenuModel {
    val scored = commands.mapNotNull { cmd -> matchSlashCommand(cmd, q)?.let { (row, score) -> Triple(row, score + recentBoost(cmd, recents), cmd) } }
        .sortedWith(compareByDescending<Triple<SlashRow, Int, SlashCommand>> { it.second }.thenBy { it.third.name.length }.thenBy { it.third.name })
    val byName = scored.filter { it.first.descriptionHits == null }.map { it.first }
    val byDescription = scored.filter { it.first.descriptionHits != null }.take(DESCRIPTION_MATCHES_MAX).map { it.first }
    val sections = buildList {
        add(SlashSection("matches", null, byName))
        if (byDescription.isNotEmpty()) add(SlashSection("description", "In descriptions", byDescription, showCount = false))
    }
    return SlashMenuModel(q, sections)
}

private fun recentBoost(cmd: SlashCommand, recents: List<String>): Int {
    val at = recents.indexOf(cmd.name)
    return if (at < 0 || cmd.action != null) 0 else 30 - at * 4
}

/** "superpowers:brainstorming" → "brainstorming"; a name with no plugin prefix is its own short name. */
fun shortName(name: String): String = name.substringAfter(':')

/** The "plugin:" prefix of [name], with its colon, or "" (MCP prompts are "server:prompt" too). */
fun namePrefix(name: String): String = if (':' in name) name.substringBefore(':') + ":" else ""

/**
 * How well [cmd] answers [q] (lowercase, no sigil), or null. Its short name and full name count in
 * full, an alias a little less, and the description only when nothing else matched (and only where a
 * word starts, so "rev" never finds "previous"). Supermux's own command wins a tie with the agent's.
 */
fun matchSlashCommand(cmd: SlashCommand, q: String): Pair<SlashRow, Int>? {
    val prefix = namePrefix(cmd.name)
    var best: Pair<SlashRow, Int>? = null
    fun offer(row: SlashRow, score: Int) { if (best == null || score > best!!.second) best = row to score }
    matchField(cmd.name.substring(prefix.length), q)?.let { m -> offer(SlashRow(cmd, nameHits = m.hits.map { it + prefix.length }), m.score + 5) }
    if (prefix.isNotEmpty()) matchField(cmd.name, q)?.let { m -> offer(SlashRow(cmd, nameHits = m.hits), m.score) }
    for (alias in cmd.aliases) {
        if (alias == cmd.name || alias == shortName(cmd.name)) continue
        matchField(alias, q)?.let { m -> offer(SlashRow(cmd, alias = alias, aliasHits = m.hits), m.score - 40) }
    }
    if (best == null && q.length >= 3) {
        val desc = cmd.description.orEmpty().lowercase()
        var at = desc.indexOf(q)
        while (at > 0 && desc[at - 1].isLetterOrDigit()) at = desc.indexOf(q, at + 1)
        if (at >= 0) offer(SlashRow(cmd, descriptionHits = (at until at + q.length).toList()), 120)
    }
    return best?.let { (row, score) -> row to score + if (cmd.action != null) 1 else 0 }
}

internal data class FieldMatch(val score: Int, val hits: List<Int>)

private fun isBoundary(c: Char) = c == '-' || c == ':' || c == '_' || c == ' ' || c == '/' || c == '.'
private fun isWordStart(s: String, i: Int) = i == 0 || isBoundary(s[i - 1])

/**
 * [q] against one name: the whole name, its start, the start of a word in it, a run of word starts
 * ("sdd" → subagent-driven-development, "sysdeb" → systematic-debugging), then anywhere in it (3+
 * letters). Never letters scattered mid-word, which made "mod" match every `mux:ios-dev-…`.
 */
internal fun matchField(field: String, q: String): FieldMatch? {
    if (q.isEmpty()) return null
    val f = field.lowercase()
    if (f == q) return FieldMatch(1000, q.indices.toList())
    if (f.startsWith(q)) return FieldMatch(900 - (f.length - q.length), q.indices.toList())
    for (i in 1..(f.length - q.length)) {
        if (isWordStart(f, i) && f.startsWith(q, i)) return FieldMatch(800 - i, (i until i + q.length).toList())
    }
    if (q.length >= 2) wordRuns(f, q)?.let { (hits, runs) -> return FieldMatch(720 - runs * 6 - hits.first() / 2, hits) }
    if (q.length >= 3) {
        val at = f.indexOf(q)
        if (at >= 0) return FieldMatch(600 - at, (at until at + q.length).toList())
    }
    return null
}

/** [q] as consecutive runs, each starting at a word start of [f], in order: the hits and how many runs. */
private fun wordRuns(f: String, q: String): Pair<List<Int>, Int>? {
    val starts = f.indices.filter { isWordStart(f, it) && !isBoundary(f[it]) }
    fun go(qi: Int, si: Int): List<IntRange>? {
        if (qi == q.length) return emptyList()
        for (s in si until starts.size) {
            val p = starts[s]
            var n = 0
            while (qi + n < q.length && p + n < f.length && !isBoundary(f[p + n]) && f[p + n] == q[qi + n]) n++
            for (k in n downTo 1) {
                val rest = go(qi + k, s + 1) ?: continue
                return listOf(p until p + k) + rest
            }
        }
        return null
    }
    val runs = go(0, 0) ?: return null
    return runs.flatMap { it.toList() } to runs.size
}

/** [recents] with [name] moved to the front, capped at [SLASH_RECENTS_MAX]. */
fun withRecent(recents: List<String>, name: String): List<String> =
    (listOf(name) + recents.filter { it != name }).take(SLASH_RECENTS_MAX)

/**
 * The agent command a sent message starts with — its sigil and name, or one of its aliases — so a
 * command typed out in full counts for Recent too. Null for anything else.
 */
fun sentSlashCommand(text: String, commands: List<SlashCommand>): SlashCommand? {
    val token = text.trimStart().substringBefore(' ').substringBefore('\n')
    if (token.length < 2) return null
    return commands.firstOrNull { cmd ->
        cmd.action == null && (token == cmd.sigil + cmd.name || cmd.aliases.any { token == cmd.sigil + it })
    }
}

/** The argument hint shown dimmed after [text], the draft a pick just wrote, until it changes. */
data class SlashGhost(val text: String, val hint: String)

/** How far Page Up / Page Down move a menu's highlight. */
const val MENU_PAGE = 8
