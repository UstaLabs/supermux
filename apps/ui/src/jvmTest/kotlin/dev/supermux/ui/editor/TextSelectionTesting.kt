package dev.supermux.ui.editor

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextLayoutResult
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable

/**
 * Records what a copy writes, instead of the system clipboard (there is none headless).
 * `SelectionContainer` copies through `LocalClipboard`, not `LocalClipboardManager`.
 */
internal class FakeClipboard : Clipboard {
    var entry: ClipEntry? = null
    override suspend fun getClipEntry(): ClipEntry? = entry
    override suspend fun setClipEntry(clipEntry: ClipEntry?) { entry = clipEntry }

    @OptIn(ExperimentalComposeUiApi::class)
    @Suppress("DEPRECATION")
    val text: String?
        get() = (entry?.nativeClipEntry as? Transferable)?.getTransferData(DataFlavor.stringFlavor) as? String
}

internal fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
    val results = mutableListOf<TextLayoutResult>()
    fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
    return results.single()
}

/** The middle of the character at [offset], in the node's own coordinates. */
internal fun SemanticsNodeInteraction.pointAt(offset: Int): Offset = textLayout().getBoundingBox(offset).center

private val copyChord = if (System.getProperty("os.name").startsWith("Mac")) Key.MetaLeft else Key.CtrlLeft

/** Cmd+C on macOS, Ctrl+C elsewhere. It reaches whatever has the focus, as a real key press does. */
@OptIn(ExperimentalTestApi::class)
internal fun SemanticsNodeInteraction.copyShortcut() {
    performKeyInput { withKeyDown(copyChord) { pressKey(Key.C) } }
}
