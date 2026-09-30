package dev.supermux.desktop.host

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties
import dev.supermux.ui.widgets.AlertDialog

/** "This stops supermux and your N running sessions." [Cancel] [Quit]. */
@Composable
fun QuitConfirmDialog(text: String, onQuit: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Quit supermux?") },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onQuit) { Text("Quit") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** A broker set up outside the app answers on our port (spec D6). No dismiss: it must be answered. */
@Composable
fun TakeoverDialog(onManage: () -> Unit, onLeave: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        text = {
            Text(
                "supermux is already running on this computer (set up outside the app). " +
                    "Let the app manage and update it?",
            )
        },
        confirmButton = { TextButton(onClick = onManage) { Text("Manage it") } },
        dismissButton = { TextButton(onClick = onLeave) { Text("Leave it alone") } },
    )
}

/** The found broker is newer than the bundled one (spec D6). */
@Composable
fun DowngradeDialog(onKeep: () -> Unit, onUseBundled: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        text = { Text("A newer supermux is running. Keep it until the app catches up?") },
        confirmButton = { TextButton(onClick = onKeep) { Text("Keep it") } },
        dismissButton = { TextButton(onClick = onUseBundled) { Text("Use this app's version") } },
    )
}

/**
 * Every hosting question the window can show, over the wizard or the shell: the supervisor's open
 * takeover/downgrade question, then the quit confirm.
 */
@Composable
fun HostingDialogs(
    status: HostingStatus,
    confirmQuit: String?,
    onTakeover: (manage: Boolean) -> Unit,
    onDowngrade: (useBundled: Boolean) -> Unit,
    onQuit: () -> Unit,
    onCancelQuit: () -> Unit,
) {
    when (status) {
        is HostingStatus.AskTakeover -> TakeoverDialog(onManage = { onTakeover(true) }, onLeave = { onTakeover(false) })
        is HostingStatus.AskDowngrade -> DowngradeDialog(onKeep = { onDowngrade(false) }, onUseBundled = { onDowngrade(true) })
        else -> Unit
    }
    if (confirmQuit != null) QuitConfirmDialog(confirmQuit, onQuit = onQuit, onCancel = onCancelQuit)
}
