package dev.supermux.webview.engine.frames

import dev.supermux.webview.engine.linux.FakeWpeView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Picture
import org.jetbrains.skia.PictureRecorder
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/**
 * Compose records each frame into a Skia picture, and a renderer may draw that picture later: skiko's Direct3D renderer
 * on Windows draws it on a thread of its own, after the EDT moved on (the VM's crash: an access violation in skiko,
 * reading a released engine's frame). Whatever the store does next, a recording draws the frame it recorded.
 */
class FrameStoreReplayTest {
    @Test fun aFrameARecordingHoldsIsNeverWrittenOver() {
        val view = FakeWpeView()
        val store = FrameStore(view, guardDraws = true)
        view.fill = RED
        view.frame = Triple(64, 32, 1L)
        assertTrue(store.pull(full = false))
        val held = record(assertNotNull(store.image))
        view.fill = BLUE
        view.frame = Triple(64, 32, 2L)
        assertTrue(store.pull(full = false))
        assertEquals(RED, colourOf(held), "the recording still draws the frame it recorded")
        assertEquals(BLUE, colourOf(record(assertNotNull(store.image))), "the store shows the new one")
        held.close()
        store.close()
    }

    @Test fun aBlockNoRecordingHoldsAnyMoreIsWrittenAgain() {
        // Two blocks by turns while each frame's recording goes before the next pull, as Compose's do: no third.
        val view = FakeWpeView()
        val store = FrameStore(view, guardDraws = true)
        val addresses = mutableSetOf<Long>()
        var held: Picture? = null
        for (seq in 1L..6L) {
            view.fill = if (seq % 2 == 0L) BLUE else RED
            view.frame = Triple(64, 32, seq)
            assertTrue(store.pull(full = false), "frame $seq")
            addresses += view.dst
            held?.close()   // the renderer drew it, and Compose's next frame replaced it
            held = record(assertNotNull(store.image))
        }
        assertEquals(2, addresses.size, "blocks used: $addresses")
        held?.close()
        store.close()
    }

    @Test fun aFrameIsCopiedWholeIntoAnotherBlock() {
        // A block that wasn't the last written holds an older frame: what changed since the last pull isn't enough.
        val view = FakeWpeView()
        val store = FrameStore(view, guardDraws = true)
        view.frame = Triple(64, 32, 1L)
        store.pull(full = false)
        val held = record(assertNotNull(store.image))
        view.frame = Triple(64, 32, 2L)
        view.pulls.clear()
        assertTrue(store.pull(full = false))
        assertEquals(listOf(true), view.pulls)
        held.close()
        store.close()
    }

    @Test fun whileEveryBlockIsHeldTheFrameWaits() {
        // A renderer far behind: the store doesn't take more memory without bound, and the frame is pulled once a block
        // is free again (the glue keeps the newest).
        val view = FakeWpeView()
        val store = FrameStore(view, guardDraws = true)
        val held = mutableListOf<Picture>()
        var seq = 0L
        while (true) {
            view.frame = Triple(64, 32, ++seq)
            if (!store.pull(full = false)) break
            held += record(assertNotNull(store.image))
            assertTrue(seq < 10, "the store took a block for every frame")
        }
        assertEquals(seq - 1, store.seq, "the last frame shown is the last one a block was free for")
        held.removeAt(0).close()
        assertTrue(store.pull(full = false), "a block is free again")
        assertEquals(seq, store.seq)
        held.forEach { it.close() }
        store.close()
    }

    @Test fun aClosedStoresFrameStaysWithTheRecordingThatHoldsIt() {
        // An engine released while the renderer still has its last frame to draw. Then memory of the same size is taken
        // and painted, as the next engine's would be: the recording still draws its own frame.
        for (guard in listOf(false, true)) {
            val view = FakeWpeView()
            val store = FrameStore(view, guardDraws = guard)
            view.fill = RED
            view.frame = Triple(64, 32, 1L)
            store.pull(full = true)
            val held = record(assertNotNull(store.image))
            store.close()
            val others = List(8) { Bitmap().apply { allocPixels(ImageInfo(256, 256, ColorType.BGRA_8888, ColorAlphaType.PREMUL)); erase(GREEN) } }
            assertEquals(RED, colourOf(held), "guard $guard")
            others.forEach { it.close() }
            held.close()
        }
    }

    @Test fun anUnguardedStoreWritesInPlace() {
        // Linux's renderer draws on the EDT, before the next pull: one block, what changed copied into it (F10).
        val view = FakeWpeView()
        val store = FrameStore(view)
        view.frame = Triple(64, 32, 1L)
        store.pull(full = true)
        val held = record(assertNotNull(store.image))
        val dst = view.dst
        view.frame = Triple(64, 32, 2L)
        view.pulls.clear()
        assertTrue(store.pull(full = false))
        assertEquals(dst, view.dst)
        assertEquals(listOf(false), view.pulls)
        held.close()
        store.close()
    }

    private fun record(image: Image): Picture {
        val recorder = PictureRecorder()
        recorder.beginRecording(Rect.makeWH(image.width.toFloat(), image.height.toFloat())).drawImage(image, 0f, 0f)
        return recorder.finishRecordingAsPicture().also { recorder.close() }
    }

    /** What [picture] draws at (8, 8), as 0xAARRGGBB. */
    private fun colourOf(picture: Picture): Int {
        val surface = Surface.makeRasterN32Premul(64, 32)
        surface.canvas.drawPicture(picture)
        val bitmap = Bitmap().apply { allocN32Pixels(64, 32) }
        check(surface.readPixels(bitmap, 0, 0))
        return bitmap.getColor(8, 8).also {
            bitmap.close()
            surface.close()
        }
    }

    private companion object {
        const val RED = 0xFFCC3333.toInt()
        const val BLUE = 0xFF3366CC.toInt()
        const val GREEN = 0xFF33CC66.toInt()
    }
}
