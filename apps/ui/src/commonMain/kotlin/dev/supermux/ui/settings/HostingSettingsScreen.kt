// Settings ▸ Hosting (spec: desktop hosting lifecycle, "Settings ▸ Hosting").
//
// Platform-neutral: the page takes a [HostingUiState] and [HostingActions], so `apps/ui` never names
// a desktop class. Desktop builds the state from its `HostSupervisor`; every other host hides the
// row (`Caps.localBroker` is false there) and never reaches this screen.
package dev.supermux.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Space
import dev.supermux.ui.widgets.AlertDialog
import dev.supermux.ui.widgets.SettingsDetailMaxWidth
import dev.supermux.ui.widgets.SettingsSectionHeader

/**
 * What Settings ▸ Hosting shows. Built by the host platform (desktop: from its supervisor).
 *
 * @property statusDot "🟢" / "🟡" / "🔴" / "⚪".
 * @property statusText e.g. "Running · 3 sessions · v1.5.0".
 * @property readOnly the broker was set up outside the app and the user chose to leave it alone.
 * @property localUrl the address other devices on this network reach it at; null hides the row.
 * @property relayUrl the relay address; null hides the Remote row.
 * @property logTail the last log lines; non-empty only when it can't start.
 * @property failed it can't start: the Restart button becomes "Try again".
 * @property restartEnabled false while it is starting/restarting, or when it is not ours to restart.
 * @property canPair a pairing code can be minted (the broker is running).
 * @property backgroundError why "Keep running in the background" could not be applied, if it couldn't.
 * @property gitMissing the local broker reports no usable git (a Mac without the Command Line Tools).
 */
data class HostingUiState(
    val hosting: Boolean,
    val statusDot: String,
    val statusText: String,
    val readOnly: Boolean,
    val localUrl: String?,
    val relayUrl: String?,
    val relay: Boolean,
    val background: Boolean,
    val sessions: Int,
    val logTail: List<String>,
    val failed: Boolean = false,
    val restartEnabled: Boolean = true,
    val canPair: Boolean = true,
    val backgroundError: String? = null,
    val gitMissing: Boolean = false,
)

/** What the page can ask the host platform to do. Every call returns at once; work runs elsewhere. */
interface HostingActions {
    fun setHosting(on: Boolean)
    fun setBackground(on: Boolean)
    fun setRelay(on: Boolean)
    fun restart()
    fun retry()
    fun showLog()
    fun pairDevice()
    fun manageIt()
    /** Start installing git (desktop macOS: `xcode-select --install`). */
    fun installGit()
}

/** The copy the page shows, as constants so the tests assert the exact strings. */
object HostingCopy {
    const val TITLE = "Host on this computer"
    const val SUBTITLE = "Your agents run here. Your phone and other devices connect to it."
    const val OFF = "This app connects to brokers elsewhere."
    const val RELAY = "Remote access through relay"
    const val BACKGROUND = "Keep running in the background"
    const val BACKGROUND_HELP = "Your agents stay reachable after you quit, sign out or restart."
    const val BACKGROUND_READ_ONLY = "Managed by its own service"
    const val MANAGE = "Let the app manage it"
    const val GIT_MISSING =
        "Git isn't installed on this Mac. Agents can't use git until you install Apple's Command Line Tools."
    const val INSTALL_GIT = "Install…"

    /** The confirm shown before turning hosting off. */
    fun stopConfirm(sessions: Int): String {
        val stops = when {
            sessions <= 0 -> "This stops supermux and removes it from startup."
            sessions == 1 -> "This stops supermux and your 1 running session, and removes it from startup."
            else -> "This stops supermux and your $sessions running sessions, and removes it from startup."
        }
        return "Stop hosting? $stops Your data stays in ~/.mux."
    }
}

