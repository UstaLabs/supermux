// M5-3: the real NotificationManager, backed by Compose Desktop's TrayState.sendNotification — the
// underlying java.awt.TrayIcon.displayMessage call (confirmed via javap against the real
// ui-desktop-1.11.1.jar: Tray_desktopKt.displayMessage maps Notification.Type to
// TrayIcon.MessageType and calls TrayIcon.displayMessage). No new Gradle dependency — Tray/
// TrayState/Notification are all part of compose.desktop.currentOs, already a project dependency.
package dev.supermux.desktop.notify

import androidx.compose.ui.window.Notification
import dev.supermux.ui.platform.NotificationManager
import androidx.compose.ui.window.TrayState
import dev.supermux.desktop.host.linux.SniTray

/**
 * [sniTray] (Linux): whenever its session bus is up — tray registered or not — the toast goes to
 * `org.freedesktop.Notifications` on that bus; AWT's balloon needs an AWT tray icon.
 */
class TrayNotificationManager(
    private val trayState: TrayState,
    private val sniTray: SniTray? = null,
) : NotificationManager {
    override fun notify(sessionId: String, title: String, message: String) {
        if (sniTray != null && sniTray.busUp) {
            sniTray.notify(title, message)
            return
        }
        // sessionId isn't carried by Compose's Notification (title/message/type only, confirmed
        // via javap) — NotificationController is the one that remembers WHICH session this toast
        // was for (lastNotifiedSession), for the tray icon's best-effort click-to-focus.
        trayState.sendNotification(Notification(title, message, Notification.Type.Info))
    }
}
