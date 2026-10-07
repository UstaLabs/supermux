// A fenced code block's text on the native editor (read-only): the same surface, highlighting and
// theme as a file in the editor pane, sized to its whole text so the chat scrolls, not the block.
package dev.supermux.ui.chat

import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.height
import dev.supermux.editor.compose.Editor
import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.plugins.highlight.highlight
import dev.supermux.editor.plugins.highlight.rememberSyntaxHost
import dev.supermux.ui.editor.minimalChange
import dev.supermux.ui.editor.rememberAppEditorTheme
import dev.supermux.ui.editor.rememberSyntaxBackend
import dev.supermux.ui.platform.LocalPlatform

/** The chat's code size: what the old `Text` block used (12sp on an 18sp line). */
internal const val CODE_BLOCK_FONT_SP = 12f
internal const val CODE_BLOCK_LINE_FACTOR = 1.5f

/**
 * [code] read-only on the editor, highlighted as [lang] (a fence's info string: `kotlin`, `ts`,
 * `py title=x`; unknown or empty is plain text). No gutter, no wrapping (a long line scrolls
 * sideways). Its height is its lines': with nothing to scroll vertically, the surface hands a wheel
 * or a drag over it on to the chat (nested scroll). Selection, `Mod-a` and `Mod-c` work as in any editor.
 *
 * A streaming reply grows [code]: the same view takes the difference, so the highlighting and a
 * selection survive every chunk.
 */
@Composable
internal fun CodeBlockEditor(code: String, lang: String?, modifier: Modifier = Modifier) {
    val syntax = LocalPlatform.current.editorSyntax
    val language = remember(lang) { lang?.let(syntax.registry::aliasFor) }
    val view = remember(language) { EditorView(EditorState.create(code, extensions = highlight(language))) }
    LaunchedEffect(view, code) {
        val change = minimalChange(view.state.doc.toString(), code) ?: return@LaunchedEffect
        view.dispatch(TransactionSpec(changes = listOf(change)))
    }
    // Only with a grammar: the backend (the web's syntax module) loads on the first highlighted block.
    val backend = if (language != null) rememberSyntaxBackend() else null
    if (backend != null) rememberSyntaxHost(view, backend, syntax.registry)

    val appTheme = rememberAppEditorTheme()
    val theme = remember(appTheme) {
        // The block's own surface shows through (the chat's code background, its accent).
        appTheme.copy(background = Color.Transparent, fontSizeSp = CODE_BLOCK_FONT_SP, lineHeightFactor = CODE_BLOCK_LINE_FACTOR)
    }
    // The surface's line height, as it computes it (LineLayouts): a zoom (Mod +/−) grows the block.
    val density = LocalDensity.current
    val fontSp = view.fontSize ?: theme.fontSizeSp
    val lineHeightPx = kotlin.math.round(with(density) { fontSp.sp.toPx() } * theme.lineHeightFactor).coerceAtLeast(1f)
    val height = with(density) { (view.state.doc.lineCount * lineHeightPx).toDp() }

    // Its selection is the editor's own; the message's SelectionContainer stays out of it.
    DisableSelection {
        Editor(
            view = view,
            modifier = modifier.height(height).testTag("chat_code_editor"),
            theme = theme,
            lineWrap = false,
            showLineNumbers = false,
            readOnly = true,
            label = if (language != null) "$language code" else "Code",
        )
    }
}
