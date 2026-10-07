package dev.supermux.desktop.host.linux

import dev.supermux.desktop.host.TrayItem

/**
 * One `com.canonical.dbusmenu` item, as plain Kotlin: [props] values are String, Boolean or Int,
 * turned into D-Bus variants only at the bus edge (`DbusMenuObject`). [item] is the tray row it
 * renders (null for the root), so a "clicked" event dispatches the row it was shown for.
 */
data class MenuEntry(val id: Int, val props: Map<String, Any>, val item: TrayItem?)

/** The dbusmenu ids. Stable per row KIND, so a host can keep an open menu across updates. */
object MenuIds {
    const val ROOT = 0
    const val HEADER = 1
    private const val ACTION_BASE = 10
    private const val TOGGLE_BASE = 20
    private const val SEPARATOR_BASE = 100

    fun action(a: dev.supermux.desktop.host.TrayAction) = ACTION_BASE + a.ordinal
    fun toggle(t: dev.supermux.desktop.host.TrayToggle) = TOGGLE_BASE + t.ordinal

    /** The [n]th separator (0-based) in the menu. */
    fun separator(n: Int) = SEPARATOR_BASE + n
}

/** dbusmenu labels treat `_` as the mnemonic marker; a literal one is doubled. */
fun dbusMenuLabel(text: String): String = text.replace("_", "__")

/**
 * Pure: the dbusmenu children of the root for [items] — the SAME list the AWT tray renders
 * ([dev.supermux.desktop.host.trayMenuItems]). Header → a disabled label; Action → a standard item;
 * Checkbox → `toggle-type=checkmark` with `toggle-state` 0/1; Separator → `type=separator`.
 */
fun dbusMenuEntries(items: List<TrayItem>): List<MenuEntry> {
    var separators = 0
    return items.map { item ->
        when (item) {
            is TrayItem.Header -> MenuEntry(
                MenuIds.HEADER,
                mapOf("label" to dbusMenuLabel(item.text), "enabled" to false),
                item,
            )
            is TrayItem.Action -> MenuEntry(
                MenuIds.action(item.id),
                mapOf("label" to dbusMenuLabel(item.label), "enabled" to item.enabled),
                item,
            )
            is TrayItem.Checkbox -> MenuEntry(
                MenuIds.toggle(item.id),
                mapOf(
                    "label" to dbusMenuLabel(item.label),
                    "enabled" to item.enabled,
                    "toggle-type" to "checkmark",
                    "toggle-state" to if (item.checked) 1 else 0,
                ),
                item,
            )
            TrayItem.Separator -> MenuEntry(MenuIds.separator(separators++), mapOf("type" to "separator"), item)
        }
    }
}

/** The root item's own properties. */
val ROOT_PROPS: Map<String, Any> = mapOf("children-display" to "submenu")

/** What changed between two menus: the dbusmenu signal(s) to send. */
sealed interface MenuChange {
    /** Nothing changed: no signal, no revision bump. */
    data object None : MenuChange

    /** The rows or their order changed: `LayoutUpdated(revision, 0)`. */
    data class Layout(val revision: Int) : MenuChange

    /**
     * Same rows, new properties: `ItemsPropertiesUpdated` with [updated] (id → full props), and
     * `LayoutUpdated(revision, 0)` too, so a host that only re-reads on a layout change stays current.
     */
    data class Props(val revision: Int, val updated: List<MenuEntry>) : MenuChange
}

/**
 * The menu's current entries and layout revision. [update] bumps the revision whenever the item
 * list changes (rows or their properties) and says which signal(s) that means. Thread-safe: the
 * bus reads [snapshot] from its own threads while the UI pushes [update]s.
 */
class MenuState(items: List<TrayItem> = emptyList()) {
    data class Snapshot(val revision: Int, val entries: List<MenuEntry>) {
        fun byId(id: Int): MenuEntry? = entries.firstOrNull { it.id == id }
    }

    @Volatile var snapshot: Snapshot = Snapshot(1, dbusMenuEntries(items))
        private set

    @Synchronized
    fun update(items: List<TrayItem>): MenuChange {
        val old = snapshot
        val entries = dbusMenuEntries(items)
        if (entries.map { it.id to it.props } == old.entries.map { it.id to it.props }) {
            // Same rows and props; keep the newest TrayItem objects (equal anyway, as data classes).
            snapshot = old.copy(entries = entries)
            return MenuChange.None
        }
        val next = Snapshot(old.revision + 1, entries)
        snapshot = next
        return if (entries.map { it.id } == old.entries.map { it.id }) {
            val oldProps = old.entries.associate { it.id to it.props }
            MenuChange.Props(next.revision, entries.filter { oldProps[it.id] != it.props })
        } else {
            MenuChange.Layout(next.revision)
        }
    }
}
