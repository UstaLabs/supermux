// #117 S1: "Add to Home screen" for an applet on Android — a pinned shortcut (ShortcutManager.requestPinShortcut, One UI
// shows its own "Add to Home screen?" sheet and badges the icon with supermux's) that opens the applet's own task, the
// same AppletActivity intent the app opens it with, so a tap brings its existing Recents card back. Each applet is
// also a dynamic shortcut (long-press supermux's icon), most recent first.
package dev.supermux.android.applets

import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import dev.supermux.net.AppletDto
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.session.AppletInstaller

class AndroidAppletInstaller(private val context: Context) : AppletInstaller {
    private val shortcuts: ShortcutManager? get() = context.getSystemService(ShortcutManager::class.java)

    override val phone: Boolean = true
    override val where: String get() = localized(Str.applets_where_home)
    override val label: String get() = localized(Str.applets_menu_add_home)

    private fun id(hostId: String, name: String) = "applet:$hostId/$name"

    override fun isInstalled(hostId: String, applet: AppletDto): Boolean =
        runCatching { shortcuts?.pinnedShortcuts?.any { it.id == id(hostId, applet.name) } == true }.getOrDefault(false)

    override suspend fun install(hostId: String, applet: AppletDto, icon: ByteArray?): String? {
        val sm = shortcuts ?: return localized(Str.applets_install_no_home)
        if (!sm.isRequestPinShortcutSupported) return localized(Str.applets_install_no_home)
        val title = applet.title.ifBlank { applet.name }
        val info = ShortcutInfo.Builder(context, id(hostId, applet.name))
            .setShortLabel(title.take(24))
            .setLongLabel(title)
            .setIcon(Icon.createWithAdaptiveBitmap(iconBitmap(icon, title)))
            .setIntent(AppletActivity.intent(context, hostId, applet.name, title))
            .build()
        val asked = runCatching {
            // Offered among the app's own shortcuts too (long-press supermux), then pinned with the system's sheet.
            if (android.os.Build.VERSION.SDK_INT >= 30) runCatching { sm.pushDynamicShortcut(info) }
            sm.requestPinShortcut(info, null)
        }.getOrElse { return it.message ?: localized(Str.applets_install_refused) }
        if (!asked) return localized(Str.applets_install_refused)
        // The system's sheet decides: it counts as added only once the shortcut is really on the Home screen. A sheet
        // he cancels (or leaves for a minute) is no error: "" leaves the button as it was.
        repeat(60) {
            if (isInstalled(hostId, applet)) return null
            kotlinx.coroutines.delay(1_000)
        }
        return ""
    }

    /** An adaptive icon's 108dp square, with the applet's own image in its safe zone (or the lettered tile). */
    private fun iconBitmap(bytes: ByteArray?, title: String) = AppletIcons.bitmap(bytes, title, 432)
}
