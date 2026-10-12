package dev.supermux.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.supermux.ui.platform.PickedFile

/**
 * Accept files dragged in from OUTSIDE the app (a file manager, a browser download bar).
 *
 * Desktop's `dragAndDropTarget` behind an expect/actual because the payload it hands back is an
 * AWT `DataFlavor.javaFileListFlavor` list, which has no place in `:ui` commonMain, and there
 * is no OS drag session to receive on a phone at all, so Android's actual is a no-op modifier.
 *
 * @param enabled false wires nothing (a text-only composer with no upload seam bound).
 * @param onDragOver drag-over highlight: true on enter, false on exit AND on the drag session
 *   ending elsewhere, so the highlight can never stick.
 * @param onFiles the dropped files, already filtered to ones that still exist on disk and
 *   converted into upload-ready [PickedFile]s — the SAME funnel the attach dialog uses.
 */
expect fun Modifier.externalFileDropTarget(
    enabled: Boolean,
    onDragOver: (Boolean) -> Unit,
    onFiles: (List<PickedFile>) -> Unit,
): Modifier

/**
 * Lets a host (the chat panel) own the drop target for a WHOLE surface while the [Composer] inside it
 * stays the one funnel files are staged through: a file dropped on the transcript lands as a chip in
 * the composer exactly as if it had been dropped on the card.
 *
 * The host installs [externalFileDropTarget] wired to [dragOver] / [deliver] on its own root and
 * hands this to the composer, which then installs no target of its own (two nested targets would
 * both highlight) and registers itself as the [deliver] sink while it is composed.
 */
@Stable
class ComposerDropState {
    /** True while an external drag hovers anywhere over the host — the composer card highlights. */
    var dragOver by mutableStateOf(false)

    internal var sink: ((List<PickedFile>) -> Unit)? = null

    /** Hand dropped files to the composer. False when no composer is listening (nothing staged). */
    fun deliver(files: List<PickedFile>): Boolean {
        val s = sink ?: return false
        s(files)
        return true
    }
}

/**
 * A right-click "Paste image" affordance around the composer card.
 *
 * Desktop's `ContextMenuArea`; a no-op passthrough on Android, where the paste-image entry lives in
 * the `+` attach menu instead (there is no right-click on a phone).
 */
@Composable
expect fun ComposerContextMenu(
    pasteEnabled: Boolean,
    onPasteImage: () -> Unit,
    content: @Composable () -> Unit,
)
