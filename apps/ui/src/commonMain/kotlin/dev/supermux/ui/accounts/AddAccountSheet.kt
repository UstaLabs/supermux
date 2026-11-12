// Add a login (slice A3b, restyled as a Settings kit sheet in #97): the chooser, the paste-a-token / API-key form and
// the guided login, as the steps of ONE sheet so the user never loses their place between them.
//
// The guided login is driven by `account_login_state` frames; a slow poll of
// GET /accounts/login/<id> only fills a gap left by a dropped socket, and never moves the sheet
// backwards (see [accountLoginPhaseRank]).
package dev.supermux.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.AccountDto
import dev.supermux.net.AccountLoginStateDto
import dev.supermux.state.AccountResult
import dev.supermux.state.accountLoginPhaseRank
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.retain.rememberRetainedOnPage
import dev.supermux.ui.retain.rememberRetainedScope
import dev.supermux.ui.settings.kit.KitCopyCommand
import dev.supermux.ui.settings.kit.KitGhostButton
import dev.supermux.ui.settings.kit.KitItem
import dev.supermux.ui.settings.kit.KitItemList
import dev.supermux.ui.settings.kit.KitSheet
import dev.supermux.ui.settings.kit.KitTextAction
import dev.supermux.ui.settings.kit.KitTextField
import dev.supermux.ui.settings.kit.kitColors
import dev.supermux.ui.settings.kit.kitFocusRing
import dev.supermux.ui.theme.MonoFontFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull


private const val LOGIN_POLL_MS = 4_000L
private const val DONE_CLOSE_MS = 2_200L

private sealed interface AddStep {
    data object Choose : AddStep
    data class Secret(val method: AddAccountOption) : AddStep
    data object Login : AddStep
}

/**
 * One guided login's client-side state. Frames for other logins are ignored; frames that beat the
 * POST's answer (the broker can publish before the response lands) are held and replayed.
 */
@Stable
internal class AccountLoginFlow(private val agent: String, private val actions: AccountsActions, private val email: String? = null) {
    var loginId by mutableStateOf<String?>(null)
        private set
    var state by mutableStateOf<AccountLoginStateDto?>(null)
        private set
    var startError by mutableStateOf<String?>(null)
        private set
    var starting by mutableStateOf(false)
        private set
    private val early = mutableListOf<AccountLoginStateDto>()

    val terminal: Boolean get() = state?.phase.let { it == "done" || it == "failed" || it == "cancelled" }

    fun onState(next: AccountLoginStateDto) {
        val id = loginId
        if (id == null) {
            if (starting && next.agent == agent) early += next
            return
        }
        if (next.loginId != id) return
        val cur = state
        if (cur == null || accountLoginPhaseRank(next.phase) >= accountLoginPhaseRank(cur.phase)) state = next
    }

    suspend fun start() {
        starting = true
        startError = null
        loginId = null
        state = null
        early.clear()
        when (val r = actions.startLogin(agent, email)) {
            is AccountResult.Ok -> {
                loginId = r.value.loginId
                state = r.value
                early.toList().forEach { onState(it) }
            }
            // Blank = the broker gave no words: the sheet says it in the app's language.
            is AccountResult.Failed -> startError = r.message ?: ""
        }
        early.clear()
        starting = false
    }

    suspend fun cancel() {
        val id = loginId ?: return
        if (!terminal) actions.cancelLogin(id)
    }
}

/**
 * The add-account sheet's state (#89): its step, the guided sign-in, the key being added and the
 * code being pasted. Kept for the page on display when the sheet has a `retainKey`; closing the
 * sheet [reset]s it, so the next one starts over, as a new composition did.
 */
private class AddAccountForm(private val agent: String, private val actions: AccountsActions, private val signInAgain: AccountDto? = null) {
    var step by mutableStateOf(firstStep())
    var flow by mutableStateOf(newFlow())
    var secret by mutableStateOf("")
    var label by mutableStateOf("")
    var saving by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    /** The account was added: the sheet on screen closes. */
    var added by mutableStateOf(false)
    var code by mutableStateOf("")
    var codeSubmitting by mutableStateOf(false)
    var codeSubmitted by mutableStateOf(false)
    var codeError by mutableStateOf<String?>(null)

