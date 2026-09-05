package dev.supermux.android.settings

import android.app.Application
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.AppViewModel
import androidx.compose.ui.platform.LocalContext
import dev.supermux.android.update.AppUpdatePage
import dev.supermux.chat.mergeTimeline
import dev.supermux.ui.chat.TimelineItemRow
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.LocalPanes
import dev.supermux.ui.theme.TEXT_SCALE_MAX
import dev.supermux.ui.theme.TEXT_SCALE_MIN
import kotlin.math.roundToInt
import dev.supermux.net.ArchivedDto
import dev.supermux.net.CodexResetResult
import dev.supermux.net.LspInstallResult
import dev.supermux.net.LspMutationResult
import dev.supermux.net.LspServer
import dev.supermux.net.ModelInfo
import dev.supermux.ui.chat.PickerSheet
import dev.supermux.android.session.deriveArchivedWorkspaceRow
import dev.supermux.android.session.relTime
import dev.supermux.proto.WorkspaceDto
import dev.supermux.session.archivedProjects
import dev.supermux.session.filterArchivedByProject
import dev.supermux.session.formatWorkdir
import dev.supermux.workspace.groupArchivedWorkspaces
import dev.supermux.proto.LogEntry
import dev.supermux.proto.ServerFrame
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.json.JSONObject
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import dev.supermux.ui.settings.LspSettingsScreen
import dev.supermux.ui.settings.AgentSettingsActions
import dev.supermux.ui.settings.CuratorSettingsActions
import dev.supermux.ui.settings.CuratorSettingsScreen
import dev.supermux.ui.settings.DevicesSettingsActions
import dev.supermux.ui.settings.DevicesSettingsScreen
import dev.supermux.ui.settings.PersonalAssistantsActions
import dev.supermux.ui.settings.PersonalAssistantsScreen
import dev.supermux.ui.settings.ProxiesSettingsActions
import dev.supermux.ui.settings.ProxiesSettingsScreen
import dev.supermux.ui.settings.AgentSettingsScreen
import dev.supermux.ui.settings.AssistantSettingsActions
import dev.supermux.ui.settings.AssistantSettingsScreen
import dev.supermux.ui.settings.GitHostingActions
import dev.supermux.ui.settings.GitHostingScreen
import dev.supermux.ui.settings.SystemSettingsActions
import dev.supermux.ui.settings.SystemSettingsScreen
import dev.supermux.ui.settings.SettingsExtra
import dev.supermux.ui.settings.SettingsHub
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.prefs.EDITOR_FONT_DEFAULT
import dev.supermux.ui.prefs.EDITOR_FONT_MAX
import dev.supermux.ui.prefs.EDITOR_FONT_MIN
import dev.supermux.ui.prefs.EDITOR_LINE_WRAP_DEFAULT
import dev.supermux.ui.prefs.LocalUiPrefs
import kotlinx.coroutines.flow.first
import dev.supermux.net.AddCustomLspArgs

