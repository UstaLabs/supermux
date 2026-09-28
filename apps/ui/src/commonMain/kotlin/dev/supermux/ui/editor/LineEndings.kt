package dev.supermux.ui.editor

/**
 * Line endings (spec §8): the editor's rope knows only `\n`, so the host normalizes a file on load
 * and remembers its ending to restore it on save. A file is CRLF when most of its line breaks are
 * (a mixed file is saved with its majority ending: every line, not only the edited ones). A lone
 * `\r` (classic Mac) is not a line break here and is left in the text.
 */
object LineEndings {
    /** A file's text as the editor holds it, and whether it is saved back with `\r\n`. */
    data class Loaded(val text: String, val crlf: Boolean)

    fun load(raw: String): Loaded {
        if (raw.indexOf('\r') < 0) return Loaded(raw, false)
        var crlf = 0
        var lf = 0
        for (i in raw.indices) {
            if (raw[i] == '\n') { if (i > 0 && raw[i - 1] == '\r') crlf++ else lf++ }
        }
        return Loaded(if (crlf == 0) raw else raw.replace("\r\n", "\n"), crlf > lf)
    }

    fun save(text: String, crlf: Boolean): String = if (crlf && text.indexOf('\n') >= 0) text.replace("\n", "\r\n") else text
}
