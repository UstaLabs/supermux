package dev.supermux.desktop.overlay

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.AbstractConnection
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusExecutionException
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
    private var config: File? = null
    private lateinit var env: Map<String, String>

    @BeforeTest fun bus() {
        val dbusDaemon = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "dbus-daemon") }.firstOrNull { it.canExecute() }
        assumeTrue("needs dbus-daemon", dbusDaemon != null)
        socket = File.createTempFile("overlay-bus", ".sock").also { it.delete() }
        // A bare bus with no service directories: with the stock session config, asking for the portal
        // starts this machine's own xdg-desktop-portal, and the test meets a real desktop's answers.
        config = File.createTempFile("overlay-bus", ".conf").apply {
            writeText(
                """
                <busconfig>
                  <type>session</type>
                  <listen>unix:path=${socket!!.path}</listen>
                  <auth>EXTERNAL</auth>
                  <policy context="default">
                    <allow send_destination="*" eavesdrop="true"/>
                    <allow eavesdrop="true"/>
                    <allow own="*"/>
                  </policy>
                </busconfig>
                """.trimIndent(),
            )
        }
        daemon = ProcessBuilder(dbusDaemon!!.path, "--config-file=${config!!.path}", "--nofork", "--print-address=1").start()
        env = mapOf("DBUS_SESSION_BUS_ADDRESS" to daemon!!.inputStream.bufferedReader().readLine())
    }

    @AfterTest fun stop() {
        daemon?.destroy()
        socket?.delete()
        config?.delete()
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

    // A recent xdg-desktop-portal (1.21) gives the key only to an app it can name. Started from the desktop's
    // launcher, the app's systemd scope names it; from a terminal nothing does, unless the app names its launcher entry.
    @Test fun theAppNamesItsLauncherEntrySoADesktopThatMustKnowWhoAsksBindsIt() = withPortal(answer = 0, namesApps = true) { portal ->
        assertEquals(WaylandHotKey.Outcome.BOUND, WaylandHotKey({}, {}, env).register(ctrlAltSpace, "Hold to talk"))
        assertEquals(listOf("supermux-supermux"), (portal as NamingPortal).registered.values.toList())
    }

    private fun withPortal(answer: Int, namesApps: Boolean = false, body: (FakePortal) -> Unit) {
        val conn = DBusConnectionBuilder.forAddress(env.getValue("DBUS_SESSION_BUS_ADDRESS")).withShared(false).build()
        try {
            val portal = if (namesApps) NamingPortal(conn, answer) else FakePortal(conn, answer)
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

    /**
     * Answers like xdg-desktop-portal does (a Response on the request path), and fires the shortcut on demand. Like
     * an older portal it has no app registry, and doesn't ask who an app is.
     */
    open class FakePortal(private val conn: DBusConnection, private val bindAnswer: Int) : PortalGlobalShortcuts, Properties {
        val asked = Collections.synchronizedList(mutableListOf<PortalShortcut>())
        @Volatile var session = ""

        override fun getObjectPath() = WaylandHotKey.DESKTOP_PATH

        protected fun sender() = AbstractConnection.getCallInfo().source.removePrefix(":").replace('.', '_')

        /** Whether the caller may open a session at all. */
        protected open fun admit() = Unit

        private fun answer(path: String, code: Int, results: Map<String, Variant<*>>) {
            thread { Thread.sleep(30); conn.sendMessage(PortalRequest.Response(path, UInt32(code.toLong()), results)) }
        }

        override fun CreateSession(options: Map<String, Variant<*>>): DBusPath {
            admit()
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

    /**
     * A recent portal (1.21), to an app outside a sandbox: one it can't name from its systemd scope (started from a
     * terminal) is told "An app id is required", unless it first registered its launcher entry.
     */
    class NamingPortal(conn: DBusConnection, bindAnswer: Int) : FakePortal(conn, bindAnswer), PortalRegistry {
        val registered: MutableMap<String, String> = Collections.synchronizedMap(linkedMapOf())

        override fun Register(appId: String, options: Map<String, Variant<*>>) {
            registered[sender()] = appId
        }

        override fun admit() {
            if (sender() !in registered) throw DBusExecutionException("An app id is required")
        }
    }
}