// ─── SettingsScreen ───────────────────────────────────────────────────────────
//
// Since cluster E1 the index + push router IS the shared `dev.supermux.ui.settings.SettingsHub`
// (rail + detail on a tablet, Android's index list on a phone). What is left here is the SLOT
// WIRING: which page each `SettingsSection` is on Android, and how it crosses the VM boundary as
// suspend lambdas / plain callbacks (the established style), plus one actions holder per screen that
// is already shared. The pages still defined below carry their own Scaffold/TopAppBar, so the hub
// is asked NOT to paint the detail chrome (`compactTopBar = false`); each E4–E7 task that moves a
// page into `:ui` drops its branch here.
//
// The signature is the union of this Settings suite + the Voice track (canonical order
// in the 2026-06-21-android-settings-changelist §3a). Both must match MainActivity's call.

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    /** Personal assistants: one holder since E4 — `ui/settings/PersonalAssistantsScreen.kt`. */
    paActions: PersonalAssistantsActions,
    /** Assistant identity: one holder since E3 — `ui/settings/AssistantSettingsScreen.kt`. */
    assistantActions: AssistantSettingsActions,
    /** Agents: one holder since E2 — the screen itself is `ui/settings/AgentSettingsScreen.kt`. */
    agentActions: AgentSettingsActions,
    /** Nightly curator: one holder since E4 — `ui/settings/CuratorSettingsScreen.kt`. */
    curatorActions: CuratorSettingsActions,
    // Voice (Voice track)
    voiceLoadModels: suspend (family: String) -> List<dev.supermux.net.ModelInfo>,
    voiceLoadConfig: suspend () -> dev.supermux.net.AppConfigDto?,
    voiceSaveVoiceStt: (engine: String?) -> Unit,
    voiceSaveVoiceTts: (engine: String?) -> Unit = {},
    voiceSaveVoiceCleanup: (engine: String?, model: String?) -> Unit,
    glossaryLoad: suspend () -> List<String>,
    glossarySave: suspend (List<String>) -> List<String>?,
    // Editor / LSP
    lspLoad: suspend () -> List<LspServer>,
    lspToggle: suspend (id: String, enabled: Boolean) -> List<LspServer>?,
    lspInstall: suspend (id: String) -> LspInstallResult?,
    lspInstallLog: StateFlow<Map<String, List<String>>>,
    lspInstallDone: StateFlow<Map<String, ServerFrame.LspInstallDone>>,
    lspAddCustom: suspend (AddCustomLspArgs) -> LspMutationResult?,
    lspRemoveCustom: suspend (id: String) -> LspMutationResult?,
    /** Git hosting: one holder since E3 — `ui/settings/GitHostingScreen.kt`. */
    gitHostingActions: GitHostingActions,
    /** Broker system/maintenance: one holder since E3 — `ui/settings/SystemSettingsScreen.kt`. */
    systemActions: SystemSettingsActions,
    // Devices + Proxies: standalone routes too (Route.Devices / Route.Proxies), and hub sections
    // since E1 — the same shared screens since E4, reached either way.
    devicesActions: DevicesSettingsActions,
    proxiesActions: ProxiesSettingsActions,
    /** The Appearance extra row (`Caps.appearanceControls`): theme / Material You / text scale
     *  live in MainActivity's prefs, so the page arrives as a slot. Shared in E7. */
    appearanceContent: @Composable (onBack: () -> Unit) -> Unit,
) {
    var section by remember { mutableStateOf(SettingsSection.PersonalAssistants) }

    SettingsHub(
        section = section,
        onSectionChange = { section = it },
        onBack = onBack,
        // MainActivity already wraps this route in `key(activeHost)`, so the hub's own host scoping
        // has nothing left to reset; passing null keeps one owner of that behaviour.
        hostKey = null,
        // The pages still on this file bring their own Scaffold + TopAppBar (and, for the ones
        // that push a sub-page of their own, their own BackHandler). The screens already shared
        // — Agents, Assistant, Git hosting, System — read `scope.topBarShown` instead and let the
        // hub own compact Back; each remaining E4–E7 task drops one more branch here.
        compactTopBar = false,
        extraContent = { extra, scope ->
            when (extra) {
                SettingsExtra.Appearance -> appearanceContent(scope.onClose)
                SettingsExtra.AppUpdate -> AppUpdatePage(onBack = scope.onClose)
            }
        },
    ) { s, scope ->
        when (s) {
            // Shared since E4 — the screen carries test tags for the first time on either host.
            SettingsSection.PersonalAssistants -> PersonalAssistantsScreen(
                actions = paActions,
                onBack = scope.onClose,
                topBarShown = scope.topBarShown,
            )
            // Shared since E3 — Android gained the dirty guard, the overwrite confirm and the
            // typed save error with it.
            SettingsSection.Assistant -> AssistantSettingsScreen(
                actions = assistantActions,
                onDirtyChange = scope.onDirtyChange,
                onBack = scope.onClose,
                topBarShown = scope.topBarShown,
            )
            // Shared since E2 — Android gained the install section with it. The page paints its
            // own compact TopAppBar because this hub still passes `compactTopBar = false`.
            SettingsSection.Agents -> AgentSettingsScreen(
                actions = agentActions,
                onBack = scope.onClose,
                topBarShown = scope.topBarShown,
            )
            // Shared since E4 — Android gained the load error/retry, the save failure line and
            // the "Saved" confirmation; its PickerSheet/TimePicker copies gave way to the shared
            // adaptive menus.
            SettingsSection.Curator -> CuratorSettingsScreen(
                actions = curatorActions,
                onBack = scope.onClose,
                topBarShown = scope.topBarShown,
            )
            // Voice pushes the glossary as its own sub-page — a Voice-local stack, not a hub row.
            SettingsSection.Voice -> {
                var glossary by remember { mutableStateOf(false) }
                if (glossary) {
                    VoiceGlossaryPage(
                        onBack = { glossary = false },
                        load = glossaryLoad,
                        save = glossarySave,
                    )
                } else {
                    VoiceSettingsPage(
                        onBack = scope.onClose,
                        loadModels = voiceLoadModels,
                        loadConfig = voiceLoadConfig,
                        saveVoiceStt = voiceSaveVoiceStt,
                        saveVoiceTts = voiceSaveVoiceTts,
                        saveVoiceCleanup = voiceSaveVoiceCleanup,
                        onOpenGlossary = { glossary = true },
                    )
                }
            }
            SettingsSection.EditorLsp -> EditorSettingsPage(
                onBack = scope.onClose,
                lspLoad = lspLoad,
                lspToggle = lspToggle,
                lspInstall = lspInstall,
                lspInstallLog = lspInstallLog,
                lspInstallDone = lspInstallDone,
                lspAddCustom = lspAddCustom,
                lspRemoveCustom = lspRemoveCustom,
            )
            // Shared since E3 — Android gained the host-URL validation and the disconnect that
            // keeps the row when the broker rejects it.
            SettingsSection.GitHosting -> GitHostingScreen(
                actions = gitHostingActions,
                onBack = scope.onClose,
                topBarShown = scope.topBarShown,
            )
            // Shared since E3 — Android gained the UPDATES Recheck and the error/load states.
            SettingsSection.System -> SystemSettingsScreen(
                actions = systemActions,
                onBack = scope.onClose,
                topBarShown = scope.topBarShown,
            )
            // Shared since E4 — Android gained "the load failed" as a state of its own, the
            // revoke confirm that survives a rejected DELETE, and the shared QR encoder.
            SettingsSection.Devices -> DevicesSettingsScreen(
                actions = devicesActions,
                onBack = scope.onClose,
                topBarShown = scope.topBarShown,
            )
            // Shared since E4 — Android gained the make-public confirm, the per-row URL with
            // copy/open, and mutations that report what the broker actually did.
            SettingsSection.Proxies -> ProxiesSettingsScreen(
                actions = proxiesActions,
                onBack = scope.onClose,
                topBarShown = scope.topBarShown,
            )
        }
    }
}

