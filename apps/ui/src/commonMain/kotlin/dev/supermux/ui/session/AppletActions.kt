// #117: where applets come from in the app, and how one opens.
package dev.supermux.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import dev.supermux.net.AppletDto
import dev.supermux.state.FleetStore

/**
 * Opens an applet in its own chrome-less window (desktop) or activity (Android), straight away. Each platform provides
 * its own; null where applets can't open (the web build, until #114 gives applets their own origins): the app then
 * shows no Applets section at all, rather than one that can't do anything.
 */
fun interface AppletOpener {
    /** [hostId]: the host picker's host, whose broker serves the applet. */
    fun open(hostId: String, applet: AppletDto)
}

val LocalAppletOpener = staticCompositionLocalOf<AppletOpener?> { null }

/**
 * The active host's applets as the sidebar and All applets use them; null when there is nothing to show: no opener on
 * this platform, a host whose broker has no applets, or not connected yet.
 */
@Composable
fun rememberAppletsActions(fleet: FleetStore, hostId: String?, hostName: String, showAll: () -> Unit): AppletsActions? {
    val opener = LocalAppletOpener.current ?: return null
    val applets by fleet.activeApplets.collectAsState()
    val list = applets ?: return null
    val host = hostId ?: return null
    return remember(list, host, hostName, opener, showAll) {
        AppletsActions(
            applets = list,
            hostName = hostName,
            open = { a -> opener.open(host, a) },
            showAll = showAll,
            pin = { a, on -> fleet.pinApplet(a.name, on) },
            loadIcon = { a -> fleet.appletIconBytes(a) },
        )
    }
}
