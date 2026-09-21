// The phone's edge-swipe back, drawn the way each platform draws its own.
//
// Android keeps the predictive-back preview: the open layer shrinks a little and fades, and the
// list slides in once the system commits. iOS does something else entirely, and imitating
// Android there is what made the swipe feel wrong on an iPhone. UIKit's interactive pop has three
// parts:
//
//  1. The open screen tracks the finger 1:1. Compose's iOS input reports `progress` as the finger's
//     travel over the view width (`IosBackNavigationEventInput`), so progress × width IS the
//     finger.
//  2. The screen underneath is already there during the drag. It starts 30% of the width to the
//     left, moves in at a slower rate, and has a light dimming over it that fades out as the swipe
//     goes on. The top screen casts a soft shadow on its left edge.
//  3. On release UIKit animates the rest of the way from wherever the finger let go. Compose does
//     not: it fires `onBackCompleted` straight away (a flick faster than 100pt/s, or a drag past
//     30% of the width) or `onBackCancelled`. So this file finishes the motion itself, forward to
//     the list or back to the chat, and only THEN clears the selection. Clearing it first
//     blanks the chat panel while it is still on screen.

package dev.supermux.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** True where the edge-swipe back should look like UIKit's interactive pop (iOS). */
internal expect val iosStyleBackSwipe: Boolean

/** How far left the screen underneath starts, as a fraction of the width (UIKit uses 30%). */
private const val UNDER_PARALLAX = 0.3f

/** Opacity of the dimming over the screen underneath when the swipe begins. */
private const val UNDER_DIM = 0.1f

/** Opacity of the shadow the moving screen casts on its left edge. */
private const val EDGE_SHADOW = 0.18f

/**
 * Critically damped, about 0.3 s from mid-screen: close to UIKit's finish after the finger lifts,
 * and with no bounce, which a pop never has.
 */
private val settleSpec = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 500f)

/** The iOS swipe's shared state: the finger's position and whether the pop just landed. */
@Stable
internal class IosBackSwipe {
    /** 0 = the chat fills the screen, 1 = it has slid fully off to the right. */
    val progress = Animatable(0f)

    /** True while any of the swipe is on screen, so the list underneath must be composed. */
    val revealing by derivedStateOf { progress.value > 0f }

    /**
     * True for the one frame in which a finished swipe clears the selection. The list is already in
     * place by then, so its own slide-in would play the pop a second time.
     */
    var landed by mutableStateOf(false)
        private set

    suspend fun track(fraction: Float) = progress.snapTo(fraction)

    /** Finish a committed swipe from where the finger let go, then run [pop]. */
    suspend fun complete(pop: () -> Unit) {
        progress.animateTo(1f, settleSpec)
        landed = true
        pop()
        // Two frames: one for the list to recompose in place, one for the transition to start with
        // `landed` still set. Then the next open or close animates normally again.
        withFrameNanos { }
        withFrameNanos { }
        progress.snapTo(0f)
        landed = false
    }

    /** Return a cancelled swipe to the chat. */
    suspend fun cancel() = progress.animateTo(0f, settleSpec)
}

/** The open chat or workspace: follows the finger and casts a shadow on the list underneath. */
internal fun Modifier.iosSwipedLayer(swipe: IosBackSwipe): Modifier = this
    .graphicsLayer { translationX = swipe.progress.value * size.width }
    .drawBehind {
        val p = swipe.progress.value
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

/** The session list during a swipe: parallaxed in from the left under a fading dim. */
internal fun Modifier.iosSwipeUnderLayer(swipe: IosBackSwipe): Modifier = this
    .graphicsLayer { translationX = -UNDER_PARALLAX * size.width * (1f - swipe.progress.value) }
    .drawWithContent {
        drawContent()
        drawRect(Color.Black, alpha = UNDER_DIM * (1f - swipe.progress.value))
    }
