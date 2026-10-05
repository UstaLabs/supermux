@file:Suppress("FunctionName", "FunctionNaming") // D-Bus member names are PascalCase.

package dev.supermux.desktop.host.linux

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

// The D-Bus side of the Linux tray: the interfaces dbus-java exports or calls, their structs and
// signals, and the two exported objects. Wildcards are suppressed throughout: dbus-java derives each
// signature from the Java generic types, and a `? extends Variant` would not map to `v`.

/** `(iiay)`: one icon pixmap. */
@JvmSuppressWildcards
class SniPixmapStruct(
    @field:Position(0) @JvmField val width: Int,
    @field:Position(1) @JvmField val height: Int,
    @field:Position(2) @JvmField val data: ByteArray,
) : Struct()

/** `(sa(iiay)ss)`: icon name, icon pixmaps, title, description. */
@JvmSuppressWildcards
class SniToolTipStruct(
    @field:Position(0) @JvmField val iconName: String,
    @field:Position(1) @JvmField val iconPixmap: List<SniPixmapStruct>,
    @field:Position(2) @JvmField val title: String,
    @field:Position(3) @JvmField val description: String,
) : Struct()

/** `(ia{sv}av)`: a dbusmenu node; each child is a variant holding another of these. */
@JvmSuppressWildcards
class MenuLayoutStruct(
    @field:Position(0) @JvmField val id: Int,
    @field:Position(1) @JvmField val props: Map<String, Variant<*>>,
    @field:Position(2) @JvmField val children: List<Variant<*>>,
) : Struct()

/** `(ia{sv})`: an item's properties. */
@JvmSuppressWildcards
class MenuItemPropsStruct(
    @field:Position(0) @JvmField val id: Int,
    @field:Position(1) @JvmField val props: Map<String, Variant<*>>,
) : Struct()

/** `(ias)`: an item's removed property names. */
@JvmSuppressWildcards
class MenuItemPropNamesStruct(
    @field:Position(0) @JvmField val id: Int,
    @field:Position(1) @JvmField val names: List<String>,
) : Struct()

/** `(isvu)`: one event of `EventGroup`. */
@JvmSuppressWildcards
class MenuEventStruct(
    @field:Position(0) @JvmField val id: Int,
    @field:Position(1) @JvmField val eventId: String,
    @field:Position(2) @JvmField val data: Variant<*>,
    @field:Position(3) @JvmField val timestamp: UInt32,
) : Struct()

/**
 * Two out args (`GetLayout`'s `u(ia{sv}av)`, `AboutToShowGroup`'s `aiai`). Generic on purpose:
 * dbus-java reads a Tuple's signature from the method's parameterized return type.
 */
class Tuple2<A, B>(
    @field:Position(0) @JvmField val first: A,
    @field:Position(1) @JvmField val second: B,
) : Tuple()

@DBusInterfaceName("org.kde.StatusNotifierItem")
interface StatusNotifierItem : DBusInterface {
    fun Activate(x: Int, y: Int)
    fun SecondaryActivate(x: Int, y: Int)
    fun ContextMenu(x: Int, y: Int)
    fun Scroll(delta: Int, orientation: String)

    /** KDE/GNOME extension: an XDG activation token sent ahead of `Activate` (Wayland focus). */
    fun ProvideXdgActivationToken(token: String)

    class NewIcon(path: String) : DBusSignal(path)
    class NewToolTip(path: String) : DBusSignal(path)
    class NewStatus(path: String, val status: String) : DBusSignal(path, status)
}

@JvmSuppressWildcards
@DBusInterfaceName("com.canonical.dbusmenu")
interface DbusMenu : DBusInterface {
    fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): Tuple2<UInt32, MenuLayoutStruct>
    fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<MenuItemPropsStruct>
    fun GetProperty(id: Int, name: String): Variant<*>
    fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32)
    fun EventGroup(events: List<MenuEventStruct>): List<Int>
    fun AboutToShow(id: Int): Boolean
    fun AboutToShowGroup(ids: List<Int>): Tuple2<List<Int>, List<Int>>

    class LayoutUpdated(path: String, val revision: UInt32, val parent: Int) : DBusSignal(path, revision, parent)

    @JvmSuppressWildcards
    class ItemsPropertiesUpdated(
        path: String,
        val updatedProps: List<MenuItemPropsStruct>,
        val removedProps: List<MenuItemPropNamesStruct>,
    ) : DBusSignal(path, updatedProps, removedProps)
}

