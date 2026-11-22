// #117: the applet screens, reached only through [LocalAppletScreens]. The hosts that can open applets (Android, desktop,
// iOS) provide [NativeAppletScreens]; the web build, which hides applets until #114, provides none, so nothing in it
// refers to the sidebar block, All applets or Applet info and its bundle drops them (dead-code elimination).
package dev.supermux.ui.session

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import dev.supermux.state.FleetStore

interface AppletScreens {
    /** The sidebar's Applets block: its header and the pinned grid. */
    fun zone(scope: LazyListScope, actions: AppletsActions, phone: Boolean)

    /** All applets. */
    @Composable fun All(actions: AppletsActions, phone: Boolean, onBack: () -> Unit)

    /** The applet [name] of host [hostId] as a screen of the app (one-window hosts): a back row, then the page. */
    @Composable fun Page(fleet: FleetStore, hostId: String, name: String, onBack: () -> Unit, openChat: (String) -> Unit)

    /** Applet info for [name], at [initialTab]; [openChat] shows a chat in the main window. */
    @Composable fun Info(fleet: FleetStore, actions: AppletsActions, name: String, phone: Boolean, initialTab: String?, onBack: () -> Unit, openChat: (String) -> Unit)

    /** Host [hostId]'s first pinned applets, loaded ahead so they open at once. */
    @Composable fun Preloads(fleet: FleetStore, hostId: String?)

    /** Wipes what the app's web views keep of each host as it is forgotten (unpaired). */
    @Composable fun ForgetUnpairedSites(fleet: FleetStore)
}

val LocalAppletScreens = staticCompositionLocalOf<AppletScreens?> { null }

object NativeAppletScreens : AppletScreens {
    override fun zone(scope: LazyListScope, actions: AppletsActions, phone: Boolean) = scope.appletsZone(actions, phone)

    @Composable
    override fun All(actions: AppletsActions, phone: Boolean, onBack: () -> Unit) = AppletsScreen(actions, phone, onBack)

    @Composable
    override fun Page(fleet: FleetStore, hostId: String, name: String, onBack: () -> Unit, openChat: (String) -> Unit) =
        dev.supermux.ui.applets.AppletScreen(fleet, hostId, name, onBack, openChat)

    @Composable
    override fun Info(fleet: FleetStore, actions: AppletsActions, name: String, phone: Boolean, initialTab: String?, onBack: () -> Unit, openChat: (String) -> Unit) {
        val info = remember(fleet, actions) {
            dev.supermux.ui.applets.appletInfoActions(fleet, actions.hostName, actions.open, openChat = openChat, installer = actions.installer)
        }
        dev.supermux.ui.applets.AppletInfoScreen(name, info, phone = phone, onBack = onBack, initialTab = initialTab)
    }

    @Composable
    override fun Preloads(fleet: FleetStore, hostId: String?) = dev.supermux.ui.applets.AppletPreloads(fleet, hostId)

    @Composable
    override fun ForgetUnpairedSites(fleet: FleetStore) = dev.supermux.ui.applets.ForgetUnpairedSites(fleet)
}
