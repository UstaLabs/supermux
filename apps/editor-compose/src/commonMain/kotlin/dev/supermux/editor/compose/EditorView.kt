package dev.supermux.editor.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.supermux.editor.core.CommandTarget
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Transaction
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.ChangeSet
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.SelectionRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One open editor: its current [EditorState], the way to change it ([dispatch]), and what the
 * surface reports back (the [viewport], [focused]).
 *
 * The host owns it (usually through [rememberEditorView]) and shows it with `Editor(view)`.
 * Plugins and commands only ever see it as a [CommandTarget]: a state plus `dispatch(spec)`.
 *
 * **Threading.** [dispatch] runs on the UI thread. A background producer (the syntax worker) hops
 * its dispatches onto the UI thread, in order.
 *
 * **Listeners** run synchronously inside [dispatch], after the state is replaced, for every
 * transaction: that is where the host hands `tr.state` to a syntax worker.
 */
@Stable
class EditorView(initial: EditorState) : CommandTarget {
    private var current: EditorState by mutableStateOf(initial)
    private var listeners: List<(Transaction) -> Unit> = emptyList()
    private val viewportFlow = MutableStateFlow(IntRange.EMPTY)

    override val state: EditorState get() = current

    /**
     * UTF-16 `[start, end)` of the lines the surface lays out (the visible ones plus overscan), as
     * `start until end`; empty before the first paint. Updated at most once per frame.
     */
    val viewport: StateFlow<IntRange> = viewportFlow.asStateFlow()

    /** True while the surface holds the keyboard focus (mirrors Compose focus). */
    var focused: Boolean by mutableStateOf(false)

    /**
     * No user edits: typing, deleting and pasting (`input*`, `delete*`, `paste*`, `undo`, `redo`)
     * are dropped. Programmatic changes (a disk reload, LSP, plugins' effects) still apply, and the
     * selection still moves. Set by the surface from `Editor(readOnly = ...)`.
     */
    var readOnly: Boolean = false

    /** Where copy and cut put text and paste takes it from; set by the surface (`Editor(clipboard = …)`). */
    internal var clipboard: EditorClipboard? = null

    /** A scope on the UI thread for work a command cannot finish at once (reading the clipboard). */
    internal var scope: CoroutineScope? = null

    /**
     * Paste [text] (userEvent `paste`): with as many cursors as [text] has lines, one line at each
     * cursor (CM6's behaviour for a multi-cursor copy); otherwise all of [text] at every cursor.
     */
    fun paste(text: String) {
        if (text.isEmpty()) return
        val st = state
        val ranges = st.selection.ranges
        val lines = text.split('\n')
        val specs = ranges.mapIndexed { i, r -> ChangeSpec(r.from, r.to, if (ranges.size > 1 && lines.size == ranges.size) lines[i] else text) }
        val changes = ChangeSet.of(st.doc.length, specs)
        val next = ranges.map { SelectionRange(changes.mapPos(it.to, 1)) }
        dispatch(TransactionSpec(changeSet = changes, selection = EditorSelection.create(next, st.selection.mainIndex), scrollIntoView = true, userEvent = "paste"))
    }

    /** The surface's geometry once it is composed; vertical moves and page moves need it. */
    internal var geometry: Geometry? = null

    /** The composed surface's hooks (height map, scrolling), null while nothing shows this view. */
    internal var surface: EditorSurfaceHooks? = null

    /** The goal x of each range for consecutive vertical moves, valid while the selection is [Goal.selection]. */
    internal var goal: Goal? = null

    internal class Goal(val selection: EditorSelection, val xs: List<Float>)

    override fun dispatch(spec: TransactionSpec) {
        if (readOnly && isUserEdit(spec)) return
        val tr = current.update(spec)
        current = tr.state
        surface?.onTransaction(tr)
        for (l in listeners) l(tr)
        if (tr.scrollIntoView) surface?.scrollIntoView()
    }

    /** Replace the whole state (another file, a reload that is not an edit). Listeners are not called. */
    fun setState(state: EditorState) {
        current = state
        goal = null
        surface?.onStateReplaced()
    }

    /** Call [l] after every transaction; returns the function that removes it. */
    fun addListener(l: (Transaction) -> Unit): () -> Unit {
        listeners = listeners + l
        return { listeners = listeners - l }
    }

    internal fun publishViewport(range: IntRange) {
        viewportFlow.value = range
    }

    private fun isUserEdit(spec: TransactionSpec): Boolean {
        val changes = spec.changes.isNotEmpty() || spec.changeSet?.isEmpty == false
        val e = spec.userEvent ?: return false
        return changes && USER_EDITS.any { e == it || e.startsWith("$it.") }
    }

    private companion object {
        val USER_EDITS = listOf("input", "delete", "paste", "undo", "redo", "drop")
    }
}

/** What the composed surface does for its view; see [EditorView.surface]. */
internal interface EditorSurfaceHooks {
    /** Follow a transaction (the height map, the scroll anchor) before any listener runs. */
    fun onTransaction(tr: Transaction)

    /** The state was replaced wholesale. */
    fun onStateReplaced()

    /** Scroll the least needed to show the main cursor with a margin. */
    fun scrollIntoView()

    /** Scroll by [dy] pixels (page moves scroll a page as well as moving the cursor). */
    fun scrollBy(dy: Float)

    /** The viewport's height in pixels. */
    val viewportHeightPx: Float
}

/** An [EditorView] that lives as long as the composition; [initial] runs once. */
@Composable
fun rememberEditorView(initial: () -> EditorState): EditorView = remember { EditorView(initial()) }
