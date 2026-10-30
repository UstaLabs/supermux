package dev.supermux.webview.engine.linux

/**
 * What the WPE feasibility test decided (docs/superpowers/plans/2026-10-30-webview-linux-wpe.md, Results). Each value
 * names the check that set it; change one only with a new measurement.
 */
internal object WpeDefaults {
    /** S5: damage rectangles are buffer pixels (1.0), logical pixels (the view's scale), or unusable (0.0: whole frames). */
    const val DAMAGE_SCALE: Double = 1.0

    /** L4: what wheel deltas are multiplied by, so the page scrolls the way the wheel turns. WPE takes them the other way round from Compose. */
    const val SCROLL_SIGN: Double = -1.0

    /** S7: WebKit on GLib's global main context instead of a private one. */
    const val GLOBAL_CONTEXT: Boolean = false

    /**
     * M1: live engines on Linux. The test measured 141.7 MB a view, each its own web process, close to the 150 MB bar on
     * a light page; and every page paints in its own process, on the CPU where there's no GPU path. So Linux takes 6,
     * spec §4's tablet budget, below the desktop's 8.
     */
    const val MAX_LIVE: Int = 6

    /**
     * Spares on Linux: each is a web process too, so 2 instead of the desktop's 3. With [MAX_LIVE] that is at most 8 WPE
     * web processes, 1,436 MB in the test's measurement, against about 1,850 MB for the desktop's 8 + 3.
     */
    const val MAX_IDLE: Int = 2

    /** I1–I2: whether a page's composition goes through WPE's input-method context (false defers IME, Task 24). */
    const val IME: Boolean = true
}
