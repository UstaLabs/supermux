package dev.supermux.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SpaceHoldGesture] driven by a virtual clock, the way the composer drives it: every key event goes
 * through [SpaceHoldGesture.onKey], an [SpaceHoldStep.Arm] starts a [SPACE_HOLD_MS] timer, a
 * [SpaceHoldStep.Disarm] cancels it, and the timer firing calls [SpaceHoldGesture.onHoldElapsed].
 *
 * The harness also plays the text field: a Space press (or its typed character, on desktop) that
 * the gesture lets through types a space, and a [SpaceHoldStep.Start] takes it back.
 */
class SpaceHoldGestureTest {

    private class Harness(
        /** Desktop delivers the character as a separate KEY_TYPED event after the press. */
        private val separateTypedEvent: Boolean = false,
    ) {
        val gesture = SpaceHoldGesture()
        var now = 0L
        var text = ""
        val actions = mutableListOf<String>()
        private var deadline: Long? = null
        private var armedPress = 0
        private var before = ""

        private fun step(s: SpaceHoldStep): SpaceHoldStep {
            when (s) {
                SpaceHoldStep.Arm -> { deadline = now + SPACE_HOLD_MS; armedPress = gesture.press; before = text }
                SpaceHoldStep.Disarm -> deadline = null
                SpaceHoldStep.Start -> {
                    if (isTypedSpaceOf(before, text)) text = before
                    actions += "start"
                }
                SpaceHoldStep.Stop -> actions += "stop"
                SpaceHoldStep.Pass, SpaceHoldStep.Swallow -> Unit
            }
            return s
        }

        fun spaceDown(eligible: Boolean = true) {
            val s = step(gesture.onKey(SpaceHoldKey.SpaceDown, eligible))
            val passed = s != SpaceHoldStep.Swallow
            if (separateTypedEvent) {
                if (step(gesture.onKey(SpaceHoldKey.SpaceTyped, eligible)) != SpaceHoldStep.Swallow) text += " "
            } else if (passed) {
                text += " "
            }
        }

        fun spaceUp() { step(gesture.onKey(SpaceHoldKey.SpaceUp, eligible = true)) }

        fun otherKey(ch: Char) {
            val s = step(gesture.onKey(SpaceHoldKey.OtherDown, eligible = true))
            if (s != SpaceHoldStep.Swallow) text += ch
        }

        fun advance(ms: Long) {
            now += ms
            val d = deadline
            if (d != null && now >= d) {
                deadline = null
                step(gesture.onHoldElapsed(armedPress))
            }
        }
    }

    @Test fun a_tap_types_exactly_one_space_and_never_dictates() {
        val h = Harness()
        h.text = "hello"
        h.spaceDown()
        h.advance(80)
        h.spaceUp()
        h.advance(1_000)
        assertEquals("hello ", h.text)
        assertEquals(emptyList(), h.actions)
        assertEquals(SpaceHoldGesture.Phase.Idle, h.gesture.phase)
    }

    @Test fun a_hold_starts_dictation_without_the_space_and_stops_on_release() {
        val h = Harness()
        h.text = "hello"
        h.spaceDown()
        h.advance(SPACE_HOLD_MS - 1)
        assertEquals(emptyList(), h.actions)
        h.advance(1)
        assertEquals(listOf("start"), h.actions)
        assertEquals("hello", h.text)
        h.advance(2_000)
        h.spaceUp()
        assertEquals(listOf("start", "stop"), h.actions)
        assertEquals(SpaceHoldGesture.Phase.Idle, h.gesture.phase)
    }

    @Test fun another_key_while_space_is_pending_keeps_the_space_and_types_the_key() {
        val h = Harness()
        h.text = "hello"
        h.spaceDown()
        h.advance(40)
        h.otherKey('w') // rollover: w goes down before Space comes up
        h.advance(1_000)
        h.spaceUp()
        assertEquals("hello w", h.text)
        assertEquals(emptyList(), h.actions)
    }

    @Test fun auto_repeat_while_held_types_nothing() {
        val h = Harness()
        h.spaceDown()
        repeat(5) { h.advance(60); h.spaceDown() } // the OS repeat starts before the hold fires
        h.advance(SPACE_HOLD_MS)
        repeat(10) { h.advance(30); h.spaceDown() }
        h.spaceUp()
        assertEquals("", h.text)
        assertEquals(listOf("start", "stop"), h.actions)
    }

