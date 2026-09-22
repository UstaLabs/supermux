package dev.supermux.terminal.compose

import dev.supermux.terminal.TerminalCell
import dev.supermux.terminal.TerminalRow
import dev.supermux.terminal.TerminalSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The reducer contract, written before anything was drawn: what the session publishes (one full
 * frame, then partial ones) has to become a complete screen without ever indexing a row that is not
 * there.
 */
class ViewportModelTest {
    private val size = TerminalSize(8, 4, 8, 16)

    @Test fun aFullViewportReplacesEveryRow() {
        val model = ViewportModel()
        val applied = model.apply(ViewportFixtures.full(1, size, listOf("one", "two", "three", "four")))
        assertTrue(applied is ViewportUpdate.Applied, "got $applied")

        val replaced = model.apply(ViewportFixtures.full(2, size, listOf("A", "B", "C", "D")))
        assertTrue(replaced is ViewportUpdate.Applied, "got $replaced")
        val frame = assertNotNull(model.frame)
        assertEquals(listOf("A", "B", "C", "D"), (0 until size.rows).map { frame.rowText(it) })
        assertEquals(size.rows, frame.rows.size)
        assertEquals((0 until size.rows).toList(), frame.rows.map { it.index })
    }

    @Test fun anIncrementalViewportReplacesOnlyTheRowsItNames() {
        val model = ViewportModel()
        model.apply(ViewportFixtures.full(1, size, listOf("one", "two", "three", "four")))
        val untouched = assertNotNull(model.frame).rows[3]

        val applied = model.apply(ViewportFixtures.partial(2, size, mapOf(1 to "patched")))
        assertTrue(applied is ViewportUpdate.Applied, "got $applied")

        val frame = assertNotNull(model.frame)
        assertEquals(listOf("one", "patched", "three", "four"), (0 until size.rows).map { frame.rowText(it) })
        // Untouched rows are the SAME objects: patching must not rebuild (or lose) what it did not
        // receive.
        assertSame(untouched, frame.rows[3])
        assertEquals(2L, frame.generation)
    }

    @Test fun changedDimensionsRequireAFullViewport() {
        val model = ViewportModel()
        model.apply(ViewportFixtures.full(1, size, listOf("one", "two", "three", "four")))

        val wider = TerminalSize(12, 4, 8, 16)
        val rejected = model.apply(ViewportFixtures.partial(2, wider, mapOf(0 to "grown")))
        assertTrue(rejected is ViewportUpdate.Rejected, "got $rejected")
        assertEquals(ViewportRejection.NEEDS_FULL, rejected.reason)
        // The old screen is still on display, unchanged and consistent with its own size.
        val frame = assertNotNull(model.frame)
        assertEquals(size, frame.size)
        assertEquals("one", frame.rowText(0))

        val applied = model.apply(ViewportFixtures.full(3, wider, listOf("grown", "", "", "")))
        assertTrue(applied is ViewportUpdate.Applied, "got $applied")
        assertEquals(wider, assertNotNull(model.frame).size)
    }

    @Test fun anOutOfOrderGenerationIsRejected() {
        val model = ViewportModel()
        model.apply(ViewportFixtures.full(5, size, listOf("newest")))

        val older = model.apply(ViewportFixtures.partial(4, size, mapOf(0 to "stale")))
        assertTrue(older is ViewportUpdate.Rejected, "got $older")
        assertEquals(ViewportRejection.STALE_GENERATION, older.reason)

        val same = model.apply(ViewportFixtures.partial(5, size, mapOf(0 to "stale")))
        assertTrue(same is ViewportUpdate.Rejected, "got $same")
        assertEquals(ViewportRejection.STALE_GENERATION, same.reason)

        val olderFull = model.apply(ViewportFixtures.full(4, size, listOf("stale")))
        assertTrue(olderFull is ViewportUpdate.Rejected, "got $olderFull")

        assertEquals("newest", assertNotNull(model.frame).rowText(0))

        // A full frame that REPEATS the current generation is not stale: that is what the session
        // publishes when a surface asks for every row without the screen having changed.
        val repeated = model.apply(ViewportFixtures.full(5, size, listOf("newest", "rest")))
        assertTrue(repeated is ViewportUpdate.Applied, "got $repeated")
        assertEquals("rest", assertNotNull(model.frame).rowText(1))
    }

    @Test fun aResetInvalidatesThePreviousGenerationNamespace() {
        val model = ViewportModel()
        model.apply(ViewportFixtures.full(9, size, listOf("secret", "secret", "secret", "secret")))
        val epoch = model.epoch

        model.reset()
        assertNull(model.frame, "a reset screen shows nothing of the session before it")
        assertEquals(epoch + 1, model.epoch)

        // A partial frame cannot bring the old rows back — there is nothing to patch.
        val rejected = model.apply(ViewportFixtures.partial(10, size, mapOf(0 to "after")))
        assertTrue(rejected is ViewportUpdate.Rejected, "got $rejected")
        assertEquals(ViewportRejection.NEEDS_FULL, rejected.reason)
        assertNull(model.frame)

        // And generations that restart from zero are not mistaken for out-of-order updates.
        val applied = model.apply(ViewportFixtures.full(0, size, listOf("after")))
        assertTrue(applied is ViewportUpdate.Applied, "got $applied")
        val frame = assertNotNull(model.frame)
        assertEquals(epoch + 1, frame.epoch)
        assertEquals(listOf("after", "", "", ""), (0 until size.rows).map { frame.rowText(it) })
    }

