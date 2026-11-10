// Every broker call the accounts UI makes, in one holder (slice A3b), and its wiring against one host (desktop) or the
// fleet's active host (Android, web, iOS). Moved out of the old Settings ▸ Accounts screen unchanged when #97 put the
// logins on each provider's page.
package dev.supermux.ui.accounts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import dev.supermux.net.AccountDto
import dev.supermux.net.AccountLoginStateDto
import dev.supermux.net.AccountRemovalPlanDto
import dev.supermux.state.AccountResult
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

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
    /** #92: wait for the first limit reset and carry on (ON unless turned off). Loaded with [loadAutoSwitch]. */
    val autoResume: Flow<Boolean?> = flowOf(null),
    val setAutoResume: suspend (Boolean) -> AccountResult<Boolean> = { NOT_WIRED },
    val addAccount: suspend (agent: String, method: String, secret: String, label: String?) -> AccountResult<AccountDto> =
        { _, _, _, _ -> NOT_WIRED },
    val removeAccount: suspend (id: String, deleteHome: Boolean) -> AccountResult<Unit> = { _, _ -> NOT_WIRED },
    /** POST /accounts/login; [email] signs in as that login (Claude), which repairs that account when it exists (#106). */
    val startLogin: suspend (agent: String, email: String?) -> AccountResult<AccountLoginStateDto> = { _, _ -> NOT_WIRED },
    /** GET /accounts/<id>/removal: how many chats removing it moves, and where (null = unknown). */
    val removalPlan: suspend (id: String) -> AccountRemovalPlanDto? = { null },
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
        autoResume = app.accountsAutoResume,
        setAutoResume = { app.setAccountsAutoResume(it) },
        addAccount = { agent, method, secret, label -> app.addAccount(agent, method, secret, label) },
        removeAccount = { id, deleteHome -> app.removeAccount(id, deleteHome) },
        startLogin = { agent, email -> app.startAccountLogin(agent, email = email) },
        removalPlan = { app.accountRemovalPlan(it) },
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
        autoResume = fleet.activeAccountsAutoResume,
        setAutoResume = { fleet.activeApp()?.setAccountsAutoResume(it) ?: NOT_WIRED },
        addAccount = { agent, method, secret, label ->
            fleet.activeApp()?.addAccount(agent, method, secret, label) ?: NOT_WIRED
        },
        removeAccount = { id, deleteHome -> fleet.activeApp()?.removeAccount(id, deleteHome) ?: NOT_WIRED },
        startLogin = { agent, email -> fleet.activeApp()?.startAccountLogin(agent, email = email) ?: NOT_WIRED },
        removalPlan = { fleet.activeApp()?.accountRemovalPlan(it) },
        pollLogin = { fleet.activeApp()?.accountLoginState(it) },
        loginFrames = fleet.activeAccountLogins,
        sendLoginCode = { id, code -> fleet.activeApp()?.sendAccountLoginCode(id, code) ?: NOT_WIRED },
        cancelLogin = { fleet.activeApp()?.cancelAccountLogin(it) ?: NOT_WIRED },
    )
}
