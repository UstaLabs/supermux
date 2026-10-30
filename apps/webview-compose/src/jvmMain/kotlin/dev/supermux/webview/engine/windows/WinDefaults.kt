package dev.supermux.webview.engine.windows

/** What the Windows engine's tests decided (Task 31 measures and sets the budget; until then, the desktop's). */
internal object WinDefaults {
    /** Live engines; each is a renderer process of its own (measured: Task 31). */
    const val MAX_LIVE: Int = 8

    /** Warm spares. */
    const val MAX_IDLE: Int = 3

    /** How long a size waits to settle before WebView2 is resized (the test's 150 ms, C9). */
    const val SIZE_SETTLE_NANOS: Long = 150_000_000L

    /** How long the glue's UI thread waits for Compose's answer to an accelerator (D15). */
    const val KEY_OFFER_MILLIS: Long = 50L

    /** A Tab this recent decides the direction Compose focus entered the surface from (D15). */
    const val TAB_WINDOW_NANOS: Long = 300_000_000L
}
