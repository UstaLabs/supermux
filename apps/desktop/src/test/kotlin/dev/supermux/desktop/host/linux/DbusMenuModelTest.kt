package dev.supermux.desktop.host.linux

import dev.supermux.desktop.host.HostingPrefs
import dev.supermux.desktop.host.HostingStatus
import dev.supermux.desktop.host.TrayAction
import dev.supermux.desktop.host.TrayItem
import dev.supermux.desktop.host.TrayModel
import dev.supermux.desktop.host.TrayPower
import dev.supermux.desktop.host.TrayToggle
import dev.supermux.desktop.host.trayMenuItems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class DbusMenuModelTest {
    private val items = listOf(
        TrayItem.Header("🟢 supermux is running · 2 sessions"),
        TrayItem.Action(TrayAction.OPEN, "Open supermux"),
        TrayItem.Separator,
        TrayItem.Action(TrayAction.RESTART, "Restart supermux", enabled = false),
        TrayItem.Checkbox("Keep running in the background", checked = true, enabled = true),
        TrayItem.Checkbox("Keep this computer awake", checked = false, enabled = false, id = TrayToggle.KEEP_AWAKE),
        TrayItem.Separator,
        TrayItem.Action(TrayAction.QUIT, "Quit supermux"),
    )

    @Test fun mapsEveryRowKind() {
        val e = dbusMenuEntries(items)
        assertEquals(listOf(1, 10, 100, 12, 20, 21, 101, 13), e.map { it.id })
        assertEquals(mapOf("label" to "🟢 supermux is running · 2 sessions", "enabled" to false), e[0].props)
        assertEquals(mapOf("label" to "Open supermux", "enabled" to true), e[1].props)
        assertEquals(mapOf("type" to "separator"), e[2].props)
        assertEquals(false, e[3].props["enabled"])
        assertEquals(
            mapOf("label" to "Keep running in the background", "enabled" to true, "toggle-type" to "checkmark", "toggle-state" to 1),
            e[4].props,
        )
        assertEquals(0, e[5].props["toggle-state"])
        assertEquals(false, e[5].props["enabled"])
        assertEquals(mapOf("type" to "separator"), e[6].props)
        assertSame(items[7], e[7].item)
    }

    @Test fun idsAreStableWhenRowsComeAndGo() {
        val fewer = items.filterNot { it is TrayItem.Checkbox && it.id == TrayToggle.KEEP_AWAKE }
        val e = dbusMenuEntries(fewer)
        assertEquals(listOf(1, 10, 100, 12, 20, 101, 13), e.map { it.id })
    }

    @Test fun underscoresAreNotMnemonics() {
        assertEquals("a__b", dbusMenuLabel("a_b"))
    }

    @Test fun rendersTheRealTrayMenu() {
        val model = TrayModel.of(HostingStatus.Running(9898, false), HostingPrefs(), 2, null)
        val real = trayMenuItems(model, background = false, power = TrayPower(keepAwake = true))
        val e = dbusMenuEntries(real)
        assertEquals(real.size, e.size)
        assertEquals(real, e.map { it.item })
        assertEquals(e.size, e.map { it.id }.toSet().size, "ids are unique")
    }

    @Test fun revisionBumpsOnlyOnChange() {
        val state = MenuState()
        val first = state.update(items)
        assertIs<MenuChange.Layout>(first)
        assertEquals(2, state.snapshot.revision)

        assertEquals(MenuChange.None, state.update(items.toList()))
        assertEquals(2, state.snapshot.revision)

        // Same rows, a checkbox flipped: a property change, with a bump.
        val flipped = items.map { if (it is TrayItem.Checkbox && it.id == TrayToggle.BACKGROUND) it.copy(checked = false) else it }
        val props = state.update(flipped)
        assertIs<MenuChange.Props>(props)
        assertEquals(3, props.revision)
        assertEquals(listOf(20), props.updated.map { it.id })
        assertEquals(0, props.updated.single().props["toggle-state"])

        // A row gone: a layout change.
        val layout = state.update(flipped.filterNot { it is TrayItem.Action && it.id == TrayAction.RESTART })
        assertEquals(MenuChange.Layout(4), layout)
        assertEquals(4, state.snapshot.revision)
    }
}
