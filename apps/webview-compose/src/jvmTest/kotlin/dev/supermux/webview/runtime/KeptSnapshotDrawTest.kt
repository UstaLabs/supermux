package dev.supermux.webview.runtime

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A kept snapshot is drawn as its view's cover at every frame until the page paints. Compose's Skia canvas makes a
 * Skia image of the bitmap at each draw, which shares the pixels of an immutable bitmap but copies a mutable one whole:
 * 16 MB a frame for a big pane's cover, and on a GPU a new texture to upload each time.
 */
class KeptSnapshotDrawTest {
    @Test fun aSnapshotRedrawnSmallerIsKeptImmutable() {
        // A pane of 2400 × 1800 px: over the 4,000,000-pixel cap, so it is redrawn.
        val taken = ImageBitmap(2400, 1800)
        val kept = keptSnapshot(taken, viewWidthDp = 1200f)
        assertNotSame(taken, kept, "it wasn't redrawn")
        assertTrue(kept.asSkiaBitmap().isImmutable, "a redrawn snapshot is copied whole at every draw of its cover")
    }

    @Test fun aSnapshotKeptAsItWasTakenIsKeptImmutable() {
        // An engine's own picture, already at the size kept: the very image becomes the cover.
        val taken = ImageBitmap(300, 200)
        val kept = keptSnapshot(taken, viewWidthDp = 150f)
        assertSame(taken, kept)
        assertTrue(kept.asSkiaBitmap().isImmutable, "an engine's snapshot is copied whole at every draw of its cover")
    }
}
