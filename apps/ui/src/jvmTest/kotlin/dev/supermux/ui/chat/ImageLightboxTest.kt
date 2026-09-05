package dev.supermux.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import dev.supermux.ui.platform.FakePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lightbox opens on desktop as well as on a phone (cluster D2), so every way to move the
 * picture has to work with a mouse: pinch alone left a desktop user with an inert full-screen image
 * where the OS viewer used to be. Zoom/pan state is hoisted into [LightboxTransform] so a test can
 * assert what a wheel, a drag or a button actually did.
 */
@OptIn(ExperimentalTestApi::class)
class ImageLightboxTest {

    private val painter = ColorPainter(Color.Red)

    // ── Pure clamping ─────────────────────────────────────────────────────────────────

    @Test fun zoom_is_clamped_to_the_fit_and_max_bounds() {
        val t = LightboxTransform()
        assertEquals(LightboxTransform.MIN, t.scale)
        repeat(20) { t.zoomBy(1f / LightboxTransform.STEP) }
        assertEquals(LightboxTransform.MIN, t.scale, "cannot zoom out past fit")
        repeat(40) { t.zoomBy(LightboxTransform.STEP) }
        assertEquals(LightboxTransform.MAX, t.scale, "cannot zoom in past the cap")
    }

    @Test fun panning_is_clamped_to_the_overflow_the_scale_creates() {
        val t = LightboxTransform()
        t.viewport = Size(400f, 200f)
        t.panBy(Offset(500f, 500f))
        assertEquals(Offset.Zero, t.offset, "at fit there is nothing to pan")
        t.scaleTo(2f)
        t.panBy(Offset(500f, 500f))
        // At 2× the overflow is half the viewport in each direction.
        assertEquals(Offset(200f, 100f), t.offset)
        t.panBy(Offset(-5000f, -5000f))
        assertEquals(Offset(-200f, -100f), t.offset)
    }

    @Test fun zooming_back_out_pulls_the_offset_back_into_bounds() {
        val t = LightboxTransform()
        t.viewport = Size(400f, 200f)
        t.scaleTo(3f)
        t.panBy(Offset(1000f, 1000f))
        assertEquals(Offset(400f, 200f), t.offset)
        t.scaleTo(1f)
        assertEquals(Offset.Zero, t.offset, "back at fit the image must recentre, not stay off-screen")
    }

    @Test fun double_tap_toggles_between_fit_and_2x() {
        val t = LightboxTransform()
        t.toggleZoom()
        assertEquals(LightboxTransform.DOUBLE_TAP, t.scale)
        t.toggleZoom()
        assertEquals(LightboxTransform.MIN, t.scale)
    }

    // ── Wired up ──────────────────────────────────────────────────────────────────────

    @Test fun the_zoom_buttons_change_the_scale() = runComposeUiTest {
        val t = LightboxTransform()
        setPlatformContent { lightbox(t) }
        onNodeWithTag("image_lightbox_zoom_in").assertIsDisplayed()
        onNodeWithTag("image_lightbox_zoom_in").performClick()
        assertEquals(LightboxTransform.STEP, t.scale)
        onNodeWithTag("image_lightbox_zoom_out").performClick()
        assertEquals(LightboxTransform.MIN, t.scale)
    }

    @Test fun the_scroll_wheel_zooms() = runComposeUiTest {
        val t = LightboxTransform()
        setPlatformContent { lightbox(t) }
        onNodeWithTag("image_lightbox").performMouseInput {
            enter(center)
            scroll(-1f)
        }
        waitForIdle()
        assertTrue(t.scale > LightboxTransform.MIN, "a wheel notch up must zoom in; got ${t.scale}")
        val zoomedIn = t.scale
        onNodeWithTag("image_lightbox").performMouseInput { scroll(1f) }
        waitForIdle()
        assertTrue(t.scale < zoomedIn, "scrolling back down must zoom out; got ${t.scale}")
    }

    @Test fun dragging_pans_a_zoomed_image() = runComposeUiTest {
        val t = LightboxTransform()
        setPlatformContent { lightbox(t) }
        onNodeWithTag("image_lightbox_zoom_in").performClick()
        onNodeWithTag("image_lightbox_zoom_in").performClick()
        onNodeWithTag("image_lightbox_zoom_in").performClick()
        waitForIdle()
        assertTrue(t.scale > 1.5f)
        onNodeWithTag("image_lightbox").performTouchInput { swipeLeft() }
        waitForIdle()
        assertTrue(t.offset.x < 0f, "a leftward drag must move the picture left; got ${t.offset}")
    }

    // ── The download button uses the host's own save/open rule ────────────────────────

    @Test fun download_under_a_pointer_saves_then_opens_what_was_saved() = runComposeUiTest {
        val platform = FakePlatform()
        setPlatformContent(platform, pointer = true) { lightbox(LightboxTransform()) }
        onNodeWithTag("image_lightbox_download").performClick()
        waitUntil(timeoutMillis = 5_000L) { platform.files.openedSaved.isNotEmpty() }
        assertEquals(listOf("shot.png|image/png|3"), platform.files.saved)
        assertEquals("shot.png", platform.files.openedSaved.single().name)
        assertTrue(platform.files.opened.isEmpty())
    }

    @Test fun download_under_touch_opens_externally() = runComposeUiTest {
        val platform = FakePlatform()
        setPlatformContent(platform, pointer = false) { lightbox(LightboxTransform()) }
        onNodeWithTag("image_lightbox_download").performClick()
        waitUntil(timeoutMillis = 5_000L) { platform.files.opened.isNotEmpty() }
        assertEquals(listOf("shot.png|image/png|3"), platform.files.opened)
        assertTrue(platform.files.saved.isEmpty())
    }

    @androidx.compose.runtime.Composable
    private fun lightbox(t: LightboxTransform) {
        Box(Modifier.size(400.dp)) {
            ImageLightbox(
                painter = painter,
                name = "shot.png",
                mime = "image/png",
                bytes = byteArrayOf(1, 2, 3),
                onDismiss = {},
                transform = t,
            )
        }
    }
}
