package dev.supermux.webview.sample.checks

import androidx.compose.ui.geometry.Rect
import kotlin.time.TimeSource

/**
 * What a window showed in [region] (window pixels), display frame by display frame, for checks that look at every frame
 * of a change: the Android tests' frame recorder, on iOS's display link or the Mac's Compose frames.
 * - At every frame, [probe] says what the view is doing, and [platform] copies the window in [region]. The copy can show
 *   a frame from before the probe's: a frame is committed at the end of the main thread's turn, which may come after
 *   this frame's callback, and a canvas with no native view in it presents a frame later still. So each frame keeps the
 *   probes of the two frames before it ([Frame.earlier]), and counts as in its place only if all three were.
 * - Once [arm]ed, the first frame whose probe says [Probe.lifted] is the lift ([liftIndex]). The recorder keeps the
 *   [before] frames before it and the [after] frames from it, then stops; it gives up after [MAX_FRAMES].
 */
class FrameRecorder(
    private val platform: CheckPlatform,
    private val region: Rect,
    private val before: Int,
    private val after: Int,
    private val probe: () -> Probe,
) {
    /** What the view was doing at a frame: [inPlace] (laid out where it shows), its cover [lifted], and what else. */
    class Probe(val inPlace: Boolean, val lifted: Boolean, val says: String)

    /** A frame: its index, what the probe said then and at the two frames before ([earlier]), and what the window showed. */
    class Frame(val index: Int, val probe: Probe, val earlier: List<Probe>, val image: Captured) {
        /** In place at this frame and the two before: whichever frame the copy caught, the view was where it shows. */
        val inPlace: Boolean get() = probe.inPlace && earlier.size == 2 && earlier.all { it.inPlace }
    }

    private val kept = ArrayList<Frame>()
    private var ticks = 0
    private var armed = false
    private var stopped = false
    private val lastProbes = ArrayDeque<Probe>()

    /** How long each copy took, in milliseconds. */
    val copyMillis = ArrayList<Double>()

    /** The first frame, once armed, whose probe said lifted; null until then. */
    var liftIndex: Int? = null
        private set

    /** The first frame after [arm]. */
    var armedAt: Int? = null
        private set

    /** Records until the lift and [after] frames more, [MAX_FRAMES], or [stop]; returns the frames kept, in order. */
    suspend fun record(): List<Frame> {
        platform.everyFrame {
            tick()
            !stopped
        }
        return kept.toList()
    }

    /** From the next frame on, the first one whose probe says lifted is the lift. Main thread. */
    fun arm() {
        armed = true
        armedAt = ticks + 1
    }

    /** Stops recording at the next frame, whatever it has. Main thread. */
    fun stop() {
        stopped = true
    }

    /** The frames kept so far, for a recording that had to be cancelled. */
    fun framesSoFar(): List<Frame> = kept.toList()

    private fun tick() {
        ticks++
        val said = probe()
        if (armed && liftIndex == null && said.lifted) liftIndex = ticks
        val mark = TimeSource.Monotonic.markNow()
        val image = platform.copy(region)
        copyMillis += mark.elapsedNow().inWholeMicroseconds / 1000.0
        if (image != null) kept += Frame(ticks, said, lastProbes.toList(), image)
        lastProbes.addLast(said)
        if (lastProbes.size > 2) lastProbes.removeFirst()
        val floor = (liftIndex ?: ticks) - before
        kept.removeAll { it.index < floor }
        val lift = liftIndex
        if ((lift != null && ticks >= lift + after) || ticks >= MAX_FRAMES) stopped = true
    }

    companion object {
        /** About 10 s at 60 Hz. */
        const val MAX_FRAMES = 600
    }
}
