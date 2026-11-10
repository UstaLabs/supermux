// The desktop overlay's own settings, under its switch on Privacy & labs (#62, #97): how you hold to talk from any app.
package dev.supermux.desktop.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.prefs.LocalUiPrefs
import dev.supermux.ui.settings.kit.KitGhostButton
import dev.supermux.ui.settings.kit.KitNote
import dev.supermux.ui.settings.kit.KitRow
import dev.supermux.ui.settings.kit.KitTextAction
import dev.supermux.ui.settings.kit.kitColors
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * "Hold to talk from any app", under the overlay's switch on Privacy & labs: the combo (no permission), or right ⌥
 * alone, which macOS gates behind Input Monitoring. That permission is only ever offered here, once you pick right ⌥.
 */
@Composable
internal fun DesktopOverlaySettings() {
    val prefs = LocalUiPrefs.current
    val scope = rememberCoroutineScope()
    val stored by prefs.assistantOverlayTalkKey.collectAsState(null)
    val key = TalkKey.parse(stored, desktopOs)
    val label = key.on(desktopOs)?.label.orEmpty()
    val refused by TalkKeyStatus.refused.collectAsState()
    val portal by TalkKeyStatus.wayland.collectAsState()
    var open by remember { mutableStateOf(false) }
    val c = kitColors()
    // The notes sit under the indented row, at its indent.
    val under = Modifier.padding(start = 18.dp)
    Column(Modifier.fillMaxWidth()) {
        KitRow(
            "Hold to talk from any app",
            description = if (key.needsInputMonitoring) "Hold right ⌥ on its own and talk; let go to send. macOS asks for Input Monitoring first."
            else "Hold $label and talk; let go to send. No permission needed.",
            indent = true,
        ) {
            Box {
                KitGhostButton(label, onClick = { open = true }, modifier = Modifier.testTag("appearance_overlay_talk_key"))
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    for (k in TalkKey.offeredOn(desktopOs)) {
                        val name = k.on(desktopOs)?.label.orEmpty()
                        DropdownMenuItem(
                            text = { Text(if (k.needsInputMonitoring) "$name · asks for Input Monitoring" else name) },
                            onClick = {
                                open = false
                                // The same key again sets it up anew: a desktop that declined it, or failed to, is asked again.
                                if (k == key) TalkKeyStatus.picks.update { it + 1 }
                                else scope.launch { prefs.putAssistantOverlayTalkKey(k.name) }
                            },
                        )
                    }
                }
            }
        }
        waylandKeyNote(portal)?.let { KitNote(it, under) }
        refused?.let {
            Text(
                "Another app already uses ${it.label}: pick another key here.",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = c.danger,
                modifier = under.padding(top = 10.dp),
            )
        }
        if (key.needsInputMonitoring && isMac && !MacOverlayNative.canListenToKeys()) {
            KitTextAction(
                "Allow Input Monitoring",
                onClick = { if (!MacOverlayNative.askToListenToKeys()) MacOverlayNative.openPrivacySettings("ListenEvent") },
                modifier = under.padding(top = 6.dp),
            )
        }
    }
}

/** What Settings says about the Wayland key, as its portal answered: what the desktop can't do, and what instead. */
internal fun waylandKeyNote(outcome: WaylandHotKey.Outcome?): String? = when (outcome) {
    WaylandHotKey.Outcome.NO_PORTAL -> "Hold-to-talk from any app needs GNOME 48+ or KDE Plasma 5.27+; hold your Agent to talk."
    // Its dialog is up, for as long as the user takes: the key comes once they approve it.
    WaylandHotKey.Outcome.ASKING -> "Waiting for you to approve the shortcut…"
    WaylandHotKey.Outcome.DECLINED -> "The desktop wasn't allowed to give supermux this key. Pick it again here to be asked again."
    // It answered but couldn't hand the key over: its backend failed.
    WaylandHotKey.Outcome.FAILED -> "The desktop couldn't give supermux this key; hold your Agent to talk, or pick it again here to try again."
    WaylandHotKey.Outcome.BOUND, null -> null
}
