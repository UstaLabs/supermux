package dev.supermux.ui.session

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Card #132: the idle desktop app woke ~126 times a second. The name-derived avatar (an Agent without a rig of its
 * own) drew on every screen frame and never stood still, and spinners and pulses kept running behind other windows.
 */
@OptIn(ExperimentalTestApi::class)
class IdleLoopsTest {
    /** Draws of the window around [content] over one second of frames. */
    private fun drawsPerSecond(live: Boolean, content: @Composable () -> Unit): Int {
        var draws = 0
        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent {
                CompositionLocalProvider(LocalAvatarsLive provides live) {
                    Box(Modifier.drawWithContent { draws++; drawContent() }) { content() }
                }
            }
            mainClock.advanceTimeBy(500)
            draws = 0
            mainClock.advanceTimeBy(1000)
        }
        return draws
    }

    @Composable private fun NameAvatars(n: Int, online: Boolean = true, hovered: Boolean = false) =
        Row { repeat(n) { AgentAvatar("agent-$it", online = online, working = false, hovered = hovered) } }

    @Test fun a_name_avatar_redraws_about_24_times_a_second_not_every_frame() {
        val d = drawsPerSecond(live = true) { NameAvatars(1) }
        assertTrue(d in 20..26, "idle draws/s = $d")
    }

    @Test fun an_asleep_name_avatar_redraws_about_8_times_a_second() {
        val d = drawsPerSecond(live = true) { NameAvatars(1, online = false) }
        assertTrue(d in 6..10, "asleep draws/s = $d")
    }

    @Test fun name_avatars_share_their_frames() {
        val d = drawsPerSecond(live = true) { NameAvatars(5) }
        assertTrue(d in 20..26, "5 idle draws/s = $d")
    }

    @Test fun a_hovered_name_avatar_runs_smooth() {
        val d = drawsPerSecond(live = true) { NameAvatars(1, hovered = true) }
        assertTrue(d >= 50, "hovered draws/s = $d")
    }

    @Test fun name_avatars_stand_still_while_the_window_cannot_be_seen() {
        assertEquals(0, drawsPerSecond(live = false) { NameAvatars(3) })
    }

    @Composable private fun Spinner() {
        val angle by rememberLiveLoop(0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing)), label = "spin")
        Canvas(Modifier.size(14.dp)) { drawArc(Color.Gray, angle, 290f, useCenter = false) }
    }

    @Test fun a_live_loop_runs_while_the_window_is_seen() {
        val d = drawsPerSecond(live = true) { Spinner() }
        assertTrue(d >= 50, "spinner draws/s = $d")
    }

    @Test fun a_live_loop_stands_still_while_the_window_cannot_be_seen() {
        assertEquals(0, drawsPerSecond(live = false) { Spinner() })
    }
}
