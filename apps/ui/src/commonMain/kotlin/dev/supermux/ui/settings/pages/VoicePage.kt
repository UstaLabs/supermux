// Voice (#97 spec §2.2, §10.7, §11): how speech becomes text on the machine, the languages it listens for, the
// words it spells your way (a sub-page), how replies are read aloud and, folded away, the tidy-up of dictated
// text; then, where this device has its own recogniser, where speech is turned into text. Today's Voice screen
// restyled with the Settings kit: each control saves the moment it changes, and a failed save puts it back and
// says so under it. The config and the word list load together, so the word count is there with the rows.
package dev.supermux.ui.settings.pages

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.AppConfigDto
import dev.supermux.net.ModelInfo
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.ui.chat.DictationMode
import dev.supermux.ui.chat.MessageTts
import dev.supermux.ui.chat.resolveDictationMode
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.prefs.LocalUiPrefsOrNull
import dev.supermux.ui.retain.rememberRetainedOnPage
import dev.supermux.ui.settings.GLOSSARY_SUB
import dev.supermux.ui.settings.SettingsPlace
import dev.supermux.ui.settings.kit.KitFold
import dev.supermux.ui.settings.kit.KitGhostButton
import dev.supermux.ui.settings.kit.KitGlyphs
import dev.supermux.ui.settings.kit.KitMachineScope
import dev.supermux.ui.settings.kit.KitNote
import dev.supermux.ui.settings.kit.KitOfflineNote
import dev.supermux.ui.settings.kit.KitOptionsRow
import dev.supermux.ui.settings.kit.KitPage
import dev.supermux.ui.settings.kit.KitRow
import dev.supermux.ui.settings.kit.KitSectionHead
import dev.supermux.ui.settings.kit.KitSheet
import dev.supermux.ui.settings.kit.KitTextAction
import dev.supermux.ui.settings.kit.KitToggleRow
import dev.supermux.ui.settings.kit.KitValueRow
import dev.supermux.ui.settings.kit.LocalSettingsMachines
import dev.supermux.ui.settings.kit.currentMachineOnline
import dev.supermux.ui.settings.kit.kitAnchor
import dev.supermux.ui.settings.kit.kitColors
import dev.supermux.ui.settings.kit.kitDeviceNoun
import dev.supermux.ui.settings.kit.kitFocusRing
import dev.supermux.ui.settings.kit.kitMetrics
import dev.supermux.ui.settings.settingsTitle
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private data class Choice(val id: String, val label: String)
private data class VoiceEngine(val id: String, val label: String, val family: String)

// Speech engines — mirror STT_ENGINES in src/core/transcription/stt-types.ts; labelled by whose they are.
private val STT_ENGINES = listOf(
    Choice("codex-realtime", "ChatGPT"),
    Choice("claude-voice", "Claude"),
    Choice("cursor-stt", "Cursor"),
    Choice("whisper", "Whisper"),
)
private const val DEFAULT_STT_ENGINE = "codex-realtime"

internal fun sttEngineLabel(id: String): String = STT_ENGINES.firstOrNull { it.id == id }?.label ?: id

// Dictation languages (ISO-639-1) — the broker's `voiceLanguages`. Several at once is the point:
// codex-realtime turns them into a "mixes Turkish and English" prompt rather than a pin.
private val VOICE_LANGUAGES = listOf(
    "tr" to "Turkish",
    "en" to "English",
    "de" to "German",
    "fr" to "French",
    "es" to "Spanish",
    "it" to "Italian",
    "pt" to "Portuguese",
    "nl" to "Dutch",
    "ru" to "Russian",
    "uk" to "Ukrainian",
    "ar" to "Arabic",
    "fa" to "Persian",
    "bs" to "Bosnian",
    "sq" to "Albanian",
    "zh" to "Chinese",
    "ja" to "Japanese",
)

internal fun voiceLanguageLabel(code: String): String =
    VOICE_LANGUAGES.firstOrNull { it.first == code }?.second ?: code

/** "English, Turkish" in the order they were picked; "Detect automatically" for none. */
internal fun voiceLanguagesSummary(codes: List<String>): String =
    if (codes.isEmpty()) "Detect automatically" else codes.joinToString(", ") { voiceLanguageLabel(it) }

// Read-aloud engines — MessageTts uses platform (say/espeak/Android TTS) or codex (the machine's /speak).
private val TTS_ENGINES = listOf(
    Choice("platform", "Device's own voice"),
    Choice("codex", "ChatGPT"),
)
private const val DEFAULT_TTS_ENGINE = "platform"

