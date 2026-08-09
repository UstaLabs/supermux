package dev.supermux.desktop.shell

import androidx.compose.ui.Modifier
import dev.supermux.ui.panes.PaneStripChrome

/**
 * The real desktop chrome: registers the macOS title-bar drag regions.
 *
 * On macOS the EMPTY TAIL of a strip is a native window-drag handle (browser-tab-bar behavior).
 * The tabs + "+" row punches a hole in that region so dragging a tab never moves the window. A
 * no-op off macOS/JBR — see MacWindowChrome.kt.
 */
object DesktopStripChrome : PaneStripChrome {
    override fun strip(key: String): Modifier = Modifier.macTitleBarDragRegion(key)
    override fun tabs(key: String): Modifier = Modifier.macTitleBarNoDragRegion(key)
}
