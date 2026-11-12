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
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.i18n.localizedPlural
import dev.supermux.ui.resources.Res
import dev.supermux.ui.resources.settings_pages_advanced
import dev.supermux.ui.resources.settings_pages_default
import dev.supermux.ui.resources.settings_pages_done
import dev.supermux.ui.resources.settings_pages_try_again
import dev.supermux.ui.resources.settings_voice_detect_automatically
import dev.supermux.ui.resources.settings_voice_dictation
import dev.supermux.ui.resources.settings_voice_dictation_description_device
import dev.supermux.ui.resources.settings_voice_dictation_description_phone
import dev.supermux.ui.resources.settings_voice_dictation_on_machine
import dev.supermux.ui.resources.settings_voice_dictation_on_this_device
import dev.supermux.ui.resources.settings_voice_dictation_on_this_machine
import dev.supermux.ui.resources.settings_voice_dictation_on_this_phone
import dev.supermux.ui.resources.settings_voice_glossary
import dev.supermux.ui.resources.settings_voice_glossary_description
import dev.supermux.ui.resources.settings_voice_glossary_failed
import dev.supermux.ui.resources.settings_voice_lang_ar
import dev.supermux.ui.resources.settings_voice_lang_bs
import dev.supermux.ui.resources.settings_voice_lang_de
import dev.supermux.ui.resources.settings_voice_lang_en
import dev.supermux.ui.resources.settings_voice_lang_es
import dev.supermux.ui.resources.settings_voice_lang_fa
import dev.supermux.ui.resources.settings_voice_lang_fr
import dev.supermux.ui.resources.settings_voice_lang_it
import dev.supermux.ui.resources.settings_voice_lang_ja
import dev.supermux.ui.resources.settings_voice_lang_nl
import dev.supermux.ui.resources.settings_voice_lang_pt
import dev.supermux.ui.resources.settings_voice_lang_ru
import dev.supermux.ui.resources.settings_voice_lang_sq
import dev.supermux.ui.resources.settings_voice_lang_tr
import dev.supermux.ui.resources.settings_voice_lang_uk
import dev.supermux.ui.resources.settings_voice_lang_zh
import dev.supermux.ui.resources.settings_voice_languages
import dev.supermux.ui.resources.settings_voice_languages_description
import dev.supermux.ui.resources.settings_voice_languages_failed
import dev.supermux.ui.resources.settings_voice_languages_sheet_description
import dev.supermux.ui.resources.settings_voice_preview
import dev.supermux.ui.resources.settings_voice_read_aloud
import dev.supermux.ui.resources.settings_voice_read_aloud_failed
import dev.supermux.ui.resources.settings_voice_speech_recognition
import dev.supermux.ui.resources.settings_voice_speech_recognition_description
import dev.supermux.ui.resources.settings_voice_speech_recognition_failed
import dev.supermux.ui.resources.settings_voice_speech_recognition_unused_device
import dev.supermux.ui.resources.settings_voice_speech_recognition_unused_phone
import dev.supermux.ui.resources.settings_voice_this_device
import dev.supermux.ui.resources.settings_voice_this_phone
import dev.supermux.ui.resources.settings_voice_tidy_up_engine
import dev.supermux.ui.resources.settings_voice_tidy_up_engine_failed
import dev.supermux.ui.resources.settings_voice_tidy_up_model
import dev.supermux.ui.resources.settings_voice_tidy_up_model_description
import dev.supermux.ui.resources.settings_voice_tidy_up_model_failed
import dev.supermux.ui.resources.settings_voice_tidy_up_note
import dev.supermux.ui.resources.settings_voice_tts_device
import dev.supermux.ui.resources.settings_voice_words
import dev.supermux.ui.settings.localizedSettingsTitle
import org.jetbrains.compose.resources.StringResource
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
/** A dictation language: its code, its English name (the plain helpers below) and its name in the app's language. */
private data class VoiceLanguage(val code: String, val english: String, val name: StringResource)

private val VOICE_LANGUAGES = listOf(
    VoiceLanguage("tr", "Turkish", Res.string.settings_voice_lang_tr),
    VoiceLanguage("en", "English", Res.string.settings_voice_lang_en),
    VoiceLanguage("de", "German", Res.string.settings_voice_lang_de),
    VoiceLanguage("fr", "French", Res.string.settings_voice_lang_fr),
    VoiceLanguage("es", "Spanish", Res.string.settings_voice_lang_es),
    VoiceLanguage("it", "Italian", Res.string.settings_voice_lang_it),
    VoiceLanguage("pt", "Portuguese", Res.string.settings_voice_lang_pt),
    VoiceLanguage("nl", "Dutch", Res.string.settings_voice_lang_nl),
    VoiceLanguage("ru", "Russian", Res.string.settings_voice_lang_ru),
    VoiceLanguage("uk", "Ukrainian", Res.string.settings_voice_lang_uk),
    VoiceLanguage("ar", "Arabic", Res.string.settings_voice_lang_ar),
    VoiceLanguage("fa", "Persian", Res.string.settings_voice_lang_fa),
    VoiceLanguage("bs", "Bosnian", Res.string.settings_voice_lang_bs),
    VoiceLanguage("sq", "Albanian", Res.string.settings_voice_lang_sq),
    VoiceLanguage("zh", "Chinese", Res.string.settings_voice_lang_zh),
    VoiceLanguage("ja", "Japanese", Res.string.settings_voice_lang_ja),
)

