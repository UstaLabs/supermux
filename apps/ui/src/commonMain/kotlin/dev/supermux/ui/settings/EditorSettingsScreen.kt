// The one Editor settings screen for both apps (cluster E7).
//
// Base = Android's `MoreScreens.kt` `EditorSettingsPage` — the only side that had one: two
// device-local preferences (soft wrap + font size, now `UiPrefs`/`SettingsKeys.EDITOR_*` rather
// than SharedPreferences) above the broker-backed [LspSettingsScreen], all in one scroll. Desktop
// reached `LspSettingsScreen` bare from its hub's `EditorLsp` section and had no way at all to
// change wrap or font size from Settings; folding this in gives it both without touching its
// editor behaviour — the steppers write exactly the keys `WebCodeEditor`/`DiffView` already read.
//
// The whole page scrolls (the LSP list plus its add-form is tall), so the embedded LSP screen is
// asked NOT to scroll: a vertical scroll nested in a vertical scroll measures to zero height.
package dev.supermux.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.AddCustomLspArgs
import dev.supermux.net.LspInstallResult
import dev.supermux.net.LspMutationResult
import dev.supermux.net.LspServer
import dev.supermux.proto.ServerFrame
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.prefs.EDITOR_FONT_DEFAULT
import dev.supermux.ui.prefs.EDITOR_FONT_MAX
import dev.supermux.ui.prefs.EDITOR_FONT_MIN
import dev.supermux.ui.prefs.EDITOR_LINE_WRAP_DEFAULT
import dev.supermux.ui.prefs.LocalUiPrefs
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Editor settings: the device-local appearance preferences plus the broker's language servers.
 *
 * The LSP half is [LspSettingsScreen] verbatim — this screen owns the chrome and the scroll and
 * passes the seven LSP lambdas straight through, so both hosts keep the wiring they already had.
 *
 * @param onBack leave the screen (the hub's `SettingsSlotScope.onClose`).
 * @param topBarShown the hub already painted a `TopAppBar` for this detail.
 * @param standalone the screen is its own destination rather than a hub section, so it paints
 *   title + Back at every width (nothing on either host routes here that way today; the parameter
 *   keeps the signature the same shape as every other shared settings screen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorSettingsScreen(
    lspLoad: suspend () -> List<LspServer>,
    lspToggle: suspend (id: String, enabled: Boolean) -> List<LspServer>?,
    lspInstall: suspend (id: String) -> LspInstallResult?,
    lspInstallLog: StateFlow<Map<String, List<String>>>,
    lspInstallDone: StateFlow<Map<String, ServerFrame.LspInstallDone>>,
    lspAddCustom: suspend (AddCustomLspArgs) -> LspMutationResult?,
    lspRemoveCustom: suspend (id: String) -> LspMutationResult?,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    topBarShown: Boolean = false,
    standalone: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val compact = LocalWindowWidthClass.current == WindowWidthClass.Compact
    if ((standalone || compact) && !topBarShown) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Editor", color = cs.onSurface) },
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("editor_settings_back"),
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
            EditorSettingsBody(
                lspLoad, lspToggle, lspInstall, lspInstallLog, lspInstallDone,
                lspAddCustom, lspRemoveCustom, modifier.padding(padding), onBack,
            )
        }
    } else {
        EditorSettingsBody(
            lspLoad, lspToggle, lspInstall, lspInstallLog, lspInstallDone,
            lspAddCustom, lspRemoveCustom, modifier, onBack,
        )
    }
}

@Composable
private fun EditorSettingsBody(
    lspLoad: suspend () -> List<LspServer>,
    lspToggle: suspend (id: String, enabled: Boolean) -> List<LspServer>?,
    lspInstall: suspend (id: String) -> LspInstallResult?,
    lspInstallLog: StateFlow<Map<String, List<String>>>,
    lspInstallDone: StateFlow<Map<String, ServerFrame.LspInstallDone>>,
    lspAddCustom: suspend (AddCustomLspArgs) -> LspMutationResult?,
    lspRemoveCustom: suspend (id: String) -> LspMutationResult?,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val prefs = LocalUiPrefs.current
    val scope = rememberCoroutineScope()
    val lineWrap by prefs.editorLineWrap.collectAsState(EDITOR_LINE_WRAP_DEFAULT)
    val fontSize by prefs.editorFontSize.collectAsState(EDITOR_FONT_DEFAULT)

    Column(
        modifier
            .fillMaxSize()
            .background(cs.background)
            .verticalScroll(rememberScrollState())
            .testTag("editor_settings_screen"),
    ) {
        // 1. Wrap long lines
        SettingsToggleRow(
            label = "Wrap long lines",
            desc = "Wrap instead of horizontal scroll.",
        ) {
            Switch(
                checked = lineWrap,
                onCheckedChange = { on -> scope.launch { prefs.putEditorLineWrap(on) } },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = cs.onPrimary,
                    checkedTrackColor = cs.primary,
                ),
                modifier = Modifier.testTag("editor_wrap_switch"),
            )
        }
        HorizontalDivider(color = cs.outlineVariant)

        // 2. Font size stepper (clamped 10..24 by UiPrefs itself, so a stuck button cannot drift)
        SettingsToggleRow(
            label = "Font size",
            desc = "Code editor text size.",
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StepperButton(
                    text = "−",
                    enabled = fontSize > EDITOR_FONT_MIN,
                    testTag = "editor_font_minus",
                ) { scope.launch { prefs.putEditorFontSize(fontSize - 1) } }
                Text(
                    fontSize.toString(),
                    color = cs.onSurface,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag("editor_font_size"),
                )
                StepperButton(
                    text = "+",
                    enabled = fontSize < EDITOR_FONT_MAX,
                    testTag = "editor_font_plus",
                ) { scope.launch { prefs.putEditorFontSize(fontSize + 1) } }
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
            // This page owns the Back arrow and the scroll; the LSP half is an embedded section.
            onBack = onBack,
            showTopBar = false,
            scrollable = false,
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** Label/description + trailing control (Android's `SettingsRow`, unchanged metrics). */
@Composable
private fun SettingsToggleRow(
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

/**
 * Small bordered −/+ button. The 32dp visual is the same on both hosts; the TAP target grows to
 * the 48dp minimum only where a finger is the pointer (`LocalPointerAvailable == false`), so a
 * mouse-driven window keeps the dense row it always had.
 */
@Composable
private fun StepperButton(
    text: String,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val alpha = if (enabled) 1f else 0.4f
    val touch = !LocalPointerAvailable.current
    Box(
        if (touch) Modifier.minimumInteractiveComponentSize() else Modifier,
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(cs.surfaceContainer)
                .border(1.dp, cs.outline.copy(alpha = alpha), RoundedCornerShape(6.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .testTag(testTag),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = cs.onSurface.copy(alpha = alpha), fontSize = 18.sp)
        }
    }
}
