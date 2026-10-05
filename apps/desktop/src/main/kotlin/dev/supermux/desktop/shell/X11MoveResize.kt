// Native window move/resize for an undecorated X11 frame: the EWMH `_NET_WM_MOVERESIZE` request,
// the same one GTK sends for a client-side-decorated window. The window manager then runs the
// move or resize itself, so it behaves exactly like dragging a native border: GNOME's edge
// snapping and tiling, the resize cursor, a smooth compositor-driven resize, and Esc to cancel.
//
// JetBrains Runtime exposes only the MOVE half of this as an API (`JBR.getWindowMove()`, which
// sends direction 8). The resize directions go through the very same AWT internals JBR's move
// uses (XNETProtocol.startMovingWindowTogetherWithMouse), reached by reflection. That needs
// `--add-opens java.desktop/sun.awt=ALL-UNNAMED --add-opens java.desktop/sun.awt.X11=ALL-UNNAMED`,
// which build.gradle.kts passes on Linux. [load] resolves every handle up front: any miss (another
// toolkit, a JVM without the opens, a renamed internal) returns null, and the app keeps the
// system title bar instead of shipping a window that cannot be resized.
package dev.supermux.desktop.shell

import dev.supermux.desktop.DesktopDebug
import java.awt.Component
import java.awt.Point
import java.awt.Toolkit
import java.awt.Window
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The five data longs of a `_NET_WM_MOVERESIZE` ClientMessage (EWMH): the pointer's root position
 * (device px), the direction, the button (1 = primary), and the source indication (1 = a normal
 * application).
 */
fun moveResizeMessageData(rootX: Int, rootY: Int, direction: WmMoveResizeDirection): LongArray =
    longArrayOf(rootX.toLong(), rootY.toLong(), direction.code, 1L, 1L)

/** `_NET_WM_MOVERESIZE` directions (EWMH). */
enum class WmMoveResizeDirection(val code: Long) {
    TopLeft(0), Top(1), TopRight(2), Right(3), BottomRight(4), Bottom(5), BottomLeft(6), Left(7), Move(8),
}

