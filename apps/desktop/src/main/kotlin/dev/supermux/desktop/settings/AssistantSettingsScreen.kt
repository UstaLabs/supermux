// Ported from apps/android/.../settings/AssistantSettingsScreen.kt + CuratorSettingsPage
// (MoreScreens.kt) and iOS CuratorSettingsView.swift.
// Desktop adaptations:
//   - No Scaffold/Back — Settings hub owns navigation
//   - Soul + Curator side-by-side (Identity | Curator) so Save stays on-screen
//   - sp/dp hardcodes → theme Space / Stroke / MaterialTheme.typography
//   - Failure ≠ empty: Error + Retry (Proxies pattern); never Save unloaded soul
//   - testTags for compose UI tests + SM_ASSISTANT headless verification
package dev.supermux.desktop.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.desktop.theme.Radii
import dev.supermux.desktop.theme.Space
import dev.supermux.desktop.theme.Stroke
import dev.supermux.net.CuratorSettingsResponse
import dev.supermux.net.ModelInfo
import dev.supermux.net.ReasoningResponse
import dev.supermux.net.resolveReasoningLevel
import dev.supermux.net.showReasoningPicker
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val ERROR_AUTO_RETRY_MS = 3_000L
private val CURATOR_AGENTS = listOf("claude", "codex", "cursor", "opencode", "grok")

/** Load model for assistant identity (PA name + soul). */
internal sealed class AssistantLoadState {
    data object Loading : AssistantLoadState()
    data class Ready(val paName: String, val soul: String) : AssistantLoadState()
    data class Error(val message: String) : AssistantLoadState()
}

