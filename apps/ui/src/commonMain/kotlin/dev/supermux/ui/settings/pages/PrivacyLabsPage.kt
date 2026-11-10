// Privacy & labs (#97 spec §2.2, §10.5): the machine's anonymous usage statistics (the switch, its lock reason, the
// events it sent and the document listing every field — TELEMETRY.md at the repo root), and, where the platform has
// one, the experimental assistant overlay with its permission walk. Mixed: Privacy is the machine's, Labs this
// device's, and each section head says whose it is.
package dev.supermux.ui.settings.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.TelemetryState
import dev.supermux.ui.platform.AssistantOverlayHost
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.prefs.LocalUiPrefs
import dev.supermux.ui.settings.SettingsPlace
import dev.supermux.ui.settings.SystemSettingsActions
import dev.supermux.ui.settings.kit.KitButtons
import dev.supermux.ui.settings.kit.KitGhostButton
import dev.supermux.ui.settings.kit.KitMixedScope
import dev.supermux.ui.settings.kit.KitNote
import dev.supermux.ui.settings.kit.KitOfflineNote
import dev.supermux.ui.settings.kit.KitPage
import dev.supermux.ui.settings.kit.KitSectionHead
import dev.supermux.ui.settings.kit.KitSheet
import dev.supermux.ui.settings.kit.KitTextAction
import dev.supermux.ui.settings.kit.KitToggleRow
import dev.supermux.ui.settings.kit.LocalSettingsMachines
import dev.supermux.ui.settings.kit.currentMachineName
import dev.supermux.ui.settings.kit.currentMachineOnline
import dev.supermux.ui.settings.kit.kitAnchor
import dev.supermux.ui.settings.kit.kitColors
import dev.supermux.ui.settings.kit.kitDeviceNoun
import dev.supermux.ui.settings.settingsTitle
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.theme.MonoFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Every event and field supermux sends, opened by "What we send". */
const val TELEMETRY_DOC_URL = "https://github.com/UstaLabs/supermux/blob/main/TELEMETRY.md"

private val prettyJson = Json { prettyPrint = true }

/**
 * Why the switch can't be used, or null when it can. Says where, never how: the environment variables that lock it
 * are named in TELEMETRY.md, not on the page (spec §6).
 */
internal fun telemetryLockedReason(state: TelemetryState, machine: String): String? = when (state.source) {
    "env" -> "Turned off on $machine."
    "build" -> "A test build of supermux: nothing is sent."
    else -> null
}

/** The machine as a sentence names it: its name, or "this machine" when Settings knows none. */
@Composable
private fun machineInSentence(): String = LocalSettingsMachines.current?.current?.name ?: "this machine"

@Composable
fun PrivacyLabsPage(
    system: SystemSettingsActions,
    modifier: Modifier = Modifier,
    anchor: String? = null,
    onAnchorShown: () -> Unit = {},
) {
    val caps = LocalPlatform.current.caps
    KitPage(
        settingsTitle(SettingsPlace(SettingsSection.PrivacyLabs), labs = caps.assistantOverlay),
        modifier,
        scope = { KitMixedScope() },
        anchor = anchor,
        onAnchorShown = onAnchorShown,
        tag = "privacy_settings_screen",
    ) {
        KitSectionHead("Privacy", note = "on ${currentMachineName()}")
        if (!currentMachineOnline()) KitOfflineNote() else TelemetryRows(system)
        if (caps.assistantOverlay) {
            LocalPlatform.current.assistantOverlay?.let { host ->
                KitSectionHead("Labs", note = "on this ${kitDeviceNoun()}")
                AssistantOverlayRows(host)
            }
        }
    }
}

