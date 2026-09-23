// Back, drawn the way each platform draws its own.
//
// Android keeps a plain back (and the phone chat layer keeps its predictive-back preview: the open
// layer shrinks a little and fades). iOS does something else entirely, and imitating Android there
// is what made going back feel wrong on an iPhone. UIKit's interactive pop has three parts:
//
//  1. The open page tracks the finger 1:1. Compose's iOS input reports `progress` as the finger's
//     travel over the view width (`IosBackNavigationEventInput`), so progress × width IS the
//     finger.
//  2. The page underneath is already there during the drag. It starts 30% of the width to the
//     left, moves in at a slower rate, and has a light dimming over it that fades out as the swipe
//     goes on. The top page casts a soft shadow on its left edge.
//  3. On release UIKit animates the rest of the way from wherever the finger let go. Compose does
//     not: it fires `onBackCompleted` straight away (a flick faster than 100pt/s, or a drag past
//     30% of the width) or `onBackCancelled`. So [IosBackSwipe] finishes the motion itself, forward
//     to the page underneath or back to the open one, and only THEN runs the back. Running it first
//     would blank or remove the page while it is still on screen.
//
// Every page that can be backed out of uses the same three pieces: a [SwipeBackHandler] that owns
// the gesture, [iosSwipedLayer] on the page being swiped off, and [iosSwipeUnderLayer] on the page
// it reveals ([SwipeBackPages] puts the two together for a page pushed inside one screen).

package dev.supermux.ui.widgets

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.backhandler.PredictiveBackHandler
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** True where going back should look like UIKit's interactive pop (iOS). */
internal expect val iosStyleBackSwipe: Boolean

/** How far left the page underneath starts, as a fraction of the width (UIKit uses 30%). */
private const val UNDER_PARALLAX = 0.3f

/** Opacity of the dimming over the page underneath when the swipe begins. */
private const val UNDER_DIM = 0.1f

/** Opacity of the shadow the moving page casts on its left edge. */
private const val EDGE_SHADOW = 0.18f

/**
 * Critically damped, about 0.3 s from mid-screen: close to UIKit's finish after the finger lifts,
 * and with no bounce, which a pop never has.
 */
private val settleSpec = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 500f)

/**
 * One back-able page's swipe: the page's offset, whether the pop just landed, and its push-in.
 *
 * Remember it with [rememberIosBackSwipe] in the composable that OUTLIVES the page (the one that
 * decides whether the page shows): the finish runs in that scope, because the back it ends with
 * removes the page, and a scope inside the page would be cancelled mid-animation.
 */
@Stable
class IosBackSwipe internal constructor(private val scope: CoroutineScope) {
    private val offset = mutableFloatStateOf(0f)

    /** Every motion (finger, finish, cancel, push-in) takes this; a new one cancels the last. */
    private val mutex = MutatorMutex()

    /** 0 = the page fills the screen, 1 = it is fully off to the right. */
    val progress: Float get() = offset.floatValue

    /** True while any of the swipe is on screen, so the page underneath must be composed. */
    val revealing by derivedStateOf { offset.floatValue > 0f }

    /**
     * True for the frames in which a finished swipe runs its back. The page underneath is already
     * in place by then, so a transition of its own would play the pop a second time.
     */
    var landed by mutableStateOf(false)
        private set

    internal suspend fun track(fraction: Float) = mutex.mutate { offset.floatValue = fraction }

    private suspend fun animateTo(target: Float) = mutex.mutate {
        animate(offset.floatValue, target, animationSpec = settleSpec) { v, _ -> offset.floatValue = v }
    }

    /** Finish a committed swipe from where the finger let go, then run [pop]. */
    internal fun complete(pop: () -> Unit) {
        scope.launch {
            try {
                animateTo(1f)
                landed = true
                pop()
                // Two frames: one for the page underneath to recompose in place, one for any
                // transition to start with `landed` still set.
                withFrameNanos { }
                withFrameNanos { }
            } finally {
                withContext(NonCancellable) {
                    landed = false
                    mutex.mutate { offset.floatValue = 0f }
                }
            }
        }
    }

    /** Return a cancelled swipe to the page. */
    internal fun cancel() {
        scope.launch { animateTo(0f) }
    }

    /**
     * Park a page that is about to appear off screen to the right. Called from COMPOSITION, in the
     * very pass that first shows the page, so its first frame is already off screen — an effect
     * would run a frame late and flash the page in place first. [pushIn] then brings it on.
     */
    internal fun parkOffScreen() {
        offset.floatValue = 1f
    }

