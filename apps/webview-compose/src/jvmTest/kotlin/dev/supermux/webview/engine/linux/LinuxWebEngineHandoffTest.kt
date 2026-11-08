package dev.supermux.webview.engine.linux

import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.engine.EngineEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

class LinuxWebEngineHandoffTest {
    private val demo = WebContent.Html("<p>hi</p>", WebOrigin("demo"))
    private val paint = WpeEvent.Relay("0123456789abcdef paint")

    private fun EngineRig.painted() = events.count { it == EngineEvent.FirstPaint }

    @Test fun firstPaintWaitsUntilTheContentIsDrawnAndTwoFramesPassed() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.view.frame = Triple(320, 480, 4L)
        rig.engine.onEvent(paint)
        assertEquals(0, rig.painted(), "content known, not yet drawn")
        rig.engine.onSurfaceFrame(full = false)
        assertEquals(0, rig.painted(), "pulled, not yet drawn")
        rig.engine.onSurfaceDrawn()
        rig.engine.onSurfaceFrame(full = false)
        assertEquals(0, rig.painted(), "drawn a frame ago")
        rig.engine.onSurfaceFrame(full = false)
        assertEquals(1, rig.painted())
        rig.close()
    }

    @Test fun aFrameOlderThanTheContentDoesNotCount() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.view.frame = Triple(320, 480, 4L)
        rig.engine.onSurfaceFrame(full = false)
        rig.engine.onSurfaceDrawn()
        rig.view.frame = Triple(320, 480, 5L)
        rig.engine.onEvent(paint)
        repeat(3) { rig.engine.onSurfaceFrame(full = false) }
        assertEquals(0, rig.painted(), "frame 5 pulled, never drawn")
        rig.engine.onSurfaceDrawn()
        repeat(2) { rig.engine.onSurfaceFrame(full = false) }
        assertEquals(1, rig.painted())
        rig.close()
    }

    @Test fun withNoSurfaceItWaitsForOne() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.engine.onSurfaceAttached(false)
        rig.show(demo, demo.origin.url)
        rig.view.frame = Triple(320, 480, 4L)
        rig.engine.onEvent(paint)
        assertEquals(0, rig.painted())
        rig.engine.onSurfaceAttached(true)
        rig.drawnInPlace()
        assertEquals(1, rig.painted())
        rig.close()
    }

    @Test fun aSurfaceThatLeavesMidwayStartsTheCountAgain() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.view.frame = Triple(320, 480, 4L)
        rig.engine.onEvent(paint)
        rig.engine.onSurfaceFrame(full = false)
        rig.engine.onSurfaceDrawn()
        rig.engine.onSurfaceFrame(full = false)
        rig.engine.onSurfaceAttached(false)
        rig.engine.onSurfaceAttached(true)
        repeat(2) { rig.engine.onSurfaceFrame(full = false) }
        assertEquals(0, rig.painted(), "the new surface hasn't drawn")
        rig.engine.onSurfaceDrawn()
        repeat(2) { rig.engine.onSurfaceFrame(full = false) }
        assertEquals(1, rig.painted())
        rig.close()
    }

    @Test fun aPageWithNothingToShowIsShownAfterThreeSecondsOnceDrawn() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.view.frame = Triple(320, 480, 4L)
        advanceTimeBy(2_999)
        rig.drawnInPlace()
        assertEquals(0, rig.painted(), "before 3 s")
        advanceTimeBy(2)
        rig.drawnInPlace()
        assertEquals(1, rig.painted())
        rig.close()
    }

    @Test fun theLastDocumentsHandoffNeverPaintsTheNext() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(demo, demo.origin.url)
        rig.view.frame = Triple(320, 480, 4L)
        rig.engine.onEvent(paint)
        rig.engine.load(WebContent.Html("<p>b</p>", WebOrigin("other")))
        rig.drawnInPlace()
        assertEquals(0, rig.painted())
        rig.close()
    }

    @Test fun aSnapshotIsTheLastFrameOnceTheDocumentPaintedWithASurface() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.engine.onSurfaceSize(320, 480, 1f)
        rig.show(demo, demo.origin.url)
        rig.view.frame = Triple(320, 480, 2L)
        assertNull(rig.engine.snapshot(), "before the first paint")
        rig.engine.onEvent(paint)
        rig.drawnInPlace()
        val snapshot = assertNotNull(rig.engine.snapshot())
        assertEquals(320, snapshot.width)
        assertEquals(480, snapshot.height)
        rig.engine.onSurfaceAttached(false)
        assertNull(rig.engine.snapshot(), "no surface composed")
        rig.close()
    }

    @Test fun noSnapshotBeforeTheDocumentPaintedEvenWithItsFrameDrawn() = runTest {
        // Its own frame, drawn, but not yet its content: a cover made from it would be the page's bare background.
        val rig = EngineRig(this)
        rig.warm()
        rig.engine.onSurfaceSize(320, 480, 1f)
        rig.show(demo, demo.origin.url)
        rig.view.frame = Triple(320, 480, 2L)
        rig.engine.onSurfaceFrame(full = false)
        rig.engine.onSurfaceDrawn()
        assertEquals(2L, rig.engine.frames.drawnSeq)
        assertNull(rig.engine.snapshot())
        rig.close()
    }

    @Test fun aFrameOfTheViewsEarlierSizeIsNoFirstPaint() = runTest {
        // A new view's first page can paint at the size the view was made with, before the surface's size reaches
        // WebKit (the Linux checks' snapshot on real WebKit, 2 runs in 3): scaled into the surface, it is not in place.
        val rig = EngineRig(this)
        rig.warm()
        rig.engine.onSurfaceSize(320, 480, 1f)
        rig.show(demo, demo.origin.url)
        rig.view.frame = Triple(800, 600, 4L)
        rig.engine.onEvent(paint)
        rig.engine.onSurfaceFrame(full = false)
        rig.engine.onSurfaceDrawn()
        repeat(3) { rig.engine.onSurfaceFrame(full = false) }
        assertEquals(0, rig.painted(), "an 800 x 600 frame in a 320 x 480 surface")
        rig.view.frame = Triple(320, 480, 5L)
        rig.engine.onSurfaceFrame(full = false)
        rig.engine.onSurfaceDrawn()
        repeat(2) { rig.engine.onSurfaceFrame(full = false) }
        assertEquals(1, rig.painted())
        assertEquals(320 to 480, rig.engine.snapshot()?.let { it.width to it.height })
        rig.close()
    }

    @Test fun aFrameOfAnotherSizeDuringTheCountStartsItOver() = runTest {
        // On real WebKit a late frame of the view's earlier size could be pulled between the draw that started the count
        // and the first paint, and the snapshot then copied it (4 runs in 5).
        val rig = EngineRig(this)
        rig.warm()
        rig.engine.onSurfaceSize(320, 480, 1f)
        rig.show(demo, demo.origin.url)
        rig.view.frame = Triple(320, 480, 4L)
        rig.engine.onEvent(paint)
        rig.engine.onSurfaceFrame(full = false)
        rig.engine.onSurfaceDrawn()
        rig.view.frame = Triple(800, 600, 5L)
        repeat(3) { rig.engine.onSurfaceFrame(full = false) }
        assertEquals(0, rig.painted(), "an 800 x 600 frame pulled during the count")
        rig.view.frame = Triple(320, 480, 6L)
        rig.engine.onSurfaceFrame(full = false)
        rig.engine.onSurfaceDrawn()
        repeat(2) { rig.engine.onSurfaceFrame(full = false) }
        assertEquals(1, rig.painted())
        assertEquals(320 to 480, rig.engine.snapshot()?.let { it.width to it.height })
        rig.close()
    }
}
