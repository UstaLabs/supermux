package dev.supermux.desktop.host.linux

import dev.supermux.desktop.host.TrayDispatcher
import dev.supermux.desktop.host.TrayIcons
import dev.supermux.desktop.host.TrayItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private fun log(msg: String) = System.err.println("supermux tray (SNI): $msg")

    /** Run [block] on the tray thread; a failure is logged, never thrown. */
    private fun onBus(block: () -> Unit) {
        runCatching {
            executor.execute { runCatching(block).onFailure { log("${it::class.simpleName}: ${it.message}") } }
        } // rejected after close: ignore
    }

    /** Connect, export, take the name and register with the watcher (asynchronously). */
    fun start() = onBus {
        if (closed || bus != null) return@onBus
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
        // A watcher that appears later (the extension enabled, the shell restarted) gets us then;
        // one that goes away leaves no tray.
        runCatching { b.watchOwner(DbusJavaSniBus.WATCHER_NAME) { owned -> onBus { watcherChanged(owned) } } }
            .onFailure { log("can't watch the watcher: ${it.message}") }
        runCatching { b.watchHostRegistered { onBus { if (bus != null && _status.value != SniStatus.REGISTERED) register() } } }
        val present = runCatching { b.hasOwner(DbusJavaSniBus.WATCHER_NAME) }.getOrDefault(false)
        if (present) register() else unsupported("no StatusNotifierWatcher on the session bus")
    }

    private fun watcherChanged(owned: Boolean) {
        if (closed || bus == null) return
        if (!owned) return unsupported("the StatusNotifierWatcher went away")
        // A watcher that has just started may not take items (or have its host) yet: a few tries.
        for (attempt in 0 until REGISTER_TRIES) {
            if (attempt > 0) Thread.sleep(RETRY_MS)
            if (closed || bus == null) return
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

    /** A desktop notification; dropped when there is no bus. */
    fun notify(title: String, body: String) = onBus { bus?.notify(title, body) }

    private fun activate() = ui { runCatching { onActivate?.invoke() } }

    private fun clicked(entry: MenuEntry) {
        val row = entry.item ?: return
        ui { runCatching { dispatcher?.click(row) }.onFailure { log("click failed: ${it.message}") } }
    }

    /** Release the name and close the connection; waits briefly so the icon goes away with the app. */
    fun close() {
        onBus {
            closed = true
            bus?.close()
            bus = null
        }
        executor.shutdown()
        runCatching { executor.awaitTermination(2, TimeUnit.SECONDS) }
    }

    private companion object {
        const val REGISTER_TRIES = 3
        const val RETRY_MS = 1_000L
    }
}
