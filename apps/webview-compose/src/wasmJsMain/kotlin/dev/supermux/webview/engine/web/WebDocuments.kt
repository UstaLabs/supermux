package dev.supermux.webview.engine.web

/** The documents the web engine writes into iframes. */
internal object WebDocuments {
    /** A leading doctype, after an optional BOM, whitespace and comments. */
    private val LEADING_DOCTYPE = Regex("^\\uFEFF?\\s*(?:<!--[\\s\\S]*?-->\\s*)*<!doctype[^>]*>", RegexOption.IGNORE_CASE)

    /**
     * [html] with [script] run before any of the page's own scripts. It goes right after a leading doctype, because
     * anything before a doctype would put the page in quirks mode, or at the very start when there is none. A `</` in
     * the script is written `<\/`, so the script can't end its own tag.
     */
    fun withScript(html: String, script: String): String {
        val tag = "<script>" + script.replace("</", "<\\/") + "</script>"
        val doctype = LEADING_DOCTYPE.find(html) ?: return tag + html
        val end = doctype.range.last + 1
        return html.substring(0, end) + tag + html.substring(end)
    }

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
