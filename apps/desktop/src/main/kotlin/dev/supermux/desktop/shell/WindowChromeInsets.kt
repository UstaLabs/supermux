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
        fun of(osName: String? = System.getProperty("os.name")): ChromeOs {
            val n = osName?.lowercase().orEmpty()
            return when {
                n.contains("mac") || n.contains("darwin") -> MacOs
                n.contains("linux") -> Linux
                n.startsWith("windows") -> Windows
                else -> Other
            }
        }
    }
}

/**
 * Room the window controls take inside the top band of the content.
 *
 * @property start width kept clear at the top-left (the traffic lights or the ☰ menu, and the
 *   sidebar toggle, which stays in the band in both sidebar states; just the traffic lights in a
 *   window with no sidebar)
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
 * outside the content and nothing needs to move). [sidebarControls]: the band also holds the
 * sidebar's toggle (and on Linux / Windows the ☰ menu) — the main window's does, a torn-off
 * window's has no sidebar.
 */
fun chromeInsets(
    os: ChromeOs,
    customChrome: Boolean,
    windowsCaptionButtons: Dp = WindowsCaptionButtonsFallbackWidth,
    macTrafficLights: Dp = MacTrafficLightsWidth,
    sidebarControls: Boolean = true,
): ChromeInsets {
    val bandControls = if (sidebarControls) SidebarBandControlsWidth else 0.dp
    return when {
        customChrome && os == ChromeOs.Linux ->
            ChromeInsets(start = bandControls, end = LinuxWindowControlsWidth, band = LinuxTitleBarHeight)
        // Windows: the native caption buttons (JBR's live right inset) at the top-right of the band.
        customChrome && os == ChromeOs.Windows ->
            ChromeInsets(start = bandControls, end = windowsCaptionButtons, band = WindowsTitleBarHeight)
        // macOS: the traffic lights (JBR's live left inset) and the sidebar toggle beside them.
        os == ChromeOs.MacOs -> ChromeInsets(
            start = if (sidebarControls) macTrafficLights + MacTitleBarHeight + 4.dp else macTrafficLights,
            end = 0.dp,
            band = MacTitleBarHeight,
        )
        // Everything else keeps the system frame.
        else -> ChromeInsets.None
    }
}

/** The ☰ main menu and the sidebar toggle at the start of the custom Linux / Windows band. */
val SidebarBandControlsWidth = 4.dp + LinuxTitleBarHeight * 2 + 4.dp

/**
 * The live insets for a window's content, provided by [ProvideDesktopWindowChrome].
 * [ChromeInsets.None] by default, so tests reserve nothing.
 */
val LocalWindowChromeInsets = staticCompositionLocalOf { ChromeInsets.None }
