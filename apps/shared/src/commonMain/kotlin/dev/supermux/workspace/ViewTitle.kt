package dev.supermux.workspace

import dev.supermux.proto.ViewDto
import dev.supermux.proto.stateString

/** Tab / accessibility label for a view. Prefers the broker title, then kind-specific fallbacks. */
fun viewTitle(view: ViewDto): String {
    view.title?.takeIf { it.isNotBlank() }?.let { return it }
    return when (view.kind) {
        "chat" -> "Chat"
        "terminal" -> view.stateString("terminalId") ?: "Terminal"
        // The three editor panes name themselves differently: a file tab is its FILENAME, which is
        // the whole point of the tab row being one row (spec §7.2).
        "editor" -> when (view.stateString("mode")) {
            "file" -> view.stateString("path")?.substringAfterLast('/')?.ifBlank { null } ?: "File"
            "diff" -> "Changes"
            // "Files", not "Explorer": the tab says what it holds, the way Changes does.
            else -> "Files"
        }
        "display" -> "Display"
        else -> view.kind
    }
}
