// Linux Wayland: the overlay's hold-to-talk key from any app (#62), through the GlobalShortcuts portal
// (xdg-desktop-portal; GNOME 48+, KDE Plasma 5.27+). Wayland gives no app a key grab of its own: the desktop
// asks the user once to confirm the shortcut, then reports its press (Activated) and release (Deactivated).
package dev.supermux.desktop.overlay

import dev.supermux.desktop.host.linux.DesktopNotification
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
import java.util.concurrent.atomic.AtomicInteger

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

/** Where an app outside any sandbox tells the portal which it is: its launcher entry's id, no `.desktop`. */
@JvmSuppressWildcards
@DBusInterfaceName("org.freedesktop.host.portal.Registry")
interface PortalRegistry : DBusInterface {
    fun Register(appId: String, options: Map<String, Variant<*>>)
}

@JvmSuppressWildcards
@DBusInterfaceName("org.freedesktop.portal.Request")
interface PortalRequest : DBusInterface {
    class Response(path: String, val response: UInt32, val results: Map<String, Variant<*>>) : DBusSignal(path, response, results)
}

/**
 * The hold-to-talk key on Wayland. [register] asks the desktop for [KeyCombo] as a global shortcut, and [onOutcome]
 * says what came of it: [Outcome.ASKING] while the desktop's dialog waits for the user, however long that takes,
 * then its answer. From then on [onPress] and [onRelease] come on a bus thread, as [onOutcome]'s answers do. [env]
 * gives the session bus; [appId] is the launcher entry the portal knows the app by.
 */
internal class WaylandHotKey(
    private val onPress: () -> Unit,
    private val onRelease: () -> Unit,
    private val onOutcome: (Outcome) -> Unit,
    private val env: Map<String, String> = System.getenv(),
    private val appId: String = DesktopNotification.DESKTOP_ENTRY,
) {
    enum class Outcome { ASKING, BOUND, NO_PORTAL, DECLINED, FAILED }

    private var conn: DBusConnection? = null
    private val handlers = mutableListOf<AutoCloseable>()
    /** Which asking is the latest: an answer to an earlier one is no longer news. */
    private val asking = AtomicInteger()

    /** Asks for [combo]; returns once the desktop's dialog is up, its answer comes to [onOutcome] later. */
    fun register(combo: KeyCombo, description: String) {
        unregister()
        val mine = asking.get()
        val tell = { outcome: Outcome -> if (asking.get() == mine) onOutcome(outcome) }
        val address = SessionBus.address(env) ?: return tell(Outcome.NO_PORTAL)
        val c = runCatching { DBusConnectionBuilder.forAddress(address).withShared(false).build() }.getOrNull()
            ?: return tell(Outcome.NO_PORTAL)
        conn = c
        // A recent xdg-desktop-portal (1.21 checked) gives the key only to an app it can name ("An app id is
        // required"). Started from the desktop's launcher, the app's systemd scope names it; from a terminal nothing
        // does, so the app names its launcher entry, first on this connection. A portal without the registry, or
        // without that entry installed (not the .deb), says no, and asking goes on.
        runCatching { c.getRemoteObject(DESKTOP_NAME, DESKTOP_PATH, PortalRegistry::class.java).Register(appId, emptyMap()) }
        // No GlobalShortcuts interface (GNOME before 48, older KDE): nothing to ask.
        val props = c.getRemoteObject(DESKTOP_NAME, DESKTOP_PATH, Properties::class.java)
        if (runCatching { props.Get<Any?>(IFACE, "version") }.isFailure) return tell(Outcome.NO_PORTAL)
        val token = "supermux${System.nanoTime()}"
        val responses = LinkedBlockingQueue<PortalRequest.Response>()
        // Listening before asking: an answer may come before its call returns. The dialog's answer is taken
        // whenever the user gives it: a clock on it would call a slow yes a failure.
        handlers += c.addSigHandler(PortalRequest.Response::class.java) { r ->
            if (r.path.endsWith("/${token}b")) tell(answered(r.response.toInt())) else responses.add(r)
        }
        val portal = c.getRemoteObject(DESKTOP_NAME, DESKTOP_PATH, PortalGlobalShortcuts::class.java)
        val created = runCatching {
            portal.CreateSession(mapOf("handle_token" to Variant(token + "c"), "session_handle_token" to Variant(token + "s")))
        }.getOrNull() ?: return tell(Outcome.FAILED)
        // Opening a session asks the user nothing: a portal that doesn't answer it soon won't.
        val opened = await(responses, created.path, CREATE_WAIT_S) ?: return tell(Outcome.FAILED)
        if (opened.response.toInt() != 0) return tell(Outcome.FAILED)
        val session = opened.results["session_handle"]?.value?.toString() ?: return tell(Outcome.FAILED)
        handlers += c.addSigHandler(PortalGlobalShortcuts.Activated::class.java) { s ->
            if (s.session.path == session && s.shortcutId == TALK) onPress()
        }
        handlers += c.addSigHandler(PortalGlobalShortcuts.Deactivated::class.java) { s ->
            if (s.session.path == session && s.shortcutId == TALK) onRelease()
        }
        val shortcut = PortalShortcut(TALK, mapOf("description" to Variant(description), "preferred_trigger" to Variant(trigger(combo))))
        // Said before asking, so the answer can't come first.
        tell(Outcome.ASKING)
        runCatching {
            portal.BindShortcuts(DBusPath(session), listOf(shortcut), "", mapOf("handle_token" to Variant(token + "b")))
        }.onFailure { tell(Outcome.FAILED) }
    }

    fun unregister() {
        asking.incrementAndGet()
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

        /** The dialog's answer: 0 yes, 1 the user said no, anything else it ended some other way. */
        fun answered(response: Int): Outcome = when (response) {
            0 -> Outcome.BOUND
            1 -> Outcome.DECLINED
            else -> Outcome.FAILED
        }

        /** [combo] as the XDG shortcuts spec writes a trigger: CTRL+ALT+space. */
        fun trigger(combo: KeyCombo): String {
            val mods = listOf(0x4 to "CTRL", 0x8 to "ALT", 0x1 to "SHIFT", 0x40 to "LOGO")
                .filter { (bit, _) -> combo.modifiers and bit != 0 }.map { it.second }
            val key = if (combo.key == 0x20) "space" else combo.key.toChar().toString()
            return (mods + key).joinToString("+")
        }
    }
}
