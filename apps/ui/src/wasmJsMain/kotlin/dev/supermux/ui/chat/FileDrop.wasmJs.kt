package dev.supermux.ui.chat

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import dev.supermux.chat.mimeForFileName
import dev.supermux.net.BlobChunkSource
import dev.supermux.ui.platform.PickedFile
import dev.supermux.ui.widgets.PointerAnchoredMenu
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.DragEvent
import org.w3c.dom.HTMLCanvasElement
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
 * A drag position in Compose window coordinates, or null when there is no Compose canvas yet.
 *
 * The DOM reports `clientX/clientY` in CSS pixels relative to the viewport; Compose's canvas is
 * sized `cssPixels * devicePixelRatio`, and its window coordinates are in those device pixels — the
 * same conversion `ComposeViewport` applies to real pointer events.
 */
private fun dragPositionInWindow(event: DragEvent): Offset? {
    val canvas = document.querySelector("canvas") as? HTMLCanvasElement ?: return null
    val rect = canvas.getBoundingClientRect()
    val scale = window.devicePixelRatio
    return Offset(
        ((event.clientX - rect.left) * scale).toFloat(),
        ((event.clientY - rect.top) * scale).toFloat(),
    )
}

/**
 * Accepts an OS file drag over THIS node's bounds.
 *
 * The listeners are on the document because the Compose canvas is a single `<canvas>` element —
 * there is no DOM node per composable to hang a drop target on — but every event is then hit-tested
 * against the node's own `boundsInWindow()`, so a second chat pane does not stage the same drop a
 * second time, and a hidden pane (laid out 0×0 by [dev.supermux.ui.widgets.KeepAlivePanel], yet
 * still composed) is inert without any extra bookkeeping.
 *
 * The highlight tracks that hit test rather than `dragleave`, which fires on every DOM-internal
 * crossing and would flicker; `dragend`/`drop`, plus a `dragleave` that lands outside the bounds
 * (what leaving the window looks like), guarantee it cannot stick.
 *
 * `dragenter` and `dragover` must both `preventDefault()` or the browser refuses the drop and
 * navigates to the file instead.
 */
private class DomFileDropNode(
    var onDragOver: (Boolean) -> Unit,
    var onFiles: (List<PickedFile>) -> Unit,
) : Modifier.Node(), GlobalPositionAwareModifierNode {
    private var bounds: Rect = Rect.Zero
    private var inside = false

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        bounds = coordinates.boundsInWindow()
    }

    private fun isOver(event: Event): Boolean {
        val drag = event as? DragEvent ?: return false
        val at = dragPositionInWindow(drag) ?: return false
        return !bounds.isEmpty && bounds.contains(at)
    }

    /** Edge-triggered: the composer only hears about a change, never a repeat of the same state. */
    private fun setInside(value: Boolean) {
        if (inside == value) return
        inside = value
        onDragOver(value)
    }

    // Held in fields so removeEventListener gets the SAME function instances addEventListener got.
    private val onDragEnterEvent: (Event) -> Unit = { event ->
        event.preventDefault()
        setInside(isOver(event))
    }
    private val onDragOverEvent: (Event) -> Unit = { event ->
        event.preventDefault()
        val over = isOver(event)
        // Tell the OS this is a copy, so the cursor shows the right affordance over our bounds.
        if (over) (event as? DragEvent)?.dataTransfer?.dropEffect = "copy"
        setInside(over)
    }
    private val onDragLeaveEvent: (Event) -> Unit = { event ->
        // Only a leave OUTSIDE our bounds ends the drag for us — leaving the window reports a
        // position of (0, 0), while a crossing between two elements inside the pane does not.
        if (!isOver(event)) setInside(false)
    }
    private val onDragEndEvent: (Event) -> Unit = { setInside(false) }
    private val onDropEvent: (Event) -> Unit = { event ->
        event.preventDefault()
        val over = isOver(event)
        setInside(false)
        if (over) {
            val files = (event as? DragEvent)?.dataTransfer?.files?.let(::pickedFilesFrom).orEmpty()
            if (files.isNotEmpty()) onFiles(files)
        }
    }

    override fun onAttach() {
        document.addEventListener("dragenter", onDragEnterEvent)
        document.addEventListener("dragover", onDragOverEvent)
        document.addEventListener("dragleave", onDragLeaveEvent)
        document.addEventListener("dragend", onDragEndEvent)
        document.addEventListener("drop", onDropEvent)
    }

    override fun onDetach() {
        document.removeEventListener("dragenter", onDragEnterEvent)
        document.removeEventListener("dragover", onDragOverEvent)
        document.removeEventListener("dragleave", onDragLeaveEvent)
        document.removeEventListener("dragend", onDragEndEvent)
        document.removeEventListener("drop", onDropEvent)
        // A pane torn down mid-drag must not leave the composer highlighted forever.
        setInside(false)
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
