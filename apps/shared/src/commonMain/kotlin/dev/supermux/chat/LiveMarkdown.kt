package dev.supermux.chat

/**
 * Card #76: Markdown that is still being written, made safe to draw as it is.
 * - An unfinished table row (the last line, starting with `|`) waits for its newline, so a table does not
 *   flicker between text and table.
 * - In the last paragraph, an open `` ` `` or `**` is closed, so code and emphasis show as such instead of
 *   their markers; a bare opener at the very end is held back.
 * - An open code fence needs nothing: CommonMark runs it to the end of the text.
 */
fun liveMarkdown(text: String): String {
    var t = text
    val lastBreak = t.lastIndexOf('\n')
    if (t.substring(lastBreak + 1).trimStart().startsWith("|")) t = t.substring(0, lastBreak + 1)
    if (insideFence(t)) return t
    val para = t.substring(t.lastIndexOf("\n\n").let { if (it < 0) 0 else it + 2 })
    if (para.count { it == '`' } % 2 == 1) {
        return if (t.endsWith("`")) t.dropLast(1) else "$t`"
    }
    val outsideCode = para.replace(Regex("`[^`]*`"), "")
    if (Regex("""\*\*""").findAll(outsideCode).count() % 2 == 1) {
        return if (t.endsWith("**")) t.dropLast(2) else "$t**"
    }
    return t
}

private fun insideFence(t: String): Boolean =
    t.lineSequence().count { line -> line.trimStart().let { it.startsWith("```") || it.startsWith("~~~") } } % 2 == 1