    private fun firstStep(): AddStep =
        if (signInAgain != null) AddStep.Login
        else addAccountOptions(agent).let { if (it.size == 1) AddStep.Secret(it.single()) else AddStep.Choose }

    private fun newFlow() = AccountLoginFlow(agent, actions, signInAgain?.let { signInAgainEmail(it) })

    fun newSecret() {
        secret = ""
        label = ""
        saving = false
        error = null
        added = false
    }

    fun clearCode() {
        code = ""
        codeSubmitting = false
        codeSubmitted = false
        codeError = null
    }

    fun reset() {
        step = firstStep()
        flow = newFlow()
        newSecret()
        clearCode()
    }
}

/**
 * The add-account modal for [agent]. [existing] are that agent's accounts (to name an
 * `account_exists` collision).
 *
 * [retainKey] names the sheet's state kept for the page on display (#89): its step, a sign-in in
 * flight and what is typed. Null keeps all of it in this composition.
 *
 * [signInAgain]: sign that login in again (#106). The sheet goes straight to the guided login, as its email
 * where the agent's login takes one; the broker repairs that same account (same id, its chats stay on it).
 */

/**
 * The add-a-login sheet for [agent]. [existing] are that agent's accounts (to name an
 * `account_exists` collision).
 *
 * [retainKey] names the sheet's state kept for the page on display (#89): its step, a sign-in in
 * flight and what is typed. Null keeps all of it in this composition.
 *
 * [signInAgain]: sign that login in again (#106). The sheet goes straight to the guided login, as its email
 * where the agent's login takes one; the broker repairs that same account (same id, its chats stay on it).
 */
@Composable
fun AddAccountSheet(
    agent: String,
    actions: AccountsActions,
    existing: List<AccountDto>,
    onDismiss: () -> Unit,
    retainKey: String? = null,
    signInAgain: AccountDto? = null,
) {
    val options = remember(agent) { addAccountOptions(agent) }
    // With [retainKey], a rotation finds the sheet where it was: it went back to the first step in
    // the middle of a sign-in that went on on the host. The sign-in, a key being added and a cancel
    // then run on the fleet's scope, and closing the sheet starts the next one afresh.
    val form = if (retainKey != null) {
        rememberRetainedOnPage(retainKey) { AddAccountForm(agent, actions, signInAgain) }
    } else {
        remember(agent, actions, signInAgain?.id) { AddAccountForm(agent, actions, signInAgain) }
    }
    val scope = if (retainKey != null) rememberRetainedScope() else rememberCoroutineScope()
    var step by form::step
    val flow = form.flow
    val close: () -> Unit = {
        form.reset()
        onDismiss()
    }

    LaunchedEffect(flow) { actions.loginFrames.collect { flow.onState(it) } }
    // Sign in again starts the login at once: there is nothing to choose.
    LaunchedEffect(flow) { if (signInAgain != null && flow.loginId == null && !flow.starting) flow.start() }
    // The fallback poll — only while a login is live, and never backwards.
    LaunchedEffect(flow.loginId, flow.terminal) {
        val id = flow.loginId ?: return@LaunchedEffect
        while (isActive && !flow.terminal) {
            delay(LOGIN_POLL_MS)
            actions.pollLogin(id)?.let { flow.onState(it) }
        }
    }

    // Cancel first, close after: this composable's scope dies with the sheet, and a cancel
    // launched into it could be torn down mid-request. Bounded so a dead socket never traps the user.
    val dismiss: () -> Unit = {
        if (step == AddStep.Login && flow.loginId != null && !flow.terminal) {
            scope.launch {
                withTimeoutOrNull(3_000L) { flow.cancel() }
                close()
            }
        } else {
            close()
        }
    }

    when (val s = step) {
        AddStep.Choose -> ChooseStep(
            agent = agent,
            options = options,
            onPick = { option ->
                if (option == AddAccountOption.Login) {
                    step = AddStep.Login
                    scope.launch { flow.start() }
                } else {
                    // What was typed for another method does not carry over (it was keyed on it).
                    form.newSecret()
                    step = AddStep.Secret(option)
                }
            },
            onCancel = close,
        )
        is AddStep.Secret -> SecretStep(
            agent = agent,
            method = s.method,
            canGoBack = options.size > 1,
            onBack = { step = AddStep.Choose },
            onCancel = close,
            form = form,
            scope = scope,
            onSave = { secret, label ->
                val method = if (s.method == AddAccountOption.Token) "token" else "api_key"
                actions.addAccount(agent, method, secret, label)
            },
            onAdded = close,
        )
        AddStep.Login -> LoginStep(
            agent = agent,
            title = signInAgain?.let { localized(Str.accounts_sign_in_again_title, localizedAccountDisplayName(it)) },
            flow = flow,
            existing = existing,
            form = form,
            scope = scope,
            onSubmitCode = { code -> flow.loginId?.let { actions.sendLoginCode(it, code) } },
            onRetry = { scope.launch { flow.start() } },
            onCancel = dismiss,
            onDone = close,
        )
    }
}

