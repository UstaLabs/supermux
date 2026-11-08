package dev.supermux.browser

import kotlinx.serialization.json.JsonObject

/** One write of a Browser tab to its view: the page as the view's state, and the page's title. */
data class BrowserPatch(val state: JsonObject, val title: String?)

/**
 * What a Browser tab writes to its view, and what it makes of the view's state when that changes (#105).
 *
 * The tab writes each page it commits, and every client follows the page stored. The broker echoes a tab's own
 * writes back, sometimes after the user has gone on to another page, so a page this tab wrote lately is its own echo,
 * never a page to go back to. (Another client storing one of those pages isn't followed either: rare, and better than
 * a tab that jumps back.)
 */
class BrowserTabSync(private val remembered: Int = 8) {
    private val written = ArrayDeque<String>()
    private var last: BrowserPatch? = null

    /** The tab committed [url], with [title] once the page has one: the write to send, or null when there's nothing new. */
    fun committed(url: String?, title: String?): BrowserPatch? {
        val page = url?.let(BrowserViewState::page) ?: return null
        val patch = BrowserPatch(page.toJson(), title?.trim()?.take(MAX_TITLE)?.ifEmpty { null })
        if (patch == last) return null
        last = patch
        written.remove(page.url)
        written.addLast(page.url)
        while (written.size > remembered) written.removeFirst()
        return patch
    }

    /** The view's [stored] state: the page this tab should load, or null when it shows it already or wrote it itself. */
    fun toFollow(stored: BrowserViewState, showing: String?): String? {
        val url = (stored as? BrowserViewState.Page)?.url ?: return null
        return url.takeIf { it != showing && it !in written }
    }

    private companion object {
        const val MAX_TITLE = 200
    }
}
