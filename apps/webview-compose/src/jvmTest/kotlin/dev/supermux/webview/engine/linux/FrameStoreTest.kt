package dev.supermux.webview.engine.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameStoreTest {
    @Test fun theFirstPullAllocatesAtTheFramesSizeAndCopiesItWhole() {
        val view = FakeWpeView()
        val store = FrameStore(view)
        view.frame = Triple(64, 32, 5L)
        assertTrue(store.pull(full = false))
        assertEquals(listOf(false, true), view.pulls, "asked at no size, then whole at the frame's")
        assertEquals(64, store.width)
        assertEquals(32, store.height)
        assertEquals(5L, store.seq)
        assertNotNull(store.image)
        store.close()
    }

    @Test fun nothingNewChangesNothing() {
        val view = FakeWpeView()
        val store = FrameStore(view)
        view.frame = Triple(64, 32, 5L)
        store.pull(full = true)
        val image = store.image
        assertFalse(store.pull(full = false))
        assertTrue(image === store.image)
        store.close()
    }

    @Test fun aNewFrameIsANewImage() {
        val view = FakeWpeView()
        val store = FrameStore(view)
        view.frame = Triple(64, 32, 5L)
        store.pull(full = true)
        val image = store.image
        view.frame = Triple(64, 32, 6L)
        assertTrue(store.pull(full = false))
        assertEquals(6L, store.seq)
        assertTrue(image !== store.image, "Compose draws a new instance again")
        store.close()
    }

    @Test fun aNewSizeAllocatesAgain() {
        val view = FakeWpeView()
        val store = FrameStore(view)
        view.frame = Triple(64, 32, 5L)
        store.pull(full = true)
        view.frame = Triple(128, 64, 6L)
        assertTrue(store.pull(full = false))
        assertEquals(128, store.width)
        assertEquals(64, store.height)
        store.close()
    }

    @Test fun whatIsDrawnFollowsTheSurface() {
        val view = FakeWpeView()
        val store = FrameStore(view)
        assertEquals(-1L, store.drawnSeq)
        view.frame = Triple(64, 32, 5L)
        store.pull(full = true)
        store.onDrawn()
        assertEquals(5L, store.drawnSeq)
        store.close()
    }

    @Test fun aCopyIsTheLastFrameAtItsSize() {
        val view = FakeWpeView()
        val store = FrameStore(view)
        assertNull(store.copy(), "nothing before the first frame")
        view.frame = Triple(64, 32, 5L)
        store.pull(full = true)
        val copy = assertNotNull(store.copy())
        assertEquals(64, copy.width)
        assertEquals(32, copy.height)
        store.close()
    }

    @Test fun aHiddenStoreShowsNothingBeforeItsFrameAndThenCopiesItWhole() {
        val view = FakeWpeView()
        val store = FrameStore(view)
        view.frame = Triple(64, 32, 5L)
        store.pull(full = true)
        store.onDrawn()
        store.hideUntil(7L)
        assertNull(store.image, "the last document's frame goes")
        assertEquals(-1L, store.seq)
        assertEquals(-1L, store.drawnSeq)
        assertFalse(store.pull(full = true), "pulled again whole, still the old frame")
        view.frame = Triple(64, 32, 6L)
        assertFalse(store.pull(full = false), "an older frame: pulled, so WebKit goes on, but not shown")
        assertNull(store.image)
        view.frame = Triple(64, 32, 7L)
        view.pulls.clear()
        assertTrue(store.pull(full = false))
        assertEquals(7L, store.seq)
        assertEquals(listOf(true), view.pulls, "whole: the bitmap still holds the old document's pixels outside the damage")
        store.close()
    }

    @Test fun aClosedStorePullsNothing() {
        val view = FakeWpeView()
        val store = FrameStore(view)
        store.close()
        view.frame = Triple(64, 32, 5L)
        assertFalse(store.pull(full = true))
        assertTrue(view.pulls.isEmpty())
        assertNull(store.image)
    }
}
