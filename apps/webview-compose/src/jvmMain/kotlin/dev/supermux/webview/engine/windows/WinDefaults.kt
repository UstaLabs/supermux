package dev.supermux.webview.engine.windows

/** What the Windows engine's tests decided. */
internal object WinDefaults {
    /**
     * M1 (Task 31, 2026-10-31): 23 MB a view on WebView2 156.0.4314.56 (private bytes, 22.6 and 23.8 MB in two runs: the
     * slope from 1 to 8 article views on their own origins, each its own renderer; 51 to 55 MB of working set), and about
     * 70 MB for the browser, GPU and utility processes the first view starts (none run before it), on the VM's WARP. Well
     * under the 150 MB a view the desktop's budget allows: the desktop's 8 live engines.
     */
    const val MAX_LIVE: Int = 8

    /** Warm spares: the desktop's 3, each a renderer on the floor. */
    const val MAX_IDLE: Int = 3

    /** How long a size waits to settle before WebView2 is resized (the test's 150 ms, C9). */
    const val SIZE_SETTLE_NANOS: Long = 150_000_000L

    /** How long the glue's UI thread waits for Compose's answer to an accelerator (D15). */
    const val KEY_OFFER_MILLIS: Long = 50L

    /** A Tab this recent decides the direction Compose focus entered the surface from (D15). */
    const val TAB_WINDOW_NANOS: Long = 300_000_000L
}