    @Test fun impossibleGeometryIsADiagnosticNotAnIndex() {
        val model = ViewportModel()
        model.apply(ViewportFixtures.full(1, size, listOf("one", "two", "three", "four")))

        val outside = model.apply(
            ViewportFixtures.viewport(2, size, listOf(ViewportFixtures.row(9, "nope", size.columns)), full = false),
        )
        assertTrue(outside is ViewportUpdate.Rejected, "got $outside")
        assertEquals(ViewportRejection.IMPOSSIBLE_GEOMETRY, outside.reason)
        assertTrue("row 9" in outside.diagnostic, "diagnostic should name the row: ${outside.diagnostic}")

        val tooWide = TerminalRow(0, List(size.columns + 3) { TerminalCell("x", 1, ViewportFixtures.DEFAULT_STYLE) })
        val wide = model.apply(ViewportFixtures.viewport(3, size, listOf(tooWide), full = false))
        assertTrue(wide is ViewportUpdate.Rejected, "got $wide")
        assertEquals(ViewportRejection.IMPOSSIBLE_GEOMETRY, wide.reason)

        val gappy = model.apply(
            ViewportFixtures.viewport(
                4,
                size,
                listOf(ViewportFixtures.row(0, "a", size.columns), ViewportFixtures.row(2, "c", size.columns)),
                full = true,
            ),
        )
        assertTrue(gappy is ViewportUpdate.Rejected, "got $gappy")
        assertEquals(ViewportRejection.IMPOSSIBLE_GEOMETRY, gappy.reason)

        // Nothing of the above touched the screen.
        val frame = assertNotNull(model.frame)
        assertEquals(1L, frame.generation)
        assertEquals(listOf("one", "two", "three", "four"), (0 until size.rows).map { frame.rowText(it) })
    }

    @Test fun theFrameNumberCountsAppliedUpdatesOnly() {
        val model = ViewportModel()
        model.apply(ViewportFixtures.full(1, size, listOf("one")))
        assertEquals(0L, assertNotNull(model.frame).frameNumber)
        model.apply(ViewportFixtures.partial(0, size, mapOf(0 to "stale")))
        assertEquals(0L, assertNotNull(model.frame).frameNumber)
        model.apply(ViewportFixtures.partial(2, size, mapOf(0 to "next")))
        assertEquals(1L, assertNotNull(model.frame).frameNumber)
    }

    @Test fun aMissedPublicationIsNotPatchedOver() {
        val model = ViewportModel()
        // Two surfaces share one session: this one applied publication 1 and the sibling then
        // acknowledged publication 2, so the conflated flow hands this one publication 3 directly.
        assertTrue(model.apply(ViewportFixtures.full(1, size, listOf("one", "two"), sequence = 1)) is ViewportUpdate.Applied)
        val skipped = model.apply(ViewportFixtures.partial(3, size, mapOf(1 to "patched"), sequence = 3))
        assertTrue(skipped is ViewportUpdate.Rejected, "got $skipped")
        assertEquals(ViewportRejection.NEEDS_FULL, skipped.reason)
        assertTrue("missed 1" in skipped.diagnostic, skipped.diagnostic)
        // The screen kept the rows it could prove, and row 1 was NOT patched from the wrong base.
        assertEquals(listOf("one", "two", "", ""), (0 until size.rows).map { assertNotNull(model.frame).rowText(it) })

        // The surface asks for a full frame; that heals it whatever the sequence jumped to.
        val healed = model.apply(ViewportFixtures.full(4, size, listOf("one", "patched"), sequence = 4))
        assertTrue(healed is ViewportUpdate.Applied, "got $healed")
        // And patching resumes from the new base.
        assertTrue(model.apply(ViewportFixtures.partial(5, size, mapOf(2 to "three"), sequence = 5)) is ViewportUpdate.Applied)
        assertEquals("three", assertNotNull(model.frame).rowText(2))
    }

    @Test fun consecutivePublicationsPatchAndUnpublishedFramesAreNotChecked() {
        val model = ViewportModel()
        model.apply(ViewportFixtures.full(1, size, listOf("one"), sequence = 7))
        assertTrue(model.apply(ViewportFixtures.partial(2, size, mapOf(1 to "two"), sequence = 8)) is ViewportUpdate.Applied)
        assertEquals("two", assertNotNull(model.frame).rowText(1))

        // A frame that no session published (sequence 0) carries no counter to check: the generation
        // ordering is all there is, and the reducer stays usable for hand-built frames and tests.
        val fresh = ViewportModel()
        fresh.apply(ViewportFixtures.full(1, size, listOf("one")))
        assertTrue(fresh.apply(ViewportFixtures.partial(9, size, mapOf(1 to "two"))) is ViewportUpdate.Applied)
    }
}
