// Memory (#97 spec §2.2, §10.4, §11): the nightly tidy-up of what sessions learned — on or off, when, the next one
// with Run now, and (folded away) which agent, model and effort do it. Today's Curator screen restyled with the
// Settings kit, and saved on change: no Save button, no dirty state. Every change sends the config as it was loaded
// (or last saved) with that one field changed, never Kotlin defaults for the rest; saves run one at a time and the
// newest wins; a failed one puts its control back and says so under it. Nothing saves from a load or an effect.
//
// "Next tidy-up" formats through `:shared`'s `curatorNextRunLabel` — the identical `java.time` output, minus the
// `java.time` import `:ui` commonMain cannot have.
package dev.supermux.ui.settings.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
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
import dev.supermux.net.CuratorConfig
import dev.supermux.net.CuratorSettingsResponse
import dev.supermux.net.ModelInfo
import dev.supermux.net.ReasoningResponse
import dev.supermux.net.showReasoningPicker
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.retain.rememberRetainedOnPage
import dev.supermux.ui.settings.SettingsPlace
import dev.supermux.ui.settings.kit.KitActionRow
import dev.supermux.ui.settings.kit.KitFold
import dev.supermux.ui.settings.kit.KitMachineScope
import dev.supermux.ui.settings.kit.KitNote
import dev.supermux.ui.settings.kit.KitOfflineNote
import dev.supermux.ui.settings.kit.KitPage
import dev.supermux.ui.settings.kit.KitTextAction
import dev.supermux.ui.settings.kit.KitToggleRow
import dev.supermux.ui.settings.kit.KitValueRow
import dev.supermux.ui.settings.kit.LocalSettingsMachines
import dev.supermux.ui.settings.kit.currentMachineOnline
import dev.supermux.ui.settings.kit.kitAnchor
import dev.supermux.ui.settings.kit.kitColors
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem
import dev.supermux.util.curatorNextRunLabel
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.settings.localizedSettingsTitle
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val CURATOR_AGENTS = listOf("claude", "codex", "cursor", "opencode", "grok", "antigravity")

private fun agentLabel(agent: String): String = agent.replaceFirstChar { it.uppercase() }

/**
 * Every broker call the Memory page makes, in one holder.
 *
 * Load and save return the response (null = the call failed), and "Run now" reports whether the broker accepted it.
 */
@Immutable
class CuratorSettingsActions(
    val curatorLoad: suspend () -> CuratorSettingsResponse? = { null },
    val curatorSave: suspend (
        enabled: Boolean,
        hour: Int,
        minute: Int,
        agent: String,
        model: String?,
        reasoningLevel: String?,
    ) -> CuratorSettingsResponse? = { _, _, _, _, _, _ -> null },
    val curatorRunNow: suspend () -> Boolean = { false },
    val loadModels: suspend (agent: String) -> List<ModelInfo> = { emptyList() },
    val loadReasoning: suspend (agent: String, model: String?) -> ReasoningResponse? = { _, _ -> null },
)

/** [CuratorSettingsActions] against one paired host — desktop's wiring. */
@Composable
fun rememberCuratorSettingsActions(app: HostStore): CuratorSettingsActions = remember(app) {
    CuratorSettingsActions(
        curatorLoad = { app.curatorSettings() },
        curatorSave = { enabled, hour, minute, agent, model, reasoning ->
            app.saveCurator(enabled, hour, minute, agent, model, reasoning)
        },
        curatorRunNow = { app.runCuratorNow() },
        loadModels = { agent -> app.launcherModels(agent) },
        loadReasoning = { agent, model -> app.launcherReasoning(agent, model) },
    )
}

/** [CuratorSettingsActions] against the fleet's ACTIVE host — Android's and iOS's wiring. */
@Composable
fun rememberCuratorSettingsActions(fleet: FleetStore): CuratorSettingsActions = remember(fleet) {
    CuratorSettingsActions(
        curatorLoad = { fleet.curatorSettings() },
        curatorSave = { enabled, hour, minute, agent, model, reasoning ->
            fleet.saveCurator(enabled, hour, minute, agent, model, reasoning)
        },
        curatorRunNow = { fleet.runCuratorNow() },
        loadModels = { agent -> fleet.launcherModels(agent) },
        loadReasoning = { agent, model -> fleet.launcherReasoning(agent, model) },
    )
}

