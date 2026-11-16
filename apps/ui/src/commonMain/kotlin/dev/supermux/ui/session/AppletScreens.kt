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

    /** Applet info for [name], at [initialTab]; [openChat] shows a chat in the main window. */
    @Composable fun Info(fleet: FleetStore, actions: AppletsActions, name: String, phone: Boolean, initialTab: String?, onBack: () -> Unit, openChat: (String) -> Unit)
}

val LocalAppletScreens = staticCompositionLocalOf<AppletScreens?> { null }

object NativeAppletScreens : AppletScreens {
    override fun zone(scope: LazyListScope, actions: AppletsActions, phone: Boolean) = scope.appletsZone(actions, phone)

    @Composable
    override fun All(actions: AppletsActions, phone: Boolean, onBack: () -> Unit) = AppletsScreen(actions, phone, onBack)

    @Composable
    override fun Info(fleet: FleetStore, actions: AppletsActions, name: String, phone: Boolean, initialTab: String?, onBack: () -> Unit, openChat: (String) -> Unit) {
        val info = remember(fleet, actions) {
            dev.supermux.ui.applets.appletInfoActions(fleet, actions.hostName, actions.open, openChat = openChat, installer = actions.installer)
        }
        dev.supermux.ui.applets.AppletInfoScreen(name, info, phone = phone, onBack = onBack, initialTab = initialTab)
    }
}
