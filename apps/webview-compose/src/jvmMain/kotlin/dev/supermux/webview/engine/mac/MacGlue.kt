package dev.supermux.webview.engine.mac

/**
 * The macOS engine's glue (`native/macos/WV*.m`, D3, D5): every call into it, and its one call back, [onEvent].
 *
 * - `page` arguments are the glue's handle of a page (Task 7), valid from its `CREATED` event until [release].
 * - Every call returns at once and runs later on AppKit's main thread, in the order made, unless its KDoc says it waits.
 * - Lengths are Compose window pixels unless named otherwise.
 */
internal object MacGlue {
    /** The ABI this library speaks: `WV_ABI_VERSION` in `native/macos/WVGlue.h`. Change both together. */
    const val ABI: Int = 1

    /** The loaded glue's ABI; -1 if it couldn't find [onEvent] when it loaded. */
    @JvmStatic external fun abiVersion(): Int
}
