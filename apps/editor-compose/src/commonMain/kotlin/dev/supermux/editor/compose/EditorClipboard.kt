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
}

/** The host platform's clipboard. */
@Composable
fun rememberEditorClipboard(): EditorClipboard {
    @Suppress("DEPRECATION")
    val manager = LocalClipboardManager.current
    return remember(manager) { ComposeEditorClipboard(manager) }
}

@Suppress("DEPRECATION")
private class ComposeEditorClipboard(private val manager: ClipboardManager) : EditorClipboard {
    override fun write(text: String) = manager.setText(AnnotatedString(text))
    override suspend fun read(): String? = manager.getText()?.text?.takeIf { it.isNotEmpty() }
}
