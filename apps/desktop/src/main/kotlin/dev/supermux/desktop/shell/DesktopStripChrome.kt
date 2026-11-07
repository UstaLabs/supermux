package dev.supermux.desktop.shell

import androidx.compose.ui.Modifier
import dev.supermux.ui.panes.PaneStripChrome

/**
 * The real desktop chrome: registers the title-bar drag regions (macOS, and Linux with the custom
 * chrome).
 *
 * The EMPTY TAIL of a strip is a native window-drag handle (browser-tab-bar behavior). The tabs +
 * "+" row punches a hole in that region so dragging a tab never moves the window. A no-op without
 * a provided registry — see MacWindowChrome.kt / LinuxWindowChrome.kt. A strip in the top band also
 * keeps its tabs out from under the window's own buttons — Windows' caption buttons, Linux's custom
 * ones, the macOS traffic lights ([avoidWindowControls]).
 */
object DesktopStripChrome : PaneStripChrome {
    override fun strip(key: String): Modifier = Modifier.macTitleBarDragRegion(key).avoidWindowControls()
    override fun tabs(key: String): Modifier = Modifier.macTitleBarNoDragRegion(key)
}
