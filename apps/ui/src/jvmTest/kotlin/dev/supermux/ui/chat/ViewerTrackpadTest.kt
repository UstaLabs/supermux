package dev.supermux.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A two-finger trackpad swipe in the viewer, read from the wheel events macOS sends for it: the
 * axis it moves on, the moment the fingers let go (the deltas start falling the way the system's
 * momentum does), how fast they were moving then, and the item it comes to rest on.
 */
class ViewerTrackpadTest {

    private fun swipe() = TrackpadSwipe(slopPx = 8f, letGoSpeed = 1f)

    /** Feeds sideways [deltas] (px), [every] ms apart from [from]; returns the travel applied. */
    private fun TrackpadSwipe.sideways(vararg deltas: Float, from: Long = 0, every: Long = 8): Float {
        var t = from
        var applied = 0f
        for (d in deltas) {
            applied += add(t, d, 0f)
            t += every
        }
        return applied
    }

    /** Fast, uneven fingers, then a steady fall: the fingers let go at the fifth delta from the end. */
    private val flick = floatArrayOf(10f, 20f, 30f, 30f, 28.5f, 27f, 25.6f)

    @Test fun nothing_moves_inside_the_slop_then_the_slop_moves_at_once() {
        val s = swipe()
        assertEquals(0f, s.add(0, 3f, 1f))
        assertNull(s.axis)
        assertEquals(9f, s.add(8, 6f, 0f), "the travel so far catches up with the fingers")
        assertEquals(SwipeAxis.Horizontal, s.axis)
    }

    @Test fun the_axis_is_kept_to_the_end_of_the_swipe() {
        val s = swipe()
        s.add(0, 10f, 0f)
        assertEquals(0f, s.add(8, 0f, 30f), "a vertical wobble moves nothing on a sideways swipe")
        assertEquals(5f, s.add(16, 5f, 40f))
        assertEquals(SwipeAxis.Horizontal, s.axis)
    }

    @Test fun a_pull_takes_the_vertical_axis() {
        val s = swipe()
        assertEquals(12f, s.add(0, 2f, 12f))
        assertEquals(SwipeAxis.Vertical, s.axis)
    }

    @Test fun a_steady_fall_after_a_fast_stretch_is_the_fingers_letting_go() {
        val s = swipe()
        s.sideways(10f, 20f, 30f, 26f, 32f)
        assertFalse(s.letGo)
        s.sideways(30f, 28.5f, 27f, from = 40)
        assertFalse(s.letGo, "three falls in a row can still be the fingers")
        s.sideways(25.6f, from = 64)
        assertTrue(s.letGo)
    }

    @Test fun a_slow_drag_that_slows_down_is_still_held() {
        val s = swipe()
        s.sideways(9f, 3f, 2.8f, 2.6f, 2.4f, 2.2f)
        assertFalse(s.letGo, "about 0.3 px/ms: fingers easing off, not momentum")
    }

    @Test fun uneven_fast_fingers_are_still_held() {
        val s = swipe()
        s.sideways(20f, 25f, 18f, 30f, 22f, 26f, 19f, 24f)
        assertFalse(s.letGo)
    }

    @Test fun the_other_axis_half_of_each_event_hides_no_let_go() {
        // AWT splits a diagonal trackpad event in two, the vertical half first: a sideways swipe's
        // stream is full of (0, dy) wobbles between its (dx, 0) steps.
        val s = swipe()
        var t = 0L
        for (d in flick) {
            s.add(t, 0f, 1.5f)
            s.add(t, d, 0f)
            t += 8
        }
        assertTrue(s.letGo)
    }

    @Test fun the_other_axis_halves_of_the_momentum_are_no_new_swipe() {
        val s = swipe()
        var t = 0L
        for (d in flick) {
            s.add(t, 0f, 1.5f)
            s.add(t, d, 0f)
            t += 8
        }
        for (d in floatArrayOf(24f, 22.9f, 21.7f)) {
            assertFalse(s.beginsAnother(0f, 1.4f))
            assertFalse(s.beginsAnother(d, 0f), "$d px is still the momentum falling")
        }
    }

    @Test fun falling_deltas_bunched_into_one_instant_are_not_momentum_when_slow() {
        // AWT stamps an event when Java gets to it, so a busy app hands several over at once.
        val s = swipe()
        s.add(0, 9f, 0f)
        for (d in floatArrayOf(3f, 2.8f, 2.6f, 2.4f, 2.2f)) s.add(40, d, 0f)
        assertFalse(s.letGo, "a slow drag stays a slow drag however its events arrive")
    }

