// Live tear-off on desktop: the AWT side of TabDragWorld — where our windows are, which is in
// front, moving / hiding them, and the pointer and Esc while a tab drag is out of its window.
package dev.supermux.desktop.shell

import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import dev.supermux.ui.shell.windows.TabDragWorld
import dev.supermux.ui.shell.windows.TearOffPlatform
import dev.supermux.ui.shell.windows.WindowBounds
import dev.supermux.ui.shell.windows.WindowHostRegistry
import java.awt.AWTEvent
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.RootPaneContainer
import kotlin.math.roundToInt

/**
 * Desktop's [TearOffPlatform], and the [TabDragWorld] over it. One per process: every window
 * (`Main.kt`'s main window and each extra `Window {}`) [register]s itself under its host id.
 *
 * Positions are AWT screen points, the unit `Window {}` places windows in; a ComposeWindow's
 * content pane is what its Compose root fills, and the window's display transform is root px per
 * point (as in `PaneAreaFit.jvm.kt`).
 */
internal object DesktopTearOff : TearOffPlatform {
    private class Entry(val window: Window, val extra: Boolean, val focus: WindowAdapter)

    private val windows = LinkedHashMap<String, Entry>()
    /** Front-to-back order, best effort: bumped when a window gains focus or we raise it. */
    private val raised = HashMap<String, Long>()
    private var stamp = 0L
    /** Content top-left minus window top-left, per window, last seen while it was on screen. */
    private val contentInsets = HashMap<String, Point>()
    private val following = HashSet<String>()

    /** Extra windows a live drag has hidden; `Main.kt` shows each extra `Window {}` unless it is here. */
    val hidden = mutableStateSetOf<String>()

    /** Null until `Main.kt` binds the window registry. */
    var world: TabDragWorld? = null
        private set
    private var boundRegistry: WindowHostRegistry? = null

    /** Live tear-off over [registry]'s windows; a new registry gets a new world. */
    fun bind(registry: WindowHostRegistry) {
        if (boundRegistry !== registry) {
            boundRegistry = registry
            world = TabDragWorld(registry, this)
        }
        installEscape()
    }

    fun register(hostId: String, window: Window, extra: Boolean) {
        windows[hostId]?.let { old -> old.window.removeWindowFocusListener(old.focus) }
        val focus = object : WindowAdapter() {
            override fun windowGainedFocus(e: WindowEvent) {
                raised[hostId] = ++stamp
            }
        }
        window.addWindowFocusListener(focus)
        windows[hostId] = Entry(window, extra, focus)
        raised[hostId] = ++stamp
    }

    fun unregister(hostId: String, window: Window) {
        val entry = windows[hostId] ?: return
        if (entry.window !== window) return
        window.removeWindowFocusListener(entry.focus)
        windows.remove(hostId)
        raised.remove(hostId)
        contentInsets.remove(hostId)
        hidden.remove(hostId)
    }

    /** [hostId]'s window is following the pointer: `Main.kt` holds its moves out of the saved bounds. */
    fun isFollowing(hostId: String): Boolean = hostId in following

    private fun showing(hostId: String): Window? = windows[hostId]?.window?.takeIf { it.isShowing }

    // ── TearOffPlatform ──────────────────────────────────────────────────────────────

    override fun contentOrigin(hostId: String): Offset? {
        val w = showing(hostId) ?: return null
        val content = (w as? RootPaneContainer)?.contentPane ?: return null
        val p = runCatching { content.locationOnScreen }.getOrNull() ?: return null
        contentInsets[hostId] = Point(p.x - w.x, p.y - w.y)
        return Offset(p.x.toFloat(), p.y.toFloat())
    }

    override fun contentSize(hostId: String): Size? {
        val content = (showing(hostId) as? RootPaneContainer)?.contentPane ?: return null
        return Size(content.width.toFloat(), content.height.toFloat())
    }

    override fun windowFrame(hostId: String): Rect? {
        val b = showing(hostId)?.bounds ?: return null
        return Rect(b.x.toFloat(), b.y.toFloat(), (b.x + b.width).toFloat(), (b.y + b.height).toFloat())
    }

    override fun scale(hostId: String): Float? =
        showing(hostId)?.let { (it.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0).toFloat() }

    override fun frontToBack(): List<String> =
        windows.keys.filter { showing(it) != null }.sortedByDescending { raised[it] ?: 0L }