internal fun ttsEngineLabel(id: String): String = TTS_ENGINES.firstOrNull { it.id == id }?.label ?: id

// Tidy-up engines — a curated mirror of ENGINES in src/core/agent-api/index.ts. `family` is the
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

/** "3 words", "1 word". */
internal fun wordCount(n: Int): String = if (n == 1) "1 word" else "$n words"

internal const val ERROR_AUTO_RETRY_MS = 3_000L
private const val PREVIEW_TTS_SAMPLE = "Hello from Supermux."

/** Load model for the voice config. */
internal sealed class VoiceLoadState {
    data object Loading : VoiceLoadState()
    data object Ready : VoiceLoadState()
    data object Error : VoiceLoadState()
}

/**
 * Every broker call the Voice page and its word list make, in one holder.
 *
 * The saves report success so a failed pick puts its control back, and `glossaryLoad` returns null for a FAILED
 * load — never an empty list, which a save would then write back over the real word list.
 */
@Immutable
class VoiceSettingsActions(
    val loadConfig: suspend () -> AppConfigDto? = { null },
    val loadModels: suspend (family: String) -> List<ModelInfo> = { emptyList() },
    val saveVoiceStt: suspend (engine: String?) -> Boolean = { false },
    val saveVoiceTts: suspend (engine: String?) -> Boolean = { false },
    val saveVoiceLanguages: suspend (languages: List<String>) -> Boolean = { false },
    val saveVoiceCleanup: suspend (engine: String?, model: String?) -> Boolean = { _, _ -> false },
    /** Null = failure; empty = no words; never collapse failure into empty. */
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
        saveVoiceLanguages = { languages -> app.saveVoiceLanguages(languages) },
        saveVoiceCleanup = { engine, model -> app.saveVoiceCleanup(engine, model) },
        glossaryLoad = { app.fetchGlossary() },
        glossarySave = { terms -> app.updateGlossary(terms) },
    )
}

/** [VoiceSettingsActions] against the fleet's ACTIVE host — Android's and iOS's wiring. */
@Composable
fun rememberVoiceSettingsActions(fleet: FleetStore): VoiceSettingsActions = remember(fleet) {
    VoiceSettingsActions(
        loadConfig = { fleet.appConfig() },
        loadModels = { family -> fleet.launcherModels(family) },
        saveVoiceStt = { engine -> fleet.saveVoiceStt(engine) },
        saveVoiceTts = { engine -> fleet.saveVoiceTts(engine) },
        saveVoiceLanguages = { languages -> fleet.saveVoiceLanguages(languages) },
        saveVoiceCleanup = { engine, model -> fleet.saveVoiceCleanup(engine, model) },
        glossaryLoad = { fleet.fetchGlossary() },
        glossarySave = { terms -> fleet.updateGlossary(terms) },
    )
}

/** Which row a failed save belongs under. */
private object VoiceField {
    const val STT = "speech-engine"
    const val LANGUAGES = "languages"
    const val TTS = "read-aloud"
    const val ENGINE = "tidy-up-engine"
    const val MODEL = "tidy-up-model"
}

/**
 * The Voice page. [onOpenSub] opens a sub-page of it (the word list, [GLOSSARY_SUB]): the hub's `onOpen` with this
 * section.
 */
