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
import kotlinx.coroutines.test.runTest

class WindowsWebEngineHandoffTest {
    private val demo = WebContent.Html("<p>hi</p>", WebOrigin("demo"))

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
