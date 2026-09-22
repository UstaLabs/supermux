// Cluster G1: the process-wide handle behind `Platform.notifications` on desktop.
package dev.supermux.desktop.notify

import dev.supermux.ui.platform.NoopNotificationManager
import dev.supermux.ui.platform.NotificationManager

/**
 * Forwards to the live [TrayNotificationManager] once `Main.kt` has one.
 *
 * The real manager can only be built inside `application { }` (it wraps the `TrayState` that
 * `Tray(...)` needs), while `Platform` is constructed per theme mount — so the seam is this stable
 * singleton and `Main.kt` [install]s the tray manager into it. Before that (and on a host with no
 * tray at all) every notify is a no-op, which is exactly what a trayless desktop did.
 */
object DesktopNotifications : NotificationManager {
    @Volatile
    private var delegate: NotificationManager = NoopNotificationManager

    fun install(manager: NotificationManager) {
        delegate = manager
    }

    override fun notify(sessionId: String, title: String, message: String) =
        delegate.notify(sessionId, title, message)
}