/** The usage-statistics switch, its buttons and the recent-events sheet, for the machine Settings is about. */
@Composable
private fun TelemetryRows(actions: SystemSettingsActions) {
    val c = kitColors()
    val scope = rememberCoroutineScope()
    val platform = LocalPlatform.current
    val machine = machineInSentence()
    val machineId = LocalSettingsMachines.current?.current?.id
    var state by remember(machineId) { mutableStateOf<TelemetryState?>(null) }
    var failed by remember(machineId) { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var recent by remember { mutableStateOf<List<JsonObject>?>(null) }

    LaunchedEffect(machineId, attempt) {
        failed = false
        val got = actions.getTelemetry()
        state = got
        failed = got == null
    }

    if (failed) {
        KitNote("Couldn't reach $machine.", Modifier.testTag("privacy_telemetry_failed"))
        KitTextAction("Try again", onClick = { attempt++ }, modifier = Modifier.padding(top = 4.dp))
        return
    }
    val s = state ?: return
    val locked = telemetryLockedReason(s, machine)
    KitToggleRow(
        label = "Send anonymous usage statistics",
        checked = s.enabled,
        onCheckedChange = { on ->
            saving = true
            error = null
            scope.launch {
                val next = actions.setTelemetry(on)
                if (next != null) state = next else error = "Couldn't change the setting."
                saving = false
            }
        },
        modifier = Modifier.kitAnchor("telemetry").testTag("system_telemetry_switch"),
        description = locked ?: "Which agents, models and features you use. Never prompts, code, paths or names.",
        enabled = locked == null && !saving,
    )
    error?.let { Text(it, fontSize = 12.sp, color = c.danger, modifier = Modifier.padding(top = 6.dp)) }
    KitButtons {
        KitGhostButton(
            "Recent events",
            onClick = { scope.launch { recent = actions.getTelemetryRecent()?.events ?: emptyList() } },
            modifier = Modifier.testTag("system_telemetry_recent"),
        )
        KitGhostButton(
            "What we send",
            onClick = { platform.openUrl(TELEMETRY_DOC_URL) },
            modifier = Modifier.testTag("system_telemetry_doc"),
        )
    }

    recent?.let { events ->
        KitSheet(
            title = "Recent events",
            onDismiss = { recent = null },
            description = "The last events from $machine, exactly as sent.",
            tag = "system_telemetry_recent_dialog",
            buttons = { KitGhostButton("Close", onClick = { recent = null }) },
        ) {
            Column(
                Modifier.padding(top = 12.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (events.isEmpty()) Text("Nothing recorded yet.", fontSize = 12.5.sp, color = c.dim)
                for (e in events) {
                    val sent = (e["sent"] as? JsonPrimitive)?.booleanOrNull == true
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(if (sent) "sent" else "queued", fontSize = 11.5.sp, color = c.faint)
                        Text(
                            prettyJson.encodeToString(JsonElement.serializer(), JsonObject(e - "sent")),
                            fontFamily = MonoFontFamily,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            color = c.text,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The assistant overlay switch (#62, experimental): the default Agent floating over other apps. Turning it on walks
 * through the two permissions it needs, in context: "Display over other apps" (a system page; the switch turns on
 * once you come back with it allowed) and the mic for hold-to-talk (asked, never required: tap-to-chat works
 * without it).
 */
@Composable
private fun AssistantOverlayRows(host: AssistantOverlayHost) {
    val prefs = LocalUiPrefs.current
    val scope = rememberCoroutineScope()
    val on by prefs.assistantOverlay.collectAsState(false)
    var waiting by remember { mutableStateOf(false) }
    // The system page has no result: look again until it is allowed (or the switch is let go).
    LaunchedEffect(waiting) {
        while (waiting) {
            delay(600)
            if (host.canDrawOverOtherApps()) {
                waiting = false
                host.requestMic()
                prefs.putAssistantOverlay(true)
            }
        }
    }
    Column(Modifier.testTag("appearance_overlay_row")) {
        KitToggleRow(
            label = "Assistant overlay",
            checked = on || waiting,
            onCheckedChange = { want ->
                when {
                    !want -> {
                        waiting = false
                        scope.launch { prefs.putAssistantOverlay(false) }
                    }
                    host.canDrawOverOtherApps() -> scope.launch {
                        host.requestMic()
                        prefs.putAssistantOverlay(true)
                    }
                    else -> {
                        waiting = true
                        host.openPermissionPage()
                    }
                }
            },
            modifier = Modifier.kitAnchor("assistant-overlay").testTag("appearance_overlay_switch"),
            description = "Your default Agent floats over other apps: tap it to chat, hold it to talk.",
            badge = "Experimental",
        )
        if (waiting) {
            KitNote("Allow “Display over other apps” for supermux, then come back here.")
            KitTextAction(
                "Open the setting again",
                onClick = { host.openPermissionPage() },
                modifier = Modifier.testTag("appearance_overlay_allow"),
            )
        }
        if (on) {
            val screen by prefs.assistantOverlayScreen.collectAsState(true)
            KitToggleRow(
                label = "Let it see your screen",
                checked = screen,
                onCheckedChange = { scope.launch { prefs.putAssistantOverlayScreen(it) } },
                modifier = Modifier.testTag("appearance_overlay_screen_switch"),
                description = host.screenNote,
                indent = true,
            )
            host.moreSettings?.invoke()
        }
    }
}
