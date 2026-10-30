package dev.supermux.webview.engine.windows

import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

class WindowsWebEngineHandoffTest {
    private val demo = WebContent.Html("<p>hi</p>", WebOrigin("demo"))
    private val RED = 0xFFCC3333.toInt()
    private val BLUE = 0xFF3366CC.toInt()

    @AfterTest fun quiet() = WinPages.resetForTests()

    @Test fun noFirstPaintWithoutASurfaceAndNoSnapshotBeforeIt() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.engine.onSurfaceAttached(false)
        rig.show(demo, demo.origin.url)
        rig.paints()
        assertTrue(EngineEvent.FirstPaint !in rig.events)
        assertNull(rig.engine.snapshot(), "L25: a page that hasn't painted would give the previous page or white")
        rig.engine.onSurfaceAttached(true)
        rig.drawnInPlace()
        assertTrue(EngineEvent.FirstPaint in rig.events)
        rig.close()
    }

    @Test fun aFrameOfAnotherSizeIsNotDrawnInPlace() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.engine.onSurfacePlacedForTests(320, 480)
        rig.show(demo, demo.origin.url)
        rig.paints()
        rig.page.frame = Triple(800, 600, 1L)       // WebView2 still at its earlier size
        rig.engine.onSurfaceFrame(full = false)
        rig.engine.onSurfaceDrawn()
        repeat(3) { rig.engine.onSurfaceFrame(full = false) }
        assertTrue(EngineEvent.FirstPaint !in rig.events)
        rig.page.frame = Triple(320, 480, 2L)
        rig.drawnInPlace()
        assertTrue(EngineEvent.FirstPaint in rig.events)
        rig.close()
    }

    /** [rig]'s page shown, its surface placed, and WebView2's blank (composed at 1.000 s) drawn before its paint. */
    private fun blankDrawn(rig: WinRig) {
        rig.show(demo, demo.origin.url)
        rig.engine.onSurfaceAttached(true)
        rig.engine.onSurfacePlacedForTests(320, 480)
        rig.page.frame = Triple(320, 480, 1L)
        rig.page.composed = 1_000_000_000L
        rig.engine.onSurfaceFrame(full = false)
        rig.engine.onSurfaceDrawn()
    }

    @Test fun aFirstPaintWaitsForAFrameComposedSinceTheContentWasPresented() = runTest {
        // WebView2's capture can be a composition or two behind the page (the checks on WARP: the screen white at the
        // first paint): a frame composed before the content was presented doesn't give the first paint.
        val rig = WinRig(this)
        rig.warm()
        blankDrawn(rig)
        rig.paints(agoMillis = 30.0, arrivedNs = 1_050_000_000L)   // presented at 1.020 s
        repeat(3) {
            rig.engine.onSurfaceFrame(full = false)
            rig.engine.onSurfaceDrawn()
        }
        assertTrue(EngineEvent.FirstPaint !in rig.events, "a frame composed before the content was presented")
        rig.page.frame = Triple(320, 480, 2L)
        rig.page.composed = 1_031_250_000L
        rig.drawnInPlace()
        assertTrue(EngineEvent.FirstPaint in rig.events)
        rig.close()
    }

    @Test fun withoutAFrameComposedSinceTheNewestIsShownAfterAWhile() = runTest {
        // A frame of the content the capture had before the signal came, as the newest: never waited for forever.
        val rig = WinRig(this)
        rig.warm()
        blankDrawn(rig)
        rig.paints(agoMillis = 30.0, arrivedNs = 1_050_000_000L)
        rig.drawnInPlace()
        assertTrue(EngineEvent.FirstPaint !in rig.events)
        advanceTimeBy(600)
        rig.drawnInPlace()
        assertTrue(EngineEvent.FirstPaint in rig.events)
        rig.close()
    }

    @Test fun aPaintThatSaysNothingOfWhenTakesTheNewestFrame() = runTest {
        // A page that paints nothing but the default background: its frames are what there is to show.
        val rig = WinRig(this)
        rig.warm()
        blankDrawn(rig)
        rig.paints()
        rig.drawnInPlace()
        assertTrue(EngineEvent.FirstPaint in rig.events)
        rig.close()
    }

    @Test fun aFrameTheRendererMayStillDrawIsNeverWrittenOver() = runTest {
        // skiko's Direct3D renderer draws Compose's recording of a frame on a thread of its own, later: the next frame
        // goes into other memory (the VM's crash, an access violation in skiko reading a released engine's frame).
        val rig = WinRig(this)
        rig.warm()
        rig.page.fill = RED
        rig.show(demo, demo.origin.url)
        rig.paints()
        rig.drawnInPlace()
        val recording = PictureRecorder().let { recorder ->
            recorder.beginRecording(Rect.makeWH(320f, 480f)).drawImage(assertNotNull(rig.engine.frames.image), 0f, 0f)
            recorder.finishRecordingAsPicture().also { recorder.close() }
        }
        rig.page.fill = BLUE
        rig.page.frame = Triple(320, 480, 2L)
        rig.engine.onSurfaceFrame(full = false)
        val surface = Surface.makeRasterN32Premul(320, 480)
        surface.canvas.drawPicture(recording)
        val drawn = Bitmap().apply { allocN32Pixels(320, 480) }
        assertTrue(surface.readPixels(drawn, 0, 0))
        assertEquals(RED, drawn.getColor(160, 240), "what the recording draws")
        drawn.close()
        surface.close()
        recording.close()
        rig.close()
    }

    @Test fun theSnapshotIsTheLastFrameAtItsPixels() = runTest {
        val rig = WinRig(this)
        rig.warm()
        rig.page.fill = 0xFF3366CC.toInt()
        rig.show(demo, demo.origin.url)
        rig.paints()
        rig.drawnInPlace()
        val image = assertNotNull(rig.engine.snapshot())
        assertEquals(320 to 480, image.width to image.height)
        rig.close()
    }

    @Test fun aRecycledEngineShowsNothingOfTheLastLease() = runTest {
        // L16: until the next document's own frames, the store shows nothing: never the last page, nor the floor's white.
        val rig = WinRig(this, WebProfile.Default)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.paints()
        rig.drawnInPlace()
        assertNotNull(rig.engine.frames.image)
        assertEquals(true, rig.resets()())
        assertNull(rig.engine.frames.image)
        rig.page.frame = Triple(320, 480, 5L)       // the floor's frame
        rig.engine.onSurfaceFrame(full = false)
        assertNull(rig.engine.frames.image, "the floor never shows")
        rig.close()
    }
}
