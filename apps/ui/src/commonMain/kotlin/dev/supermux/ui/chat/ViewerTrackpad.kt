// Two-finger trackpad swipes in the chat's viewer, the way a Mac pages and dismisses: the item
// follows the fingers, and on letting go it goes on to the next one or springs back.
package dev.supermux.ui.chat

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** The axis a two-finger swipe moves on: picked from its first travel, kept to its end. */
enum class SwipeAxis { Horizontal, Vertical }

/**
 * One two-finger trackpad swipe, read from the wheel events it arrives as
 * ([dev.supermux.ui.platform.Caps.trackpadMomentum]). macOS hands Java no "fingers lifted": the
 * fingers' own deltas run straight on into the system's momentum, and the momentum is a steady fall,
 * each delta no bigger than the one before. So the swipe counts as let go once [FALLS] deltas in a
 * row have fallen, by [MIN_DECLINE] or more overall, while still faster than [letGoSpeed] (px per ms:
 * fingers easing off a slow drag fall too, but slowly). Travel is in px, positive toward the next
 * item on x and downward on y.
 */
internal class TrackpadSwipe(private val slopPx: Float, private val letGoSpeed: Float) {
    var axis: SwipeAxis? = null
        private set

    var letGo: Boolean = false
        private set

    private var pendingX = 0f
    private var pendingY = 0f
    private val recent = ArrayDeque<Sample>()
    private var releaseSpeed = 0f

    // The last size of each axis's own steps: AWT hands a diagonal event over as one per axis.
    private var tailX = 0f
    private var tailY = 0f

    private class Sample(val time: Long, val along: Float)

    /**
     * One wheel event. Returns the travel to apply now along [axis]: nothing inside the slop, then
     * the slop's own travel at once, so the picture catches up with the fingers.
     */
    fun add(timeMillis: Long, x: Float, y: Float): Float {
        noteTail(x, y)
        val locked = axis
        if (locked != null) {
            val along = if (locked == SwipeAxis.Horizontal) x else y
            record(timeMillis, along)
            return along
        }
        pendingX += x
        pendingY += y
        if (max(abs(pendingX), abs(pendingY)) < slopPx) return 0f
        val picked = if (abs(pendingX) >= abs(pendingY)) SwipeAxis.Horizontal else SwipeAxis.Vertical
        axis = picked
        // Only this event's own step: the slop's sum is one jump on screen, not one delta of speed.
        record(timeMillis, if (picked == SwipeAxis.Horizontal) x else y)
        return if (picked == SwipeAxis.Horizontal) pendingX else pendingY
    }

    /** The speed the fingers let go at, px/s along [axis]; 0 when the swipe went quiet instead. */
    fun releaseVelocity(): Float = if (letGo) releaseSpeed else 0f

    /**
     * After [letGo]: whether this wheel event begins a new swipe rather than trailing this one's
     * momentum. The momentum only falls; new fingers stop it and start slow, then speed up, or go
     * the other way.
     */
    fun beginsAnother(x: Float, y: Float): Boolean {
        val rises = rises(x, tailX) || rises(y, tailY)
        noteTail(x, y)
        val along = if (axis == SwipeAxis.Horizontal) x else y
        if (abs(along) >= slopPx / 2f && sign(along) != sign(releaseSpeed)) return true
        return rises
    }

    private fun rises(step: Float, before: Float): Boolean = abs(step) >= slopPx / 2f && abs(step) > before * RISE

    private fun noteTail(x: Float, y: Float) {
        if (x != 0f) tailX = abs(x)
        if (y != 0f) tailY = abs(y)
    }

