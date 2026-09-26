package dev.supermux.editor.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.invalidateSemantics
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.copyText
import androidx.compose.ui.semantics.cutText
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.insertTextAtCursor
import androidx.compose.ui.semantics.isEditable
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.pasteText
import androidx.compose.ui.semantics.requestFocus
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setSelection
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.semantics.textSelectionRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.Rope
import dev.supermux.editor.core.TransactionSpec

/** The labels a screen-reader user hears; part of the surface's contract. */
object EditorSemantics {
    /** The editor's name when the host gives none (`Editor(label = …)`). */
    const val LABEL = "Code editor"

    const val COPY = "Copy"
    const val CUT = "Cut"
    const val PASTE = "Paste"
    const val FOCUS = "Edit"

    /** What a caret move to another line says: "line 12: fun main() {". */
    fun lineAnnouncement(lineNumber: Int, text: String): String = "line $lineNumber: $text"
}

/**
 * The text a screen reader is given: the VISIBLE lines plus the caret's line, never the whole
 * document (a 10 MB file would be copied into the platform's accessibility tree on every change).
 * Each line is a segment of the document (a line longer than [MAX_LINE] units: the part around the
 * caret, or its start); segments are joined with "\n", which is the document's own line break
 * between neighbouring whole lines. Offsets map both ways ([toText], [toDoc]).
 */
internal class AccessibleText private constructor(val text: String, private val docFrom: IntArray, private val docTo: IntArray, private val textFrom: IntArray) {
    val segments: Int get() = docFrom.size

    /** [offset] in the document as an offset in [text], clamped into the nearest segment. */
    fun toText(offset: Int): Int {
        if (segments == 0) return 0
        var i = 0
        while (i < segments - 1 && offset > docTo[i] && offset >= docFrom[i + 1]) i++
        if (i < segments - 1 && offset > docTo[i]) return textFrom[i] + (docTo[i] - docFrom[i]) // between segments
        return textFrom[i] + (offset.coerceIn(docFrom[i], docTo[i]) - docFrom[i])
    }

    /** [offset] in [text] as a document offset (the "\n" between two segments: the first one's end). */
    fun toDoc(offset: Int): Int {
        if (segments == 0) return 0
        var i = segments - 1
        while (i > 0 && offset < textFrom[i]) i--
        return docFrom[i] + (offset - textFrom[i]).coerceIn(0, docTo[i] - docFrom[i])
    }

    companion object {
        /** A longer line is exposed in part. */
        const val MAX_LINE = 2_000

        fun build(doc: Rope, visible: IntRange, caret: Int): AccessibleText {
            val caretLine = doc.lineIndexAt(caret.coerceIn(0, doc.length))
            val lines = ArrayList<Int>()
            if (!visible.isEmpty()) for (l in visible.first.coerceAtLeast(0)..visible.last.coerceAtMost(doc.lineCount - 1)) lines += l
            if (caretLine !in lines) {
                val at = lines.indexOfFirst { it > caretLine }.let { if (it < 0) lines.size else it }
                lines.add(at, caretLine)
            }
            val n = lines.size
            val df = IntArray(n)
            val dt = IntArray(n)
            val tf = IntArray(n)
            val sb = StringBuilder()
            for ((i, l) in lines.withIndex()) {
                val from = doc.lineStart(l)
                val to = if (l + 1 < doc.lineCount) doc.lineStart(l + 1) - 1 else doc.length
                var a = from
                var b = to
                if (b - a > MAX_LINE) {
                    val around = if (l == caretLine) caret else from
                    a = (around - MAX_LINE / 2).coerceIn(from, to - MAX_LINE)
                    b = a + MAX_LINE
                    a = TextBoundaries.snap(doc, a)
                    b = TextBoundaries.snap(doc, b)
                }
                if (i > 0) sb.append('\n')
                df[i] = a; dt[i] = b; tf[i] = sb.length
                sb.append(doc.slice(a, b))
            }
            return AccessibleText(sb.toString(), df, dt, tf)
        }
    }
}

/**
 * The editor as a screen reader sees it: ONE editable text node (the surface) carrying
 * [AccessibleText], the main selection in it, the host's [label] as its description, focus, and
 * actions: set the selection (move by character / word, select), type at the caret, copy, cut,
 * paste, click (focus and raise the keyboard). The hidden input field is hidden from accessibility
 * (it holds only a window of text); the gutter's numbers are drawn, never exposed.
 *
 * A node, not `Modifier.semantics { }`: the surface repaints without recomposing, so a semantics
 * lambda would serve stale text. This one observes the state, the scroll and the focus, and
 * invalidates only when what it exposes changed (the visible lines, the text, the selection).
 */
internal fun Modifier.editorSemantics(c: EditorController, label: String, readOnly: Boolean): Modifier =
    this then EditorSemanticsElement(c, label, readOnly)

private data class EditorSemanticsElement(val c: EditorController, val label: String, val readOnly: Boolean) : ModifierNodeElement<EditorSemanticsNode>() {
    override fun create() = EditorSemanticsNode(this)
    override fun update(node: EditorSemanticsNode) = node.bind(this)
}