    override fun moveContentTo(hostId: String, contentTopLeft: Offset): Boolean {
        val w = windows[hostId]?.window ?: return false
        if (w.isShowing) contentOrigin(hostId)
        val inset = contentInsets[hostId] ?: return false
        w.setLocation((contentTopLeft.x - inset.x).roundToInt(), (contentTopLeft.y - inset.y).roundToInt())
        return true
    }

    override fun frameForContent(contentTopLeft: Offset, contentSize: Size): WindowBounds {
        // Decorated like the extra windows already open, or the platform's usual frame.
        val insets = windows.values.firstOrNull { it.extra && it.window.isShowing }?.window?.insets
            ?: defaultFrameInsets()
        val usable = usableScreenAt(contentTopLeft)
        val width = (contentSize.width + insets.left + insets.right)
            .coerceAtMost(usable?.width?.toFloat() ?: Float.MAX_VALUE)
        val height = (contentSize.height + insets.top + insets.bottom)
            .coerceAtMost(usable?.height?.toFloat() ?: Float.MAX_VALUE)
        return WindowBounds(contentTopLeft.x - insets.left, contentTopLeft.y - insets.top, width, height)
    }

    override fun setHidden(hostId: String, hidden: Boolean) {
        if (hidden) this.hidden.add(hostId) else this.hidden.remove(hostId)
        if (!hidden) raised[hostId] = ++stamp
    }

    override fun sendToBack(hostId: String) {
        windows[hostId]?.window?.toBack()
        raised[hostId] = -(++stamp)
    }

    override fun bringToFront(hostId: String) {
        windows[hostId]?.window?.toFront()
        raised[hostId] = ++stamp
    }

    override fun focus(hostId: String) {
        val w = windows[hostId]?.window ?: return
        w.toFront()
        w.requestFocus()
        raised[hostId] = ++stamp
    }

    override fun setFollowing(hostId: String, following: Boolean) {
        if (following) this.following.add(hostId) else this.following.remove(hostId)
    }

    // ── The pointer while a drag is out of its window ────────────────────────────────

    private var pointerFeed: AWTEventListener? = null

    /**
     * Every mouse event of the app, whichever window AWT delivers it to: the press began on a tab
     * that has left its window (or in a window now being moved under it), so no Compose gesture
     * is following it any more.
     */
    override fun liveDragStarted() {
        if (pointerFeed != null) return
        val feed = AWTEventListener { e ->
            val me = e as? MouseEvent ?: return@AWTEventListener
            val world = world ?: return@AWTEventListener
            val at = me.locationOnScreen.let { Offset(it.x.toFloat(), it.y.toFloat()) }
            when (me.id) {
                MouseEvent.MOUSE_DRAGGED -> world.move(at)
                MouseEvent.MOUSE_RELEASED -> if (me.button == MouseEvent.BUTTON1) world.release(at)
                // A press while the drag is live: its release went missing somewhere. End it.
                MouseEvent.MOUSE_PRESSED -> world.release(at)
            }
        }
        pointerFeed = feed
        Toolkit.getDefaultToolkit().addAWTEventListener(
            feed,
            AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK,
        )
    }

    override fun liveDragEnded() {
        pointerFeed?.let { Toolkit.getDefaultToolkit().removeAWTEventListener(it) }
        pointerFeed = null
    }

    private var escape: KeyEventDispatcher? = null

    /** Esc ends any tab drag — in a window's strip, or out of its window — and is swallowed only then. */
    private fun installEscape() {
        if (escape != null) return
        val dispatcher = KeyEventDispatcher { e ->
            e.id == KeyEvent.KEY_PRESSED && e.keyCode == KeyEvent.VK_ESCAPE &&
                world?.cancelActiveDrag() == true
        }
        escape = dispatcher
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
    }

    // ── Screens ──────────────────────────────────────────────────────────────────────

    private fun usableScreenAt(p: Offset): java.awt.Rectangle? {
        if (GraphicsEnvironment.isHeadless()) return null
        val device = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .firstOrNull { it.defaultConfiguration.bounds.contains(p.x.roundToInt(), p.y.roundToInt()) }
            ?: return null
        val config = device.defaultConfiguration
        val b = config.bounds
        val i = Toolkit.getDefaultToolkit().getScreenInsets(config)
        return java.awt.Rectangle(b.x + i.left, b.y + i.top, b.width - i.left - i.right, b.height - i.top - i.bottom)
    }

    /** A system-decorated window's frame, before any extra window has shown us the real one. */
    private fun defaultFrameInsets(): Insets {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            "mac" in os -> Insets(28, 0, 0, 0)
            "win" in os -> Insets(31, 8, 8, 8)
            else -> Insets(37, 0, 0, 0)
        }
    }
}
