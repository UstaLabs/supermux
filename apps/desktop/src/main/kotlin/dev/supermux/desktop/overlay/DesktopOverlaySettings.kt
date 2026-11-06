// The desktop overlay's own settings, under its Appearance switch (#62): how you hold to talk from any app.
package dev.supermux.desktop.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.supermux.ui.prefs.LocalUiPrefs
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * "Hold to talk from any app": the combo (no permission), or right ⌥ alone, which macOS gates behind Input
 * Monitoring. That permission is only ever offered here, once you pick right ⌥.
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
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Hold to talk from any app", style = MaterialTheme.typography.bodyLarge, color = cs.onBackground)
                Text(
                    if (key.needsInputMonitoring) "Hold right ⌥ on its own and talk; let go to send. macOS asks for Input Monitoring first."
                    else "Hold $label and talk; let go to send. No permission needed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Box {
                OutlinedButton(onClick = { open = true }, modifier = Modifier.testTag("appearance_overlay_talk_key")) { Text(label) }
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
        waylandKeyNote(portal)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant) }
        refused?.let {
            Text(
                "Another app already uses ${it.label}: pick another key here.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.error,
            )
        }
        if (key.needsInputMonitoring && isMac && !MacOverlayNative.canListenToKeys()) {
            TextButton(onClick = { if (!MacOverlayNative.askToListenToKeys()) MacOverlayNative.openPrivacySettings("ListenEvent") }) {
                Text("Allow Input Monitoring")
            }
        }
    }
}

/** What Settings says about the Wayland key, as its portal answered: what the desktop can't do, and what instead. */
internal fun waylandKeyNote(outcome: WaylandHotKey.Outcome?): String? = when (outcome) {
    WaylandHotKey.Outcome.NO_PORTAL -> "Hold-to-talk from any app needs GNOME 48+ or KDE Plasma 5.27+; hold your Agent to talk."
    WaylandHotKey.Outcome.DECLINED -> "The desktop wasn't allowed to give supermux this key. Pick it again here to be asked again."
    // It answered but couldn't hand the key over: its backend failed, or its dialog went unanswered.
    WaylandHotKey.Outcome.FAILED -> "The desktop couldn't give supermux this key; hold your Agent to talk, or pick it again here to try again."
    WaylandHotKey.Outcome.BOUND, null -> null
}
