package dev.supermux.webview.testing

import dev.supermux.webview.engine.mac.MacFrameSync
import dev.supermux.webview.engine.mac.MacGlue
import dev.supermux.webview.engine.mac.MacNativeLibrary
import dev.supermux.webview.engine.mac.MacPlatform
import dev.supermux.webview.engine.mac.MacWebEngine
import dev.supermux.webview.engine.mac.composeWindowHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Window

/**
 * What the sample's macOS engine checks drive the real engine with (D11): input posted into this app's own event queue
 * (no Accessibility permission, the cursor doesn't move), the window as the window server composited it, and AppKit's
 * state. It exists so the engine can be checked in a real window: no API for apps.
 *
 * Windows are Compose windows and dialogs (a `ComposeWindow`'s `windowHandle`); lengths are Compose window pixels, as
 * `onGloballyPositioned` reports them. Without macOS or the engine's glue ([isAvailable]) every member does nothing and
 * answers false, null or "unavailable".
 */
public object MacEngineTesting {
    /** Whether the macOS engine's glue is loaded in this process. */
    public val isAvailable: Boolean get() = MacPlatform.isMac && MacNativeLibrary.ensureLoaded() == null

    /** Asks for the app to be active and [window] key; true if both are. macOS may refuse: then a person clicks once. */
    public fun activate(window: Window): Boolean = handle(window)?.let { MacGlue.testActivate(it) } ?: false

    /** A left click at ([x], [y]), through AppKit's own routing (`sendEvent:`, the monitors, `hitTest:`). */
    public fun click(window: Window, x: Float, y: Float) {
        val handle = handle(window) ?: return
        val scale = scaleOf(window)
        MacGlue.testClick(handle, x / scale, y / scale)
    }

    /** Lowercase a–z only: their key codes need no layout table. */
    public fun type(window: Window, text: String) {
        handle(window)?.let { MacGlue.testKeys(it, text, command = false) }
    }

    /** ⌘ and a lowercase letter. */
    public fun command(window: Window, key: Char) {
        handle(window)?.let { MacGlue.testKeys(it, key.toString(), command = true) }
    }

    /** Who holds the keyboard: `"page <class>"`, `"compose <class>"` or `"other <class>"`. */
    public fun firstResponder(window: Window): String = handle(window)?.let { MacGlue.testFirstResponder(it) } ?: "unavailable"

    /** Whether VoiceOver can reach the first page's web view from [window] (the bridge, M3). Off the EDT: AppKit asks Java there. */
    public suspend fun accessibilityReachesPage(window: Window): Boolean {
        val handle = handle(window) ?: return false
        return withContext(Dispatchers.IO) { MacGlue.testAccessibilityReaches(handle) }
    }

    /** What the window server shows of [window] in this rect, at the screen's scale (M12); null if it gave nothing. */
    public fun copy(window: Window, left: Float, top: Float, width: Float, height: Float): Copied? {
        val handle = handle(window) ?: return null
        val scale = scaleOf(window)
        return copiedOf(MacGlue.testCopyWindow(handle, left / scale, top / scale, width / scale, height / scale, nominal = false) ?: return null)
    }

    /**
     * What the window server shows of [window] in this rect, a pixel a point (a quarter of [copy]'s pixels on a 2×
     * screen): cheap enough to copy every frame of a large area, from any thread.
     */
    public fun copyPoints(window: Window, left: Float, top: Float, width: Float, height: Float): Copied? {
        val handle = handle(window) ?: return null
        val scale = scaleOf(window)
        return copiedOf(MacGlue.testCopyWindow(handle, left / scale, top / scale, width / scale, height / scale, nominal = true) ?: return null)
    }

    /**
     * One event of a trackpad's two-finger scroll at ([x], [y]), as AppKit gets it: [deltaY] points (natural scrolling:
     * positive moves the content down), in its gesture's [phase] or its momentum's ([momentumPhase]). It goes to the view
     * the window's hit test picks, AWT's (and so Compose) outside a pane, as WebKit's test runner sends them: AppKit's
     * own queue would drop it, and pass momentum on only for a gesture the trackpad started. [ScrollPhase] holds the phases.
     */
    public fun scroll(window: Window, x: Float, y: Float, deltaY: Float, phase: Int, momentumPhase: Int) {
        val handle = handle(window) ?: return
        val scale = scaleOf(window)
        MacGlue.testScroll(handle, x / scale, y / scale, deltaY, phase, momentumPhase)
    }