// ─── Editor page (local appearance prefs + broker Language-servers section) ───────
//
// Mirrors iOS EditorSettingsScreen: ONE "Editor" screen with two sections — the
// device-local appearance prefs (line-wrap + font-size, SharedPreferences) and the
// broker-backed Language servers (the shared LspSettingsScreen, embedded). The whole page
// scrolls because the LSP list + add-form can be tall.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorSettingsPage(
    onBack: () -> Unit,
    lspLoad: suspend () -> List<LspServer>,
    lspToggle: suspend (id: String, enabled: Boolean) -> List<LspServer>?,
    lspInstall: suspend (id: String) -> LspInstallResult?,
    lspInstallLog: StateFlow<Map<String, List<String>>>,
    lspInstallDone: StateFlow<Map<String, ServerFrame.LspInstallDone>>,
    lspAddCustom: suspend (AddCustomLspArgs) -> LspMutationResult?,
    lspRemoveCustom: suspend (id: String) -> LspMutationResult?,
) {
    val cs = MaterialTheme.colorScheme
    val app = LocalContext.current.applicationContext as Application
    val vm: AppViewModel = viewModel(factory = AppViewModel.factory(app))
    val editorPrefs = LocalUiPrefs.current
    val scope = rememberCoroutineScope()
    val lineWrap by editorPrefs.editorLineWrap.collectAsState(EDITOR_LINE_WRAP_DEFAULT)
    val fontSize by editorPrefs.editorFontSize.collectAsState(EDITOR_FONT_DEFAULT)

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Editor", color = cs.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // 1. Wrap long lines
            SettingsRow(
                label = "Wrap long lines",
                desc = "Wrap instead of horizontal scroll.",
            ) {
                Switch(
                    checked = lineWrap,
                    onCheckedChange = { on -> scope.launch { editorPrefs.putEditorLineWrap(on) } },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = cs.onPrimary,
                        checkedTrackColor = cs.primary,
                    ),
                )
            }
            HorizontalDivider(color = cs.outlineVariant)

            // 2. Font size stepper (clamp 10..24)
            SettingsRow(
                label = "Font size",
                desc = "Code editor text size.",
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StepperButton(text = "−", enabled = fontSize > EDITOR_FONT_MIN) {
                        scope.launch { editorPrefs.putEditorFontSize(editorPrefs.editorFontSize.first() - 1) }
                    }
                    Text(
                        fontSize.toString(),
                        color = cs.onSurface,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    StepperButton(text = "+", enabled = fontSize < EDITOR_FONT_MAX) {
                        scope.launch { editorPrefs.putEditorFontSize(editorPrefs.editorFontSize.first() + 1) }
                    }
                }
            }
            HorizontalDivider(color = cs.outlineVariant)

            // 3. Language servers (broker-backed)
            Spacer(Modifier.height(8.dp))
            LspSettingsScreen(
                lspLoad = lspLoad,
                lspToggle = lspToggle,
                lspInstall = lspInstall,
                lspInstallLog = lspInstallLog,
                lspInstallDone = lspInstallDone,
                lspAddCustom = lspAddCustom,
                lspRemoveCustom = lspRemoveCustom,
                // The page owns the Back arrow and the scroll; this is the embedded section.
                onBack = onBack,
                showTopBar = false,
                scrollable = false,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Small bordered −/+ button for the font-size stepper. */
/**
 * Label/description + trailing control row for the pages still on this file (the Editor page).
 *
 * Was `CuratorRow`, shared with the curator page until cluster E4 moved that screen into `:ui`;
 * it keeps the same metrics so the Editor page looks unchanged. E7 takes it along with the page.
 */
@Composable
private fun SettingsRow(
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

@Composable
private fun StepperButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.4f
    // Keep the 32dp visual but expand the tap target to ≥48dp (a11y, §5).
    Box(
        Modifier.minimumInteractiveComponentSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(cs.surfaceContainer)
                .border(1.dp, cs.outline.copy(alpha = alpha), RoundedCornerShape(6.dp))
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = cs.onSurface.copy(alpha = alpha), fontSize = 18.sp)
        }
    }
}

