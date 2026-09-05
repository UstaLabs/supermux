// Desktop's external-file drop target + right-click Paste image, behind the `:ui` expect/actual so
// the shared composer never names AWT. Moved from `desktop/chat/DesktopComposer.kt`.
//
// `androidx.compose.foundation.draganddrop.dragAndDropTarget` (the Modifier) is STABLE — no
// ExperimentalFoundationApi marker. Reading the dropped payload as `DragData` DOES need the
// file-level `@OptIn(ExperimentalComposeUiApi::class)` (confirmed by decompiling the shipped jars:
// no marker on `dragAndDropTarget`, but `DragData` and `dragData()` both carry it). On desktop an
// external OS file drop arrives as `DragData.FilesList` — `DragDataFilesListImpl` reads
// `DataFlavor.javaFileListFlavor` off the AWT transferable and maps each `java.io.File` to
// `file.toURI().toString()`, so [composerFilesFromDragData] parses those URIs back to Files.
@file:OptIn(ExperimentalComposeUiApi::class)

package dev.supermux.ui.chat

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import dev.supermux.chat.mimeForFileName
import dev.supermux.net.ChunkSource
import dev.supermux.ui.platform.PickedFile
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.nio.ByteBuffer

/** Converts the file-URI strings from `DragData.FilesList.readFiles()` back into [File]s. Compose
 *  Desktop's drag source encodes each dropped OS file as `File.toURI().toString()` (a `file:` URI),
 *  not a raw path — pure so the URI parsing is unit-testable without an actual AWT drag session. An
 *  entry that fails to parse (malformed/non-file URI) is dropped rather than throwing. */
internal fun composerFilesFromDragData(uris: List<String>): List<File> =
    uris.mapNotNull { runCatching { File(URI(it)) }.getOrNull() }

/** Filters dropped files to ones that still exist as a regular file on disk — pure so the filtering
 *  is unit-testable without AWT or Compose. Silently drops entries that vanished between the OS drop
 *  and staging (a stale symlink target, a file deleted mid-drag) rather than crashing the stager. */
internal fun filterExistingFiles(files: List<File>): List<File> = files.filter { it.isFile }

/** A [ChunkSource] over a `java.io.File`: a fresh position-absolute read per call (no shared mutable
 *  position), so the resumable upload's retries are safe to run concurrently. */
private class DroppedFileChunkSource(private val file: File) : ChunkSource {
    override val size: Long get() = file.length()

    override fun read(offset: Long, len: Int): ByteArray {
        val total = size
        if (offset >= total || len <= 0) return ByteArray(0)
        val capped = minOf(len.toLong(), total - offset).toInt()
        RandomAccessFile(file, "r").use { raf ->
            val ch = raf.channel
            val buf = ByteBuffer.allocate(capped)
            var pos = offset
            while (buf.hasRemaining()) {
                val n = ch.read(buf, pos)
                if (n < 0) break
                pos += n
            }
            return buf.array().copyOf(buf.position())
        }
    }
}

/** One existing file as an upload-ready [PickedFile] — the shape `Platform.pickFiles` returns, so a
 *  dropped file and a dialog-picked one stage identically. */
internal fun droppedPickedFile(file: File): PickedFile = PickedFile(
    name = file.name,
    mime = mimeForFileName(file.name) ?: "application/octet-stream",
    source = DroppedFileChunkSource(file),
)

actual fun Modifier.externalFileDropTarget(
    enabled: Boolean,
    onDragOver: (Boolean) -> Unit,
    onFiles: (List<PickedFile>) -> Unit,
): Modifier {
    if (!enabled) return this
    // Note: this anonymous target has no equals(), so DropTargetElement rebuilds the underlying
    // delegate node on every recomposition (minor churn, not a swap-in-place). Safe: the AWT
    // DropTarget is owned at the scene root and dispatches per-event by live tree traversal.
    val target = object : DragAndDropTarget {
        override fun onEntered(event: DragAndDropEvent) = onDragOver(true)
        override fun onExited(event: DragAndDropEvent) = onDragOver(false)
        override fun onEnded(event: DragAndDropEvent) = onDragOver(false)
        override fun onDrop(event: DragAndDropEvent): Boolean {
            val files = (event.dragData() as? DragData.FilesList)
                ?.readFiles()
                ?.let(::composerFilesFromDragData)
                ?.let(::filterExistingFiles)
                ?: return false
            if (files.isEmpty()) return false
            onFiles(files.map(::droppedPickedFile))
            return true
        }
    }
    return this.dragAndDropTarget(shouldStartDragAndDrop = { true }, target = target)
}

@Composable
actual fun ComposerContextMenu(
    pasteEnabled: Boolean,
    onPasteImage: () -> Unit,
    content: @Composable () -> Unit,
) {
    ContextMenuArea(
        items = {
            if (pasteEnabled) listOf(ContextMenuItem("Paste image") { onPasteImage() }) else emptyList()
        },
        content = content,
    )
}
