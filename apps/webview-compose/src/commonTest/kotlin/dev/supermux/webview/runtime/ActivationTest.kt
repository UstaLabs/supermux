package dev.supermux.webview.runtime

import dev.supermux.webview.Activation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivationTest {
    private fun inputs(
        activation: Activation = Activation.WhenVisible(settleMillis = 150),
        visible: Boolean = true,
        focused: Boolean = false,
        interacted: Boolean = false,
        live: Boolean = false,
        now: Long = 10_000,
        lastMovedAt: Long = 0,
        hiddenSince: Long? = null,
    ) = ActivationInputs(activation, visible, focused, interacted, live, now, lastMovedAt, hiddenSince, hiddenReleaseMillis = 600_000)

    @Test fun eagerGoesLiveAsSoonAsItIsVisibleEvenWhileMoving() {
        val d = decideActivation(inputs(Activation.Eager, lastMovedAt = 10_000))
        assertTrue(d.wantLive)
        assertEquals(PRIORITY_VISIBLE_EAGER, d.priority)
        assertNull(d.reevaluateAt)
    }

    @Test fun whenVisibleWaitsUntilTheViewHasBeenStill() {
        val moving = decideActivation(inputs(now = 10_100, lastMovedAt = 10_000))
        assertFalse(moving.wantLive)
        assertEquals(10_150, moving.reevaluateAt)
        assertTrue(decideActivation(inputs(now = 10_150, lastMovedAt = 10_000)).wantLive)
    }

    @Test fun aLiveViewIsNeverTornDownWhileVisibleAndMoving() {
        assertTrue(decideActivation(inputs(live = true, now = 10_010, lastMovedAt = 10_000)).wantLive)
    }

    /**
     * Off screen, a live view keeps its engine at the lowest priority, whatever its activation, until it has been hidden
     * for hiddenReleaseMillis: the pool takes it sooner only for a view that needs the slot.
     */
    @Test fun anOffScreenLiveViewKeepsItsEngineUntilTheHiddenLimitWhateverItsActivation() {
        for (activation in listOf(Activation.WhenVisible(settleMillis = 150), Activation.OnInteraction, Activation.Eager)) {
            val within = decideActivation(inputs(activation, visible = false, live = true, now = 300_000, hiddenSince = 10_000))
            assertTrue(within.wantLive, "$activation")
            assertEquals(610_000, within.reevaluateAt, "$activation")
            assertEquals(PRIORITY_HIDDEN, within.priority, "$activation")
            val past = decideActivation(inputs(activation, visible = false, live = true, now = 610_000, hiddenSince = 10_000))
            assertFalse(past.wantLive, "$activation")
        }
    }

    @Test fun aColdHiddenViewStaysCold() {
        assertFalse(decideActivation(inputs(Activation.Eager, visible = false)).wantLive)
        assertFalse(decideActivation(inputs(visible = false)).wantLive)
    }

    @Test fun onInteractionWaitsForATap() {
        assertFalse(decideActivation(inputs(Activation.OnInteraction)).wantLive)
        assertTrue(decideActivation(inputs(Activation.OnInteraction, interacted = true)).wantLive)
    }

    @Test fun focusOutranksEverythingButOnlyWhileVisible() {
        assertEquals(PRIORITY_FOCUSED, decideActivation(inputs(focused = true)).priority)
        assertEquals(PRIORITY_HIDDEN, decideActivation(inputs(focused = true, visible = false)).priority)
        assertEquals(PRIORITY_VISIBLE, decideActivation(inputs()).priority)
    }

    @Test fun aHiddenLiveViewWithNoHiddenSinceCountsAsHiddenSinceNow() {
        val d = decideActivation(inputs(visible = false, live = true, now = 10_000, hiddenSince = null))
        assertTrue(d.wantLive)
        assertEquals(610_000, d.reevaluateAt)
    }

    @Test fun aHiddenEagerPaneRanksAsHiddenEvenWhenFocused() {
        val hidden = inputs(Activation.Eager, visible = false, live = true, now = 10_000, hiddenSince = 9_000)
        assertEquals(PRIORITY_HIDDEN, decideActivation(hidden).priority)
        assertEquals(PRIORITY_HIDDEN, decideActivation(hidden.copy(focused = true)).priority)
    }

    @Test fun aWaitTooLongToAddUpMeansNeverNotNow() {
        // Long.MAX_VALUE spells "never": the deadline saturates instead of wrapping into the past.
        val keptForever = inputs(Activation.Eager, visible = false, live = true, now = 10_000, hiddenSince = 5_000)
            .copy(hiddenReleaseMillis = Long.MAX_VALUE)
        assertTrue(decideActivation(keptForever).wantLive)
        val neverSettles = decideActivation(
            inputs(Activation.WhenVisible(settleMillis = Long.MAX_VALUE), now = 10_100, lastMovedAt = 10_000),
        )
        assertFalse(neverSettles.wantLive)
        assertEquals(Long.MAX_VALUE, neverSettles.reevaluateAt)
    }
}
