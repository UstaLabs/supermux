package dev.supermux.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.DpOffset
import dev.supermux.chat.mimeForFileName
import dev.supermux.net.BlobChunkSource
import dev.supermux.ui.platform.PickedFile
import kotlinx.browser.document
import org.w3c.dom.DragEvent
import org.w3c.dom.events.Event
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
 * Listens for an OS file drag on the WHOLE document rather than on this node's box.
 *
 * The Compose canvas is one `<canvas>` element: there is no DOM node per composable to attach a
 * drop target to, so the document is the target. That matches desktop, where the drag-over
 * highlight also lights up for a drag anywhere over the window — the composer is the only drop
 * consumer in the app either way.
 *
 * `dragover` must `preventDefault()` or the browser refuses the drop and navigates to the file.
 */
private class DomFileDropNode(
    var onDragOver: (Boolean) -> Unit,
    var onFiles: (List<PickedFile>) -> Unit,
) : Modifier.Node() {
    // Held in fields so removeEventListener gets the SAME function instances addEventListener got.
    private val onDragOverEvent: (Event) -> Unit = { event ->
        event.preventDefault()
        onDragOver(true)
    }
    private val onDragLeaveEvent: (Event) -> Unit = { onDragOver(false) }
    private val onDropEvent: (Event) -> Unit = { event ->
        event.preventDefault()
        onDragOver(false)
        val files = (event as? DragEvent)?.dataTransfer?.files?.let(::pickedFilesFrom).orEmpty()
        if (files.isNotEmpty()) onFiles(files)
    }

    override fun onAttach() {
        document.addEventListener("dragover", onDragOverEvent)
        document.addEventListener("dragleave", onDragLeaveEvent)
        document.addEventListener("drop", onDropEvent)
    }

    override fun onDetach() {
        document.removeEventListener("dragover", onDragOverEvent)
        document.removeEventListener("dragleave", onDragLeaveEvent)
        document.removeEventListener("drop", onDropEvent)
    }
}

private data class DomFileDropElement(
    val onDragOver: (Boolean) -> Unit,
    val onFiles: (List<PickedFile>) -> Unit,
) : ModifierNodeElement<DomFileDropNode>() {
    override fun create() = DomFileDropNode(onDragOver, onFiles)

    // Refresh the callbacks in place: the listeners stay registered across recomposition, they
    // just close over the node's current fields.
    override fun update(node: DomFileDropNode) {
        node.onDragOver = onDragOver
        node.onFiles = onFiles
    }
}

actual fun Modifier.externalFileDropTarget(
    enabled: Boolean,
    onDragOver: (Boolean) -> Unit,
    onFiles: (List<PickedFile>) -> Unit,
): Modifier {
    if (!enabled) return this
    return this then DomFileDropElement(onDragOver, onFiles)
}

/**
 * Right-click "Paste image", as a material3 [DropdownMenu] at the pointer — foundation's
 * `ContextMenuArea` (the desktop actual) has no web target.
 */
@Composable
actual fun ComposerContextMenu(
    pasteEnabled: Boolean,
    onPasteImage: () -> Unit,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var at by remember { mutableStateOf(DpOffset.Zero) }
    Box(
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.type == PointerEventType.Press && ev.buttons.isSecondaryPressed) {
                        val p = ev.changes.first().position
                        at = DpOffset(p.x.toDp(), p.y.toDp())
                        open = true
                        ev.changes.forEach { it.consume() }
                    }
                }
            }
        },
    ) {
        content()
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, offset = at) {
            DropdownMenuItem(
                text = { Text("Paste image") },
                enabled = pasteEnabled,
                onClick = {
                    open = false
                    onPasteImage()
                },
            )
        }
    }
}