    /** Slide a parked page in: UIKit's push, the pop's motion in reverse. */
    internal suspend fun pushIn() = animateTo(0f)
}

@Composable
fun rememberIosBackSwipe(): IosBackSwipe {
    val scope = rememberCoroutineScope()
    return remember(scope) { IosBackSwipe(scope) }
}

/**
 * iOS: slide the page this [swipe] moves in from the right whenever [key] changes to a non-null
 * value — and on first composition too, unless [animateInitial] is false (a page that is already
 * open when the screen appears: a cold start, a deep link). Nothing elsewhere.
 */
@Composable
fun IosPushIn(swipe: IosBackSwipe, key: Any? = Unit, animateInitial: Boolean = true) {
    if (!iosStyleBackSwipe) return
    val seen = remember { KeyHolder(if (animateInitial) NoKey else key) }
    if (key != null && key != seen.key) {
        seen.key = key
        seen.entering = true
        swipe.parkOffScreen()
    } else if (key == null) {
        seen.key = null
    }
    LaunchedEffect(key) {
        if (seen.entering) {
            seen.entering = false
            swipe.pushIn()
        }
    }
}

private object NoKey

private class KeyHolder(var key: Any?) {
    var entering = false
}

/**
 * The swipe of the page this content sits in, for a [SwipeBackHandler] deep inside it that leaves
 * the whole page (the shell provides it for every full-screen route).
 */
val LocalIosBackSwipe = staticCompositionLocalOf<IosBackSwipe?> { null }

/**
 * Back for a page. On iOS the edge swipe drags the page with [swipe] and runs [onBack] once the
 * page is off screen; everywhere else — and on iOS when [interactive] is false, e.g. a page whose
 * back asks first — it is an ordinary [BackHandler].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SwipeBackHandler(
    enabled: Boolean = true,
    swipe: IosBackSwipe? = LocalIosBackSwipe.current,
    interactive: Boolean = true,
    onBack: () -> Unit,
) {
    if (!iosStyleBackSwipe || swipe == null || !interactive) {
        BackHandler(enabled = enabled, onBack = onBack)
        return
    }
    val currentOnBack by rememberUpdatedState(onBack)
    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { e -> swipe.track(e.progress) }
            swipe.complete { currentOnBack() }
        } catch (_: Throwable) {
            swipe.cancel()
        }
    }
}

/** The page being swiped: follows the finger and casts a shadow on the page underneath. */
fun Modifier.iosSwipedLayer(swipe: IosBackSwipe): Modifier = this
    .graphicsLayer { translationX = swipe.progress * size.width }
    .drawBehind {
        val p = swipe.progress
        if (p <= 0f) return@drawBehind
        val w = 10.dp.toPx()
        drawRect(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = EDGE_SHADOW * (1f - p))),
                startX = -w,
                endX = 0f,
            ),
            topLeft = Offset(-w, 0f),
            size = Size(w, size.height),
        )
    }

/** The page underneath a swipe: parallaxed in from the left under a fading dim. Inert at rest. */
fun Modifier.iosSwipeUnderLayer(swipe: IosBackSwipe): Modifier = this
    .graphicsLayer {
        val p = swipe.progress
        translationX = if (p > 0f) -UNDER_PARALLAX * size.width * (1f - p) else 0f
    }
    .drawWithContent {
        drawContent()
        val p = swipe.progress
        if (p > 0f) drawRect(Color.Black, alpha = UNDER_DIM * (1f - p))
    }

/**
 * A page pushed inside one screen: [page] over [under] while [pushed], only [under] otherwise.
 * During a swipe [under] is composed beneath the page, so it is already there when the page leaves.
 * Pair it with a [SwipeBackHandler] on the same [swipe].
 */
@Composable
fun SwipeBackPages(
    pushed: Boolean,
    swipe: IosBackSwipe,
    pageBackground: Color,
    modifier: Modifier = Modifier,
    under: @Composable () -> Unit,
    page: @Composable () -> Unit,
) {
    // A page pushed while this screen shows slides in; one that is open as the screen appears
    // (a deep link straight to it) does not.
    IosPushIn(swipe, key = if (pushed) Unit else null, animateInitial = false)
    Box(modifier.fillMaxSize()) {
        if (!pushed || swipe.revealing) {
            Box(Modifier.fillMaxSize().then(if (pushed) Modifier.iosSwipeUnderLayer(swipe) else Modifier)) {
                under()
            }
        }
        if (pushed) {
            Box(Modifier.fillMaxSize().iosSwipedLayer(swipe).background(pageBackground)) {
                page()
            }
        }
    }
}
