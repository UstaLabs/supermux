// `@` file mentions in the chat composer: which word the caret is in, what the menu asks the host
// for, and what a pick writes into the draft. The agent gets plain text: `@src/a.ts`, a folder as
// `@src/`, a path with whitespace as `@"my notes/a.md"`. Claude Code reads such a mention into its
// context by itself; the other agents read the path with their own file tools.
package dev.supermux.ui.chat

import dev.supermux.fs.FileSystemService
import dev.supermux.net.FsEntry
import dev.supermux.ui.files.GoToEntry
import dev.supermux.ui.files.absoluteInWorkdir
import dev.supermux.ui.files.toGoToEntry

/** The `@` word the caret is in: [start] is the `@`, [end] is where the word ends (at or after the caret). */
data class MentionToken(val start: Int, val end: Int, val query: String, val quoted: Boolean)

/** What the `@` menu asks the host for. */
sealed interface MentionLookup {
    /** One folder of the workdir, workdir-relative ("" is the workdir itself). */
    data class Folder(val path: String) : MentionLookup

    /** A fuzzy search over the whole workdir, the one "Go to file…" runs. */
    data class Search(val query: String) : MentionLookup
}

/** A pick's result: the new draft and where the caret goes. */
data class MentionEdit(val text: String, val caret: Int)

/** Rows one search asks for; the menu scrolls. */
const val MENTION_SEARCH_LIMIT = 50

/** `@` at the start of a line or after whitespace, so an email's `@` never opens the menu. An open
 *  quote may hold spaces (`@"my no`), a closed one ends the word. Group 1 is what follows the `@`. */
private val mentionTokenRegex = Regex("""(?:^|\s)@("[^"\n]*|[^\s"]*)$""")

/** The `@` word the caret is in, or null. Only what is left of the caret is the query. */
fun activeMentionToken(text: String, caret: Int): MentionToken? {
    if (caret < 0 || caret > text.length) return null
    val body = mentionTokenRegex.find(text.substring(0, caret))?.groupValues?.get(1) ?: return null
    var end = caret
    while (end < text.length && !text[end].isWhitespace()) end++
    val quoted = body.startsWith('"')
    return MentionToken(start = caret - body.length - 1, end = end, query = if (quoted) body.drop(1) else body, quoted = quoted)
}

/**
 * A bare `@` lists the workdir, a trailing `/` lists that folder, anything else is a fuzzy search.
 * Null for a path that leaves the workdir (absolute, or through `..`): the menu only offers the project.
 */
fun mentionLookup(query: String): MentionLookup? {
    val q = query.removePrefix("./")
    if (q.startsWith('/') || q.split('/').any { it == ".." }) return null
    return when {
        q.isEmpty() -> MentionLookup.Folder("")
        q.endsWith('/') -> MentionLookup.Folder(q.trimEnd('/'))
        else -> MentionLookup.Search(q)
    }
}

/**
 * The draft text for [relativePath]: `@path`, a folder ending in `/`, quoted when the path holds
 * whitespace. [open] leaves the closing quote off, so the word stays open on that folder.
 */
fun mentionText(relativePath: String, isDir: Boolean, open: Boolean = false): String {
    val path = if (isDir) relativePath.trimEnd('/') + "/" else relativePath
    if (path.none { it.isWhitespace() }) return "@$path"
    return if (open) "@\"$path" else "@\"$path\""
}

/**
 * [token] in [text] replaced by the mention of [relativePath]. A file closes the word with a space
 * (the one already after it, else a new one), which closes the menu. A folder writes `@dir/` and
 * leaves the word open, so the menu lists that folder next and a file in it can be picked.
 */
fun applyMention(
    text: String,
    token: MentionToken,
    relativePath: String,
    isDir: Boolean,
    intoFolder: Boolean = false,
): MentionEdit {
    val open = isDir
    val mention = mentionText(relativePath, isDir, open)
    val before = text.substring(0, token.start)
    val after = text.substring(token.end)
    if (open) return MentionEdit(before + mention + after, before.length + mention.length)
    val space = if (after.firstOrNull()?.isWhitespace() == true) "" else " "
    return MentionEdit(before + mention + space + after, before.length + mention.length + 1)
}

/**
 * One folder's listing as menu rows: folders first, then files, by name. Ignored entries and `.git`
 * are left out, as the search leaves them out.
 */
fun mentionRows(workdir: String, folder: String, entries: List<FsEntry>): List<GoToEntry> =
    entries.asSequence()
        .filter { !it.ignored && it.name != ".git" }
        .map { e ->
            val rel = if (folder.isEmpty()) e.name else "$folder/${e.name}"
            GoToEntry(
                absolutePath = absoluteInWorkdir(workdir, rel),
                relativePath = rel,
                name = e.name,
                folder = folder,
                isDir = e.type == "dir" || e.target == "dir",
            )
        }
        .sortedWith(compareBy<GoToEntry>({ !it.isDir }, { it.name.lowercase() }))
        .toList()

/** Rows for [lookup] in [workdir] from the host's file system; null when the host could not answer. */
suspend fun lookupMentionFiles(fs: FileSystemService, workdir: String, lookup: MentionLookup): List<GoToEntry>? =
    when (lookup) {
        is MentionLookup.Folder ->
            fs.list(absoluteInWorkdir(workdir, lookup.path)).getOrNull()?.let { mentionRows(workdir, lookup.path, it.entries) }
        is MentionLookup.Search ->
            fs.search(workdir, lookup.query, MENTION_SEARCH_LIMIT).getOrNull()?.mapNotNull { toGoToEntry(workdir, it) }
    }
