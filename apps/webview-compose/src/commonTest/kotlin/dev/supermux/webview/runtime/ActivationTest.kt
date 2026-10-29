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
    ) = ActivationInputs(activation, visible, focused, interacted, live, now, lastMovedAt, hiddenSince, graceMillis = 2_000, hiddenReleaseMillis = 600_000)

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

    @Test fun anOffScreenLiveEmbedKeepsItsEngineForTheGracePeriod() {
        val within = decideActivation(inputs(visible = false, live = true, now = 11_000, hiddenSince = 10_000))
        assertTrue(within.wantLive)
        assertEquals(12_000, within.reevaluateAt)
        assertEquals(PRIORITY_HIDDEN, within.priority)
        assertFalse(decideActivation(inputs(visible = false, live = true, now = 12_000, hiddenSince = 10_000)).wantLive)
    }

    @Test fun aHiddenEagerPaneKeepsItsEngineMuchLonger() {
        val within = decideActivation(inputs(Activation.Eager, visible = false, live = true, now = 300_000, hiddenSince = 0))
        assertTrue(within.wantLive)
        assertEquals(600_000, within.reevaluateAt)
        assertFalse(decideActivation(inputs(Activation.Eager, visible = false, live = true, now = 600_000, hiddenSince = 0)).wantLive)
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
