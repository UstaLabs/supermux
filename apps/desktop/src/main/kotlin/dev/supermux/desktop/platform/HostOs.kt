package dev.supermux.desktop.platform

/** True on macOS (the window chrome, the title bar band). */
internal fun isMacOs(osName: String? = System.getProperty("os.name")): Boolean =
    osName?.lowercase()?.let { it.contains("mac") || it.contains("darwin") } ?: false

/** True on Linux (the StatusNotifierItem tray). */
internal fun isLinuxOs(osName: String? = System.getProperty("os.name")): Boolean =
    osName?.lowercase()?.contains("linux") ?: false

/** True on Windows (the tray glyph follows the taskbar theme). */
internal fun isWindowsOs(osName: String? = System.getProperty("os.name")): Boolean =
    osName?.lowercase()?.startsWith("windows") ?: false
