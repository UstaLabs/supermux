package dev.supermux.webview.engine.frames

import dev.supermux.webview.engine.windows.FakeWinPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a frame shows, from nine points of it: nothing (every point clear), one colour, or more. WebView2's blank frames
 * (its empty visual, clear; its default background, white) can be composed after the page's paint was presented, a
 * composition or two before its content (the checks on WARP): the engine's first paint never takes one for the page.
 */
class FrameStoreLookTest {
    private val white = 0xFFFFFFFF.toInt()
    private val blue = 0xFF3366CC.toInt()

    private fun store(page: FakeWinPage) = FrameStore(page, guardDraws = true, looks = true)

    @Test fun aFrameOfNothingIsClear() {
        val page = FakeWinPage()
        val store = store(page)
        page.fill = 0x00000000
        page.frame = Triple(320, 480, 1L)
        store.pull(full = false)
        assertEquals(FrameLook.Clear, store.look)
        store.close()
    }

    @Test fun aFrameOfOneColourIsUniformAndAnythingMoreIsVaried() {
        val page = FakeWinPage()
        val store = store(page)
        page.fill = white
        page.frame = Triple(320, 480, 1L)
        store.pull(full = false)
        assertEquals(FrameLook.Uniform(white), store.look)
        // Text on the page's background: one of the nine points on it is enough.
        page.pattern = { x, y -> if (x in 150..170 && y in 230..250) 0xFF000000.toInt() else white }
        page.frame = Triple(320, 480, 2L)
        store.pull(full = false)
        assertEquals(FrameLook.Varied, store.look)
        store.close()
    }

    @Test fun whatASurfaceDrewKeepsItsLookUntilItDrawsAnother() {
        val page = FakeWinPage()
        val store = store(page)
        page.fill = white
        page.frame = Triple(320, 480, 1L)
        store.pull(full = false)
        assertNull(store.drawnLook, "nothing drawn yet")
        store.onDrawn()
        page.fill = blue
        page.frame = Triple(320, 480, 2L)
        store.pull(full = false)
        assertEquals(FrameLook.Uniform(blue), store.look)
        assertEquals(FrameLook.Uniform(white), store.drawnLook, "what was drawn is still the white one")
        store.onDrawn()
        assertEquals(FrameLook.Uniform(blue), store.drawnLook)
        store.forgetDrawn()
        assertNull(store.drawnLook)
        store.hideUntil(5L)
        assertNull(store.look, "a hidden frame says nothing")
        store.close()
    }

    @Test fun aStoreThatDoesntLookSaysNothing() {
        val page = FakeWinPage()
        val store = FrameStore(page, guardDraws = true)
        page.fill = 0x00000000
        page.frame = Triple(320, 480, 1L)
        store.pull(full = false)
        store.onDrawn()
        assertNull(store.look)
        assertNull(store.drawnLook)
        store.close()
    }
}
