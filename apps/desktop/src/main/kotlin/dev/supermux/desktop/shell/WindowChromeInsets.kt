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
 * @property start width kept clear at the top-left
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
fun chromeInsets(os: ChromeOs, customChrome: Boolean, windowsCaptionButtons: Dp = WindowsCaptionButtonsFallbackWidth): ChromeInsets = when {
    customChrome && os == ChromeOs.Linux ->
        ChromeInsets(start = 0.dp, end = LinuxWindowControlsWidth, band = LinuxTitleBarHeight)
    // Windows: the native caption buttons (JBR's live right inset) at the top-right of the band.
    customChrome && os == ChromeOs.Windows ->
        ChromeInsets(start = 0.dp, end = windowsCaptionButtons, band = WindowsTitleBarHeight)
    // macOS keeps its own traffic-light inset (LocalMacTrafficLightsInset, live from JBR); the rest
    // keep the system frame.
    else -> ChromeInsets.None
}

/**
 * The live insets for the main window's content. [ChromeInsets.None] by default, so pop-out
 * windows (system frame) and tests reserve nothing.
 */
val LocalWindowChromeInsets = staticCompositionLocalOf { ChromeInsets.None }
