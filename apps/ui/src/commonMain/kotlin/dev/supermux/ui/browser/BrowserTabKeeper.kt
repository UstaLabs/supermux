package dev.supermux.ui.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import dev.supermux.proto.ViewDto

/**
 * Desktop: every open Browser tab's page stays live while another tab is in front, as a browser's tabs do (#105, Ahmet's
 * call). Each tab is one movable composition: the pane shows it ([Show]); while its tab isn't in front,
 * [KeepBrowserTabsLive] keeps the same composition, at the size the pane last gave it, measured but never placed. So
 * the page never leaves the composition: switching back is the live page at once, nothing parked, covered or reloaded.
 * Phones don't provide one: their tabs leave the composition and the pool parks their pages ([AppWebViews.tab]).
 */
class BrowserTabKeeper {
    private class Args(val view: ViewDto, val workspaceId: String, val actions: BrowserActions, val modifier: Modifier)

    private val contents = mutableMapOf<String, @Composable (Args) -> Unit>()

    /** The size each tab's pane last had, so a tab in the back keeps it and its page doesn't reflow. */
    private val sizes = mutableMapOf<String, IntSize>()

    private fun contentOf(viewId: String): @Composable (Args) -> Unit = contents.getOrPut(viewId) {
        movableContentOf { a: Args -> BrowserPane(a.view, a.workspaceId, a.actions, a.modifier, keepRunning = true) }
    }

    /** The tab [view] in its pane. */
    @Composable
    fun Show(view: ViewDto, workspaceId: String, actions: BrowserActions, modifier: Modifier = Modifier) {
        contentOf(view.id)(Args(view, workspaceId, actions, modifier.onSizeChanged { sizes[view.id] = it }))
    }

    @Composable
    internal fun Kept(view: ViewDto, workspaceId: String, actions: BrowserActions) {
        contentOf(view.id)(Args(view, workspaceId, actions, Modifier))
    }

    internal fun sizeOf(viewId: String): IntSize? = sizes[viewId]

    /** A closed tab's composition and size go. */
    internal fun forget(keep: Set<String>) {
        contents.keys.retainAll(keep)
        sizes.keys.retainAll(keep)
    }

    companion object {
        /** At most this many tabs in the back stay live; past it the rest leave and the pool parks their pages. */
        const val MAX_LIVE_TABS = 32
    }
}

val LocalBrowserTabKeeper = staticCompositionLocalOf<BrowserTabKeeper?> { null }

/**
 * The workspace's Browser tabs not [shown] in a pane, kept composed by [LocalBrowserTabKeeper] (desktop): each measured
 * at its pane's last size and never placed, so its page counts as hidden and keeps running. Without a keeper, nothing.
 */
@Composable
fun KeepBrowserTabsLive(
    views: Collection<ViewDto>,
    shown: Set<String>,
    workspaceId: String,
    actions: BrowserActions,
    /** The views in this window's layout, or null for all: a tab another window shows is that window's to keep. */
    inLayout: Set<String>? = null,
) {
    val keeper = LocalBrowserTabKeeper.current ?: return
    val browser = views.filter { it.kind == "browser" && (inLayout == null || it.id in inLayout) }
    keeper.forget(browser.map { it.id }.toSet())
    val kept = browser.filter { it.id !in shown }.takeLast(BrowserTabKeeper.MAX_LIVE_TABS)
    Layout(
        content = { kept.forEach { v -> key(v.id) { keeper.Kept(v, workspaceId, actions) } } },
        measurePolicy = { measurables, constraints ->
            measurables.forEachIndexed { i, m ->
                val size = keeper.sizeOf(kept[i].id)
                m.measure(if (size != null) Constraints.fixed(size.width, size.height) else constraints)
            }
            layout(0, 0) {}
        },
    )
}
