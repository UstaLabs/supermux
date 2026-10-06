package dev.supermux.desktop.host.linux

import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.matchrules.DBusMatchRuleBuilder
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.nio.file.Files
import java.nio.file.Path

/** A signal the tray sends, as plain data (so a fake [SniBus] can record it). */
sealed interface SniSignal {
    data object NewIcon : SniSignal
    data object NewToolTip : SniSignal
    data class NewStatus(val status: String) : SniSignal
    data class LayoutUpdated(val revision: Int) : SniSignal
    data class ItemsPropertiesUpdated(val updated: List<MenuEntry>) : SniSignal
}

/**
 * The session bus, as far as the tray needs it. [DbusJavaSniBus] is the real one; tests inject a
 * fake. Every call may throw; [SniTray] calls them all from its own thread and catches.
 */
interface SniBus : AutoCloseable {
    /** Export [objects] (path → object), then own [busName]. */
    fun publish(busName: String, objects: Map<String, DBusInterface>)

    /** Whether [name] has an owner on the bus. */
    fun hasOwner(name: String): Boolean

    /** Calls [cb] (on a bus thread) with whether [name] now has an owner, whenever that changes. */
    fun watchOwner(name: String, cb: (owned: Boolean) -> Unit)

    /** `RegisterStatusNotifierItem(busName)` on the watcher. */
    fun registerItem(busName: String)

    /** The watcher's `IsStatusNotifierHostRegistered`; true when it can't be read. */
    fun hostRegistered(): Boolean

    /** Calls [cb] (on a bus thread) on the watcher's `StatusNotifierHostRegistered`. */
    fun watchHostRegistered(cb: () -> Unit)

    fun emit(signal: SniSignal)

    /** A desktop notification through `org.freedesktop.Notifications`. */
    fun notify(n: DesktopNotification)

    /** Release the name and close the connection. */
    override fun close()
}

/** One `org.freedesktop.Notifications.Notify` call, already escaped. */
data class DesktopNotification(
    val summary: String,
    val body: String,
    val appIcon: String,
    val desktopEntry: String,
) {
    /** Who sends it: the .desktop id (no suffix) the shell attributes it to, and an icon file. */
    data class Identity(val desktopEntry: String, val appIcon: String)

    companion object {
        /**
         * The .desktop jpackage's deb installs (`/opt/supermux/lib/supermux-supermux.desktop`,
         * registered through xdg-desktop-menu under that name).
         */
        const val DESKTOP_ENTRY = "supermux-supermux"

        /**
         * Pure: escaped for the body, which servers may render as markup (`&`, `<`, `>`); the
         * summary is plain text per the spec.
         */
        fun escapeBody(text: String): String =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        fun of(title: String, message: String, id: Identity): DesktopNotification =
            DesktopNotification(title, escapeBody(message), id.appIcon, id.desktopEntry)

        /**
         * The installed app's identity: the icon jpackage puts beside the runtime
         * (`<java.home>/../supermux.png`) when it is there ([exists]), else none.
         */
        fun identity(
            javaHome: String? = System.getProperty("java.home"),
            exists: (Path) -> Boolean = Files::exists,
        ): Identity {
            val icon = javaHome?.let { runCatching { Path.of(it).parent?.resolve("supermux.png") }.getOrNull() }
                ?.takeIf(exists)
            return Identity(DESKTOP_ENTRY, icon?.toString() ?: "")
        }
    }
}

object SessionBus {
    /**
     * Pure: the session bus address to dial. The first `unix:path=` entry of
     * `DBUS_SESSION_BUS_ADDRESS` (the JDK socket transport can't reach an abstract socket), else
     * `$XDG_RUNTIME_DIR/bus` when that socket exists ([exists]); null when there is neither.
     */
    fun address(env: Map<String, String>, exists: (Path) -> Boolean = Files::exists): String? {
        env["DBUS_SESSION_BUS_ADDRESS"]?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("unix:") && it.split(':', limit = 2)[1].split(',').any { kv -> kv.startsWith("path=") } }
            ?.let { return it }
        val runtime = env["XDG_RUNTIME_DIR"]?.takeIf { it.isNotBlank() } ?: return null
        val bus = Path.of(runtime, "bus")
        return if (exists(bus)) "unix:path=$bus" else null
    }
}

/** [SniBus] over dbus-java (JDK unix sockets; no native code). */
class DbusJavaSniBus private constructor(private val conn: DBusConnection) : SniBus {
    private var ownedName: String? = null
    private val exported = mutableListOf<String>()
    private val handlers = mutableListOf<AutoCloseable>()

