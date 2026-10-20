// Desktop window-drag seam for shared UI outside a tab strip (see PaneStripChrome for the strips).
//
// On the desktop the whole top band of the window moves it, on every screen. Two things in the
// shared UI need to say so: what must never move the window (anything that is itself dragged or
// scrolled — splitter seams, the sidebar resize seam), and opaque layers that hide the chrome of
// the page underneath (a full-pane overlay over Home). Elsewhere these are no-ops.
package dev.supermux.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import dev.supermux.ui.panes.newPaneKey

@Stable
interface WindowDragChrome {
    /** This element is dragged or scrolled itself, so a press on it never moves the window. */
    fun exclude(key: String): Modifier

    /** An opaque layer: the chrome registered on the layers below is ignored under it. */
    fun cover(key: String): Modifier

    object None : WindowDragChrome {
        override fun exclude(key: String): Modifier = Modifier
        override fun cover(key: String): Modifier = Modifier
    }
}

val LocalWindowDragChrome = staticCompositionLocalOf<WindowDragChrome> { WindowDragChrome.None }

/** How many opaque layers deep this content is; region registrations carry it. */
val LocalWindowDragLayer = staticCompositionLocalOf { 0 }

/** [WindowDragChrome.exclude] with a key of its own. */
@Composable
fun Modifier.excludeFromWindowDrag(): Modifier {
    val key = remember { newPaneKey() }
    return this then LocalWindowDragChrome.current.exclude("exclude-$key")
}

/** [WindowDragChrome.cover] with a key of its own; apply inside the layer it covers for. */
@Composable
fun Modifier.windowDragCover(): Modifier {
    val key = remember { newPaneKey() }
    return this then LocalWindowDragChrome.current.cover("cover-$key")
}
