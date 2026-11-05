package dev.supermux.ui.overlay

import dev.supermux.ui.overlay.CompanionGesture.Intent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompanionGestureTest {
    private fun gesture(lock: Float = 70f) = CompanionGesture(holdMs = 320, slop = 8f, cancelDistance = 70f, lockDistance = lock)

    @Test
    fun aQuickPressIsATap() {
        val g = gesture()
        g.down(100f, 500f, at = 0)
        assertEquals(listOf<Intent>(Intent.Tap), g.up(at = 120))
    }

    @Test
    fun jitterInsideTheSlopIsStillATap() {
        val g = gesture()
        g.down(100f, 500f, at = 0)
        assertEquals(emptyList(), g.move(104f, 503f, at = 40))
        assertEquals(listOf<Intent>(Intent.Tap), g.up(at = 90))
    }

    @Test
    fun movingBeforeTheHoldIsADrag() {
        val g = gesture()
        g.down(100f, 500f, at = 0)
        assertEquals(listOf(Intent.DragStart, Intent.DragTo(20f, -10f)), g.move(120f, 490f, at = 50))
        assertEquals(listOf<Intent>(Intent.DragTo(60f, -40f)), g.move(160f, 460f, at = 80))
        assertEquals(listOf<Intent>(Intent.DragEnd), g.up(at = 120))
    }

    @Test
    fun aDragNeverTurnsIntoListening() {
        val g = gesture()
        g.down(100f, 500f, at = 0)
        g.move(130f, 500f, at = 50)
        assertEquals(emptyList(), g.hold(at = 400))
    }

    @Test
    fun holdingStartsListeningAndLettingGoSends() {
        val g = gesture()
        g.down(100f, 500f, at = 0)
        assertEquals(listOf<Intent>(Intent.ListenStart), g.hold(at = 320))
        assertEquals(listOf<Intent>(Intent.ListenSend), g.up(at = 2000))
    }

    @Test
    fun aTimerThatFiresEarlyDoesNothing() {
        val g = gesture()
        g.down(100f, 500f, at = 0)
        assertEquals(emptyList(), g.hold(at = 200))
        assertEquals(listOf<Intent>(Intent.Tap), g.up(at = 250))
    }

    @Test
    fun jitterInsideTheSlopStillLetsTheHoldFire() {
        val g = gesture()
        g.down(100f, 500f, at = 0)
        g.move(105f, 497f, at = 100)
        assertEquals(listOf<Intent>(Intent.ListenStart), g.hold(at = 330))
    }

    @Test
    fun slidingTowardTheMiddleArmsCancelAndLettingGoCancels() {
        val g = gesture()
        g.down(100f, 500f, at = 0)
        g.hold(at = 320)
        assertEquals(listOf<Intent>(Intent.ListenSlide(cancelArmed = true, lockArmed = false)), g.move(180f, 500f, at = 600))
        assertEquals(listOf<Intent>(Intent.ListenCancel), g.up(at = 700))
    }

    @Test
    fun slidingBackDisarmsCancel() {
        val g = gesture()
        g.down(100f, 500f, at = 0)
        g.hold(at = 320)
        g.move(180f, 500f, at = 600)
        assertEquals(listOf<Intent>(Intent.ListenSlide(cancelArmed = false, lockArmed = false)), g.move(120f, 500f, at = 650))
        assertEquals(listOf<Intent>(Intent.ListenSend), g.up(at = 700))
    }

    @Test
    fun onTheRightEdgeTheMiddleIsToTheLeft() {
        val g = gesture()
        g.towardMiddle = -1
        g.down(300f, 500f, at = 0)
        g.hold(at = 320)
        assertEquals(emptyList(), g.move(380f, 500f, at = 500))
        assertEquals(listOf<Intent>(Intent.ListenSlide(cancelArmed = true, lockArmed = false)), g.move(220f, 500f, at = 600))
        assertEquals(listOf<Intent>(Intent.ListenCancel), g.up(at = 700))
    }

    @Test
    fun slidingUpLocksAndLettingGoKeepsListening() {
        val g = gesture()
        g.down(100f, 500f, at = 0)
        g.hold(at = 320)
        assertEquals(listOf<Intent>(Intent.ListenSlide(cancelArmed = false, lockArmed = true)), g.move(100f, 460f, at = 500))
        assertEquals(listOf<Intent>(Intent.ListenLock), g.move(100f, 425f, at = 550))
        assertEquals(emptyList(), g.up(at = 600))
    }

    @Test
    fun withoutALockSlidingUpDoesNothing() {
        val g = gesture(lock = 0f)
        g.down(100f, 500f, at = 0)
        g.hold(at = 320)
        assertEquals(emptyList(), g.move(100f, 300f, at = 500))
        assertEquals(listOf<Intent>(Intent.ListenSend), g.up(at = 600))
    }

    @Test
    fun anInterruptedPressEndsWhatItStarted() {
        val listening = gesture().apply { down(100f, 500f, at = 0); hold(at = 320) }
        assertEquals(listOf<Intent>(Intent.ListenCancel), listening.cancel())
        val dragging = gesture().apply { down(100f, 500f, at = 0); move(140f, 500f, at = 40) }
        assertEquals(listOf<Intent>(Intent.DragEnd), dragging.cancel())
        val pressed = gesture().apply { down(100f, 500f, at = 0) }
        assertEquals(emptyList(), pressed.cancel())
    }

    @Test
    fun aNewPressStartsClean() {
        val g = gesture()
        g.down(100f, 500f, at = 0)
        g.hold(at = 320)
        g.move(100f, 420f, at = 500)
        g.up(at = 600)
        g.down(100f, 500f, at = 5000)
        assertEquals(listOf<Intent>(Intent.Tap), g.up(at = 5080))
    }

    // A double-tap puts the Agent's glasses on; while one can come, a single tap waits to be sure.
    private fun doubleTapping() = gesture().apply { doubleTapMs = 300 }

    @Test
    fun aTapWaitsForASecondOneThenIsATap() {
        val g = doubleTapping()
        g.down(100f, 500f, at = 0)
        assertEquals(emptyList(), g.up(at = 80))
        assertTrue(g.tapWaiting)
        assertEquals(emptyList(), g.tapTimeout(at = 300)) // a timer that fires early does nothing
        assertEquals(listOf<Intent>(Intent.Tap), g.tapTimeout(at = 380))
        assertFalse(g.tapWaiting)
        assertEquals(emptyList(), g.tapTimeout(at = 400)) // and only once
    }

    @Test
    fun aSecondTapInTimeIsADoubleTap() {
        val g = doubleTapping()
        g.down(100f, 500f, at = 0)
        g.up(at = 80)
        // Pressed again within 300 ms of letting go, as Android counts it.
        assertEquals(emptyList(), g.down(102f, 498f, at = 300))
        assertEquals(listOf<Intent>(Intent.DoubleTap), g.up(at = 360))
        assertEquals(emptyList(), g.tapTimeout(at = 380))
    }

    @Test
    fun aSecondPressTooLateLetsTheFirstTapGoFirst() {
        val g = doubleTapping()
        g.down(100f, 500f, at = 0)
        g.up(at = 80)
        // Its timer was late: the next press brings the tap out before it starts.
        assertEquals(listOf<Intent>(Intent.Tap), g.down(100f, 500f, at = 600))
        assertEquals(emptyList(), g.up(at = 660))
        assertEquals(listOf<Intent>(Intent.Tap), g.tapTimeout(at = 960))
    }

    @Test
    fun aTapThenAHoldTalksAndForgetsTheTap() {
        val g = doubleTapping()
        g.down(100f, 500f, at = 0)
        g.up(at = 80)
        g.down(100f, 500f, at = 200)
        assertEquals(listOf<Intent>(Intent.ListenStart), g.hold(at = 520))
        assertEquals(listOf<Intent>(Intent.ListenSend), g.up(at = 1500))
        assertEquals(emptyList(), g.tapTimeout(at = 1600))
    }

    @Test
    fun aTapThenADragMovesAndForgetsTheTap() {
        val g = doubleTapping()
        g.down(100f, 500f, at = 0)
        g.up(at = 80)
        g.down(100f, 500f, at = 200)
        assertEquals(listOf(Intent.DragStart, Intent.DragTo(30f, 0f)), g.move(130f, 500f, at = 240))
        assertEquals(listOf<Intent>(Intent.DragEnd), g.up(at = 400))
        assertEquals(emptyList(), g.tapTimeout(at = 700))
    }

    @Test
    fun withoutDoubleTapsATapIsATapAtOnce() {
        val g = gesture()
        g.down(100f, 500f, at = 0)
        assertEquals(listOf<Intent>(Intent.Tap), g.up(at = 80))
        // Two quick taps are just two taps.
        assertEquals(emptyList(), g.down(100f, 500f, at = 150))
        assertEquals(listOf<Intent>(Intent.Tap), g.up(at = 200))
    }
}
