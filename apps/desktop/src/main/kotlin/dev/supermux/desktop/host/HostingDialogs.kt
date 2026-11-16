package dev.supermux.desktop.host

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.widgets.AlertDialog

/** "This stops supermux and your N running sessions." [Cancel] [Quit]. */
@Composable
fun QuitConfirmDialog(text: String, onQuit: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(localized(Str.host_app_desktop_quit_title)) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onQuit) { Text(localized(Str.host_app_desktop_quit)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(localized(Str.shell_cancel)) } },
    )
}

/** A broker set up outside the app answers on our port (spec D6). No dismiss: it must be answered. */
@Composable
fun TakeoverDialog(onManage: () -> Unit, onLeave: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        text = {
            Text(localized(Str.host_app_desktop_takeover_text))
        },
        confirmButton = { TextButton(onClick = onManage) { Text(localized(Str.host_app_desktop_manage_it)) } },
        dismissButton = { TextButton(onClick = onLeave) { Text(localized(Str.host_app_desktop_leave_it)) } },
    )
}

/** The found broker is newer than the bundled one (spec D6). */
@Composable
fun DowngradeDialog(onKeep: () -> Unit, onUseBundled: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        text = { Text(localized(Str.host_app_desktop_downgrade_text)) },
        confirmButton = { TextButton(onClick = onKeep) { Text(localized(Str.host_app_desktop_keep_it)) } },
        dismissButton = { TextButton(onClick = onUseBundled) { Text(localized(Str.host_app_desktop_use_bundled)) } },
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
