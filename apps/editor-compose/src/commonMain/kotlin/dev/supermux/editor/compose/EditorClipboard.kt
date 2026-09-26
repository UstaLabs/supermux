package dev.supermux.editor.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

/**
 * The clipboard as the editor needs it: plain text in, plain text out.
 *
 * An interface, like terminal-compose's `TerminalClipboard`, for the same reason: Compose
 * Multiplatform 1.12's `LocalClipboard` takes a `ClipEntry` that has no common text constructor,
 * while the (deprecated) `LocalClipboardManager` is common plain text. A host may substitute its own
 * (a remote clipboard, a test double). On the web the copy/cut/paste KEYS are served by the
 * browser's own clipboard events instead (synchronous, no permission prompt); see
 * `installFastTyping`.
 */
@Stable
interface EditorClipboard {
    /** Put [text] on the clipboard. */
    fun write(text: String)

    /** The clipboard's text, or null when it holds none. */
    suspend fun read(): String?

    /**
     * True when there is text to paste, WITHOUT reading it (the touch menu shows Paste only then;
     * on iOS reading the clipboard is what asks the user for permission, asking whether it has
     * text is not). A clipboard that cannot tell says true.
     */
    fun hasText(): Boolean = true
}

/** The host platform's clipboard. */
@Composable
fun rememberEditorClipboard(): EditorClipboard {
    @Suppress("DEPRECATION")
    val manager = LocalClipboardManager.current
    return remember(manager) { platformEditorClipboard(ComposeEditorClipboard(manager)) }
}

/** The platform's clipboard where Compose's is not enough (the web), else [compose]. */
internal expect fun platformEditorClipboard(compose: EditorClipboard): EditorClipboard

@Suppress("DEPRECATION")
private class ComposeEditorClipboard(private val manager: ClipboardManager) : EditorClipboard {
    override fun write(text: String) = manager.setText(AnnotatedString(text))
    override suspend fun read(): String? = manager.getText()?.text?.takeIf { it.isNotEmpty() }
    override fun hasText(): Boolean = manager.hasText()
}

/**
 * What the web's copy/cut event puts on the clipboard for [view]: the whole selection (one line per
 * range; a cut then deletes it), or null to let the browser do its default. Null when [view] is
 * not focused, and when nothing is selected: the browser's own copy then runs and, with no text
 * selected in its field, leaves the clipboard as it was (a VS Code-style "copy the line" is a
 * deliberate later choice, not a side effect).
 */
internal fun webClipboardText(view: EditorView, cut: Boolean): String? {
    if (!view.focused) return null
    val text = DefaultCommands.selectedText(view.state) ?: return null
    if (cut && !view.readOnly) DefaultCommands.deleteSelection.run(view)
    return text
}
