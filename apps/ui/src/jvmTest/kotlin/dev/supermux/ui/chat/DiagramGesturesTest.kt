package dev.supermux.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performMultiModalInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A diagram zooms and pans where it sits in the chat, so the gestures must take what is meant for
 * the diagram (a pinch, Ctrl + wheel, a drag of a zoomed diagram) and leave the rest to the chat's
 * scroll (a wheel or a one-finger drag over a diagram at fit).
 */
@OptIn(ExperimentalTestApi::class)
class DiagramGesturesTest {

    // ── Pure ──────────────────────────────────────────────────────────────────────────

    @Test fun zooming_about_a_point_keeps_that_point_still() {
        val t = LightboxTransform()
        t.viewport = Size(400f, 200f)
        val focus = Offset(300f, 50f)
        val before = contentUnder(t, focus)
        t.zoomBy(2f, focus)
        assertEquals(2f, t.scale)
        val after = contentUnder(t, focus)
        assertTrue(abs(before.x - after.x) < 0.01f && abs(before.y - after.y) < 0.01f, "$before → $after")
    }

    @Test fun double_tap_zooms_in_on_the_tapped_point_and_back_to_fit() {
        val t = LightboxTransform()
        t.viewport = Size(400f, 200f)
        t.toggleZoom(Offset(350f, 100f))
        assertEquals(LightboxTransform.DOUBLE_TAP, t.scale)
        assertTrue(t.offset.x < 0f, "zooming on the right edge must shift the content left; got ${t.offset}")
        t.toggleZoom(Offset(0f, 0f))
        assertEquals(LightboxTransform.MIN, t.scale)
        assertEquals(Offset.Zero, t.offset)
    }

    @Test fun trackpad_pinch_deltas_compose_to_the_same_zoom_as_one_notch() {
        val oneNotch = wheelZoomFactor(-1f)
        var many = 1f
        repeat(10) { many *= wheelZoomFactor(-0.1f) }
        assertTrue(abs(oneNotch - many) < 0.001f, "$oneNotch vs $many")
        assertTrue(oneNotch > 1f && wheelZoomFactor(1f) < 1f)
    }

    // ── Wired up ──────────────────────────────────────────────────────────────────────

    @Test fun a_plain_wheel_at_fit_is_left_for_the_chat() = runComposeUiTest {
        val t = LightboxTransform()
        setContent { Target(t) }
        onNodeWithTag("target").performMouseInput { enter(center); scroll(-3f) }
        waitForIdle()
        assertEquals(LightboxTransform.MIN, t.scale)
    }

    @Test fun ctrl_wheel_zooms_about_the_cursor() = runComposeUiTest {
        val t = LightboxTransform()
        setContent { Target(t) }
        onNodeWithTag("target").performMultiModalInput {
            key { keyDown(Key.CtrlLeft) }
            mouse { moveTo(Offset(width * 0.9f, height / 2f)); scroll(-1f) }
            key { keyUp(Key.CtrlLeft) }
        }
        waitForIdle()
        assertTrue(t.scale > LightboxTransform.MIN, "Ctrl + wheel up must zoom in; got ${t.scale}")
        assertTrue(t.offset.x < 0f, "zooming near the right edge must keep it under the cursor; got ${t.offset}")
    }

    @Test fun a_plain_wheel_pans_a_zoomed_diagram() = runComposeUiTest {
        val t = LightboxTransform()
        setContent { Target(t) }
        runOnIdle { t.scaleTo(3f) }
        onNodeWithTag("target").performMouseInput { enter(center); scroll(2f) }
        waitForIdle()
        assertEquals(3f, t.scale, "a plain wheel must not zoom")
        assertTrue(t.offset.y < 0f, "scrolling down must move the diagram up; got ${t.offset}")
    }

    @Test fun pinch_zooms_in_place() = runComposeUiTest {
        val t = LightboxTransform()
        setContent { Target(t) }
        onNodeWithTag("target").performTouchInput {
            pinch(center - Offset(10f, 0f), center - Offset(150f, 0f), center + Offset(10f, 0f), center + Offset(150f, 0f))
        }
        waitForIdle()
        assertTrue(t.scale > 2f, "spreading two fingers must zoom in; got ${t.scale}")
    }

    @Test fun a_one_finger_drag_only_pans_once_zoomed() = runComposeUiTest {
        val t = LightboxTransform()
        setContent { Target(t) }
        onNodeWithTag("target").performTouchInput { swipeLeft() }
        waitForIdle()
        assertEquals(Offset.Zero, t.offset, "at fit a drag belongs to the chat")
        runOnIdle { t.scaleTo(3f) }
        onNodeWithTag("target").performTouchInput { swipeLeft() }
        waitForIdle()
        assertTrue(t.offset.x < 0f, "a zoomed diagram follows the finger; got ${t.offset}")
    }

    @androidx.compose.runtime.Composable
    private fun Target(t: LightboxTransform) {
        Box(Modifier.size(400.dp, 200.dp).diagramGestures(t).testTag("target"))
    }

    /** The content-space point (unscaled, relative to the centre) drawn at [focus]. */
    private fun contentUnder(t: LightboxTransform, focus: Offset): Offset =
        (focus - Offset(t.viewport.width / 2f, t.viewport.height / 2f) - t.offset) / t.scale
}
