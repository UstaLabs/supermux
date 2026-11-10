package dev.supermux.ui.accounts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
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
import dev.supermux.net.AccountRemovalPlanDto
import dev.supermux.state.AccountResult
import dev.supermux.ui.retain.rememberRetainedOnPage
import dev.supermux.ui.settings.kit.KitGhostButton
import dev.supermux.ui.settings.kit.KitItem
import dev.supermux.ui.settings.kit.KitItemList
import dev.supermux.ui.settings.kit.KitMark
import dev.supermux.ui.settings.kit.KitSheet
import dev.supermux.ui.settings.kit.KitToggleRow
import dev.supermux.ui.settings.kit.kitColors
import kotlinx.coroutines.launch

/**
 * A provider page's logins (#97's layout): "Your login" (the provider's own, never removable; its "Sign in again"
 * is the provider's flow, [onSignInSystem], with [systemLoginExtra] under it), the added ones, then "+ Add a login"
 * (not for OpenCode). An added login whose sign-in expired says so in the warning tone with "Sign in again" (#106):
 * the guided login as its email, which repairs that same account. Tapping an added login opens it: sign in again,
 * or remove (saying where its chats go). Each added login shows its usage, and one kept apart says it doesn't switch.
 *
 * [own] is Your login as the provider's page knows it from the machine's CLI (#97): its line, its tone and its Sign in
 * / Sign in again. With it, Your login is drawn even before the logins load (or where there are none).
 */
@Composable
fun ProviderLogins(
    agent: String,
    accounts: List<AccountDto>,
    actions: AccountsActions,
    onSignInSystem: () -> Unit,
    systemLoginExtra: (@Composable () -> Unit)? = null,
    own: OwnLogin? = null,
    showAddLogin: Boolean = true,
) {
    // The open add sheet, kept for the page on display (#89): a rotation closed it in the middle of a sign-in that went
    // on on the host. The sheet keeps its own state under its key.
    val adding = rememberRetainedOnPage("accounts:adding:$agent") { LoginAdding() }
    var signingIn by remember { mutableStateOf<AccountDto?>(null) }
    var opened by remember { mutableStateOf<AccountDto?>(null) }
    var removing by remember { mutableStateOf<AccountDto?>(null) }
    val mine = accounts.filter { it.agent == agent }
    val added = mine.filter { !it.system }

    KitItemList {
        val system = mine.firstOrNull { it.system }
        if (system != null || own != null) {
            val expired = system != null && signInExpired(system)
            KitItem(
                title = "Your login",
                modifier = Modifier.testTag("provider_login_${system?.id ?: "$agent:system"}"),
                subtitle = own?.subtitle
                    ?: if (expired) "Sign-in expired" else system?.identity?.email?.takeIf { it.isNotBlank() } ?: "This machine's login",
                subtitleWarn = own?.warn ?: expired,
                mark = KitMark.Letters(system?.let(::initials) ?: agentDisplayName(agent).take(2).uppercase()),
            ) {
                when {
                    own != null -> own.trailing(this)
                    expired && system != null ->
                        KitGhostButton("Sign in again", onClick = onSignInSystem, modifier = Modifier.testTag("provider_login_${system.id}_sign_in"))
                }
            }
        }
        systemLoginExtra?.invoke()
        added.forEach { account ->
            val expired = signInExpired(account)
            KitItem(
                title = accountDisplayName(account),
                modifier = Modifier.testTag("provider_login_${account.id}"),
                subtitle = if (expired) "Sign-in expired" else accountUnavailableText(account) ?: loginDetail(account),
                subtitleWarn = account.unavailable != null,
                mark = KitMark.Letters(initials(account)),
                onClick = { opened = account },
                below = if (account.isolated || account.usage.isNotEmpty()) {
                    {
                        if (account.isolated) Text("Doesn't switch automatically", fontSize = 12.sp, color = kitColors().dim)
                        if (account.usage.isNotEmpty()) {
                            // Its own node: the item merges what it holds, and the meters read as one group.
                            Column(Modifier.semantics(mergeDescendants = true) {}.testTag("provider_login_usage_${account.id}")) {
                                account.usage.forEach { UsageMeter(it) }
                            }
                        }
                    }
                } else {
                    null
                },
            ) {
                if (expired) {
                    KitGhostButton(
                        "Sign in again",
                        onClick = { signingIn = account },
                        modifier = Modifier.testTag("provider_login_${account.id}_sign_in"),
                    )
                }
            }
        }
        // OpenCode's guided login is broken today (#97 spec §11).
        if (agent != "opencode" && showAddLogin) KitItem("Add a login", Modifier.testTag("provider_login_add"), mark = KitMark.Add, onClick = { adding.open = true })
    }

    if (adding.open) AddAccountSheet(agent, actions, existing = mine, onDismiss = { adding.open = false }, retainKey = "accounts:add:$agent")
    signingIn?.let { account ->
        AddAccountSheet(agent, actions, existing = mine, onDismiss = { signingIn = null }, signInAgain = account)
    }
    opened?.let { account ->
        KitSheet(
            title = accountDisplayName(account),
            onDismiss = { opened = null },
            description = if (signInExpired(account)) "Sign-in expired. Signing in again keeps this login and its chats." else loginDetail(account),
            tag = "provider_login_sheet",
            buttons = {
                KitGhostButton("Remove", onClick = { opened = null; removing = account }, destructive = true,
                    modifier = Modifier.testTag("provider_login_${account.id}_remove"))
                KitGhostButton("Sign in again", onClick = { opened = null; signingIn = account })
            },
        )
    }
    removing?.let { account -> RemoveLoginSheet(account, actions, onDone = { removing = null }) }
}