private fun optionTag(option: AddAccountOption): String = when (option) {
    AddAccountOption.Login -> "login"
    AddAccountOption.Token -> "token"
    AddAccountOption.ApiKey -> "api_key"
}

@Composable
private fun ChooseStep(
    agent: String,
    options: List<AddAccountOption>,
    onPick: (AddAccountOption) -> Unit,
    onCancel: () -> Unit,
) {
    KitSheet(
        title = localized(Str.accounts_add_title, agentDisplayName(agent)),
        onDismiss = onCancel,
        tag = "account-add-sheet",
        buttons = { KitGhostButton(localized(Str.accounts_cancel), onClick = onCancel, modifier = Modifier.testTag("account-add-cancel")) },
    ) {
        KitItemList(Modifier.padding(top = 12.dp)) {
            options.forEach { option ->
                KitItem(
                    localizedAddOptionTitle(agent, option),
                    Modifier.testTag("account-add-option:${optionTag(option)}"),
                    subtitle = localizedAddOptionHint(agent, option),
                    onClick = { onPick(option) },
                )
            }
        }
    }
}

@Composable
private fun SecretStep(
    agent: String,
    method: AddAccountOption,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    form: AddAccountForm,
    scope: CoroutineScope,
    onSave: suspend (secret: String, label: String?) -> AccountResult<AccountDto>,
    onAdded: () -> Unit,
) {
    var secret by form::secret
    var label by form::label
    var saving by form::saving
    var error by form::error
    val token = method == AddAccountOption.Token
    val canSave = secret.isNotBlank() && !saving

    fun save() {
        if (!canSave) return
        saving = true
        error = null
        val typed = secret.trim()
        val name = label.trim().ifBlank { null }
        scope.launch {
            when (val r = onSave(typed, name)) {
                is AccountResult.Ok -> form.added = true
                is AccountResult.Failed -> {
                    form.saving = false
                    // Blank = the broker gave no words: the sheet says it in the app's language.
                    form.error = r.message ?: ""
                }
            }
        }
    }
    // The account was added: the sheet on screen closes (#89: it may not be the one that saved).
    LaunchedEffect(form, form.added) {
        if (!form.added) return@LaunchedEffect
        form.added = false
        onAdded()
    }

    val addFailed = localized(Str.accounts_add_failed)
    KitSheet(
        title = localizedAddOptionTitle(agent, method),
        onDismiss = { if (!saving) onCancel() },
        description = if (token) localized(Str.accounts_token_description) else localizedAddOptionHint(agent, method),
        tag = "account-add-sheet",
        // A half-typed key is not thrown away by a stray tap.
        dismissOnOutside = false,
        buttons = {
            if (canGoBack) KitGhostButton(localized(Str.accounts_back), onClick = onBack, enabled = !saving, modifier = Modifier.testTag("account-add-back"))
            KitGhostButton(localized(Str.accounts_cancel), onClick = onCancel, enabled = !saving, modifier = Modifier.testTag("account-add-cancel"))
            KitGhostButton(
                localized(if (saving) Str.accounts_adding else Str.accounts_add_login),
                onClick = ::save,
                enabled = canSave,
                modifier = Modifier.testTag("account-secret-save"),
            )
        },
    ) {
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (token) KitCopyCommand("claude setup-token") // i18n-ok: a command
            KitTextField(
                secret,
                {
                    secret = it
                    error = null
                },
                placeholder = if (token) "sk-ant-oat01-…" else "sk-…", // i18n-ok: a key's shape
                secret = true,
                onSubmit = ::save,
                fieldTag = if (token) "account-token-field" else "account-key-field",
            )
            KitTextField(
                label,
                { label = it },
                label = localized(Str.accounts_name_optional),
                placeholder = localized(Str.accounts_name_placeholder),
                onSubmit = ::save,
                fieldTag = "account-label-field",
            )
            error?.let { SheetLine(it.ifBlank { addFailed }, warn = true, tag = "account-secret-error") }
        }
    }
}

