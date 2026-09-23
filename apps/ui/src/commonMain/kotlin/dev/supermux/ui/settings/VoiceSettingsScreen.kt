// The one Voice settings screen for both apps (cluster E5).
//
// Base = desktop's `settings/VoiceSettingsScreen.kt`: the STT / read-aloud / cleanup-engine /
// cleanup-model rows, the load Error+Retry with a 3s auto-retry, the "save failed → revert the chip
// and say so" behaviour, and a glossary whose failure is never collapsed into an empty list.
// Android's `VoiceSettingsScreens.kt` (`VoiceSettingsPage` + `VoiceGlossaryPage`) contributes the
// Compact branch: its `TopAppBar` when nothing above painted one, and — the reason this screen is
// not just desktop's — the glossary as a PUSHED SUB-PAGE rather than an inline expand.
//
// Compact glossary back, and why the hub stays generic: the sub-page is a stack local to this
// screen, so it owns its own [BackHandler]. The hub's compact `BackHandler` is registered first
// (it composes above this content), and the navigation dispatcher runs the most recently added
// enabled callback first — so while the glossary is open the system back gesture lands here and
// returns to the voice page; the next one falls through to the hub and returns to the index. No
// hub hook, no nested-stack protocol, nothing for the other sections to opt out of.
//
// Android additions folded in: swipe-to-delete on a glossary row (touch only — the Remove button
// stays for pointers and for TalkBack), the IME `Done` action that adds a term, and the push
// chevron. Everything Android hardcoded in sp/dp is on the shared tokens; `R.drawable` icons are
// Material icons.
package dev.supermux.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.supermux.net.AppConfigDto
import dev.supermux.net.ModelInfo
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.MessageTts
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.Stroke
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem
import dev.supermux.ui.widgets.IosBackSwipe
import dev.supermux.ui.widgets.SettingsDetailMaxWidth
import dev.supermux.ui.widgets.SwipeBackHandler
import dev.supermux.ui.widgets.SwipeBackPages
import dev.supermux.ui.widgets.rememberIosBackSwipe
import dev.supermux.ui.widgets.settingsFieldColors
import dev.supermux.ui.widgets.submitOnEnter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Material's minimum touch target, applied to the value chips when there is no pointer. */
private val TouchTargetMin = 48.dp

private data class SttEngine(val id: String, val label: String)
private data class VoiceEngine(val id: String, val label: String, val family: String)

// STT engines — mirror STT_ENGINES in src/core/transcription/stt-types.ts.
private val STT_ENGINES = listOf(
    SttEngine("codex-realtime", "Codex Realtime (ChatGPT)"),
    SttEngine("claude-voice", "Claude Code voice"),
    SttEngine("cursor-stt", "Cursor voice"),
    SttEngine("whisper", "Whisper (local)"),
)
private const val DEFAULT_STT_ENGINE = "codex-realtime"
internal fun sttEngineLabel(id: String): String = STT_ENGINES.firstOrNull { it.id == id }?.label ?: id

// Read-aloud engines — MessageTts uses platform (say/espeak/Android TTS) or codex (broker /speak).
private val TTS_ENGINES = listOf(
    SttEngine("platform", "Device (system voice)"),
    SttEngine("codex", "ChatGPT (Codex login)"),
)
private const val DEFAULT_TTS_ENGINE = "platform"
internal fun ttsEngineLabel(id: String): String = TTS_ENGINES.firstOrNull { it.id == id }?.label ?: id

// Cleanup engines — a curated mirror of ENGINES in src/core/agent-api/index.ts. `family` is the
// AgentKind whose models GET /models?agent= returns for that engine.
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

/** Load model for the glossary — failure is distinct from a legitimate empty list. */
internal sealed class GlossaryLoadState {
    data object Loading : GlossaryLoadState()
    data object Empty : GlossaryLoadState()
    data class Ready(val terms: List<String>) : GlossaryLoadState()
    data class Error(val message: String) : GlossaryLoadState()
}

/**
 * Every broker call the Voice screen makes, in one holder.
 *
 * Shapes are desktop's: the three saves report success so a failed pick reverts its chip, and
 * `glossaryLoad` returns null for a FAILED load — never an empty list, which a save would then
 * write back over the real glossary. Android's fire-and-forget `Unit` wrappers were retyped to
 * match (`FleetStore.saveVoiceStt/Tts/Cleanup`, `fetchGlossary`).
 */