    @Test fun the_release_speed_is_the_speed_the_fingers_let_go_at() {
        val s = swipe()
        s.sideways(*flick)
        assertTrue(s.letGo)
        val v = s.releaseVelocity()
        assertTrue(v in 3000f..4000f, "about 28 px per 8 ms, got $v")
    }

    @Test fun a_backward_swipe_lets_go_backward() {
        val s = swipe()
        s.sideways(*flick.map { -it }.toFloatArray())
        assertTrue(s.letGo)
        assertTrue(s.releaseVelocity() < -3000f)
    }

    @Test fun a_swipe_that_went_quiet_was_let_go_at_rest() {
        val s = swipe()
        s.sideways(20f, 25f, 18f, 30f)
        assertFalse(s.letGo)
        assertEquals(0f, s.releaseVelocity(), "no momentum followed: the fingers had stopped")
    }

    @Test fun momentum_after_the_let_go_is_not_a_new_swipe() {
        val s = swipe()
        s.sideways(*flick)
        assertFalse(s.beginsAnother(24f, 0f))
        assertFalse(s.beginsAnother(22.9f, 0f))
        assertFalse(s.beginsAnother(21.7f, 0f))
    }

    @Test fun fingers_coming_back_down_begin_a_new_swipe() {
        val s = swipe()
        s.sideways(*flick)
        assertFalse(s.beginsAnother(3f, 0f), "the momentum stops under the fingers, which start slow")
        assertTrue(s.beginsAnother(7f, 0f), "and then speed up")
    }

    @Test fun a_swipe_back_the_other_way_begins_a_new_swipe() {
        val s = swipe()
        s.sideways(*flick)
        assertTrue(s.beginsAnother(-6f, 0f))
    }

    @Test fun a_pull_during_sideways_momentum_begins_a_new_swipe() {
        val s = swipe()
        s.sideways(*flick)
        assertFalse(s.beginsAnother(1f, 3f))
        assertTrue(s.beginsAnother(0f, 9f))
    }

    @Test fun a_flick_goes_on_to_the_next_item_in_its_direction() {
        assertEquals(3, swipeRestingPage(start = 2, position = 2.1f, velocity = 900f, flick = 400f, minTravel = 0.02f, lastIndex = 5))
        assertEquals(1, swipeRestingPage(start = 2, position = 1.9f, velocity = -900f, flick = 400f, minTravel = 0.02f, lastIndex = 5))
    }

    @Test fun a_flick_back_toward_the_start_returns_there() {
        assertEquals(2, swipeRestingPage(start = 2, position = 2.3f, velocity = -900f, flick = 400f, minTravel = 0.02f, lastIndex = 5))
    }

    @Test fun anything_slower_rests_on_the_nearest_item() {
        assertEquals(2, swipeRestingPage(start = 2, position = 2.4f, velocity = 100f, flick = 400f, minTravel = 0.02f, lastIndex = 5))
        assertEquals(3, swipeRestingPage(start = 2, position = 2.6f, velocity = 100f, flick = 400f, minTravel = 0.02f, lastIndex = 5))
    }

    @Test fun one_swipe_moves_one_item_at_most() {
        assertEquals(3, swipeRestingPage(start = 2, position = 3f, velocity = 5000f, flick = 400f, minTravel = 0.02f, lastIndex = 5))
        assertEquals(1, swipeRestingPage(start = 2, position = 1f, velocity = -5000f, flick = 400f, minTravel = 0.02f, lastIndex = 5))
    }

    @Test fun a_flick_that_barely_moved_stays() {
        assertEquals(2, swipeRestingPage(start = 2, position = 2.01f, velocity = 900f, flick = 400f, minTravel = 0.02f, lastIndex = 5))
    }

    @Test fun the_first_and_last_items_hold() {
        assertEquals(0, swipeRestingPage(start = 0, position = 0f, velocity = -900f, flick = 400f, minTravel = 0.02f, lastIndex = 5))
        assertEquals(5, swipeRestingPage(start = 5, position = 5f, velocity = 900f, flick = 400f, minTravel = 0.02f, lastIndex = 5))
    }
}
