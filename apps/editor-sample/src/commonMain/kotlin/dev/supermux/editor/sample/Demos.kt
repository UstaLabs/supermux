package dev.supermux.editor.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.editor.compose.Editor
import dev.supermux.editor.compose.EditorTheme
import dev.supermux.editor.compose.GutterClickHandler
import dev.supermux.editor.compose.WidgetClickHandler
import dev.supermux.editor.compose.WidgetRegistry
import dev.supermux.editor.compose.gutterClickFacet
import dev.supermux.editor.compose.widgetClickFacet
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.FacetDep
import dev.supermux.editor.core.GutterMarker
import dev.supermux.editor.core.Panel
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.Rope
import dev.supermux.editor.core.StateEffectType
import dev.supermux.editor.core.StateField
import dev.supermux.editor.core.Transaction
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.WidgetKey
import dev.supermux.editor.core.decorationsFacet
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.gutterMarkersFacet
import dev.supermux.editor.core.panelsFacet

// ------------------------------------------------------------------ the M3c demo --
//
// What M4's later plugins will do, faked just enough to try M3c's surface by hand: lint and diff
// markers (a click says which line), a review thread under a line with a reply field (the 💬 marker
// opens and closes it), an inline type hint, and a find panel. Folding is the real fold plugin now
// (M4a), as in every sample file.

/** The demo's own memory: its markers, the thread and the panel. */
private class DemoValue(
    val markers: RangeSet<GutterMarker>,
    val threadAt: Int,
    val threadOpen: Boolean,
    val hintAt: Int,
)

/** Device checks: what the demo's review-thread reply field holds, and where it is (root pixels). */
object DemoProbe {
    var draft: String = ""
    var replyCenter: androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset.Unspecified
    var find: String = ""
    var findCenter: androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset.Unspecified
}

object M3cDemo {
    private val toggleThread = StateEffectType<Unit>("demo.thread")

    /** The find panel: in or out of the configuration. */
    private val panelSlot = dev.supermux.editor.core.Compartment("demo.panel")

    /** Show or hide the demo's find panel. */
    fun setPanel(view: dev.supermux.editor.core.CommandTarget, on: Boolean) =
        view.dispatch(TransactionSpec(effects = listOf(panelSlot.reconfigure(if (on) panelsFacet.of(Panel("find", top = true)) else extensionOf()))))

    /** The line the review thread hangs under (0-based), clamped to the document. */
    private const val THREAD_LINE = 12

    private val field: StateField<DemoValue> = StateField(
        "demo",
        { st -> initial(st) },
        { v, tr -> update(v, tr) },
        { f ->
            extensionOf(
                gutterMarkersFacet.compute(FacetDep.field(f)) { it.field(f).markers },
                decorationsFacet.compute(FacetDep.field(f)) { st ->
                    val v = st.field(f)
                    val extra = ArrayList<Ranged<Decoration>>()
                    if (v.threadOpen) extra += Ranged(v.threadAt, v.threadAt, Decoration.BlockWidget(WidgetKey("thread", "t1"), estimatedHeightLines = 5f))
                    extra += Ranged(v.hintAt, v.hintAt, Decoration.InlineWidget(WidgetKey("hint", "h1"), side = 1))
                    RangeSet.of(extra)
                },
            )
        },
    )

    private fun initial(st: EditorState): DemoValue {
        val doc = st.doc
        fun at(line: Int) = doc.lineStart(line.coerceIn(0, doc.lineCount - 1))
        val m = ArrayList<Ranged<GutterMarker>>()
        m += Ranged(at(4), at(4), GutterMarker("lint", "lint-error", "error: unresolved reference (demo)"))
        m += Ranged(at(9), at(9), GutterMarker("lint", "lint-warning", "warning: unused variable (demo)"))
        for (l in 20..24) m += Ranged(at(l), at(l), GutterMarker("diff", "diff-add", "added line (demo)"))
        m += Ranged(at(27), at(27), GutterMarker("diff", "diff-remove", "removed lines (demo)"))
        m += Ranged(at(30), at(30), GutterMarker("diff", "diff-change", "changed line (demo)"))
        val thread = (THREAD_LINE).coerceAtMost(doc.lineCount - 1)
        m += Ranged(at(thread), at(thread), GutterMarker("comment", "comment", "review thread: 1 comment"))
        // The hint after the first "val x" found (else the end of line 6).
        val text = doc.slice(0, minOf(doc.length, 20_000))
        val v = Regex("\\bval [A-Za-z_][A-Za-z0-9_]*").find(text)
        val hint = v?.range?.last?.plus(1) ?: (at(7) - 1).coerceAtLeast(0)
        return DemoValue(RangeSet.of(m), at(thread), threadOpen = true, hintAt = hint)
    }

    private fun update(v: DemoValue, tr: Transaction): DemoValue {
        val markers = v.markers.map(tr.changes)
        var threadAt = tr.changes.mapPos(v.threadAt, -1)
        var open = v.threadOpen
        val doc = tr.state.doc
        for (e in tr.effects) {
            e.valueIf(toggleThread)?.let { open = !open }
        }
        if (threadAt > doc.length) threadAt = doc.length
        return DemoValue(markers, doc.lineStart(doc.lineIndexAt(threadAt)), open, tr.changes.mapPos(v.hintAt, 1))
    }

