package dev.supermux.desktop

/**
 * Diagnostics nobody needs unless they ask: printed (to stderr) only with `SUPERMUX_DEBUG=1`, or
 * `SUPERMUX_CHROME_DEBUG=1`, which also turns on the window chrome's own hit-test log.
 */
object DesktopDebug {
    val enabled: Boolean = System.getenv("SUPERMUX_DEBUG") == "1" || System.getenv("SUPERMUX_CHROME_DEBUG") == "1"

    fun log(tag: String, message: String) {
        if (enabled) System.err.println("[$tag] $message")
    }
}
