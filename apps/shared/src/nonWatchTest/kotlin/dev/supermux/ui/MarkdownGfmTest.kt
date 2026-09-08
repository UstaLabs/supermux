package dev.supermux.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

class MarkdownGfmTest {
    // ---- Inline links --------------------------------------------------------
    @Test fun inline_link_becomes_url_span() {
        assertEquals(
            listOf(
                MdSpan("see ", SpanStyleKind.PLAIN),
                MdSpan("Google", SpanStyleKind.LINK, url = "https://google.com"),
                MdSpan(" now", SpanStyleKind.PLAIN),
            ),
            parseInlineMarkdown("see [Google](https://google.com) now"),
        )
    }

    @Test fun link_empty_label_falls_back_to_url() {
        val spans = parseInlineMarkdown("[](https://x.com)")
        assertEquals(listOf(MdSpan("https://x.com", SpanStyleKind.LINK, url = "https://x.com")), spans)
    }

    @Test fun strikethrough_span() {
        assertEquals(
            listOf(MdSpan("a ", SpanStyleKind.PLAIN), MdSpan("gone", SpanStyleKind.STRIKE)),
            parseInlineMarkdown("a ~~gone~~"),
        )
    }

    @Test fun image_inline_falls_back_to_link() {
        assertEquals(
            listOf(MdSpan("alt", SpanStyleKind.LINK, url = "https://img/x.png")),
            parseInlineMarkdown("![alt](https://img/x.png)"),
        )
    }

    // ---- Tables --------------------------------------------------------------
    @Test fun table_parsed_with_aligns_and_rows() {
        val md = """
            | Name | Age | City |
            |:-----|:---:|-----:|
            | Al   | 30  | NYC  |
            | Bo   | 25  | LA   |
        """.trimIndent()
        val blocks = parseMarkdownBlocks(md)
        assertEquals(1, blocks.size)
        val t = blocks[0] as MdBlock.Table
        assertEquals(listOf("Name", "Age", "City"), t.headers)
        assertEquals(listOf(ColumnAlign.LEFT, ColumnAlign.CENTER, ColumnAlign.RIGHT), t.aligns)
        assertEquals(listOf(listOf("Al", "30", "NYC"), listOf("Bo", "25", "LA")), t.rows)
    }

    @Test fun ragged_table_row_is_padded() {
        val md = "| a | b |\n|---|---|\n| x |"
        val t = parseMarkdownBlocks(md)[0] as MdBlock.Table
        assertEquals(listOf("x", ""), t.rows[0])
    }

    // ---- Task lists ----------------------------------------------------------
    @Test fun task_list_flags() {
        val blocks = parseMarkdownBlocks("- [ ] todo\n- [x] done\n- plain")
        val bullets = blocks.filterIsInstance<MdBlock.Bullet>()
        assertEquals(3, bullets.size)
        assertEquals("todo", bullets[0].text); assertEquals(false, bullets[0].task)
        assertEquals("done", bullets[1].text); assertEquals(true, bullets[1].task)
        assertEquals("plain", bullets[2].text); assertNull(bullets[2].task)
    }

    // ---- Standalone image block ---------------------------------------------
    @Test fun standalone_image_is_image_block() {
        val blocks = parseMarkdownBlocks("![a cat](https://img/cat.png)")
        assertEquals(1, blocks.size)
        val img = blocks[0] as MdBlock.Image
        assertEquals("https://img/cat.png", img.url)
        assertEquals("a cat", img.alt)
    }

    @Test fun text_with_image_stays_prose() {
        val blocks = parseMarkdownBlocks("look ![a](b.png) here")
        assertTrue(blocks[0] is MdBlock.Prose, "mixed image+text should be prose, got ${blocks[0]}")
    }

    // ---- Headings / quotes / lists still work over the AST -------------------
    @Test fun heading_levels() {
        val blocks = parseMarkdownBlocks("# One\n## Two")
        assertEquals(MdBlock.Heading(1, "One"), blocks[0])
        assertEquals(MdBlock.Heading(2, "Two"), blocks[1])
    }

    @Test fun blockquote_strips_marker() {
        val blocks = parseMarkdownBlocks("> hello there")
        assertEquals(MdBlock.Quote("hello there"), blocks[0])
    }

    @Test fun link_and_bold_together() {
        assertEquals(
            listOf(
                MdSpan("a ", SpanStyleKind.PLAIN),
                MdSpan("b", SpanStyleKind.BOLD),
                MdSpan(" ", SpanStyleKind.PLAIN),
                MdSpan("c", SpanStyleKind.LINK, url = "u"),
            ),
            parseInlineMarkdown("a **b** [c](u)"),
        )
    }

    /** Bare URLs stay PLAIN so platform renderers can appendLinkify them (Android/desktop). */
    @Test fun bare_https_url_stays_plain_for_linkify() {
        val spans = parseInlineMarkdown("see https://example.com for details")
        assertEquals(1, spans.size)
        assertEquals(SpanStyleKind.PLAIN, spans[0].kind)
        assertTrue(spans[0].text.contains("https://example.com"))
    }
}
