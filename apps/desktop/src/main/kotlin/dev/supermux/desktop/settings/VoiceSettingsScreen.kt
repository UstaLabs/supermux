// Ported from apps/android/.../settings/VoiceSettingsScreens.kt.
// Desktop adaptations:
//   - No Scaffold/Back — Settings hub owns navigation
//   - Glossary is an in-section expand (rail owns nav; no push-subpage)
//   - Picker chips have border + chevron (not status-badge pills)
//   - Failure ≠ empty for glossary; Error + Retry like Proxies
//   - Engine save failures revert the chip and surface an error
//   - testTags for compose UI tests + SM_VOICE headless verification
package dev.supermux.desktop.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import dev.supermux.desktop.chat.MessageTts
import dev.supermux.desktop.theme.Radii
import dev.supermux.desktop.theme.Space
import dev.supermux.desktop.theme.Stroke
import dev.supermux.net.AppConfigDto
import dev.supermux.net.ModelInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private data class SttEngine(val id: String, val label: String)
private data class VoiceEngine(val id: String, val label: String, val family: String)

// STT engines — mirror STT_ENGINES in src/core/transcription/stt-types.ts / Android VoiceSettings.
private val STT_ENGINES = listOf(
    SttEngine("codex-realtime", "Codex Realtime (ChatGPT)"),
    SttEngine("claude-voice", "Claude Code voice"),
    SttEngine("cursor-stt", "Cursor voice"),
    SttEngine("whisper", "Whisper (local)"),
)
private const val DEFAULT_STT_ENGINE = "codex-realtime"
internal fun sttEngineLabel(id: String): String = STT_ENGINES.firstOrNull { it.id == id }?.label ?: id

// Read-aloud engines — MessageTts uses platform (say/espeak) or codex (broker /speak).
private val TTS_ENGINES = listOf(
    SttEngine("platform", "Device (system voice)"),
    SttEngine("codex", "ChatGPT (Codex login)"),
)
private const val DEFAULT_TTS_ENGINE = "platform"
internal fun ttsEngineLabel(id: String): String = TTS_ENGINES.firstOrNull { it.id == id }?.label ?: id

// Cleanup engines — family drives GET /models?agent=
private val VOICE_ENGINES = listOf(
    VoiceEngine("codex", "Codex", "codex"),
    VoiceEngine("opencode-zen", "OpenCode Zen", "opencode"),
    VoiceEngine("opencode-go", "OpenCode Go", "opencode"),
    VoiceEngine("cursor", "Cursor", "cursor"),
)
private const val DEFAULT_VOICE_ENGINE = "codex"
internal fun voiceEngineFamily(id: String): String =
    VOICE_ENGINES.firstOrNull { it.id == id }?.family ?: "codex"
internal fun voiceEngineLabel(id: String): String =
    VOICE_ENGINES.firstOrNull { it.id == id }?.label ?: id

private const val ERROR_AUTO_RETRY_MS = 3_000L
private const val PREVIEW_TTS_SAMPLE = "Hello from Supermux."

/** Load model for voice config. */
internal sealed class VoiceLoadState {
    data object Loading : VoiceLoadState()
    data object Ready : VoiceLoadState()
    data class Error(val message: String) : VoiceLoadState()
}

/** Load model for glossary — failure is distinct from a legitimate empty list. */
internal sealed class GlossaryLoadState {
    data object Loading : GlossaryLoadState()
    data object Empty : GlossaryLoadState()
    data class Ready(val terms: List<String>) : GlossaryLoadState()
    data class Error(val message: String) : GlossaryLoadState()
}

