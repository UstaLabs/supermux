package dev.supermux.desktop.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * Drop-in replacements for the Material3 / Compose modal surfaces that also
 * register with [LocalModalPresence], so the heavyweight AWT children (JediTerm,
 * JCEF) hide themselves and the modal is actually visible.
 *
 * These deliberately carry the SAME NAMES as the originals. A call site opts in
 * by changing one import line —
 *
 *     -import androidx.compose.material3.AlertDialog
 *     +import dev.supermux.desktop.ui.AlertDialog
 *
 * — and nothing else, so nobody has to remember a bespoke wrapper name at the
 * 49 places this matters. Kotlin resolves the explicitly imported symbol, so
 * there is no ambiguity with the library function.
 *
 * Only the parameters this app actually passes are exposed. Adding one later is
 * a one-line change here; guessing at Material3's full default surface today
 * would pin us to internal default symbols for no benefit.
 */

@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    ModalOpen()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        properties = properties,
    )
}

@Composable
fun Dialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    ModalOpen()
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties,
        content = content,
    )
}

@Composable
fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    // Only while it is actually open: a closed menu is composed all over the app
    // and would otherwise pin every terminal hidden forever.
    if (expanded) ModalOpen()
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        content = content,
    )
}
