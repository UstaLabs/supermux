package dev.supermux.terminal.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString

/**
 * Publishes the screen as text for accessibility and for UI tests.
 *
 * It is a modifier NODE rather than `Modifier.semantics { … }` on purpose: the frame lives in
 * snapshot state that the draw pass reads, and a plain semantics lambda would keep serving the
 * screen from whenever the composable last recomposed. This observes the same state and invalidates
 * only the semantics when it changes — the terminal still repaints without a single recomposition.
 *
 * This is the MINIMUM that makes the surface readable and testable (one text property, the visible
 * grid, continuation cells contributing nothing). Selection, the cursor position, a live region for
 * new output and the IME contract are Plan 2 Task 5 and replace this file's contents, not its seam.
 */
internal fun Modifier.terminalSemantics(model: ViewportModel): Modifier =
    this then TerminalSemanticsElement(model)

private data class TerminalSemanticsElement(
    val model: ViewportModel,
) : ModifierNodeElement<TerminalSemanticsNode>() {
    override fun create(): TerminalSemanticsNode = TerminalSemanticsNode(model)

    override fun update(node: TerminalSemanticsNode) {
        node.bind(model)
    }
}

private class TerminalSemanticsNode(private var model: ViewportModel) :
    Modifier.Node(), SemanticsModifierNode, ObserverModifierNode {

    private var screen: String = ""

    override fun onAttach() {
        refresh()
    }

    fun bind(model: ViewportModel) {
        this.model = model
        if (isAttached) refresh()
    }

    override fun onObservedReadsChanged() {
        refresh()
    }

    private fun refresh() {
        var next = ""
        observeReads { next = model.frame?.plainText().orEmpty() }
        if (next != screen) {
            screen = next
            invalidateSemantics()
        }
    }

    override fun SemanticsPropertyReceiver.applySemantics() {
        text = AnnotatedString(screen)
    }
}