@Composable
private fun SheetLine(text: String, warn: Boolean = false, tag: String? = null, modifier: Modifier = Modifier) {
    val c = kitColors()
    Text(
        text,
        fontSize = 12.5.sp,
        lineHeight = 19.sp,
        color = if (warn) c.warn else c.dim,
        modifier = modifier.then(if (tag != null) Modifier.testTag(tag) else Modifier),
    )
}

@Composable
private fun LoginStep(
    agent: String,
    title: String?,
    flow: AccountLoginFlow,
    existing: List<AccountDto>,
    form: AddAccountForm,
    scope: CoroutineScope,
    onSubmitCode: suspend (String) -> AccountResult<Unit>?,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val view = loginView(flow.state, agent, existing)
    // The code step's fields go when the step does, as they went with its composition before.
    val awaiting = view is LoginView.AwaitingUser
    LaunchedEffect(awaiting) { if (!awaiting) form.clearCode() }
    LaunchedEffect(view is LoginView.Done) {
        if (view is LoginView.Done) {
            delay(DONE_CLOSE_MS)
            onDone()
        }
    }
    val startFailed = localized(Str.accounts_start_failed)
    val startError = flow.startError?.ifBlank { startFailed }
    KitSheet(
        title = title ?: if (agent == "codex") localized(Str.accounts_sign_in_with, "ChatGPT") else localized(Str.accounts_sign_in_to, agentDisplayName(agent)),
        onDismiss = onCancel,
        tag = "account-add-sheet",
        // A sign-in under way is cancelled on purpose, not by a stray tap.
        dismissOnOutside = false,
        buttons = {
            if (view !is LoginView.Done) {
                KitGhostButton(localized(Str.accounts_cancel), onClick = onCancel, modifier = Modifier.testTag("account-login-cancel"))
                if (startError != null || view is LoginView.Failed || view is LoginView.Cancelled) {
                    KitGhostButton(localized(Str.accounts_try_again), onClick = onRetry, enabled = !flow.starting, modifier = Modifier.testTag("account-login-retry"))
                }
            }
        },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(top = 12.dp).testTag("account-login-sheet"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                startError != null -> SheetLine(startError, warn = true, tag = "account-login-failed")
                else -> when (view) {
                    LoginView.Starting -> SheetLine(localized(Str.accounts_starting_sign_in), tag = "account-login-starting")
                    is LoginView.AwaitingUser -> AwaitingBlock(view, form, scope, onSubmitCode)
                    LoginView.Verifying -> SheetLine(localized(Str.accounts_checking_sign_in), tag = "account-login-verifying")
                    is LoginView.Done -> Text(
                        localized(
                            Str.accounts_added,
                            flow.state?.account?.let { localizedAccountShortLabel(it) } ?: localized(Str.accounts_the_account),
                        ),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = kitColors().ok,
                        modifier = Modifier.testTag("account-login-done"),
                    )
                    is LoginView.Failed -> SheetLine(
                        flow.state?.let { localizedLoginFailureText(it, agent, existing) } ?: view.message,
                        warn = true,
                        tag = "account-login-failed",
                    )
                    LoginView.Cancelled -> SheetLine(localized(Str.accounts_sign_in_cancelled))
                }
            }
        }
    }
}

