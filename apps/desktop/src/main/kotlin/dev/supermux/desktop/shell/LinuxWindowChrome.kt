// Linux window chrome, the JetBrains way: no system title bar, content edge to edge as on macOS,
// and our own minimise / maximise / close at the top-right (LinuxWindowControls.kt).
//
// Why not JBR's CustomTitleBar (what MacWindowChrome.kt uses)? JetBrains Runtime 21 implements it
// for macOS and Windows only: on Linux `JBR.isWindowDecorationsSupported()` is false and
// `JBR.getWindowDecorations()` is null (checked on the Ubuntu 24.04 GNOME VM, XWayland, JBR
// 21.0.11b1163.116; jbr-api's javadoc says the same). The other JBR route, the
// `xawt.mwm_decor_title=false` root-pane property (Motif "border only"), is ignored by mutter: the
// title bar stays, minus its buttons. So, like IntelliJ's own Linux frame, the window is
// UNDECORATED and the native behaviour comes back piece by piece through the window manager:
//   - move: a drag on the empty top band (sidebar band, tab-strip tails — the same regions macOS
//     registers) starts a WM move via `JBR.getWindowMove()`, so snapping and tiling work
//   - resize: thin edge handles start a WM resize via `_NET_WM_MOVERESIZE` (X11MoveResize.kt)
//   - double-click on the band maximises / restores; minimise and close are our buttons
//
// The custom chrome engages only when every piece is there ([LinuxWindowChrome.detect]); otherwise
// the window keeps the system frame and nothing changes. SUPERMUX_SYSTEM_TITLEBAR=1 forces that.
package dev.supermux.desktop.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.jetbrains.JBR
import java.awt.Frame
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.WindowAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowStateListener
import kotlin.math.abs

/** Height of the Linux top band: the pane tab strip's height, so the band IS the strip row. */
val LinuxTitleBarHeight = 32.dp

/** Diameter of one window button (Adwaita). */
val LinuxWindowButtonSize = 24.dp

/** Gap between window buttons, and the margin around the group. */
val LinuxWindowButtonGap = 8.dp

/** Width the three buttons take at the top-right, margins included. */
val LinuxWindowControlsWidth = LinuxWindowButtonSize * 3 + LinuxWindowButtonGap * 4

object LinuxWindowChrome {
    /** Set to 1 / true to keep the system title bar on Linux. */
    const val OPT_OUT_ENV = "SUPERMUX_SYSTEM_TITLEBAR"

    /** The X11 WM_CLASS; matches the `StartupWMClass` that `addStartupWmClassToDebs` (build.gradle.kts) puts in the .deb's launcher entry. */
    const val WM_CLASS = "supermux"

    /**
     * Names the app's X11 windows [name] (`WM_CLASS`) so GNOME groups them under the launcher's
     * icon and name. Without it AWT derives the class from the main class
     * (`dev-supermux-desktop-MainKt`), and the dock shows an anonymous second icon. Must run
     * before the first window is created. A no-op outside the X11 toolkit or without the
     * `sun.awt.X11` opens.
     */
    fun setWmClass(name: String = WM_CLASS) {
        runCatching {
            val toolkit = Toolkit.getDefaultToolkit()
            if (toolkit.javaClass.name != "sun.awt.X11.XToolkit") return
            toolkit.javaClass.getDeclaredField("awtAppClassName").apply {
                isAccessible = true
                set(null, name)
            }
        }.onFailure { println("[LinuxWindowChrome] could not set WM_CLASS: $it") }
    }

    /**
     * Whether to draw our own chrome. Pure, so the gate is testable without a display.
     */
    fun shouldEngage(
        linux: Boolean,
        optOut: String?,
        windowMoveSupported: Boolean,
        nativeResize: Boolean,
    ): Boolean = linux &&
        optOut?.trim()?.lowercase() !in setOf("1", "true", "yes") &&
        windowMoveSupported &&
        nativeResize

