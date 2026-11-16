// #117: applets on the desktop open in their own OS windows, with nothing of supermux around the page — like an
// installed web app. One window per applet: opening an applet that is already open brings its window to the front.
package dev.supermux.desktop.applets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

    /**
     * Where this supermux takes applets from later launches and from installed applets' stubs (AppletHandOff): the
     * packaged app's (Main listens on it), or a dev run's own (the hot run listens here, from first use), so a stub
     * a dev run installed opens in that run, and never in the installed app with other state, or the other way round.
     */
    private val packaged = System.getProperty("jpackage.app-path") != null
    val handOffSocket: java.nio.file.Path =
        dev.supermux.desktop.auth.DesktopTokenStore.defaultPath().parent.resolve(if (packaged) "applets.sock" else "applets-dev.sock")

    private val listening = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * A dev run starts listening on its socket (once): at first use, and again before an install writes a stub — a
     * hot reload doesn't re-run this object's init, so the install is what makes sure a stub has someone to talk to.
     */
    fun ensureListening() {
        if (packaged || !listening.compareAndSet(false, true)) return
        val ok = AppletHandOff.listen(
            handOffSocket,
            onOpen = { host, name -> javax.swing.SwingUtilities.invokeLater { open(host, name, name) } },
            onActivate = { activateRequests.tryEmit(Unit) },
        )
        if (!ok) listening.set(false)
    }

    init { ensureListening() }

    /** Install on this Mac / PC / computer. */
    val installer = DesktopAppletInstaller(handOffSocket = { ensureListening(); handOffSocket })

    /** A second launch of the installed app asked for the main window (AppletHandOff). */
    val activateRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 4)

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

    /** Every open applet window to the front (a cold start from an applet's shortcut: above the main window). */
    fun raiseAll() {
        for (w in windows.values) {
            w.toFront()
            w.requestFocus()
        }
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
internal fun AppletWindows(fleet: FleetStore?, onOpenChat: (String) -> Unit, theme: @Composable (@Composable () -> Unit) -> Unit) {
    for (a in DesktopApplets.open) {
        key(a.key) {
            val state = rememberWindowState(position = WindowPosition.PlatformDefault, size = DpSize(1100.dp, 780.dp))
            // Opened from a shortcut it has only its name: the window takes the title the host gives it.
            val none = androidx.compose.runtime.remember { kotlinx.coroutines.flow.MutableStateFlow<List<AppletDto>?>(null) }
            val listed by (fleet?.appForRecord(a.hostId)?.applets ?: none).collectAsState()
            val title = listed?.firstOrNull { it.name == a.name }?.title?.takeIf { it.isNotBlank() } ?: a.title
            Window(
                onCloseRequest = { DesktopApplets.close(a.key) },
                title = title,
                icon = painterResource("supermux-icon.png"),
                state = state,
            ) {
                DisposableEffect(a.key, window) {
                    DesktopApplets.register(a.key, window)
                    onDispose { DesktopApplets.unregister(a.key, window) }
                }
                theme {
                    AppletPage(fleet, a.hostId, a.name, onOpenChat = onOpenChat)
                }
            }
        }
    }
}
