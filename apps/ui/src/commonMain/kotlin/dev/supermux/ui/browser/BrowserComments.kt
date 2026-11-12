package dev.supermux.ui.browser

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.supermux.webview.PageBox
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One comment as it goes to the agent (#122 3b): its pin's number, the words, the element and its picture's name. */
internal data class CommentOut(val number: Int, val comment: String, val selector: String, val text: String, val picture: String?)

/** A pin's number as the message writes it: ①…⑳, then (21). */
internal fun pinMark(number: Int): String = if (number in 1..20) (0x2460 + number - 1).toChar().toString() else "($number)"

/**
 * The message a batch of comments is (§5.4): readable as it is, the page it was on as you'd type it, how it was viewed,
 * then each comment with its element (a selector that resolves to it, and its text) and its picture's name. Nothing
 * else from the page: the agent has the code.
 */
internal fun commentsMessage(url: String?, viewedAs: String?, comments: List<CommentOut>, ids: Map<Int, String>? = null): String = buildString {
    append(if (comments.size == 1) "**1 browser comment**" else "**${comments.size} browser comments**")
    url?.let { append(" · ").append(it) }
    append('\n')
    viewedAs?.let { append("Viewed as ").append(it).append('\n') }
    for (c in comments) {
        append("\n### ").append(pinMark(c.number)).append(" \"").append(c.comment.trim().replace("\n", " ")).append('"')
        ids?.get(c.number)?.let { append(" · id ").append(it) }
        append('\n')
        append("- element: `").append(c.selector).append('`')
        // The text when it helps tell the element apart: not when empty or already in the selector.
        if (c.text.isNotBlank() && !c.selector.contains(c.text, ignoreCase = true)) append(" · text \"").append(c.text).append('"')
        append('\n')
        c.picture?.let { append("- picture: ").append(it).append('\n') }
    }
    // Stored (phase 4): the agent answers each through its mux tools, and the pins follow.
    if (!ids.isNullOrEmpty()) {
        append("\nAnswer each with your mux tools: resolve_browser_comment (done, with what you changed), reply_browser_comment ")
        append("or dismiss_browser_comment, by its id.")
    }
}.trimEnd()

/**
 * The part of [page] around [box] (viewport fractions), [margin] px each side, with the element outlined and its pin
 * as a dot on its corner: the picture a comment carries.
 */
internal fun cropWithPin(page: ImageBitmap, box: PageBox, accent: Color, margin: Int = 24): ImageBitmap {
    val left = (box.x * page.width).roundToInt()
    val top = (box.y * page.height).roundToInt()
    val right = ((box.x + box.width) * page.width).roundToInt()
    val bottom = ((box.y + box.height) * page.height).roundToInt()
    val x0 = max(0, left - margin)
    val y0 = max(0, top - margin)
    val x1 = min(page.width, max(right + margin, x0 + 1))
    val y1 = min(page.height, max(bottom + margin, y0 + 1))
    val out = ImageBitmap(x1 - x0, y1 - y0)
    val canvas = Canvas(out)
    canvas.drawImageRect(page, IntOffset(x0, y0), IntSize(x1 - x0, y1 - y0), IntOffset.Zero, IntSize(x1 - x0, y1 - y0), Paint())
    val stroke = Paint().apply { color = accent; style = PaintingStyle.Stroke; strokeWidth = 3f }
    canvas.drawRect(left - x0.toFloat(), top - y0.toFloat(), right - x0.toFloat(), bottom - y0.toFloat(), stroke)
    canvas.drawCircle(Offset((left - x0).toFloat(), (top - y0).toFloat()), 9f, Paint().apply { color = accent })
    return out
}

