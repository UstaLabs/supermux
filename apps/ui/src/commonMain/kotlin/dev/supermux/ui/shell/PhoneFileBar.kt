// A phone's per-file controls (#84).
//
// The wide layouts put a file's Save and its Markdown preview toggle on the file's TAB
// (WorkspaceFileTab). A phone has no tab strip — its tabs are cards behind one count button — so
// those controls live in the phone's top bar instead, beside the file's name, and the unsaved state
// is marked there and on the file's card in the tab switcher.
package dev.supermux.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.supermux.fs.pathLeaf
import dev.supermux.ui.editor.DocumentStore
import dev.supermux.ui.editor.isMarkdownPath
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.theme.HapticKind
import dev.supermux.ui.theme.rememberHaptics

/** The unsaved-changes dot before a file's name, drawn in the name's own colour. */
@Composable
internal fun UnsavedDot(color: Color, modifier: Modifier = Modifier, size: Dp = 7.dp) {
    Box(
        modifier
            .size(size)
            .background(color, CircleShape)
            .semantics { contentDescription = "Unsaved changes" },
    )
}

/**
 * The phone bar's dot, while [path] has unsaved edits. A composable of its own on purpose: the dirty
 * flag is read on every keystroke, and only this should redraw for it, not the whole phone layout.
 */
@Composable
internal fun PhoneFileUnsavedDot(documents: DocumentStore, path: String) {
    if (!documents.isDirty(path)) return
    UnsavedDot(
        color = LocalContentColor.current,
        modifier = Modifier.padding(start = 10.dp).testTag("phone_file_unsaved"),
    )
}

/**
 * The phone bar's file actions: the Markdown preview toggle (Markdown files only) and Save.
 *
 * Save is there only while the file has unsaved edits, so a clean file's bar looks as it always did,
 * and it is a labelled button rather than the tab's ✓: on a phone it is the bar's one prominent
 * action. The write runs on the store's scope, so switching tabs mid-save cannot cancel it; a
 * refused write says so, and the edit stays unsaved with Save still there to try again.
 */
@Composable
internal fun PhoneFileActions(
    viewId: String,
    path: String,
    documents: DocumentStore,
    previewModes: SnapshotStateMap<String, Boolean>,
) {
    val cs = MaterialTheme.colorScheme
    val notices = LocalPlatform.current.notices
    val haptics = rememberHaptics()

    if (isMarkdownPath(path)) {
        val preview = previewModes[viewId] == true
        IconButton(
            onClick = { previewModes[viewId] = !preview },
            modifier = Modifier.testTag("phone_file_preview"),
        ) {
            Icon(
                imageVector = if (preview) Icons.Filled.Edit else Icons.Filled.Visibility,
                contentDescription = if (preview) "Edit" else "Preview",
                tint = if (preview) cs.primary else cs.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }

    val saving = documents.isSaving(path)
    if (saving || documents.isDirty(path)) {
        FilledTonalButton(
            onClick = {
                val doc = documents.get(path) ?: return@FilledTonalButton
                haptics.perform(HapticKind.Confirm)
                documents.save(doc) { written ->
                    if (!written) notices.show("Couldn't save ${pathLeaf(path)}")
                }
            },
            enabled = !saving,
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .height(32.dp)
                .widthIn(min = 64.dp)
                .testTag("phone_file_save"),
        ) {
            if (saving) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = cs.primary)
            } else {
                Text("Save")
            }
        }
    }
}
