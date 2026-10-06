// Add an account (slice A3b): the chooser, the paste-a-token / API-key form and the guided login,
// as the steps of ONE modal so the user never loses their place between them.
//
// The guided login is driven by `account_login_state` frames; a slow poll of
// GET /accounts/login/<id> only fills a gap left by a dropped socket, and never moves the sheet
// backwards (see [accountLoginPhaseRank]).
package dev.supermux.ui.accounts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.AccountDto
import dev.supermux.net.AccountLoginStateDto
import dev.supermux.state.AccountResult
import dev.supermux.state.accountLoginPhaseRank
import dev.supermux.ui.chat.CardButton
import dev.supermux.ui.chat.CardButtonStyle
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.session.AgentLogo
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.Stroke
import dev.supermux.ui.widgets.CopyableCommand
import dev.supermux.ui.widgets.SecretField
import dev.supermux.ui.widgets.settingsFieldColors
import dev.supermux.ui.widgets.submitOnEnter
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
internal class AccountLoginFlow(private val agent: String, private val actions: AccountsActions) {
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
        when (val r = actions.startLogin(agent)) {
            is AccountResult.Ok -> {
                loginId = r.value.loginId
                state = r.value
                early.toList().forEach { onState(it) }
            }
            is AccountResult.Failed -> startError = r.message ?: "Couldn't start the sign-in."
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
 * The add-account modal for [agent]. [existing] are that agent's accounts (to name an
 * `account_exists` collision).
 */
@Composable
fun AddAccountSheet(
    agent: String,
    actions: AccountsActions,
    existing: List<AccountDto>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val options = remember(agent) { addAccountOptions(agent) }
    var step by remember(agent) {
        mutableStateOf(if (options.size == 1) AddStep.Secret(options.single()) else AddStep.Choose)
    }
    val flow = remember(agent, actions) { AccountLoginFlow(agent, actions) }

    LaunchedEffect(flow) { actions.loginFrames.collect { flow.onState(it) } }
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
                onDismiss()
            }
        } else {
            onDismiss()
        }
    }

    AccountsModal(onDismiss = dismiss, testTag = "account-add-sheet") {
        when (val s = step) {
            AddStep.Choose -> ChooseStep(
                agent = agent,
                options = options,
                onPick = { option ->
                    if (option == AddAccountOption.Login) {
                        step = AddStep.Login
                        scope.launch { flow.start() }
                    } else {
                        step = AddStep.Secret(option)
                    }
                },
                onCancel = onDismiss,
            )
            is AddStep.Secret -> SecretStep(
                agent = agent,
                method = s.method,
                canGoBack = options.size > 1,
                onBack = { step = AddStep.Choose },
                onCancel = onDismiss,
                onSave = { secret, label ->
                    val method = if (s.method == AddAccountOption.Token) "token" else "api_key"
                    actions.addAccount(agent, method, secret, label)
                },
                onAdded = onDismiss,
            )
            AddStep.Login -> LoginStep(
                agent = agent,
                flow = flow,
                existing = existing,
                onSubmitCode = { code -> flow.loginId?.let { actions.sendLoginCode(it, code) } },
                onRetry = { scope.launch { flow.start() } },
                onCancel = dismiss,
                onDone = onDismiss,
            )
        }
    }
}