    override fun publish(busName: String, objects: Map<String, DBusInterface>) {
        for ((path, obj) in objects) {
            conn.exportObject(path, obj)
            exported += path
        }
        conn.requestBusName(busName)
        ownedName = busName
    }

    private fun dbus(): DBus = conn.getRemoteObject(DBUS_NAME, DBUS_PATH, DBus::class.java)

    override fun hasOwner(name: String): Boolean = dbus().NameHasOwner(name)

    override fun watchOwner(name: String, cb: (owned: Boolean) -> Unit) {
        // arg0 = the name: the bus delivers only that name's changes, not every client's churn.
        val rule = DBusMatchRuleBuilder.create()
            .withType(DBus.NameOwnerChanged::class.java)
            .withSender(DBUS_NAME)
            .withArg0123(0, name)
            .build()
        handlers += conn.addSigHandler<DBus.NameOwnerChanged>(rule) { s ->
            if (s.name == name) cb(s.newOwner.isNotEmpty())
        }
    }

    override fun registerItem(busName: String) {
        conn.getRemoteObject(WATCHER_NAME, WATCHER_PATH, StatusNotifierWatcher::class.java)
            .RegisterStatusNotifierItem(busName)
    }

    override fun hostRegistered(): Boolean = runCatching {
        val v: Any? = conn.getRemoteObject(WATCHER_NAME, WATCHER_PATH, Properties::class.java)
            .Get<Any?>(WATCHER_NAME, "IsStatusNotifierHostRegistered")
        ((v as? Variant<*>)?.value ?: v) as? Boolean ?: true
    }.getOrDefault(true)

    override fun watchHostRegistered(cb: () -> Unit) {
        handlers += conn.addSigHandler(StatusNotifierWatcher.StatusNotifierHostRegistered::class.java) { cb() }
    }

    override fun emit(signal: SniSignal) {
        val sni = SniItemObject.SNI_PATH
        val menu = DbusMenuObject.MENU_PATH
        conn.sendMessage(
            when (signal) {
                SniSignal.NewIcon -> StatusNotifierItem.NewIcon(sni)
                SniSignal.NewToolTip -> StatusNotifierItem.NewToolTip(sni)
                is SniSignal.NewStatus -> StatusNotifierItem.NewStatus(sni, signal.status)
                is SniSignal.LayoutUpdated -> DbusMenu.LayoutUpdated(menu, UInt32(signal.revision.toLong()), 0)
                is SniSignal.ItemsPropertiesUpdated ->
                    DbusMenu.ItemsPropertiesUpdated(menu, DbusMenuObject.itemProps(signal.updated), emptyList())
            },
        )
    }

    override fun notify(n: DesktopNotification) {
        val hints = if (n.desktopEntry.isEmpty()) emptyMap() else mapOf<String, Variant<*>>("desktop-entry" to Variant(n.desktopEntry))
        conn.getRemoteObject(NOTIFY_NAME, NOTIFY_PATH, FreedesktopNotifications::class.java)
            .Notify("supermux", UInt32(0), n.appIcon, n.summary, n.body, emptyList(), hints, -1)
    }

    override fun close() {
        handlers.forEach { runCatching { it.close() } }
        ownedName?.let { n -> runCatching { conn.releaseBusName(n) } }
        exported.forEach { p -> runCatching { conn.unExportObject(p) } }
        runCatching { conn.close() }
    }

    companion object {
        const val DBUS_NAME = "org.freedesktop.DBus"
        const val DBUS_PATH = "/org/freedesktop/DBus"
        const val WATCHER_NAME = "org.kde.StatusNotifierWatcher"
        const val WATCHER_PATH = "/StatusNotifierWatcher"
        const val NOTIFY_NAME = "org.freedesktop.Notifications"
        const val NOTIFY_PATH = "/org/freedesktop/Notifications"

        /** Dial the session bus. Throws when there is none or it can't be reached. */
        fun connect(env: Map<String, String> = System.getenv()): DbusJavaSniBus {
            val address = SessionBus.address(env) ?: error("no session bus (DBUS_SESSION_BUS_ADDRESS / XDG_RUNTIME_DIR)")
            // Not shared: dbus-java would otherwise hand the same connection to any other caller.
            return DbusJavaSniBus(DBusConnectionBuilder.forAddress(address).withShared(false).build())
        }
    }
}
