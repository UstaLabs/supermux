package dev.supermux.ui.overlay

import dev.supermux.ui.overlay.CompanionGesture.Intent
import kotlin.test.Test
import kotlin.test.assertEquals

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

    // ── holding still while dragging: "look at this" ──

    private fun looker() = CompanionGesture(holdMs = 320, slop = 8f, cancelDistance = 70f, lockDistance = 70f, lookMs = 450, lookSlop = 16f)

    private fun CompanionGesture.dragTo(x: Float, y: Float, at: Long) {
        down(100f, 500f, at = 0)
        move(x, y, at)
    }

    @Test
    fun holdingStillWhileDraggingArmsLookAndLettingGoLooks() {
        val g = looker()
        g.dragTo(200f, 500f, at = 50)
        assertEquals(500L, g.lookDueAt)
        assertEquals(emptyList(), g.dwell(at = 499))
        assertEquals(listOf<Intent>(Intent.LookArm), g.dwell(at = 500))
        assertEquals(null, g.lookDueAt)
        assertEquals(listOf<Intent>(Intent.LookDrop), g.up(at = 700))
    }

    @Test
    fun jitterWhileHoldingStillDoesNotRestartTheWait() {
        val g = looker()
        g.dragTo(200f, 500f, at = 50)
        assertEquals(listOf<Intent>(Intent.DragTo(105f, 2f)), g.move(205f, 502f, at = 300))
        assertEquals(500L, g.lookDueAt)
    }

    @Test
    fun movingOnRestartsTheWait() {
        val g = looker()
        g.dragTo(200f, 500f, at = 50)
        g.move(260f, 500f, at = 300)
        assertEquals(750L, g.lookDueAt)
        assertEquals(emptyList(), g.dwell(at = 500))
    }

    @Test
    fun aLittleWobbleKeepsItArmed() {
        val g = looker()
        g.dragTo(200f, 500f, at = 50)
        g.dwell(at = 500)
        assertEquals(listOf<Intent>(Intent.DragTo(112f, 0f)), g.move(212f, 500f, at = 600))
        assertEquals(listOf<Intent>(Intent.LookDrop), g.up(at = 700))
    }

    @Test
    fun movingAwayDisarmsAndLettingGoThenJustMovesIt() {
        val g = looker()
        g.dragTo(200f, 500f, at = 50)
        g.dwell(at = 500)
        assertEquals(listOf(Intent.DragTo(140f, 0f), Intent.LookDisarm), g.move(240f, 500f, at = 600))
        assertEquals(1050L, g.lookDueAt)
        assertEquals(listOf<Intent>(Intent.DragEnd), g.up(at = 700))
    }

    @Test
    fun aCancelledArmedDragDisarms() {
        val g = looker()
        g.dragTo(200f, 500f, at = 50)
        g.dwell(at = 500)
        assertEquals(listOf(Intent.LookDisarm, Intent.DragEnd), g.cancel())
    }

    @Test
    fun withoutLookingADragNeverArms() {
        val g = gesture()
        g.down(100f, 500f, at = 0)
        g.move(200f, 500f, at = 50)
        assertEquals(null, g.lookDueAt)
        assertEquals(emptyList(), g.dwell(at = 5_000))
        assertEquals(listOf<Intent>(Intent.DragEnd), g.up(at = 5_100))
    }

    @Test
    fun holdingStillBeforeTheDragIsStillTalkNotLook() {
        val g = looker()
        g.down(100f, 500f, at = 0)
        assertEquals(null, g.lookDueAt)
        assertEquals(listOf<Intent>(Intent.ListenStart), g.hold(at = 320))
        assertEquals(emptyList(), g.dwell(at = 900))
    }
}
