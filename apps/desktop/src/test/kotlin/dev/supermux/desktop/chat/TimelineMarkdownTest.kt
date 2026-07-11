package dev.supermux.desktop.chat

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import dev.supermux.ui.ColumnAlign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Desktop renderer coverage for the GFM block/span types the shared parser gained (tables,
 * task lists, strikethrough, `[label](url)` links and standalone images). The parser itself is
 * shared + tested elsewhere; these host the pure-Compose [MarkdownBody]/[AssistantMessage] under
 * [runComposeUiTest] and assert the DESKTOP rendering (glyphs, testTags, link annotations).
 */
@OptIn(ExperimentalTestApi::class)
class TimelineMarkdownTest {

    // ── Pure helper ───────────────────────────────────────────────────────────────────

    @Test fun columnTextAlign_maps_each_column_align() {
        assertEquals(TextAlign.Left, columnTextAlign(ColumnAlign.LEFT))
        assertEquals(TextAlign.Center, columnTextAlign(ColumnAlign.CENTER))
        assertEquals(TextAlign.Right, columnTextAlign(ColumnAlign.RIGHT))
    }

    // ── Tables ────────────────────────────────────────────────────────────────────────

    @Test fun table_renders_grid_with_header_and_data_cells() = runComposeUiTest {
        val md = """
            | Name | Role |
            | :--- | ---: |
            | Ada  | Dev  |
        """.trimIndent()
        setContent { MarkdownBody(text = md) }

        onNodeWithTag("md_table").assertIsDisplayed()
        onNodeWithText("Name").assertIsDisplayed()
        onNodeWithText("Role").assertIsDisplayed()
        onNodeWithText("Ada").assertIsDisplayed()
        onNodeWithText("Dev").assertIsDisplayed()
    }

    // ── Task lists ──────────────────────────────────────────────────────────────────────

    @Test fun task_list_shows_checked_and_unchecked_glyphs() = runComposeUiTest {
        val md = """
            - [x] done
            - [ ] todo
            - plain
        """.trimIndent()
        setContent { MarkdownBody(text = md) }

        onNodeWithText("☑").assertIsDisplayed() // checked task
        onNodeWithText("☐").assertIsDisplayed() // unchecked task
        onNodeWithText("•").assertIsDisplayed() // plain bullet keeps the dot
    }

    // ── Strikethrough ─────────────────────────────────────────────────────────────────

    @Test fun strikethrough_span_carries_line_through_decoration() = runComposeUiTest {
        setContent { MarkdownBody(text = "this is ~~gone~~ now") }

        val node = onNodeWithText("this is gone now", substring = true).fetchSemanticsNode()
        val annotated = node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()
        assertTrue(annotated != null, "expected rendered text semantics")
        val struck = annotated.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough }
        assertTrue(struck, "expected a LineThrough span over the struck text")
    }

    // ── `[label](url)` web links ────────────────────────────────────────────────────────

    @Test fun labeled_link_renders_label_with_url_link_annotation() = runComposeUiTest {
        setContent { MarkdownBody(text = "see [the docs](https://example.com/docs) here") }

        val node = onNodeWithText("the docs", substring = true).fetchSemanticsNode()
        val annotated = node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()
        assertTrue(annotated != null, "expected rendered text semantics")
        val urls = annotated.getLinkAnnotations(0, annotated.length)
            .mapNotNull { (it.item as? LinkAnnotation.Url)?.url }
        assertTrue("https://example.com/docs" in urls, "expected a Url link annotation carrying the href, got $urls")
    }

    // ── Standalone images render as a tappable link line (no Coil3) ──────────────────────

    @Test fun standalone_image_renders_link_line_opening_the_url() = runComposeUiTest {
        setContent { MarkdownBody(text = "![a diagram](https://example.com/pic.png)") }

        onNodeWithTag("md_image").assertIsDisplayed()
        val node = onNodeWithTag("md_image").fetchSemanticsNode()
        val annotated = node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()
        assertTrue(annotated != null, "expected rendered image link-line text")
        assertTrue(annotated.text.contains("a diagram"), "expected the alt text as the link label")
        val urls = annotated.getLinkAnnotations(0, annotated.length)
            .mapNotNull { (it.item as? LinkAnnotation.Url)?.url }
        assertTrue("https://example.com/pic.png" in urls, "expected the image url as a Url link annotation, got $urls")
    }
}