@Composable
fun VoicePage(
    actions: VoiceSettingsActions,
    modifier: Modifier = Modifier,
    anchor: String? = null,
    onAnchorShown: () -> Unit = {},
    onOpenSub: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val online = currentMachineOnline()
    // A word being typed belongs to one visit of the word list: back on this page, it is gone.
    val glossaryForm = rememberRetainedOnPage("voice:glossary") { GlossaryForm() }
    LaunchedEffect(Unit) { glossaryForm.clear() }

    var models by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var sttEngine by remember { mutableStateOf(DEFAULT_STT_ENGINE) }
    var ttsEngine by remember { mutableStateOf(DEFAULT_TTS_ENGINE) }
    var languages by remember { mutableStateOf<List<String>>(emptyList()) }
    var engine by remember { mutableStateOf(DEFAULT_VOICE_ENGINE) }
    var selectedModel by remember { mutableStateOf("") }
    var words by remember { mutableStateOf<Int?>(null) }
    var wordsFailed by remember { mutableStateOf(false) }
    var loadState by remember { mutableStateOf<VoiceLoadState>(VoiceLoadState.Loading) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var saveError by remember { mutableStateOf<Pair<String, String>?>(null) }
    var codexAvailable by remember { mutableStateOf<Boolean?>(null) }
    var languagesOpen by remember { mutableStateOf(false) }

    fun show(cfg: AppConfigDto) {
        sttEngine = cfg.voiceSttEngine?.ifBlank { null } ?: DEFAULT_STT_ENGINE
        languages = cfg.voiceLanguages.orEmpty()
        ttsEngine = cfg.voiceTtsEngine?.ifBlank { null } ?: DEFAULT_TTS_ENGINE
        engine = cfg.voiceCleanupEngine?.ifBlank { null } ?: DEFAULT_VOICE_ENGINE
        selectedModel = cfg.voiceCleanupModel ?: ""
        codexAvailable = cfg.voiceCodexAvailable
    }

    // The config and the word list together; an offline machine is not asked (another host would answer).
    LaunchedEffect(reloadKey, online) {
        if (!online) return@LaunchedEffect
        if (loadState !is VoiceLoadState.Ready) loadState = VoiceLoadState.Loading
        val (cfg, glossary) = coroutineScope {
            val c = async { actions.loadConfig() }
            val g = async { actions.glossaryLoad() }
            c.await() to g.await()
        }
        words = glossary?.size
        wordsFailed = glossary == null
        if (cfg == null) {
            loadState = VoiceLoadState.Error
            return@LaunchedEffect
        }
        show(cfg)
        models = actions.loadModels(voiceEngineFamily(engine))
        saveError = null
        loadState = VoiceLoadState.Ready
    }

    // A failed load tries again on its own every few seconds, as well as on "Try again".
    LaunchedEffect(loadState, reloadKey) {
        if (loadState !is VoiceLoadState.Error) return@LaunchedEffect
        while (isActive) {
            delay(ERROR_AUTO_RETRY_MS)
            val cfg = actions.loadConfig() ?: continue
            show(cfg)
            if (words == null) {
                val glossary = actions.glossaryLoad()
                words = glossary?.size
                wordsFailed = glossary == null
            }
            models = actions.loadModels(voiceEngineFamily(engine))
            loadState = VoiceLoadState.Ready
            break
        }
    }

    // Dictation mode is THIS device's (UiPrefs), not the machine's; only offered where the OS has a recogniser.
    // The machine's ChatGPT login decides the default when nothing was picked.
    val hasDeviceStt = LocalPlatform.current.mic.liveTranscript != null
    val prefs = LocalUiPrefsOrNull.current
    val explicitMode by remember(prefs) { prefs?.dictationMode ?: flowOf(null) }.collectAsState(null)
    val mode = resolveDictationMode(explicitMode, codexAvailable, hasDeviceStt)
    val noun = if (kitDeviceNoun() == "phone") "phone" else "device"
    val machine = machineInSentence()

    KitPage(
        settingsTitle(SettingsPlace(SettingsSection.Voice)),
        modifier,
        scope = { KitMachineScope() },
        anchor = anchor,
        onAnchorShown = onAnchorShown,
        tag = "voice_settings_screen",
    ) {
        when {
            !online -> KitOfflineNote()
            loadState is VoiceLoadState.Loading -> Unit
            loadState is VoiceLoadState.Error -> {
                KitNote("Couldn't reach $machine.", Modifier.testTag("voice_settings_error"))
                KitTextAction(
                    "Try again",
                    onClick = { reloadKey++ },
                    modifier = Modifier.padding(top = 4.dp).testTag("voice_settings_retry"),
                )
            }
            else -> {
                val sttInUse = mode == DictationMode.Broker
                KitOptionsRow(
                    label = "Speech recognition",
                    options = STT_ENGINES.map { it.id },
                    selected = sttEngine,
                    onSelect = { picked ->
                        if (picked == sttEngine) return@KitOptionsRow
                        val previous = sttEngine
                        sttEngine = picked
                        saveError = null
                        scope.launch {
                            if (!actions.saveVoiceStt(picked)) {
                                sttEngine = previous
                                saveError = VoiceField.STT to "Couldn't save speech recognition."
                            }
                        }
                    },
                    optionLabel = ::sttEngineLabel,
                    modifier = Modifier.kitAnchor("speech-engine").testTag("voice_stt_row"),
                    description = if (sttInUse) "Turns what you say into text." else "Not used while this $noun turns speech into text.",
                    enabled = sttInUse,
                    optionTag = { "voice_stt_chip_option_$it" },
                )
                ErrorUnder(saveError, VoiceField.STT)

                KitValueRow(
                    label = "Languages",
                    value = voiceLanguagesSummary(languages),
                    modifier = Modifier.kitAnchor("languages").testTag("voice_languages_chip"),
                    description = "Leave empty to detect automatically.",
                    onClick = { languagesOpen = true },
                )
                if (!languagesOpen) ErrorUnder(saveError, VoiceField.LANGUAGES)

                KitValueRow(
                    label = "Words to spell your way",
                    value = when {
                        words != null -> wordCount(words!!)
                        wordsFailed -> "Couldn't load"
                        else -> ""
                    },
                    modifier = Modifier.kitAnchor("glossary").testTag("voice_glossary_link"),
                    description = "Names and terms dictation and agents keep exactly as you write them.",
                    onClick = { onOpenSub(GLOSSARY_SUB) },
                )

                KitRow(
                    label = "Read replies aloud",
                    modifier = Modifier.kitAnchor("read-aloud").testTag("voice_tts_row"),
                ) {
                    val previewTts = LocalPlatform.current.tts
                    MenuValue(
                        value = ttsEngineLabel(ttsEngine),
                        options = TTS_ENGINES.map { it.id to it.label },
                        onPick = { picked ->
                            if (picked == ttsEngine) return@MenuValue
                            val previous = ttsEngine
                            ttsEngine = picked
                            saveError = null
                            scope.launch {
                                if (!actions.saveVoiceTts(picked)) {
                                    ttsEngine = previous
                                    saveError = VoiceField.TTS to "Couldn't save how replies are read."
                                }
                            }
                        },
                        tag = "voice_tts_chip",
                        menuLabel = "Read replies aloud",
                    )
                    KitGhostButton(
                        "Preview",
                        onClick = { MessageTts.toggle(previewTts, PREVIEW_TTS_SAMPLE) },
                        modifier = Modifier.testTag("voice_tts_preview"),
                    )
                }
                ErrorUnder(saveError, VoiceField.TTS)

                KitFold("Advanced", anchors = setOf("tidy-up-engine", "tidy-up-model"), tag = "voice_advanced") {
                    KitNote("Only used when speech is recognised on a phone or by Whisper; ChatGPT and Claude already return clean text.")
                    MenuValueRow(
                        label = "Tidy-up engine",
                        value = voiceEngineLabel(engine),
                        options = VOICE_ENGINES.map { it.id to it.label },
                        onPick = { picked ->
                            if (picked == engine) return@MenuValueRow
                            val previousEngine = engine
                            val previousModel = selectedModel
                            engine = picked
                            selectedModel = ""
                            saveError = null
                            scope.launch {
                                if (!actions.saveVoiceCleanup(picked, "")) {
                                    engine = previousEngine
                                    selectedModel = previousModel
                                    saveError = VoiceField.ENGINE to "Couldn't save the tidy-up engine."
                                } else {
                                    models = actions.loadModels(voiceEngineFamily(picked))
                                }
                            }
                        },
                        modifier = Modifier.kitAnchor("tidy-up-engine"),
                        tag = "voice_cleanup_engine_chip",
                    )
                    ErrorUnder(saveError, VoiceField.ENGINE)
                    MenuValueRow(
                        label = "Tidy-up model",
                        value = if (selectedModel.isEmpty()) {
                            "Default"
                        } else {
                            models.firstOrNull { it.id == selectedModel }?.displayName ?: selectedModel
                        },
                        options = listOf("" to "Default") + models.map { it.id to it.displayName },
                        onPick = { picked ->
                            if (picked == selectedModel) return@MenuValueRow
                            val previous = selectedModel
                            selectedModel = picked
                            saveError = null
                            scope.launch {
                                if (!actions.saveVoiceCleanup(null, picked)) {
                                    selectedModel = previous
                                    saveError = VoiceField.MODEL to "Couldn't save the tidy-up model."
                                }
                            }
                        },
                        modifier = Modifier.kitAnchor("tidy-up-model"),
                        description = "Default is ${voiceEngineLabel(engine)}'s own.",
                        tag = "voice_cleanup_model_chip",
                    )
                    ErrorUnder(saveError, VoiceField.MODEL)
                }
            }
        }

        if (hasDeviceStt && prefs != null) {
            KitSectionHead(if (noun == "phone") "This phone" else "This device")
            KitOptionsRow(
                label = "Turn speech into text",
                options = listOf(DictationMode.Device, DictationMode.Broker),
                selected = mode,
                onSelect = { picked -> scope.launch { prefs.putDictationMode(picked) } },
                optionLabel = { if (it == DictationMode.Device) "On this $noun" else "On $machine" },
                modifier = Modifier.kitAnchor("dictation").testTag("voice_mode_row"),
                description = "On this $noun works offline and starts faster.",
                optionTag = { "voice_mode_chip_option_${it.wire}" },
            )
        }
    }

    if (languagesOpen) {
        LanguagesSheet(
            selected = languages,
            error = saveError?.takeIf { it.first == VoiceField.LANGUAGES }?.second,
            onChange = { next ->
                val previous = languages
                languages = next
                saveError = null
                scope.launch {
                    if (!actions.saveVoiceLanguages(next)) {
                        languages = previous
                        saveError = VoiceField.LANGUAGES to "Couldn't save languages."
                    }
                }
            },
            onDismiss = { languagesOpen = false },
        )
    }
}

/** The machine as a sentence names it mid-way: its name, or "this machine" when Settings knows none. */
@Composable
private fun machineInSentence(): String = LocalSettingsMachines.current?.current?.name ?: "this machine"

/** "Couldn't save …" under the row whose save failed. */
@Composable
private fun ErrorUnder(error: Pair<String, String>?, field: String) {
    if (error == null || error.first != field) return
    Text(
        error.second,
        fontSize = 12.sp,
        color = kitColors().danger,
        modifier = Modifier.padding(top = 6.dp).testTag("voice_save_error"),
    )
}

/** One toggle per language (today's choices), and "Detect automatically" for none; each change saves at once. */
@Composable
private fun LanguagesSheet(
    selected: List<String>,
    error: String?,
    onChange: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    // A stored code outside the curated list still shows (and can be turned off).
    val options = VOICE_LANGUAGES + selected.filter { c -> VOICE_LANGUAGES.none { it.first == c } }.map { it to it }
    KitSheet(
        title = "Languages",
        onDismiss = onDismiss,
        description = "What you dictate in. Pick several if you mix them; leave all off to detect automatically.",
        tag = "voice_languages_sheet",
        buttons = { KitGhostButton("Done", onClick = onDismiss, modifier = Modifier.testTag("voice_languages_done")) },
    ) {
        KitToggleRow(
            label = "Detect automatically",
            checked = selected.isEmpty(),
            onCheckedChange = { on -> if (on && selected.isNotEmpty()) onChange(emptyList()) },
            modifier = Modifier.testTag("voice_languages_chip_option_auto"),
        )
        for ((code, label) in options) {
            val on = code in selected
            KitToggleRow(
                label = label,
                checked = on,
                onCheckedChange = { onChange(if (on) selected - code else selected + code) },
                modifier = Modifier.testTag("voice_languages_chip_option_$code"),
            )
        }
        if (error != null) {
            Text(error, fontSize = 12.sp, color = kitColors().danger, modifier = Modifier.padding(top = 6.dp).testTag("voice_save_error"))
        }
    }
}

/** A value row whose value is picked from a menu. Options are tagged `‹tag›_option_‹id›` ("default" for ""). */
@Composable
private fun MenuValueRow(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onPick: (String) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        KitValueRow(label, value, Modifier.testTag(tag), description = description, onClick = { open = true })
        OptionsMenu(open, onDismiss = { open = false }, options = options, onPick = onPick, tag = tag)
    }
}

