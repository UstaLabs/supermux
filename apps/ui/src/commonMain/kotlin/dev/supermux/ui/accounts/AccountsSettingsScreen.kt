// Settings → Accounts (slice A3b): every agent's logins in one place, the add/sign-in flow and the
// auto-switch toggle.
//
// Its own hub section rather than a block inside each agent's settings: accounts are a fleet-wide
// list the broker keeps once for every agent (one registry), the auto-switch toggle is global, and
// the Agents screen is about installing and authorising the CLI itself — mixing "is the CLI
// signed in" with "which extra logins can sessions use" made both harder to read.
package dev.supermux.ui.accounts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.supermux.net.AccountDto
import dev.supermux.net.AccountLoginStateDto
import dev.supermux.state.AccountResult
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.session.AgentLogo
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.Stroke
import dev.supermux.ui.widgets.AlertDialog
import dev.supermux.ui.widgets.SettingsDetailMaxWidth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private val NOT_WIRED: AccountResult.Failed = AccountResult.Failed(null, null, "Not available on this client")

/**
 * Every broker call the accounts UI makes, in one holder (the settings-actions pattern). Defaults
 * are the empty/refused answers so a test or preview can build a partial screen.
 */
@Immutable
class AccountsActions(
    /** The host's accounts, live; null until loaded (or on a broker without accounts). */
    val accounts: Flow<List<AccountDto>?> = flowOf(null),
    /** Start following [accounts] (the first call fetches). */
    val ensureAccounts: () -> Unit = {},
    val autoSwitch: Flow<Boolean?> = flowOf(null),
    val loadAutoSwitch: suspend () -> Boolean? = { null },
    val setAutoSwitch: suspend (Boolean) -> AccountResult<Boolean> = { NOT_WIRED },
    val addAccount: suspend (agent: String, method: String, secret: String, label: String?) -> AccountResult<AccountDto> =
        { _, _, _, _ -> NOT_WIRED },
    val removeAccount: suspend (id: String, deleteHome: Boolean) -> AccountResult<Unit> = { _, _ -> NOT_WIRED },
    val startLogin: suspend (agent: String) -> AccountResult<AccountLoginStateDto> = { NOT_WIRED },
    /** GET /accounts/login/<id> — the fallback when a frame was missed (null = unknown). */
    val pollLogin: suspend (loginId: String) -> AccountLoginStateDto? = { null },
    /** `account_login_state` frames (every login; the sheet filters by id). */
    val loginFrames: Flow<AccountLoginStateDto> = emptyFlow(),
    val sendLoginCode: suspend (loginId: String, code: String) -> AccountResult<Unit> = { _, _ -> NOT_WIRED },
    val cancelLogin: suspend (loginId: String) -> AccountResult<Unit> = { NOT_WIRED },
)

/** [AccountsActions] against one paired host — desktop's wiring. */
@Composable
fun rememberAccountsActions(app: HostStore): AccountsActions = remember(app) {
    AccountsActions(
        accounts = app.accounts,
        ensureAccounts = { app.ensureAccounts() },
        autoSwitch = app.accountsAutoSwitch,
        loadAutoSwitch = { app.loadAccountsAutoSwitch() },
        setAutoSwitch = { app.setAccountsAutoSwitch(it) },
        addAccount = { agent, method, secret, label -> app.addAccount(agent, method, secret, label) },
        removeAccount = { id, deleteHome -> app.removeAccount(id, deleteHome) },
        startLogin = { app.startAccountLogin(it) },
        pollLogin = { app.accountLoginState(it) },
        loginFrames = app.accountLogins,
        sendLoginCode = { id, code -> app.sendAccountLoginCode(id, code) },
        cancelLogin = { app.cancelAccountLogin(it) },
    )
}

/** [AccountsActions] against the fleet's ACTIVE host — Android / web / iOS wiring. */
@Composable
fun rememberAccountsActions(fleet: FleetStore): AccountsActions = remember(fleet) {
    AccountsActions(
        accounts = fleet.activeAccounts,
        ensureAccounts = { fleet.activeApp()?.ensureAccounts() },
        autoSwitch = fleet.activeAccountsAutoSwitch,
        loadAutoSwitch = { fleet.activeApp()?.loadAccountsAutoSwitch() },
        setAutoSwitch = { fleet.activeApp()?.setAccountsAutoSwitch(it) ?: NOT_WIRED },
        addAccount = { agent, method, secret, label ->
            fleet.activeApp()?.addAccount(agent, method, secret, label) ?: NOT_WIRED
        },
        removeAccount = { id, deleteHome -> fleet.activeApp()?.removeAccount(id, deleteHome) ?: NOT_WIRED },
        startLogin = { fleet.activeApp()?.startAccountLogin(it) ?: NOT_WIRED },
        pollLogin = { fleet.activeApp()?.accountLoginState(it) },
        loginFrames = fleet.activeAccountLogins,
        sendLoginCode = { id, code -> fleet.activeApp()?.sendAccountLoginCode(id, code) ?: NOT_WIRED },
        cancelLogin = { fleet.activeApp()?.cancelAccountLogin(it) ?: NOT_WIRED },
    )
}