@Immutable
class VoiceSettingsActions(
    val loadConfig: suspend () -> AppConfigDto? = { null },
    val loadModels: suspend (family: String) -> List<ModelInfo> = { emptyList() },
    val saveVoiceStt: suspend (engine: String?) -> Boolean = { false },
    val saveVoiceTts: suspend (engine: String?) -> Boolean = { false },
    val saveVoiceCleanup: suspend (engine: String?, model: String?) -> Boolean = { _, _ -> false },
    /** Null = failure; empty = no terms; never collapse failure into empty. */
    val glossaryLoad: suspend () -> List<String>? = { null },
    val glossarySave: suspend (List<String>) -> List<String>? = { null },
)

/** [VoiceSettingsActions] against one paired host — desktop's wiring. */
@Composable
fun rememberVoiceSettingsActions(app: HostStore): VoiceSettingsActions = remember(app) {
    VoiceSettingsActions(
        loadConfig = { app.appConfig() },
        loadModels = { family -> app.launcherModels(family) },
        saveVoiceStt = { engine -> app.saveVoiceStt(engine) },
        saveVoiceTts = { engine -> app.saveVoiceTts(engine) },
        saveVoiceCleanup = { engine, model -> app.saveVoiceCleanup(engine, model) },
        glossaryLoad = { app.fetchGlossary() },
        glossarySave = { terms -> app.updateGlossary(terms) },
    )
}

/** [VoiceSettingsActions] against the fleet's ACTIVE host — Android's wiring. */
@Composable
fun rememberVoiceSettingsActions(fleet: FleetStore): VoiceSettingsActions = remember(fleet) {
    VoiceSettingsActions(
        loadConfig = { fleet.appConfig() },
        loadModels = { family -> fleet.launcherModels(family) },
        saveVoiceStt = { engine -> fleet.saveVoiceStt(engine) },
        saveVoiceTts = { engine -> fleet.saveVoiceTts(engine) },
        saveVoiceCleanup = { engine, model -> fleet.saveVoiceCleanup(engine, model) },
        glossaryLoad = { fleet.fetchGlossary() },
        glossarySave = { terms -> fleet.updateGlossary(terms) },
    )
}

