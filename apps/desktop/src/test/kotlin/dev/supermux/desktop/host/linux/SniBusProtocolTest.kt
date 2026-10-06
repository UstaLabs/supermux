@file:Suppress("FunctionName")

package dev.supermux.desktop.host.linux

import dev.supermux.desktop.host.TrayAction
import dev.supermux.desktop.host.TrayDispatcher
import dev.supermux.desktop.host.TrayItem
import dev.supermux.desktop.host.TrayToggle
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Marshalling
import org.freedesktop.dbus.bin.EmbeddedDBusDaemon
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tray over a REAL D-Bus wire, hermetically: dbus-java's EmbeddedDBusDaemon on a unix socket in
 * a temp dir, a fake StatusNotifierWatcher on it, and a second connection playing the tray host.
 * No session bus, no desktop. Skipped where the JDK can't bind a unix socket (Windows).
 */
class SniBusProtocolTest {
    /** The watcher a tray host (GNOME's AppIndicator extension, KDE) would own. */
    class FakeWatcher : StatusNotifierWatcher, Properties {
        val registered = CopyOnWriteArrayList<String>()
        val gotOne = CountDownLatch(1)

        override fun getObjectPath() = DbusJavaSniBus.WATCHER_PATH
        override fun RegisterStatusNotifierItem(service: String) {
            registered += service
            gotOne.countDown()
        }

        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A = Variant(true) as A
        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) = Unit
        override fun GetAll(interfaceName: String): Map<String, Variant<*>> =
            mapOf("IsStatusNotifierHostRegistered" to Variant(true))
    }

    private val cleanup = mutableListOf<AutoCloseable>()

    @AfterTest fun tearDown() = cleanup.asReversed().forEach { runCatching { it.close() } }

    private val items = listOf(
        TrayItem.Header("supermux is running"),
        TrayItem.Action(TrayAction.OPEN, "Open supermux"),
        TrayItem.Separator,
        TrayItem.Checkbox("Keep running in the background", checked = false, enabled = true),
        TrayItem.Action(TrayAction.QUIT, "Quit supermux"),
    )

    private fun connect(address: String): DBusConnection =
        DBusConnectionBuilder.forAddress(address).withShared(false).build().also { cleanup += it }

    @Test fun theTrayOverARealBus() {
        assumeTrue("unix sockets", !System.getProperty("os.name").lowercase().contains("windows"))
        val dir = Files.createTempDirectory("sni-bus")
        val address = "unix:path=${dir.resolve("bus")}"
        val daemon = EmbeddedDBusDaemon("$address,listen=true")
        cleanup += daemon
        val started = runCatching { daemon.startInBackgroundAndWait(10_000) }
        assumeTrue("EmbeddedDBusDaemon could not start: ${started.exceptionOrNull()}", started.isSuccess)

        val watcher = FakeWatcher()
        connect(address).apply {
            exportObject(DbusJavaSniBus.WATCHER_PATH, watcher)
            requestBusName(DbusJavaSniBus.WATCHER_NAME)
        }

        val toggles = CopyOnWriteArrayList<Pair<TrayToggle, Boolean>>()
        val toggled = CountDownLatch(1)
        val icon = SniIcon(1, 1, byteArrayOf(0x7F, 1, 2, 3))
        val tray = SniTray(
            busFactory = { DbusJavaSniBus.connect(mapOf("DBUS_SESSION_BUS_ADDRESS" to address)) },
            ui = { it.run() },
            icons = { listOf(icon) },
            pid = 4242,
        )
        cleanup += AutoCloseable { tray.close() }
        tray.dispatcher = TrayDispatcher({}, { k, on -> toggles += k to on; toggled.countDown() })
        tray.start()
        tray.update(items, "2 sessions")

        // Registration reaches the watcher with our well-known name.
        assertTrue(watcher.gotOne.await(10, TimeUnit.SECONDS), "never registered")
        assertEquals(listOf("org.kde.StatusNotifierItem-4242-1"), watcher.registered.toList())
        val deadline = System.currentTimeMillis() + 5_000
        while (tray.status.value != SniStatus.REGISTERED && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(SniStatus.REGISTERED, tray.status.value)

        // The host side.
        val host = connect(address)
        val name = tray.busName

        // GetLayout, raw: the reply's wire signature and its contents.
        val call = host.messageFactory.createMethodCall(
            name, DbusMenuObject.MENU_PATH, DbusMenuObject.MENU_INTERFACE, "GetLayout", 0.toByte(), "iias", 0, -1, emptyList<String>(),
        )
        host.sendMessage(call)
        val reply = call.getReply(5_000)
        assertEquals("u(ia{sv}av)", reply.sig)
        val params = reply.parameters
        assertEquals(UInt32(2), params[0])
        val root = params[1] as Array<*>
        assertEquals(0, root[0])
        val kids = (root[2] as List<*>).map { (it as Variant<*>).value as Array<*> }
        assertEquals(listOf(1, 10, 100, 20, 13), kids.map { it[0] })
        @Suppress("UNCHECKED_CAST")
        val toggleProps = (kids[3][1] as Map<String, Variant<*>>).mapValues { it.value.value }
        assertEquals(
            mapOf("label" to "Keep running in the background", "enabled" to true, "toggle-type" to "checkmark", "toggle-state" to 0),
            toggleProps,
        )

        // GetAll on both objects.
        val sni = host.getRemoteObject(name, SniItemObject.SNI_PATH, Properties::class.java).GetAll(SniItemObject.SNI_INTERFACE)
        assertEquals("ApplicationStatus", sni["Category"]?.value)
        assertEquals("supermux", sni["Id"]?.value)
        assertEquals("Active", sni["Status"]?.value)
        assertEquals(false, sni["ItemIsMenu"]?.value)
        assertEquals(DBusPath(DbusMenuObject.MENU_PATH).path, (sni["Menu"]?.value as DBusPath).path)
        assertEquals("a(iiay)", sni["IconPixmap"]?.sig)
        val px = (sni["IconPixmap"]?.value as List<*>).single() as Array<*>
        assertEquals(listOf<Any?>(1, 1), px.take(2))
        // `ay` inside a variant comes back as a List<Byte> or a ByteArray, depending on the path.
        val bytes = when (val raw = px[2]) {
            is ByteArray -> raw
            is List<*> -> ByteArray(raw.size) { raw[it] as Byte }
            else -> error("unexpected ay: $raw")
        }
        assertContentEquals(icon.argb, bytes)
        assertEquals("(sa(iiay)ss)", sni["ToolTip"]?.sig)
        val menuProps = host.getRemoteObject(name, DbusMenuObject.MENU_PATH, Properties::class.java).GetAll(DbusMenuObject.MENU_INTERFACE)
        assertEquals(UInt32(3), menuProps["Version"]?.value)
        assertEquals("ltr", menuProps["TextDirection"]?.value)
        assertEquals("normal", menuProps["Status"]?.value)

        // Signals on a menu change: a checkbox flips → ItemsPropertiesUpdated, then LayoutUpdated.
        val layoutUpdated = CountDownLatch(1)
        val propsUpdated = CountDownLatch(1)
        val revisions = CopyOnWriteArrayList<UInt32>()
        val updatedIds = CopyOnWriteArrayList<Int>()
        cleanup += host.addSigHandler(DbusMenu.LayoutUpdated::class.java) { s -> revisions += s.revision; layoutUpdated.countDown() }
        cleanup += host.addSigHandler(DbusMenu.ItemsPropertiesUpdated::class.java) { s ->
            updatedIds += s.updatedProps.map { it.id }
            propsUpdated.countDown()
        }
        tray.update(items.map { if (it is TrayItem.Checkbox) it.copy(checked = true) else it }, "2 sessions")
        assertTrue(propsUpdated.await(5, TimeUnit.SECONDS), "no ItemsPropertiesUpdated")
        assertTrue(layoutUpdated.await(5, TimeUnit.SECONDS), "no LayoutUpdated")
        assertEquals(listOf(MenuIds.toggle(TrayToggle.BACKGROUND)), updatedIds.toList())
        assertEquals(listOf(UInt32(3)), revisions.toList())

        // Event "clicked" from the host dispatches the row (it now shows checked → toggles off).
        host.getRemoteObject(name, DbusMenuObject.MENU_PATH, DbusMenu::class.java)
            .Event(MenuIds.toggle(TrayToggle.BACKGROUND), "clicked", Variant(""), UInt32(0))
        assertTrue(toggled.await(5, TimeUnit.SECONDS), "click not dispatched")
        assertEquals(listOf(TrayToggle.BACKGROUND to false), toggles.toList())

        // Close releases the name.
        tray.close()
        val dbus = host.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", org.freedesktop.dbus.interfaces.DBus::class.java)
        assertTrue(!dbus.NameHasOwner(name), "name still owned after close")
    }

    /** The signatures dbus-java derives for our Kotlin declarations (what goes on the wire). */
    @Test fun marshalledSignatures() {
        fun sig(t: java.lang.reflect.Type) = Marshalling.getDBusType(t).toList()
        val menu = DbusMenu::class.java.methods.associateBy { it.name }
        assertEquals(listOf("u", "(ia{sv}av)"), sig(menu.getValue("GetLayout").genericReturnType))
        assertEquals(listOf("i", "i", "as"), menu.getValue("GetLayout").genericParameterTypes.flatMap { sig(it) })
        assertEquals(listOf("a(ia{sv})"), sig(menu.getValue("GetGroupProperties").genericReturnType))
        assertEquals(listOf("ai", "as"), menu.getValue("GetGroupProperties").genericParameterTypes.flatMap { sig(it) })
        assertEquals(listOf("v"), sig(menu.getValue("GetProperty").genericReturnType))
        assertEquals(listOf("i", "s", "v", "u"), menu.getValue("Event").genericParameterTypes.flatMap { sig(it) })
        assertEquals(listOf("a(isvu)"), menu.getValue("EventGroup").genericParameterTypes.flatMap { sig(it) })
        assertEquals(listOf("ai"), sig(menu.getValue("EventGroup").genericReturnType))
        assertEquals(listOf("ai", "ai"), sig(menu.getValue("AboutToShowGroup").genericReturnType))
        assertEquals(listOf("(ia{sv}av)"), sig(MenuLayoutStruct::class.java))
        assertEquals(listOf("(sa(iiay)ss)"), sig(SniToolTipStruct::class.java))
        assertEquals(listOf("(iiay)"), sig(SniPixmapStruct::class.java))
        val sni = StatusNotifierItem::class.java.methods.associateBy { it.name }
        assertEquals(listOf("i", "i"), sni.getValue("Activate").genericParameterTypes.flatMap { sig(it) })
        assertEquals(listOf("i", "s"), sni.getValue("Scroll").genericParameterTypes.flatMap { sig(it) })
    }
}
