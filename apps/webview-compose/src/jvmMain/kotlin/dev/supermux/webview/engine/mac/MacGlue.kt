package dev.supermux.webview.engine.mac

/**
 * The macOS engine's glue (`native/macos/WV*.m`, D3, D5): every call into it, and its one call back, [onEvent].
 *
 * - `page` arguments are the glue's handle of a page (Task 7), valid from its `CREATED` event until [release].
 * - Every call returns at once and runs later on AppKit's main thread, in the order made, unless its KDoc says it waits.
 * - Lengths are Compose window pixels unless named otherwise.
 */
internal object MacGlue {
    /**
     * The ABI this library speaks: `WV_ABI_VERSION` in `native/macos/WVGlue.h`. Change both together. 3: [setBackdrop]. 4:
     * [place]'s window, [syncFrames], [frameRecorded], [testFrameSync]. 5: [createPage]'s floor at a host of the bundles'
     * scheme, [testWebProcess]. 6: [find], [clearFind], [setPageZoom], [setCookie].
     */
    const val ABI: Int = 6

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

    /**
     * Whether `hitTest:`'s rule gives the page the point ([x], [y]: points from the top-left) of a view [viewHeight]
     * points high, for an [InputRegion]'s [geometry]: for `InputRegionMacTest`.
     */
    @JvmStatic external fun testRegionTakes(geometry: FloatArray, holeCount: Int, density: Float, viewHeight: Float, x: Float, y: Float): Boolean

    // --- Pages (WVPage.m): `PageGlue`'s calls, by the page's handle. ---

    /**
     * Makes a page on AppKit's thread; its `CREATED` upcall, to [token], carries the handle, 0 if WebKit refused. The
     * floor, [floorHtml], is served at [floorHost] on [bundleScheme], the bundles' scheme.
     */
    @JvmStatic external fun createPage(
        token: Long,
        profile: Int,
        storeUuid: String?,
        mediaNeedsGesture: Boolean,
        worldName: String,
        handlerName: String,
        bundleScheme: String,
        floorHost: String,
        floorHtml: String,
    )

    // platform-ok: WebKit's user-agent setting; not an OS check
    @JvmStatic external fun configure(page: Long, dark: Boolean, transparent: Boolean, userAgent: String?, inspectable: Boolean, relay: String, shim: String?)

    @JvmStatic external fun loadUrl(page: Long, epoch: Int, url: String, headerNames: Array<String>, headerValues: Array<String>)

    @JvmStatic external fun loadHtml(page: Long, epoch: Int, html: String, baseUrl: String)

    @JvmStatic external fun reload(page: Long, epoch: Int)

    @JvmStatic external fun goBack(page: Long)

    @JvmStatic external fun goForward(page: Long)

    @JvmStatic external fun stop(page: Long)

    @JvmStatic external fun find(page: Long, request: Long, query: String, backwards: Boolean)

    @JvmStatic external fun clearFind(page: Long)

    @JvmStatic external fun setPageZoom(page: Long, factor: Double)

    @JvmStatic external fun setCookie(
        page: Long, request: Long, name: String, value: String, url: String, path: String, domain: String?,
        secure: Boolean, httpOnly: Boolean, sameSite: String, maxAgeSeconds: Long,
    )

    @JvmStatic external fun evaluate(page: Long, request: Long, script: String, pageWorld: Boolean)

    @JvmStatic external fun deliver(page: Long, body: String, json: String, target: String, eventName: String)

    @JvmStatic external fun repaint(page: Long, request: Long, body: String)

    @JvmStatic external fun snapshot(page: Long, request: Long, widthPoints: Double)

    @JvmStatic external fun saveState(page: Long, request: Long)

    /** Waits for AppKit's thread. */
    @JvmStatic external fun restoreState(page: Long, epoch: Int, state: ByteArray): Boolean

    /** Waits for AppKit's thread. */
    @JvmStatic external fun showFloor(page: Long, epoch: Int): Boolean

    @JvmStatic external fun setMediaSuspended(page: Long, suspended: Boolean)

    @JvmStatic external fun stopMedia(page: Long)

    @JvmStatic external fun removeScripts(page: Long)

    @JvmStatic external fun wipeData(page: Long, request: Long)

    @JvmStatic external fun answerPolicy(page: Long, question: Long, allow: Boolean, javaScript: Boolean)

    @JvmStatic external fun answerResponse(page: Long, question: Long, allow: Boolean)

    @JvmStatic external fun answerDialog(page: Long, question: Long, confirmed: Boolean, text: String?)

    @JvmStatic external fun answerPermission(page: Long, question: Long, grant: Boolean)

