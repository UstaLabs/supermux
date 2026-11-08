package dev.supermux.webview.engine.mac

import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate
import org.jetbrains.skiko.Version
import java.awt.Component
import java.awt.Container
import java.util.WeakHashMap

/**
 * Frame sync: a page's view moves in the Core Animation transaction that presents the Compose frame whose layout placed
 * it (the glue's `WVFrameSync.m`), so a page in a list that scrolls stays in its card. Without it, AppKit moves the view
 * at its next commit, and skiko moves the hole when the GPU has drawn Compose's frame: while WebGL pages keep the GPU
 * busy the page runs two to four frames ahead of its card, and snaps back into it when the list stops (a user's report,
 * 2026-11-01: "wiggly woggle").
 *
 * It hooks skiko's own layer, so it is used only where everything it assumes holds; anywhere else a view moves at its
 * layout, as it always could, with the backdrop under the band of its hole it has left. The pins, skiko 0.150.2 (tag
 * `v0.150.2`, the version Compose 1.12.2 brings):
 * - `SkiaLayer.awt.kt` 594–646: on the EDT, `update()` calls the layer's `renderDelegate` (Compose lays out and records
 *   its frame: our placements come from that layout), then makes that recording the one `draw()` draws next (637–641).
 *   Compose sets the delegate once (`SurfaceSkiaLayerComponent`): [RecordingMarker] stands in for it, and tells the glue
 *   once each frame is recorded. A list also lays itself out as a scroll event comes, between frames: what that places
 *   goes with the next recording;
 * - `MetalRedrawer.kt` 188–226: a frame is `update()` then `draw()`, which draws the recording on `dispatcherToBlockOn`
 *   (119–128), one frame at a time;
 * - `MetalContextHandler.mm` 41: the frame's drawable comes from `[device.layer nextDrawable]`; 57–91: `finishFrame`
 *   commits a "Present" command buffer whose scheduled handler presents it (83), unless the layer was resized meanwhile;
 * - `MetalRedrawer.mm` 23–38 and 136–141, `MetalDevice.h` 3: that layer is an `AWTMetalLayer`, a `CAMetalLayer` subclass
 *   with no `nextDrawable` of its own, a sublayer of the window's AWT layer (JAWT's window layer), as our views' layers
 *   are; 137–139 and 161: at most `maximumDrawableCount` (3) frames in flight.
 * Here: the skiko version, the window's renderer, its one `SkiaLayer` and its delegate ([usable]); in the glue: the class,
 * its superclass, no `nextDrawable` of its own, one such layer in the window. If any fails, views move at their layout,
 * and the glue says why once in the process's log. `-Ddev.supermux.webview.mac.frameSync=off` ([PROPERTY]) turns it off.
 * EDT only.
 */
internal object MacFrameSync {
    const val PINNED_SKIKO: String = "0.150.2"

    /** `off` keeps every page view moving at its layout, as before frame sync. */
    const val PROPERTY: String = "dev.supermux.webview.mac.frameSync"

    /**
     * What frame sync depends on, on the JVM's side: skiko's version, the window's renderer (`GraphicsApi`'s name), and
     * whether its frames' recordings are marked ([RecordingMarker] in its one `SkiaLayer`).
     */
    data class Facts(val skikoVersion: String, val renderApi: String?, val recordingsMarked: Boolean, val switchedOff: Boolean)

    fun usable(facts: Facts): Boolean =
        !facts.switchedOff && facts.skikoVersion == PINNED_SKIKO && facts.renderApi == "METAL" && facts.recordingsMarked

    /** The windows the glue was asked about, and their handles: asked again when skiko changes a window's renderer. */
    private val asked = WeakHashMap<java.awt.Window, Long>()

    /** False turns frame sync off in every window now, and on again (where [usable]) when true: the checks' baseline. */
    var enabled: Boolean = true
        set(value) {
            field = value
            for ((window, handle) in asked.entries.toList()) tell(window, handle)
        }

    /**
     * Asks the glue to move [window]'s page views with skiko's frames, if [usable]: once per window ([handle] its
     * `NSWindow`), and again whenever skiko gives it another renderer, which comes with a layer of its own.
     */
    fun ensure(window: java.awt.Window?, handle: Long) {
        if (window == null || handle == 0L || asked[window] == handle) return
        val first = window !in asked
        asked[window] = handle
        if (first) {
            val again = { asked[window]?.let { tell(window, it) } }
            when (window) {
                is ComposeWindow -> window.onRenderApiChanged { again() }
                is ComposeDialog -> window.onRenderApiChanged { again() }
            }
        }
        tell(window, handle)
    }

    private fun tell(window: java.awt.Window, handle: Long) {
        val facts = factsOf(window, mark = handle)
        MacGlue.syncFrames(handle, usable(facts))
    }

    /** [window]'s facts; with [mark] (its handle), its recordings are marked first, where they can be. */
    fun factsOf(window: java.awt.Window?, mark: Long = 0L): Facts = Facts(
        skikoVersion = runCatching { Version.skiko }.getOrDefault("unknown"),
        renderApi = when (window) {
            is ComposeWindow -> window.renderApi.name
            is ComposeDialog -> window.renderApi.name
            else -> null
        },
        recordingsMarked = window != null && mark != 0L && markRecordings(window, mark),
        switchedOff = System.getProperty(PROPERTY) == "off" || !enabled,
    )

    /** Whether [window]'s one `SkiaLayer` tells the glue of each frame it records, as the window [handle]'s: now it does. */
    private fun markRecordings(window: java.awt.Window, handle: Long): Boolean {
        val layer = skiaLayerOf(window) ?: return false
        val delegate = layer.renderDelegate ?: return false
        if (delegate is RecordingMarker) {
            delegate.window = handle
        } else {
            layer.renderDelegate = RecordingMarker(delegate, handle)
        }
        return true
    }

    /** The one `SkiaLayer` in [root]'s tree (a window's), or null if it has none, or more than one (layers drawn apart). */
    fun skiaLayerOf(root: Component): SkiaLayer? {
        val found = ArrayList<SkiaLayer>(1)
        fun visit(component: Component) {
            if (component is SkiaLayer) {
                found += component
                return
            }
            if (component is Container) component.components.forEach(::visit)
        }
        visit(root)
        return found.singleOrNull()
    }
}

/**
 * Compose's render delegate in a window in sync ([MacFrameSync]): [inner] lays out and records the frame, then the glue
 * hears it was, so the placements made so far go with the drawable skiko draws this recording into.
 */
internal class RecordingMarker(val inner: SkikoRenderDelegate, @Volatile var window: Long) : SkikoRenderDelegate {
    override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
        try {
            inner.onRender(canvas, width, height, nanoTime)
        } finally {
            val handle = window
            if (handle != 0L) MacGlue.frameRecorded(handle)
        }
    }
}