/** A numbered step: its number, then the instruction and its control. */
@Composable
private fun StepItem(number: Int, text: String, content: @Composable () -> Unit = {}) {
    val c = kitColors()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("$number.", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.dim)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, fontSize = 13.sp, lineHeight = 19.sp, color = c.text)
            content()
        }
    }
}

@Composable
private fun AwaitingBlock(
    view: LoginView.AwaitingUser,
    form: AddAccountForm,
    scope: CoroutineScope,
    onSubmitCode: suspend (String) -> AccountResult<Unit>?,
) {
    val c = kitColors()
    val platform = LocalPlatform.current
    var code by form::code
    var submitting by form::codeSubmitting
    var submitted by form::codeSubmitted
    var error by form::codeError
    var copied by remember { mutableStateOf(false) }
    val url = view.url

    fun submit() {
        val typed = code.trim()
        if (typed.isEmpty() || submitting) return
        submitting = true
        error = null
        scope.launch {
            when (val r = onSubmitCode(typed)) {
                // Blank = the broker gave no words: the step says it in the app's language.
                is AccountResult.Failed -> form.codeError = r.message ?: ""
                else -> form.codeSubmitted = true
            }
            form.codeSubmitting = false
        }
    }

    val sendFailed = localized(Str.accounts_send_code_failed)
    var n = 1
    StepItem(n++, localized(if (url != null) Str.accounts_open_page_step else Str.accounts_waiting_for_page)) {
        if (url != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KitGhostButton(localized(Str.accounts_open_sign_in_page), onClick = { platform.openUrl(url) }, modifier = Modifier.testTag("account-login-open"))
                KitTextAction(localized(Str.accounts_copy_link), { platform.copyToClipboard(url) }, Modifier.testTag("account-login-copy-url"))
            }
        } else {
            SheetLine(localized(Str.accounts_generating_link), tag = "account-login-generating")
        }
    }
    view.code?.let { device ->
        StepItem(n++, localized(Str.accounts_enter_code)) {
            val src = remember { MutableInteractionSource() }
            Row(
                Modifier
                    .clickable(interactionSource = src, indication = null, role = Role.Button) {
                        platform.copyToClipboard(device)
                        copied = true
                    }
                    .kitFocusRing(src, RoundedCornerShape(4.dp))
                    .testTag("account-login-device-code"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SelectionContainer {
                    Text(device, fontFamily = MonoFontFamily, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, color = c.text)
                }
                Text(localized(if (copied) Str.accounts_copied else Str.accounts_copy), fontSize = 12.5.sp, color = c.accent)
            }
        }
    }
    if (view.needsCode) {
        StepItem(n, localized(Str.accounts_paste_code_step)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitTextField(
                    code,
                    {
                        code = it
                        error = null
                        submitted = false
                    },
                    Modifier.weight(1f),
                    placeholder = localized(Str.accounts_paste_code),
                    mono = true,
                    onSubmit = ::submit,
                    fieldTag = "account-login-code",
                )
                KitGhostButton(
                    localized(if (submitting) Str.accounts_sending else Str.accounts_submit),
                    onClick = ::submit,
                    enabled = code.isNotBlank() && !submitting,
                    modifier = Modifier.testTag("account-login-submit"),
                )
            }
            when {
                error != null -> SheetLine(error!!.ifBlank { sendFailed }, warn = true)
                submitted -> SheetLine(localized(Str.accounts_code_sent))
            }
        }
    } else if (url != null) {
        SheetLine(localized(Str.accounts_waiting_approval), modifier = Modifier.padding(start = 22.dp))
    }
}
