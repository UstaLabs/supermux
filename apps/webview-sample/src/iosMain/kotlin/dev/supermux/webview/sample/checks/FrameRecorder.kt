package dev.supermux.webview.sample.checks

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CompletableDeferred
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSSelectorFromString
import platform.QuartzCore.CADisplayLink
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIGraphicsImageRendererFormatRangeStandard
import platform.UIKit.UIImage
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import kotlin.time.TimeSource

/**
 * What a window showed in [region] (window points), display tick by display tick, for checks that look at every frame
 * of a change: iOS's counterpart of the Android tests' frame recorder.
 * - At every tick (`CADisplayLink`), [probe] says what the view is doing, and the window is copied in [region] at
 *   [scale] (`drawViewHierarchyInRect` without screen updates: what the screen has now, the page's own layers and the
 *   canvas over them). The copy can show a frame from before the probe's: a frame is committed at the end of the main
 *   run loop's turn, which may come after this tick's link fires, and a canvas with no native view in it presents its
 *   frames a frame later still (not with the run loop's transaction). So each frame keeps the probes of the two ticks
 *   before it ([Frame.earlier]), and counts as in its place only if all three were.
 * - Once [arm]ed, the first tick whose probe says [Probe.lifted] is the lift ([liftIndex]). The recorder keeps the
 *   [before] frames before it and the [after] frames from it, then stops; it gives up after [MAX_FRAMES].
 */
class FrameRecorder(
    private val window: UIWindow,
    private val region: CValue<CGRect>,
    private val scale: Double,
    private val before: Int,
    private val after: Int,
    private val probe: () -> Probe,
) {
    /** What the view was doing at a tick: [inPlace] (laid out where it shows), its cover [lifted], and what else. */
    class Probe(val inPlace: Boolean, val lifted: Boolean, val says: String)

    /** A frame: its tick, what the probe said then and at the two ticks before ([earlier]), and what the window showed. */
    class Frame(val index: Int, val probe: Probe, val earlier: List<Probe>, val image: UIImage) {
        /** In place at this tick and the two before: whichever frame the copy caught, the view was where it shows. */
        val inPlace: Boolean get() = probe.inPlace && earlier.size == 2 && earlier.all { it.inPlace }
    }

    private val kept = ArrayList<Frame>()
    private val finished = CompletableDeferred<Unit>()
    private val ticker = Ticker(::tick)
    private var link: CADisplayLink? = null
    private var ticks = 0
    private var armed = false
    private val lastProbes = ArrayDeque<Probe>()

    /** How long each copy took, in milliseconds. */
    val copyMillis = ArrayList<Double>()

    /** The first tick, once armed, whose probe said lifted; null until then. */
    var liftIndex: Int? = null
        private set

    /** The first tick after [arm]. */
    var armedAt: Int? = null
        private set

    /** Starts recording. Main thread. */
    fun start() {
        val link = CADisplayLink.displayLinkWithTarget(ticker, NSSelectorFromString("tick"))
        link.addToRunLoop(NSRunLoop.mainRunLoop, NSRunLoopCommonModes)
        this.link = link
    }

    /** From the next tick on, the first one whose probe says lifted is the lift. Main thread. */
    fun arm() {
        armed = true
        armedAt = ticks + 1
    }

    /** The frames kept, in order, once the recorder has stopped. */
    suspend fun frames(): List<Frame> {
        finished.await()
        return kept.toList()
    }

    /** Stops recording now, whatever it has. Main thread. */
    fun stop() {
        link?.invalidate()
        link = null
        finished.complete(Unit)
    }

    private fun tick() {
        ticks++
        val said = probe()
        if (armed && liftIndex == null && said.lifted) liftIndex = ticks
        val mark = TimeSource.Monotonic.markNow()
        val image = copy(window, region, scale)
        copyMillis += mark.elapsedNow().inWholeMicroseconds / 1000.0
        kept += Frame(ticks, said, lastProbes.toList(), image)
        lastProbes.addLast(said)
        if (lastProbes.size > 2) lastProbes.removeFirst()
        val floor = (liftIndex ?: ticks) - before
        kept.removeAll { it.index < floor }
        val lift = liftIndex
        if ((lift != null && ticks >= lift + after) || ticks >= MAX_FRAMES) stop()
    }

    companion object {
        /** About 10 s at 60 Hz. */
        const val MAX_FRAMES = 600

        /** What [window] shows now in [region] (window points), at [scale] pixels a point, in standard colour. */
        fun copy(window: UIWindow, region: CValue<CGRect>, scale: Double): UIImage {
            val format = UIGraphicsImageRendererFormat.preferredFormat()
            format.preferredRange = UIGraphicsImageRendererFormatRangeStandard
            format.scale = scale
            val (x, y) = region.useContents { origin.x to origin.y }
            val (width, height) = region.useContents { size.width to size.height }
            val (windowWidth, windowHeight) = window.bounds.useContents { size.width to size.height }
            return UIGraphicsImageRenderer(size = CGSizeMake(width, height), format = format).imageWithActions { _ ->
                window.drawViewHierarchyInRect(CGRectMake(-x, -y, windowWidth, windowHeight), afterScreenUpdates = false)
            }
        }
    }
}

/** A display link's target: runs [onTick] at every tick. */
@OptIn(BetaInteropApi::class)
private class Ticker(private val onTick: () -> Unit) : NSObject() {
    @ObjCAction
    fun tick() = onTick()
}
