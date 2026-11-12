// Providers & accounts (#97 spec §2.2, §10.3, §11, §12): the coding agents a machine can run, one item each (its
// status line and Ready / Sign in again / Install), then Switch accounts at a limit and, under it, #92's Resume after
// a limit reset. A provider opens its own page (`ProviderDetailPage`), where its logins are.
package dev.supermux.ui.settings.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.AccountDto
import dev.supermux.net.AgentInstallStatus
import dev.supermux.state.AccountResult
import dev.supermux.ui.accounts.AccountsActions
import dev.supermux.ui.accounts.accountsFor
import dev.supermux.ui.i18n.Plurals
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.settings.AgentsLoadState
import dev.supermux.ui.settings.SettingsPlace
import dev.supermux.ui.settings.installFailureMessage
import dev.supermux.ui.settings.kit.KitDot
import dev.supermux.ui.settings.kit.KitGhostButton
import dev.supermux.ui.settings.kit.KitItem
import dev.supermux.ui.settings.kit.KitItemList
import dev.supermux.ui.settings.kit.KitItemStatus
import dev.supermux.ui.settings.kit.KitMachineScope
import dev.supermux.ui.settings.kit.KitMark
import dev.supermux.ui.settings.kit.KitNote
import dev.supermux.ui.settings.kit.KitOfflineNote
import dev.supermux.ui.settings.kit.KitPage
import dev.supermux.ui.settings.kit.KitTextAction
import dev.supermux.ui.settings.kit.KitToggleRow
import dev.supermux.ui.settings.kit.LocalSettingsMachines
import dev.supermux.ui.settings.kit.currentMachineName
import dev.supermux.ui.settings.kit.currentMachineOnline
import dev.supermux.ui.settings.kit.kitAnchor
import dev.supermux.ui.settings.kit.kitColors
import dev.supermux.ui.settings.normalizeInstallState
import dev.supermux.ui.settings.settingsTitle
import dev.supermux.ui.settings.statusLabel
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.i18n.localizedPlural
import dev.supermux.ui.settings.localizedInstallFailureMessage
import dev.supermux.ui.settings.localizedSettingsTitle
import dev.supermux.ui.settings.localizedStatusLabel
import kotlinx.coroutines.launch

/**
 * The list. [providers] is remembered by the caller above this page and the provider pages, so a login or install
 * started here is still running there. [showAccountSettings] is false in the setup wizard (no accounts wiring).
 */
@Composable
fun ProvidersPage(
    providers: ProvidersState,
    accounts: AccountsActions,
    onOpenProvider: (String) -> Unit,
    modifier: Modifier = Modifier,
    anchor: String? = null,
    onAnchorShown: () -> Unit = {},
    showAccountSettings: Boolean = true,
) {
    KitPage(
        localizedSettingsTitle(SettingsPlace(SettingsSection.Providers)),
        modifier,
        scope = { KitMachineScope() },
        lede = localized(Str.providers_lede, providerMachine()),
        anchor = anchor,
        onAnchorShown = onAnchorShown,
        tag = "agent_settings_screen",
    ) {
        if (!currentMachineOnline()) {
            KitOfflineNote()
        } else {
            val all by accounts.accounts.collectAsState(null)
            LaunchedEffect(accounts) {
                accounts.ensureAccounts()
                accounts.loadAutoSwitch()
            }
            ProvidersList(providers, all.orEmpty(), onOpenProvider)
            if (showAccountSettings) {
                Spacer(Modifier.height(18.dp))
                LimitRows(accounts)
            }
            KitNote(localized(Str.providers_composer_note))
        }
    }
}

/** The machine as a sentence names it mid-way: its name, or "this machine" when Settings knows none. */
@Composable
internal fun providerMachine(): String = LocalSettingsMachines.current?.current?.name ?: localized(Str.providers_this_machine)

/** A provider's name as the prototype writes it: Claude's agent is "Claude Code". */
internal fun providerName(kind: String): String = settingsTitle(SettingsPlace(SettingsSection.Providers, kind))

/** The two letters on a provider's mark. */
internal fun providerMark(kind: String): String = when (kind) {
    "claude" -> "C"
    "codex" -> "X"
    "opencode" -> "O"
    "cursor" -> "Cu"
    "grok" -> "G"
    "antigravity" -> "A"
    else -> kind.take(1).uppercase()
}