/** Removing an added login: says where its chats go (GET /accounts/<id>/removal) before anything moves. */
@Composable
fun RemoveLoginSheet(account: AccountDto, actions: AccountsActions, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var plan by remember(account.id) { mutableStateOf<AccountRemovalPlanDto?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleteHome by remember(account.id) { mutableStateOf(false) }
    // Only a subscription has a sign-in folder of its own.
    val subscription = account.method == "subscription"
    LaunchedEffect(account.id) { plan = actions.removalPlan(account.id) }
    KitSheet(
        title = "Remove ${accountDisplayName(account)}?",
        onDismiss = { if (!busy) onDone() },
        description = listOfNotNull("supermux forgets this login.", removalSentence(plan)).joinToString(" "),
        tag = "provider_login_remove_sheet",
        dismissOnOutside = false,
        buttons = {
            KitGhostButton("Cancel", onClick = onDone, enabled = !busy)
            KitGhostButton(
                "Remove",
                onClick = {
                    busy = true
                    scope.launch {
                        when (val r = actions.removeAccount(account.id, subscription && deleteHome)) {
                            is AccountResult.Ok -> onDone()
                            is AccountResult.Failed -> { error = r.message ?: "Couldn't remove it."; busy = false }
                        }
                    }
                },
                enabled = !busy,
                destructive = true,
                modifier = Modifier.testTag("provider_login_remove_confirm"),
            )
        },
    ) {
        if (subscription) {
            KitToggleRow(
                "Also delete its sign-in folder",
                checked = deleteHome,
                onCheckedChange = { deleteHome = it },
                modifier = Modifier.padding(top = 8.dp).testTag("account-remove-delete-home"),
                enabled = !busy,
                divider = false,
            )
        }
        error?.let { Text(it, fontSize = 12.sp, color = kitColors().warn, modifier = Modifier.padding(top = 8.dp)) }
    }
}

/** Your login on a provider's page (#97): its line ([warn]: expired or refused) and its Sign in / Sign in again. */
@Immutable
class OwnLogin(val subtitle: String, val warn: Boolean, val trailing: @Composable RowScope.() -> Unit)

private fun loginDetail(account: AccountDto): String? {
    val email = account.identity?.email?.takeIf { it.isNotBlank() }
    return listOfNotNull(methodLabel(account.method), email?.takeIf { it != accountDisplayName(account) }).joinToString(" · ").ifBlank { null }
}

private fun initials(account: AccountDto): String =
    (account.identity?.email ?: accountDisplayName(account)).filter { it.isLetterOrDigit() }.take(2).uppercase().ifBlank { "?" }

/** Whether the add-a-login sheet is open, kept for the page on display (`rememberRetainedOnPage`, #89). */
private class LoginAdding {
    var open by mutableStateOf(false)
}
