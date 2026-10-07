package dev.supermux.ui.display

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.supermux.net.VncRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shared VNC pointer route ([vncPointerInput]) and the frame leaf ([VncFrame]) — the two G2
 * review fixes. Both hosts' `DisplayPanel` is this surface plus its own chrome, and G4's shared one
 * is exactly this.
 */
@OptIn(ExperimentalTestApi::class)
class VncPointerInputTest {

    /** One recorded PointerEvent, as the RFB wire sees it. */
    private data class Sent(val x: Int, val y: Int, val mask: Int)

    /** The node's measured pixel size, so expectations do not depend on the test density. */
    private var measured = IntSize.Zero

    /** Remote pixel a view-space coordinate maps to on a square surface over a 100x100 remote. */
    private fun remoteOf(px: Float, dim: Int): Int = (px * 100f / dim).toInt()

    /** The surface under test: a square node over a 100x100 remote, with an overlay button exactly
     *  like the panel's Ctrl+Alt+Del / keyboard toggle. */
    private fun surface(
        sent: MutableList<Sent>,
        remote: Pair<Int, Int>? = 100 to 100,
        onPress: () -> Unit = {},
    ): @androidx.compose.runtime.Composable () -> Unit = {
        Box(
            Modifier
                .size(100.dp)
                .testTag("vnc_surface")
                .onSizeChanged { measured = it }
                .vncPointerInput(
                    key = "s1",
                    viewSize = { measured },
                    remoteSize = { remote },
                    onPress = onPress,
                ) { x, y, mask -> sent += Sent(x, y, mask) },
            contentAlignment = Alignment.BottomStart,
        ) {
            TextButton(onClick = { }, modifier = Modifier.testTag("overlay_button")) { Text("C-A-D") }
        }
    }

    @Test
    fun a_tap_on_the_surface_presses_and_releases_the_remote_left_button() = runComposeUiTest {
        val sent = mutableListOf<Sent>()
        setContent(surface(sent))

        onNodeWithTag("vnc_surface").performTouchInput { down(center); up() }

        assertEquals(listOf(1, 0), sent.map { it.mask })
        assertTrue(sent.all { it.x in 45..55 && it.y in 45..55 }, "mapped to the middle: $sent")
    }

    @Test
    fun a_tap_on_an_overlay_button_never_reaches_the_remote() = runComposeUiTest {
        // The button is INSIDE the surface node (Compose delivers what a child consumed to its
        // ancestor too), so without the isConsumed guard pressing Ctrl+Alt+Del also clicked the
        // remote desktop under the button.
        val sent = mutableListOf<Sent>()
        setContent(surface(sent))

        onNodeWithTag("overlay_button").performClick()

        assertEquals(emptyList(), sent)
    }

    @Test
    fun a_hover_move_follows_the_cursor_without_dragging() = runComposeUiTest {
        val sent = mutableListOf<Sent>()
        setContent(surface(sent))

        onNodeWithTag("vnc_surface").performMouseInput {
            moveTo(Offset(1f, 1f))   // entering the node is an Enter, not a Move — ignored, as before
            moveTo(Offset(10f, 20f))
        }

        // Motion with no button held is mask 0 — the remote cursor moves, nothing drags.
        assertEquals(
            listOf(Sent(remoteOf(10f, measured.width), remoteOf(20f, measured.height), 0)),
            sent,
        )
    }

    @Test
    fun a_mouse_drag_holds_the_button_down_across_the_move() = runComposeUiTest {
        val sent = mutableListOf<Sent>()
        setContent(surface(sent))

        onNodeWithTag("vnc_surface").performMouseInput {
            moveTo(Offset(10f, 10f))
            press()
            moveTo(Offset(30f, 30f))
            release()
        }

        // press(1) · move-while-held(1) · release(0) — the entering move is an Enter, not a Move.
        assertEquals(listOf(1, 1, 0), sent.map { it.mask })
        assertEquals(Sent(remoteOf(30f, measured.width), remoteOf(30f, measured.height), 1), sent[1])
    }

    @Test
    fun a_cancelled_gesture_releases_the_remote_button() = runComposeUiTest {
        // Leaving the composition mid-press tears the gesture loop down; the old TextureView got an
        // ACTION_CANCEL and sent mask 0. Without the finally the remote holds the button forever.
        val sent = mutableListOf<Sent>()
        var shown by mutableStateOf(true)
        val body = surface(sent)
        setContent { if (shown) body() else Box(Modifier.fillMaxSize()) }

        onNodeWithTag("vnc_surface").performTouchInput { down(center) }
        assertEquals(listOf(1), sent.map { it.mask })

        shown = false
        waitForIdle()

        assertEquals(listOf(1, 0), sent.map { it.mask }, "the held button is released at its own point")
        assertEquals(sent[0].x to sent[0].y, sent[1].x to sent[1].y)
    }

    @Test
    fun nothing_is_sent_before_the_server_reports_a_framebuffer_size() = runComposeUiTest {
        val sent = mutableListOf<Sent>()
        var pressed = 0
        setContent(surface(sent, remote = null, onPress = { pressed++ }))

        onNodeWithTag("vnc_surface").performTouchInput { down(center); up() }

        assertEquals(emptyList(), sent)
        // onPress still fires — desktop takes keyboard focus on a click into an unsized surface too.
        assertEquals(1, pressed)
    }

    @Test
    fun a_frame_recomposes_the_image_leaf_and_not_the_panel_around_it() = runComposeUiTest {
        val fb = VncFramebuffer()
        var panelCompositions = 0
        setContent {
            panelCompositions++
            Box(Modifier.size(100.dp)) {
                VncFrame(fb, Modifier.fillMaxSize().testTag("vnc_frame"))
                Text("chrome")
            }
        }
        waitForIdle()
        val before = panelCompositions

        fb.applyUpdate(listOf(VncRect(0, 0, 2, 2, ByteArray(2 * 2 * 4))), 2 to 2)
        waitForIdle()

        assertEquals(before, panelCompositions, "a server frame must not recompose the panel body")
    }
}
