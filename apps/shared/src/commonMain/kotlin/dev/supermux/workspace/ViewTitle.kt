package dev.supermux.workspace

import dev.supermux.browser.BrowserViewState
import dev.supermux.browser.DisplayAddress
import dev.supermux.browser.displayAddress
import dev.supermux.fs.pathLeaf
import dev.supermux.proto.ViewDto
import dev.supermux.proto.chatSessionId
import dev.supermux.proto.stateString

/**
 * Tab / accessibility label for a view. Prefers the broker title, then kind-specific fallbacks. A
 * chat tab is named after its session ([sessionName] looks it up by id), and a pending "+ → Chat"
 * tab, which has no session yet, is "New Chat".
 */
fun viewTitle(view: ViewDto, sessionName: (String) -> String? = { null }): String {
    view.title?.takeIf { it.isNotBlank() }?.let { return it }
    return when (view.kind) {
        "chat" -> when (val sid = view.chatSessionId()) {
            null -> "New Chat"
            else -> sessionName(sid)?.takeIf { it.isNotBlank() } ?: "Chat"
        }
        "terminal" -> view.stateString("terminalId") ?: "Terminal"
        // The three editor panes name themselves differently: a file tab is its FILENAME, which is
        // the whole point of the tab row being one row (spec §7.2).
        "editor" -> when (view.stateString("mode")) {
            "file" -> view.stateString("path")?.let(::pathLeaf)?.ifBlank { null } ?: "File"
            "diff" -> "Changes"
            // "Files", not "Explorer": the tab says what it holds, the way Changes does.
            else -> "Files"
        }
        "browser" -> browserTabTitle(view)
        "display" -> "Display"
        else -> view.kind
    }
}

/** A Browser tab before its page has a title: the site, the port on the host, or a new tab. */
private fun browserTabTitle(view: ViewDto): String = when (val state = BrowserViewState.from(view.state)) {
    is BrowserViewState.Page -> when (val shown = displayAddress(state.url, emptyList())) {
        is DisplayAddress.Local -> "localhost:${shown.port}"
        is DisplayAddress.Preview -> "port ${shown.port}"
        is DisplayAddress.Web -> shown.host
    }
    // An agent page (#104) names itself through the view's title.
    is BrowserViewState.Doc -> "Page"
    BrowserViewState.Empty -> "New tab"
}