// ─── Appearance page (light/dark + Material You) ──────────────────────────────
//
// Born native: reads MaterialTheme.colorScheme (not LocalPanes) and uses the M3
// SingleChoiceSegmentedButtonRow.
//
// NOTE: the "Material You" switch is a NO-OP since the theme moved to :ui — the brand OKLCH
// palette is the only palette on every platform. The setting is still shown and still persisted
// so nobody loses their stored preference; nothing reads it to build a colour scheme.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsPage(
    appearance: AppearanceMode,
    dynamicColor: Boolean,
    textScale: Float,
    onAppearanceChange: (AppearanceMode) -> Unit,
    onDynamicChange: (Boolean) -> Unit,
    onTextScaleChange: (Float) -> Unit,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    BackHandler { onBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appearance") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = cs.surfaceContainerHigh,
                ),
            )
        },
        containerColor = cs.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Theme", style = MaterialTheme.typography.titleMedium, color = cs.onBackground)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val modes = AppearanceMode.entries
                    modes.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = appearance == mode,
                            onClick = { onAppearanceChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        ) {
                            Text(
                                when (mode) {
                                    AppearanceMode.SYSTEM -> "System"
                                    AppearanceMode.LIGHT -> "Light"
                                    AppearanceMode.DARK -> "Dark"
                                }
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Material You", style = MaterialTheme.typography.titleMedium, color = cs.onBackground)
                    Text(
                        "Use colours from your wallpaper (Android 12+). Currently unavailable — " +
                            "supermux always uses the brand palette.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = dynamicColor, onCheckedChange = onDynamicChange)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Text size", style = MaterialTheme.typography.titleMedium, color = cs.onBackground)
                        Text(
                            "Scales all text in the app — the whole screen previews it live.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${(textScale * 100).roundToInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (textScale == 1f) cs.onSurfaceVariant else cs.primary,
                    )
                }
                Slider(
                    value = textScale,
                    onValueChange = { raw ->
                        // Snap to 5% increments so the stored value stays clean.
                        val snapped = (raw * 20).roundToInt() / 20f
                        if (snapped != textScale) onTextScaleChange(snapped)
                    },
                    valueRange = TEXT_SCALE_MIN..TEXT_SCALE_MAX,
                    steps = 7,
                )
            }
        }
    }
}