class X11MoveResize private constructor(
    private val componentAccessor: Any,
    private val getPeer: Method,
    private val getParentTopLevel: Method,
    private val getWindowId: Method,
    private val setGrab: Method,
    private val lastPressLocation: Method,
    private val awtLock: Method,
    private val awtUnlock: Method,
    private val getDisplay: Method,
    private val getRootWindow: Method,
    private val moveResizeAtom: Long,
    private val newEvent: () -> Any,
    private val setType: Method,
    private val setWindow: Method,
    private val setFormat: Method,
    private val setMessageType: Method,
    private val setData: Method,
    private val eventData: Field,
    private val disposeEvent: Method,
    private val ungrabPointer: Method,
    private val ungrabKeyboard: Method,
    private val sendEvent: Method,
    private val flush: Method,
) {
    /**
     * Hands the pointer to the window manager to move or resize [window] from the last button
     * press (left button). Call it from that press (or a drag that follows it) while the button
     * is still down. False when the request could not be sent; nothing has changed then.
     */
    fun start(window: Window, direction: WmMoveResizeDirection): Boolean = runCatching {
        val peer = getPeer.invoke(componentAccessor, window) ?: return failed("no peer for the window")
        val topLevel = getParentTopLevel.invoke(peer) ?: return failed("no top-level peer")
        val xid = getWindowId.invoke(topLevel) as Long
        // Root-window coordinates of the press, in device pixels — what the WM expects.
        val at = lastPressLocation.invoke(null) as Point? ?: return failed("no button press recorded")
        setGrab.invoke(topLevel, false)
        awtLock.invoke(null)
        try {
            val event = newEvent()
            try {
                setType.invoke(event, CLIENT_MESSAGE)
                setWindow.invoke(event, xid)
                setFormat.invoke(event, 32)
                setMessageType.invoke(event, moveResizeAtom)
                moveResizeMessageData(at.x, at.y, direction).forEachIndexed { i, v -> setData.invoke(event, i, v) }
                val display = getDisplay.invoke(null) as Long
                ungrabPointer.invoke(null, display, 0L)
                ungrabKeyboard.invoke(null, display, 0L)
                sendEvent.invoke(
                    null,
                    display,
                    getRootWindow.invoke(null) as Long,
                    false,
                    SUBSTRUCTURE_REDIRECT_AND_NOTIFY,
                    eventData.getLong(event),
                )
                flush.invoke(null, display)
            } finally {
                disposeEvent.invoke(event)
            }
        } finally {
            awtUnlock.invoke(null)
        }
        true
    }.getOrElse { failed(it.toString()) }

    private val warned = AtomicBoolean(false)

    /** False, logging the first failure only: a broken path would otherwise log on every press. */
    private fun failed(why: String): Boolean {
        if (warned.compareAndSet(false, true)) DesktopDebug.log("LinuxWindowChrome", "_NET_WM_MOVERESIZE not sent: $why")
        return false
    }

    companion object {
        private const val CLIENT_MESSAGE = 33
        private const val SUBSTRUCTURE_REDIRECT_AND_NOTIFY = (1L shl 19) or (1L shl 20)

        /** Null unless AWT runs the X11 toolkit and every internal this needs is reachable. */
        fun load(): X11MoveResize? = runCatching {
            if (Toolkit.getDefaultToolkit().javaClass.name != "sun.awt.X11.XToolkit") return null
            val accessorClass = Class.forName("sun.awt.AWTAccessor")
            val componentAccessorClass = Class.forName("sun.awt.AWTAccessor\$ComponentAccessor")
            val sunToolkit = Class.forName("sun.awt.SunToolkit")
            val xToolkit = Class.forName("sun.awt.X11.XToolkit")
            val xWindowPeer = Class.forName("sun.awt.X11.XWindowPeer")
            val xBaseWindow = Class.forName("sun.awt.X11.XBaseWindow")
            val xAtom = Class.forName("sun.awt.X11.XAtom")
            val xlib = Class.forName("sun.awt.X11.XlibWrapper")
            val event = Class.forName("sun.awt.X11.XClientMessageEvent")
            val atom = method(xAtom, "get", String::class.java).invoke(null, "_NET_WM_MOVERESIZE")
            val ctor = event.getDeclaredConstructor().also { it.isAccessible = true }
            X11MoveResize(
                componentAccessor = method(accessorClass, "getComponentAccessor").invoke(null),
                getPeer = method(componentAccessorClass, "getPeer", Component::class.java),
                getParentTopLevel = method(xWindowPeer, "getParentTopLevel"),
                getWindowId = method(xBaseWindow, "getWindow"),
                setGrab = method(xWindowPeer, "setGrab", Boolean::class.javaPrimitiveType!!),
                lastPressLocation = method(xWindowPeer, "getLastButtonPressAbsLocation"),
                awtLock = method(sunToolkit, "awtLock"),
                awtUnlock = method(sunToolkit, "awtUnlock"),
                getDisplay = method(xToolkit, "getDisplay"),
                getRootWindow = method(xToolkit, "getDefaultRootWindow"),
                moveResizeAtom = method(xAtom, "getAtom").invoke(atom) as Long,
                newEvent = { ctor.newInstance() },
                setType = method(event, "set_type", Int::class.javaPrimitiveType!!),
                setWindow = method(event, "set_window", Long::class.javaPrimitiveType!!),
                setFormat = method(event, "set_format", Int::class.javaPrimitiveType!!),
                setMessageType = method(event, "set_message_type", Long::class.javaPrimitiveType!!),
                setData = method(event, "set_data", Int::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!),
                eventData = event.getDeclaredField("pData").also { it.isAccessible = true },
                disposeEvent = method(event, "dispose"),
                ungrabPointer = method(xlib, "XUngrabPointer", Long::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!),
                ungrabKeyboard = method(xlib, "XUngrabKeyboard", Long::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!),
                sendEvent = method(
                    xlib,
                    "XSendEvent",
                    Long::class.javaPrimitiveType!!,
                    Long::class.javaPrimitiveType!!,
                    Boolean::class.javaPrimitiveType!!,
                    Long::class.javaPrimitiveType!!,
                    Long::class.javaPrimitiveType!!,
                ),
                flush = method(xlib, "XFlush", Long::class.javaPrimitiveType!!),
            )
        }.getOrElse {
            DesktopDebug.log("LinuxWindowChrome", "native move/resize unavailable: $it")
            null
        }

        /** A declared method of [cls] or a superclass, made accessible. */
        private fun method(cls: Class<*>, name: String, vararg params: Class<*>): Method {
            var c: Class<*>? = cls
            while (c != null) {
                runCatching { c.getDeclaredMethod(name, *params) }.getOrNull()?.let {
                    it.isAccessible = true
                    return it
                }
                c = c.superclass
            }
            throw NoSuchMethodException("${cls.name}.$name")
        }
    }
}
