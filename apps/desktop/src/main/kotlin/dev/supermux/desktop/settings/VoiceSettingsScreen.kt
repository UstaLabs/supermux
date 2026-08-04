// Ported from apps/android/.../settings/VoiceSettingsScreens.kt.
// Desktop adaptations:
//   - No Scaffold/Back — Settings hub owns navigation
//   - PickerSheet → DropdownMenu (desktop pointer convention)
//   - Glossary is an in-section sub-view (same hub section), not a separate nav route
//   - Swipe-to-delete → Remove button (pointer UI)
//   - sp/dp hardcodes → theme Space / MaterialTheme.typography
//   - Integrates with existing MessageTts (reads voiceTtsEngine from config) — do not duplicate TTS
//   - testTags for compose UI tests + SM_VOICE headless verification
package dev.supermux.desktop.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import dev.supermux.desktop.theme.Radii
import dev.supermux.desktop.theme.Space
import dev.supermux.net.AppConfigDto
import dev.supermux.net.ModelInfo
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

@Composable
fun VoiceSettingsScreen(
    loadConfig: suspend () -> AppConfigDto?,
    loadModels: suspend (family: String) -> List<ModelInfo>,
    saveVoiceStt: suspend (engine: String?) -> Boolean,
    saveVoiceTts: suspend (engine: String?) -> Boolean,
    saveVoiceCleanup: suspend (engine: String?, model: String?) -> Boolean,
    glossaryLoad: suspend () -> List<String>,
    glossarySave: suspend (List<String>) -> List<String>?,
    modifier: Modifier = Modifier,
) {
    var showGlossary by remember { mutableStateOf(false) }
    if (showGlossary) {
        VoiceGlossaryPage(
            load = glossaryLoad,
            save = glossarySave,
            onBack = { showGlossary = false },
            modifier = modifier,
        )
    } else {
        VoiceSettingsMain(
            loadConfig = loadConfig,
            loadModels = loadModels,
            saveVoiceStt = saveVoiceStt,
            saveVoiceTts = saveVoiceTts,
            saveVoiceCleanup = saveVoiceCleanup,
            onOpenGlossary = { showGlossary = true },
            modifier = modifier,
        )
    }
}

@Composable
private fun VoiceSettingsMain(
    loadConfig: suspend () -> AppConfigDto?,
    loadModels: suspend (family: String) -> List<ModelInfo>,
    saveVoiceStt: suspend (engine: String?) -> Boolean,
    saveVoiceTts: suspend (engine: String?) -> Boolean,
    saveVoiceCleanup: suspend (engine: String?, model: String?) -> Boolean,
    onOpenGlossary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var sttEngine by remember { mutableStateOf(DEFAULT_STT_ENGINE) }
    var ttsEngine by remember { mutableStateOf(DEFAULT_TTS_ENGINE) }
    var engine by remember { mutableStateOf(DEFAULT_VOICE_ENGINE) }
    var selectedModel by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var showStt by remember { mutableStateOf(false) }
    var showTts by remember { mutableStateOf(false) }
    var showEngine by remember { mutableStateOf(false) }
    var showModel by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val cfg = loadConfig()
        if (cfg == null) {
            loadError = true
            loading = false
            return@LaunchedEffect
        }
        sttEngine = cfg.voiceSttEngine?.ifBlank { null } ?: DEFAULT_STT_ENGINE
        ttsEngine = cfg.voiceTtsEngine?.ifBlank { null } ?: DEFAULT_TTS_ENGINE
        engine = cfg.voiceCleanupEngine?.ifBlank { null } ?: DEFAULT_VOICE_ENGINE
        selectedModel = cfg.voiceCleanupModel ?: ""
        models = loadModels(voiceEngineFamily(engine))
        loading = false
    }

    Box(
        modifier
            .fillMaxSize()
            .background(cs.background)
            .testTag("voice_settings_screen"),
        contentAlignment = Alignment.TopCenter,
    ) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = cs.primary,
                    modifier = Modifier.testTag("voice_settings_loading"),
                )
            }
            loadError -> Box(
                Modifier
                    .widthIn(max = SettingsDetailMaxWidth)
                    .fillMaxSize()
                    .padding(Space.xl)
                    .testTag("voice_settings_error"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Couldn't load voice settings.",
                    color = cs.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            else -> Column(
                Modifier
                    .widthIn(max = SettingsDetailMaxWidth)
                    .fillMaxWidth()
                    .fillMaxSize()
                    .testTag("voice_settings_content"),
            ) {
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
                            sttEngine = picked
                            scope.launch { saveVoiceStt(picked) }
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
                    ValueChip(
                        text = ttsEngineLabel(ttsEngine).take(28),
                        expanded = showTts,
                        onExpand = { showTts = true },
                        onDismiss = { showTts = false },
                        options = TTS_ENGINES.map { it.id to it.label },
                        current = ttsEngine,
                        onPick = { picked ->
                            ttsEngine = picked
                            scope.launch { saveVoiceTts(picked) }
                        },
                        testTag = "voice_tts_chip",
                    )
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
                            engine = picked
                            selectedModel = ""
                            scope.launch {
                                saveVoiceCleanup(picked, "")
                                models = loadModels(voiceEngineFamily(picked))
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
                            selectedModel = picked
                            scope.launch { saveVoiceCleanup(null, picked) }
                        },
                        testTag = "voice_cleanup_model_chip",
                    )
                }
                HorizontalDivider(color = cs.outlineVariant)

                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenGlossary)
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
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = cs.onSurfaceVariant,
                    )
                }
                HorizontalDivider(color = cs.outlineVariant)
            }
        }
    }
}

