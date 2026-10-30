package dev.supermux.webview.layouttest

/** The page's messages. The glue calls it on the AppKit thread: implementations only hand them over. */
fun interface PageListener {
    fun onMessage(json: String)
}

/**
 * The native glue (native/macos/LT*.m), 17 entry points; build.sh checks the dylib exports exactly that many. Call
 * everything on the EDT. `window` is `ComposeWindow.windowHandle` (an NSWindow), `page` what [attach] returned. Lengths
 * are Compose window px unless named otherwise.
 */
object Native {
    /** Loads the dylib named by -Dlayouttest.nativeLibrary, as terminal-core's -Dsupermux.terminal.nativeLibrary does. */
    fun load() {
        val path = System.getProperty("layouttest.nativeLibrary")
            ?: error("-Dlayouttest.nativeLibrary=<path to libwebview_layout_test.dylib> is required")
        System.load(path)
    }

    /** Adds the page view to the window's content view, loads [html] and returns the page's handle. */
    @JvmStatic external fun attach(window: Long, html: String, listener: PageListener): Long

    /** 0: layout A, under skiko's layer; 1: layout B, over it ([Layout.ordinal]). */
    @JvmStatic external fun setLayout(page: Long, layout: Int)

    @JvmStatic external fun setFrame(page: Long, density: Float, left: Float, top: Float, width: Float, height: Float)

    /** [PackedCutouts]: page-local window px. */
    @JvmStatic external fun setCutouts(page: Long, density: Float, geometry: FloatArray, holeCount: Int, scrimColors: IntArray)

    @JvmStatic external fun evaluate(page: Long, script: String)

    /** The ⌘-key monitor: ⌘C/X/V/A/Z/⇧Z go to the page as actions while it is first responder. */
    @JvmStatic external fun setKeyRouting(page: Long, enabled: Boolean)

    @JvmStatic external fun detach(page: Long)

    /** Asks for the app to be active and the window key; true if both already are. macOS may refuse. */
    @JvmStatic external fun activate(window: Long): Boolean

    @JvmStatic external fun click(window: Long, density: Float, x: Float, y: Float)

    /** A pixel-unit wheel event of [deltaY] points at ([x], [y]). */
    @JvmStatic external fun scroll(window: Long, density: Float, x: Float, y: Float, deltaY: Float)

    /** Lowercase ASCII letters only. */
    @JvmStatic external fun typeText(window: Long, text: String)

    /** ⌘ and one lowercase letter. */
    @JvmStatic external fun commandKey(window: Long, key: String)

    /** [WindowInfo] as JSON. */
    @JvmStatic external fun windowInfo(window: Long): String

    @JvmStatic external fun layerTree(window: Long): String

    /** "page <class>", "compose <class>" or "other <class>". */
    @JvmStatic external fun firstResponder(window: Long): String

    /** Answers later, as a [PageEvent.Ax] through the page's listener. */
    @JvmStatic external fun probeAccessibility(window: Long)

    @JvmStatic external fun setAccessibilityBridge(window: Long, enabled: Boolean)
}
