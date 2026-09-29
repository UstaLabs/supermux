package dev.supermux.ui.terminal

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import dev.supermux.proto.ViewDto
import dev.supermux.workspace.viewTitle

/**
 * The window title each live terminal VIEW's program last set (OSC 0/2), by view id.
 *
 * A workspace terminal is drawn by [dev.supermux.ui.shell.ViewHost] but titled by the pane's tab
 * strip, two places that share nothing but the view id — and the title is live, per-device state
 * the broker never stores. So the pane that draws the terminal writes here through
 * [LocalTerminalTitleSink], and the tab strip reads it in [liveViewTitle]. Snapshot state: a new
 * title recomposes the tab, nothing else.
 */
object TerminalTitles {
    private val titles = mutableStateMapOf<String, String>()

    operator fun get(viewId: String): String? = titles[viewId]

    fun set(viewId: String, title: String) {
        if (title.isBlank()) titles.remove(viewId) else titles[viewId] = title
    }

    fun forget(viewId: String) {
        titles.remove(viewId)
    }
}

/** Where a terminal reports its program's title; the default drops it (no tab to put it on). */
val LocalTerminalTitleSink = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * A tab's label: a title the user gave the view wins, then the title its terminal's program set,
 * then the usual per-kind fallback ([viewTitle]).
 */
fun liveViewTitle(view: ViewDto, sessionName: (String) -> String? = { null }): String {
    view.title?.takeIf { it.isNotBlank() }?.let { return it }
    if (view.kind == "terminal") TerminalTitles[view.id]?.let { return it }
    return viewTitle(view, sessionName)
}