internal fun voiceLanguageLabel(code: String): String =
    VOICE_LANGUAGES.firstOrNull { it.code == code }?.english ?: code

/** "English, Turkish" in the order they were picked; "Detect automatically" for none. English: the UI reads [localizedLanguagesSummary]. */
internal fun voiceLanguagesSummary(codes: List<String>): String =
    if (codes.isEmpty()) "Detect automatically" else codes.joinToString(", ") { voiceLanguageLabel(it) } // i18n-ok: the English twin of localizedLanguagesSummary

/** A language's name in the app's language; a code outside the list shows as itself. */
@Composable
private fun localizedLanguageName(code: String): String =
    VOICE_LANGUAGES.firstOrNull { it.code == code }?.let { localized(it.name) } ?: code

/** [voiceLanguagesSummary] in the app's language. */
@Composable
private fun localizedLanguagesSummary(codes: List<String>): String {
    if (codes.isEmpty()) return localized(Res.string.settings_voice_detect_automatically)
    return codes.map { localizedLanguageName(it) }.joinToString(", ")
}

// Read-aloud engines — MessageTts uses platform (say/espeak/Android TTS) or codex (the machine's /speak).
private val TTS_ENGINES = listOf(
    Choice("platform", "Device's own voice"), // i18n-ok: the English twin; the UI reads localizedTtsEngineLabel
    Choice("codex", "ChatGPT"),
)
private const val DEFAULT_TTS_ENGINE = "platform"

internal fun ttsEngineLabel(id: String): String = TTS_ENGINES.firstOrNull { it.id == id }?.label ?: id

/** [ttsEngineLabel] in the app's language: the device's voice is described, ChatGPT is a name. */
@Composable
private fun localizedTtsEngineLabel(id: String): String =
    if (id == "platform") localized(Res.string.settings_voice_tts_device) else ttsEngineLabel(id)

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

