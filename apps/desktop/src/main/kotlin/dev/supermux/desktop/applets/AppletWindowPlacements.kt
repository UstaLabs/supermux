// Where each applet's window was (Ahmet): reopened, an applet's window comes back where it was, at the same size, maximized
// or full screen as it was. Per device (window places are this computer's), in applet-windows.json next to the token store,
// keyed by the host's record and the applet's name, the applet's address (supermux://applet/<host>/<name>).
package dev.supermux.desktop.applets

import dev.supermux.desktop.auth.DesktopTokenStore
import dev.supermux.desktop.platform.InitialWindow
import dev.supermux.desktop.platform.SavedWindow
import dev.supermux.desktop.platform.restoredWindow
import java.awt.Rectangle
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** An applet window's place: its last floating bounds (dp, AWT user space), and whether it was maximized or full screen. */
@Serializable
data class AppletWindowPlace(val window: SavedWindow, val fullscreen: Boolean = false)

/** Where an applet window opens: its bounds (null x/y: the OS places it), and its state. */
internal data class AppletWindowOpening(val bounds: InitialWindow, val fullscreen: Boolean)

/** The size an applet window opens at when it has no place yet (dp). */
internal const val APPLET_WINDOW_WIDTH = 1100
internal const val APPLET_WINDOW_HEIGHT = 780

/**
 * Pure. Where [key]'s window opens: its [saved] place when that is still on one of [screens] (the main window's rule,
 * [restoredWindow]: its title bar on a screen, its size clamped to it), else the default size, placed by the OS. A
 * window last left on an unplugged display opens at its size on a display that is there, never off screen.
 */
internal fun appletWindowOpening(saved: AppletWindowPlace?, screens: List<Rectangle>): AppletWindowOpening {
    val fallback = InitialWindow(
        saved?.window?.width?.takeIf { it > 0 }?.let { w -> screens.maxOfOrNull { it.width }?.let { minOf(w, it) } ?: w } ?: APPLET_WINDOW_WIDTH,
        saved?.window?.height?.takeIf { it > 0 }?.let { h -> screens.maxOfOrNull { it.height }?.let { minOf(h, it) } ?: h } ?: APPLET_WINDOW_HEIGHT,
    ).copy(maximized = saved?.window?.maximized == true)
    val bounds = restoredWindow(saved?.window, screens, fallback)
    return AppletWindowOpening(bounds, saved?.fullscreen == true)
}

/** The applet windows' places on this computer. Reads once, writes as windows move; a broken file is a fresh start. */
internal class AppletWindowPlacements(private val path: Path = defaultPath()) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val lock = Any()
    private var places: MutableMap<String, AppletWindowPlace>? = null

    private fun loaded(): MutableMap<String, AppletWindowPlace> = places ?: runCatching {
        json.decodeFromString<Map<String, AppletWindowPlace>>(Files.readString(path)).toMutableMap()
    }.getOrElse { mutableMapOf() }.also { places = it }

    fun get(hostId: String, name: String): AppletWindowPlace? = synchronized(lock) { loaded()[key(hostId, name)] }

    /** Keeps [place] for the applet and writes the file whole (a temporary file moved over it: never half written). */
    fun put(hostId: String, name: String, place: AppletWindowPlace) {
        val text = synchronized(lock) {
            val all = loaded()
            if (all[key(hostId, name)] == place) return
            all[key(hostId, name)] = place
            json.encodeToString(kotlinx.serialization.builtins.MapSerializer(String.serializer(), AppletWindowPlace.serializer()), all)
        }
        runCatching {
            Files.createDirectories(path.parent)
            val tmp = path.resolveSibling("${path.fileName}.tmp")
            Files.writeString(tmp, text)
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure { println("[AppletWindowPlacements] not saved: $it") } // i18n-ok: a log line, never shown
    }

    companion object {
        fun key(hostId: String, name: String): String = "$hostId/$name"
        fun defaultPath(): Path = DesktopTokenStore.defaultPath().parent.resolve("applet-windows.json")
        val shared: AppletWindowPlacements by lazy { AppletWindowPlacements() }
    }
}
