package dev.supermux.chat

import kotlin.test.Test
import kotlin.test.assertEquals

/** Card #76: Markdown that is still being written. */
class LiveMarkdownTest {
    @Test fun anUnfinishedTableRowWaitsForItsLine() =
        assertEquals("| a | b |\n|---|---|\n", liveMarkdown("| a | b |\n|---|---|\n| Par"))

    @Test fun openBoldAndCodeAreClosed() {
        assertEquals("this is **bo**", liveMarkdown("this is **bo"))
        assertEquals("run `ls -l`", liveMarkdown("run `ls -l"))
        assertEquals("done **bold** and `code`", liveMarkdown("done **bold** and `code`"))
    }

    @Test fun aBareOpenerIsHeldBack() {
        assertEquals("this is ", liveMarkdown("this is **"))
        assertEquals("run ", liveMarkdown("run `"))
    }

    @Test fun insideAnOpenFenceNothingChanges() =
        assertEquals("```py\nx = \"**\"", liveMarkdown("```py\nx = \"**\""))

    @Test fun onlyTheLastParagraphCounts() =
        assertEquals("a ** b\n\nnow **x**", liveMarkdown("a ** b\n\nnow **x"))
}
