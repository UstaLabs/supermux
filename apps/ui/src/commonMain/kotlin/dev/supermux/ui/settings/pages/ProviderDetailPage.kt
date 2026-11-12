// A provider's page (#97 spec §2.2, §10.3, §11, §12): its logins — Your login (the machine's own sign-in of that
// provider: today's login flow, and "Use a token instead", which saves the machine's own token and never adds a
// login), the added logins with their usage and Remove, and Add a login (#106's `ProviderLogins`) — or, for OpenCode,
// its key and the model providers inside it; and Install while the provider is missing.
package dev.supermux.ui.settings.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.AccountDto
import dev.supermux.net.AgentInstallStatus
import dev.supermux.net.AgentLoginState
import dev.supermux.ui.accounts.AccountsActions
import dev.supermux.ui.accounts.OwnLogin
import dev.supermux.ui.accounts.ProviderLogins
import dev.supermux.ui.accounts.accountUnavailableText
import dev.supermux.ui.accounts.accountsFor
import dev.supermux.ui.accounts.signInExpired
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.retain.rememberRetainedOnPage
import dev.supermux.ui.retain.rememberRetainedScope
import dev.supermux.ui.settings.AgentSettingsActions
import dev.supermux.ui.settings.AgentsLoadState
import dev.supermux.ui.settings.LOGIN_KINDS
import dev.supermux.ui.settings.installFailureMessage
import dev.supermux.ui.settings.installLogTail
import dev.supermux.ui.settings.kit.KitCopyCommand
import dev.supermux.ui.settings.kit.KitGhostButton
import dev.supermux.ui.settings.kit.KitGlyphs
import dev.supermux.ui.settings.kit.KitHairline
import dev.supermux.ui.settings.kit.KitMachineScope
import dev.supermux.ui.settings.kit.KitNote
import dev.supermux.ui.settings.kit.KitOfflineNote
import dev.supermux.ui.settings.kit.KitPage
import dev.supermux.ui.settings.kit.KitSheet
import dev.supermux.ui.settings.kit.KitTextAction
import dev.supermux.ui.settings.kit.KitTextField
import dev.supermux.ui.settings.kit.currentMachineOnline
import dev.supermux.ui.settings.kit.kitColors
import dev.supermux.ui.settings.kit.kitMetrics
import dev.supermux.ui.settings.normalizeInstallState
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.resources.Res
import dev.supermux.ui.resources.providers_api_key_hint
import dev.supermux.ui.resources.providers_cancel
import dev.supermux.ui.resources.providers_claude_token_hint
import dev.supermux.ui.resources.providers_close
import dev.supermux.ui.resources.providers_codex_hint
import dev.supermux.ui.resources.providers_copy_link
import dev.supermux.ui.resources.providers_couldnt_reach
import dev.supermux.ui.resources.providers_cursor_key_hint
import dev.supermux.ui.resources.providers_enter_code_there
import dev.supermux.ui.resources.providers_gemini_key_hint
import dev.supermux.ui.resources.providers_generating_link
import dev.supermux.ui.resources.providers_install
import dev.supermux.ui.resources.providers_install_start_failed
import dev.supermux.ui.resources.providers_install_timed_out_machine
import dev.supermux.ui.resources.providers_installed
import dev.supermux.ui.resources.providers_installing
import dev.supermux.ui.resources.providers_loading
import dev.supermux.ui.resources.providers_not_installed
import dev.supermux.ui.resources.providers_not_on_machine
import dev.supermux.ui.resources.providers_not_signed_in
import dev.supermux.ui.resources.providers_open_page_hint
import dev.supermux.ui.resources.providers_open_sign_in_page
import dev.supermux.ui.resources.providers_openai_key_hint
import dev.supermux.ui.resources.providers_paste_api_key_title
import dev.supermux.ui.resources.providers_paste_code
import dev.supermux.ui.resources.providers_paste_code_hint
import dev.supermux.ui.resources.providers_paste_token_title
import dev.supermux.ui.resources.providers_retry_install
import dev.supermux.ui.resources.providers_save
import dev.supermux.ui.resources.providers_save_failed
import dev.supermux.ui.resources.providers_saving
import dev.supermux.ui.resources.providers_sign_in
import dev.supermux.ui.resources.providers_sign_in_again
import dev.supermux.ui.resources.providers_sign_in_cancelled
import dev.supermux.ui.resources.providers_sign_in_expired
import dev.supermux.ui.resources.providers_sign_in_failed
import dev.supermux.ui.resources.providers_sign_in_start_failed
import dev.supermux.ui.resources.providers_sign_in_timed_out
import dev.supermux.ui.resources.providers_signed_in
import dev.supermux.ui.resources.providers_stop_watching
import dev.supermux.ui.resources.providers_stop_watching_hint
import dev.supermux.ui.resources.providers_stopped_watching
import dev.supermux.ui.resources.providers_submit
import dev.supermux.ui.resources.providers_this_machines_login
import dev.supermux.ui.resources.providers_token_description
import dev.supermux.ui.resources.providers_try_again
import dev.supermux.ui.resources.providers_unknown_error
import dev.supermux.ui.resources.providers_use_token
import dev.supermux.ui.resources.providers_waiting_approval
import dev.supermux.ui.resources.providers_watch_progress
import dev.supermux.ui.settings.SettingsPlace
import dev.supermux.ui.settings.localizedInstallFailureMessage
import dev.supermux.ui.settings.localizedSettingsTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * [agent]'s page. [providers] is the Providers section's (remembered above the list and this page). [onBack] goes back
 * to the list: the hub's `onClose` on a sub-page, shown as "‹ Providers & accounts" on a wide window. [backOnPhone]
 * shows it on a phone too — the setup wizard opens a provider in place and has no top bar to go back with.
 */
