package dev.supermux.desktop.platform

/** True on macOS (the window chrome, the title bar band). The OS is [DesktopOs]'s one answer. */
internal fun isMacOs(os: DesktopOs = DesktopOs.current): Boolean = os == DesktopOs.MACOS

/** True on Linux and the other Unixes (the StatusNotifierItem tray, freedesktop's tools). */
internal fun isLinuxOs(os: DesktopOs = DesktopOs.current): Boolean = os == DesktopOs.LINUX

/** True on Windows (the tray glyph follows the taskbar theme). */
internal fun isWindowsOs(os: DesktopOs = DesktopOs.current): Boolean = os == DesktopOs.WINDOWS
