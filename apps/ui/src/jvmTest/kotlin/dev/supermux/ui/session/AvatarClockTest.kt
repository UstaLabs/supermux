package dev.supermux.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Card #108: a living avatar asked for every screen frame, so a window holding one redrew at the
 * display's refresh rate (~45% CPU on his Mac). Its clock is capped like the overlay head's.
 */
@OptIn(ExperimentalTestApi::class)
class AvatarClockTest {
    private val rig = assertNotNull(
        parseAvatarRig(
            """{"format":"supermux-avatar","version":2,"size":100,"parts":[
                {"id":"face","shape":"ellipse","cx":50,"cy":50,"rx":30,"ry":30,"fill":"#FFE0CC","behaviors":[{"type":"breathe"}]}],
                "states":{"idle":{"tracks":[]},"asleep":{"tracks":[]}}}""",
        ),
    )

    @Test fun rates_follow_the_overlay_heads_rule() {
        assertEquals(24, avatarFps(state = "idle", hovered = false, live = true))
        assertEquals(24, avatarFps(state = "working", hovered = false, live = true))
        assertEquals(8, avatarFps(state = "asleep", hovered = false, live = true))
        assertEquals(60, avatarFps(state = "idle", hovered = true, live = true))
        assertEquals(60, avatarFps(state = "asleep", hovered = true, live = true))
        assertEquals(0, avatarFps(state = "idle", hovered = true, live = false))
    }

    /** Draws of the window around [n] avatars over one second of frames. */
    private fun drawsPerSecond(n: Int, state: String = "idle", hovered: Boolean = false, live: Boolean = true): Int {
        var draws = 0
        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent {
                CompositionLocalProvider(LocalAvatarsLive provides live) {
                    Box(Modifier.drawWithContent { draws++; drawContent() }) {
                        Row { repeat(n) { AvatarRigView(rig, state = state, hovered = hovered, seed = "a$it") } }
                    }
                }
            }
            mainClock.advanceTimeBy(500)
            draws = 0
            mainClock.advanceTimeBy(1000)
        }
        return draws
    }

    @Test fun an_idle_avatar_redraws_the_window_about_24_times_a_second() {
        val d = drawsPerSecond(1)
        assertTrue(d in 20..26, "idle draws/s = $d")
    }

    @Test fun an_asleep_avatar_redraws_about_8_times_a_second() {
        val d = drawsPerSecond(1, state = "asleep")
        assertTrue(d in 6..10, "asleep draws/s = $d")
    }

    @Test fun many_avatars_share_their_frames() {
        val d = drawsPerSecond(6)
        assertTrue(d in 20..26, "6 idle avatars draws/s = $d")
    }

    @Test fun a_hovered_avatar_runs_smooth() {
        val d = drawsPerSecond(1, hovered = true)
        assertTrue(d >= 50, "hovered draws/s = $d")
    }

    @Test fun avatars_stand_still_while_the_window_cannot_be_seen() {
        assertEquals(0, drawsPerSecond(3, live = false))
    }
}
