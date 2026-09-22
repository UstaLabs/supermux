package dev.supermux.terminal.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.copyText
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.pasteText
import androidx.compose.ui.semantics.requestFocus
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.textSelectionRange
import androidx.compose.ui.semantics.verticalScrollAxisRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange

/**
 * Everything a terminal has to be for a screen reader, a keyboard and a UI test.
 *
 * **What it publishes.**
 * - a **name** ([TerminalSemantics.LABEL], or the host's) as the pane title, so TalkBack and
 *   VoiceOver say what this thing is without masking its contents the way a content description
 *   would;
 * - the **visible viewport** as text — bounded on purpose: the rows on screen, never the scrollback.
 *   A terminal's history is unbounded and a screen reader that is handed all of it is unusable;
 * - the **selection** as a range into that text, so "read selection" and the platform's own
 *   copy affordance have something to work with;
 * - **focus**, and a request-focus action, so keyboard traversal can reach the terminal and hand it
 *   the keyboard — this is also what a UI test's `requestFocus()` uses;
 * - **actions**: copy, paste and scroll, with labels that do not change ([TerminalSemantics]), so a
 *   user's learned gesture keeps doing the same thing between releases.
 *
 * **What it deliberately does NOT publish.** No live region. A terminal is a firehose — a build log,
 * a `tail -f`, a progress bar redrawing at 60 Hz — and announcing every change would make the
 * screen reader read a build instead of the user's own typing. The text is there to be read on
 * demand; the surface never interrupts.
 *
 * **Why a node and not `Modifier.semantics { }`.** The frame lives in snapshot state that the DRAW
 * pass reads. A plain semantics lambda would serve whatever the composable last recomposed with —
 * and this surface deliberately repaints without recomposing. This observes the same state and
 * invalidates only the semantics when the text or the selection actually changes.
 */
internal fun Modifier.terminalSemantics(
    model: ViewportModel,
    scroll: ScrollController,
    label: String,
    enabled: Boolean,
    focused: () -> Boolean,
    onRequestFocus: () -> Boolean,
    onCopy: () -> Boolean,
    onPaste: () -> Boolean,
): Modifier = this then TerminalSemanticsElement(
    model = model,
    scroll = scroll,
    label = label,
    enabled = enabled,
    focused = focused,
    onRequestFocus = onRequestFocus,
    onCopy = onCopy,
    onPaste = onPaste,
)

/** Action labels a screen-reader user learns; they are part of the surface's contract. */
object TerminalSemantics {
    /** The terminal's name when the host does not give it one. */
    const val LABEL: String = "Terminal"

    const val COPY: String = "Copy selection"
    const val PASTE: String = "Paste"
    const val SCROLL: String = "Scroll terminal history"
    const val FOCUS: String = "Focus terminal"
}

private data class TerminalSemanticsElement(
    val model: ViewportModel,
    val scroll: ScrollController,
    val label: String,
    val enabled: Boolean,
    val focused: () -> Boolean,
    val onRequestFocus: () -> Boolean,
    val onCopy: () -> Boolean,
    val onPaste: () -> Boolean,
) : ModifierNodeElement<TerminalSemanticsNode>() {
    override fun create(): TerminalSemanticsNode = TerminalSemanticsNode(this)

    override fun update(node: TerminalSemanticsNode) {
        node.bind(this)
    }
}

private class TerminalSemanticsNode(private var element: TerminalSemanticsElement) :
    Modifier.Node(), SemanticsModifierNode, ObserverModifierNode {

    private var screen: String = ""
    private var selection: TextRange = TextRange.Zero
    private var hasFocus: Boolean = false

    override fun onAttach() {
        refresh()
    }

    fun bind(element: TerminalSemanticsElement) {
        this.element = element
        if (isAttached) refresh()
    }

    override fun onObservedReadsChanged() {
        refresh()
    }

    private fun refresh() {
        var text = ""
        var range = TextRange.Zero
        var focus = false
        observeReads {
            val frame = element.model.frame
            text = frame?.plainText().orEmpty()
            range = frame?.let { selectionRangeOf(it) } ?: TextRange.Zero
            focus = element.focused()
        }
        if (text != screen || range != selection || focus != hasFocus) {
            screen = text
            selection = range
            hasFocus = focus
            invalidateSemantics()
        }
    }

    override fun SemanticsPropertyReceiver.applySemantics() {
        paneTitle = element.label
        text = AnnotatedString(screen)
        textSelectionRange = selection
        focused = hasFocus
        if (!element.enabled) disabled()
        requestFocus(TerminalSemantics.FOCUS) { element.onRequestFocus() }
        copyText(TerminalSemantics.COPY) { element.onCopy() }
        pasteText(TerminalSemantics.PASTE) { element.onPaste() }
        // A screen reader (and a keyboard-only user) can walk the history with no pointer at all.
        val controller = element.scroll
        verticalScrollAxisRange = ScrollAxisRange(
            value = { controller.position.row.toFloat() },
            maxValue = { controller.newestTop.toFloat() },
            reverseScrolling = false,
        )
        scrollBy(TerminalSemantics.SCROLL) { _, y ->
            controller.consumePx(y) != 0f
        }
    }
}

/**
 * The frame's selection as a range into [TerminalFrame.plainText], or [TextRange.Zero].
 *
 * The screen string is the visible rows joined by newlines, each one trimmed of its trailing blanks
 * — so a column is NOT an offset, and the mapping has to be done against the rows that were
 * actually emitted. Everything outside the viewport is clipped away: the selection may run far into
 * the scrollback, but this text does not.
 */
internal fun selectionRangeOf(frame: TerminalFrame): TextRange {
    val selection = frame.selection ?: return TextRange.Zero
    val ordered = selection.ordered()
    var offset = 0
    var start = -1
    var end = -1
    for (row in 0 until frame.size.rows) {
        val line = frame.rowText(row)
        val absolute = frame.viewportTop + row
        if (absolute in ordered.start.row..ordered.end.row) {
            val first = if (absolute == ordered.start.row) ordered.start.column.coerceIn(0, line.length) else 0
            // The end column is inclusive in the terminal's coordinates and exclusive here.
            val last = if (absolute == ordered.end.row) {
                (ordered.end.column + 1).coerceIn(first, line.length)
            } else {
                line.length
            }
            if (start < 0) start = offset + first
            end = offset + last
        }
        offset += line.length + 1 // the newline joined onto every row but conceptually the last
    }
    return if (start < 0 || end < start) TextRange.Zero else TextRange(start, end)
}