/**
 * Voice: speech engine, read-aloud engine, cleanup engine + model, and the dictation glossary.
 *
 * @param onBack leave the screen; only reachable from the top bar this screen paints for itself
 *   (pass the hub's `SettingsSlotScope.onClose`).
 * @param topBarShown something above already painted a `TopAppBar` for this detail.
 * @param standalone this is its own route rather than a hub section, so it needs a title and Back
 *   at every width — not only under Compact.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun VoiceSettingsScreen(
    actions: VoiceSettingsActions,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    topBarShown: Boolean = false,
    standalone: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val compact = LocalWindowWidthClass.current == WindowWidthClass.Compact
    // Compact pushes the glossary as a sub-page (Android); anything wider expands it in place
    // (desktop — the rail owns navigation, so there is nowhere to push to).
    val pushed = compact
    var glossaryOpen by remember { mutableStateOf(false) }
    val subPage = pushed && glossaryOpen

    // The sub-page's own back, registered BELOW the hub's — so it runs first and the gesture
    // returns to the voice page instead of collapsing the whole section.
    val glossarySwipe = rememberIosBackSwipe()
    SwipeBackHandler(enabled = subPage, swipe = glossarySwipe) { glossaryOpen = false }

    if ((standalone || compact) && !topBarShown) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(if (subPage) "Voice glossary" else "Voice", color = cs.onSurface)
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = { if (subPage) glossaryOpen = false else onBack() },
                            modifier = Modifier.testTag(
                                if (subPage) "voice_glossary_back" else "voice_settings_back",
                            ),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = cs.onSurface,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = cs.surfaceContainerHigh,
                    ),
                )
            },
            containerColor = cs.background,
        ) { padding ->
            VoiceSettingsBody(
                actions = actions,
                pushed = pushed,
                glossaryOpen = glossaryOpen,
                onGlossaryOpenChange = { glossaryOpen = it },
                // The bar above already carries the sub-page's Back.
                subPageBackInBody = false,
                glossarySwipe = glossarySwipe,
                modifier = modifier.padding(padding),
            )
        }
    } else {
        VoiceSettingsBody(
            actions = actions,
            pushed = pushed,
            glossaryOpen = glossaryOpen,
            onGlossaryOpenChange = { glossaryOpen = it },
            // Someone else painted the chrome, and their Back leaves the whole section — so the
            // sub-page carries its own, in the body, or a phone would be stuck on the glossary.
            subPageBackInBody = true,
            glossarySwipe = glossarySwipe,
            modifier = modifier,
        )
    }
}

@Composable
private fun VoiceSettingsBody(
    actions: VoiceSettingsActions,
    pushed: Boolean,
    glossaryOpen: Boolean,
    onGlossaryOpenChange: (Boolean) -> Unit,
    subPageBackInBody: Boolean,
    glossarySwipe: IosBackSwipe,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    // Remembered HERE, above both pages: the engine saves launch into this scope, and a scope
    // remembered inside the voice page would be disposed the moment the glossary is pushed —
    // cancelling a save that is still in flight.
    val scope = rememberCoroutineScope()

    if (!pushed) {
        VoiceMainPage(actions, pushed, glossaryOpen, onGlossaryOpenChange, scope, modifier)
        return
    }
    // The pushed sub-page covers the voice page (Android's behaviour), which is only composed again
    // under an iOS back swipe or once the glossary closes: coming back reloads the config, exactly
    // as re-entering `VoiceSettingsPage` did.
    SwipeBackPages(
        pushed = glossaryOpen,
        swipe = glossarySwipe,
        pageBackground = cs.background,
        modifier = modifier,
        under = { VoiceMainPage(actions, pushed, glossaryOpen, onGlossaryOpenChange, scope, Modifier) },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(cs.background)
                .testTag("voice_glossary_page"),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = SettingsDetailMaxWidth)
                    .fillMaxWidth()
                    .fillMaxSize()
                    // A glossary longer than the phone screen has to be reachable: Android's page
                    // was a LazyColumn, and the sub-page is the only scroll container here.
                    .verticalScroll(rememberScrollState()),
            ) {
                if (subPageBackInBody) {
                    TextButton(
                        onClick = { onGlossaryOpenChange(false) },
                        modifier = Modifier
                            .padding(start = Space.sm, top = Space.sm)
                            .testTag("voice_glossary_back"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = cs.onSurface,
                            modifier = Modifier.size(Space.lg),
                        )
                        Text(
                            "Voice",
                            color = cs.onSurface,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = Space.xs),
                        )
                    }
                }
                VoiceGlossarySection(
                    load = actions.glossaryLoad,
                    save = actions.glossarySave,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun VoiceMainPage(
    actions: VoiceSettingsActions,
    pushed: Boolean,
    glossaryOpen: Boolean,
    onGlossaryOpenChange: (Boolean) -> Unit,
    scope: CoroutineScope,
    modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val previewTts = LocalPlatform.current.tts
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

    suspend fun loadOnce() {
        if (loadState !is VoiceLoadState.Ready) {
            loadState = VoiceLoadState.Loading
        }
        val cfg = actions.loadConfig()
        if (cfg == null) {
            loadState = VoiceLoadState.Error("Couldn't load voice settings.")
            return
        }
        sttEngine = cfg.voiceSttEngine?.ifBlank { null } ?: DEFAULT_STT_ENGINE
        ttsEngine = cfg.voiceTtsEngine?.ifBlank { null } ?: DEFAULT_TTS_ENGINE
        engine = cfg.voiceCleanupEngine?.ifBlank { null } ?: DEFAULT_VOICE_ENGINE
        selectedModel = cfg.voiceCleanupModel ?: ""
        models = actions.loadModels(voiceEngineFamily(engine))
        saveError = null
        loadState = VoiceLoadState.Ready
    }

    LaunchedEffect(reloadKey) { loadOnce() }

    LaunchedEffect(loadState, reloadKey) {
        if (loadState !is VoiceLoadState.Error) return@LaunchedEffect
        while (isActive) {
            delay(ERROR_AUTO_RETRY_MS)
            val cfg = actions.loadConfig()
            if (cfg != null) {
                sttEngine = cfg.voiceSttEngine?.ifBlank { null } ?: DEFAULT_STT_ENGINE
                ttsEngine = cfg.voiceTtsEngine?.ifBlank { null } ?: DEFAULT_TTS_ENGINE
                engine = cfg.voiceCleanupEngine?.ifBlank { null } ?: DEFAULT_VOICE_ENGINE
                selectedModel = cfg.voiceCleanupModel ?: ""
                models = actions.loadModels(voiceEngineFamily(engine))
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
                                val ok = actions.saveVoiceStt(picked)
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
                                    val ok = actions.saveVoiceTts(picked)
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
                            onClick = { MessageTts.toggle(previewTts, PREVIEW_TTS_SAMPLE) },
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
                                val ok = actions.saveVoiceCleanup(picked, "")
                                if (!ok) {
                                    engine = previousEngine
                                    selectedModel = previousModel
                                    saveError = "Couldn't save cleanup engine."
                                } else {
                                    saveError = null
                                    models = actions.loadModels(voiceEngineFamily(picked))
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
                                val ok = actions.saveVoiceCleanup(null, picked)
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

                val pointer = LocalPointerAvailable.current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (pushed) onGlossaryOpenChange(true) else onGlossaryOpenChange(!glossaryOpen)
                        }
                        .padding(
                            horizontal = Space.lg,
                            vertical = if (pointer) Space.md else Space.lg,
                        )
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
                    // Pushing means a chevron (Android); expanding in place means a caret.
                    Icon(
                        when {
                            pushed -> Icons.AutoMirrored.Filled.KeyboardArrowRight
                            glossaryOpen -> Icons.Filled.ExpandLess
                            else -> Icons.Filled.ExpandMore
                        },
                        contentDescription = when {
                            pushed -> "Open"
                            glossaryOpen -> "Collapse"
                            else -> "Expand"
                        },
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.testTag("voice_glossary_chevron"),
                    )
                }
                HorizontalDivider(color = cs.outlineVariant)

                if (!pushed && glossaryOpen) {
                    VoiceGlossarySection(
                        load = actions.glossaryLoad,
                        save = actions.glossarySave,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceGlossarySection(
    load: suspend () -> List<String>?,
    save: suspend (List<String>) -> List<String>?,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val pointer = LocalPointerAvailable.current
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

    fun removeTerm(term: String) {
        terms.remove(term)
        loadState = if (terms.isEmpty()) {
            GlossaryLoadState.Empty
        } else {
            GlossaryLoadState.Ready(terms.toList())
        }
        persist()
    }

    Column(
        modifier
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
                // Android's IME contract: no autocorrect on technical terms, Done adds the term.
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { add() }),
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
                    if (pointer) {
                        GlossaryTermRow(term, tagSafe) { removeTerm(term) }
                    } else {
                        // Android's swipe-to-delete, for fingers only. `key` keeps the dismiss
                        // state with its term as the list changes underneath.
                        key(term) {
                            val dismiss = rememberSwipeToDismissBoxState(
                                confirmValueChange = {
                                    if (it != SwipeToDismissBoxValue.Settled) {
                                        removeTerm(term)
                                        true
                                    } else {
                                        false
                                    }
                                },
                            )
                            SwipeToDismissBox(
                                state = dismiss,
                                modifier = Modifier.testTag("voice_glossary_swipe_$tagSafe"),
                                backgroundContent = {
                                    Box(
                                        Modifier
                                            .fillMaxSize()
                                            .background(cs.error)
                                            .padding(horizontal = Space.lg),
                                        contentAlignment = Alignment.CenterEnd,
                                    ) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "Delete",
                                            tint = cs.onError,
                                            modifier = Modifier.size(Space.lg),
                                        )
                                    }
                                },
                            ) {
                                Box(Modifier.background(cs.background)) {
                                    GlossaryTermRow(term, tagSafe) { removeTerm(term) }
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = cs.outlineVariant)
                }
            }
        }
    }
}

/** One glossary term: the name, and the Remove button pointers and screen readers use. */
@Composable
private fun GlossaryTermRow(term: String, tagSafe: String, onRemove: () -> Unit) {
    val cs = MaterialTheme.colorScheme
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
            onClick = onRemove,
            modifier = Modifier.testTag("voice_glossary_remove_$tagSafe"),
        ) {
            Text("Remove", color = cs.error)
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
    // Touch rows sit further apart: keyed on LocalPointerAvailable, NOT LocalInputMode — a phone
    // with a keyboard attached still taps with a finger.
    val pointer = LocalPointerAvailable.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg, vertical = if (pointer) Space.md else Space.lg)
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
    val pointer = LocalPointerAvailable.current
    Box {
        Row(
            Modifier
                .then(if (pointer) Modifier else Modifier.heightIn(min = TouchTargetMin))
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
