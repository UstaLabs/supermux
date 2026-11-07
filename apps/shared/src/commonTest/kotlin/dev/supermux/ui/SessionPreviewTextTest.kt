package dev.supermux.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The session-list preview line is plain text (Mail/Messages parity) — agent markdown must not leak
 * into it. These pin the shapes actually seen in the live list, and, just as importantly, the
 * false positives: a stripper that is too eager mangles ordinary prose in every row it touches.
 */
class SessionPreviewTextTest {

    /** Verbatim shape of a real row: an ordered marker at a line start, inline code, and bold. */
    @Test fun stripsBoldInlineCodeAndAnOrderedMarker() {
        assertEquals(
            "Merged and pushed to dev. Committed on main",
            sessionPreviewPlainText("Merged and pushed to `dev`.\n1. **Committed** on `main`"),
        )
    }

    @Test fun stripsHeadingsBlockquotesAndBullets() {
        assertEquals("Result one two", sessionPreviewPlainText("### Result\n- one\n- two"))
        assertEquals("quoted reply", sessionPreviewPlainText("> quoted reply"))
        assertEquals("first second", sessionPreviewPlainText("1. first\n2. second"))
    }

    /** A bold run at position 0 must not be mistaken for a `*` bullet marker and half-eaten. */
    @Test fun stripsALeadingBoldRun() {
        assertEquals(
            "Mac needed real product changes — the green unread rail",
            sessionPreviewPlainText("**Mac needed real product changes** — the green unread rail"),
        )
    }

    @Test fun singleAsteriskEmphasisIsUnwrapped() {
        assertEquals("this is really important", sessionPreviewPlainText("this is *really* important"))
    }

    @Test fun collapsesNewlinesAndRunsOfWhitespace() {
        assertEquals("first line second line", sessionPreviewPlainText("first line\n\n   second    line\n"))
    }

    /** The URL is pure noise in a one-line row; the label is the part a human reads. */
    @Test fun aLinkKeepsItsLabelAndDropsTheUrl() {
        assertEquals(
            "see the PR for details",
            sessionPreviewPlainText("see [the PR](https://github.com/a/b/pull/1) for details"),
        )
    }

    /** Fenced code is usually the informative half of the message, so the fence goes, not the code. */
    @Test fun aCodeFenceKeepsItsContents() {
        assertEquals("bun test", sessionPreviewPlainText("```bash\nbun test\n```"))
    }

    /**
     * The regression guards. A list/quote/heading marker only counts at a LINE START, `_` is never
     * emphasis, and a lone `*` is arithmetic — each of these is a real sentence a naive stripper
     * turns into nonsense.
     */
    @Test fun ordinaryProseAndIdentifiersComeThroughUnmangled() {
        assertEquals("shipped v1. 2 more to go", sessionPreviewPlainText("shipped v1. 2 more to go"))
        assertEquals("the flag is set - see the docs", sessionPreviewPlainText("the flag is set - see the docs"))
        assertEquals("2 * 3 = 6", sessionPreviewPlainText("2 * 3 = 6"))
        assertEquals("renamed last_read_at to lastReadAt", sessionPreviewPlainText("renamed last_read_at to lastReadAt"))
        assertEquals(
            "Got it — 16:57:23, round-trip working.",
            sessionPreviewPlainText("Got it — 16:57:23, round-trip working."),
        )
    }

    /** It runs per visible row per recomposition, so the regex work has to be bounded by input size. */
    @Test fun veryLongInputIsBounded() {
        assertTrue(sessionPreviewPlainText("a".repeat(5_000)).length <= 300)
    }

    // A whole message to read (the overlay's bubble, "Read it all"): as plain as a preview, but nothing cut
    // and its lines kept.
    @Test fun aWholeMessageIsNeverCut() {
        val long = "word ".repeat(200).trim()
        assertEquals(long, messagePlainText(long))
    }

    @Test fun aWholeMessageKeepsItsLinesAndParagraphs() {
        assertEquals("First line.\nSecond line.\n\nNew para.", messagePlainText("First line.\nSecond line.\n\n\n\nNew **para**."))
    }

    @Test fun aWholeMessageKeepsItsListsReadable() {
        assertEquals("Plan\n• one\n• two\n1. first", messagePlainText("### Plan\n- one\n- two\n1. first"))
    }

    @Test fun aWholeMessageLosesItsMarkdownAsAPreviewDoes() {
        assertEquals("Merged dev, see the PR and this", messagePlainText("Merged `dev`, see [the PR](https://x.y/1) and **this**"))
        assertEquals("a b c", messagePlainText("a   b\t\tc"))
    }

    // The overlay reads a whole message as plain text, but its links must still open (#62): each
    // `[label](url)` keeps where its label lands. Bare URLs stay in the text as they are.
    @Test fun aWholeMessageKeepsWhereItsLinksGo() {
        val raw = "### Plan\n- see [the PR](https://x.y/1) and **[the docs](https://d.e/f)**\n- then https://a.b/c"
        val plain = messagePlainLinked(raw)
        assertEquals(messagePlainText(raw), plain.text)
        assertEquals("Plan\n• see the PR and the docs\n• then https://a.b/c", plain.text)
        assertEquals(
            listOf("the PR" to "https://x.y/1", "the docs" to "https://d.e/f"),
            plain.links.map { plain.text.substring(it.start, it.end) to it.url },
        )
    }

    @Test fun aLinkWithNoLabelLeavesNothingToOpen() {
        val plain = messagePlainLinked("see [](https://x.y/1) here")
        assertEquals("see here", plain.text)
        assertEquals(emptyList(), plain.links)
    }

    @Test fun aLinkThatWouldNotOpenKeepsOnlyItsLabel() {
        val plain = messagePlainLinked("see [docs](javascript:void) and [site](https://x.y/1)")
        assertEquals("see docs and site", plain.text)
        assertEquals(listOf("site" to "https://x.y/1"), plain.links.map { plain.text.substring(it.start, it.end) to it.url })
    }

    // The marks it keeps links with can't be faked by the text itself.
    @Test fun aMessageHoldingItsOwnMarksStillReads() {
        val plain = messagePlainLinked("odd  marks  and [a link](https://x.y/1)")
        assertEquals("odd marks and a link", plain.text)
        assertEquals(listOf("a link" to "https://x.y/1"), plain.links.map { plain.text.substring(it.start, it.end) to it.url })
    }
}
