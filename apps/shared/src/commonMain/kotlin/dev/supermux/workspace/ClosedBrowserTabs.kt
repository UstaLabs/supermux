package dev.supermux.workspace

/** A Browser tab that left its workspace (#122): the page it showed last and the pane it was in. */
data class ClosedBrowserTab(val url: String, val groupId: String?)

/**
 * The workspace's closed Browser tabs, newest first, so ⌘⇧T / Ctrl+Shift+T can bring them back. Fed the Browser tabs
 * the workspace has now (id → page, null for a tab with none) on every change; a tab missing since the last update
 * has left, however it went (its × or another device). Keeps the last [keep].
 */
class ClosedBrowserTabs(private val keep: Int = 10) {
    private var last: Map<String, Pair<String?, String?>> = emptyMap()
    private val closed = ArrayDeque<ClosedBrowserTab>()

    val isEmpty: Boolean get() = closed.isEmpty()

    fun update(now: Map<String, String?>, groupOf: (viewId: String) -> String?) {
        for ((id, was) in last) {
            val url = was.first ?: continue
            if (id !in now) {
                closed.addFirst(ClosedBrowserTab(url, was.second))
                while (closed.size > keep) closed.removeLast()
            }
        }
        last = now.mapValues { (id, url) -> url to (groupOf(id) ?: last[id]?.second) }
    }

    /** The newest closed tab, taken off the list; null when none is left. */
    fun pop(): ClosedBrowserTab? = closed.removeFirstOrNull()
}
