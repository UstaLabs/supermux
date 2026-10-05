package dev.supermux.desktop.host.linux

import dev.supermux.desktop.host.TrayAction
import dev.supermux.desktop.host.TrayDispatcher
import dev.supermux.desktop.host.TrayItem
import dev.supermux.desktop.host.TrayToggle
import org.freedesktop.dbus.interfaces.DBusInterface
import org.junit.Assume.assumeTrue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SniTrayTest {
    /** A session bus in memory: records what the tray did, and lets a test play the watcher. */
    private class FakeBus(var watcher: Boolean, var host: Boolean = true) : SniBus {
        val published = mutableMapOf<String, DBusInterface>()
        var name: String? = null
        val registered = mutableListOf<String>()
        val signals = mutableListOf<SniSignal>()
        val notified = mutableListOf<DesktopNotification>()
        var ownerCb: ((Boolean) -> Unit)? = null
        var closed = false
        var failRegister = false

        override fun publish(busName: String, objects: Map<String, DBusInterface>) {
            published += objects
            name = busName
        }
        override fun hasOwner(name: String) = name == DbusJavaSniBus.WATCHER_NAME && watcher
        override fun watchOwner(name: String, cb: (owned: Boolean) -> Unit) { ownerCb = cb }
        override fun registerItem(busName: String) {
            if (failRegister) error("refused")
            registered += busName
        }
        override fun hostRegistered() = host
        override fun watchHostRegistered(cb: () -> Unit) = Unit
        override fun emit(signal: SniSignal) { signals += signal }
        override fun notify(n: DesktopNotification) { notified += n }
        override fun close() { closed = true }
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val uiQueue = mutableListOf<Runnable>()

    @AfterTest fun tearDown() { executor.shutdownNow() }

    private fun flush() = executor.submit {}.get()

    private fun tray(bus: () -> SniBus) =
        SniTray(busFactory = bus, ui = { uiQueue += it }, icons = { emptyList() }, pid = 42, executor = executor)

    private val items = listOf(
        TrayItem.Header("supermux"),
        TrayItem.Action(TrayAction.OPEN, "Open supermux"),
        TrayItem.Checkbox("Keep running in the background", checked = false, enabled = true),
    )

    @Test fun registersWithThePresentWatcher() {
        val bus = FakeBus(watcher = true)
        val t = tray { bus }
        t.start()
        t.update(items, "tip")
        flush()
        assertEquals("org.kde.StatusNotifierItem-42-1", bus.name)
        assertEquals(setOf("/StatusNotifierItem", "/MenuBar"), bus.published.keys)
        assertEquals(listOf("org.kde.StatusNotifierItem-42-1"), bus.registered)
        assertEquals(SniStatus.REGISTERED, t.status.value)
    }

    @Test fun registrationWaitsForTheFirstMenu() {
        // GNOME's extension never refetches a menu it first saw empty while closed: no rows, no item.
        val bus = FakeBus(watcher = true)
        val t = tray { bus }
        t.start()
        flush()
        assertTrue(bus.registered.isEmpty())
        assertEquals(SniStatus.STARTING, t.status.value)
        t.update(items, "tip")
        flush()
        assertEquals(listOf("org.kde.StatusNotifierItem-42-1"), bus.registered)
        assertEquals(SniStatus.REGISTERED, t.status.value)
    }

    @Test fun noWatcherIsUnsupportedUntilOneAppears() {
        val bus = FakeBus(watcher = false)
        val t = tray { bus }
        t.start()
        t.update(items, "tip")
        flush()
        assertEquals(SniStatus.UNSUPPORTED, t.status.value)
        assertTrue(bus.registered.isEmpty())

        bus.watcher = true
        bus.ownerCb!!(true)
        flush()
        assertEquals(SniStatus.REGISTERED, t.status.value)
        assertEquals(1, bus.registered.size)

        // The extension disabled / the panel gone: no tray to hide into.
        bus.ownerCb!!(false)
        flush()
        assertEquals(SniStatus.UNSUPPORTED, t.status.value)
    }

    @Test fun aWatcherWithoutAHostIsUnsupported() {
        val t = tray { FakeBus(watcher = true, host = false) }
        t.start()
        t.update(items, "tip")
        flush()
        assertEquals(SniStatus.UNSUPPORTED, t.status.value)
    }

    @Test fun aRefusedRegistrationIsUnsupported() {
        val bus = FakeBus(watcher = true).apply { failRegister = true }
        val t = tray { bus }
        t.start()
        t.update(items, "tip")
        flush()
        assertEquals(SniStatus.UNSUPPORTED, t.status.value)
    }

    @Test fun noBusIsFailedAndNeverThrows() {
        val t = tray { error("no session bus") }
        t.start()
        t.update(items, "tip")
        t.notify("a", "b")
        flush()
        assertEquals(SniStatus.FAILED, t.status.value)
        t.close()
    }

    @Test fun updatesEmitLayoutAndTooltipSignals() {
        val bus = FakeBus(watcher = true)
        val t = tray { bus }
        t.start()
        t.update(items, "one")
        flush()
        // The first rows go out with the registration itself (the host reads them then).
        assertTrue(bus.signals.isEmpty())
        assertEquals(1, bus.registered.size)

        t.update(items, "one")
        flush()
        assertTrue(bus.signals.isEmpty(), "nothing changed, nothing sent")

        t.update(items.dropLast(1), "two")
        flush()
        assertEquals(listOf(SniSignal.NewToolTip, SniSignal.LayoutUpdated(3)), bus.signals)
        bus.signals.clear()
        t.update(items, "two")
        flush()
        assertEquals(listOf<SniSignal>(SniSignal.LayoutUpdated(4)), bus.signals)
        bus.signals.clear()

        t.update(items.map { if (it is TrayItem.Checkbox) it.copy(checked = true) else it }, "two")
        flush()
        assertEquals(2, bus.signals.size)
        assertTrue(bus.signals.first() is SniSignal.ItemsPropertiesUpdated)
        assertEquals(SniSignal.LayoutUpdated(5), bus.signals.last())
    }

    @Test fun clicksGoThroughTheDispatcherOnTheUiThread() {
        val bus = FakeBus(watcher = true)
        val t = tray { bus }
        val actions = mutableListOf<TrayAction>()
        val toggles = mutableListOf<Pair<TrayToggle, Boolean>>()
        var activated = 0
        t.dispatcher = TrayDispatcher({ actions += it }, { k, on -> toggles += k to on })
        t.onActivate = { activated++ }
        t.start()
        t.update(items, "tip")
        flush()
        val menu = bus.published["/MenuBar"] as DbusMenuObject
        val item = bus.published["/StatusNotifierItem"] as SniItemObject
        menu.Event(MenuIds.action(TrayAction.OPEN), "clicked", org.freedesktop.dbus.types.Variant(""), org.freedesktop.dbus.types.UInt32(0))
        menu.Event(MenuIds.toggle(TrayToggle.BACKGROUND), "clicked", org.freedesktop.dbus.types.Variant(""), org.freedesktop.dbus.types.UInt32(0))
        menu.Event(MenuIds.HEADER, "clicked", org.freedesktop.dbus.types.Variant(""), org.freedesktop.dbus.types.UInt32(0))
        item.Activate(0, 0)
        // Nothing runs on the bus thread: it is all queued for the UI.
        assertTrue(actions.isEmpty() && toggles.isEmpty() && activated == 0)
        uiQueue.forEach { it.run() }
        assertEquals(listOf(TrayAction.OPEN), actions)
        assertEquals(listOf(TrayToggle.BACKGROUND to true), toggles)
        assertEquals(1, activated)
        assertEquals("tip", item.GetAll(SniItemObject.SNI_INTERFACE)["ToolTip"]?.let { (it.value as SniToolTipStruct).description })
    }

    @Test fun notifyAndCloseReachTheBus() {
        val bus = FakeBus(watcher = true)
        val t = tray { bus }
        t.start()
        t.update(items, "tip")
        flush()
        t.notificationIdentity = DesktopNotification.Identity("supermux-supermux", "/opt/supermux/lib/supermux.png")
        t.notify("supermux", "a <b> & c")
        t.close()
        assertEquals(
            listOf(DesktopNotification("supermux", "a &lt;b&gt; &amp; c", "/opt/supermux/lib/supermux.png", "supermux-supermux")),
            bus.notified,
        )
        assertTrue(bus.closed)
        assertTrue(!t.busUp)
    }

    @Test fun closeCutsTheRegisterRetriesShort() {
        // A watcher that keeps refusing: the tray retries (1 s apart) — close must not wait them out.
        val bus = FakeBus(watcher = false).apply { failRegister = true }
        val t = tray { bus }
        t.start()
        t.update(items, "tip")
        flush()
        bus.watcher = true
        bus.ownerCb!!(true) // queues the retry loop on the tray thread
        Thread.sleep(100)
        val started = System.nanoTime()
        t.close()
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMs < 900, "close took $tookMs ms")
        assertTrue(bus.closed)
    }

    /** The real session bus, when there is one (a Linux desktop): connects, exports and registers. */
    @Test fun realSessionBus() {
        assumeTrue(System.getProperty("os.name").lowercase().contains("linux"))
        assumeTrue(SessionBus.address(System.getenv()) != null)
        val t = SniTray(ui = { it.run() }, icons = { emptyList() })
        try {
            t.start()
            t.update(items, "test")
            val deadline = System.currentTimeMillis() + 5_000
            while (t.status.value == SniStatus.STARTING && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue(t.status.value == SniStatus.REGISTERED || t.status.value == SniStatus.UNSUPPORTED, "status ${t.status.value}")
        } finally {
            t.close()
        }
    }
}
