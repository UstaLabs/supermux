// A high-detail tool pane's text on the native editor (read-only): a Bash command and its output,
// a written file, an edit's diff. The same surface as a chat code block (CodeBlockEditor), but
// capped to a few lines with a "Show all" toggle, since a tool's output can run to thousands of lines.
package dev.supermux.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.editor.compose.Editor
import dev.supermux.editor.compose.EditorTheme
import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.GutterMarker
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.decorationsFacet
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.gutterMarkersFacet
import dev.supermux.editor.plugins.highlight.highlight
import dev.supermux.editor.plugins.highlight.rememberSyntaxHost
import dev.supermux.ui.DiffLineKind
import dev.supermux.ui.editor.minimalChange
import dev.supermux.ui.editor.rememberSyntaxBackend
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.theme.Space

/** A tool pane shows this many lines before "Show all". */
internal const val TOOL_PANE_LINES = 14

/** "Show all" grows the pane up to this many lines; past it the pane scrolls (one canvas paints them all). */
internal const val TOOL_PANE_EXPANDED_LINES = 400

/** The class of a [DiffLineKind.META] line (a later hunk header, a per-file header): line and text. */
internal const val TOOL_DIFF_META = "tool-diff-meta"

/**
 * [text] read-only on the editor, highlighted as [language] (a grammar id; null is plain text), in
 * [theme] (its background shows through). No line numbers, no wrapping. At most [maxLines] lines
 * tall, then a "Show all N lines" toggle in [toggleColor]. [lineKinds]: [text] is a diff document
 * (`toolDiffDoc`), its added and removed lines tinted and barred in the gutter.
 *
 * A running tool grows [text] (Bash output streams): the same view takes the difference.
 */
@Composable
internal fun ToolCodeEditor(
    text: String,
    language: String?,
    label: String,
    theme: EditorTheme,
    toggleColor: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = TOOL_PANE_LINES,
    lineKinds: List<DiffLineKind>? = null,
    tag: String = "tool_code_editor",
) {
    val syntax = LocalPlatform.current.editorSyntax
    // A diff is rebuilt whole (its decorations are by line); plain text streams into one view.
    val view = remember(language, lineKinds, if (lineKinds != null) text else null) {
        val extensions = extensionOf(highlight(language), lineKinds?.let { diffLines(text, it) } ?: extensionOf())
        EditorView(EditorState.create(text, extensions = extensions))
    }
    LaunchedEffect(view, text) {
        val change = minimalChange(view.state.doc.toString(), text) ?: return@LaunchedEffect
        view.dispatch(TransactionSpec(changes = listOf(change)))
    }
    val backend = if (language != null) rememberSyntaxBackend() else null
    if (backend != null) rememberSyntaxHost(view, backend, syntax.registry)

    val shownTheme = remember(theme) {
        theme.copy(
            background = Color.Transparent,
            fontSizeSp = CODE_BLOCK_FONT_SP,
            lineHeightFactor = CODE_BLOCK_LINE_FACTOR,
            classStyles = theme.classStyles + (TOOL_DIFF_META to SpanStyle(color = theme.gutterForeground, fontStyle = FontStyle.Italic)),
            lineClassBackgrounds = theme.lineClassBackgrounds + (TOOL_DIFF_META to theme.currentLine),
        )
    }
    val lineCount = view.state.doc.lineCount
    var expanded by remember { mutableStateOf(false) }
    val shownLines = lineCount.coerceAtMost(if (expanded) TOOL_PANE_EXPANDED_LINES else maxLines)
    val density = LocalDensity.current
    val fontSp = view.fontSize ?: shownTheme.fontSizeSp
    val lineHeightPx = kotlin.math.round(with(density) { fontSp.sp.toPx() } * shownTheme.lineHeightFactor).coerceAtLeast(1f)
    val height = with(density) { (shownLines * lineHeightPx).toDp() }

    Column(modifier) {
        // Its selection is the editor's own; a message's SelectionContainer stays out of it.
        DisableSelection {
            Editor(
                view = view,
                modifier = Modifier.fillMaxWidth().height(height).testTag(tag),
                theme = shownTheme,
                lineWrap = false,
                showLineNumbers = false,
                readOnly = true,
                label = label,
            )
        }
        if (lineCount > maxLines) {
            Text(
                text = if (expanded) "Show less" else "Show all $lineCount lines",
                color = toggleColor,
                fontSize = 11.sp,
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(top = Space.xs, bottom = 2.dp)
                    .testTag("${tag}_toggle"),
            )
        }
    }
}

/** [text]'s lines tinted by [kinds]: added and removed lines (background and a gutter bar), headers. */
private fun diffLines(text: String, kinds: List<DiffLineKind>): Extension {
    val lines = ArrayList<Ranged<Decoration>>()
    val marks = ArrayList<Ranged<Decoration>>()
    val bars = ArrayList<Ranged<GutterMarker>>()
    var start = 0
    for (kind in kinds) {
        if (start > text.length) break
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        when (kind) {
            DiffLineKind.ADD -> {
                lines += Ranged(start, start, Decoration.LineStyle(setOf("diff-add")))
                bars += Ranged(start, start, GutterMarker("diff", "diff-add", "added"))
            }
            DiffLineKind.REMOVE -> {
                lines += Ranged(start, start, Decoration.LineStyle(setOf("diff-remove")))
                bars += Ranged(start, start, GutterMarker("diff", "diff-remove", "removed"))
            }
            DiffLineKind.META -> {
                lines += Ranged(start, start, Decoration.LineStyle(setOf(TOOL_DIFF_META)))
                if (end > start) marks += Ranged(start, end, Decoration.Mark(setOf(TOOL_DIFF_META)))
            }
            DiffLineKind.CONTEXT -> Unit
        }
        start = end + 1
    }
    // After highlight(): a header's own colour wins over the grammar's.
    return extensionOf(
        decorationsFacet.of(RangeSet.of(lines + marks)),
        gutterMarkersFacet.of(RangeSet.of(bars)),
    )
}
