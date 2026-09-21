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

    @Test fun bare_url_with_file_extension_stays_plain() {
        val url = "https://supermux-core-design.ustalabs.com/event-taxonomy-report.md"
        assertEquals(listOf(MdSpan("$url this kinda", SpanStyleKind.PLAIN)), parseInlineMarkdown("$url this kinda"))
    }

    @Test fun markdown_link_to_file_opens_editor() {
        assertEquals(
            listOf(MdSpan("FilePathRef.kt", SpanStyleKind.LINK, FilePathRef("apps/shared/FilePathRef.kt", 30))),
            parseInlineMarkdown("[FilePathRef.kt](apps/shared/FilePathRef.kt:30)"),
        )
        assertEquals(
            listOf(MdSpan("a", SpanStyleKind.LINK, FilePathRef("src/a.ts", 10, 20))),
            parseInlineMarkdown("[a](src/a.ts#L10-L20)"),
        )
        assertEquals(
            listOf(MdSpan("readme", SpanStyleKind.LINK, FilePathRef("README.md"))),
            parseInlineMarkdown("[readme](README.md)"),
        )
        assertEquals(
            listOf(MdSpan("home", SpanStyleKind.LINK, FilePathRef("/home/ahmet/x/app.kt", 5))),
            parseInlineMarkdown("[home](/home/ahmet/x/app.kt:5)"),
        )
    }

    @Test fun markdown_link_to_web_stays_url() {
        val url = "https://supermux-core-design.ustalabs.com/event-taxonomy-report.md"
        assertEquals(listOf(MdSpan("report", SpanStyleKind.LINK, url = url)), parseInlineMarkdown("[report]($url)"))
        assertEquals(listOf(MdSpan("top", SpanStyleKind.LINK, url = "#top")), parseInlineMarkdown("[top](#top)"))
        assertEquals(listOf(MdSpan("m", SpanStyleKind.LINK, url = "mailto:a@b.co")), parseInlineMarkdown("[m](mailto:a@b.co)"))
    }
}