// ─── UsageScreen ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(
    onBack: () -> Unit,
    onLoad: suspend () -> String?,
    onRedeem: suspend () -> CodexResetResult?,
) {
    val cs = MaterialTheme.colorScheme
    var usage by remember { mutableStateOf<UsageData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        loading = true
        loadFailed = false
        val raw = onLoad()
        if (raw == null) {
            loadFailed = true
        } else {
            usage = parseUsage(raw)
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Usage", color = cs.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = cs.onSurface,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { if (!loading) reloadKey++ }, enabled = !loading) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = "Refresh",
                            tint = if (loading) cs.onSurfaceVariant else cs.onSurface,
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
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading && usage == null -> {
                    CircularProgressIndicator(
                        color = cs.primary,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                loadFailed && usage == null -> {
                    Text(
                        "Unable to load usage data.",
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                else -> {
                    val u = usage
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        ClaudeUsageCard(u?.claude, u?.errors?.get("claude"))
                        CodexUsageCard(u?.codex, u?.errors?.get("codex"), onRedeem = onRedeem, onRefresh = { reloadKey++ })
                        CursorUsageCard(u?.cursor, u?.errors?.get("cursor"))
                        GrokUsageCard(u?.grok, u?.errors?.get("grok"))
                    }
                }
            }
        }
    }
}

// ─── Usage data model + parsing (defensive: missing sections → null) ───────────

/** resetsAt raw value: ISO string (claude/cursor) or unix-seconds number (codex). */
private data class UsageWindowData(
    val used: Double,
    val resetsAt: String?,
    val label: String? = null,
)
private data class ClaudeExtraUsageData(val enabled: Boolean, val monthlyLimit: Double, val usedCredits: Double, val currency: String)
private data class ClaudeUsageData(
    val fiveHour: UsageWindowData?,
    val sevenDay: UsageWindowData?,
    val sevenDaySonnet: UsageWindowData?,
    val sevenDayFable: UsageWindowData?,
    val extraUsage: ClaudeExtraUsageData?,
)
private data class CodexCreditsData(val hasCredits: Boolean, val balance: String)
private data class CodexUsageData(
    val plan: String?,
    val windows: List<UsageWindowData>,
    val credits: CodexCreditsData?,
    val limitReached: Boolean,
    val resetCredits: Int,
)
private data class CursorUsageData(
    val totalPercentUsed: Double,
    val totalSpendCents: Double,
    val includedCents: Double,
    val limitCents: Double,
    val spendAvailable: Boolean,
    val billingCycleStart: String?,
    val billingCycleEnd: String?,
)
private data class GrokUsageData(
    val plan: String?,
    val percentUsed: Double,
    val used: Double,
    val monthlyLimit: Double,
    val onDemandCap: Double,
    val onDemandUsed: Double,
    val prepaidBalance: Double,
    val billingPeriodStart: String?,
    val billingPeriodEnd: String?,
)
private data class UsageData(
    val claude: ClaudeUsageData?,
    val codex: CodexUsageData?,
    val cursor: CursorUsageData?,
    val grok: GrokUsageData?,
    val errors: Map<String, String>,
)

// Defensive accessors over org.json (Android SDK, no extra dep). Any missing /
// wrong-typed field collapses to null/0 so a partial payload still renders.
private fun JSONObject.objOrNull(key: String): JSONObject? =
    if (isNull(key)) null else optJSONObject(key)
private fun JSONObject.strOrNull(key: String): String? =
    if (isNull(key) || !has(key)) null else optString(key, "").ifEmpty { null }
private fun JSONObject.numOr(key: String, def: Double = 0.0): Double = optDouble(key, def)

private fun parseWindow(o: JSONObject?): UsageWindowData? {
    if (o == null) return null
    // resetsAt may be a string (ISO) or a number (unix secs) — keep it as text.
    val reset = if (o.isNull("resetsAt") || !o.has("resetsAt")) null else o.get("resetsAt").toString()
    return UsageWindowData(
        used = o.numOr("used"),
        resetsAt = reset,
        label = o.strOrNull("label"),
    )
}

private fun parseUsage(raw: String): UsageData {
    val root = runCatching { JSONObject(raw) }.getOrNull()
        ?: return UsageData(null, null, null, null, emptyMap())

    val claude = root.objOrNull("claude")?.let { o ->
        ClaudeUsageData(
            fiveHour = parseWindow(o.objOrNull("fiveHour")),
            sevenDay = parseWindow(o.objOrNull("sevenDay")),
            sevenDaySonnet = parseWindow(o.objOrNull("sevenDaySonnet")),
            sevenDayFable = parseWindow(o.objOrNull("sevenDayFable")),
            extraUsage = o.objOrNull("extraUsage")?.let { e ->
                ClaudeExtraUsageData(
                    enabled = e.optBoolean("enabled", false),
                    monthlyLimit = e.numOr("monthlyLimit"),
                    usedCredits = e.numOr("usedCredits"),
                    currency = e.strOrNull("currency") ?: "USD",
                )
            },
        )
    }

    val codex = root.objOrNull("codex")?.let { o ->
        CodexUsageData(
            plan = o.strOrNull("plan"),
            windows = o.optJSONArray("windows")?.let { windows ->
                buildList {
                    for (i in 0 until windows.length()) {
                        parseWindow(windows.optJSONObject(i))?.let(::add)
                    }
                }
            } ?: emptyList(),
            credits = o.objOrNull("credits")?.let { cr ->
                CodexCreditsData(
                    hasCredits = cr.optBoolean("hasCredits", false),
                    balance = cr.strOrNull("balance") ?: "0",
                )
            },
            limitReached = o.optBoolean("limitReached", false),
            resetCredits = o.optInt("resetCredits", 0),
        )
    }

    val cursor = root.objOrNull("cursor")?.let { o ->
        CursorUsageData(
            totalPercentUsed = o.numOr("totalPercentUsed"),
            totalSpendCents = o.numOr("totalSpendCents"),
            includedCents = o.numOr("includedCents"),
            limitCents = o.numOr("limitCents"),
            spendAvailable = o.optBoolean("spendAvailable", false),
            billingCycleStart = o.strOrNull("billingCycleStart"),
            billingCycleEnd = o.strOrNull("billingCycleEnd"),
        )
    }

    val grok = root.objOrNull("grok")?.let { o ->
        GrokUsageData(
            plan = o.strOrNull("plan"),
            percentUsed = o.numOr("percentUsed"),
            used = o.numOr("used"),
            monthlyLimit = o.numOr("monthlyLimit"),
            onDemandCap = o.numOr("onDemandCap"),
            onDemandUsed = o.numOr("onDemandUsed"),
            prepaidBalance = o.numOr("prepaidBalance"),
            billingPeriodStart = o.strOrNull("billingPeriodStart"),
            billingPeriodEnd = o.strOrNull("billingPeriodEnd"),
        )
    }

    val errors = root.objOrNull("errors")?.let { e ->
        buildMap {
            for (key in e.keys()) {
                e.strOrNull(key)?.let { put(key, it) }
            }
        }
    } ?: emptyMap()

    return UsageData(claude, codex, cursor, grok, errors)
}

// ─── Usage rendering helpers ───────────────────────────────────────────────────

private enum class ResetKind { CLAUDE, CODEX, CURSOR, GROK }

private fun clampPct(v: Double): Double = v.coerceIn(0.0, 100.0)

/** Bar colour by percentage: >=85 red, >=60 amber, else primary. */
@Composable
private fun barColor(pct: Double): Color {
    val cs = MaterialTheme.colorScheme
    val panes = LocalPanes.current
    return when {
        pct >= 85 -> cs.error
        pct >= 60 -> Color(panes.warning)
        else -> cs.primary
    }
}

/**
 * Reset formatting.
 *  - CLAUDE: resetsAt is an ISO-8601 string.
 *  - CODEX: resetsAt is unix SECONDS.
 *  - CURSOR: resetsAt is an ISO-8601 string (billing cycle end).
 * Shows "resets in Xh Ym" when <24h, else "resets <Mon D>".
 */
private fun formatReset(resetsAt: String?, kind: ResetKind): String {
    val s = resetsAt?.takeIf { it.isNotBlank() } ?: return ""
    val ms: Long = when (kind) {
        ResetKind.CODEX -> {
            val secs = s.toDoubleOrNull() ?: return ""
            (secs * 1000.0).toLong()
        }
        ResetKind.CLAUDE, ResetKind.CURSOR, ResetKind.GROK -> {
            // Try epoch-millis numeric first, else parse ISO-8601.
            s.toLongOrNull() ?: runCatching {
                Instant.parse(s).toEpochMilli()
            }.getOrElse { return "" }
        }
    }
    val diff = ms - System.currentTimeMillis()
    if (diff <= 0) return "resets soon"
    if (diff < 24L * 3600_000L) {
        val h = (diff / 3600_000L).toInt()
        val m = ((diff % 3600_000L) / 60_000L).toInt()
        return if (h > 0) "resets in ${h}h ${m}m" else "resets in ${m}m"
    }
    val date = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val month = date.month.getDisplayName(TextStyle.SHORT, Locale.US)
    return "resets $month ${date.dayOfMonth}"
}

private fun money(cents: Double): String = "$" + "%.2f".format(Locale.US, cents / 100.0)
private fun dollars(v: Double): String = "$" + "%.2f".format(Locale.US, v)

/** Outer usage card: rounded 12dp, border, title + plan subtitle, content slot. */
@Composable
private fun UsageCard(
    title: String,
    subtitle: String,
    enabled: Boolean,
    badge: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.5f
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cs.surfaceContainer.copy(alpha = alpha))
            .border(1.dp, cs.outline.copy(alpha = alpha), RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = cs.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(subtitle, color = cs.onSurfaceVariant, fontSize = 12.sp)
            }
            badge?.invoke()
        }
        content()
    }
}