    @Test fun desktop_typed_events_type_one_space_and_their_repeats_are_swallowed() {
        val h = Harness(separateTypedEvent = true)
        h.text = "a"
        h.spaceDown()
        h.spaceDown()
        h.spaceDown()
        assertEquals("a ", h.text)
        h.advance(SPACE_HOLD_MS)
        assertEquals("a", h.text)
        h.spaceDown()
        h.spaceUp()
        assertEquals("a", h.text)
        assertEquals(listOf("start", "stop"), h.actions)
    }

    @Test fun desktop_tap_types_one_space() {
        val h = Harness(separateTypedEvent = true)
        h.spaceDown()
        h.spaceUp()
        h.advance(1_000)
        assertEquals(" ", h.text)
        assertEquals(emptyList(), h.actions)
    }

    @Test fun an_ineligible_space_is_plain_typing_even_when_held() {
        val h = Harness()
        h.spaceDown(eligible = false) // e.g. Shift+Space, or no mic
        h.advance(1_000)
        h.spaceDown(eligible = false) // its repeats are typed, as they always were
        h.spaceUp()
        assertEquals("  ", h.text)
        assertEquals(emptyList(), h.actions)
    }

    @Test fun a_stale_timer_from_an_earlier_press_cannot_start_dictation() {
        val g = SpaceHoldGesture()
        assertEquals(SpaceHoldStep.Arm, g.onKey(SpaceHoldKey.SpaceDown, true))
        val first = g.press
        assertEquals(SpaceHoldStep.Disarm, g.onKey(SpaceHoldKey.SpaceUp, true))
        assertEquals(SpaceHoldStep.Arm, g.onKey(SpaceHoldKey.SpaceDown, true))
        assertEquals(SpaceHoldStep.Pass, g.onHoldElapsed(first))
        assertEquals(SpaceHoldStep.Start, g.onHoldElapsed(g.press))
    }

    @Test fun a_hold_that_can_no_longer_start_goes_back_to_typing() {
        val g = SpaceHoldGesture()
        g.onKey(SpaceHoldKey.SpaceDown, true)
        assertEquals(SpaceHoldStep.Pass, g.onHoldElapsed(g.press, stillEligible = false))
        assertEquals(SpaceHoldStep.Pass, g.onKey(SpaceHoldKey.SpaceUp, true))
    }

    @Test fun other_keys_while_held_are_typing_and_do_not_end_the_hold() {
        val g = SpaceHoldGesture()
        g.onKey(SpaceHoldKey.SpaceDown, true)
        g.onHoldElapsed(g.press)
        assertEquals(SpaceHoldStep.Pass, g.onKey(SpaceHoldKey.OtherDown, true))
        assertEquals(SpaceHoldStep.Stop, g.onKey(SpaceHoldKey.SpaceUp, true))
    }

    @Test fun losing_focus_keeps_a_tap_and_stops_a_hold() {
        val g = SpaceHoldGesture()
        assertEquals(SpaceHoldStep.Pass, g.cancel())
        g.onKey(SpaceHoldKey.SpaceDown, true)
        assertEquals(SpaceHoldStep.Disarm, g.cancel())
        g.onKey(SpaceHoldKey.SpaceDown, true)
        g.onHoldElapsed(g.press)
        assertEquals(SpaceHoldStep.Stop, g.cancel())
        // The key-up that arrives later (if it does) is nothing.
        assertEquals(SpaceHoldStep.Pass, g.onKey(SpaceHoldKey.SpaceUp, true))
    }

    @Test fun eligibility_needs_a_bare_space_from_a_real_keyboard_with_an_idle_mic() {
        fun ok(
            physical: Boolean = true,
            modifier: Boolean = false,
            shown: Boolean = true,
            busy: Boolean = false,
            slash: Boolean = false,
        ) = spaceHoldEligible(physical, modifier, shown, busy, slash)
        assertTrue(ok())
        assertFalse(ok(physical = false))
        assertFalse(ok(modifier = true))
        assertFalse(ok(shown = false))
        assertFalse(ok(busy = true))
        assertFalse(ok(slash = true))
    }

    @Test fun typed_space_detection() {
        assertTrue(isTypedSpaceOf("", " "))
        assertTrue(isTypedSpaceOf("hello", "hello "))
        assertTrue(isTypedSpaceOf("helloworld", "hello world"))
        assertTrue(isTypedSpaceOf("a b", "a  b"))
        assertTrue(isTypedSpaceOf("hello big world", "hello  world")) // typed over "big"
        assertFalse(isTypedSpaceOf("hello", "hello"))
        assertFalse(isTypedSpaceOf("hello", "hellox"))
        assertFalse(isTypedSpaceOf("hello", "hello  "))
        assertFalse(isTypedSpaceOf("hello", "jello "))
    }
}
