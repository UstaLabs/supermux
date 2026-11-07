// Every window's own chrome, the main window's and each extra window's alike: no system title bar,
// content edge to edge, the OS's window controls over it, and a top band that drags the window.
// One install per window, from whichever platform chrome engages there (MacWindowChrome.kt,
// LinuxWindowChrome.kt, WindowsWindowChrome.kt); `Main.kt` composes it into every `Window {}`.
package dev.supermux.desktop.shell

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import dev.supermux.desktop.platform.isMacOs
import dev.supermux.desktop.platform.isWindowsOs
import dev.supermux.ui.adaptive.LocalWindowControlsInsets
import dev.supermux.ui.adaptive.WindowControlsInsets
import dev.supermux.ui.shell.LocalWindowDragChrome

/** One window's chrome: at most one of the three platform installs is there. */
class DesktopWindowChrome(
    val mac: MacWindowChromeInstall?,
    val linux: LinuxWindowChromeInstall?,
    val windows: WindowsWindowChromeInstall?,
) {
    /** The band's drag regions under the custom chrome (Linux or Windows). */
    val bandRegions: MacChromeRegions? get() = linux?.regions ?: windows?.regions

    /** Every chrome's registry (macOS's too): the whole top band drags the window. */
    val regions: MacChromeRegions? get() = mac?.regions ?: bandRegions

    /** The traffic lights' live room on macOS; the windowed fallback elsewhere. */
    val trafficLightsInset: Dp get() = mac?.trafficLightsInset ?: MacTrafficLightsWidth

    /** Room the controls take in the band; [sidebarControls]: the sidebar's controls sit there too. */
    fun insets(sidebarControls: Boolean): ChromeInsets = chromeInsets(
        ChromeOs.of(dev.supermux.desktop.platform.DesktopOs.current),
        customChrome = linux != null || windows != null,
        windowsCaptionButtons = windows?.captionButtonsWidth ?: WindowsCaptionButtonsFallbackWidth,
        macTrafficLights = trafficLightsInset,
        sidebarControls = sidebarControls,
    )

    /** The OS's own window controls over the content, for a full-window overlay. */
    val controlsInsets: WindowControlsInsets
        get() = when {
            mac != null -> WindowControlsInsets(band = MacTitleBarHeight, start = trafficLightsInset)
            windows != null -> WindowControlsInsets(band = WindowsTitleBarHeight, end = windows.captionButtonsWidth)
            else -> WindowControlsInsets()
        }
}

/**
 * A window's caption: empty on macOS, where a non-empty title still paints centred over our own UI
 * on runtimes that ignore `apple.awt.windowTitleVisible`. Other platforms keep it — on Linux even
 * with the custom chrome: an undecorated window has no title bar to paint it in, and GNOME's
 * overview and Alt+Tab caption the window with it.
 */
fun desktopWindowTitle(title: String): String = if (isMacOs()) "" else title

/**
 * Installs [window]'s chrome. macOS: the JBR custom title bar, or on a runtime without it the
 * full-size-content client properties (where the band still drags the window under tabs, since
 * stock AWT has no hit-test hook). Windows: the JBR custom title bar, keeping Windows' own caption
 * buttons. Linux: with [linuxMoveResize] (the window must then be created undecorated), the band's
 * WM move and the edge resize. Null installs: that platform keeps its system frame.
 */
@Composable
fun rememberDesktopWindowChrome(window: ComposeWindow, linuxMoveResize: X11MoveResize?): DesktopWindowChrome {
    val mac = if (isMacOs()) rememberMacWindowChrome(window) else null
    val linux = linuxMoveResize?.let { rememberLinuxWindowChrome(window, it) }
    val windows = if (isWindowsOs()) rememberWindowsWindowChrome(window, dark = true) else null
    if (mac != null && mac.titleBar == null) {
        LaunchedEffect(window) {
            window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
            window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
            window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
        }
    }
    return DesktopWindowChrome(mac, linux, windows)
}

/**
 * Hands [chrome] to [content]: the band's drag regions, the room the controls take in it
 * ([sidebarControls]: the main window's sidebar toggle, and on Linux / Windows its ☰ menu, sit
 * there as well), and Windows' caption buttons drawn light or dark to match the theme. Call inside
 * the window's theme. Under Linux's custom chrome the window draws its own buttons and edges too:
 * [LinuxWindowChromeOverlay], last in the window's root box.
 */
@Composable
fun ProvideDesktopWindowChrome(
    chrome: DesktopWindowChrome,
    sidebarControls: Boolean,
    content: @Composable () -> Unit,
) {
    chrome.windows?.let { wc ->
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        LaunchedEffect(wc, dark) { wc.setDark(dark) }
    }
    CompositionLocalProvider(
        LocalMacWindowChrome provides chrome.regions,
        LocalWindowDragChrome provides DesktopWindowDragChrome,
        LocalWindowChromeInsets provides chrome.insets(sidebarControls),
        LocalWindowControlsInsets provides chrome.controlsInsets,
        LocalMacTrafficLightsInset provides chrome.trafficLightsInset,
        content = content,
    )
}
