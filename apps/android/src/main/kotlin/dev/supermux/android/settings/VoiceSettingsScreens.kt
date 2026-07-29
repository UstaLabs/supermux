package dev.supermux.android.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.R
import dev.supermux.android.chat.PickerSheet
import kotlinx.coroutines.launch

// ─── Voice settings (STT engine + cleanup engine + model + glossary link) ───────
//
// Speech engine: broker multipart /transcribe backend (codex-realtime / claude-voice /
// whisper). Cleanup engine: direct-API adapter that polishes drafts (Codex default,
// OpenCode Zen/Go, Cursor). The gated Claude cleanup adapter is intentionally not
// offered here. Rows reuse the shared M3 PickerSheet and persist immediately on pick.
// Switching cleanup engine drops the now-irrelevant model and reloads the model list.

private data class VoiceEngine(val id: String, val label: String, val family: String)

// STT engines — mirror STT_ENGINES in src/core/transcription/stt-types.ts.
private data class SttEngine(val id: String, val label: String)
private val STT_ENGINES = listOf(
    SttEngine("codex-realtime", "Codex Realtime (ChatGPT)"),
    SttEngine("claude-voice", "Claude Code voice"),
    SttEngine("cursor-stt", "Cursor voice"),
    SttEngine("whisper", "Whisper (local)"),
)
private const val DEFAULT_STT_ENGINE = "codex-realtime"
private fun sttEngineLabel(id: String): String = STT_ENGINES.firstOrNull { it.id == id }?.label ?: id

// Read-aloud engines — mirror TTS_ENGINES in src/core/tts/tts-types.ts.
private val TTS_ENGINES = listOf(
    SttEngine("platform", "Device (system voice)"),
    SttEngine("codex", "ChatGPT (Codex login)"),
)
private const val DEFAULT_TTS_ENGINE = "platform"
private fun ttsEngineLabel(id: String): String = TTS_ENGINES.firstOrNull { it.id == id }?.label ?: id

// Curated mirror of ENGINES in src/core/agent-api/index.ts. `family` is the
// AgentKind whose models GET /models?agent= returns for that engine.
private val VOICE_ENGINES = listOf(
    VoiceEngine("codex", "Codex", "codex"),
    VoiceEngine("opencode-zen", "OpenCode Zen", "opencode"),
    VoiceEngine("opencode-go", "OpenCode Go", "opencode"),
    VoiceEngine("cursor", "Cursor", "cursor"),
)
private const val DEFAULT_VOICE_ENGINE = "codex"
private fun voiceEngineFamily(id: String): String = VOICE_ENGINES.firstOrNull { it.id == id }?.family ?: "codex"
private fun voiceEngineLabel(id: String): String = VOICE_ENGINES.firstOrNull { it.id == id }?.label ?: id

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsPage(
    onBack: () -> Unit,
    loadModels: suspend (family: String) -> List<dev.supermux.net.ModelInfo>,
    loadConfig: suspend () -> dev.supermux.net.AppConfigDto?,
    saveVoiceStt: (engine: String?) -> Unit,
    saveVoiceTts: (engine: String?) -> Unit = {},
    saveVoiceCleanup: (engine: String?, model: String?) -> Unit,
    onOpenGlossary: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<dev.supermux.net.ModelInfo>>(emptyList()) }
    var sttEngine by remember { mutableStateOf(DEFAULT_STT_ENGINE) }
    var ttsEngine by remember { mutableStateOf(DEFAULT_TTS_ENGINE) }
    var engine by remember { mutableStateOf(DEFAULT_VOICE_ENGINE) }
    var selectedModel by remember { mutableStateOf("") }   // "" = the engine's default
    var loading by remember { mutableStateOf(true) }
    var showSttPicker by remember { mutableStateOf(false) }
    var showTtsPicker by remember { mutableStateOf(false) }
    var showEnginePicker by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val cfg = loadConfig()
        sttEngine = cfg?.voiceSttEngine?.ifBlank { null } ?: DEFAULT_STT_ENGINE
        ttsEngine = cfg?.voiceTtsEngine?.ifBlank { null } ?: DEFAULT_TTS_ENGINE
        engine = cfg?.voiceCleanupEngine?.ifBlank { null } ?: DEFAULT_VOICE_ENGINE
        selectedModel = cfg?.voiceCleanupModel ?: ""
        models = loadModels(voiceEngineFamily(engine))
        loading = false
    }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice", color = cs.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = cs.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surfaceContainerHigh),
            )
        },
        containerColor = cs.background,
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = cs.primary)
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding)) {
                // Row 1: Speech (STT) engine → tappable chip → picker.
                VoiceSettingRow(
                    label = "Speech engine",
                    desc = "Cloud STT for uploaded mic audio. Claude Code voice needs a Claude.ai login.",
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(cs.surfaceContainer)
                            .clickable { showSttPicker = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(sttEngineLabel(sttEngine).take(28), color = cs.onSurface, fontSize = 13.sp, maxLines = 1)
                    }
                }
                HorizontalDivider(color = cs.outlineVariant)

                // Row 2: Read aloud engine
                VoiceSettingRow(
                    label = "Read aloud",
                    desc = "Device uses the OS voice. ChatGPT needs a Codex login.",
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(cs.surfaceContainer)
                            .clickable { showTtsPicker = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(ttsEngineLabel(ttsEngine).take(28), color = cs.onSurface, fontSize = 13.sp, maxLines = 1)
                    }
                }
                HorizontalDivider(color = cs.outlineVariant)

                // Row 3: Cleanup engine → tappable value chip → picker.
                VoiceSettingRow(
                    label = "Cleanup engine",
                    desc = "Direct-API agent that cleans up voice-dictation transcripts.",
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(cs.surfaceContainer)
                            .clickable { showEnginePicker = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(voiceEngineLabel(engine).take(24), color = cs.onSurface, fontSize = 13.sp, maxLines = 1)
                    }
                }
                HorizontalDivider(color = cs.outlineVariant)

                // Row 3: Cleanup model (for the selected engine) → tappable chip → picker.
                val modelLabel =
                    if (selectedModel.isEmpty()) "Default"
                    else models.firstOrNull { it.id == selectedModel }?.displayName ?: selectedModel
                VoiceSettingRow(
                    label = "Cleanup model",
                    desc = "Model for ${voiceEngineLabel(engine)}. Default uses the engine's own.",
                ) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(cs.surfaceContainer)
                            .clickable { showModelPicker = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(modelLabel.take(24), color = cs.onSurface, fontSize = 13.sp, maxLines = 1)
                    }
                }
                HorizontalDivider(color = cs.outlineVariant)

                // Row 4: Dictation glossary → glossary editor.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenGlossary)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Dictation glossary", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "Project & technical terms to bias dictation toward (shared across devices).",
                            color = cs.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                    Icon(
                        painterResource(R.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                HorizontalDivider(color = cs.outlineVariant)
            }
        }
    }

    if (showSttPicker) {
        PickerSheet(
            title = "Speech engine",
            options = STT_ENGINES.map { it.id to it.label },
            current = sttEngine,
            onPick = { picked ->
                sttEngine = picked
                saveVoiceStt(picked)
            },
            onDismiss = { showSttPicker = false },
        )
    }
    if (showTtsPicker) {
        PickerSheet(
            title = "Read aloud",
            options = TTS_ENGINES.map { it.id to it.label },
            current = ttsEngine,
            onPick = { picked ->
                ttsEngine = picked
                saveVoiceTts(picked)
            },
            onDismiss = { showTtsPicker = false },
        )
    }
    if (showEnginePicker) {
        PickerSheet(
            title = "Cleanup engine",
            options = VOICE_ENGINES.map { it.id to it.label },
            current = engine,
            onPick = { picked ->
                engine = picked
                selectedModel = ""                 // drop the stale, engine-specific model
                saveVoiceCleanup(picked, "")       // set engine + reset model to default
                scope.launch { models = loadModels(voiceEngineFamily(picked)) }
            },
            onDismiss = { showEnginePicker = false },
        )
    }
    if (showModelPicker) {
        // "" → the engine's default + the engine's models.
        val options = listOf("" to "Default") + models.map { it.id to it.displayName }
        PickerSheet(
            title = "Cleanup model",
            options = options,
            current = selectedModel,
            onPick = { picked ->
                selectedModel = picked
                saveVoiceCleanup(null, picked)     // engine unchanged; "" resets to default
            },
            onDismiss = { showModelPicker = false },
        )
    }
}