/** Loading, an empty answer, a failed one (it retries by itself), or the providers. */
@Composable
private fun ProvidersList(providers: ProvidersState, accounts: List<AccountDto>, onOpen: (String) -> Unit) {
    when (val s = providers.load) {
        AgentsLoadState.Loading -> KitNote(localized(Str.providers_loading), Modifier.testTag("agent_settings_loading"))
        AgentsLoadState.Empty -> Column(Modifier.testTag("agent_settings_empty")) {
            KitNote(localized(Str.providers_no_agents, currentMachineName()))
            KitTextAction(localized(Str.providers_try_again), providers::reload, Modifier.testTag("agent_settings_retry"))
        }
        is AgentsLoadState.Error -> Column(Modifier.testTag("agent_settings_error")) {
            KitNote(localized(Str.providers_couldnt_reach, providerMachine()))
            KitTextAction(localized(Str.providers_try_again), providers::reload, Modifier.testTag("agent_settings_retry"))
        }
        is AgentsLoadState.Ready -> KitItemList {
            s.statuses.forEach { status -> ProviderItem(status, providers.holder(status.kind), accounts, onOpen) }
        }
    }
}

@Composable
private fun ProviderItem(status: AgentInstallStatus, p: ProviderState, all: List<AccountDto>, onOpen: (String) -> Unit) {
    val kind = status.kind
    val mine = accountsFor(all, kind)
    val email = mine.firstOrNull { it.system }?.identity?.email?.takeIf { it.isNotBlank() }
    val added = mine.count { !it.system }
    val installFailed = !status.installed && !p.installActive &&
        (p.installRequestFailed || p.installTimedOut || normalizeInstallState(p.install?.state) == "failed")
    val base = when {
        installFailed -> when {
            p.installRequestFailed -> localized(Str.providers_install_start_failed)
            p.installTimedOut -> localized(Str.providers_install_timed_out)
            else -> localizedInstallFailureMessage(p.install)
        }
        status.authed && email != null -> localized(Str.providers_your_login_email, email)
        else -> localizedStatusLabel(status)
    }
    val line = if (added > 0 && status.installed) localizedPlural(Plurals.providers_line_more, added, base, added) else base
    // `agent_row_‹kind›` is the tag the shell's tests have always found a provider by.
    Box(Modifier.testTag("agent_row_$kind")) {
        KitItem(
            title = providerName(kind),
            modifier = Modifier.kitAnchor("provider-$kind").testTag("providers_$kind"),
            subtitle = line,
            subtitleWarn = installFailed,
            mark = KitMark.Letters(providerMark(kind)),
            onClick = { onOpen(kind) },
        ) {
            when {
                !status.installed && p.installActive ->
                    // Its own node (the item merges what it holds), so the running install can be found by its tag.
                    KitItemStatus(localized(Str.providers_installing), modifier = Modifier.semantics(mergeDescendants = true) {}.testTag("agent_install_running_$kind"))
                !status.installed ->
                    KitGhostButton(localized(Str.providers_install), onClick = p::startInstall, modifier = Modifier.testTag("agent_install_start_$kind"))
                p.loginActive -> KitItemStatus(localized(Str.providers_signing_in))
                status.signInExpired -> KitItemStatus(localized(Str.providers_sign_in_again), warn = true)
                status.authed || kind == "opencode" -> KitItemStatus(localized(Str.providers_ready), dot = KitDot.Ok)
            }
        }
    }
}

/** Switch accounts at a limit, and #92's Resume after a limit reset right under it (`/settings/accounts`). */
@Composable
private fun LimitRows(accounts: AccountsActions) {
    val scope = rememberCoroutineScope()
    val autoSwitch by accounts.autoSwitch.collectAsState(null)
    val autoResume by accounts.autoResume.collectAsState(null)
    var pendingSwitch by remember { mutableStateOf<Boolean?>(null) }
    var pendingResume by remember { mutableStateOf<Boolean?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val saveFailed = localized(Str.providers_setting_save_failed)

    fun save(on: Boolean, pending: (Boolean?) -> Unit, call: suspend (Boolean) -> AccountResult<Boolean>) {
        pending(on)
        error = null
        scope.launch {
            val r = call(on)
            pending(null)
            if (r is AccountResult.Failed) error = r.message ?: saveFailed
        }
    }

    KitToggleRow(
        localized(Str.providers_auto_switch),
        checked = pendingSwitch ?: autoSwitch ?: false,
        onCheckedChange = { save(it, { v -> pendingSwitch = v }, accounts.setAutoSwitch) },
        modifier = Modifier.kitAnchor("auto-switch").testTag("accounts-autoswitch"),
        description = localized(Str.providers_auto_switch_description),
    )
    KitToggleRow(
        localized(Str.providers_auto_resume),
        // On unless turned off.
        checked = pendingResume ?: autoResume ?: true,
        onCheckedChange = { save(it, { v -> pendingResume = v }, accounts.setAutoResume) },
        modifier = Modifier.kitAnchor("limit-resume").testTag("accounts-autoresume"),
        description = localized(Str.providers_auto_resume_description),
    )
    error?.let {
        Text(it, fontSize = 12.sp, color = kitColors().warn, modifier = Modifier.padding(top = 6.dp).testTag("accounts-autoswitch-error"))
    }
}