    /**
     * The native move/resize handle when the custom chrome should engage on this machine, else
     * null (keep the system frame). Call once, before the window is created: it decides whether
     * the window is undecorated.
     */
    fun detect(linux: Boolean): X11MoveResize? {
        if (!linux) return null
        val optOut = System.getenv(OPT_OUT_ENV)
        // Opted out: decide on the gate alone and never touch the X11 internals.
        if (!shouldEngage(linux, optOut, windowMoveSupported = true, nativeResize = true)) {
            println("[LinuxWindowChrome] system title bar ($OPT_OUT_ENV=$optOut)")
            return null
        }
        val moveResize = X11MoveResize.load()
        val move = runCatching { JBR.isWindowMoveSupported() }.getOrDefault(false)
        val engage = shouldEngage(linux, optOut, move, moveResize != null)
        println(
            if (engage) "[LinuxWindowChrome] custom chrome on (undecorated, WM move/resize)"
            else "[LinuxWindowChrome] system title bar (windowMove=$move nativeResize=${moveResize != null})",
        )
        return moveResize.takeIf { engage }
    }
}

/** The frame state our buttons drive. [Frame] in the app; a fake in tests. */
interface WindowControlTarget {
    var extendedState: Int

    /** The same path as the system close button: the window's close request. */
    fun requestClose()
}

/** What the three buttons (and a double-click on the band) do. */
object WindowControlActions {
    fun isMaximised(extendedState: Int): Boolean =
        extendedState and Frame.MAXIMIZED_BOTH == Frame.MAXIMIZED_BOTH

    fun minimise(target: WindowControlTarget) {
        target.extendedState = target.extendedState or Frame.ICONIFIED
    }

    fun toggleMaximise(target: WindowControlTarget) {
        val s = target.extendedState
        target.extendedState = if (isMaximised(s)) s and Frame.MAXIMIZED_BOTH.inv() else s or Frame.MAXIMIZED_BOTH
    }

    fun close(target: WindowControlTarget) = target.requestClose()
}

/**
 * A real frame as a [WindowControlTarget]. Close posts WINDOW_CLOSING, which Compose routes to the
 * Window's `onCloseRequest` — hide to the tray when there is one, else quit — exactly as the
 * system close button would.
 */
class FrameControlTarget(private val frame: Frame) : WindowControlTarget {
    override var extendedState: Int
        get() = frame.extendedState
        set(value) {
            frame.extendedState = value
        }

    override fun requestClose() {
        frame.dispatchEvent(WindowEvent(frame, WindowEvent.WINDOW_CLOSING))
    }
}

/** The engaged Linux chrome for one window: what the overlay and the shell read. */
class LinuxWindowChromeInstall(
    val regions: MacChromeRegions,
    val target: WindowControlTarget,
    val maximised: Boolean,
    val startResize: (WmMoveResizeDirection) -> Unit,
    /**
     * Bumped whenever the window is hidden, iconified or deactivated. The pointer leaves without
     * Compose seeing an exit then (close to the tray from the close button), so the buttons key
     * their hover state on it — see [LinuxWindowControls].
     */
    val pointerEpoch: Int = 0,
)

/**
 * Wires the custom chrome into [frame]: tracks maximised state, and turns a drag (or double-click)
 * on a registered drag region inside the top band into a WM move (or maximise / restore).
 */
@Composable
fun rememberLinuxWindowChrome(frame: Frame, moveResize: X11MoveResize): LinuxWindowChromeInstall {
    val regions = remember(frame) { MacChromeRegions() }
    val target = remember(frame) { FrameControlTarget(frame) }
    var maximised by remember(frame) { mutableStateOf(WindowControlActions.isMaximised(frame.extendedState)) }
    var pointerEpoch by remember(frame) { mutableIntStateOf(0) }
    DisposableEffect(frame) {
        val stateListener = WindowStateListener {
            maximised = WindowControlActions.isMaximised(it.newState)
            if (it.newState and Frame.ICONIFIED != 0) pointerEpoch++
        }
        frame.addWindowStateListener(stateListener)
        val focusListener = object : WindowAdapter() {
            override fun windowDeactivated(e: WindowEvent) {
                pointerEpoch++
            }
        }
        frame.addWindowListener(focusListener)
        val hideListener = object : ComponentAdapter() {
            override fun componentHidden(e: ComponentEvent) {
                pointerEpoch++
            }
        }
        frame.addComponentListener(hideListener)
        val mouse = BandDragListener(frame, regions, moveResize, target)
        frame.addMouseListener(mouse)
        frame.addMouseMotionListener(mouse)
        onDispose {
            frame.removeWindowStateListener(stateListener)
            frame.removeWindowListener(focusListener)
            frame.removeComponentListener(hideListener)
            frame.removeMouseListener(mouse)
            frame.removeMouseMotionListener(mouse)
        }
    }
    return LinuxWindowChromeInstall(
        regions = regions,
        target = target,
        maximised = maximised,
        startResize = { moveResize.start(frame, it) },
        pointerEpoch = pointerEpoch,
    )
}

