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
    /** The menu's words for it: "Add to Home screen", "Install on this Mac". */
    val label: String
    /** Whether [applet] of [hostId] is already on this device's launcher (the menu then says so instead). */
    fun isInstalled(hostId: String, applet: AppletDto): Boolean
    /** Asks the system to add it: null once it is there, "" when he didn't (no error), else why it couldn't. */
    suspend fun install(hostId: String, applet: AppletDto, icon: ByteArray?): String?
}

val LocalAppletInstaller = staticCompositionLocalOf<AppletInstaller?> { null }

/**
 * Home screen widgets for applets (W1), on a phone: null where this device has none (the menu then has no "Add widget").
 */
interface AppletWidgets {
    /** True: the app asks the system to place one (Android's "Add widget?"). False: only he can (iPhone): a how-to sheet. */
    val canPin: Boolean
    /** Asks the system for a widget showing [applet]: null once asked, else why it couldn't. */
    suspend fun pin(hostId: String, applet: AppletDto): String?
}

val LocalAppletWidgets = staticCompositionLocalOf<AppletWidgets?> { null }

/**
 * Asks every open applet menu and "Add widget" sheet to close: an applet opened from outside the app (a widget, a
 * control) must not come up under a sheet left open from before (seen on iOS: the how-to stayed over the applet).
 */
object AppletSheets {
    private val _closeRequests = kotlinx.coroutines.flow.MutableStateFlow(0)
    val closeRequests: kotlinx.coroutines.flow.StateFlow<Int> = _closeRequests

    fun closeAll() { _closeRequests.value++ }
}

/**
 * The active host's applets as the sidebar and All applets use them; null when there is nothing to show: no opener on
 * this platform, a host whose broker has no applets, or not connected yet.
 */
@Composable
fun rememberAppletsActions(fleet: FleetStore, hostId: String?, hostName: String, showAll: () -> Unit, info: (AppletDto, String) -> Unit): AppletsActions? {
    val opener = LocalAppletOpener.current ?: return null
    val installer = LocalAppletInstaller.current
    val widgets = LocalAppletWidgets.current
    val applets by fleet.activeApplets.collectAsState()
    val list = applets ?: return null
    val host = hostId ?: return null
    return remember(list, host, hostName, opener, installer, widgets, showAll, info) {
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
                    label = i.label,
                    isInstalled = { a -> i.isInstalled(host, a) },
                    install = { a ->
                        i.install(host, a, if (a.iconUrl != null) fleet.appletIconBytes(a) else null)
                            ?.takeIf { it.isNotEmpty() }
                            ?: run {
                                if (!i.isInstalled(host, a)) return@run null
                                fleet.appletAction { api -> api.addAppletInstall(a.name, i.where) }
                                null
                            }
                    },
                )
            },
            widget = widgets?.let { w -> AppletWidgetAdd(w.canPin) { a -> w.pin(host, a) } },
        )
    }
}
