package dev.supermux.ui.chat

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.ui.MdBlock
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Wrapping table columns, and markdown images read from the host instead of the web. */
@OptIn(ExperimentalTestApi::class)
class MarkdownTableMediaTest {

    // ── Table column widths ─────────────────────────────────────────────────────────────

    @Test fun column_takes_its_widest_cell_up_to_the_cap() {
        // 2 columns × 2 rows, row-major.
        val natural = intArrayOf(40, 900, 60, 120)
        assertContentEquals(intArrayOf(60, 280), mdTableColumnWidths(natural, cols = 2, cap = 280))
    }

    @Test fun long_cell_wraps_and_its_row_stays_aligned() = runComposeUiTest {
        val long = List(40) { "word" }.joinToString(" ")
        val md = """
            | Key | Value |
            | --- | --- |
            | a | $long |
            | b | short |
        """.trimIndent()
        setPlatformContent { MarkdownBody(text = md, modifier = Modifier.width(800.dp)) }

        val table = onNodeWithTag("md_table").getUnclippedBoundsInRoot()
        assertTrue(table.right - table.left < 800.dp, "a capped column keeps the table narrower than its one-line width")
        val longCell = onNodeWithText(long).getUnclippedBoundsInRoot()
        assertTrue(longCell.right - longCell.left <= MdTableDimens.MaxColumnWidth, "the long cell wraps inside the cap")
        val a = onNodeWithText("a").getUnclippedBoundsInRoot()
        assertEquals(a.top, longCell.top, "cells of one row start on one line")
        val b = onNodeWithText("b").getUnclippedBoundsInRoot()
        val short = onNodeWithText("short").getUnclippedBoundsInRoot()
        assertTrue(b.top >= longCell.bottom, "the next row starts below the wrapped cell")
        assertEquals(b.top, short.top)
    }

    // ── Host media paths ────────────────────────────────────────────────────────────────

    @Test fun resolves_relative_absolute_and_file_urls() {
        assertEquals("/repo/docs/img/a.png", resolveMarkdownMediaPath("img/a.png", "/repo/docs"))
        assertEquals("/repo/img/a.png", resolveMarkdownMediaPath("./../img/a.png", "/repo/docs/"))
        assertEquals("/tmp/out.mp4", resolveMarkdownMediaPath("/tmp/out.mp4", null))
        assertEquals("/tmp/my shot.png", resolveMarkdownMediaPath("file:///tmp/my%20shot.png?raw=1#x", null))
    }

    @Test fun web_data_and_baseless_paths_are_not_host_files() {
        assertNull(resolveMarkdownMediaPath("https://example.com/a.png", "/repo"))
        assertNull(resolveMarkdownMediaPath("data:image/png;base64,AAAA", "/repo"))
        assertNull(resolveMarkdownMediaPath("#anchor", "/repo"))
        assertNull(resolveMarkdownMediaPath("img/a.png", null))
    }

    @Test fun relative_image_reads_through_the_host_and_paints() = runComposeUiTest {
        val asked = mutableListOf<String>()
        val files = MarkdownFiles("/repo/docs") { path -> asked += path; Result.success(TINY_PNG_BYTES) }
        setPlatformContent {
            CompositionLocalProvider(LocalMarkdownFiles provides files) {
                MarkdownBody(text = "![shot](img/a.png)")
            }
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("md_image").fetchSemanticsNodes().any {
                it.config.getOrNull(SemanticsProperties.Text) == null
            }
        }
        assertEquals(listOf("/repo/docs/img/a.png"), asked)
    }

    @Test fun unreadable_host_image_falls_back_to_the_failure_line() = runComposeUiTest {
        val files = MarkdownFiles("/repo") { Result.failure(IllegalStateException("404")) }
        setPlatformContent {
            CompositionLocalProvider(LocalMarkdownFiles provides files) {
                MarkdownImage(MdBlock.Image(url = "gone.png", alt = "gone"))
            }
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("md_image").fetchSemanticsNodes().any {
                it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text?.contains("Couldn't load image") == true
            }
        }
    }

    @Test fun host_video_gets_the_inline_player() = runComposeUiTest {
        val files = MarkdownFiles("/repo") { Result.success(ByteArray(0)) }
        setPlatformContent {
            CompositionLocalProvider(LocalMarkdownFiles provides files) {
                MarkdownBody(text = "![demo](out/demo.mp4)")
            }
        }
        onNodeWithTag("attachment_video_poster").assertExists()
        onNodeWithTag("md_image").assertDoesNotExist()
    }
}