@Composable
fun MemoryPage(
    actions: CuratorSettingsActions,
    modifier: Modifier = Modifier,
    anchor: String? = null,
    onAnchorShown: () -> Unit = {},
) {
    KitPage(
        localizedSettingsTitle(SettingsPlace(SettingsSection.Memory)),
        modifier,
        scope = { KitMachineScope() },
        anchor = anchor,
        onAnchorShown = onAnchorShown,
        tag = "curator_settings_screen",
    ) {
        if (!currentMachineOnline()) KitOfflineNote() else MemoryRows(actions)
        val machine = LocalSettingsMachines.current?.current?.name
        KitNote(
            if (machine != null) {
                localized(Str.settings_memory_shared, machine)
            } else {
                localized(Str.settings_memory_shared_this_machine)
            },
        )
    }
}

/** Which control a failed save puts back, so "Couldn't save." shows under it. */
private object Field {
    const val NIGHTLY = "nightly"
    const val TIME = "time"
    const val AGENT = "agent"
    const val MODEL = "model"
    const val EFFORT = "effort"
}

@Composable
private fun MemoryRows(actions: CuratorSettingsActions) {
    val scope = rememberCoroutineScope()
    val couldntReach = pagesCouldntReach()
    val runFailed = localized(Str.settings_memory_run_failed)
    val defaultLabel = localized(Str.settings_pages_default)
    // What was loaded and what is shown, kept for the page on display (#89): a rotation must not reload it.
    val form = rememberRetainedOnPage("curator") { CuratorForm() }
    var models by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var reasoningVisible by remember { mutableStateOf(false) }
    var reasoningOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var runError by remember { mutableStateOf<String?>(null) }
    val saving = remember { Mutex() }

    LaunchedEffect(form.reloadKey) {
        // Loaded already for this key: the page was rebuilt (a rotation, a width change), not retried.
        if (form.loadedKey == form.reloadKey) return@LaunchedEffect
        form.loaded = false
        val r = actions.curatorLoad()
        if (r != null) {
            form.stored = r.config
            form.shown = r.config
            form.nextRun = r.nextRun
            form.loadFailed = false
            form.saveErrorAt = null
        } else {
            form.loadFailed = true
        }
        form.loaded = true
        form.loadedKey = form.reloadKey
    }

    val ready = form.loaded && !form.loadFailed
    val shown = form.shown

    // The lists the pickers offer. They only read: a model the list does not name stays selected (spec §11).
    LaunchedEffect(shown.agent, ready) {
        if (!ready) return@LaunchedEffect
        models = actions.loadModels(shown.agent)
    }
    LaunchedEffect(shown.agent, shown.model, ready) {
        if (!ready) return@LaunchedEffect
        val resp = actions.loadReasoning(shown.agent, shown.model)
        val levels = resp?.levels.orEmpty()
        reasoningVisible = resp != null && resp.visible && showReasoningPicker(levels)
        reasoningOptions = levels.map { it.id to (it.description ?: it.id) }
    }

    /**
     * Shows [next] at once and saves it. One save at a time: a change made while one runs waits for it, and only the
     * newest of those goes out, with the latest values.
     */
    fun change(field: String, next: CuratorConfig) {
        form.shown = next
        form.saveErrorAt = null
        val mine = ++form.changes
        scope.launch {
            saving.withLock {
                if (mine != form.changes) return@withLock
                val sent = form.shown
                val r = actions.curatorSave(sent.enabled, sent.hour, sent.minute, sent.agent, sent.model, sent.reasoningLevel)
                if (r != null) {
                    form.stored = sent
                    form.nextRun = r.nextRun
                } else {
                    form.shown = putBack(form.shown, sent, form.stored)
                    form.saveErrorAt = field
                }
            }
        }
    }

    when {
        !form.loaded -> Unit
        form.loadFailed -> {
            KitNote(couldntReach, Modifier.testTag("assistant_curator_error"))
            KitTextAction(
                localized(Str.settings_pages_try_again),
                onClick = { form.reloadKey++ },
                modifier = Modifier.padding(top = 4.dp).testTag("assistant_curator_retry"),
            )
        }
        else -> {
            KitToggleRow(
                label = localized(Str.settings_memory_nightly),
                checked = shown.enabled,
                onCheckedChange = { change(Field.NIGHTLY, form.shown.copy(enabled = it)) },
                modifier = Modifier.kitAnchor("memory-nightly").testTag("memory_memory-nightly"),
                description = localized(Str.settings_memory_nightly_description),
            )
            SaveError(form.saveErrorAt == Field.NIGHTLY)

            TimeRow(shown, onPick = { h, m -> change(Field.TIME, form.shown.copy(hour = h, minute = m)) })
            SaveError(form.saveErrorAt == Field.TIME)

            KitActionRow(
                label = localized(Str.settings_memory_next),
                button = localized(if (running) Str.settings_memory_running else Str.settings_memory_run_now),
                onClick = {
                    scope.launch {
                        running = true
                        runError = null
                        val ok = actions.curatorRunNow()
                        running = false
                        if (!ok) runError = runFailed
                    }
                },
                modifier = Modifier.kitAnchor("memory-next").testTag("memory_memory-next"),
                description = curatorNextRunLabel(shown.enabled, form.nextRun),
                enabled = !running,
                buttonTag = "assistant_curator_run_now",
                descriptionTag = "assistant_curator_next_run",
            )
            runError?.let { ErrorLine(it, "assistant_curator_run_error") }

            KitFold(localized(Str.settings_pages_advanced), anchors = setOf("memory-agent"), tag = "memory_advanced") {
                PickerRow(
                    label = localized(Str.settings_memory_done_by),
                    value = agentLabel(shown.agent),
                    options = CURATOR_AGENTS.map { it to agentLabel(it) },
                    onPick = { a ->
                        // Another agent's models are not this one's: its model and effort go back to its defaults.
                        if (a != form.shown.agent) change(Field.AGENT, form.shown.copy(agent = a, model = null, reasoningLevel = null))
                    },
                    modifier = Modifier.kitAnchor("memory-agent").testTag("assistant_curator_agent"),
                    description = localized(Str.settings_memory_done_by_description),
                )
                SaveError(form.saveErrorAt == Field.AGENT)
                PickerRow(
                    label = localized(Str.settings_memory_model),
                    value = shown.model?.let { id -> models.firstOrNull { it.id == id }?.displayName ?: id } ?: defaultLabel,
                    options = listOf<Pair<String?, String>>(null to defaultLabel) + models.map { it.id to it.displayName },
                    onPick = { m -> if (m != form.shown.model) change(Field.MODEL, form.shown.copy(model = m)) },
                    modifier = Modifier.testTag("assistant_curator_model"),
                    indent = true,
                )
                SaveError(form.saveErrorAt == Field.MODEL)
                if (reasoningVisible) {
                    PickerRow(
                        label = localized(Str.settings_memory_effort),
                        value = shown.reasoningLevel?.let { id -> reasoningOptions.firstOrNull { it.first == id }?.second ?: id } ?: defaultLabel,
                        options = reasoningOptions,
                        onPick = { l -> if (l != form.shown.reasoningLevel) change(Field.EFFORT, form.shown.copy(reasoningLevel = l)) },
                        modifier = Modifier.testTag("assistant_curator_reasoning"),
                        indent = true,
                    )
                    SaveError(form.saveErrorAt == Field.EFFORT)
                }
            }
        }
    }
}

