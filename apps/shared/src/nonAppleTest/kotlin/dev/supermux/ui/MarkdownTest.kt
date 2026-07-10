package dev.supermux.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownTest {
    @Test fun bold() {
        assertEquals(
            listOf(
                MdSpan("hi ", SpanStyleKind.PLAIN),
                MdSpan("bold", SpanStyleKind.BOLD),
                MdSpan(" there", SpanStyleKind.PLAIN),
            ),
            parseInlineMarkdown("hi **bold** there"),
        )
    }

    @Test fun code_and_italic() {
        assertEquals(
            listOf(MdSpan("run ", SpanStyleKind.PLAIN), MdSpan("ls -la", SpanStyleKind.CODE)),
            parseInlineMarkdown("run `ls -la`"),
        )
        assertEquals(
            listOf(MdSpan("a ", SpanStyleKind.PLAIN), MdSpan("b", SpanStyleKind.ITALIC)),
            parseInlineMarkdown("a *b*"),
        )
    }

    @Test fun unclosed_is_literal() {
        assertEquals(
            listOf(MdSpan("a **b", SpanStyleKind.PLAIN)),
            parseInlineMarkdown("a **b"),
        )
    }
}
