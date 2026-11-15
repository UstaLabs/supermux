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
 * Puts an applet on this device's own launcher: a Home screen shortcut on a phone, an app in the Start menu /
 * Applications / the app menu on a desktop (S1). Null where this device can't: the menu then has no such item.
 */
interface AppletInstaller {
    /** Phone wording ("Add to Home screen") or desktop wording ("Install on this …"). */
    val phone: Boolean
    /** Where it lands, for On your devices ("Home screen", "Start menu", "Applications"). */
    val where: String
    /** Whether [applet] of [hostId] is already on this device's launcher (the menu then says so instead). */
    fun isInstalled(hostId: String, applet: AppletDto): Boolean
    /** Asks the system to add it; null when asked, else why it couldn't (shown in the menu). */
    suspend fun install(hostId: String, applet: AppletDto, icon: ByteArray?): String?
}

val LocalAppletInstaller = staticCompositionLocalOf<AppletInstaller?> { null }

/**
 * The active host's applets as the sidebar and All applets use them; null when there is nothing to show: no opener on
 * this platform, a host whose broker has no applets, or not connected yet.
 */
@Composable
fun rememberAppletsActions(fleet: FleetStore, hostId: String?, hostName: String, showAll: () -> Unit, info: (AppletDto, String) -> Unit): AppletsActions? {
    val opener = LocalAppletOpener.current ?: return null
    val installer = LocalAppletInstaller.current
    val applets by fleet.activeApplets.collectAsState()
    val list = applets ?: return null
    val host = hostId ?: return null
    return remember(list, host, hostName, opener, installer, showAll, info) {
        AppletsActions(
            applets = list,
            hostName = hostName,
            open = { a -> opener.open(host, a) },
            showAll = showAll,
            info = info,
            pin = { a, on -> fleet.pinApplet(a.name, on) },
            loadIcon = { a -> fleet.appletIconBytes(a) },
            installer = installer?.let { i ->
                AppletInstall(
                    phone = i.phone,
                    isInstalled = { a -> i.isInstalled(host, a) },
                    install = { a ->
                        i.install(host, a, if (a.iconUrl != null) fleet.appletIconBytes(a) else null)
                            ?: run { fleet.appletAction { api -> api.addAppletInstall(a.name, i.where) }; null }
                    },
                )
            },
        )
    }
}
