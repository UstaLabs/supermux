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
    val sectionTitle = SettingsSection.Providers.label
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
                AgentsLoadState.Loading -> KitNote("Loading…")
                AgentsLoadState.Empty, is AgentsLoadState.Error -> Column {
                    KitNote("Couldn't reach ${providerMachine()}.")
                    KitTextAction("Try again", providers::reload, Modifier.testTag("provider_retry"))
                }
                is AgentsLoadState.Ready -> {
                    val status = s.statuses.firstOrNull { it.kind == agent }
                    if (status == null) {
                        KitNote("${providerName(agent)} isn't on ${providerMachine()}.")
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
private fun ownLogin(status: AgentInstallStatus, p: ProviderState, system: AccountDto?): OwnLogin {
    val expired = status.signInExpired || (system != null && signInExpired(system))
    val refused = system?.takeIf { !expired && it.unavailable != null }?.let(::accountUnavailableText)
    val email = system?.identity?.email?.takeIf { it.isNotBlank() }
    val subtitle = when {
        expired -> "Sign-in expired"
        refused != null -> refused
        status.authed -> email ?: "This machine's login"
        else -> "Not signed in"
    }
    return OwnLogin(subtitle, warn = expired || refused != null) {
        if (status.kind in LOGIN_KINDS && !p.loginActive) {
            KitGhostButton(
                if (!status.authed && !expired) "Sign in" else "Sign in again",
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
            KitNote("Signing in needs \"Allow device code login\" turned on in ChatGPT ▸ Settings ▸ Security.")
        }
        // Grok has no token or key to paste.
        if (kind != "grok") KitTextAction("Use a token instead", onUseToken, Modifier.testTag("provider_use_token"))
    }
    KitHairline()
}

/** The login flow while it runs or has just ended, and why the last one could not start or timed out. */
@Composable
internal fun LoginLines(p: ProviderState, agents: AgentSettingsActions) {
    val kind = p.kind
    if (p.loginActive || p.login?.phase == "success") LoginFlow(p, agents)
    if (p.loginStartFailed) WarnLine("Couldn't start the sign-in.", "agent_login_start_failed_$kind")
    if (p.loginTimedOut) WarnLine("The sign-in timed out — ${providerMachine()} may be unreachable.", "agent_login_timeout_$kind")
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
                DimLine("Generating the sign-in link — this can take a few seconds…", Modifier.testTag("agent_login_generating"))
                KitTextAction("Cancel", cancel, Modifier.testTag("agent_login_cancel"))
            }
            "success" -> Text("Signed in.", fontSize = 12.5.sp, color = kitColors().ok, modifier = Modifier.testTag("agent_login_success_$kind"))
            "failed", "cancelled" -> {
                if (login.phase == "failed") {
                    WarnLine("Sign-in failed: ${login.error ?: "unknown error"}", "agent_login_failed_$kind")
                } else {
                    DimLine("Sign-in cancelled.")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    KitTextAction("Try again", p::startLogin, Modifier.testTag("agent_login_retry"))
                    KitTextAction("Close", p::dismissLogin)
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
        DimLine("Generating the sign-in link — this can take a few seconds…", Modifier.testTag("agent_login_generating"))
    } else {
        DimLine("Open the sign-in page and approve the request.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            KitGhostButton("Open sign-in page", onClick = { platform.openUrl(url) }, modifier = Modifier.testTag("agent_login_open_url"))
            KitTextAction("Copy link", { platform.copyToClipboard(url) }, Modifier.testTag("agent_login_copy_url"))
        }
        login.code?.let { code ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DimLine("Enter this code there:")
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
        DimLine("Waiting for you to approve…")
        if (login.needsCode) {
            DimLine("Then paste the code the page shows:")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitTextField(
                    form.code,
                    { form.code = it },
                    Modifier.weight(1f),
                    placeholder = "Paste the code",
                    mono = true,
                    onSubmit = ::submit,
                    fieldTag = "agent_login_code_field",
                )
                KitGhostButton("Submit", onClick = ::submit, enabled = form.code.isNotBlank(), modifier = Modifier.testTag("agent_login_code_submit"))
            }
        }
    }
    KitTextAction("Cancel", onCancel, Modifier.testTag("agent_login_cancel"))
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
    fun submit() {
        val v = form.value.trim()
        if (v.isEmpty() || form.saving) return
        form.save(saves, "Couldn't save — check the connection and try again.") { save(v) }
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
        title = if (claude) "Paste a token" else "Paste an API key",
        onDismiss = { if (!form.saving) close() },
        description = "A token for ${providerMachine()}'s own ${providerName(kind)} login.",
        tag = "provider_secret_sheet",
        dismissOnOutside = false,
        buttons = {
            KitGhostButton("Cancel", onClick = close, enabled = !form.saving)
            KitGhostButton(
                if (form.saving) "Saving…" else "Save",
                onClick = ::submit,
                enabled = form.value.isNotBlank() && !form.saving,
                modifier = Modifier.testTag("provider_secret_save"),
            )
        },
    ) {
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DimLine(
                when (kind) {
                    "claude" -> "On a computer with a browser, run this and paste the token it prints:"
                    "codex" -> "Paste your OpenAI API key."
                    "cursor" -> "Paste your Cursor API key."
                    "antigravity" -> "Paste your Gemini API key."
                    else -> "Paste an API key."
                },
            )
            if (claude) KitCopyCommand("claude setup-token")
            KitTextField(
                form.value,
                {
                    form.value = it
                    form.error = null
                },
                placeholder = if (claude) "sk-ant-oat01-…" else "sk-…",
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
        KitNote("${providerName(kind)} isn't installed on $machine yet. Install it to start chats with it.")
        when {
            // Only installActive draws the running state: after Stop watching, the button is back even if the last
            // answer still said running.
            p.installActive -> {
                Row(
                    Modifier.testTag("agent_install_running_$kind"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DimLine("Installing…")
                    KitTextAction("Stop watching", p::stopWatchingInstall, Modifier.testTag("agent_install_cancel_$kind"))
                }
                DimLine("Stops updating this page only. The install keeps running on $machine.", Modifier.testTag("agent_install_stop_hint_$kind"))
            }
            state == "done" && !p.installTimedOut && !p.installStoppedWatching ->
                Text("Installed.", fontSize = 12.5.sp, color = c.ok, modifier = Modifier.testTag("agent_install_done_$kind"))
            else -> {
                if (p.installStoppedWatching) {
                    DimLine(
                        "Stopped watching. The install may still be running on $machine — Watch progress to check.",
                        Modifier.testTag("agent_install_stopped_watching_$kind"),
                    )
                }
                KitGhostButton(
                    when {
                        p.installStoppedWatching -> "Watch progress"
                        state == "failed" || p.installTimedOut -> "Retry installation"
                        else -> "Install"
                    },
                    onClick = p::startInstall,
                    modifier = Modifier.testTag("agent_install_start_$kind"),
                )
            }
        }
        if (state == "failed" || p.installRequestFailed || p.installTimedOut) {
            WarnLine(
                when {
                    p.installRequestFailed -> "Couldn't start the install."
                    p.installTimedOut -> "The install timed out — $machine may be unreachable."
                    else -> installFailureMessage(p.install)
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