    private fun record(time: Long, along: Float) {
        // AWT splits a diagonal event in two: the other axis's half says nothing about this one.
        if (along == 0f) return
        recent.addLast(Sample(time, along))
        if (recent.size > FALLS + 1) recent.removeFirst()
        if (letGo || recent.size <= FALLS) return
        val first = recent.first().along
        if (first == 0f) return
        var previous = abs(first)
        for (i in 1 until recent.size) {
            val d = recent[i].along
            if (sign(d) != sign(first) || abs(d) > previous) return
            previous = abs(d)
        }
        if (previous > abs(first) * (1f - MIN_DECLINE)) return
        // Events come no closer than a 120 Hz frame; closer, they were bunched up on the way.
        val span = (recent.last().time - recent.first().time).coerceAtLeast(FALLS * MIN_STEP_MS)
        var moved = 0f
        for (i in 1 until recent.size) moved += abs(recent[i].along)
        val speed = moved / span
        if (speed < letGoSpeed) return
        letGo = true
        releaseSpeed = sign(first) * speed * 1000f
    }

    private companion object {
        const val FALLS = 4
        const val MIN_DECLINE = 0.05f
        const val RISE = 1.5f
        const val MIN_STEP_MS = 8L
    }
}

/**
 * Where a sideways swipe that began on page [start] comes to rest, let go at [position] (pages)
 * moving at [velocity] (px/s, toward the next item positive). A flick ([flick] px/s or faster) goes
 * on to the next item in its direction, as long as it moved at least [minTravel] (pages) that way;
 * anything slower rests on the nearest item. Never more than one item from [start].
 */
internal fun swipeRestingPage(start: Int, position: Float, velocity: Float, flick: Float, minTravel: Float, lastIndex: Int): Int {
    val travel = position - start
    val target = when {
        velocity >= flick -> if (travel >= minTravel) floor(position).toInt() + 1 else start
        velocity <= -flick -> if (-travel >= minTravel) ceil(position).toInt() - 1 else start
        else -> position.roundToInt()
    }
    return target.coerceIn(start - 1, start + 1).coerceIn(0, lastIndex)
}

/** A wheel unit in dp on a Mac: AWT hands a trackpad's travel over as a tenth of a point per unit. */
internal val TrackpadScrollUnit = 10.dp

/** Travel before a swipe picks its axis. */
private val AxisSlop = 6.dp

/** Per second: falling deltas faster than this are the system's momentum, not fingers easing off. */
private val LetGoSpeed = 300.dp

/** Per second: let go at least this fast, a swipe goes on to the next item (Compose's fling speed). */
private val FlickSpeed = 400.dp

/** A flick that moved less than this from where it began stays put. */
private val MinFlickTravel = 16.dp

/** No wheel event for this long: the fingers stopped, or lifted with no momentum after them. */
private const val QUIET_MS = 120L

/** The rest of a swipe after letting go: no bounce, about 0.3 s from mid-screen. */
internal val SwipeSettleSpec = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 500f)

/**
 * The viewer's two-finger trackpad swipes on a Mac. Sideways the item follows the fingers and, on
 * letting go, goes on to the next one or springs back, one item per swipe, as Photos and Quick
 * Look do. A pull follows the fingers through [onPull] (px, downward positive) and is judged on
 * letting go by [onPullLetGo] (px/s). [onQuiet] says when a swipe's events, momentum and all, have
 * stopped: a viewer closed earlier hands the rest of the coasting to whatever is under it. Wheel
 * events [owns] declines (⌘/Ctrl + scroll on a picture, a zoomed picture) go on to the page
 * underneath, untouched. Read for the modifier's life: pass callbacks that read state rather than
 * capture it.
 */
