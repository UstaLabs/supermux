package dev.supermux.desktop.platform

import dev.supermux.desktop.host.OsEnv
import dev.supermux.desktop.host.SystemOsEnv

/** What one settings query's [output] says about reduced motion on [os]; false when it cannot tell. */
internal fun reduceMotionFrom(os: DesktopOs, output: String?): Boolean {
    val value = output?.trim() ?: return false
    return when (os) {
        DesktopOs.MACOS -> value == "1"
        DesktopOs.LINUX -> value == "false"
        DesktopOs.WINDOWS -> false
    }
}

/**
 * Asks the OS once, through [env] within 2 s: macOS `defaults`, GNOME `gsettings`. Windows, and a query that fails
 * or times out (macOS's key is absent until the user touched the setting), read as false. Blocking.
 */
internal fun probeReduceMotion(os: DesktopOs = DesktopOs.current, env: OsEnv = SystemOsEnv): Boolean {
    val query = when (os) {
        DesktopOs.MACOS -> listOf("defaults", "read", "com.apple.universalaccess", "reduceMotion")
        DesktopOs.LINUX -> listOf("gsettings", "get", "org.gnome.desktop.interface", "enable-animations")
        DesktopOs.WINDOWS -> return false
    }
    val result = env.runResult(query, 2_000)
    return result.exit == 0 && reduceMotionFrom(os, result.out)
}
