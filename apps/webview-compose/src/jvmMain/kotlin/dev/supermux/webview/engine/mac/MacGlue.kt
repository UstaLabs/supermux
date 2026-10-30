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

    /**
     * The glue's one call back (D5), from AppKit's thread or a background one. It decodes and hands over, nothing more;
     * nothing it throws may reach the glue's thread.
     */
    @JvmStatic
    fun onEvent(
        page: Long,
        type: Int,
        epoch: Int,
        id: Long,
        flags: Int,
        x: Double,
        y: Double,
        a: String?,
        b: String?,
        c: String?,
        d: String?,
        data: ByteArray?,
    ) {
        try {
            val event = GlueEvent.decode(type, epoch, id, flags, x, y, a, b, c, d, data) ?: return
            MacPages.post(page, event)
        } catch (e: Throwable) {
            // The event is lost; the glue goes on.
        }
    }

    /** Starts the process's macOS memory-pressure source (warn and critical), once: `MEMORY` upcalls. */
    @JvmStatic external fun startMemoryPressure()

    /** A `MEMORY` upcall now, as the source sends it: for the sample's check. */
    @JvmStatic external fun testMemoryPressure()

    /** The JSON writer (D5) over fixed inputs, one line each: for `MacNativeLibraryMacTest`. */
    @JvmStatic external fun jsonSelfTest(): String
}
