// The native editor inside the app's panes (M5 A3): what `EditorSurface` + the CodeMirror engine
// did for one document, on the Compose editor. The pane BORROWS the document's view from its
// NativeDocument (the view outlives the pane: see NativeDocuments.kt); everything around it (header,
// tabs, stale banner, preview, empty/error/loading states) stays the host's.
package dev.supermux.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.editor.compose.Editor
import dev.supermux.editor.compose.EditorAccessories
import dev.supermux.editor.compose.EditorEffects
import dev.supermux.editor.compose.EditorTheme
import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.compose.WidgetRegistry
import dev.supermux.editor.compose.packagedEditorFontFamily
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.plugins.autocomplete.Autocomplete
import dev.supermux.editor.plugins.autocomplete.registerWidgets
import dev.supermux.editor.plugins.highlight.SyntaxHost
import dev.supermux.editor.plugins.lint.Lint
import dev.supermux.editor.plugins.lint.registerWidgets
import dev.supermux.editor.plugins.search.Search
import dev.supermux.editor.plugins.search.registerWidgets
import dev.supermux.editor.plugins.view.EditorSettings
import dev.supermux.editor.plugins.view.ViewSettings
import dev.supermux.ui.platform.LocalPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Give [documents] native views for as long as this composition lives, on [scope] (the owner's UI
 * scope: plugins, syntax hosts and LSP clients run there). Idempotent: a store another composition
 * already equipped keeps its environment. False when the native editor is switched off
 * ([NativeEditor.enabled]).
 */
@Composable
fun rememberNativeDocuments(documents: DocumentStore, scope: CoroutineScope = rememberCoroutineScope()): Boolean {
    if (!NativeEditor.enabled) return false
    val syntax = LocalPlatform.current.editorSyntax
    remember(documents) {
        if (documents.native == null) documents.native = NativeEditorEnv(scope = scope, syntax = syntax)
        Unit
    }
    return true
}

/** The editor's theme, following the app's light / dark scheme (decided 2026-09-28; CM6 was always dark). */
@Composable
fun rememberAppEditorTheme(): EditorTheme {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val font = packagedEditorFontFamily()
    return remember(font, dark) { if (dark) EditorTheme.dark(font) else EditorTheme.light(font) }
}

/** The widget content every document editor needs: search and go-to-line panels, completion, lint, "syntax off". */
@Composable
fun rememberDocumentWidgets(): WidgetRegistry = remember {
    WidgetRegistry().also {
        Search.registerWidgets(it)
        Autocomplete.registerWidgets(it)
        Lint.registerWidgets(it)
        SyntaxHost.registerWidgets(it)
    }
}

/**
 * [doc] on the native editor: its NativeDocument's view (borrowed for as long as this is composed),
 * the app's settings pushed into every view ([lineWrap], [fontSize]), a zoom persisted through
 * [onFontSize] (a whole px, 10–24) with today's transient "15px" badge, a pending reveal from a chat
 * path tap (selection, centred, focused without raising a keyboard), and the mobile accessory bar
 * under the text ([accessoryBar]; it shows only while a soft keyboard is up).
 */
@Composable
fun NativeDocumentEditor(
    documents: DocumentStore,
    doc: Document,
    lineWrap: Boolean,
    fontSize: Int,
    onFontSize: (Int) -> Unit,
    modifier: Modifier = Modifier,
    accessoryBar: Boolean = true,
    readOnly: Boolean = false,
) {
    val native = remember(documents, doc) { documents.nativeFor(doc) } ?: return
    val view = remember(native) { native.acquire() }
    // Started from an effect, never mid-composition (a dispatch made while composing can be lost).
    DisposableEffect(native, view) {
        native.start()
        onDispose { native.release(view) }
    }
    LaunchedEffect(documents, lineWrap, fontSize) {
        documents.applySettings(EditorSettings(fontSize = fontSize.toFloat(), lineWrap = lineWrap))
    }

    // A chat path tap / "open at line": consumed once, after the view has been laid out.
    val reveal = doc.revealLine
    LaunchedEffect(view, reveal) {
        if (reveal == null) return@LaunchedEffect
        withTimeoutOrNull(2_000) { view.viewport.first { !it.isEmpty() } }
        revealLines(view, reveal.first, reveal.second)
        if (doc.revealLine == reveal) doc.revealLine = null
    }

    val theme = rememberAppEditorTheme()
    val widgets = rememberDocumentWidgets()
    val lspWidgets = native.lspWidgets
    if (lspWidgets != null) DisposableEffect(widgets, lspWidgets) { lspWidgets(widgets); onDispose { } }
    var badge by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(badge) { if (badge != null) { delay(ZOOM_BADGE_MS); badge = null } }
    val report = remember(view) {
        ViewSettings.fontSizeReporter(view) { px ->
            badge = px
            onFontSize(px)
        }
    }

    Column(modifier.testTag("editor_native")) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Editor(
                view = view,
                modifier = Modifier.matchParentSize(),
                theme = theme,
                lineWrap = lineWrap,
                readOnly = readOnly,
                onFontSize = report,
                label = doc.path.substringAfterLast('/'),
                widgets = widgets,
            )
            badge?.let { px ->
                Text(
                    "${px}px",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                        .testTag("editor_zoom_badge"),
                )
            }
        }
        if (accessoryBar) EditorAccessories(view, Modifier.fillMaxWidth(), theme = theme)
    }
}

/** How long the zoom badge shows ("15px"), as today's editor. */
internal const val ZOOM_BADGE_MS = 900L

/**
 * Reveal 1-based [line] (to [endLine], selected, when it is past [line]) the way today's
 * `cmRevealLine` does: the caret (or the range) there, the line CENTRED, the editor focused
 * without raising a soft keyboard.
 */
fun revealLines(view: EditorView, line: Int, endLine: Int?) {
    val doc = view.state.doc
    val first = (line - 1).coerceIn(0, doc.lineCount - 1)
    val from = doc.lineStart(first)
    val selection = if (endLine != null && endLine > line) {
        val last = (endLine - 1).coerceIn(first, doc.lineCount - 1)
        val to = if (last + 1 < doc.lineCount) doc.lineStart(last + 1) - 1 else doc.length
        EditorSelection.single(from, to)
    } else EditorSelection.cursor(from)
    view.dispatch(TransactionSpec(selection = selection, effects = listOf(EditorEffects.scrollToCenter.of(from)), userEvent = "select"))
    view.focus(showKeyboard = false)
}
