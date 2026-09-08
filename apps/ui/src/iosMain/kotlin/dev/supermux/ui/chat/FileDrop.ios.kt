package dev.supermux.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.supermux.ui.platform.PickedFile

/** No OS drag session reaches the composer on iOS (iPadOS drag-and-drop is a UIKit interaction the
 *  Compose surface does not receive) — the `+` attach menu is the equivalent, as on Android. */
actual fun Modifier.externalFileDropTarget(
    enabled: Boolean,
    onDragOver: (Boolean) -> Unit,
    onFiles: (List<PickedFile>) -> Unit,
): Modifier = this

/** No right-click on iOS: the paste-image entry is a row in the `+` attach menu. */
@Composable
actual fun ComposerContextMenu(
    pasteEnabled: Boolean,
    onPasteImage: () -> Unit,
    content: @Composable () -> Unit,
) {
    content()
}
