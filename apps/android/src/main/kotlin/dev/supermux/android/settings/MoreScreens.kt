package dev.supermux.android.settings

import android.app.Application
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.AppViewModel
import androidx.compose.ui.platform.LocalContext
import dev.supermux.android.update.AppUpdatePage
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.TEXT_SCALE_MAX
import dev.supermux.ui.theme.TEXT_SCALE_MIN
import kotlin.math.roundToInt
import dev.supermux.net.LspInstallResult
import dev.supermux.net.LspMutationResult
import dev.supermux.net.LspServer
import dev.supermux.proto.ServerFrame
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
import dev.supermux.ui.settings.VoiceSettingsActions
import dev.supermux.ui.settings.VoiceSettingsScreen
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
    /** Voice + dictation glossary: one holder since E5 — `ui/settings/VoiceSettingsScreen.kt`. */
    voiceActions: VoiceSettingsActions,
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
            // Shared since E5 — Android gained the load/save error states and the "failure is not
            // an empty glossary" rule; the glossary sub-page and its own Back moved with it, and
            // the page still owns the compact stack (this hub passes `compactTopBar = false`).
            SettingsSection.Voice -> VoiceSettingsScreen(
                actions = voiceActions,
                onBack = scope.onClose,
                topBarShown = scope.topBarShown,
            )
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
