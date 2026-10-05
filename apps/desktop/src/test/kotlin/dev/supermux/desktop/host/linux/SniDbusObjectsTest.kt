package dev.supermux.desktop.host.linux

import dev.supermux.desktop.host.TrayAction
import dev.supermux.desktop.host.TrayItem
import dev.supermux.desktop.host.TrayToggle
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The exported objects, called directly (no bus). */
class SniDbusObjectsTest {
    private val items = listOf(
        TrayItem.Header("supermux is running"),
        TrayItem.Action(TrayAction.OPEN, "Open supermux"),
        TrayItem.Separator,
        TrayItem.Checkbox("Keep running in the background", checked = false, enabled = true, id = TrayToggle.BACKGROUND),
        TrayItem.Action(TrayAction.QUIT, "Quit supermux"),
    )

    private fun menu(onClick: (MenuEntry) -> Unit = {}): DbusMenuObject {
        val state = MenuState()
        state.update(items)
        return DbusMenuObject(state, onClick)
    }

    @Test fun getLayoutReturnsTheRootWithEveryRow() {
        val reply = menu().GetLayout(0, -1, emptyList())
        assertEquals(UInt32(2), reply.first)
        val root = reply.second
        assertEquals(0, root.id)
        assertEquals("submenu", root.props["children-display"]?.value)
        val kids = root.children.map { it.value as MenuLayoutStruct }
        assertEquals(listOf(1, 10, 100, 20, 13), kids.map { it.id })
        assertTrue(root.children.all { it.sig == "(ia{sv}av)" })
        assertEquals("checkmark", kids[3].props["toggle-type"]?.value)
        assertEquals(0, kids[3].props["toggle-state"]?.value)
        assertEquals("separator", kids[2].props["type"]?.value)
        assertEquals(false, kids[0].props["enabled"]?.value)
    }

    @Test fun getLayoutHonoursDepthPropertyNamesAndParent() {
        val m = menu()
        assertTrue(m.GetLayout(0, 0, emptyList()).second.children.isEmpty())
        val labelsOnly = m.GetLayout(0, -1, listOf("label")).second.children.map { it.value as MenuLayoutStruct }
        assertEquals(setOf("label"), labelsOnly.first().props.keys)
        val one = m.GetLayout(10, -1, emptyList()).second
        assertEquals(10, one.id)
        assertEquals("Open supermux", one.props["label"]?.value)
        assertFailsWith<DBusExecutionException> { m.GetLayout(999, -1, emptyList()) }
    }

    @Test fun propertiesAndGroups() {
        val m = menu()
        assertEquals(Variant("Quit supermux"), m.GetProperty(13, "label"))
        val group = m.GetGroupProperties(listOf(10, 20), emptyList())
        assertEquals(listOf(10, 20), group.map { it.id })
        assertEquals(5 + 1, m.GetGroupProperties(emptyList(), emptyList()).size, "all rows plus the root")
        assertEquals(UInt32(3), m.GetAll(DbusMenuObject.MENU_INTERFACE)["Version"]?.value)
        assertEquals("ltr", m.GetAll(DbusMenuObject.MENU_INTERFACE)["TextDirection"]?.value)
        assertEquals("normal", m.GetAll(DbusMenuObject.MENU_INTERFACE)["Status"]?.value)
    }

    @Test fun clickedDispatchesTheRowItWasShownFor() {
        val clicked = mutableListOf<TrayItem?>()
        val m = menu { clicked += it.item }
        m.Event(20, "clicked", Variant(""), UInt32(0))
        m.Event(13, "hovered", Variant(""), UInt32(0)) // not a click
        m.Event(999, "clicked", Variant(""), UInt32(0)) // unknown id
        assertEquals(listOf<TrayItem?>(items[3]), clicked)
        val errors = m.EventGroup(listOf(MenuEventStruct(13, "clicked", Variant(""), UInt32(0)), MenuEventStruct(7, "clicked", Variant(""), UInt32(0))))
        assertEquals(listOf(7), errors)
        assertEquals(listOf<TrayItem?>(items[3], items[4]), clicked)
        assertEquals(false, m.AboutToShow(0))
        assertEquals(listOf(999), m.AboutToShowGroup(listOf(0, 10, 999)).second)
    }

    @Test fun itemProperties() {
        val icon = SniIcon(1, 1, byteArrayOf(1, 2, 3, 4))
        val props = SniItemObject.itemProps(SniItemState(listOf(icon), tooltip = "2 sessions"))
        assertEquals("ApplicationStatus", props["Category"]?.value)
        assertEquals("supermux", props["Id"]?.value)
        assertEquals("supermux", props["Title"]?.value)
        assertEquals("Active", props["Status"]?.value)
        assertEquals(false, props["ItemIsMenu"]?.value)
        assertEquals(DBusPath("/MenuBar"), props["Menu"]?.value)
        assertEquals("a(iiay)", props["IconPixmap"]?.sig)
        assertEquals("(sa(iiay)ss)", props["ToolTip"]?.sig)
        assertEquals("2 sessions", (props["ToolTip"]?.value as SniToolTipStruct).description)
        val px = (props["IconPixmap"]?.value as List<*>).single() as SniPixmapStruct
        assertEquals(1, px.width)
    }

    @Test fun activateCallsBack() {
        var n = 0
        SniItemObject({ SniItemState() }, { n++ }).Activate(0, 0)
        assertEquals(1, n)
    }

    @Test fun notificationsAreEscapedAndCarryTheAppIdentity() {
        assertEquals("1 &lt; 2 &amp;&amp; 3 &gt; 2", DesktopNotification.escapeBody("1 < 2 && 3 > 2"))
        val n = DesktopNotification.of("a <title>", "x<y", DesktopNotification.Identity("supermux-supermux", "/i.png"))
        assertEquals(DesktopNotification("a <title>", "x&lt;y", "/i.png", "supermux-supermux"), n)
        val installed = DesktopNotification.identity("/opt/supermux/lib/runtime") { it == Path.of("/opt/supermux/lib/supermux.png") }
        assertEquals(DesktopNotification.Identity("supermux-supermux", "/opt/supermux/lib/supermux.png"), installed)
        assertEquals("", DesktopNotification.identity("/usr/lib/jvm/x") { false }.appIcon)
    }

    @Test fun sessionBusAddress() {
        val none: (Path) -> Boolean = { false }
        val some: (Path) -> Boolean = { true }
        assertEquals(
            "unix:path=/run/user/1000/bus",
            SessionBus.address(mapOf("DBUS_SESSION_BUS_ADDRESS" to "unix:path=/run/user/1000/bus"), none),
        )
        // An abstract socket can't be dialled with JDK sockets: fall back to XDG_RUNTIME_DIR.
        assertEquals(
            "unix:path=/run/user/7/bus",
            SessionBus.address(mapOf("DBUS_SESSION_BUS_ADDRESS" to "unix:abstract=/tmp/dbus-x", "XDG_RUNTIME_DIR" to "/run/user/7"), some),
        )
        assertEquals(
            "unix:path=/tmp/b,guid=1",
            SessionBus.address(mapOf("DBUS_SESSION_BUS_ADDRESS" to "unix:abstract=/x;unix:path=/tmp/b,guid=1"), none),
        )
        assertNull(SessionBus.address(mapOf("XDG_RUNTIME_DIR" to "/run/user/7"), none))
        assertNull(SessionBus.address(emptyMap(), some))
    }
}
