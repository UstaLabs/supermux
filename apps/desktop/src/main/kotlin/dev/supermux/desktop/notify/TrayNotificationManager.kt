// M5-3: the real NotificationManager, backed by Compose Desktop's TrayState.sendNotification — the
// underlying java.awt.TrayIcon.displayMessage call (confirmed via javap against the real
// ui-desktop-1.11.1.jar: Tray_desktopKt.displayMessage maps Notification.Type to
// TrayIcon.MessageType and calls TrayIcon.displayMessage). No new Gradle dependency — Tray/
// TrayState/Notification are all part of compose.desktop.currentOs, already a project dependency.
package dev.supermux.desktop.notify

import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState

class TrayNotificationManager(private val trayState: TrayState) : NotificationManager {
    override fun notify(sessionId: String, title: String, message: String) {
        // sessionId isn't carried by Compose's Notification (title/message/type only, confirmed
        // via javap) — NotificationController is the one that remembers WHICH session this toast
        // was for (lastNotifiedSession), for the tray icon's best-effort click-to-focus.
        trayState.sendNotification(Notification(title, message, Notification.Type.Info))
    }
}