@DBusInterfaceName("org.kde.StatusNotifierWatcher")
interface StatusNotifierWatcher : DBusInterface {
    fun RegisterStatusNotifierItem(service: String)

    class StatusNotifierHostRegistered(path: String) : DBusSignal(path)
}

@JvmSuppressWildcards
@DBusInterfaceName("org.freedesktop.Notifications")
interface FreedesktopNotifications : DBusInterface {
    fun Notify(
        appName: String,
        replacesId: UInt32,
        appIcon: String,
        summary: String,
        body: String,
        actions: List<String>,
        hints: Map<String, Variant<*>>,
        expireTimeout: Int,
    ): UInt32
}

/** A plain Kotlin value (String, Boolean, Int, UInt32) as a variant. */
internal fun variantOf(v: Any): Variant<*> = when (v) {
    is String -> Variant(v)
    is Boolean -> Variant(v)
    is Int -> Variant(v)
    else -> Variant(v)
}

private fun propsVariants(props: Map<String, Any>, names: List<String>): Map<String, Variant<*>> =
    props.filterKeys { names.isEmpty() || it in names }.mapValues { variantOf(it.value) }

/** Read-only `org.freedesktop.DBus.Properties` over [props] for [iface]. */
private class ReadOnlyProps(private val iface: String, private val props: () -> Map<String, Variant<*>>) {
    @Suppress("UNCHECKED_CAST")
    fun <A : Any?> get(interfaceName: String, propertyName: String): A {
        if (interfaceName != iface) throw DBusExecutionException("No such interface: $interfaceName")
        return (props()[propertyName] ?: throw DBusExecutionException("No such property: $propertyName")) as A
    }

    fun set(propertyName: String): Nothing = throw DBusExecutionException("Property $propertyName is read-only")

    fun getAll(interfaceName: String): Map<String, Variant<*>> = if (interfaceName == iface) props() else emptyMap()
}

/**
 * What `/StatusNotifierItem` shows; replaced whole when anything changes. [icons] compare by
 * identity (their ByteArrays), which is exact here: they are loaded once.
 */
data class SniItemState(
    val icons: List<SniIcon> = emptyList(),
    val tooltip: String = "",
    val status: String = "Active",
)

/** `/StatusNotifierItem`. Reads [state] on the bus's threads; [onActivate] must not block. */
class SniItemObject(
    private val state: () -> SniItemState,
    private val onActivate: () -> Unit,
) : StatusNotifierItem, Properties {
    private val props = ReadOnlyProps(SNI_INTERFACE) { itemProps(state()) }

    override fun getObjectPath(): String = SNI_PATH
    override fun Activate(x: Int, y: Int) = onActivate()
    override fun SecondaryActivate(x: Int, y: Int) = Unit
    override fun ContextMenu(x: Int, y: Int) = Unit
    override fun Scroll(delta: Int, orientation: String) = Unit

    // The window is an XWayland/X11 one: AWT has no use for a Wayland activation token.
    override fun ProvideXdgActivationToken(token: String) = Unit
    override fun <A : Any?> Get(interfaceName: String, propertyName: String): A = props.get(interfaceName, propertyName)
    override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) = props.set(propertyName)
    override fun GetAll(interfaceName: String): Map<String, Variant<*>> = props.getAll(interfaceName)

    companion object {
        const val SNI_INTERFACE = "org.kde.StatusNotifierItem"
        const val SNI_PATH = "/StatusNotifierItem"
        private const val PIXMAPS = "a(iiay)"

        fun pixmaps(icons: List<SniIcon>): Variant<*> =
            Variant(icons.map { SniPixmapStruct(it.width, it.height, it.argb) }, PIXMAPS)

        /** Pure: the item's properties. */
        fun itemProps(s: SniItemState): Map<String, Variant<*>> = linkedMapOf(
            "Category" to Variant("ApplicationStatus"),
            "Id" to Variant("supermux"),
            "Title" to Variant("supermux"),
            "Status" to Variant(s.status),
            "WindowId" to Variant(0),
            "IconThemePath" to Variant(""),
            "IconName" to Variant(""),
            "IconPixmap" to pixmaps(s.icons),
            "OverlayIconName" to Variant(""),
            "OverlayIconPixmap" to pixmaps(emptyList()),
            "AttentionIconName" to Variant(""),
            "AttentionIconPixmap" to pixmaps(emptyList()),
            "AttentionMovieName" to Variant(""),
            "ToolTip" to Variant(
                SniToolTipStruct("", s.icons.map { SniPixmapStruct(it.width, it.height, it.argb) }, "supermux", s.tooltip),
                "(sa(iiay)ss)",
            ),
            "ItemIsMenu" to Variant(false),
            "Menu" to Variant(DBusPath(DbusMenuObject.MENU_PATH)),
        )
    }
}