/** What a press on the window does to the band. */
enum class BandPress { Ignore, ToggleMaximise, ArmDrag }

/**
 * The band's response to a press at [yPx] (content px) with AWT [button] / [clickCount]: only the
 * primary button, only inside the band ([LinuxTitleBarHeight] at this monitor's [scale]) and only
 * on a drag region ([inDragRegion]). A double-click maximises / restores; a single press arms a
 * move that starts once the pointer passes [dragPastSlop].
 */
fun bandPressAction(button: Int, clickCount: Int, yPx: Float, scale: Double, inDragRegion: Boolean): BandPress = when {
    button != MouseEvent.BUTTON1 -> BandPress.Ignore
    yPx >= LinuxTitleBarHeight.value * scale -> BandPress.Ignore
    !inDragRegion -> BandPress.Ignore
    clickCount == 2 -> BandPress.ToggleMaximise
    else -> BandPress.ArmDrag
}

/** Pointer travel (AWT points) after which an armed press becomes a window move. */
const val BAND_DRAG_SLOP = 4

/** True once the pointer has left the [slop] box around the press. */
fun dragPastSlop(fromX: Int, fromY: Int, x: Int, y: Int, slop: Int = BAND_DRAG_SLOP): Boolean =
    abs(x - fromX) >= slop || abs(y - fromY) >= slop

/**
 * Drag / double-click on the top band. The band's drag regions are empty chrome (no Compose
 * gesture lives there), so these events need not be taken from Compose; a hole (our buttons, the
 * menu and sidebar toggles) is never a drag region. The move starts only once the pointer has
 * travelled, so a plain click on the band stays a click.
 */
private class BandDragListener(
    private val window: Window,
    private val regions: MacChromeRegions,
    private val moveResize: X11MoveResize,
    private val target: WindowControlTarget,
) : MouseAdapter() {
    private var pressAt: java.awt.Point? = null

    override fun mousePressed(e: MouseEvent) {
        pressAt = null
        // ComposeWindow hands listeners to its content panel, so these are content-relative AWT
        // points; Compose regions are in px, so scale by this monitor's transform.
        val t = window.graphicsConfiguration?.defaultTransform
        val sx = t?.scaleX ?: 1.0
        val sy = t?.scaleY ?: 1.0
        val p = Offset((e.x * sx).toFloat(), (e.y * sy).toFloat())
        when (bandPressAction(e.button, e.clickCount, p.y, sy, regions.allowsNativeDrag(p))) {
            BandPress.ToggleMaximise -> WindowControlActions.toggleMaximise(target)
            BandPress.ArmDrag -> pressAt = e.point
            BandPress.Ignore -> Unit
        }
    }

    override fun mouseDragged(e: MouseEvent) {
        val from = pressAt ?: return
        if (!dragPastSlop(from.x, from.y, e.x, e.y)) return
        pressAt = null
        val moved = runCatching {
            JBR.getWindowMove()?.startMovingTogetherWithMouse(window, MouseEvent.BUTTON1) != null
        }.getOrDefault(false)
        if (!moved) moveResize.start(window, WmMoveResizeDirection.Move)
    }

    override fun mouseReleased(e: MouseEvent) {
        pressAt = null
    }
}