    @JvmStatic external fun answerOpenPanel(page: Long, question: Long, paths: Array<String>?)

    @JvmStatic external fun respond(page: Long, task: Long, status: Int, mimeType: String, body: ByteArray, headerNames: Array<String>, headerValues: Array<String>)

    @JvmStatic external fun failTask(page: Long, task: Long)

    @JvmStatic external fun attach(page: Long, window: Long)

    @JvmStatic external fun detach(page: Long, window: Long)

    /**
     * The slot, and the part of it that shows (I2), window px; [scale]: the window's backing scale, window px a point. In a
     * [window] in sync ([syncFrames]), the view moves with the frame skiko draws of this layout, in its transaction.
     */
    @JvmStatic external fun place(
        page: Long,
        window: Long,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        shownX: Float,
        shownY: Float,
        shownWidth: Float,
        shownHeight: Float,
        scale: Float,
        hidden: Boolean,
    )

    /** [flags]: interactive 1, shown 2, wheel to the page 4. */
    @JvmStatic external fun setInput(page: Long, geometry: FloatArray, holeCount: Int, density: Float, flags: Int)

    /** The page's own background, ARGB, which shows where the page was a moment ago while its slot moves; 0: none. */
    @JvmStatic external fun setBackdrop(page: Long, argb: Int)

    /**
     * Whether [window]'s page views move with skiko's frames ([MacFrameSync]): [on], if skiko's layer there is what the
     * glue knows (else they move at their layout, and the glue says why once in the process's log).
     */
    @JvmStatic external fun syncFrames(window: Long, on: Boolean)

    /**
     * Compose has recorded a frame in [window] ([MacFrameSync]'s render delegate, on the EDT): the placements made so far
     * go with the drawable skiko draws that frame into.
     */
    @JvmStatic external fun frameRecorded(window: Long)

    @JvmStatic external fun tick(page: Long, request: Long)

    @JvmStatic external fun focus(page: Long)

    @JvmStatic external fun release(page: Long)

    // --- The test driver (WVTesting.m), for MacEngineTesting. `window`: a ComposeWindow's handle; points from its
    // content's top-left. ---

    /** Waits: true if the app is active and the window key. */
    @JvmStatic external fun testActivate(window: Long): Boolean

    @JvmStatic external fun testClick(window: Long, x: Float, y: Float)

    /** Lowercase a–z only; with [command], each with ⌘. */
    @JvmStatic external fun testKeys(window: Long, text: String, command: Boolean)

    /** Waits: `"page <class>"`, `"compose <class>"` or `"other <class>"`. */
    @JvmStatic external fun testFirstResponder(window: Long): String

    /** Waits for AppKit's thread, which asks Java on the EDT: call it off the EDT. */
    @JvmStatic external fun testAccessibilityReaches(window: Long): Boolean

    /**
     * One event of a trackpad's two-finger scroll, [deltaY] points (natural: positive moves the content down), in its
     * gesture's [phase] or its momentum's ([momentumPhase]), CGEvent's values, 0 for none.
     */
    @JvmStatic external fun testScroll(window: Long, x: Float, y: Float, deltaY: Float, phase: Int, momentumPhase: Int)

    /**
     * Waits: 8 bytes of width and height (big-endian), then RGBA; null if the window server gave nothing. At the screen's
     * scale, or with [nominal] a pixel a point.
     */
    @JvmStatic external fun testCopyWindow(window: Long, x: Float, y: Float, width: Float, height: Float, nominal: Boolean): ByteArray?

    @JvmStatic external fun testPaintBacking(window: Long, argb: Int)

    /** Waits: the first page view's state, for messages. */
    @JvmStatic external fun testDescribePage(window: Long): String

    /** Waits: `"page"` or `"compose"`, for a press or (with [wheel]) a scroll there. */
    @JvmStatic external fun testRoutes(window: Long, x: Float, y: Float, wheel: Boolean): String

    /** Waits: the window's number, for `screencapture -l`. */
    @JvmStatic external fun testWindowNumber(window: Long): Long

    /** Waits: the window's frame sync ([syncFrames]): `"on …"`, `"arming …"`, `"off …"` with its counts, or `"none …"`. */
    @JvmStatic external fun testFrameSync(window: Long): String

    /** Waits: the process id of [page]'s WebContent process (WebKit's own name for it), or -1 if WebKit names none. */
    @JvmStatic external fun testWebProcess(page: Long): Int

    /** Waits: an upcall the library drops, from [count] threads of the glue's own, each ending after it (Minor 3). */
    @JvmStatic external fun testPostFromThreads(count: Int)
}