    /** The demo: its state field, and its say over gutter clicks (the 💬, the lint and diff markers). */
    fun extension(onGutter: (String) -> Unit = {}): Extension = extensionOf(
        field,
        panelSlot.of(extensionOf()),
        gutterClickFacet.of(GutterClickHandler { t, column, line, marker ->
            when (column) {
                "comment" -> { if (marker != null) t.dispatch(TransactionSpec(effects = listOf(toggleThread.of(Unit)))); true }
                "fold" -> false // the fold plugin's
                else -> { onGutter("gutter click: $column, line ${line + 1}${marker?.tooltip?.let { " — $it" } ?: ""}"); true }
            }
        }),
    )

    fun panelShown(state: EditorState): Boolean = state.facet(panelsFacet).any { it.id == "find" }

    /** The demo's widget content: the review thread, the type hint, the find panel. */
    @Composable
    fun rememberWidgets(ink: Color, chrome: Color): WidgetRegistry = remember(ink, chrome) {
        WidgetRegistry().apply {
            register("thread") { _ ->
                val comments = remember { mutableStateListOf("Ahmet: does this still hold after a reload from disk?") }
                val draft = rememberTextFieldState()
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(6.dp))
                        .background(chrome).border(1.dp, ink.copy(alpha = 0.25f), RoundedCornerShape(6.dp)).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BasicText("💬 review thread (a block widget)", style = TextStyle(color = ink.copy(alpha = 0.7f), fontSize = 11.sp))
                    for (c in comments) BasicText(c, style = TextStyle(color = ink, fontSize = 13.sp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.runtime.LaunchedEffect(draft) { androidx.compose.runtime.snapshotFlow { draft.text.toString() }.collect { DemoProbe.draft = it } }
                        BasicTextField(
                            draft,
                            Modifier.weight(1f).background(ink.copy(alpha = 0.08f)).padding(6.dp).testTag("thread-reply")
                                .semantics { contentDescription = "reply field" }
                                .onGloballyPositioned { DemoProbe.replyCenter = it.boundsInRoot().center }
                                .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { focusEditor(); true } else false },
                            textStyle = TextStyle(color = ink, fontSize = 13.sp),
                        )
                        BasicText(
                            "Reply",
                            Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF4BBAA7).copy(alpha = 0.35f)).clickable {
                                val t = draft.text.toString().trim()
                                if (t.isNotEmpty()) { comments += "you: $t"; draft.clearText() }
                            }.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = TextStyle(color = ink, fontSize = 13.sp),
                        )
                    }
                }
            }
            register("hint") {
                BasicText(
                    ": Hint",
                    Modifier.clip(RoundedCornerShape(3.dp)).background(ink.copy(alpha = 0.12f)).padding(horizontal = 3.dp),
                    style = TextStyle(color = ink.copy(alpha = 0.6f), fontSize = 11.sp),
                )
            }
            register("panel:find") {
                val query = rememberTextFieldState()
                Row(
                    Modifier.fillMaxWidth().background(chrome).padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BasicText("find:", style = TextStyle(color = ink, fontSize = 13.sp))
                    androidx.compose.runtime.LaunchedEffect(query) { androidx.compose.runtime.snapshotFlow { query.text.toString() }.collect { DemoProbe.find = it } }
                    BasicTextField(query, Modifier.weight(1f).background(ink.copy(alpha = 0.08f)).padding(6.dp).testTag("find-field")
                        .onGloballyPositioned { DemoProbe.findCenter = it.boundsInRoot().center }, textStyle = TextStyle(color = ink, fontSize = 13.sp))
                    val n = if (query.text.isEmpty()) 0 else Regex(Regex.escape(query.text.toString())).findAll(editor.state.doc.slice(0, minOf(editor.state.doc.length, 500_000))).count()
                    BasicText("$n found · Esc: back to the editor", style = TextStyle(color = ink.copy(alpha = 0.7f), fontSize = 11.sp))
                    BasicText("✕", Modifier.clickable { setPanel(editor, false); focusEditor() }.padding(6.dp), style = TextStyle(color = ink, fontSize = 13.sp))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ the diff demos' texts --

/**
 * B: the sample file with a few fake edits (a line edited in place, a deleted run, an inserted
 * run, a changed run of another length), so the diff demos open with every kind of hunk on screen.
 */
fun fakeWorkingCopy(text: String): String {
    val lines = text.split('\n').toMutableList()
    if (lines.size < 200) return text + "\n// an added line\n"
    // Edited in place: the character diff marks just what changed.
    lines[25] = lines[25].replace("val ", "var ").let { if (it == lines[25]) "$it // edited" else it }
    lines.subList(150, 152).let { it.clear(); it.addAll(listOf("    // changed: one run of two lines", "    // became four lines", "    // in the working copy", "    // (side-by-side demo)")) }
    lines.addAll(101, listOf("    // inserted in the working copy (1/3)", "    // inserted in the working copy (2/3)", "    // inserted in the working copy (3/3)"))
    lines.subList(40, 45).clear()
    return lines.joinToString("\n")
}
