package dev.supermux.editor.plugins.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.editor.compose.EditorTheme
import dev.supermux.editor.compose.Editor
import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.compose.WidgetRegistry
import dev.supermux.editor.compose.WidgetScope
import dev.supermux.editor.compose.tabSizeFacet
import dev.supermux.editor.core.WidgetKey

/** The diff views' widget content (the one sanctioned widget exception): register it in the `Editor`'s registry. */
fun Diff.registerWidgets(registry: WidgetRegistry) {
    registry.register(COLLAPSED) { key -> CollapsedRow(key) }
    registry.register(DELETED) { key -> DeletedLinesBlock(key) }
}

/** A registry with the diff's widgets. */
@Composable
fun rememberDiffWidgets(): WidgetRegistry = remember { WidgetRegistry().also { Diff.registerWidgets(it) } }

/**
 * The inline diff in an `Editor`: read-only unless the model's [DiffConfig.editable], with the
 * diff's (and the review threads') widgets registered. The view's state carries [inlineDiff].
 */
@Composable
fun InlineDiffEditor(
    view: EditorView,
    modifier: Modifier = Modifier,
    theme: EditorTheme = EditorTheme.default(),
    widgets: WidgetRegistry = rememberDiffWidgets(),
    label: String = "Diff",
    lineWrap: Boolean = false,
    onFontSize: (Float) -> Unit = {},
) {
    val editable = Diff.model(view.state)?.config?.editable ?: true
    Editor(view, modifier, theme = theme, readOnly = !editable, widgets = widgets, label = label, lineWrap = lineWrap, onFontSize = onFontSize)
}

/** A text button that never takes the editor's focus (a tap, not `clickable`: see the README). */
@Composable
internal fun Modifier.press(label: String, onClick: () -> Unit): Modifier {
    val latest by rememberUpdatedState(onClick)
    return this
        .pointerInput(Unit) { detectTapGestures(onTap = { latest() }) }
        .semantics { role = Role.Button; contentDescription = label; onClick(label) { latest(); true } }
}

internal fun accent(theme: EditorTheme): Color = theme.cursor

/** "⋯ N unchanged lines" with ↑ / ↓ / all: a folded run's row (an inline widget: a line tall at most). */
@Composable
private fun WidgetScope.CollapsedRow(key: WidgetKey) {
    val st = editor.state
    val run = Diff.sideModel(st)?.collapsedRun(key.id) ?: return
    val ink = theme.foreground.copy(alpha = 0.7f)
    val small = TextStyle(color = ink, fontSize = (theme.fontSizeSp * 0.85f).sp, fontFamily = theme.fontFamily)
    val step = Diff.sideModel(st)?.config?.expandStep ?: 20
    val n = run.lines
    Row(
        Modifier.height(lineHeight).clip(RoundedCornerShape(4.dp)).background(theme.widgetChipBackground.copy(alpha = 0.35f)).padding(horizontal = 6.dp).testTag("diff-collapsed"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val label = "⋯ $n unchanged ${if (n == 1) "line" else "lines"}"
        BasicText(label, Modifier.press("Show $n unchanged lines") { Diff.expand(editor, run, Expand.ALL) }, style = small)
        val link = small.copy(color = accent(theme))
        if (!run.atStart && n > step) BasicText("↓ $step", Modifier.press("Show $step more lines below") { Diff.expand(editor, run, Expand.DOWN) }.padding(horizontal = 4.dp).testTag("diff-expand-down"), style = link)
        if (!run.atEnd && n > step) BasicText("↑ $step", Modifier.press("Show $step more lines above") { Diff.expand(editor, run, Expand.UP) }.padding(horizontal = 4.dp).testTag("diff-expand-up"), style = link)
        BasicText("all", Modifier.press("Show all $n lines") { Diff.expand(editor, run, Expand.ALL) }.padding(horizontal = 4.dp).testTag("diff-expand-all"), style = link)
    }
}

/** A hunk's deleted lines, read-only and tinted, with the characters that changed marked (inline mode). */
@Composable
private fun WidgetScope.DeletedLinesBlock(key: WidgetKey) {
    val st = editor.state
    val m = Diff.model(st) ?: return
    val aFrom = key.id.removePrefix("d").toIntOrNull() ?: return
    val h = m.hunks.firstOrNull { it.aFrom == aFrom && it.aTo > it.aFrom } ?: return
    val tab = " ".repeat(st.facet(tabSizeFacet).coerceIn(1, 16))
    var all by rememberSaveable(key.id) { mutableStateOf(false) }
    val count = h.aTo - h.aFrom
    val shown = if (all) count else minOf(count, DeletedLines.MAX_SHOWN)
    val bg = theme.lineClassBackgrounds["diff-remove"] ?: Color(0x33F85149)
    val mark = theme.classStyles["diff-remove-text"] ?: SpanStyle(background = Color(0x59F85149))
    val style = TextStyle(color = theme.foreground.copy(alpha = 0.85f), fontFamily = theme.fontFamily, fontSize = theme.fontSizeSp.sp)
    val text = remember(h, shown, tab) { deletedText(m.baseLines, h, shown, tab, mark) }
    // The editor's text starts half a cell right of the gutter (a monospace cell is ~0.6 em).
    val pad = with(LocalDensity.current) { (theme.fontSizeSp * 0.3f).sp.toDp() }
    Column(Modifier.fillMaxWidth().background(bg).testTag("diff-deleted").semantics { contentDescription = "$count deleted ${if (count == 1) "line" else "lines"}" }) {
        // One line per row at the editor's line height, so the old lines read like the new ones.
        for (i in 0 until shown) BasicText(text[i], Modifier.height(lineHeight).padding(start = pad), style = style, softWrap = false, maxLines = 1)
        if (shown < count) BasicText(
            "⋯ show ${count - shown} more deleted lines",
            Modifier.height(lineHeight).padding(start = pad).press("Show all deleted lines") { all = true },
            style = style.copy(color = accent(theme)),
        )
    }
}

/** The base's lines of [h] (the first [shown]), tabs expanded, the removed characters marked. */
private fun deletedText(base: List<String>, h: DiffHunk, shown: Int, tab: String, mark: SpanStyle): List<AnnotatedString> {
    val out = ArrayList<AnnotatedString>(shown)
    var offset = 0 // the line's start in the hunk's text
    for (i in 0 until shown) {
        val line = base[h.aFrom + i]
        val lineEnd = offset + line.length
        out += buildAnnotatedString {
            var col = 0
            // The changed ranges, clipped to this line, in line coordinates (before tab expansion).
            val marks = h.chars?.mapNotNull { c -> val f = maxOf(c.aFrom, offset); val t = minOf(c.aTo, lineEnd); if (t > f) (f - offset) until (t - offset) else null }.orEmpty()
            var k = 0
            for ((j, ch) in line.withIndex()) {
                while (k < marks.size && marks[k].last < j) k++
                val marked = k < marks.size && j in marks[k]
                val s = if (ch == '\t') tab.substring(col % tab.length) else ch.toString()
                if (marked) { pushStyle(mark); append(s); pop() } else append(s)
                col += s.length
            }
        }
        offset = lineEnd + 1
    }
    return out
}