@Composable
fun VoiceSettingsScreen(
    loadConfig: suspend () -> AppConfigDto?,
    loadModels: suspend (family: String) -> List<ModelInfo>,
    saveVoiceStt: suspend (engine: String?) -> Boolean,
    saveVoiceTts: suspend (engine: String?) -> Boolean,
    saveVoiceCleanup: suspend (engine: String?, model: String?) -> Boolean,
    /** Null = failure; empty = no terms; never collapse failure into empty. */
    glossaryLoad: suspend () -> List<String>?,
    glossarySave: suspend (List<String>) -> List<String>?,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var sttEngine by remember { mutableStateOf(DEFAULT_STT_ENGINE) }
    var ttsEngine by remember { mutableStateOf(DEFAULT_TTS_ENGINE) }
    var engine by remember { mutableStateOf(DEFAULT_VOICE_ENGINE) }
    var selectedModel by remember { mutableStateOf("") }
    var loadState by remember { mutableStateOf<VoiceLoadState>(VoiceLoadState.Loading) }
    var reloadKey by remember { mutableStateOf(0) }
    var showStt by remember { mutableStateOf(false) }
    var showTts by remember { mutableStateOf(false) }
    var showEngine by remember { mutableStateOf(false) }
    var showModel by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var glossaryExpanded by remember { mutableStateOf(false) }

    suspend fun loadOnce() {
        if (loadState !is VoiceLoadState.Ready) {
            loadState = VoiceLoadState.Loading
        }
        val cfg = loadConfig()
        if (cfg == null) {
            loadState = VoiceLoadState.Error("Couldn't load voice settings.")
            return
        }
        sttEngine = cfg.voiceSttEngine?.ifBlank { null } ?: DEFAULT_STT_ENGINE
        ttsEngine = cfg.voiceTtsEngine?.ifBlank { null } ?: DEFAULT_TTS_ENGINE
        engine = cfg.voiceCleanupEngine?.ifBlank { null } ?: DEFAULT_VOICE_ENGINE
        selectedModel = cfg.voiceCleanupModel ?: ""
        models = loadModels(voiceEngineFamily(engine))
        saveError = null
        loadState = VoiceLoadState.Ready
    }

    LaunchedEffect(reloadKey) { loadOnce() }

    LaunchedEffect(loadState, reloadKey) {
        if (loadState !is VoiceLoadState.Error) return@LaunchedEffect
        while (isActive) {
            delay(ERROR_AUTO_RETRY_MS)
            val cfg = loadConfig()
            if (cfg != null) {
                sttEngine = cfg.voiceSttEngine?.ifBlank { null } ?: DEFAULT_STT_ENGINE
                ttsEngine = cfg.voiceTtsEngine?.ifBlank { null } ?: DEFAULT_TTS_ENGINE
                engine = cfg.voiceCleanupEngine?.ifBlank { null } ?: DEFAULT_VOICE_ENGINE
                selectedModel = cfg.voiceCleanupModel ?: ""
                models = loadModels(voiceEngineFamily(engine))
                loadState = VoiceLoadState.Ready
                break
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(cs.background)
            .testTag("voice_settings_screen"),
        contentAlignment = Alignment.TopCenter,
    ) {
        when (val state = loadState) {
            is VoiceLoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = cs.primary,
                    modifier = Modifier.testTag("voice_settings_loading"),
                )
            }
            is VoiceLoadState.Error -> Column(
                Modifier
                    .widthIn(max = SettingsDetailMaxWidth)
                    .fillMaxWidth()
                    .padding(Space.xl)
                    .testTag("voice_settings_error"),
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
                    modifier = Modifier.testTag("voice_settings_retry"),
                ) { Text("Retry") }
            }
            is VoiceLoadState.Ready -> Column(
                Modifier
                    .widthIn(max = SettingsDetailMaxWidth)
                    .fillMaxWidth()
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .testTag("voice_settings_content"),
            ) {
                saveError?.let { err ->
                    Text(
                        err,
                        color = cs.error,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .padding(horizontal = Space.lg, vertical = Space.sm)
                            .testTag("voice_save_error"),
                    )
                }
                VoiceSettingRow(
                    label = "Speech engine",
                    desc = "Cloud STT for uploaded mic audio. Claude Code voice needs a Claude.ai login.",
                    testTag = "voice_stt_row",
                ) {
                    ValueChip(
                        text = sttEngineLabel(sttEngine).take(28),
                        expanded = showStt,
                        onExpand = { showStt = true },
                        onDismiss = { showStt = false },
                        options = STT_ENGINES.map { it.id to it.label },
                        current = sttEngine,
                        onPick = { picked ->
                            val previous = sttEngine
                            sttEngine = picked
                            scope.launch {
                                val ok = saveVoiceStt(picked)
                                if (!ok) {
                                    sttEngine = previous
                                    saveError = "Couldn't save speech engine."
                                } else {
                                    saveError = null
                                }
                            }
                        },
                        testTag = "voice_stt_chip",
                    )
                }
                HorizontalDivider(color = cs.outlineVariant)

                VoiceSettingRow(
                    label = "Read aloud",
                    desc = "Device uses the OS voice. ChatGPT needs a Codex login.",
                    testTag = "voice_tts_row",
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ValueChip(
                            text = ttsEngineLabel(ttsEngine).take(28),
                            expanded = showTts,
                            onExpand = { showTts = true },
                            onDismiss = { showTts = false },
                            options = TTS_ENGINES.map { it.id to it.label },
                            current = ttsEngine,
                            onPick = { picked ->
                                val previous = ttsEngine
                                ttsEngine = picked
                                scope.launch {
                                    val ok = saveVoiceTts(picked)
                                    if (!ok) {
                                        ttsEngine = previous
                                        saveError = "Couldn't save read-aloud engine."
                                    } else {
                                        saveError = null
                                    }
                                }
                            },
                            testTag = "voice_tts_chip",
                        )
                        TextButton(
                            onClick = { MessageTts.toggle(PREVIEW_TTS_SAMPLE) },
                            modifier = Modifier.testTag("voice_tts_preview"),
                        ) {
                            Text("Preview", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                HorizontalDivider(color = cs.outlineVariant)

                VoiceSettingRow(
                    label = "Cleanup engine",
                    desc = "Direct-API agent that cleans up voice-dictation transcripts.",
                    testTag = "voice_cleanup_engine_row",
                ) {
                    ValueChip(
                        text = voiceEngineLabel(engine).take(24),
                        expanded = showEngine,
                        onExpand = { showEngine = true },
                        onDismiss = { showEngine = false },
                        options = VOICE_ENGINES.map { it.id to it.label },
                        current = engine,
                        onPick = { picked ->
                            val previousEngine = engine
                            val previousModel = selectedModel
                            engine = picked
                            selectedModel = ""
                            scope.launch {
                                val ok = saveVoiceCleanup(picked, "")
                                if (!ok) {
                                    engine = previousEngine
                                    selectedModel = previousModel
                                    saveError = "Couldn't save cleanup engine."
                                } else {
                                    saveError = null
                                    models = loadModels(voiceEngineFamily(picked))
                                }
                            }
                        },
                        testTag = "voice_cleanup_engine_chip",
                    )
                }
                HorizontalDivider(color = cs.outlineVariant)

                val modelLabel =
                    if (selectedModel.isEmpty()) "Default"
                    else models.firstOrNull { it.id == selectedModel }?.displayName ?: selectedModel
                VoiceSettingRow(
                    label = "Cleanup model",
                    desc = "Model for ${voiceEngineLabel(engine)}. Default uses the engine's own.",
                    testTag = "voice_cleanup_model_row",
                ) {
                    ValueChip(
                        text = modelLabel.take(24),
                        expanded = showModel,
                        onExpand = { showModel = true },
                        onDismiss = { showModel = false },
                        options = listOf("" to "Default") + models.map { it.id to it.displayName },
                        current = selectedModel,
                        onPick = { picked ->
                            val previous = selectedModel
                            selectedModel = picked
                            scope.launch {
                                val ok = saveVoiceCleanup(null, picked)
                                if (!ok) {
                                    selectedModel = previous
                                    saveError = "Couldn't save cleanup model."
                                } else {
                                    saveError = null
                                }
                            }
                        },
                        testTag = "voice_cleanup_model_chip",
                    )
                }
                HorizontalDivider(color = cs.outlineVariant)

                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { glossaryExpanded = !glossaryExpanded }
                        .padding(horizontal = Space.lg, vertical = Space.md)
                        .testTag("voice_glossary_link"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Dictation glossary",
                            color = cs.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "Project & technical terms to bias dictation toward (shared across devices).",
                            color = cs.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Icon(
                        if (glossaryExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (glossaryExpanded) "Collapse" else "Expand",
                        tint = cs.onSurfaceVariant,
                    )
                }
                HorizontalDivider(color = cs.outlineVariant)

                if (glossaryExpanded) {
                    VoiceGlossarySection(
                        load = glossaryLoad,
                        save = glossarySave,
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceGlossarySection(
    load: suspend () -> List<String>?,
    save: suspend (List<String>) -> List<String>?,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val terms = remember { mutableStateListOf<String>() }
    var newTerm by remember { mutableStateOf("") }
    var loadState by remember { mutableStateOf<GlossaryLoadState>(GlossaryLoadState.Loading) }
    var reloadKey by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun loadOnce() {
        if (loadState !is GlossaryLoadState.Ready && loadState !is GlossaryLoadState.Empty) {
            loadState = GlossaryLoadState.Loading
        }
        val result = load()
        if (result == null) {
            terms.clear()
            loadState = GlossaryLoadState.Error("Couldn't load glossary.")
        } else {
            terms.clear()
            terms.addAll(result)
            loadState = if (result.isEmpty()) GlossaryLoadState.Empty else GlossaryLoadState.Ready(result)
            error = null
        }
    }

    LaunchedEffect(reloadKey) { loadOnce() }

    LaunchedEffect(loadState, reloadKey) {
        if (loadState !is GlossaryLoadState.Error) return@LaunchedEffect
        while (isActive) {
            delay(ERROR_AUTO_RETRY_MS)
            val result = load()
            if (result != null) {
                terms.clear()
                terms.addAll(result)
                loadState = if (result.isEmpty()) GlossaryLoadState.Empty else GlossaryLoadState.Ready(result)
                break
            }
        }
    }

    fun persist() {
        val snapshot = terms.toList()
        scope.launch {
            val saved = save(snapshot)
            if (saved == null) {
                // Only reload when the load succeeds — never replace with empty-on-failure.
                val reloaded = load()
                if (reloaded != null) {
                    terms.clear()
                    terms.addAll(reloaded)
                    loadState = if (reloaded.isEmpty()) {
                        GlossaryLoadState.Empty
                    } else {
                        GlossaryLoadState.Ready(reloaded)
                    }
                }
                error = "Couldn't save — reverted"
            } else {
                error = null
                terms.clear()
                terms.addAll(saved)
                loadState = if (saved.isEmpty()) GlossaryLoadState.Empty else GlossaryLoadState.Ready(saved)
            }
        }
    }

    fun add() {
        val t = newTerm.trim()
        if (t.isEmpty() || terms.any { it.equals(t, ignoreCase = true) }) {
            newTerm = ""
            return
        }
        // Refuse add while load failed — would overwrite real glossary with one term.
        if (loadState is GlossaryLoadState.Error || loadState is GlossaryLoadState.Loading) return
        terms.add(t)
        newTerm = ""
        loadState = GlossaryLoadState.Ready(terms.toList())
        persist()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg)
            .testTag("voice_glossary_screen"),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            OutlinedTextField(
                value = newTerm,
                onValueChange = { newTerm = it },
                modifier = Modifier
                    .weight(1f)
                    .submitOnEnter(newTerm.isNotBlank()) { add() }
                    .testTag("voice_glossary_input"),
                placeholder = { Text("Add a term (e.g. Supermux)") },
                singleLine = true,
                enabled = loadState !is GlossaryLoadState.Error && loadState !is GlossaryLoadState.Loading,
                colors = settingsFieldColors(),
            )
            IconButton(
                onClick = { add() },
                enabled = newTerm.isNotBlank() &&
                    loadState !is GlossaryLoadState.Error &&
                    loadState !is GlossaryLoadState.Loading,
                modifier = Modifier.testTag("voice_glossary_add"),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add term", tint = cs.primary)
            }
        }
        Text(
            "Terms the agent keeps spelled exactly, and that dictation is biased toward.",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        error?.let {
            Text(
                it,
                color = cs.error,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .padding(vertical = Space.sm)
                    .testTag("voice_glossary_error"),
            )
        }

        when (val g = loadState) {
            is GlossaryLoadState.Loading -> Box(
                Modifier.fillMaxWidth().padding(Space.lg),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = cs.primary,
                    modifier = Modifier.testTag("voice_glossary_loading"),
                )
            }
            is GlossaryLoadState.Error -> Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = Space.lg)
                    .testTag("voice_glossary_load_error"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                Text(
                    g.message,
                    color = cs.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = { reloadKey++ },
                    modifier = Modifier.testTag("voice_glossary_retry"),
                ) { Text("Retry") }
            }
            is GlossaryLoadState.Empty -> Text(
                "No terms yet — add the names dictation keeps getting wrong.",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(vertical = Space.lg)
                    .testTag("voice_glossary_empty"),
            )
            is GlossaryLoadState.Ready -> Column(
                Modifier
                    .fillMaxWidth()
                    .testTag("voice_glossary_list"),
            ) {
                terms.forEach { term ->
                    val tagSafe = term.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = Space.sm)
                            .testTag("voice_glossary_term_$tagSafe"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            term,
                            color = cs.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                terms.remove(term)
                                if (terms.isEmpty()) {
                                    loadState = GlossaryLoadState.Empty
                                } else {
                                    loadState = GlossaryLoadState.Ready(terms.toList())
                                }
                                persist()
                            },
                            modifier = Modifier.testTag("voice_glossary_remove_$tagSafe"),
                        ) {
                            Text("Remove", color = cs.error)
                        }
                    }
                    HorizontalDivider(color = cs.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun VoiceSettingRow(
    label: String,
    desc: String,
    testTag: String,
    trailing: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg, vertical = Space.md)
            .testTag(testTag),
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

@Composable
private fun ValueChip(
    text: String,
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    options: List<Pair<String, String>>,
    current: String,
    onPick: (String) -> Unit,
    testTag: String,
) {
    val cs = MaterialTheme.colorScheme
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(Radii.sm))
                .background(cs.surfaceContainer)
                .border(Stroke.thin, cs.outline, RoundedCornerShape(Radii.sm))
                .clickable(onClick = onExpand)
                .padding(horizontal = Space.md, vertical = Space.sm)
                .testTag(testTag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Text(
                text,
                color = cs.onSurface,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            Text("▾", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            options.forEach { (id, label) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            fontWeight = if (id == current) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        onPick(id)
                        onDismiss()
                    },
                    modifier = Modifier.testTag("${testTag}_option_${id.ifBlank { "default" }}"),
                )
            }
        }
    }
}