/**
 * "At ‹HH:MM›": the menu offers the hour, then that hour's minutes; the time saves when a minute is picked (the menu
 * closes). Dim while the tidy-up is off, and still settable.
 */
@Composable
private fun TimeRow(shown: CuratorConfig, onPick: (hour: Int, minute: Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var hour by remember { mutableStateOf<Int?>(null) }
    val close = {
        open = false
        hour = null
    }
    Box {
        KitValueRow(
            label = localized(Str.settings_memory_at),
            value = "${pad2(shown.hour.coerceIn(0, 23))}:${pad2(shown.minute.coerceIn(0, 59))}",
            modifier = Modifier.kitAnchor("memory-time").testTag("memory_memory-time"),
            description = LocalSettingsMachines.current?.current?.name
                ?.let { localized(Str.settings_memory_local_time, it) }
                ?: localized(Str.settings_memory_local_time_this_machine),
            onClick = {
                hour = null
                open = true
            },
            dim = !shown.enabled,
        )
        DropdownMenu(expanded = open, onDismissRequest = close) {
            val h = hour
            if (h == null) {
                (0..23).forEach { hh ->
                    DropdownMenuItem(
                        text = { Text(pad2(hh)) },
                        onClick = { hour = hh },
                        modifier = Modifier.testTag("memory_time_hour_$hh"),
                    )
                }
            } else {
                (0..59).forEach { mm ->
                    DropdownMenuItem(
                        text = { Text("${pad2(h)}:${pad2(mm)}") },
                        onClick = {
                            close()
                            if (h != shown.hour || mm != shown.minute) onPick(h, mm)
                        },
                        modifier = Modifier.testTag("assistant_curator_minute_item_$mm"),
                    )
                }
            }
        }
    }
}

/** A value row whose value is picked from a menu. */
@Composable
private fun <T> PickerRow(
    label: String,
    value: String,
    options: List<Pair<T, String>>,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    indent: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        KitValueRow(label, value, modifier, description = description, onClick = { open = true }, indent = indent)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        open = false
                        onPick(id)
                    },
                )
            }
        }
    }
}

