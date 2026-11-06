// Linux Wayland: the overlay's hold-to-talk key from any app (#62), through the GlobalShortcuts portal
// (xdg-desktop-portal; GNOME 48+, KDE Plasma 5.27+). Wayland gives no app a key grab of its own: the desktop
// asks the user once to confirm the shortcut, then reports its press (Activated) and release (Deactivated).
package dev.supermux.desktop.overlay

import dev.supermux.desktop.host.linux.SessionBus
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.UInt64
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** One shortcut, as the portal takes it: its id, and its description and preferred trigger. `(sa{sv})` */
class PortalShortcut(
    @field:Position(0) @JvmField val id: String,
    @field:Position(1) @JvmField val properties: Map<String, Variant<*>>,
) : Struct()

@JvmSuppressWildcards
@DBusInterfaceName("org.freedesktop.portal.GlobalShortcuts")
interface PortalGlobalShortcuts : DBusInterface {
    fun CreateSession(options: Map<String, Variant<*>>): DBusPath
    fun BindShortcuts(session: DBusPath, shortcuts: List<PortalShortcut>, parentWindow: String, options: Map<String, Variant<*>>): DBusPath

    class Activated(path: String, val session: DBusPath, val shortcutId: String, val timestamp: UInt64, val options: Map<String, Variant<*>>) :
        DBusSignal(path, session, shortcutId, timestamp, options)

    class Deactivated(path: String, val session: DBusPath, val shortcutId: String, val timestamp: UInt64, val options: Map<String, Variant<*>>) :
        DBusSignal(path, session, shortcutId, timestamp, options)
}

@JvmSuppressWildcards
@DBusInterfaceName("org.freedesktop.portal.Request")
interface PortalRequest : DBusInterface {
    class Response(path: String, val response: UInt32, val results: Map<String, Variant<*>>) : DBusSignal(path, response, results)
}

/**
 * The hold-to-talk key on Wayland. [register] asks the desktop for [KeyCombo] as a global shortcut (the desktop
 * asks the user once, so it can take a while) and returns what came of it; from then on [onPress] and
 * [onRelease] come on a bus thread. [env] gives the session bus.
 */
internal class WaylandHotKey(
    private val onPress: () -> Unit,
    private val onRelease: () -> Unit,
    private val env: Map<String, String> = System.getenv(),
) {
    enum class Outcome { BOUND, NO_PORTAL, DECLINED, FAILED }

    private var conn: DBusConnection? = null
    private val handlers = mutableListOf<AutoCloseable>()

    fun register(combo: KeyCombo, description: String): Outcome {
        unregister()
        val address = SessionBus.address(env) ?: return Outcome.NO_PORTAL
        val c = runCatching { DBusConnectionBuilder.forAddress(address).withShared(false).build() }.getOrNull() ?: return Outcome.NO_PORTAL
        conn = c
        // No GlobalShortcuts interface (GNOME before 48, older KDE): nothing to ask.
        val props = c.getRemoteObject(DESKTOP_NAME, DESKTOP_PATH, Properties::class.java)
        if (runCatching { props.Get<Any?>(IFACE, "version") }.isFailure) return Outcome.NO_PORTAL
        val responses = LinkedBlockingQueue<PortalRequest.Response>()
        // Listening before asking: the answer may come before the call returns.
        handlers += c.addSigHandler(PortalRequest.Response::class.java) { responses.add(it) }
        val portal = c.getRemoteObject(DESKTOP_NAME, DESKTOP_PATH, PortalGlobalShortcuts::class.java)
        val token = "supermux${System.nanoTime()}"
        val created = runCatching {
            portal.CreateSession(mapOf("handle_token" to Variant(token + "c"), "session_handle_token" to Variant(token + "s")))
        }.getOrNull() ?: return Outcome.FAILED
        val opened = await(responses, created.path, CREATE_WAIT_S) ?: return Outcome.FAILED
        if (opened.response.toInt() != 0) return Outcome.FAILED
        val session = opened.results["session_handle"]?.value?.toString() ?: return Outcome.FAILED
        handlers += c.addSigHandler(PortalGlobalShortcuts.Activated::class.java) { s ->
            if (s.session.path == session && s.shortcutId == TALK) onPress()
        }
        handlers += c.addSigHandler(PortalGlobalShortcuts.Deactivated::class.java) { s ->
            if (s.session.path == session && s.shortcutId == TALK) onRelease()
        }
        val shortcut = PortalShortcut(TALK, mapOf("description" to Variant(description), "preferred_trigger" to Variant(trigger(combo))))
        val asked = runCatching {
            portal.BindShortcuts(DBusPath(session), listOf(shortcut), "", mapOf("handle_token" to Variant(token + "b")))
        }.getOrNull() ?: return Outcome.FAILED
        // The desktop shows its dialog here: the user has time to read it.
        val bound = await(responses, asked.path, BIND_WAIT_S) ?: return Outcome.FAILED
        return when (bound.response.toInt()) {
            0 -> Outcome.BOUND
            1 -> Outcome.DECLINED
            else -> Outcome.FAILED
        }
    }

    fun unregister() {
        handlers.forEach { runCatching { it.close() } }
        handlers.clear()
        // The portal session lives as long as this connection.
        conn?.let { runCatching { it.close() } }
        conn = null
    }

    private fun await(responses: LinkedBlockingQueue<PortalRequest.Response>, path: String, seconds: Long): PortalRequest.Response? {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (true) {
            val left = until - System.nanoTime()
            if (left <= 0) return null
            val r = responses.poll(left, TimeUnit.NANOSECONDS) ?: return null
            if (r.path == path) return r
        }
    }

    companion object {
        const val DESKTOP_NAME = "org.freedesktop.portal.Desktop"
        const val DESKTOP_PATH = "/org/freedesktop/portal/desktop"
        const val IFACE = "org.freedesktop.portal.GlobalShortcuts"
        const val TALK = "talk"
        private const val CREATE_WAIT_S = 10L
        private const val BIND_WAIT_S = 180L

        /** [combo] as the XDG shortcuts spec writes a trigger: CTRL+ALT+space. */
        fun trigger(combo: KeyCombo): String {
            val mods = listOf(0x4 to "CTRL", 0x8 to "ALT", 0x1 to "SHIFT", 0x40 to "LOGO")
                .filter { (bit, _) -> combo.modifiers and bit != 0 }.map { it.second }
            val key = if (combo.key == 0x20) "space" else combo.key.toChar().toString()
            return (mods + key).joinToString("+")
        }
    }
}
