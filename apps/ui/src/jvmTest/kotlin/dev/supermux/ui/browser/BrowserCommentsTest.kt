package dev.supermux.ui.browser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import dev.supermux.webview.PageBox
import kotlin.test.Test
import kotlin.test.assertEquals

/** #122 3b: what a batch of comments says to the agent, and the picture each carries. */
class BrowserCommentsTest {
    @Test fun aBatchReadsAsTheDesignWritesIt() {
        val message = commentsMessage(
            url = "http://localhost:5173/checkout",
            viewedAs = "iPhone 16 · 393×852",
            comments = listOf(
                CommentOut(1, "the Pay button is cut off on mobile", "button.buy", "Pay €42", "comment-1.png"),
                CommentOut(2, "make this\nmatch ①", "aside.summary > h2", "", null),
            ),
        )
        assertEquals(
            """
            **2 browser comments** · http://localhost:5173/checkout
            Viewed as iPhone 16 · 393×852

            ### ① "the Pay button is cut off on mobile"
            - element: `button.buy` · text "Pay €42"
            - picture: comment-1.png

            ### ② "make this match ①"
            - element: `aside.summary > h2`
            """.trimIndent(),
            message,
        )
    }

    @Test fun oneCommentIsSingularAndPinsCountOnPast20() {
        assertEquals("**1 browser comment**\n\n### ③ \"x\"\n- element: `a`", commentsMessage(null, null, listOf(CommentOut(3, "x", "a", "", null))))
        assertEquals("⑳", pinMark(20))
        assertEquals("(21)", pinMark(21))
    }

    @Test fun theCropIsTheElementWithAMarginInsideThePage() {
        val page = ImageBitmap(400, 200)
        val crop = cropWithPin(page, PageBox(0.25f, 0.5f, 0.5f, 0.25f), Color.Red)
        assertEquals(200 + 2 * 24, crop.width) // 100…300 px, 24 each side
        assertEquals(50 + 2 * 24, crop.height) // 100…150 px
        val corner = cropWithPin(page, PageBox(0f, 0f, 0.1f, 0.1f), Color.Red)
        assertEquals(40 + 24, corner.width, "cut at the page's edge")
    }

    /** The element's text only when it helps: not when its selector already says it. */
    @Test fun theTextIsLeftOutWhenTheSelectorSaysIt() {
        val message = commentsMessage(null, null, listOf(CommentOut(1, "x", "button[aria-label='Close']", "Close", null)))
        assertEquals("**1 browser comment**\n\n### ① \"x\"\n- element: `button[aria-label='Close']`", message)
    }
}
