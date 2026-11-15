// #117: applets on the desktop open in their own OS windows, with nothing of supermux around the page — like an
// installed web app. One window per applet: opening an applet that is already open brings its window to the front.
package dev.supermux.desktop.applets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.supermux.net.AppletDto
import dev.supermux.state.FleetStore
import dev.supermux.ui.applets.AppletPage
import dev.supermux.ui.session.AppletOpener
import java.awt.Frame

/** One open applet window: which host's applet, and its title for the window's title bar. */
internal data class OpenApplet(val hostId: String, val name: String, val title: String) {
    val key: String get() = "$hostId/$name"
}

/** The applet windows of this process. */
internal object DesktopApplets {
    val open = mutableStateListOf<OpenApplet>()
    private val windows = mutableMapOf<String, ComposeWindow>()

    val opener = AppletOpener { hostId, applet: AppletDto -> open(hostId, applet.name, applet.title.ifBlank { applet.name }) }

    fun open(hostId: String, name: String, title: String) {
        val a = OpenApplet(hostId, name, title)
        val shown = windows[a.key]
        if (shown != null) {
            // Already open: to the front, restored if minimized.
            if (shown.extendedState and Frame.ICONIFIED != 0) shown.extendedState = shown.extendedState and Frame.ICONIFIED.inv()
            shown.toFront()
            shown.requestFocus()
            return
        }
        if (open.none { it.key == a.key }) open += a
    }

    fun close(key: String) {
        open.removeAll { it.key == key }
    }

    fun register(key: String, window: ComposeWindow) { windows[key] = window }
    fun unregister(key: String, window: ComposeWindow) { if (windows[key] === window) windows.remove(key) }
}

/**
 * Every open applet's window. [theme] wraps each window's content as the main window's is (appearance, text scale).
 * Called from `application { }`, beside the main window.
 */
@Composable
internal fun AppletWindows(fleet: FleetStore?, theme: @Composable (@Composable () -> Unit) -> Unit) {
    for (a in DesktopApplets.open) {
        key(a.key) {
            val state = rememberWindowState(position = WindowPosition.PlatformDefault, size = DpSize(1100.dp, 780.dp))
            Window(
                onCloseRequest = { DesktopApplets.close(a.key) },
                title = a.title,
                icon = painterResource("supermux-icon.png"),
                state = state,
            ) {
                DisposableEffect(a.key, window) {
                    DesktopApplets.register(a.key, window)
                    onDispose { DesktopApplets.unregister(a.key, window) }
                }
                theme {
                    AppletPage(fleet, a.hostId, a.name)
                }
            }
        }
    }
}
