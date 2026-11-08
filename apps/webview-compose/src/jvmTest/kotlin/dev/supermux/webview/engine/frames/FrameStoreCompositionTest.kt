package dev.supermux.webview.engine.frames

import dev.supermux.webview.engine.linux.FakeWpeView
import dev.supermux.webview.engine.windows.FakeWinPage
import kotlin.test.Test
import kotlin.test.assertEquals

/** When the frame the store shows, and the one a surface last drew, were composed (WGC's SystemRelativeTime on Windows). */
class FrameStoreCompositionTest {
    @Test fun theShownFramesCompositionIsKeptAndSoIsTheDrawnOnes() {
        val page = FakeWinPage()
        val store = FrameStore(page, guardDraws = true)
        assertEquals(-1L, store.drawnComposedNs)
        page.frame = Triple(64, 32, 1L)
        page.composed = 1_000L
        store.pull(full = false)
        assertEquals(1_000L, store.composedNs)
        assertEquals(-1L, store.drawnComposedNs, "nothing drawn yet")
        store.onDrawn()
        assertEquals(1_000L, store.drawnComposedNs)
        page.frame = Triple(64, 32, 2L)
        page.composed = 2_000L
        store.pull(full = false)
        assertEquals(2_000L, store.composedNs)
        assertEquals(1_000L, store.drawnComposedNs, "what was drawn is still the first")
        store.forgetDrawn()
        assertEquals(-1L, store.drawnComposedNs)
        store.onDrawn()
        store.hideUntil(5L)
        assertEquals(-1L, store.composedNs)
        assertEquals(-1L, store.drawnComposedNs)
        store.close()
    }

    @Test fun aSourceThatDoesntSayHasNone() {
        val view = FakeWpeView()
        val store = FrameStore(view)
        view.frame = Triple(64, 32, 1L)
        store.pull(full = false)
        store.onDrawn()
        assertEquals(-1L, store.composedNs)
        assertEquals(-1L, store.drawnComposedNs)
        store.close()
    }
}