@Composable
fun ProviderDetailPage(
    agent: String,
    providers: ProvidersState,
    agents: AgentSettingsActions,
    accounts: AccountsActions,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    backOnPhone: Boolean = false,
    showAddLogin: Boolean = true,
) {
    val sectionTitle = localizedSettingsTitle(SettingsPlace(SettingsSection.Providers))
    KitPage(
        providerName(agent),
        modifier,
        scope = { KitMachineScope() },
        backLabel = sectionTitle,
        onBack = onBack,
        tag = "provider_page_$agent",
    ) {
        if (backOnPhone && kitMetrics().phone) {
            KitTextAction(sectionTitle, onBack, Modifier.padding(bottom = 10.dp).testTag("settings_page_back"), leading = KitGlyphs.Back)
        }
        if (!currentMachineOnline()) {
            KitOfflineNote()
        } else {
            val all by accounts.accounts.collectAsState(null)
            LaunchedEffect(accounts) { accounts.ensureAccounts() }
            when (val s = providers.load) {
                AgentsLoadState.Loading -> KitNote(localized(Res.string.providers_loading))
                AgentsLoadState.Empty, is AgentsLoadState.Error -> Column {
                    KitNote(localized(Res.string.providers_couldnt_reach, providerMachine()))
                    KitTextAction(localized(Res.string.providers_try_again), providers::reload, Modifier.testTag("provider_retry"))
                }
                is AgentsLoadState.Ready -> {
                    val status = s.statuses.firstOrNull { it.kind == agent }
                    if (status == null) {
                        KitNote(localized(Res.string.providers_not_on_machine, providerName(agent), providerMachine()))
                    } else {
                        ProviderBody(status, providers.holder(agent), agents, accounts, accountsFor(all.orEmpty(), agent), providers::reload, showAddLogin)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderBody(
    status: AgentInstallStatus,
    p: ProviderState,
    agents: AgentSettingsActions,
    accounts: AccountsActions,
    mine: List<AccountDto>,
    onChanged: () -> Unit,
    showAddLogin: Boolean,
) {
    val kind = status.kind
    when {
        !status.installed -> InstallSection(p, kind)
        // OpenCode has no sign-in of its own and no "Add a login" (spec §11): its subscription, its key and the model
        // providers inside it. Logins added before stay listed.
        kind == "opencode" -> {
            OpenCodeSection(p, agents)
            val added = mine.filter { !it.system }
            if (added.isNotEmpty()) ProviderLogins(kind, added, accounts, onSignInSystem = {})
        }
        else -> {
            val tokenForm = rememberRetainedOnPage("agents:key:$kind") { SecretForm() }
            ProviderLogins(
                kind,
                mine,
                accounts,
                onSignInSystem = p::startLogin,
                systemLoginExtra = { YourLoginExtra(status, p, agents, onUseToken = { tokenForm.open = true }) },
                own = ownLogin(status, p, mine.firstOrNull { it.system }),
                showAddLogin = showAddLogin,
            )
            if (kind != "grok") TokenSheet(kind, tokenForm, save = { agents.agentSaveSecret(kind, it) }, onSaved = onChanged)
        }
    }
}

/**
 * Your login as the machine's CLI reports it: signed in (its email when the logins know it), not signed in, expired,
 * or refused; and Sign in / Sign in again, today's login flow. Hidden while that flow is on screen under it.
 */
@Composable
private fun ownLogin(status: AgentInstallStatus, p: ProviderState, system: AccountDto?): OwnLogin {
    val expired = status.signInExpired || (system != null && signInExpired(system))
    val refused = system?.takeIf { !expired && it.unavailable != null }?.let(::accountUnavailableText)
    val email = system?.identity?.email?.takeIf { it.isNotBlank() }
    val subtitle = when {
        expired -> localized(Res.string.providers_sign_in_expired)
        refused != null -> refused
        status.authed -> email ?: localized(Res.string.providers_this_machines_login)
        else -> localized(Res.string.providers_not_signed_in)
    }
    return OwnLogin(subtitle, warn = expired || refused != null) {
        if (status.kind in LOGIN_KINDS && !p.loginActive) {
            KitGhostButton(
                if (!status.authed && !expired) localized(Res.string.providers_sign_in) else localized(Res.string.providers_sign_in_again),
                onClick = p::startLogin,
                modifier = Modifier.testTag("agent_login_start_${status.kind}"),
            )
        }
    }
}

/** Under Your login: the login flow while it runs, why the last one stopped, and "Use a token instead". */
@Composable
private fun YourLoginExtra(status: AgentInstallStatus, p: ProviderState, agents: AgentSettingsActions, onUseToken: () -> Unit) {
    val kind = status.kind
    val codexHint = kind == "codex" && !status.authed && !p.loginActive
    val flow = p.loginActive || p.login?.phase == "success" || p.loginStartFailed || p.loginTimedOut
    // Grok signs in only through its own login: with no flow on screen there is nothing to draw.
    if (!flow && !codexHint && kind == "grok") return
    Column(Modifier.fillMaxWidth().padding(start = 38.dp, top = 4.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LoginLines(p, agents)
        if (codexHint) {
            KitNote(localized(Res.string.providers_codex_hint))
        }
        // Grok has no token or key to paste.
        if (kind != "grok") KitTextAction(localized(Res.string.providers_use_token), onUseToken, Modifier.testTag("provider_use_token"))
    }
    KitHairline()
}

/** The login flow while it runs or has just ended, and why the last one could not start or timed out. */
@Composable
internal fun LoginLines(p: ProviderState, agents: AgentSettingsActions) {
    val kind = p.kind
    if (p.loginActive || p.login?.phase == "success") LoginFlow(p, agents)
    if (p.loginStartFailed) WarnLine(localized(Res.string.providers_sign_in_start_failed), "agent_login_start_failed_$kind")
    if (p.loginTimedOut) WarnLine(localized(Res.string.providers_sign_in_timed_out, providerMachine()), "agent_login_timeout_$kind")
}

@Composable
internal fun WarnLine(text: String, tag: String? = null) {
    Text(
        text,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = kitColors().warn,
        modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
    )
}

@Composable
private fun DimLine(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 12.5.sp, lineHeight = 19.sp, color = kitColors().dim, modifier = modifier)
}

/** The code pasted back from the sign-in page, kept for the page on display (#89): a rotation emptied it. */
private class LoginCodeForm {
    var code by mutableStateOf("")
}

/** Today's CLI login flow, in words: the page to open, the device code, the code to paste back, the outcome. */
@Composable
private fun LoginFlow(p: ProviderState, agents: AgentSettingsActions) {
    val kind = p.kind
    val scope = rememberCoroutineScope()
    val form = rememberRetainedOnPage("agents:code:$kind") { LoginCodeForm() }
    val cancel = {
        form.code = ""
        p.cancelLogin(scope, agents)
    }
    Column(Modifier.fillMaxWidth().testTag("agent_login_flow_$kind"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val login = p.login
        when (login?.phase) {
            null -> {
                DimLine(localized(Res.string.providers_generating_link), Modifier.testTag("agent_login_generating"))
                KitTextAction(localized(Res.string.providers_cancel), cancel, Modifier.testTag("agent_login_cancel"))
            }
            "success" -> Text(localized(Res.string.providers_signed_in), fontSize = 12.5.sp, color = kitColors().ok, modifier = Modifier.testTag("agent_login_success_$kind"))
            "failed", "cancelled" -> {
                if (login.phase == "failed") {
                    WarnLine(localized(Res.string.providers_sign_in_failed, login.error ?: localized(Res.string.providers_unknown_error)), "agent_login_failed_$kind")
                } else {
                    DimLine(localized(Res.string.providers_sign_in_cancelled))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KitTextAction(localized(Res.string.providers_try_again), p::startLogin, Modifier.testTag("agent_login_retry"))
                    KitTextAction(localized(Res.string.providers_close), p::dismissLogin)
                }
            }
            else -> AwaitingUser(login, form, onSubmit = { c -> scope.launch { agents.agentSendCode(kind, c) } }, onCancel = cancel)
        }
    }
}

@Composable
private fun AwaitingUser(login: AgentLoginState, form: LoginCodeForm, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    val platform = LocalPlatform.current
    val c = kitColors()
    val url = login.url
    fun submit() {
        val code = form.code.trim()
        if (code.isEmpty()) return
        onSubmit(code)
        form.code = ""
    }
    if (url == null) {
        DimLine(localized(Res.string.providers_generating_link), Modifier.testTag("agent_login_generating"))
    } else {
        DimLine(localized(Res.string.providers_open_page_hint))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KitGhostButton(localized(Res.string.providers_open_sign_in_page), onClick = { platform.openUrl(url) }, modifier = Modifier.testTag("agent_login_open_url"))
            KitTextAction(localized(Res.string.providers_copy_link), { platform.copyToClipboard(url) }, Modifier.testTag("agent_login_copy_url"))
        }
        login.code?.let { code ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DimLine(localized(Res.string.providers_enter_code_there))
                SelectionContainer {
                    Text(
                        code,
                        fontFamily = MonoFontFamily,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.text,
                        modifier = Modifier.testTag("agent_login_device_code"),
                    )
                }
            }
        }
        DimLine(localized(Res.string.providers_waiting_approval))
        if (login.needsCode) {
            DimLine(localized(Res.string.providers_paste_code_hint))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitTextField(
                    form.code,
                    { form.code = it },
                    Modifier.weight(1f),
                    placeholder = localized(Res.string.providers_paste_code),
                    mono = true,
                    onSubmit = ::submit,
                    fieldTag = "agent_login_code_field",
                )
                KitGhostButton(localized(Res.string.providers_submit), onClick = ::submit, enabled = form.code.isNotBlank(), modifier = Modifier.testTag("agent_login_code_submit"))
            }
        }
    }
    KitTextAction(localized(Res.string.providers_cancel), onCancel, Modifier.testTag("agent_login_cancel"))
}

/**
 * A key being pasted and its save, kept for the page on display (`rememberRetainedOnPage`, #89): a rotation emptied the
 * field, and a save in flight was cut off with no answer shown. The save runs on the fleet's scope; [saved] tells the
 * page on display to reload. [open] is the token sheet's. In memory only.
 */
internal class SecretForm {
    var open by mutableStateOf(false)
    var value by mutableStateOf("")
    var saving by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var saved by mutableStateOf(false)

    fun save(scope: CoroutineScope, failure: String, call: suspend () -> Boolean) {
        saving = true
        error = null
        scope.launch {
            val ok = call()
            saving = false
            if (ok) {
                value = ""
                saved = true
            } else {
                error = failure
            }
        }
    }
}

/**
 * "Use a token instead": the machine's own token or key for this provider's CLI (today's "Paste OAuth token" / "Paste
 * API key"), saved with `agentSaveSecret` — never an added login. A sheet, so it may show Claude's helper command.
 */
@Composable
private fun TokenSheet(kind: String, form: SecretForm, save: suspend (String) -> Boolean, onSaved: () -> Unit) {
    val saves = rememberRetainedScope()
    val failure = localized(Res.string.providers_save_failed)
    fun submit() {
        val v = form.value.trim()
        if (v.isEmpty() || form.saving) return
        form.save(saves, failure) { save(v) }
    }
    LaunchedEffect(form, form.saved) {
        if (!form.saved) return@LaunchedEffect
        form.saved = false
        form.open = false
        onSaved()
    }
    if (!form.open) return
    val close = {
        form.open = false
        form.value = ""
        form.error = null
    }
    val claude = kind == "claude"
    KitSheet(
        title = if (claude) localized(Res.string.providers_paste_token_title) else localized(Res.string.providers_paste_api_key_title),
        onDismiss = { if (!form.saving) close() },
        description = localized(Res.string.providers_token_description, providerMachine(), providerName(kind)),
        tag = "provider_secret_sheet",
        dismissOnOutside = false,
        buttons = {
            KitGhostButton(localized(Res.string.providers_cancel), onClick = close, enabled = !form.saving)
            KitGhostButton(
                if (form.saving) localized(Res.string.providers_saving) else localized(Res.string.providers_save),
                onClick = ::submit,
                enabled = form.value.isNotBlank() && !form.saving,
                modifier = Modifier.testTag("provider_secret_save"),
            )
        },
    ) {
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DimLine(
                when (kind) {
                    "claude" -> localized(Res.string.providers_claude_token_hint)
                    "codex" -> localized(Res.string.providers_openai_key_hint)
                    "cursor" -> localized(Res.string.providers_cursor_key_hint)
                    "antigravity" -> localized(Res.string.providers_gemini_key_hint)
                    else -> localized(Res.string.providers_api_key_hint)
                },
            )
            if (claude) KitCopyCommand("claude setup-token") // i18n-ok: a command
            KitTextField(
                form.value,
                {
                    form.value = it
                    form.error = null
                },
                placeholder = if (claude) "sk-ant-oat01-…" else "sk-…", // i18n-ok: a key's shape, not words
                secret = true,
                onSubmit = ::submit,
                fieldTag = "provider_secret",
            )
            form.error?.let { WarnLine(it, "provider_secret_error") }
        }
    }
}

/** Install, while the provider is missing on the machine: today's job, its progress in words, its failure and log. */
@Composable
private fun InstallSection(p: ProviderState, kind: String) {
    val c = kitColors()
    val machine = providerMachine()
    val state = normalizeInstallState(p.install?.state)
    Column(Modifier.fillMaxWidth().testTag("agent_install_$kind"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KitNote(localized(Res.string.providers_not_installed, providerName(kind), machine))
        when {
            // Only installActive draws the running state: after Stop watching, the button is back even if the last
            // answer still said running.
            p.installActive -> {
                Row(
                    Modifier.testTag("agent_install_running_$kind"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DimLine(localized(Res.string.providers_installing))
                    KitTextAction(localized(Res.string.providers_stop_watching), p::stopWatchingInstall, Modifier.testTag("agent_install_cancel_$kind"))
                }
                DimLine(localized(Res.string.providers_stop_watching_hint, machine), Modifier.testTag("agent_install_stop_hint_$kind"))
            }
            state == "done" && !p.installTimedOut && !p.installStoppedWatching ->
                Text(localized(Res.string.providers_installed), fontSize = 12.5.sp, color = c.ok, modifier = Modifier.testTag("agent_install_done_$kind"))
            else -> {
                if (p.installStoppedWatching) {
                    DimLine(
                        localized(Res.string.providers_stopped_watching, machine),
                        Modifier.testTag("agent_install_stopped_watching_$kind"),
                    )
                }
                KitGhostButton(
                    when {
                        p.installStoppedWatching -> localized(Res.string.providers_watch_progress)
                        state == "failed" || p.installTimedOut -> localized(Res.string.providers_retry_install)
                        else -> localized(Res.string.providers_install)
                    },
                    onClick = p::startInstall,
                    modifier = Modifier.testTag("agent_install_start_$kind"),
                )
            }
        }
        if (state == "failed" || p.installRequestFailed || p.installTimedOut) {
            WarnLine(
                when {
                    p.installRequestFailed -> localized(Res.string.providers_install_start_failed)
                    p.installTimedOut -> localized(Res.string.providers_install_timed_out_machine, machine)
                    else -> localizedInstallFailureMessage(p.install)
                },
                "agent_install_error_$kind",
            )
        }
        val log = installLogTail(p.install?.log.orEmpty())
        if (log.isNotEmpty()) {
            SelectionContainer {
                Text(
                    log,
                    fontFamily = MonoFontFamily,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    color = c.dim,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(7.dp))
                        .background(c.wash)
                        .padding(10.dp)
                        .testTag("agent_install_log_$kind"),
                )
            }
        }
    }
}