/**
 * Settings → Accounts.
 *
 * @param topBarShown the hub already painted a bar (`SettingsSlotScope.topBarShown`); a Compact
 *   window without one gets this screen's own, like every other section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsSettingsScreen(
    actions: AccountsActions,
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
                    title = { Text("Accounts", color = cs.onSurface) },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("accounts_settings_back")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = cs.onSurface)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surfaceContainerHigh),
                )
            },
            containerColor = cs.background,
        ) { padding -> AccountsSettingsBody(actions, modifier.padding(padding)) }
    } else {
        AccountsSettingsBody(actions, modifier)
    }
}

@Composable
private fun AccountsSettingsBody(actions: AccountsActions, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val accounts by actions.accounts.collectAsState(null)
    val autoSwitch by actions.autoSwitch.collectAsState(null)
    var addFor by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<AccountDto?>(null) }
    var autoSwitchError by remember { mutableStateOf<String?>(null) }
    var pendingAutoSwitch by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(actions) {
        actions.ensureAccounts()
        actions.loadAutoSwitch()
    }

    Box(
        modifier
            .fillMaxSize()
            .background(cs.background)
            .testTag("accounts_settings_screen"),
        contentAlignment = Alignment.TopCenter,
    ) {
        val list = accounts
        if (list == null) {
            CircularProgressIndicator(
                color = cs.primary,
                modifier = Modifier.align(Alignment.Center).testTag("accounts_settings_loading"),
            )
            return@Box
        }
        val agents = ACCOUNT_AGENTS.filter { a -> list.any { it.agent == a } } +
            list.map { it.agent }.distinct().filter { it !in ACCOUNT_AGENTS }
        LazyColumn(
            Modifier.widthIn(max = SettingsDetailMaxWidth).fillMaxWidth().fillMaxSize(),
            contentPadding = PaddingValues(start = Space.lg, end = Space.lg, top = Space.lg, bottom = Space.xxl),
            verticalArrangement = Arrangement.spacedBy(Space.lg),
        ) {
            item(key = "intro") {
                // The one-line empty state, said once for the page rather than under every agent.
                val none = list.none { !it.system }
                Text(
                    if (none) {
                        "Only this machine's logins so far. Add another account to pick one per chat, or to keep working when one hits its usage limit."
                    } else {
                        "Pick an account per chat, or keep working on another one when an account hits its usage limit."
                    },
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = if (none) Modifier.testTag("accounts-empty") else Modifier,
                )
            }
            item(key = "autoswitch") {
                AutoSwitchCard(
                    checked = pendingAutoSwitch ?: autoSwitch ?: false,
                    error = autoSwitchError,
                    onChange = { on ->
                        pendingAutoSwitch = on
                        autoSwitchError = null
                        scope.launch {
                            val r = actions.setAutoSwitch(on)
                            pendingAutoSwitch = null
                            if (r is AccountResult.Failed) autoSwitchError = r.message ?: "Couldn't save the setting."
                        }
                    },
                )
            }
            agents.forEach { agent ->
                item(key = "agent:$agent") {
                    AgentAccountsGroup(
                        agent = agent,
                        accounts = accountsFor(list, agent),
                        onAdd = { addFor = agent },
                        onRemove = { removing = it },
                    )
                }
            }
        }
    }

    addFor?.let { agent ->
        AddAccountSheet(
            agent = agent,
            actions = actions,
            existing = accountsFor(accounts.orEmpty(), agent),
            onDismiss = { addFor = null },
        )
    }
    removing?.let { account ->
        RemoveAccountDialog(
            account = account,
            onDismiss = { removing = null },
            onConfirm = { deleteHome -> actions.removeAccount(account.id, deleteHome) },
        )
    }
}

@Composable
private fun AutoSwitchCard(checked: Boolean, error: String?, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    AccountsCard(Modifier.testTag("accounts-autoswitch-card")) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onChange(!checked) }
                .padding(horizontal = Space.lg, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Switch accounts automatically",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                )
                Text(
                    "When one hits its usage limit, the chat continues on another account of the same agent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag("accounts-autoswitch"))
        }
        if (error != null) {
            Text(
                error,
                color = cs.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = Space.lg, end = Space.lg, bottom = Space.md).testTag("accounts-autoswitch-error"),
            )
        }
    }
}

/** A hairline-outlined rounded card — the request-card surface, one tone down. */
@Composable
internal fun AccountsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radii.md),
        color = cs.surfaceContainer,
        border = BorderStroke(Stroke.hairline, cs.outlineVariant.copy(alpha = 0.7f)),
    ) { Column { content() } }
}

