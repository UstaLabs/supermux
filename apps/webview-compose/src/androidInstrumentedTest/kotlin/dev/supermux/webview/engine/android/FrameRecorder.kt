package dev.supermux.webview.engine.android

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Choreographer
import android.view.PixelCopy
import android.view.ViewTreeObserver
import android.view.Window
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * What a window showed in [region], frame by frame, for tests that look at every frame of a change.
 * - As the window is about to draw a frame, [probe] says what the view is doing in it, on the main thread
 *   (`ViewTreeObserver.OnDrawListener`, after composition and layout, so as the frame draws it).
 * - At the next Choreographer frame, a `PixelCopy` of [region] is asked for: it shows the frame last drawn, the one the
 *   probe described. A frame the window didn't draw again isn't copied twice.
 * - Once [arm]ed, the first frame whose probe says [Probe.lifted] is the lift ([liftIndex]). The recorder keeps the
 *   [before] frames before it and the [after] frames from it on, then stops; or once the window has drawn nothing
 *   for [STILL_FRAMES] frames after the lift, as a still page's doesn't. It gives up after [MAX_FRAMES].
 */
internal class FrameRecorder(
    private val window: Window,
    private val region: Rect,
    private val before: Int,
    private val after: Int,
    private val probe: () -> Probe,
) {
    /**
     * What the view was doing in a frame: whether it is [inPlace] (laid out where it shows), whether its cover is
     * [lifted], and what else the test wants to know.
     */
    class Probe(val inPlace: Boolean, val lifted: Boolean, val says: String)

    /** A frame: its number, what the probe said as it was drawn, and what the window showed. */
    class Frame(val index: Int, val probe: Probe, val bitmap: Bitmap)

    private val thread = HandlerThread("frame-copies").apply { start() }
    private val copies = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val kept = ConcurrentHashMap<Int, Frame>()
    private val pending = AtomicInteger(0)
    private val finished = CompletableDeferred<Unit>()

    /** Frames drawn since the start, and what the probe said for the last one. Main thread. */
    @Volatile private var drawn = 0
    private var drawnProbe: Probe? = null
    private var copied = 0
    private var frames = 0
    private var armed = false

    /** Frames since the window last drew one, after the lift: a still window draws none, and shows the same. */
    private var idle = 0

    /** The first frame drawn, once armed, whose probe said lifted; null until then. */
    @Volatile var liftIndex: Int? = null
        private set

    /** The first frame drawn after [arm]. */
    @Volatile var armedAt: Int? = null
        private set

    private val onDraw = ViewTreeObserver.OnDrawListener {
        val said = probe()
        drawn++
        drawnProbe = said
        if (armed && liftIndex == null && said.lifted) liftIndex = drawn
    }

    /** Starts recording. Main thread. */
    fun start() {
        window.decorView.viewTreeObserver.addOnDrawListener(onDraw)
        Choreographer.getInstance().postFrameCallback(::onFrame)
    }

    /** From the next frame on, the first one whose probe says lifted is the lift. Main thread. */
    fun arm() {
        armed = true
        armedAt = drawn + 1
    }

    /** The frames kept, in order, once the recorder has stopped and every copy has come back. */
    suspend fun frames(): List<Frame> {
        finished.await()
        while (pending.get() > 0) delay(10)
        thread.quitSafely()
        return kept.values.sortedBy { it.index }
    }

    private fun onFrame(@Suppress("UNUSED_PARAMETER") frameTimeNanos: Long) {
        frames++
        val index = drawn
        val said = drawnProbe
        val fresh = said != null && index > copied
        if (said != null && fresh) {
            copied = index
            val bitmap = Bitmap.createBitmap(region.width(), region.height(), Bitmap.Config.ARGB_8888)
            pending.incrementAndGet()
            PixelCopy.request(window, region, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) kept[index] = Frame(index, said, bitmap) else bitmap.recycle()
                trim()
                pending.decrementAndGet()
            }, copies)
        }
        val lift = liftIndex
        idle = if (lift != null && !fresh) idle + 1 else 0
        if ((lift != null && copied >= lift + after) || idle >= STILL_FRAMES || frames >= MAX_FRAMES) {
            // Not from inside a draw: a listener can't be removed while the tree dispatches to it.
            main.post { window.decorView.viewTreeObserver.removeOnDrawListener(onDraw) }
            finished.complete(Unit)
            return
        }
        Choreographer.getInstance().postFrameCallback(::onFrame)
    }

    /** Lets go of frames more than [before] frames before the lift, or before the last drawn while there is none. */
    private fun trim() {
        val floor = (liftIndex ?: drawn) - before
        for (old in kept.keys.filter { it < floor }) kept.remove(old)?.bitmap?.recycle()
    }

    companion object {
        /** About 5 s at 120 Hz. */
        const val MAX_FRAMES = 600

        /** A quarter of a second at 120 Hz. */
        const val STILL_FRAMES = 30

        /** One copy of [region] of [window], as it shows now. Main thread. */
        suspend fun copy(window: Window, region: Rect): Bitmap? = suspendCancellableCoroutine { continuation ->
            val bitmap = Bitmap.createBitmap(region.width(), region.height(), Bitmap.Config.ARGB_8888)
            PixelCopy.request(window, region, bitmap, { result ->
                continuation.resume(if (result == PixelCopy.SUCCESS) bitmap else null)
            }, Handler(Looper.getMainLooper()))
        }
    }
}
