package dev.supermux.editor.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpOffset
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.TransactionSpec

/**
 * iOS's floating cursor (a long press on the space bar turns the keyboard into a trackpad). Compose
 * would move the caret through the 1 dp hidden field's own layout, so the platform forwards the three
 * calls here instead: the caret moves by the finger's travel through the editor's layout, from where
 * it was at [begin]. Only the travel counts (UIKit's points are in the input view's space). It moves
 * the selection only, never the text.
 */
internal class FloatingCursor(private val c: EditorController) {
    private var anchor: Offset? = null
    private var from = Offset.Zero

    fun begin(point: DpOffset) {
        val head = c.view.state.selection.main.head
        anchor = c.geometry.rectFor(head).center
        from = px(point)
    }

    fun update(point: DpOffset) {
        val a = anchor ?: return
        val at = c.geometry.offsetAt(a + (px(point) - from))
        val sel = c.view.state.selection.main
        if (sel.empty && sel.head == at) return
        c.view.dispatch(TransactionSpec(selection = EditorSelection.cursor(at), userEvent = "select.pointer", scrollIntoView = true))
    }

    fun end() { anchor = null }

    private fun px(p: DpOffset) = Offset(p.x.value * c.densityValue, p.y.value * c.densityValue)
}