    /** Paints what shows where neither Compose nor a page draws: the motion checks' tell. */
    public fun paintBacking(window: Window, argb: Int) {
        handle(window)?.let { MacGlue.testPaintBacking(it, argb) }
    }

    /** The first page view's state, for messages. */
    public fun describePage(window: Window): String = handle(window)?.let { MacGlue.testDescribePage(it) } ?: "unavailable"

    /** `"page"` or `"compose"`: who a press (or, with [wheel], a scroll) at ([x], [y]) goes to (D7). */
    public fun routes(window: Window, x: Float, y: Float, wheel: Boolean): String {
        val handle = handle(window) ?: return "unavailable"
        val scale = scaleOf(window)
        return MacGlue.testRoutes(handle, x / scale, y / scale, wheel)
    }

    /** [window]'s number, which `screencapture -l` takes; 0 when unavailable. */
    public fun windowNumber(window: Window): Long = handle(window)?.let { MacGlue.testWindowNumber(it) } ?: 0L

    /** A `MEMORY` upcall now, as macOS's memory-pressure source sends it. */
    public fun simulateMemoryPressure() {
        if (isAvailable) MacGlue.testMemoryPressure()
    }

    /**
     * Whether a slot being resized shows the page's snapshot (D2); a page that only moves stays live either way. Off shows
     * the live, lagging page: the motion checks' baseline.
     */
    public var motionCover: Boolean
        get() = MacWebEngine.motionCoverEnabled
        set(value) {
            MacWebEngine.motionCoverEnabled = value
        }

    /** How many times a page's surface has been drawn with the motion cover in this process (the motion checks). */
    public val motionCoverDraws: Int get() = MacWebEngine.coverDraws

    /**
     * Whether a page that moves has its backdrop, its own background where it was a moment ago, under the band of its
     * hole that Compose's frame moves later than AppKit moves the view, where views move at their layout ([frameSync]
     * off). Off shows the window's backing there: the trackpad check's baseline. Pages take it at their next placement.
     */
    public var backdrop: Boolean
        get() = MacWebEngine.backdropEnabled
        set(value) {
            MacWebEngine.backdropEnabled = value
        }

    /**
     * Whether page views move with skiko's frames, in the transaction that presents Compose's frame of their layout
     * (frame sync), wherever the engine can. Off moves them at their layout, ahead of their holes while the GPU is busy,
     * in every window at once: the skew check's baseline. Main thread.
     */
    public var frameSync: Boolean
        get() = MacFrameSync.enabled
        set(value) {
            MacFrameSync.enabled = value
        }

    /**
     * [window]'s frame sync in a few words: `"on …"` (or `"arming …"`, `"off …"`) with what it did (frames presented in
     * sync, directly, late; drawables dropped; placements applied without a frame), or `"none …"` where it has none.
     */
    public fun frameSyncState(window: Window): String = handle(window)?.let { MacGlue.testFrameSync(it) } ?: "unavailable"

    /** The glue's copy: 8 bytes of width and height (big-endian), then RGBA; null if short. */
    internal fun copiedOf(bytes: ByteArray): Copied? {
        if (bytes.size < 8) return null
        fun int(at: Int) = (0 until 4).fold(0) { value, i -> (value shl 8) or (bytes[at + i].toInt() and 0xff) }
        val width = int(0)
        val height = int(4)
        if (width <= 0 || height <= 0 || bytes.size < 8 + width * height * 4) return null
        return Copied(width, height, bytes.copyOfRange(8, 8 + width * height * 4))
    }

    private fun handle(window: Window): Long? = if (!isAvailable) null else composeWindowHandle(window).takeIf { it != 0L }

    /** Compose's density on macOS: the window's backing scale. */
    private fun scaleOf(window: Window): Float = window.graphicsConfiguration?.defaultTransform?.scaleX?.toFloat()?.takeIf { it > 0f } ?: 1f
}

/** Pixels the window server composited: [width] × [height], RGBA, premultiplied, sRGB, top row first. */
public class Copied(public val width: Int, public val height: Int, public val rgba: ByteArray)

/** The phases [MacEngineTesting.scroll] takes: CGEvent's `kCGScrollPhase*` and `kCGMomentumScrollPhase*`. */
public object ScrollPhase {
    public const val NONE: Int = 0
    public const val BEGAN: Int = 1
    public const val CHANGED: Int = 2
    public const val ENDED: Int = 4
    public const val MOMENTUM_BEGIN: Int = 1
    public const val MOMENTUM_CONTINUE: Int = 2
    public const val MOMENTUM_END: Int = 3
}