@Composable
private fun SaveError(shown: Boolean) {
    if (shown) ErrorLine(localized(Str.settings_pages_couldnt_save), "assistant_curator_save_error")
}

@Composable
private fun ErrorLine(text: String, tag: String) {
    Text(text, fontSize = 12.sp, color = kitColors().danger, modifier = Modifier.padding(top = 6.dp).testTag(tag))
}

/**
 * After a failed save of [sent]: each field it changed from [stored] goes back to the stored value, unless the user
 * has changed that field again since (that change is waiting to be sent).
 */
private fun putBack(shown: CuratorConfig, sent: CuratorConfig, stored: CuratorConfig): CuratorConfig {
    fun <T> pick(now: T, tried: T, before: T): T = if (now == tried) before else now
    return CuratorConfig(
        enabled = pick(shown.enabled, sent.enabled, stored.enabled),
        hour = pick(shown.hour, sent.hour, stored.hour),
        minute = pick(shown.minute, sent.minute, stored.minute),
        agent = pick(shown.agent, sent.agent, stored.agent),
        model = pick(shown.model, sent.model, stored.model),
        reasoningLevel = pick(shown.reasoningLevel, sent.reasoningLevel, stored.reasoningLevel),
    )
}

/** Two-digit clock field ("07"). */
private fun pad2(n: Int): String = if (n in 0..9) "0$n" else n.toString()

/** The tidy-up's config as loaded / last saved ([stored]) and as shown, kept for the page on display (#89). */
private class CuratorForm {
    var loaded by mutableStateOf(false)
    var loadFailed by mutableStateOf(false)
    var reloadKey by mutableIntStateOf(0)
    /** The [reloadKey] the config was last loaded for: -1 until the first load. */
    var loadedKey = -1
    var stored by mutableStateOf(CuratorConfig())
    var shown by mutableStateOf(CuratorConfig())
    var nextRun by mutableStateOf<String?>(null)
    var saveErrorAt by mutableStateOf<String?>(null)
    /** Counts changes, so a queued save knows a newer one will send the latest values. */
    var changes = 0
}
