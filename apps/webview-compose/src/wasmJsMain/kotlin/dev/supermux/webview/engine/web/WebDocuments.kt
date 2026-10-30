package dev.supermux.webview.engine.web

/** The documents the web engine writes into iframes. */
internal object WebDocuments {
    /**
     * [html] with [script] run before any of the page's own scripts. It goes right after a leading doctype, because
     * anything before a doctype would put the page in quirks mode, or at the very start when there is none. A `</` in
     * the script is written `<\/`, so the script can't end its own tag.
     */
    fun withScript(html: String, script: String): String {
        val tag = "<script>" + script.replace("</", "<\\/") + "</script>"
        val end = leadingDoctypeEnd(html)
        if (end < 0) return tag + html
        return html.substring(0, end) + tag + html.substring(end)
    }

    /**
     * Where a leading doctype ends in [html], after an optional BOM, whitespace and comments, or -1 when there is none.
     * One pass, as the browser's tokenizer reads it: a page is any agent's text, and the scan runs on the main thread of
     * every client that shows it. (A backtracking pattern took a second over 20 leading comments, four times that for
     * each 2 more.)
     */
    private fun leadingDoctypeEnd(html: String): Int {
        var i = if (html.startsWith('﻿')) 1 else 0
        while (true) {
            while (i < html.length && isHtmlSpace(html[i])) i++
            if (!html.startsWith("<!--", i)) break
            i = commentEnd(html, i + 4)
            if (i < 0) return -1
        }
        if (!html.regionMatches(i, "<!doctype", 0, 9, ignoreCase = true)) return -1
        val close = html.indexOf('>', i + 9)
        return if (close < 0) -1 else close + 1
    }

    /**
     * Where the comment whose text starts at [start] ends, as the HTML tokenizer ends it: at once for `<!-->` and
     * `<!--->`, else after the first `-->` or `--!>`. -1 when it never ends, which makes the rest of the page a comment.
     */
    private fun commentEnd(html: String, start: Int): Int {
        if (html.startsWith(">", start)) return start + 1
        if (html.startsWith("->", start)) return start + 2
        var j = start
        while (j + 2 < html.length) {
            if (html[j] == '-' && html[j + 1] == '-') {
                if (html[j + 2] == '>') return j + 3
                if (html[j + 2] == '!' && j + 3 < html.length && html[j + 3] == '>') return j + 4
            }
            j++
        }
        return -1
    }

    /** The HTML tokenizer's whitespace, which it skips before a doctype. */
    private fun isHtmlSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\u000C' || c == '\r'

    /** What a page that can't be shown shows instead: [message], in the theme, with no script. */
    fun errorPage(message: String, dark: Boolean): String {
        val scheme = if (dark) "dark" else "light"
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"color-scheme\" content=\"$scheme\">" +
            "<style>html,body{margin:0;height:100%;background:Canvas;color:GrayText}" +
            "body{display:flex;align-items:center;justify-content:center;box-sizing:border-box;padding:12px;" +
            "font:13px/1.4 system-ui,sans-serif;text-align:center}</style></head><body>" + escape(message) + "</body></html>"
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