@Composable
private fun AgentAccountsGroup(
    agent: String,
    accounts: List<AccountDto>,
    onAdd: () -> Unit,
    onRemove: (AccountDto) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().testTag("accounts-group:$agent"), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Row(
            Modifier.fillMaxWidth().padding(start = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            AgentLogo(agent, size = 18.dp)
            Text(
                agentDisplayName(agent),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onAdd,
                contentPadding = PaddingValues(horizontal = Space.sm),
                modifier = Modifier.height(32.dp).testTag("account-add:$agent"),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text("Add account", style = MaterialTheme.typography.labelLarge)
            }
        }
        AccountsCard {
            accounts.forEachIndexed { i, account ->
                if (i > 0) HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.6f))
                AccountRow(account, onRemove = { onRemove(account) })
            }
        }
    }
}

private fun accountIcon(account: AccountDto): ImageVector = when (account.method) {
    "system" -> Icons.Filled.Computer
    "api_key" -> Icons.Filled.Key
    "token" -> Icons.Outlined.ConfirmationNumber
    else -> Icons.Filled.Person
}

@Composable
private fun AccountRow(account: AccountDto, onRemove: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val sem = LocalSemantics.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Space.md, end = Space.xs, top = Space.md, bottom = Space.md)
            .testTag("account-row:${account.id}"),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(if (account.system) cs.onSurface.copy(alpha = 0.06f) else cs.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                accountIcon(account),
                contentDescription = null,
                tint = if (account.system) cs.onSurfaceVariant else cs.primary,
                modifier = Modifier.size(16.dp),
            )
        }
        // 5dp down: the first line's centre meets the 32dp avatar's, whatever follows it.
        Column(Modifier.weight(1f).padding(top = 5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    accountShortLabel(account),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                MethodChip(account.method)
                if (account.isolated) AccountTag("Isolated", sem.warning, Modifier.testTag("account-isolated:${account.id}"))
            }
            accountIdentityLine(account)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (account.isolated) {
                Text(
                    "Keeps its own history; never used for automatic switching.",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            }
            if (account.usage.isNotEmpty()) {
                Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    account.usage.forEach { UsageMeter(it) }
                }
            }
        }
        if (!account.system) {
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp).testTag("account-remove:${account.id}")) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = "Remove ${accountShortLabel(account)}",
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            Spacer(Modifier.size(width = 36.dp, height = 0.dp))
        }
    }
}

@Composable
private fun RemoveAccountDialog(
    account: AccountDto,
    onDismiss: () -> Unit,
    onConfirm: suspend (deleteHome: Boolean) -> AccountResult<Unit>,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var deleteHome by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val subscription = account.method == "subscription"
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.testTag("account-remove-dialog"),
        title = { Text("Remove ${accountShortLabel(account)}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text(
                    "supermux forgets this ${agentDisplayName(account.agent)} account and its saved credential. " +
                        "Chats using it need another account to continue.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (subscription) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radii.sm))
                            .clickable { deleteHome = !deleteHome }
                            .padding(vertical = Space.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = deleteHome,
                            onCheckedChange = { deleteHome = it },
                            modifier = Modifier.testTag("account-remove-delete-home"),
                        )
                        Text("Also delete its sign-in folder", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                error?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        when (val r = onConfirm(subscription && deleteHome)) {
                            is AccountResult.Ok -> onDismiss()
                            is AccountResult.Failed -> {
                                busy = false
                                error = r.message ?: "Couldn't remove the account."
                            }
                        }
                    }
                },
                modifier = Modifier.testTag("account-remove-confirm"),
            ) { Text(if (busy) "Removing…" else "Remove", color = cs.error) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.testTag("account-remove-cancel")) {
                Text("Cancel")
            }
        },
    )
}
