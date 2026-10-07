package dev.supermux.editor.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * What a finger has to hold on to: the touch handles. Mouse users never see them (only a touch
 * gesture turns them on).
 *
 * - [SELECTION]: two teardrops at the main range's ends, after a long press (or the menu's Select
 *   All). Dragging one moves that end; the other stays put.
 * - [CURSOR]: one drop under the caret, after a tap, for moving the caret.
 * - [NONE]: typing, a mouse click, a hardware key or any other selection change hides them.
 */
internal enum class TouchHandles { NONE, CURSOR, SELECTION }

/** Which handle: the selection's start, its end, or the lone caret's. */
internal enum class HandleKind { START, END, CURSOR }

/**
 * One handle where it is drawn: [tip] is the caret's bottom at [offset] (surface pixels), where the
 * drop points; [body] is the drawn circle's centre, [touch] the finger's target (at least
 * [EditorTouch.MIN_TOUCH_DP] square, centred on the body).
 */
internal data class HandleSpot(val kind: HandleKind, val offset: Int, val tip: Offset, val body: Offset, val touch: Rect)

internal object EditorTouch {
    /** The drawn drop's radius. */
    const val RADIUS_DP = 11f

    /** A handle's touch target, per side: Material's 48 dp (above iOS's 44 pt). */
    const val MIN_TOUCH_DP = 48f

    /**
     * The spots for [kind]'s handle at the caret rect [caret] (surface pixels): START hangs below
     * and left of its tip, END below and right, CURSOR centred below.
     */
    fun spot(kind: HandleKind, offset: Int, caret: Rect, density: Float): HandleSpot {
        val r = RADIUS_DP * density
        val tip = Offset(caret.left, caret.bottom)
        val body = when (kind) {
            HandleKind.START -> Offset(tip.x - r, tip.y + r)
            HandleKind.END -> Offset(tip.x + r, tip.y + r)
            HandleKind.CURSOR -> Offset(tip.x, tip.y + r * 1.414f)
        }
        val half = maxOf(MIN_TOUCH_DP * density / 2, r * 1.5f)
        return HandleSpot(kind, offset, tip, body, Rect(body.x - half, body.y - half, body.x + half, body.y + half))
    }

    /** The handle whose touch target holds [p], the nearest body first; null when none does. */
    fun hit(spots: List<HandleSpot>, p: Offset): HandleSpot? =
        spots.filter { it.touch.contains(p) }.minByOrNull { (it.body - p).getDistanceSquared() }
}

/**
 * Draw [spots] as teardrops in [color]: a circle whose corner at the tip is squared off, so the
 * point sits exactly on the caret's bottom (Android's and iOS's handles look the same way).
 */
internal fun DrawScope.drawHandles(spots: List<HandleSpot>, color: Color, density: Float) {
    val r = EditorTouch.RADIUS_DP * density
    for (s in spots) {
        drawCircle(color, radius = r, center = s.body)
        when (s.kind) {
            HandleKind.START -> drawRect(color, Offset(s.tip.x - r, s.tip.y), Size(r, r))
            HandleKind.END -> drawRect(color, s.tip, Size(r, r))
            HandleKind.CURSOR -> {
                // A diamond from the tip down to the circle: a drop pointing up.
                val p = Path().apply {
                    moveTo(s.tip.x, s.tip.y)
                    lineTo(s.body.x + r * 0.707f, s.body.y - r * 0.707f)
                    lineTo(s.body.x, s.body.y)
                    lineTo(s.body.x - r * 0.707f, s.body.y - r * 0.707f)
                    close()
                }
                drawPath(p, color)
            }
        }
    }
}
