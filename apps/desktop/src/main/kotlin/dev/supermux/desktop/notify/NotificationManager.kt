// M5-3: the seam over the actual OS/tray notification call.
package dev.supermux.desktop.notify

/** Fires one desktop notification. The real implementation ([TrayNotificationManager], Task 2)
 *  wraps Compose Desktop's `TrayState.sendNotification` — untestable-as-a-toast headlessly (there
 *  is no way to assert an OS balloon rendered under Xvfb); tests inject a capturing fake instead. */
interface NotificationManager {
    fun notify(sessionId: String, title: String, message: String)
}

/** Null-object default — used by [NotificationController]'s and [dev.supermux.desktop.workspace.WorkspaceRoot]'s
 *  parameter defaults so call sites/tests that don't care about notifications (e.g. the existing
 *  `WorkspaceRootTest` suite) keep compiling with zero changes. Production always passes a real
 *  [TrayNotificationManager] (wired in `Main.kt`, Task 2). */
object NoopNotificationManager : NotificationManager {
    override fun notify(sessionId: String, title: String, message: String) {}
}