@Composable
private fun SheetTitle(agent: String, title: String, onBack: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(32.dp).testTag("account-add-back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(18.dp))
            }
        } else {
            AgentLogo(agent, size = 22.dp)
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun optionIcon(option: AddAccountOption): ImageVector = when (option) {
    AddAccountOption.Login -> Icons.AutoMirrored.Filled.Login
    AddAccountOption.Token -> Icons.Outlined.ConfirmationNumber
    AddAccountOption.ApiKey -> Icons.Filled.Key
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
    val cs = MaterialTheme.colorScheme
    SheetTitle(agent, "Add a ${agentDisplayName(agent)} account")
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        options.forEach { option ->
            val shape = RoundedCornerShape(Radii.md)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .border(Stroke.hairline, cs.outlineVariant, shape)
                    .clickable { onPick(option) }
                    .padding(horizontal = Space.md, vertical = Space.md)
                    .testTag("account-add-option:${optionTag(option)}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(cs.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(optionIcon(option), contentDescription = null, tint = cs.primary, modifier = Modifier.size(16.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        addOptionTitle(agent, option),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onSurface,
                    )
                    Text(addOptionHint(agent, option), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        CardButton(label = "Cancel", style = CardButtonStyle.Ghost, enabled = true, testTag = "account-add-cancel", onClick = onCancel)
    }
}

@Composable
private fun SecretStep(
    agent: String,
    method: AddAccountOption,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    onSave: suspend (secret: String, label: String?) -> AccountResult<AccountDto>,
    onAdded: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var secret by remember(method) { mutableStateOf("") }
    var label by remember(method) { mutableStateOf("") }
    var saving by remember(method) { mutableStateOf(false) }
    var error by remember(method) { mutableStateOf<String?>(null) }
    val token = method == AddAccountOption.Token
    val canSave = secret.isNotBlank() && !saving

    fun save() {
        if (!canSave) return
        saving = true
        error = null
        scope.launch {
            when (val r = onSave(secret.trim(), label.trim().ifBlank { null })) {
                is AccountResult.Ok -> onAdded()
                is AccountResult.Failed -> {
                    saving = false
                    error = r.message ?: "Couldn't add the account — check the connection and try again."
                }
            }
        }
    }

    SheetTitle(agent, addOptionTitle(agent, method), onBack = if (canGoBack) onBack else null)
    if (token) {
        Text(
            "On a machine with a browser, run this and paste the token it prints:",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
        CopyableCommand("claude setup-token")
    } else {
        Text(addOptionHint(agent, method), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
    SecretField(
        value = secret,
        onValueChange = { secret = it; error = null },
        placeholder = if (token) "sk-ant-oat01-…" else "sk-…",
        modifier = Modifier.fillMaxWidth().testTag(if (token) "account-token-field" else "account-key-field"),
        onSubmit = ::save,
        submitEnabled = canSave,
    )
    OutlinedTextField(
        value = label,
        onValueChange = { label = it },
        placeholder = { Text("Name (optional) — e.g. Work", style = MaterialTheme.typography.bodySmall) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        colors = settingsFieldColors(),
        modifier = Modifier.fillMaxWidth().testTag("account-label-field").submitOnEnter(canSave, ::save),
    )
    error?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("account-secret-error")) }
    ButtonRow {
        CardButton(label = "Cancel", style = CardButtonStyle.Secondary, enabled = !saving, testTag = "account-add-cancel", onClick = onCancel)
        CardButton(
            label = if (saving) "Adding…" else "Add account",
            style = CardButtonStyle.Primary,
            enabled = canSave,
            testTag = "account-secret-save",
            onClick = ::save,
        )
    }
}

@Composable
private fun ButtonRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.sm, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun LoginStep(
    agent: String,
    flow: AccountLoginFlow,
    existing: List<AccountDto>,
    onSubmitCode: suspend (String) -> AccountResult<Unit>?,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val view = loginView(flow.state, agent, existing)
    LaunchedEffect(view is LoginView.Done) {
        if (view is LoginView.Done) {
            delay(DONE_CLOSE_MS)
            onDone()
        }
    }
    Column(Modifier.fillMaxWidth().testTag("account-login-sheet"), verticalArrangement = Arrangement.spacedBy(Space.md)) {
        SheetTitle(agent, if (agent == "codex") "Sign in with ChatGPT" else "Sign in to ${agentDisplayName(agent)}")
        val startError = flow.startError
        when {
            startError != null -> FailedBlock(startError)
            else -> when (view) {
                LoginView.Starting -> ProgressLine("Starting the sign-in…", "account-login-starting")
                is LoginView.AwaitingUser -> AwaitingBlock(agent, view, onSubmitCode)
                LoginView.Verifying -> ProgressLine("Checking the sign-in…", "account-login-verifying")
                is LoginView.Done -> DoneBlock(view.label)
                is LoginView.Failed -> FailedBlock(view.message)
                LoginView.Cancelled -> Text(
                    "Sign-in cancelled.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }
        }
        if (view !is LoginView.Done) {
            ButtonRow {
                CardButton(
                    label = "Cancel",
                    style = CardButtonStyle.Secondary,
                    enabled = true,
                    testTag = "account-login-cancel",
                    onClick = onCancel,
                )
                if (startError != null || view is LoginView.Failed || view is LoginView.Cancelled) {
                    CardButton(
                        label = "Try again",
                        style = CardButtonStyle.Primary,
                        enabled = !flow.starting,
                        testTag = "account-login-retry",
                        onClick = onRetry,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressLine(text: String, tag: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = Space.md).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        CircularProgressIndicator(color = cs.primary, strokeWidth = Stroke.thin, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    }
}

/** A numbered step: a small tinted number, then the instruction and its control. */
@Composable
private fun StepItem(number: Int, text: String, content: @Composable () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
        Box(
            Modifier.padding(top = 1.dp).size(20.dp).clip(CircleShape).background(cs.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = cs.primary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
            content()
        }
    }
}

@Composable
private fun AwaitingBlock(agent: String, view: LoginView.AwaitingUser, onSubmitCode: suspend (String) -> AccountResult<Unit>?) {
    val cs = MaterialTheme.colorScheme
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    val url = view.url

    fun submit() {
        val c = code.trim()
        if (c.isEmpty() || submitting) return
        submitting = true
        error = null
        scope.launch {
            when (val r = onSubmitCode(c)) {
                is AccountResult.Failed -> error = r.message ?: "Couldn't send the code."
                else -> submitted = true
            }
            submitting = false
        }
    }

    var n = 1
    StepItem(n++, if (url != null) "Open the sign-in page and approve the request." else "Waiting for the sign-in page…") {
        if (url != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                CardButton(
                    label = "Open sign-in page",
                    style = CardButtonStyle.Primary,
                    enabled = true,
                    testTag = "account-login-open",
                    trailing = Icons.AutoMirrored.Filled.OpenInNew,
                    onClick = { platform.openUrl(url) },
                )
                IconButton(
                    onClick = { platform.copyToClipboard(url) },
                    modifier = Modifier.size(36.dp).testTag("account-login-copy-url"),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy link", tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
        } else {
            ProgressLine("Generating the link…", "account-login-generating")
        }
    }
    view.code?.let { device ->
        StepItem(n++, "Enter this code on the page:") {
            val shape = RoundedCornerShape(Radii.sm)
            Row(
                Modifier
                    .clip(shape)
                    .background(cs.surfaceContainerHighest)
                    .border(Stroke.hairline, cs.outlineVariant, shape)
                    .clickable { platform.copyToClipboard(device); copied = true }
                    .padding(start = Space.md, end = Space.sm, top = Space.sm, bottom = Space.sm)
                    .testTag("account-login-device-code"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                SelectionContainer {
                    Text(
                        device,
                        fontFamily = MonoFontFamily,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.sp,
                        color = cs.onSurface,
                    )
                }
                Icon(
                    if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                    contentDescription = "Copy code",
                    tint = if (copied) cs.primary else cs.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
    if (view.needsCode) {
        StepItem(n, "Paste the code the page shows you after approving.") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it; error = null; submitted = false },
                    placeholder = { Text("Paste code", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
                    colors = settingsFieldColors(),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("account-login-code")
                        .submitOnEnter(code.isNotBlank() && !submitting, ::submit),
                )
                CardButton(
                    label = if (submitting) "Sending…" else "Submit",
                    style = CardButtonStyle.Primary,
                    enabled = code.isNotBlank() && !submitting,
                    testTag = "account-login-submit",
                    onClick = ::submit,
                )
            }
            when {
                error != null -> Text(error!!, color = cs.error, style = MaterialTheme.typography.bodySmall)
                submitted -> Text("Code sent — checking…", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }
        }
    } else if (url != null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            modifier = Modifier.padding(start = 32.dp),
        ) {
            CircularProgressIndicator(color = cs.onSurfaceVariant, strokeWidth = 1.5.dp, modifier = Modifier.size(12.dp))
            Text(
                "Waiting for you to approve in the browser…",
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DoneBlock(label: String) {
    val sem = LocalSemantics.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = Space.lg).testTag("account-login-done"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(sem.success.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = sem.success, modifier = Modifier.size(24.dp))
        }
        Text("Added $label", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun FailedBlock(message: String) {
    val cs = MaterialTheme.colorScheme
    val sem = LocalSemantics.current
    Surface(
        shape = RoundedCornerShape(Radii.sm),
        color = sem.danger.copy(alpha = 0.08f),
        border = BorderStroke(Stroke.hairline, sem.danger.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth().testTag("account-login-failed"),
    ) {
        Row(
            Modifier.padding(Space.md),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = sem.danger, modifier = Modifier.size(18.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
        }
    }
    Spacer(Modifier.height(0.dp))
}