/** `/MenuBar`. Reads [menu] on the bus's threads; [onClick] (the clicked entry) must not block. */
class DbusMenuObject(
    private val menu: MenuState,
    private val onClick: (MenuEntry) -> Unit,
) : DbusMenu, Properties {
    private val props = ReadOnlyProps(MENU_INTERFACE) {
        linkedMapOf(
            "Version" to Variant(UInt32(3)),
            "TextDirection" to Variant("ltr"),
            "Status" to Variant("normal"),
            "IconThemePath" to Variant(emptyList<String>(), "as"),
        )
    }

    override fun getObjectPath(): String = MENU_PATH

    override fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): Tuple2<UInt32, MenuLayoutStruct> {
        val snap = menu.snapshot
        return Tuple2(UInt32(snap.revision.toLong()), layout(snap, parentId, recursionDepth, propertyNames))
    }

    override fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<MenuItemPropsStruct> {
        val snap = menu.snapshot
        val all = listOf(MenuEntry(MenuIds.ROOT, ROOT_PROPS, null)) + snap.entries
        val wanted = if (ids.isEmpty()) all else all.filter { it.id in ids }
        return wanted.map { MenuItemPropsStruct(it.id, propsVariants(it.props, propertyNames)) }
    }

    override fun GetProperty(id: Int, name: String): Variant<*> {
        val props = if (id == MenuIds.ROOT) ROOT_PROPS else menu.snapshot.byId(id)?.props
        val v = props?.get(name) ?: throw DBusExecutionException("No property $name on item $id")
        return variantOf(v)
    }

    override fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) {
        if (eventId != "clicked") return
        menu.snapshot.byId(id)?.let(onClick)
    }

    override fun EventGroup(events: List<MenuEventStruct>): List<Int> {
        val snap = menu.snapshot
        val errors = events.filter { it.id != MenuIds.ROOT && snap.byId(it.id) == null }.map { it.id }
        events.forEach { Event(it.id, it.eventId, it.data, it.timestamp) }
        return errors
    }

    override fun AboutToShow(id: Int): Boolean = false

    override fun AboutToShowGroup(ids: List<Int>): Tuple2<List<Int>, List<Int>> {
        val snap = menu.snapshot
        return Tuple2(emptyList(), ids.filter { it != MenuIds.ROOT && snap.byId(it) == null })
    }

    override fun <A : Any?> Get(interfaceName: String, propertyName: String): A = props.get(interfaceName, propertyName)
    override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) = props.set(propertyName)
    override fun GetAll(interfaceName: String): Map<String, Variant<*>> = props.getAll(interfaceName)

    companion object {
        const val MENU_INTERFACE = "com.canonical.dbusmenu"
        const val MENU_PATH = "/MenuBar"

        /**
         * Pure: the `(ia{sv}av)` node for [parentId] in [snap]. The root carries the rows as children
         * unless [depth] is 0; rows have no children. An unknown id answers with an empty root.
         */
        fun layout(snap: MenuState.Snapshot, parentId: Int, depth: Int, names: List<String>): MenuLayoutStruct {
            if (parentId != MenuIds.ROOT) {
                val e = snap.byId(parentId) ?: return MenuLayoutStruct(MenuIds.ROOT, propsVariants(ROOT_PROPS, names), emptyList())
                return MenuLayoutStruct(e.id, propsVariants(e.props, names), emptyList())
            }
            val children = if (depth == 0) {
                emptyList()
            } else {
                snap.entries.map { Variant(MenuLayoutStruct(it.id, propsVariants(it.props, names), emptyList()), LAYOUT_SIG) }
            }
            return MenuLayoutStruct(MenuIds.ROOT, propsVariants(ROOT_PROPS, names), children)
        }

        private const val LAYOUT_SIG = "(ia{sv}av)"

        /** `(ia{sv})` for [entries], for `ItemsPropertiesUpdated`. */
        fun itemProps(entries: List<MenuEntry>): List<MenuItemPropsStruct> =
            entries.map { MenuItemPropsStruct(it.id, propsVariants(it.props, emptyList())) }
    }
}
