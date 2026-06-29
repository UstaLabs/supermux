package dev.supermux.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownLinkTest {
    @Test fun links_bare_path_in_prose() {
        assertEquals(
            listOf(
                MdSpan("see ", SpanStyleKind.PLAIN),
                MdSpan("src/main.ts:42", SpanStyleKind.LINK, FilePathRef("src/main.ts", 42)),
                MdSpan(" now", SpanStyleKind.PLAIN),
            ),
            parseInlineMarkdown("see src/main.ts:42 now"),
        )
    }

    @Test fun links_path_inside_inline_code() {
        assertEquals(
            listOf(
                MdSpan("open ", SpanStyleKind.PLAIN),
                MdSpan("src/a.ts", SpanStyleKind.LINK, FilePathRef("src/a.ts")),
            ),
            parseInlineMarkdown("open `src/a.ts`"),
        )
    }

    @Test fun non_path_code_unchanged() {
        assertEquals(
            listOf(MdSpan("run ", SpanStyleKind.PLAIN), MdSpan("ls -la", SpanStyleKind.CODE)),
            parseInlineMarkdown("run `ls -la`"),
        )
    }

    @Test fun bold_without_path_unchanged() {
        assertEquals(
            listOf(
                MdSpan("hi ", SpanStyleKind.PLAIN),
                MdSpan("bold", SpanStyleKind.BOLD),
                MdSpan(" there", SpanStyleKind.PLAIN),
            ),
            parseInlineMarkdown("hi **bold** there"),
        )
    }
}