/** "3 words", "1 word" — English; the UI reads the settings_voice_words plural. */
internal fun wordCount(n: Int): String = if (n == 1) "1 word" else "$n words" // i18n-ok: the English twin of settings_voice_words

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
    val phone = kitDeviceNoun() == "phone"
    val machineName = LocalSettingsMachines.current?.current?.name
    val couldntReach = pagesCouldntReach()
    val sttFailed = localized(Res.string.settings_voice_speech_recognition_failed)
    val ttsFailed = localized(Res.string.settings_voice_read_aloud_failed)
    val engineFailed = localized(Res.string.settings_voice_tidy_up_engine_failed)
    val modelFailed = localized(Res.string.settings_voice_tidy_up_model_failed)
    val languagesFailed = localized(Res.string.settings_voice_languages_failed)
    val defaultLabel = localized(Res.string.settings_pages_default)
    val ttsLabels = TTS_ENGINES.associate { it.id to localizedTtsEngineLabel(it.id) }
    val onThisDevice = localized(
        if (phone) Res.string.settings_voice_dictation_on_this_phone else Res.string.settings_voice_dictation_on_this_device,
    )
    val onMachine = if (machineName != null) {
        localized(Res.string.settings_voice_dictation_on_machine, machineName)
    } else {
        localized(Res.string.settings_voice_dictation_on_this_machine)
    }

    KitPage(
        localizedSettingsTitle(SettingsPlace(SettingsSection.Voice)),
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
                KitNote(couldntReach, Modifier.testTag("voice_settings_error"))
                KitTextAction(
                    localized(Res.string.settings_pages_try_again),
                    onClick = { reloadKey++ },
                    modifier = Modifier.padding(top = 4.dp).testTag("voice_settings_retry"),
                )
            }
            else -> {
                val sttInUse = mode == DictationMode.Broker
                KitOptionsRow(
                    label = localized(Res.string.settings_voice_speech_recognition),
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
                                saveError = VoiceField.STT to sttFailed
                            }
                        }
                    },
                    optionLabel = ::sttEngineLabel,
                    modifier = Modifier.kitAnchor("speech-engine").testTag("voice_stt_row"),
                    description = localized(
                        when {
                            sttInUse -> Res.string.settings_voice_speech_recognition_description
                            phone -> Res.string.settings_voice_speech_recognition_unused_phone
                            else -> Res.string.settings_voice_speech_recognition_unused_device
                        },
                    ),
                    enabled = sttInUse,
                    optionTag = { "voice_stt_chip_option_$it" },
                )
                ErrorUnder(saveError, VoiceField.STT)

                KitValueRow(
                    label = localized(Res.string.settings_voice_languages),
                    value = localizedLanguagesSummary(languages),
                    modifier = Modifier.kitAnchor("languages").testTag("voice_languages_chip"),
                    description = localized(Res.string.settings_voice_languages_description),
                    onClick = { languagesOpen = true },
                )
                if (!languagesOpen) ErrorUnder(saveError, VoiceField.LANGUAGES)

                KitValueRow(
                    label = localized(Res.string.settings_voice_glossary),
                    value = when {
                        words != null -> localizedPlural(Res.plurals.settings_voice_words, words!!, words!!)
                        wordsFailed -> localized(Res.string.settings_voice_glossary_failed)
                        else -> ""
                    },
                    modifier = Modifier.kitAnchor("glossary").testTag("voice_glossary_link"),
                    description = localized(Res.string.settings_voice_glossary_description),
                    onClick = { onOpenSub(GLOSSARY_SUB) },
                )

                KitRow(
                    label = localized(Res.string.settings_voice_read_aloud),
                    modifier = Modifier.kitAnchor("read-aloud").testTag("voice_tts_row"),
                ) {
                    val previewTts = LocalPlatform.current.tts
                    MenuValue(
                        value = ttsLabels[ttsEngine] ?: ttsEngine,
                        options = TTS_ENGINES.map { it.id to ttsLabels.getValue(it.id) },
                        onPick = { picked ->
                            if (picked == ttsEngine) return@MenuValue
                            val previous = ttsEngine
                            ttsEngine = picked
                            saveError = null
                            scope.launch {
                                if (!actions.saveVoiceTts(picked)) {
                                    ttsEngine = previous
                                    saveError = VoiceField.TTS to ttsFailed
                                }
                            }
                        },
                        tag = "voice_tts_chip",
                        menuLabel = localized(Res.string.settings_voice_read_aloud),
                    )
                    KitGhostButton(
                        localized(Res.string.settings_voice_preview),
                        onClick = { MessageTts.toggle(previewTts, PREVIEW_TTS_SAMPLE) },
                        modifier = Modifier.testTag("voice_tts_preview"),
                    )
                }
                ErrorUnder(saveError, VoiceField.TTS)

                KitFold(localized(Res.string.settings_pages_advanced), anchors = setOf("tidy-up-engine", "tidy-up-model"), tag = "voice_advanced") {
                    KitNote(localized(Res.string.settings_voice_tidy_up_note))
                    MenuValueRow(
                        label = localized(Res.string.settings_voice_tidy_up_engine),
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
                                    saveError = VoiceField.ENGINE to engineFailed
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
                        label = localized(Res.string.settings_voice_tidy_up_model),
                        value = if (selectedModel.isEmpty()) {
                            defaultLabel
                        } else {
                            models.firstOrNull { it.id == selectedModel }?.displayName ?: selectedModel
                        },
                        options = listOf("" to defaultLabel) + models.map { it.id to it.displayName },
                        onPick = { picked ->
                            if (picked == selectedModel) return@MenuValueRow
                            val previous = selectedModel
                            selectedModel = picked
                            saveError = null
                            scope.launch {
                                if (!actions.saveVoiceCleanup(null, picked)) {
                                    selectedModel = previous
                                    saveError = VoiceField.MODEL to modelFailed
                                }
                            }
                        },
                        modifier = Modifier.kitAnchor("tidy-up-model"),
                        description = localized(Res.string.settings_voice_tidy_up_model_description, voiceEngineLabel(engine)),
                        tag = "voice_cleanup_model_chip",
                    )
                    ErrorUnder(saveError, VoiceField.MODEL)
                }
            }
        }

        if (hasDeviceStt && prefs != null) {
            KitSectionHead(localized(if (phone) Res.string.settings_voice_this_phone else Res.string.settings_voice_this_device))
            KitOptionsRow(
                label = localized(Res.string.settings_voice_dictation),
                options = listOf(DictationMode.Device, DictationMode.Broker),
                selected = mode,
                onSelect = { picked -> scope.launch { prefs.putDictationMode(picked) } },
                optionLabel = { if (it == DictationMode.Device) onThisDevice else onMachine },
                modifier = Modifier.kitAnchor("dictation").testTag("voice_mode_row"),
                description = localized(
                    if (phone) Res.string.settings_voice_dictation_description_phone else Res.string.settings_voice_dictation_description_device,
                ),
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
                        saveError = VoiceField.LANGUAGES to languagesFailed
                    }
                }
            },
            onDismiss = { languagesOpen = false },
        )
    }
}

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
    val options = VOICE_LANGUAGES.map { it.code to localized(it.name) } +
        selected.filter { c -> VOICE_LANGUAGES.none { it.code == c } }.map { it to it }
    KitSheet(
        title = localized(Res.string.settings_voice_languages),
        onDismiss = onDismiss,
        description = localized(Res.string.settings_voice_languages_sheet_description),
        tag = "voice_languages_sheet",
        buttons = { KitGhostButton(localized(Res.string.settings_pages_done), onClick = onDismiss, modifier = Modifier.testTag("voice_languages_done")) },
    ) {
        KitToggleRow(
            label = localized(Res.string.settings_voice_detect_automatically),
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
