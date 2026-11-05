package dev.supermux.desktop.overlay

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.AbstractConnection
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.UInt64
import org.freedesktop.dbus.types.Variant
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Wayland hold-to-talk key (#62) against a stand-in GlobalShortcuts portal on a private session bus: what
 * the client asks for, and how the desktop's answers and the shortcut's press and release come back.
 */
class WaylandHotKeyTest {
    private val ctrlAltSpace = KeyCombo("Ctrl+Alt+Space", 0x4 or 0x8, 0x20)
    private var daemon: Process? = null
    private var socket: File? = null
    private lateinit var env: Map<String, String>

    @BeforeTest fun bus() {
        val dbusDaemon = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "dbus-daemon") }.firstOrNull { it.canExecute() }
        assumeTrue("needs dbus-daemon", dbusDaemon != null)
        socket = File.createTempFile("overlay-bus", ".sock").also { it.delete() }
        daemon = ProcessBuilder(dbusDaemon!!.path, "--session", "--nofork", "--address=unix:path=${socket!!.path}", "--print-address=1").start()
        env = mapOf("DBUS_SESSION_BUS_ADDRESS" to daemon!!.inputStream.bufferedReader().readLine())
    }

    @AfterTest fun stop() {
        daemon?.destroy()
        socket?.delete()
    }

    @Test fun aTriggerReadsAsTheShortcutsSpecWritesIt() {
        assertEquals("CTRL+ALT+space", WaylandHotKey.trigger(ctrlAltSpace))
        assertEquals("ALT+space", WaylandHotKey.trigger(KeyCombo("Alt+Space", 0x8, 0x20)))
        assertEquals("CTRL+SHIFT+space", WaylandHotKey.trigger(KeyCombo("Ctrl+Shift+Space", 0x4 or 0x1, 0x20)))
    }

    // GNOME before 48, older KDE: no GlobalShortcuts, and Settings says so.
    @Test fun withoutThePortalThereIsNothingToAsk() =
        assertEquals(WaylandHotKey.Outcome.NO_PORTAL, WaylandHotKey({}, {}, env).register(ctrlAltSpace, "Hold to talk"))

    @Test fun theDesktopBindsItAndItsPressAndReleaseComeBack() = withPortal(answer = 0) { portal ->
        val events = Collections.synchronizedList(mutableListOf<String>())
        val key = WaylandHotKey({ events += "press" }, { events += "release" }, env)
        assertEquals(WaylandHotKey.Outcome.BOUND, key.register(ctrlAltSpace, "Hold to talk to Anya"))
        assertEquals(listOf("talk"), portal.asked.map { it.id })
        assertEquals("CTRL+ALT+space", portal.asked.single().properties["preferred_trigger"]?.value)
        assertEquals("Hold to talk to Anya", portal.asked.single().properties["description"]?.value)
        portal.fire(down = true)
        portal.fire(down = false)
        waitFor { events.size == 2 }
        assertEquals(listOf("press", "release"), events.toList())
        key.unregister()
    }

    @Test fun theUserCanSayNo() = withPortal(answer = 1) {
        assertEquals(WaylandHotKey.Outcome.DECLINED, WaylandHotKey({}, {}, env).register(ctrlAltSpace, "Hold to talk"))
    }

    private fun withPortal(answer: Int, body: (FakePortal) -> Unit) {
        val conn = DBusConnectionBuilder.forAddress(env.getValue("DBUS_SESSION_BUS_ADDRESS")).withShared(false).build()
        try {
            val portal = FakePortal(conn, answer)
            conn.exportObject(WaylandHotKey.DESKTOP_PATH, portal)
            conn.requestBusName(WaylandHotKey.DESKTOP_NAME)
            body(portal)
        } finally {
            conn.close()
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < until) Thread.sleep(20)
    }

    /** Answers like xdg-desktop-portal does (a Response on the request path), and fires the shortcut on demand. */
    class FakePortal(private val conn: DBusConnection, private val bindAnswer: Int) : PortalGlobalShortcuts, Properties {
        val asked = Collections.synchronizedList(mutableListOf<PortalShortcut>())
        @Volatile var session = ""

        override fun getObjectPath() = WaylandHotKey.DESKTOP_PATH

        private fun sender() = AbstractConnection.getCallInfo().source.removePrefix(":").replace('.', '_')

        private fun answer(path: String, code: Int, results: Map<String, Variant<*>>) {
            thread { Thread.sleep(30); conn.sendMessage(PortalRequest.Response(path, UInt32(code.toLong()), results)) }
        }

        override fun CreateSession(options: Map<String, Variant<*>>): DBusPath {
            val s = sender()
            val path = "/org/freedesktop/portal/desktop/request/$s/${options["handle_token"]!!.value}"
            session = "/org/freedesktop/portal/desktop/session/$s/${options["session_handle_token"]!!.value}"
            answer(path, 0, mapOf("session_handle" to Variant(session)))
            return DBusPath(path)
        }

        override fun BindShortcuts(session: DBusPath, shortcuts: List<PortalShortcut>, parentWindow: String, options: Map<String, Variant<*>>): DBusPath {
            asked += shortcuts
            val path = "/org/freedesktop/portal/desktop/request/${sender()}/${options["handle_token"]!!.value}"
            answer(path, bindAnswer, emptyMap())
            return DBusPath(path)
        }

        fun fire(down: Boolean) {
            val at = UInt64(System.currentTimeMillis())
            conn.sendMessage(
                if (down) PortalGlobalShortcuts.Activated(WaylandHotKey.DESKTOP_PATH, DBusPath(session), "talk", at, emptyMap())
                else PortalGlobalShortcuts.Deactivated(WaylandHotKey.DESKTOP_PATH, DBusPath(session), "talk", at, emptyMap()),
            )
        }

        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(iface: String, prop: String): A = (if (prop == "version") UInt32(1) else error("no $prop")) as A

        override fun <A : Any?> Set(iface: String, prop: String, value: A) = Unit

        override fun GetAll(iface: String): Map<String, Variant<*>> = mapOf("version" to Variant(UInt32(1)))
    }
}
