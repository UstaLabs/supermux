package dev.supermux.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import dev.supermux.ui.MdBlock
import dev.supermux.ui.theme.Space
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.LocalPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Headings that outrank the prose, section spacing, nested list indentation. */
@OptIn(ExperimentalTestApi::class)
class MarkdownHierarchyTest {

    @Test fun every_heading_level_is_at_least_body_size_and_they_descend() {
        for (document in listOf(true, false)) {
            val scales = (1..4).map { mdHeadingScale(it, document) }
            assertTrue(scales.all { it >= 1f }, "document=$document: $scales")
            assertEquals(scales.sortedDescending(), scales, "document=$document")
        }
        assertTrue(mdHeadingScale(1, document = true) > mdHeadingScale(1, document = false))
    }

    @Test fun a_heading_has_more_air_above_than_below_in_a_document() {
        val h = MdBlock.Heading(2, "Section")
        val p = MdBlock.Prose("text")
        assertTrue(mdBlockGap(p, h, document = true) > mdBlockGap(h, p, document = true))
    }

    @Test fun items_of_one_list_sit_closer_than_paragraphs() {
        val a = MdBlock.Bullet("a")
        val b = MdBlock.Numbered(1, "b", depth = 1)
        assertEquals(Space.xs, mdBlockGap(a, b, document = true))
        assertTrue(mdBlockGap(MdBlock.Prose("x"), MdBlock.Prose("y"), document = true) > Space.xs)
    }

    @Test fun a_numbered_list_right_after_a_bullet_list_starts_apart() {
        assertTrue(mdBlockGap(MdBlock.Bullet("a"), MdBlock.Numbered(1, "b"), document = true) > Space.xs)
    }

    @Test fun a_document_paragraph_joins_source_lines_but_keeps_hard_breaks() = runComposeUiTest {
        var soft = ""
        var chat = ""
        setContent {
            CompositionLocalProvider(LocalPlatform provides FakePlatform()) {
                soft = mdAnnotated("one\ntwo  \nthree", softBreaks = true).text
                chat = mdAnnotated("one\ntwo", softBreaks = false).text
            }
        }
        waitForIdle()
        assertEquals("one two\nthree", soft)
        assertEquals("one\ntwo", chat)
    }

    @Test fun bullet_glyph_changes_with_depth() {
        assertEquals(3, (0..2).map { mdBulletGlyph(it) }.toSet().size)
        assertEquals(mdBulletGlyph(0), mdBulletGlyph(3))
    }

    @Test fun document_h1_renders_larger_than_its_body_text() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalPlatform provides FakePlatform()) {
                MarkdownBody("# Title\n\nSome body text.", document = true)
            }
        }
        fun height(text: String): Int {
            val results = mutableListOf<TextLayoutResult>()
            onNodeWithText(text).fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            return results.first().size.height
        }
        assertTrue(height("Title") > height("Some body text."))
    }
}
