// Where the window's own controls sit over our edge-to-edge content, per platform. Content runs to
// the top edge on macOS (traffic lights at the top-left) and on Linux with the custom chrome (our
// minimise / maximise / close at the top-right) and on Windows with the custom title bar (Windows'
// own caption buttons at the top-right); everything else keeps the system frame and needs
// no room at all.
package dev.supermux.desktop.shell

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ChromeOs {
    MacOs,
    Linux,
    Windows,
    Other,
    ;

    companion object {
        /** The chrome for [os], [dev.supermux.desktop.platform.DesktopOs]'s one answer. */
        internal fun of(os: dev.supermux.desktop.platform.DesktopOs): ChromeOs = when (os) {
            dev.supermux.desktop.platform.DesktopOs.MACOS -> MacOs
            dev.supermux.desktop.platform.DesktopOs.WINDOWS -> Windows
            dev.supermux.desktop.platform.DesktopOs.LINUX -> Linux
        }

        /** For an `os.name` value; none is [Other]. */
        fun of(osName: String?): ChromeOs = if (osName == null) Other else of(dev.supermux.desktop.platform.DesktopOs.of(osName))
    }
}

/**
 * Room the window controls take inside the top band of the content.
 *
 * @property start width kept clear at the top-left (the traffic lights or the ☰ menu, and the
 *   sidebar toggle, which stays in the band in both sidebar states)
 * @property end width kept clear at the top-right (our Linux window buttons)
 * @property band height of the band the controls sit in; content below it is never covered
 */
@Immutable
data class ChromeInsets(val start: Dp, val end: Dp, val band: Dp) {
    companion object {
        val None = ChromeInsets(0.dp, 0.dp, 0.dp)
    }
}

/**
 * The insets for [os]. [customChrome] says whether our own chrome is drawn over the content (on
 * Linux only when [LinuxWindowChrome] engaged — otherwise the system frame draws its title bar
 * outside the content and nothing needs to move).
 */
fun chromeInsets(
    os: ChromeOs,
    customChrome: Boolean,
    windowsCaptionButtons: Dp = WindowsCaptionButtonsFallbackWidth,
    macTrafficLights: Dp = MacTrafficLightsWidth,
): ChromeInsets = when {
    customChrome && os == ChromeOs.Linux ->
        ChromeInsets(start = SidebarBandControlsWidth, end = LinuxWindowControlsWidth, band = LinuxTitleBarHeight)
    // Windows: the native caption buttons (JBR's live right inset) at the top-right of the band.
    customChrome && os == ChromeOs.Windows ->
        ChromeInsets(start = SidebarBandControlsWidth, end = windowsCaptionButtons, band = WindowsTitleBarHeight)
    // macOS: the traffic lights (JBR's live left inset) and the sidebar toggle beside them.
    os == ChromeOs.MacOs ->
        ChromeInsets(start = macTrafficLights + MacTitleBarHeight + 4.dp, end = 0.dp, band = MacTitleBarHeight)
    // Everything else keeps the system frame.
    else -> ChromeInsets.None
}

/** The ☰ main menu and the sidebar toggle at the start of the custom Linux / Windows band. */
val SidebarBandControlsWidth = 4.dp + LinuxTitleBarHeight * 2 + 4.dp

/**
 * The live insets for the main window's content. [ChromeInsets.None] by default, so pop-out
 * windows (system frame) and tests reserve nothing.
 */
val LocalWindowChromeInsets = staticCompositionLocalOf { ChromeInsets.None }
