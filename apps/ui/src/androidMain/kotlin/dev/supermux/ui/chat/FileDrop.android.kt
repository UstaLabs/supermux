package dev.supermux.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.supermux.ui.platform.PickedFile

/** No OS drag session reaches a phone composer — paste-to-attach (the `+` menu / `contentReceiver`)
 *  is the Android equivalent, and it is wired in the composer itself. */
actual fun Modifier.externalFileDropTarget(
    enabled: Boolean,
    onDragOver: (Boolean) -> Unit,
    onFiles: (List<PickedFile>) -> Unit,
): Modifier = this

/** No right-click on a phone: the paste-image entry is a row in the `+` attach menu. */
@Composable
actual fun ComposerContextMenu(
    pasteEnabled: Boolean,
    onPasteImage: () -> Unit,
    content: @Composable () -> Unit,
) {
    content()
}
