package dev.supermux.desktop.host.linux

import dev.supermux.desktop.host.TrayDispatcher
import dev.supermux.desktop.host.TrayIcons
import dev.supermux.desktop.host.TrayItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/** Where the Linux tray stands. Only [REGISTERED] means a tray host is showing our icon. */
enum class SniStatus {
    /** Connecting / registering. */
    STARTING,

    /** A StatusNotifierWatcher took our item and a host is there to show it. */
    REGISTERED,

    /** No watcher (no tray host: stock GNOME without the AppIndicator extension), or it went away. */
    UNSUPPORTED,

    /** No session bus, or it refused us. Final. */
    FAILED,
}

/**
 * The Linux tray: a StatusNotifierItem (`/StatusNotifierItem`) with a dbusmenu (`/MenuBar`), the
 * protocol GNOME's AppIndicator extension, KDE, XFCE, Cinnamon and others host. It renders the same
 * [TrayItem] list as the AWT tray and dispatches clicks through the same [TrayDispatcher].
 *
 * Every bus call runs on one dedicated daemon thread, and every failure is caught: the bus can never
 * crash the app or block the UI. The bus's own threads only read immutable snapshots and hop to
 * [ui] for clicks. [status] tells the app whether there is a tray to hide into.
 */
class SniTray(
    private val busFactory: () -> SniBus = { DbusJavaSniBus.connect() },
    private val ui: (Runnable) -> Unit = { SwingUtilities.invokeLater(it) },
    private val icons: () -> List<SniIcon> = { SniPixmap.fromResource(TrayIcons.COLOUR) },
    pid: Long = ProcessHandle.current().pid(),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "supermux-sni-tray").apply { isDaemon = true }
    },
) {
    val busName = "org.kde.StatusNotifierItem-$pid-1"

    private val _status = MutableStateFlow(SniStatus.STARTING)
    val status: StateFlow<SniStatus> = _status.asStateFlow()

    /** The click handlers; replaced as the app recomposes. Read on the UI thread only. */
    @Volatile var dispatcher: TrayDispatcher? = null

    /** A click on the icon itself (`Activate`): the same as the AWT tray's onAction. */
    @Volatile var onActivate: (() -> Unit)? = null

    private val menu = MenuState()

    @Volatile private var item = SniItemState()

    // Touched only on the executor thread.
    private var bus: SniBus? = null
    private var closed = false

    /** Connected, but the first [update] hasn't come yet: registration waits for it. */
    private var awaitingMenu = false

    /**
     * Set by [close] from any thread, OUTSIDE the tray thread's queue: the register-retry loop
     * checks it and its wait ([closeSignal]) wakes at once, so close never waits out a retry.
     */
    @Volatile private var closing = false
    private val closeSignal = CountDownLatch(1)

    /** A session bus is connected and our objects are on it: notifications can go out. */
    @Volatile var busUp: Boolean = false
        private set

    /** What [notify] sends as; see [DesktopNotification.identity]. */
    @Volatile var notificationIdentity: DesktopNotification.Identity = DesktopNotification.identity()

    private fun log(msg: String) = System.err.println("supermux tray (SNI): $msg")

    /** Run [block] on the tray thread; a failure is logged, never thrown. */
    private fun onBus(block: () -> Unit) {
        runCatching {
            executor.execute { runCatching(block).onFailure { log("${it::class.simpleName}: ${it.message}") } }
        } // rejected after close: ignore
    }

    /** Connect, export, take the name and register with the watcher (asynchronously). */
    fun start() = onBus {
        if (closing || closed || bus != null) return@onBus
        item = item.copy(icons = runCatching(icons).getOrDefault(emptyList()))
        val b = try {
            busFactory()
        } catch (t: Throwable) {
            log("no session bus: ${t.message}")
            _status.value = SniStatus.FAILED
            return@onBus
        }
        try {
            b.publish(
                busName,
                mapOf(
                    SniItemObject.SNI_PATH to SniItemObject({ item }, ::activate),
                    DbusMenuObject.MENU_PATH to DbusMenuObject(menu, ::clicked),
                ),
            )
        } catch (t: Throwable) {
            log("can't publish $busName: ${t.message}")
            runCatching { b.close() }
            _status.value = SniStatus.FAILED
            return@onBus
        }
        bus = b
        busUp = true
        // A watcher that appears later (the extension enabled, the shell restarted) gets us then;
        // one that goes away leaves no tray.
        runCatching { b.watchOwner(DbusJavaSniBus.WATCHER_NAME) { owned -> onBus { watcherChanged(owned) } } }
            .onFailure { log("can't watch the watcher: ${it.message}") }
        runCatching { b.watchHostRegistered { onBus { if (bus != null && _status.value != SniStatus.REGISTERED) register() } } }
        registerIfWatcher()
    }

    /**
     * Register when there is a watcher — but never with an empty menu. GNOME's AppIndicator
     * extension fetches the layout at registration and, while its menu is closed, only FLAGS a
     * later `LayoutUpdated`; a click opens the menu only when it already has items. An item
     * registered before the first [update] would show an icon whose menu never opens.
     */
    private fun registerIfWatcher() {
        val b = bus ?: return
        if (menu.snapshot.entries.isEmpty()) {
            awaitingMenu = true
            return
        }
        val present = runCatching { b.hasOwner(DbusJavaSniBus.WATCHER_NAME) }.getOrDefault(false)
        if (present) register() else unsupported("no StatusNotifierWatcher on the session bus")
    }

    private fun watcherChanged(owned: Boolean) {
        if (closing || closed || bus == null) return
        if (!owned) return unsupported("the StatusNotifierWatcher went away")
        // A watcher that has just started may not take items (or have its host) yet: a few tries.
        for (attempt in 0 until REGISTER_TRIES) {
            if (attempt > 0 && closeSignal.await(RETRY_MS, TimeUnit.MILLISECONDS)) return
            if (closing || closed || bus == null || awaitingMenu) return
            register()
            if (_status.value == SniStatus.REGISTERED) return
        }
    }

    private fun unsupported(why: String) {
        log("$why: no tray")
        _status.value = SniStatus.UNSUPPORTED
    }

    private fun register() {
        val b = bus ?: return
        if (menu.snapshot.entries.isEmpty()) {
            awaitingMenu = true
            return
        }
        try {
            b.registerItem(busName)
        } catch (t: Throwable) {
            unsupported("RegisterStatusNotifierItem failed (${t.message})")
            return
        }
        if (b.hostRegistered()) {
            if (_status.value != SniStatus.REGISTERED) log("registered $busName")
            _status.value = SniStatus.REGISTERED
        } else {
            unsupported("a watcher but no tray host")
        }
    }

    /** Show [items] (the same [dev.supermux.desktop.host.trayMenuItems] list) and [tooltip]. */
    fun update(items: List<TrayItem>, tooltip: String) = onBus {
        val change = menu.update(items)
        val tooltipChanged = tooltip != item.tooltip
        if (tooltipChanged) item = item.copy(tooltip = tooltip)
        val b = bus ?: return@onBus
        if (awaitingMenu && menu.snapshot.entries.isNotEmpty()) {
            // The first rows: now the item can go to the watcher (it reads them at once).
            awaitingMenu = false
            registerIfWatcher()
            return@onBus
        }
        if (tooltipChanged) b.emit(SniSignal.NewToolTip)
        when (change) {
            MenuChange.None -> Unit
            is MenuChange.Layout -> b.emit(SniSignal.LayoutUpdated(change.revision))
            is MenuChange.Props -> {
                b.emit(SniSignal.ItemsPropertiesUpdated(change.updated))
                b.emit(SniSignal.LayoutUpdated(change.revision))
            }
        }
    }

    /** A desktop notification through `org.freedesktop.Notifications`; dropped when there is no bus. */
    fun notify(title: String, body: String) = onBus {
        bus?.notify(DesktopNotification.of(title, body, notificationIdentity))
    }

    private fun activate() = ui { runCatching { onActivate?.invoke() } }

    private fun clicked(entry: MenuEntry) {
        val row = entry.item ?: return
        ui { runCatching { dispatcher?.click(row) }.onFailure { log("click failed: ${it.message}") } }
    }

    /** Release the name and close the connection; waits briefly so the icon goes away with the app. */
    fun close() {
        closing = true
        busUp = false
        closeSignal.countDown()
        onBus {
            closed = true
            bus?.close()
            bus = null
        }
        executor.shutdown()
        runCatching { executor.awaitTermination(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS) }
    }

    private companion object {
        const val REGISTER_TRIES = 3
        const val RETRY_MS = 1_000L
        const val CLOSE_WAIT_MS = 2_000L
    }
}