@Composable
fun AssistantSettingsScreen(
    /** Load (paName, soul) or null on failure. */
    assistantLoad: suspend () -> Pair<String, String>?,
    /**
     * Save paName + soul.
     * Returns null on success; a human-readable error when either write fails.
     */
    assistantSave: suspend (paName: String, soul: String) -> String?,
    curatorLoad: suspend () -> CuratorSettingsResponse?,
    curatorSave: suspend (
        enabled: Boolean,
        hour: Int,
        minute: Int,
        agent: String,
        model: String?,
        reasoningLevel: String?,
    ) -> CuratorSettingsResponse?,
    curatorRunNow: suspend () -> Boolean,
    loadModels: suspend (agent: String) -> List<ModelInfo>,
    loadReasoning: suspend (agent: String, model: String?) -> ReasoningResponse?,
    /** Report whether the identity editor has unsaved edits (hub Escape/Back dirty guard). */
    onDirtyChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var loadState by remember { mutableStateOf<AssistantLoadState>(AssistantLoadState.Loading) }
    var reloadKey by remember { mutableStateOf(0) }
    var loadedPaName by remember { mutableStateOf("") }
    var loadedSoul by remember { mutableStateOf("") }
    var paName by remember { mutableStateOf("") }
    var soul by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var showSaveConfirm by remember { mutableStateOf(false) }

    // Curator state
    var curatorLoaded by remember { mutableStateOf(false) }
    var curatorError by remember { mutableStateOf(false) }
    var curatorReloadKey by remember { mutableStateOf(0) }
    var enabled by remember { mutableStateOf(false) }
    var hour by remember { mutableStateOf(1) }
    var minute by remember { mutableStateOf(0) }
    var agent by remember { mutableStateOf("claude") }
    var model by remember { mutableStateOf<String?>(null) }
    var reasoningLevel by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var reasoningVisible by remember { mutableStateOf(false) }
    var reasoningOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var nextRun by remember { mutableStateOf<String?>(null) }
    var curatorSaving by remember { mutableStateOf(false) }
    var curatorSaved by remember { mutableStateOf(false) }
    var curatorSaveError by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var runError by remember { mutableStateOf<String?>(null) }
    var agentMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var reasoningMenu by remember { mutableStateOf(false) }
    var hourMenu by remember { mutableStateOf(false) }
    var minuteMenu by remember { mutableStateOf(false) }

    val isDirty = loadState is AssistantLoadState.Ready &&
        (paName != loadedPaName || soul != loadedSoul)
    LaunchedEffect(isDirty) { onDirtyChange(isDirty) }

    suspend fun loadIdentityOnce() {
        val previous = loadState
        if (previous !is AssistantLoadState.Ready) {
            loadState = AssistantLoadState.Loading
        }
        val pair = assistantLoad()
        if (pair == null) {
            loadState = AssistantLoadState.Error("Couldn't load assistant settings.")
        } else {
            loadedPaName = pair.first
            loadedSoul = pair.second
            paName = pair.first
            soul = pair.second
            saved = false
            saveError = null
            loadState = AssistantLoadState.Ready(pair.first, pair.second)
        }
    }

    suspend fun loadCuratorOnce() {
        curatorLoaded = false
        val r = curatorLoad()
        if (r != null) {
            enabled = r.config.enabled
            hour = r.config.hour.coerceIn(0, 23)
            minute = r.config.minute.coerceIn(0, 59)
            agent = r.config.agent.takeIf { it in CURATOR_AGENTS } ?: "claude"
            model = r.config.model
            reasoningLevel = r.config.reasoningLevel
            nextRun = r.nextRun
            curatorError = false
            curatorSaveError = null
            runError = null
        } else {
            curatorError = true
        }
        curatorLoaded = true
    }

    LaunchedEffect(reloadKey) { loadIdentityOnce() }
    LaunchedEffect(curatorReloadKey) { loadCuratorOnce() }

    LaunchedEffect(loadState, reloadKey) {
        if (loadState !is AssistantLoadState.Error) return@LaunchedEffect
        while (isActive) {
            delay(ERROR_AUTO_RETRY_MS)
            val pair = assistantLoad()
            if (pair != null) {
                loadedPaName = pair.first
                loadedSoul = pair.second
                paName = pair.first
                soul = pair.second
                loadState = AssistantLoadState.Ready(pair.first, pair.second)
                break
            }
        }
    }

    LaunchedEffect(agent, curatorLoaded) {
        if (!curatorLoaded || curatorError) return@LaunchedEffect
        models = loadModels(agent)
        if (model != null && models.none { it.id == model }) model = null
    }

    LaunchedEffect(agent, model, curatorLoaded) {
        if (!curatorLoaded || curatorError) return@LaunchedEffect
        val resp = loadReasoning(agent, model)
        val levels = resp?.levels.orEmpty()
        reasoningVisible = resp != null && resp.visible && showReasoningPicker(levels)
        reasoningOptions = levels.map { it.id to (it.description ?: it.id) }
        reasoningLevel = if (reasoningVisible) resolveReasoningLevel(levels, reasoningLevel) else null
    }

    fun doSave() {
        scope.launch {
            saving = true
            saved = false
            saveError = null
            val err = assistantSave(paName, soul)
            saving = false
            if (err == null) {
                loadedPaName = paName
                loadedSoul = soul
                saved = true
                delay(2000)
                saved = false
            } else {
                saveError = err
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(cs.background)
            .testTag("assistant_settings_screen"),
        contentAlignment = Alignment.TopCenter,
    ) {
        when (val state = loadState) {
            is AssistantLoadState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        color = cs.primary,
                        modifier = Modifier.testTag("assistant_settings_loading"),
                    )
                }
            }
            is AssistantLoadState.Error -> {
                Column(
                    Modifier
                        .widthIn(max = SettingsDetailMaxWidth)
                        .fillMaxWidth()
                        .padding(Space.xl)
                        .testTag("assistant_settings_error"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    Text(
                        state.message,
                        color = cs.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = { reloadKey++ },
                        modifier = Modifier.testTag("assistant_settings_retry"),
                    ) { Text("Retry") }
                }
            }
            is AssistantLoadState.Ready -> {
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(Space.lg)
                        .testTag("assistant_settings_content"),
                    horizontalArrangement = Arrangement.spacedBy(Space.lg),
                ) {
                    // ── Identity column ──────────────────────────────────────────
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .testTag("assistant_identity_column"),
                        verticalArrangement = Arrangement.spacedBy(Space.md),
                    ) {
                        SettingsSectionHeader(title = "Identity")
                        OutlinedTextField(
                            value = paName,
                            onValueChange = { paName = it; saved = false; saveError = null },
                            label = { Text("PA name") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .submitOnEnter(paName.isNotBlank() && !saving) {
                                    if (isDirty) showSaveConfirm = true
                                }
                                .testTag("assistant_pa_name"),
                            colors = settingsFieldColors(),
                        )
                        Text(
                            "soul.md",
                            color = cs.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        OutlinedTextField(
                            value = soul,
                            onValueChange = { soul = it; saved = false; saveError = null },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp, max = 320.dp)
                                .testTag("assistant_soul"),
                            minLines = 6,
                            maxLines = 16,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
                            colors = settingsFieldColors(),
                        )
                        SettingsCaption("Personality, instructions, and persistent context prepended to every session.")
                        saveError?.let {
                            Text(
                                it,
                                color = cs.error,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.testTag("assistant_save_error"),
                            )
                        }
                        Button(
                            onClick = { showSaveConfirm = true },
                            enabled = !saving,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("assistant_save"),
                            colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
                        ) {
                            when {
                                saving -> {
                                    CircularProgressIndicator(
                                        color = cs.onPrimary,
                                        strokeWidth = Stroke.md,
                                        modifier = Modifier.size(Space.lg),
                                    )
                                    Spacer(Modifier.width(Space.sm))
                                    Text("Saving…", color = cs.onPrimary)
                                }
                                saved -> {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = cs.onPrimary,
                                        modifier = Modifier.size(Space.lg),
                                    )
                                    Spacer(Modifier.width(Space.sm))
                                    Text("Saved", color = cs.onPrimary)
                                }
                                else -> Text("Save", color = cs.onPrimary)
                            }
                        }
                    }

                    VerticalDivider(color = cs.outlineVariant, modifier = Modifier.fillMaxHeight())

                    // ── Curator column ───────────────────────────────────────────
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .testTag("assistant_curator_column"),
                        verticalArrangement = Arrangement.spacedBy(Space.sm),
                    ) {
                        SettingsSectionHeader(title = "Curator")

                        if (!curatorLoaded) {
                            CircularProgressIndicator(
                                color = cs.primary,
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(Space.xl)
                                    .testTag("assistant_curator_loading"),
                            )
                        } else if (curatorError) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(Space.md)
                                    .testTag("assistant_curator_error"),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(Space.md),
                            ) {
                                Text(
                                    "Couldn't load curator settings.",
                                    color = cs.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                OutlinedButton(
                                    onClick = { curatorReloadKey++ },
                                    modifier = Modifier.testTag("assistant_curator_retry"),
                                ) { Text("Retry") }
                            }
                        } else {
                            CuratorRow(
                                label = "Nightly curator",
                                desc = "Curate ~/.mux daily, commit + push, and post a digest.",
                            ) {
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = { enabled = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = cs.onPrimary,
                                        checkedTrackColor = cs.primary,
                                    ),
                                    modifier = Modifier.testTag("assistant_curator_enabled"),
                                )
                            }
                            HorizontalDivider(color = cs.outlineVariant)
                            CuratorRow(
                                label = "Run at",
                                desc = "Daily, host local time.",
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box {
                                        PickerChip(
                                            label = "%02d".format(hour),
                                            onClick = { hourMenu = true },
                                            testTag = "assistant_curator_hour",
                                        )
                                        DropdownMenu(expanded = hourMenu, onDismissRequest = { hourMenu = false }) {
                                            (0..23).forEach { h ->
                                                DropdownMenuItem(
                                                    text = { Text("%02d".format(h)) },
                                                    onClick = { hour = h; hourMenu = false },
                                                )
                                            }
                                        }
                                    }
                                    Text(":", color = cs.onSurface)
                                    Box {
                                        PickerChip(
                                            label = "%02d".format(minute),
                                            onClick = { minuteMenu = true },
                                            testTag = "assistant_curator_minute",
                                        )
                                        DropdownMenu(expanded = minuteMenu, onDismissRequest = { minuteMenu = false }) {
                                            (0..59).forEach { m ->
                                                DropdownMenuItem(
                                                    text = { Text("%02d".format(m)) },
                                                    onClick = { minute = m; minuteMenu = false },
                                                    modifier = Modifier.testTag("assistant_curator_minute_item_$m"),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            HorizontalDivider(color = cs.outlineVariant)
                            CuratorRow(
                                label = "Agent",
                                desc = "Which agent runs the nightly curation.",
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box {
                                        PickerChip(
                                            label = agent.replaceFirstChar { it.uppercase() },
                                            onClick = { agentMenu = true },
                                            testTag = "assistant_curator_agent",
                                        )
                                        DropdownMenu(expanded = agentMenu, onDismissRequest = { agentMenu = false }) {
                                            CURATOR_AGENTS.forEach { a ->
                                                DropdownMenuItem(
                                                    text = { Text(a.replaceFirstChar { it.uppercase() }) },
                                                    onClick = {
                                                        if (a != agent) {
                                                            agent = a
                                                            model = null
                                                            reasoningLevel = null
                                                        }
                                                        agentMenu = false
                                                    },
                                                )
                                            }
                                        }
                                    }
                                    val modelLabel = model?.let { id ->
                                        models.firstOrNull { it.id == id }?.displayName ?: id
                                    } ?: "Default"
                                    Box {
                                        PickerChip(
                                            label = modelLabel.take(20),
                                            onClick = { modelMenu = true },
                                            testTag = "assistant_curator_model",
                                        )
                                        DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                                            DropdownMenuItem(
                                                text = { Text("Default") },
                                                onClick = { model = null; modelMenu = false },
                                            )
                                            models.forEach { m ->
                                                DropdownMenuItem(
                                                    text = { Text(m.displayName) },
                                                    onClick = { model = m.id; modelMenu = false },
                                                )
                                            }
                                        }
                                    }
                                    if (reasoningVisible) {
                                        Box {
                                            PickerChip(
                                                label = reasoningLevel?.replaceFirstChar { it.uppercase() } ?: "Default",
                                                onClick = { reasoningMenu = true },
                                                testTag = "assistant_curator_reasoning",
                                            )
                                            DropdownMenu(
                                                expanded = reasoningMenu,
                                                onDismissRequest = { reasoningMenu = false },
                                            ) {
                                                reasoningOptions.forEach { (id, label) ->
                                                    DropdownMenuItem(
                                                        text = { Text(label) },
                                                        onClick = {
                                                            reasoningLevel = id
                                                            reasoningMenu = false
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            HorizontalDivider(color = cs.outlineVariant)
                            CuratorRow(
                                label = "Next run",
                                desc = "The digest notifies all your devices.",
                            ) {
                                Text(
                                    curatorNextRunLabel(enabled, nextRun),
                                    color = cs.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.testTag("assistant_curator_next_run"),
                                )
                            }
                            HorizontalDivider(color = cs.outlineVariant)
                            curatorSaveError?.let {
                                Text(
                                    it,
                                    color = cs.error,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.testTag("assistant_curator_save_error"),
                                )
                            }
                            runError?.let {
                                Text(
                                    it,
                                    color = cs.error,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.testTag("assistant_curator_run_error"),
                                )
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = Space.md),
                                horizontalArrangement = Arrangement.spacedBy(Space.sm),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            curatorSaving = true
                                            curatorSaved = false
                                            curatorSaveError = null
                                            val r = curatorSave(
                                                enabled, hour, minute, agent, model, reasoningLevel,
                                            )
                                            if (r != null) {
                                                nextRun = r.nextRun
                                                curatorSaved = true
                                                delay(2000)
                                                curatorSaved = false
                                            } else {
                                                curatorSaveError = "Couldn't save curator settings."
                                            }
                                            curatorSaving = false
                                        }
                                    },
                                    enabled = !curatorSaving,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("assistant_curator_save"),
                                    colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
                                ) {
                                    Text(
                                        when {
                                            curatorSaving -> "Saving…"
                                            curatorSaved -> "Saved"
                                            else -> "Save"
                                        },
                                        color = cs.onPrimary,
                                    )
                                }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            running = true
                                            runError = null
                                            val ok = curatorRunNow()
                                            running = false
                                            if (!ok) {
                                                runError = "Couldn't start curator run."
                                            }
                                        }
                                    },
                                    enabled = !running,
                                    modifier = Modifier.testTag("assistant_curator_run_now"),
                                ) {
                                    Icon(
                                        Icons.Filled.PlayArrow,
                                        contentDescription = null,
                                        tint = cs.onSurface,
                                        modifier = Modifier.size(Space.lg),
                                    )
                                    Spacer(Modifier.width(Space.sm))
                                    Text(if (running) "Starting…" else "Run now", color = cs.onSurface)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSaveConfirm) {
        AlertDialog(
            onDismissRequest = { if (!saving) showSaveConfirm = false },
            title = { Text("Overwrite soul.md?") },
            text = {
                Text(
                    "This replaces the assistant identity on the broker " +
                        "(PA name and soul.md). Continue?",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        showSaveConfirm = false
                        doSave()
                    },
                    modifier = Modifier.testTag("assistant_save_confirm"),
                ) { Text("Overwrite") }
            },
            dismissButton = {
                TextButton(
                    enabled = !saving,
                    onClick = { showSaveConfirm = false },
                    modifier = Modifier.testTag("assistant_save_cancel"),
                ) { Text("Cancel") }
            },
            modifier = Modifier.testTag("assistant_save_dialog"),
        )
    }
}

@Composable
private fun CuratorRow(
    label: String,
    desc: String,
    trailing: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = cs.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                desc,
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        trailing()
    }
}

/** Outlined picker trigger (not a permanently-selected FilterChip). */
@Composable
private fun PickerChip(label: String, onClick: () -> Unit, testTag: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .clip(RoundedCornerShape(Radii.sm))
            .background(cs.surfaceContainer)
            .border(Stroke.thin, cs.outline, RoundedCornerShape(Radii.sm))
            .clickable(onClick = onClick)
            .padding(horizontal = Space.md, vertical = Space.sm)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(
            label,
            color = cs.onSurface,
            fontFamily = MonoFontFamily,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("▾", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}

/** Mirrors Android curatorNextRunLabel: disabled / formatted local datetime / raw / —. */
internal fun curatorNextRunLabel(enabled: Boolean, nextRun: String?): String {
    if (!enabled) return "Disabled"
    val raw = nextRun ?: return "—"
    return runCatching {
        val dt = LocalDateTime.ofInstant(Instant.parse(raw), ZoneId.systemDefault())
        dt.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    }.getOrNull() ?: raw
}