private class EditorSemanticsNode(private var e: EditorSemanticsElement) : Modifier.Node(), SemanticsModifierNode, ObserverModifierNode, LayoutAwareModifierNode {
    private var key: Any? = null
    private var exposed: AccessibleText? = null
    private var height = 0f

    override fun onRemeasured(size: IntSize) {
        if (size.height.toFloat() != height) {
            height = size.height.toFloat()
            if (isAttached) refresh()
        }
    }

    override fun onAttach() = refresh()

    fun bind(e: EditorSemanticsElement) {
        this.e = e
        if (isAttached) { key = null; refresh() }
    }

    override fun onObservedReadsChanged() = refresh()

    /** The visible lines (no overscan) at the current scroll (a snapshot read: observed). */
    private fun visible(): IntRange {
        val c = e.c
        val y = c.scroll.y
        return if (height <= 0f) IntRange.EMPTY else c.geometry.visibleLines(y, height, 0)
    }

    /** What the node exposes depends on; the document by identity (comparing ropes copies them). */
    private class Key(val doc: Rope, val selection: EditorSelection, val visible: IntRange, val focused: Boolean) {
        override fun equals(other: Any?) = other is Key && other.doc === doc && other.selection == selection && other.visible == visible && other.focused == focused
        override fun hashCode() = visible.hashCode()
    }

    private fun refresh() {
        var k: Any? = null
        observeReads {
            val c = e.c
            val st = c.view.state
            k = Key(st.doc, st.selection, visible(), c.view.focused)
        }
        if (k != key) {
            key = k
            exposed = null
            invalidateSemantics()
        }
    }

    private fun text(): AccessibleText {
        exposed?.let { return it }
        val st = e.c.view.state
        return AccessibleText.build(st.doc, visible(), st.selection.main.head).also { exposed = it }
    }

    override fun SemanticsPropertyReceiver.applySemantics() {
        val c = e.c
        val view = c.view
        val t = text()
        val main = view.state.selection.main
        contentDescription = e.label
        editableText = AnnotatedString(t.text)
        textSelectionRange = TextRange(t.toText(main.anchor), t.toText(main.head))
        isEditable = !e.readOnly
        focused = view.focused
        requestFocus(EditorSemantics.FOCUS) { c.requestFocus() }
        onClick(EditorSemantics.FOCUS) { c.focusFromTouch(); true }
        setSelection { start, end, _ ->
            val tx = text()
            val a = tx.toDoc(start.coerceIn(0, tx.text.length))
            val b = tx.toDoc(end.coerceIn(0, tx.text.length))
            view.dispatch(TransactionSpec(selection = EditorSelection.single(a, b), scrollIntoView = true, userEvent = "select"))
            true
        }
        copyText(EditorSemantics.COPY) { DefaultCommands.copy.run(view) }
        if (!e.readOnly) {
            // A text field for the platforms (macOS maps a node with SetText to an AXTextArea whose
            // value is its text; without it VoiceOver got a static text holding only the label).
            // Setting the text replaces what changed in the exposed lines, mapped to the document.
            setText { replacement ->
                val tx = text()
                val edit = diffField(tx.text, replacement.text) ?: return@setText true
                val from = tx.toDoc(edit.from)
                val to = tx.toDoc(edit.to)
                view.dispatch(TransactionSpec(changes = listOf(dev.supermux.editor.core.ChangeSpec(from, maxOf(from, to), edit.insert)), scrollIntoView = true, userEvent = "input"))
                true
            }
            insertTextAtCursor { typed -> view.typeText(typed.text) }
            cutText(EditorSemantics.CUT) { DefaultCommands.cut.run(view) }
            pasteText(EditorSemantics.PASTE) { DefaultCommands.paste.run(view) }
        } else {
            disabled()
        }
    }
}

/**
 * The line the caret moved to, said once (a polite live region): after a move to ANOTHER line
 * that edited nothing (arrows, a page move, a tap), never while typing.
 */
internal class LineAnnouncer {
    var text: String by mutableStateOf("")
        private set
    private var lastLine = -1

    fun follow(tr: dev.supermux.editor.core.Transaction) {
        if (lastLine < 0) lastLine = tr.startState.doc.lineIndexAt(tr.startState.selection.main.head)
        val st = tr.state
        val line = st.doc.lineIndexAt(st.selection.main.head)
        if (line == lastLine) return
        val moved = lastLine >= 0 && !tr.docChanged && tr.selectionSet
        lastLine = line
        if (!moved) return
        val from = st.doc.lineStart(line)
        val to = if (line + 1 < st.doc.lineCount) st.doc.lineStart(line + 1) - 1 else st.doc.length
        text = EditorSemantics.lineAnnouncement(line + 1, st.doc.slice(from, minOf(to, from + 200)))
    }
}

/** The announcer's node: a one-pixel polite live region. */
@Composable
internal fun LineAnnouncement(announcer: LineAnnouncer) {
    val said = announcer.text
    Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; if (said.isNotEmpty()) contentDescription = said })
}