/** The value at the end of a row that opens a menu of [options] (the read-aloud voice, next to Preview). */
@Composable
private fun MenuValue(
    value: String,
    options: List<Pair<String, String>>,
    onPick: (String) -> Unit,
    tag: String,
    menuLabel: String,
) {
    val c = kitColors()
    var open by remember { mutableStateOf(false) }
    val src = remember { MutableInteractionSource() }
    Box {
        Row(
            Modifier
                .clickable(interactionSource = src, indication = null, role = Role.Button, onClickLabel = menuLabel) { open = true }
                .kitFocusRing(src, RoundedCornerShape(4.dp))
                .padding(vertical = 4.dp)
                .testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(value, fontSize = kitMetrics().value, color = c.dim, maxLines = 1)
            Icon(KitGlyphs.ChevronDown, contentDescription = null, tint = c.faint, modifier = Modifier.size(12.dp))
        }
        OptionsMenu(open, onDismiss = { open = false }, options = options, onPick = onPick, tag = tag)
    }
}

@Composable
private fun OptionsMenu(
    open: Boolean,
    onDismiss: () -> Unit,
    options: List<Pair<String, String>>,
    onPick: (String) -> Unit,
    tag: String,
) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        options.forEach { (id, label) ->
            DropdownMenuItem(
                text = { Text(label) },
                onClick = {
                    onDismiss()
                    onPick(id)
                },
                modifier = Modifier.testTag("${tag}_option_${id.ifBlank { "default" }}"),
            )
        }
    }
}
