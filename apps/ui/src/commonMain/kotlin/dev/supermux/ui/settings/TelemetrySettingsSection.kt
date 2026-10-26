// Settings → System → TELEMETRY: the anonymous-usage toggle and a viewer for exactly what was sent
// (TELEMETRY.md at the repo root lists every event and field).
package dev.supermux.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.supermux.net.TelemetryState
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Space
import dev.supermux.ui.widgets.AlertDialog
import dev.supermux.ui.widgets.SettingsCaption
import dev.supermux.ui.widgets.SettingsSectionHeader
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

const val TELEMETRY_DOC_URL = "https://github.com/UstaLabs/supermux/blob/main/TELEMETRY.md"

private val prettyJson = Json { prettyPrint = true }

/** Why the switch can't be used, or null when it can. */
internal fun telemetryLockedReason(state: TelemetryState): String? = when (state.source) {
    "env" -> "Turned off by an environment variable on the host (DO_NOT_TRACK or MUX_TELEMETRY)."
    "build" -> "Test broker: nothing is sent."
    else -> null
}

@Composable
internal fun TelemetrySettingsSection(actions: SystemSettingsActions) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var state by remember { mutableStateOf<TelemetryState?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var recent by remember { mutableStateOf<List<JsonObject>?>(null) }

    LaunchedEffect(Unit) { state = actions.getTelemetry() }
    val s = state ?: return   // older broker: no section

    HorizontalDivider(color = cs.outlineVariant)
    SettingsSectionHeader("TELEMETRY")
    val locked = telemetryLockedReason(s)
    Row(
        Modifier.fillMaxWidth().testTag("system_telemetry_row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Send anonymous usage events", style = MaterialTheme.typography.titleMedium, color = cs.onBackground)
            Text(
                locked ?: "Which agents, models, apps and features you use. No prompts, code, paths or names.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(Space.md))
        Switch(
            checked = s.enabled,
            enabled = locked == null && !saving,
            onCheckedChange = { on ->
                saving = true
                error = null
                scope.launch {
                    val next = actions.setTelemetry(on)
                    if (next != null) state = next else error = "Couldn't change the setting."
                    saving = false
                }
            },
            modifier = Modifier.testTag("system_telemetry_switch"),
        )
    }
    error?.let { Text(it, color = cs.error, style = MaterialTheme.typography.labelMedium) }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        TextButton(
            onClick = { scope.launch { recent = actions.getTelemetryRecent()?.events ?: emptyList() } },
            modifier = Modifier.testTag("system_telemetry_recent"),
        ) { Text("View recent events") }
        TextButton(
            onClick = { uri.openUri(TELEMETRY_DOC_URL) },
            modifier = Modifier.testTag("system_telemetry_doc"),
        ) { Text("What's sent") }
    }

    recent?.let { events ->
        AlertDialog(
            onDismissRequest = { recent = null },
            title = { Text("Recent telemetry events") },
            text = {
                Column(
                    Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    if (events.isEmpty()) SettingsCaption("Nothing recorded yet.")
                    for (e in events) {
                        val sent = (e["sent"] as? JsonPrimitive)?.booleanOrNull == true
                        Text(
                            if (sent) "sent" else "queued",
                            style = MaterialTheme.typography.labelMedium,
                            color = cs.onSurfaceVariant,
                        )
                        Text(
                            prettyJson.encodeToString(JsonElement.serializer(), JsonObject(e - "sent")),
                            fontFamily = MonoFontFamily,
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurface,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { recent = null }) { Text("Close") } },
            modifier = Modifier.testTag("system_telemetry_recent_dialog"),
        )
    }
}