internal fun Modifier.viewerTrackpad(
    pager: PagerState,
    owns: (PointerEvent) -> Boolean,
    onPull: (Float) -> Unit,
    onPullLetGo: (Float) -> Unit,
    onQuiet: () -> Unit = {},
): Modifier = pointerInput(pager) {
    val unit = TrackpadScrollUnit.toPx()
    val slop = AxisSlop.toPx()
    val letGoSpeed = LetGoSpeed.toPx() / 1000f
    val flick = FlickSpeed.toPx()
    val minTravel = MinFlickTravel.toPx()
    coroutineScope {
        var settling: Job? = null
        awaitPointerEventScope {
            var carried: WheelStep? = null
            while (true) {
                val first = carried ?: awaitOwnedWheel(owns, unit)
                carried = null
                settling?.cancel()
                val swipe = TrackpadSwipe(slop, letGoSpeed)
                val start = pager.currentPage
                val travel = Channel<Float>(Channel.UNLIMITED)
                var following: Job? = null
                var step: WheelStep? = first
                while (step != null) {
                    val along = swipe.add(step.time, step.x, step.y)
                    when (swipe.axis) {
                        SwipeAxis.Horizontal -> {
                            if (following == null) following = launch { pager.follow(travel, start) }
                            if (along != 0f) travel.trySend(along)
                        }
                        SwipeAxis.Vertical -> if (along != 0f) onPull(along)
                        null -> Unit
                    }
                    if (swipe.letGo) break
                    step = withTimeoutOrNull(QUIET_MS) { awaitOwnedWheel(owns, unit) }
                }
                travel.close()
                val velocity = swipe.releaseVelocity()
                when (swipe.axis) {
                    SwipeAxis.Horizontal -> {
                        val followed = following
                        settling = launch {
                            followed?.join()
                            pager.settleAfterSwipe(start, velocity, flick, minTravel)
                        }
                    }
                    SwipeAxis.Vertical -> onPullLetGo(velocity)
                    null -> Unit
                }
                // Let go into momentum: the rest of it moves nothing, unless new fingers start a swipe.
                if (swipe.letGo) {
                    while (true) {
                        val trailing = withTimeoutOrNull(QUIET_MS) { awaitOwnedWheel(owns, unit) } ?: break
                        if (swipe.beginsAnother(trailing.x, trailing.y)) {
                            carried = trailing
                            break
                        }
                    }
                }
                // No new swipe carried over: nothing more is coming from the pad.
                if (carried == null) onQuiet()
            }
        }
    }
}

private class WheelStep(val time: Long, val x: Float, val y: Float)

/**
 * The next wheel event that [owns] takes, consumed so neither the pager nor the page acts on it as
 * well, as travel in px: x toward the next item, y downward. Natural scrolling is what makes that
 * "with the fingers": fingers moving left scroll right, fingers moving down scroll up.
 */
private suspend fun AwaitPointerEventScope.awaitOwnedWheel(owns: (PointerEvent) -> Boolean, unit: Float): WheelStep {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (event.type != PointerEventType.Scroll || !owns(event)) continue
        val delta = event.changes.fold(Offset.Zero) { acc, c -> acc + c.scrollDelta }
        event.changes.forEach { it.consume() }
        if (delta == Offset.Zero) continue
        return WheelStep(event.changes.first().uptimeMillis, delta.x * unit, -delta.y * unit)
    }
}

private fun PagerState.pageSizePx(): Float = (layoutInfo.pageSize + layoutInfo.pageSpacing).toFloat()

/** Moves with the fingers, held within one item of [start] either way. */
private suspend fun PagerState.follow(travel: ReceiveChannel<Float>, start: Int) {
    scroll(MutatePriority.UserInput) {
        for (px in travel) {
            val size = pageSizePx()
            if (size <= 0f) continue
            val position = currentPage + currentPageOffsetFraction
            scrollBy(px.coerceIn((start - 1 - position) * size, (start + 1 - position) * size))
        }
    }
}

/** Brings a sideways swipe to rest from the speed it was let go at ([swipeRestingPage]). */
private suspend fun PagerState.settleAfterSwipe(start: Int, velocity: Float, flick: Float, minTravel: Float) {
    val size = pageSizePx()
    if (size <= 0f) return
    val position = currentPage + currentPageOffsetFraction
    val target = swipeRestingPage(start, position, velocity, flick, minTravel / size, pageCount - 1)
    val distance = (target - position) * size
    if (distance == 0f) return
    scroll {
        var done = 0f
        animate(0f, distance, initialVelocity = velocity, animationSpec = SwipeSettleSpec) { value, _ ->
            done += scrollBy(value - done)
        }
    }
}
