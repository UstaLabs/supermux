package dev.supermux.desktop.host

/**
 * What a tray menu click does, shared by every tray that renders [trayMenuItems]: the AWT/Compose
 * tray ([HostingTrayMenu]) and the Linux StatusNotifierItem tray (`host/linux/SniTray`). Both call
 * the same two handlers, so a row does the same thing whichever tray showed it.
 *
 * Called on the UI thread (the SNI tray hops there before calling).
 */
class TrayDispatcher(
    private val action: (TrayAction) -> Unit,
    private val toggle: (TrayToggle, Boolean) -> Unit,
) {
    fun onAction(a: TrayAction) = action(a)

    fun onToggle(t: TrayToggle, on: Boolean) = toggle(t, on)

    /** A click on [item]: an action runs, a checkbox flips, a header or separator does nothing. */
    fun click(item: TrayItem) {
        when (item) {
            is TrayItem.Action -> if (item.enabled) onAction(item.id)
            is TrayItem.Checkbox -> if (item.enabled) onToggle(item.id, !item.checked)
            is TrayItem.Header, TrayItem.Separator -> Unit
        }
    }
}