/**
 * Settings ▸ Hosting.
 *
 * @param onBack leave the screen (the hub's `SettingsSlotScope.onClose`).
 * @param topBarShown the hub already painted a `TopAppBar` for this detail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostingSettingsScreen(
    state: HostingUiState,
    actions: HostingActions,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    topBarShown: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val compact = LocalWindowWidthClass.current == WindowWidthClass.Compact
    if (compact && !topBarShown) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Hosting", color = cs.onSurface) },
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("hosting_settings_back"),
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
            HostingSettingsBody(state, actions, modifier.padding(padding))
        }
    } else {
        HostingSettingsBody(state, actions, modifier)
    }
}

@Composable
private fun HostingSettingsBody(
    state: HostingUiState,
    actions: HostingActions,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val platform = LocalPlatform.current
    var confirmOff by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .background(cs.background)
            .verticalScroll(rememberScrollState())
            .testTag("hosting_settings_screen"),
    ) {
        Column(
            Modifier
                .widthIn(max = SettingsDetailMaxWidth)
                .padding(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.lg),
        ) {
            SwitchRow(
                title = HostingCopy.TITLE,
                supporting = HostingCopy.SUBTITLE,
                checked = state.hosting,
                onCheckedChange = { on -> if (on) actions.setHosting(true) else confirmOff = true },
                tag = "hosting_switch",
            )

            if (!state.hosting) {
                Text(
                    HostingCopy.OFF,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.testTag("hosting_off_text"),
                )
                return@Column
            }

            // ── Status ──
            LabeledRow("Status") {
                Text(
                    "${state.statusDot} ${state.statusText}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onBackground,
                    modifier = Modifier.testTag("hosting_status"),
                )
            }
            if (state.logTail.isNotEmpty()) {
                Text(
                    state.logTail.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily),
                    color = cs.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Space.sm))
                        .background(cs.surfaceContainerHigh)
                        .padding(Space.md)
                        .testTag("hosting_log_tail"),
                )
            }

            if (state.gitMissing) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Space.sm))
                        .background(cs.surfaceContainerHigh)
                        .padding(Space.md)
                        .testTag("hosting_git_missing"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        HostingCopy.GIT_MISSING,
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(Space.md))
                    OutlinedButton(
                        onClick = actions::installGit,
                        modifier = Modifier.testTag("hosting_install_git"),
                    ) { Text(HostingCopy.INSTALL_GIT) }
                }
            }

            // ── Address ──
            if (state.localUrl != null || state.relayUrl != null) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    SettingsSectionHeader("ADDRESS")
                    state.localUrl?.let { url ->
                        LabeledRow("Local") {
                            AddressText(url, "hosting_local_url")
                        }
                    }
                    state.relayUrl?.let { url ->
                        LabeledRow("Remote") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) { AddressText(url, "hosting_remote_url") }
                                TextButton(
                                    onClick = { platform.copyToClipboard(url) },
                                    modifier = Modifier.testTag("hosting_copy_remote"),
                                ) { Text("Copy") }
                            }
                        }
                    }
                }
            }

            SwitchRow(
                title = HostingCopy.RELAY,
                supporting = null,
                checked = state.relay,
                enabled = !state.readOnly,
                onCheckedChange = actions::setRelay,
                tag = "hosting_relay",
            )

            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                SwitchRow(
                    title = HostingCopy.BACKGROUND,
                    supporting = if (state.readOnly) HostingCopy.BACKGROUND_READ_ONLY else HostingCopy.BACKGROUND_HELP,
                    checked = state.background,
                    enabled = !state.readOnly,
                    onCheckedChange = actions::setBackground,
                    tag = "hosting_background",
                )
                state.backgroundError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.error,
                        modifier = Modifier.testTag("hosting_background_error"),
                    )
                }
            }

            if (state.readOnly) {
                OutlinedButton(
                    onClick = actions::manageIt,
                    modifier = Modifier.testTag("hosting_manage"),
                ) { Text(HostingCopy.MANAGE) }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                if (state.failed || state.logTail.isNotEmpty()) {
                    OutlinedButton(
                        onClick = actions::retry,
                        modifier = Modifier.testTag("hosting_retry"),
                    ) { Text("Try again") }
                } else {
                    OutlinedButton(
                        onClick = actions::restart,
                        enabled = state.restartEnabled,
                        modifier = Modifier.testTag("hosting_restart"),
                    ) { Text("Restart") }
                }
                OutlinedButton(
                    onClick = actions::showLog,
                    modifier = Modifier.testTag("hosting_show_log"),
                ) { Text("Show log") }
                OutlinedButton(
                    onClick = actions::pairDevice,
                    enabled = state.canPair,
                    modifier = Modifier.testTag("hosting_pair"),
                ) { Text("Pair a device…") }
            }
        }
    }

    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            text = { Text(HostingCopy.stopConfirm(state.sessions), modifier = Modifier.testTag("hosting_off_confirm_text")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmOff = false
                        actions.setHosting(false)
                    },
                    modifier = Modifier.testTag("hosting_off_confirm"),
                ) { Text("Stop hosting", color = cs.error) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmOff = false },
                    modifier = Modifier.testTag("hosting_off_cancel"),
                ) { Text("Cancel") }
            },
            modifier = Modifier.testTag("hosting_off_dialog"),
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    supporting: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
    enabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().testTag("${tag}_row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = cs.onBackground)
            supporting?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(Space.md))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.testTag(tag),
        )
    }
}

@Composable
private fun LabeledRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = LabelWidth),
        )
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun AddressText(url: String, tag: String) {
    Text(
        url,
        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.testTag(tag),
    )
}

private val LabelWidth = Space.xxl * 2
