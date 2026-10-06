package dev.supermux.desktop.host

import dev.supermux.desktop.host.linux.SniStatus

/**
 * Which tray the app shows. [useAwt]: compose AWT's `Tray`. [trayAvailable]: there is a tray to
 * hide the window into. [latch]: the AWT-fallback latch to keep for next time.
 */
data class TrayChoice(val useAwt: Boolean, val trayAvailable: Boolean, val latch: Boolean?)

/**
 * Pure: the tray to show, at most ONE of them.
 *
 * [sniStatus] is the Linux SNI tray's state (pass [SniStatus.FAILED] where there is no SNI tray:
 * macOS, Windows). [awtLatched] is the AWT-fallback latch: null = undecided, true = AWT allowed,
 * false = never AWT again. [awtSupported] is `isTraySupported`.
 *
 * - Once SNI is [SniStatus.REGISTERED] the latch closes for good (false): a watcher that later goes
 *   away can take its XEmbed tray with it, and AWT's TrayIcon then throws inside composition.
 * - The first settled answer (UNSUPPORTED / FAILED) opens the latch when AWT is supported then,
 *   so an XEmbed-only panel still gets a tray; STARTING decides nothing.
 * - AWT is used only while SNI is not REGISTERED and the latch allows it. A latched AWT tray that
 *   SNI later beats gives way to it: never two icons.
 */
fun trayChoice(sniStatus: SniStatus, awtLatched: Boolean?, awtSupported: Boolean): TrayChoice {
    if (sniStatus == SniStatus.REGISTERED) return TrayChoice(useAwt = false, trayAvailable = true, latch = false)
    val latch = when {
        awtLatched != null -> awtLatched
        sniStatus == SniStatus.STARTING -> null
        else -> awtSupported
    }
    val useAwt = latch == true && awtSupported
    return TrayChoice(useAwt = useAwt, trayAvailable = useAwt, latch = latch)
}