@Composable
private fun VoiceGlossaryPage(
    load: suspend () -> List<String>,
    save: suspend (List<String>) -> List<String>?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val terms = remember { mutableStateListOf<String>() }
    var newTerm by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        terms.addAll(load())
        loading = false
    }

    fun persist() {
        val snapshot = terms.toList()
        scope.launch {
            val saved = save(snapshot)
            if (saved == null) {
                // Reload first, then replace the list atomically so the UI never
                // lands on an intermediate empty state (avoids flaky "reverted"
                // assertions and brief empty flashes under concurrent load).
                val reloaded = load()
                terms.clear()
                terms.addAll(reloaded)
                error = "Couldn't save — reverted"
            } else {
                error = null
            }
        }
    }

    fun add() {
        val t = newTerm.trim()
        if (t.isEmpty() || terms.any { it.equals(t, ignoreCase = true) }) {
            newTerm = ""
            return
        }
        terms.add(t)
        newTerm = ""
        persist()
    }

    Column(
        modifier
            .fillMaxSize()
            .background(cs.background)
            .testTag("voice_glossary_screen"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.testTag("voice_glossary_back"),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                Text(" Voice")
            }
            Text(
                "Voice glossary",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        HorizontalDivider(color = cs.outlineVariant)

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = SettingsDetailMaxWidth)
                    .fillMaxSize()
                    .padding(horizontal = Space.lg),
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
                        colors = settingsFieldColors(),
                    )
                    IconButton(
                        onClick = { add() },
                        enabled = newTerm.isNotBlank(),
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

                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = cs.primary,
                            modifier = Modifier.testTag("voice_glossary_loading"),
                        )
                    }
                    terms.isEmpty() -> Text(
                        "No terms yet — add the names dictation keeps getting wrong.",
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .padding(vertical = Space.lg)
                            .testTag("voice_glossary_empty"),
                    )
                    else -> LazyColumn(
                        Modifier.fillMaxSize().testTag("voice_glossary_list"),
                        contentPadding = PaddingValues(bottom = Space.xl),
                    ) {
                        items(terms, key = { it }) { term ->
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
        Box(
            Modifier
                .clip(RoundedCornerShape(Radii.pill))
                .background(cs.surfaceContainer)
                .clickable(onClick = onExpand)
                .padding(horizontal = Space.md, vertical = Space.sm)
                .testTag(testTag),
        ) {
            Text(
                text,
                color = cs.onSurface,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
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
