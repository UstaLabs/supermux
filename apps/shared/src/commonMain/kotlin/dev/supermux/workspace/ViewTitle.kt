package dev.supermux.workspace

import dev.supermux.browser.BrowserViewState
import dev.supermux.browser.DisplayAddress
import dev.supermux.browser.displayAddress
import dev.supermux.fs.pathLeaf
import dev.supermux.proto.ViewDto
import dev.supermux.proto.chatSessionId
import dev.supermux.proto.stateString

/**
 * What a view's tab is called: a [Name] the view or its content gives, the [Port] a Browser tab shows
 * on the host, or a [Word] the UI says in the app's language (SharedWords.kt viewTitle).
 */
sealed interface ViewTitle {
    data class Name(val text: String) : ViewTitle
    data class Port(val port: Int) : ViewTitle
    enum class Word : ViewTitle { NEW_CHAT, CHAT, TERMINAL, FILE, CHANGES, FILES, DISPLAY, PAGE, NEW_TAB }
}

/**
 * Tab / accessibility label for a view. Prefers the broker title, then kind-specific fallbacks. A
 * chat tab is named after its session ([sessionName] looks it up by id), and a pending "+ → Chat"
 * tab, which has no session yet, is [ViewTitle.Word.NEW_CHAT].
 */
fun viewTitleOf(view: ViewDto, sessionName: (String) -> String? = { null }): ViewTitle {
    view.title?.takeIf { it.isNotBlank() }?.let { return ViewTitle.Name(it) }
    return when (view.kind) {
        "chat" -> when (val sid = view.chatSessionId()) {
            null -> ViewTitle.Word.NEW_CHAT
            else -> sessionName(sid)?.takeIf { it.isNotBlank() }?.let { ViewTitle.Name(it) } ?: ViewTitle.Word.CHAT
        }
        "terminal" -> view.stateString("terminalId")?.let { ViewTitle.Name(it) } ?: ViewTitle.Word.TERMINAL
        // The three editor panes name themselves differently: a file tab is its FILENAME, which is
        // the whole point of the tab row being one row (spec §7.2).
        "editor" -> when (view.stateString("mode")) {
            "file" -> view.stateString("path")?.let(::pathLeaf)?.ifBlank { null }?.let { ViewTitle.Name(it) } ?: ViewTitle.Word.FILE
            "diff" -> ViewTitle.Word.CHANGES
            // "Files", not "Explorer": the tab says what it holds, the way Changes does.
            else -> ViewTitle.Word.FILES
        }
        "browser" -> browserTabTitle(view)
        "display" -> ViewTitle.Word.DISPLAY
        else -> ViewTitle.Name(view.kind)
    }
}

/** A Browser tab before its page has a title: the site, the port on the host, or a new tab. */
private fun browserTabTitle(view: ViewDto): ViewTitle = when (val state = BrowserViewState.from(view.state)) {
    is BrowserViewState.Page -> when (val shown = displayAddress(state.url, emptyList())) {
        is DisplayAddress.Local -> ViewTitle.Name("localhost:${shown.port}")
        is DisplayAddress.Preview -> ViewTitle.Port(shown.port)
        is DisplayAddress.Web -> ViewTitle.Name(shown.host)
    }
    // An agent page (#104) names itself through the view's title.
    is BrowserViewState.Doc -> ViewTitle.Word.PAGE
    BrowserViewState.Empty -> ViewTitle.Word.NEW_TAB
}