/** Settings row: label + desc on the left, a trailing control slot on the right. */
@Composable
private fun VoiceSettingRow(
    label: String,
    desc: String,
    trailing: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(desc, color = cs.onSurfaceVariant, fontSize = 11.sp)
        }
        trailing()
    }
}

// ─── Voice glossary editor (add + swipe-to-delete, persist on every edit) ──────
//
// Parity with iOS GlossaryView: add field at the top, swipe-to-delete rows, every
// edit persists via updateGlossary; on save failure the list reloads (reverts) so
// two devices don't silently clobber each other.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceGlossaryPage(
    onBack: () -> Unit,
    load: suspend () -> List<String>,
    save: suspend (List<String>) -> List<String>?,   // returns persisted list or null on failure
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
                error = "Couldn't save — reverted"
                terms.clear()
                terms.addAll(load())
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

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice glossary", color = cs.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = cs.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surfaceContainerHigh),
            )
        },
        containerColor = cs.background,
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // Add row: OutlinedTextField + Add IconButton (autocorrect off, no autocapitalize).
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newTerm,
                    onValueChange = { newTerm = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Add a term (e.g. Supermux)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                )
                IconButton(onClick = { add() }, enabled = newTerm.isNotBlank()) {
                    Icon(painterResource(R.drawable.ic_plus), contentDescription = "Add term", tint = cs.primary)
                }
            }
            Text(
                "Terms the agent keeps spelled exactly, and that dictation is biased toward.",
                color = cs.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            error?.let {
                Text(it, color = cs.error, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
            }

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = cs.primary)
                }
                terms.isEmpty() -> Text(
                    "No terms yet — add the names dictation keeps getting wrong.",
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(terms, key = { it }) { term ->
                        val dismiss = rememberSwipeToDismissBoxState(
                            confirmValueChange = {
                                if (it != SwipeToDismissBoxValue.Settled) {
                                    terms.remove(term)
                                    persist()
                                    true
                                } else {
                                    false
                                }
                            },
                        )
                        SwipeToDismissBox(
                            state = dismiss,
                            backgroundContent = {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(cs.error)
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_trash),
                                        contentDescription = "Delete",
                                        tint = cs.onError,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            },
                        ) {
                            ListItem(
                                headlineContent = { Text(term, color = cs.onSurface) },
                                colors = ListItemDefaults.colors(containerColor = cs.surface),
                            )
                        }
                        HorizontalDivider(color = cs.outlineVariant)
                    }
                }
            }
        }
    }
}