/** A labelled usage window: label + "{pct}% used" + progress bar + reset line. */
@Composable
private fun UsageWindowRow(label: String, used: Double, resetsAt: String?, kind: ResetKind) {
    val cs = MaterialTheme.colorScheme
    val pct = clampPct(used)
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = cs.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("${used.roundToInt()}% used", color = cs.onSurface, fontSize = 12.sp)
        }
        // Progress bar (M3 LinearProgressIndicator; lambda-progress form, material3 1.4)
        LinearProgressIndicator(
            progress = { (pct / 100.0).toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = barColor(used),
            trackColor = cs.surfaceVariant,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        val reset = formatReset(resetsAt, kind)
        if (reset.isNotEmpty()) {
            Text(reset, color = cs.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** A footer row separated by a top border (extra usage / credits / spend). */
@Composable
private fun UsageFooterRow(label: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        HorizontalDivider(color = cs.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = cs.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(value, color = cs.onSurface, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ClaudeUsageCard(claude: ClaudeUsageData?, error: String?) {
    val cs = MaterialTheme.colorScheme
    UsageCard(title = "Claude", subtitle = "Pro plan", enabled = claude != null) {
        if (claude == null) {
            Text(error ?: "Not available", color = cs.onSurfaceVariant, fontSize = 12.sp)
        } else {
            claude.fiveHour?.let { UsageWindowRow("5-hour window", it.used, it.resetsAt, ResetKind.CLAUDE) }
            claude.sevenDay?.let { UsageWindowRow("7-day window", it.used, it.resetsAt, ResetKind.CLAUDE) }
            claude.sevenDaySonnet?.let { UsageWindowRow("7-day Sonnet", it.used, it.resetsAt, ResetKind.CLAUDE) }
            claude.sevenDayFable?.let { UsageWindowRow("7-day Fable", it.used, it.resetsAt, ResetKind.CLAUDE) }
            claude.extraUsage?.takeIf { it.enabled }?.let { e ->
                UsageFooterRow("Extra usage", "${dollars(e.usedCredits)} / ${dollars(e.monthlyLimit)}")
            }
        }
    }
}

@Composable
private fun CodexUsageCard(
    codex: CodexUsageData?,
    error: String?,
    onRedeem: (suspend () -> CodexResetResult?)? = null,
    onRefresh: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var redeeming by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    UsageCard(
        title = "Codex",
        subtitle = codex?.plan ?: "unknown",
        enabled = codex != null,
        badge = if (codex?.limitReached == true) {
            {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(cs.error.copy(alpha = 0.1f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text("limit reached", color = cs.error, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }
        } else null,
    ) {
        if (codex == null) {
            Text(error ?: "Not available", color = cs.onSurfaceVariant, fontSize = 12.sp)
        } else {
            codex.windows.forEach { window ->
                UsageWindowRow(window.label ?: "Usage window", window.used, window.resetsAt, ResetKind.CODEX)
            }
            codex.credits?.takeIf { it.hasCredits }?.let { cr ->
                UsageFooterRow("Credits balance", "${cr.balance} credits")
            }
            UsageFooterRow("🎟️ Resets banked", "${codex.resetCredits}")
            if (codex.resetCredits > 0 && onRedeem != null) {
                OutlinedButton(
                    onClick = { showDialog = true },
                    enabled = !redeeming,
                    modifier = Modifier.padding(top = 8.dp),
                    border = BorderStroke(1.dp, cs.outline),
                ) {
                    Text(if (redeeming) "Redeeming…" else "Use a reset", color = cs.onSurface, fontSize = 13.sp)
                }
            }
            if (note != null) {
                Text(
                    note!!,
                    color = cs.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
    if (showDialog && codex != null) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Use a banked reset?") },
            text = { Text("Spends 1 of ${codex.resetCredits} to clear your rate-limit windows now.") },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    scope.launch {
                        redeeming = true
                        val r = onRedeem?.invoke()
                        note = codexResetNote(r)
                        onRefresh()
                        redeeming = false
                    }
                }) { Text("Use reset", color = cs.primary) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel") }
            },
        )
    }
}

private fun codexResetNote(r: CodexResetResult?): String {
    if (r == null) return "Reset failed"
    return when (r.code) {
        "reset" -> "✓ Reset — cleared ${r.windowsReset} window${if (r.windowsReset == 1) "" else "s"}"
        "nothing_to_reset" -> "Nothing to reset right now"
        "no_credit" -> "No banked resets left"
        "already_redeemed" -> "That reset was already redeemed"
        else -> "Reset request completed"
    }
}

@Composable
private fun CursorUsageCard(cursor: CursorUsageData?, error: String?) {
    val cs = MaterialTheme.colorScheme
    UsageCard(title = "Cursor", subtitle = "Billing cycle", enabled = cursor != null) {
        if (cursor == null) {
            Text(error ?: "Not available", color = cs.onSurfaceVariant, fontSize = 12.sp)
        } else {
            // Cursor uses cents + ISO billing cycle end; reset line tracks billingCycleEnd.
            UsageWindowRow("Usage", cursor.totalPercentUsed, cursor.billingCycleEnd, ResetKind.CURSOR)
            if (cursor.spendAvailable) {
                UsageFooterRow("Spend", "${money(cursor.totalSpendCents)} / ${money(cursor.includedCents)} included")
            }
        }
    }
}

@Composable
private fun GrokUsageCard(grok: GrokUsageData?, error: String?) {
    val cs = MaterialTheme.colorScheme
    UsageCard(title = "Grok", subtitle = grok?.plan ?: "unknown", enabled = grok != null) {
        if (grok == null) {
            Text(error ?: "Not available", color = cs.onSurfaceVariant, fontSize = 12.sp)
        } else {
            UsageWindowRow("Monthly credits", grok.percentUsed, grok.billingPeriodEnd, ResetKind.GROK)
            if (grok.monthlyLimit > 0) {
                UsageFooterRow("Credits", "${grok.used.toLong()} / ${grok.monthlyLimit.toLong()}")
            }
            if (grok.onDemandCap > 0) {
                UsageFooterRow("On-demand", "${grok.onDemandUsed.toLong()} / ${grok.onDemandCap.toLong()}")
            }
            if (grok.prepaidBalance > 0) {
                UsageFooterRow("Prepaid balance", "${grok.prepaidBalance.toLong()}")
            }
        }
    }
}

// ─── ArchivedScreen ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedScreen(
    onBack: () -> Unit,
    workspaces: List<WorkspaceDto>,
    onRestore: (String) -> Unit,
    home: String,
    /** Same rule as the sidebar: empty live workspaces → session-archive fallback. */
    useWorkspaces: Boolean = true,
    loadArchivedSessions: suspend () -> List<ArchivedDto> = { emptyList() },
    onResumeSession: (String) -> Unit = {},
    loadLogs: suspend (String) -> List<LogEntry> = { emptyList() },
) {
    val cs = MaterialTheme.colorScheme
    var restoredIds by remember { mutableStateOf(setOf<String>()) }
    var selectedProject by remember { mutableStateOf<String?>(null) }
    var filterOpen by remember { mutableStateOf(false) }
    val groups = remember(workspaces, home) { groupArchivedWorkspaces(workspaces, home) }
    var sessionArchive by remember { mutableStateOf<List<ArchivedDto>>(emptyList()) }
    var sessionLoading by remember { mutableStateOf(false) }
    var resumedIds by remember { mutableStateOf(setOf<String>()) }
    var openedSession by remember { mutableStateOf<ArchivedDto?>(null) }
    val useSessionFallback = !useWorkspaces
    LaunchedEffect(useSessionFallback) {
        if (useSessionFallback) {
            sessionLoading = true
            sessionArchive = loadArchivedSessions()
            sessionLoading = false
        }
    }
    val sessionProjects = remember(sessionArchive, home) { archivedProjects(sessionArchive, home) }
    LaunchedEffect(groups, sessionProjects, useSessionFallback) {
        val keys = if (useSessionFallback) sessionProjects.map { it.key } else groups.map { it.key }
        if (selectedProject != null && keys.none { it == selectedProject }) {
            selectedProject = null
        }
    }
    openedSession?.let { session ->
        ArchivedChatScreen(
            sessionId = session.id,
            name = session.name,
            resumed = session.id in resumedIds,
            onBack = { openedSession = null },
            onResume = {
                onResumeSession(session.id)
                resumedIds = resumedIds + session.id
            },
            loadLogs = loadLogs,
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Archived", color = cs.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = cs.onSurface,
                        )
                    }
                },
                actions = {
                    val filterReady = if (useSessionFallback) sessionProjects.isNotEmpty() else groups.isNotEmpty()
                    if (filterReady) {
                        Box {
                            IconButton(onClick = { filterOpen = true }) {
                                Icon(
                                    Icons.Default.FilterList,
                                    contentDescription = "Filter by project",
                                    tint = if (selectedProject != null) cs.primary else cs.onSurface,
                                )
                            }
                            DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("All projects") },
                                    onClick = { selectedProject = null; filterOpen = false },
                                    trailingIcon = if (selectedProject == null) {
                                        { Icon(Icons.Default.Check, contentDescription = null) }
                                    } else null,
                                )
                                if (useSessionFallback) {
                                    sessionProjects.forEach { p ->
                                        DropdownMenuItem(
                                            text = { Text("${p.label}  (${p.count})") },
                                            onClick = { selectedProject = p.key; filterOpen = false },
                                            trailingIcon = if (selectedProject == p.key) {
                                                { Icon(Icons.Default.Check, contentDescription = null) }
                                            } else null,
                                        )
                                    }
                                } else {
                                    groups.forEach { g ->
                                        DropdownMenuItem(
                                            text = { Text("${g.label}  (${g.workspaces.size})") },
                                            onClick = { selectedProject = g.key; filterOpen = false },
                                            trailingIcon = if (selectedProject == g.key) {
                                                { Icon(Icons.Default.Check, contentDescription = null) }
                                            } else null,
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = cs.surfaceContainerHigh,
                ),
            )
        },
        containerColor = cs.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                useSessionFallback && sessionLoading -> CircularProgressIndicator(
                    color = cs.primary,
                    modifier = Modifier.align(Alignment.Center),
                )
                useSessionFallback && sessionArchive.isEmpty() -> Text(
                    "No archived sessions.",
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                useSessionFallback -> {
                    val visible = filterArchivedByProject(sessionArchive, selectedProject)
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                        items(visible, key = { it.id }) { session ->
                            ArchivedRow(
                                session = session,
                                home = home,
                                resumed = session.id in resumedIds,
                                onOpen = { openedSession = session },
                                onResume = {
                                    onResumeSession(session.id)
                                    resumedIds = resumedIds + session.id
                                },
                            )
                            HorizontalDivider(color = cs.outlineVariant)
                        }
                    }
                }
                workspaces.isEmpty() -> Text(
                    "No archived workspaces.",
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> {
                    val visible = groups.filter { selectedProject == null || it.key == selectedProject }
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                        visible.forEach { g ->
                            item(key = "hdr:${g.key}") {
                                Text(
                                    g.label,
                                    color = cs.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                                )
                            }
                            items(g.workspaces, key = { it.id }) { w ->
                                val row = deriveArchivedWorkspaceRow(w, home)
                                val restored = w.id in restoredIds
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(row.name, color = cs.onSurface, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                                        Text(
                                            row.pathLabel,
                                            color = cs.onSurfaceVariant,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                        )
                                        val ended = relTime(row.archivedAt)
                                        if (ended.isNotEmpty()) {
                                            Text("Archived $ended", color = cs.onSurfaceVariant, fontSize = 10.sp)
                                        }
                                    }
                                    TextButton(
                                        onClick = {
                                            onRestore(w.id)
                                            restoredIds = restoredIds + w.id
                                        },
                                        enabled = !restored,
                                    ) {
                                        Text(
                                            if (restored) "Restored" else "Restore",
                                            color = if (restored) cs.onSurfaceVariant else cs.primary,
                                            fontSize = 13.sp,
                                        )
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
}

@Composable
private fun ArchivedRow(session: ArchivedDto, home: String, resumed: Boolean, onOpen: () -> Unit, onResume: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(session.name, color = cs.onSurface, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(
                formatWorkdir(session.repo_root ?: session.workdir, home),
                color = cs.onSurfaceVariant,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
            val killed = relTime(session.killed_at)
            if (killed.isNotEmpty()) {
                Text("Ended $killed", color = cs.onSurfaceVariant, fontSize = 10.sp)
            }
        }
        TextButton(
            onClick = onResume,
            enabled = !resumed,
        ) {
            Text(
                if (resumed) "Resumed" else "Resume",
                color = if (resumed) cs.onSurfaceVariant else cs.primary,
                fontSize = 13.sp,
            )
        }
    }
}

// ─── ArchivedChatScreen (read-only timeline of an archived session) ────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArchivedChatScreen(
    sessionId: String,
    name: String,
    resumed: Boolean,
    onBack: () -> Unit,
    onResume: () -> Unit,
    loadLogs: suspend (String) -> List<LogEntry>,
) {
    val cs = MaterialTheme.colorScheme
    var messages by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(sessionId) {
        messages = loadLogs(sessionId)
        loading = false
    }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(name, color = cs.onSurface, fontSize = 16.sp, maxLines = 1)
                        Text("archived", color = cs.onSurfaceVariant, fontSize = 11.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = cs.onSurface,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onResume, enabled = !resumed) {
                        Text(
                            if (resumed) "Resumed" else "Resume",
                            color = if (resumed) cs.onSurfaceVariant else cs.primary,
                            fontSize = 13.sp,
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
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> CircularProgressIndicator(
                    color = cs.primary,
                    modifier = Modifier.align(Alignment.Center),
                )
                messages.isEmpty() -> Text(
                    "No messages.",
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> {
                    // Read-only: reuse chat timeline composables; no composer.
                    val timelineItems = remember(messages) { mergeTimeline(messages, emptyList()) }
                    LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(timelineItems) { item ->
                            TimelineItemRow(item)
                        }
                    }
                }
            }
        }
    }
}
