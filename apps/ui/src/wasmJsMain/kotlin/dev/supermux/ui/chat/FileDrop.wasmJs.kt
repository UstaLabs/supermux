// Compose's own drag-and-drop, exactly the shape of the jvm actual — only the payload differs: on
// the JVM `DragData.FilesList` wraps an AWT transferable, on the web the transfer data carries the
// DOM `DataTransfer` the browser handed the drop. `DragAndDropEvent.transferData` and
// `domDataTransferOrNull` are both still experimental in CMP 1.11.1.
@file:OptIn(ExperimentalComposeUiApi::class)

package dev.supermux.ui.chat

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.domDataTransferOrNull
import dev.supermux.chat.mimeForFileName
import dev.supermux.net.BlobChunkSource
import dev.supermux.ui.platform.PickedFile
import dev.supermux.ui.widgets.PointerAnchoredMenu
import org.w3c.files.FileList
import org.w3c.files.get

/** The dropped `FileList` as upload-ready [PickedFile]s — the SAME funnel the attach dialog uses.
 *  The browser's own `File.type` wins when it has one; otherwise the name's extension decides. */
private fun pickedFilesFrom(list: FileList): List<PickedFile> =
    (0 until list.length).mapNotNull { list[it] }.map { file ->
        PickedFile(
            name = file.name,
            mime = file.type.ifBlank { mimeForFileName(file.name) ?: "application/octet-stream" },
            source = BlobChunkSource(file),
        )
    }

/**
 * Accept files dragged in from outside the browser.
 *
 * Compose-for-Web owns the document-level drag listeners (`WebDragAndDropManager`) and hit-tests
 * them against the node tree for us, so this is the desktop actual with a different payload reader:
 * only the composer actually under the pointer sees the drag, and a hidden pane — laid out 0×0 by
 * [dev.supermux.ui.widgets.KeepAlivePanel] but still composed — is naturally inert.
 */
actual fun Modifier.externalFileDropTarget(
    enabled: Boolean,
    onDragOver: (Boolean) -> Unit,
    onFiles: (List<PickedFile>) -> Unit,
): Modifier {
    if (!enabled) return this
    val target = object : DragAndDropTarget {
        override fun onEntered(event: DragAndDropEvent) = onDragOver(true)
        override fun onExited(event: DragAndDropEvent) = onDragOver(false)

        // `onEnded` fires when the session ends ANYWHERE, so the highlight cannot stick.
        override fun onEnded(event: DragAndDropEvent) = onDragOver(false)

        override fun onDrop(event: DragAndDropEvent): Boolean {
            val files = event.transferData?.domDataTransferOrNull?.files
                ?.let(::pickedFilesFrom)
                ?: return false
            if (files.isEmpty()) return false
            onFiles(files)
            return true
        }
    }
    return this.dragAndDropTarget(shouldStartDragAndDrop = { true }, target = target)
}

/**
 * Right-click "Paste image" around the composer card.
 *
 * Unlike desktop's `ContextMenuArea` — which shows an EMPTY menu (i.e. none at all) when there is
 * nothing on the clipboard — the item is always present here and merely `enabled = pasteEnabled`,
 * because a `DropdownMenu` with no items would open as an empty sliver.
 */
@Composable
actual fun ComposerContextMenu(
    pasteEnabled: Boolean,
    onPasteImage: () -> Unit,
    content: @Composable () -> Unit,
) {
    PointerAnchoredMenu(content = content) { dismiss ->
        DropdownMenuItem(
            text = { Text("Paste image") },
            enabled = pasteEnabled,
            onClick = {
                dismiss()
                onPasteImage()
            },
        )
    }
}
