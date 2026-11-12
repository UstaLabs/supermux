// The Agents settings (cluster E2), now Providers & accounts (#97): the store calls as one [AgentSettingsActions]
// holder, the pure helpers both views share, and [AgentSettingsScreen] — the list with a provider opening in place —
// for the first-run setup wizard, which has no Settings hub around it. Settings itself shows `ProvidersPage` and
// `ProviderDetailPage` (`settings/pages`), with the state and its loops in `ProviderState.kt`.
package dev.supermux.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.supermux.net.AgentInstallJob
import dev.supermux.net.AgentInstallStatus
import dev.supermux.net.AgentLoginState
import dev.supermux.net.OpenCodeOAuthStart
import dev.supermux.net.OpenCodeProvider
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.ui.accounts.AccountsActions
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.settings.pages.ProviderDetailPage
import dev.supermux.ui.settings.pages.ProvidersPage
import dev.supermux.ui.settings.pages.rememberProvidersState
import dev.supermux.ui.i18n.localized

/** The providers whose own sign-in runs a login flow (OpenCode's is its subscription's, on its page). */
internal val LOGIN_KINDS = setOf("claude", "codex", "cursor", "grok", "antigravity")

/** Phases considered "in progress" — used to resume login UI after overlay close+reopen. */
internal fun isActiveLoginPhase(phase: String?): Boolean =
    phase == "starting" || phase == "awaiting_user"

/** The failed-install headline: the exit code when there is one, then the broker's reason. English, for the tests and non-composable callers. */
internal fun installFailureMessage(job: AgentInstallJob?): String {
    val head = job?.exitCode?.let { "Installation failed (exit code $it)." } ?: "Installation failed." // i18n-ok: English twin of localizedInstallFailureMessage
    val why = job?.error?.trim().orEmpty().replaceFirstChar { it.uppercase() }
    return if (why.isEmpty()) head else "$head $why"
}

/** [installFailureMessage] in the app's language; the broker's reason stays as the broker wrote it. */
@Composable
internal fun localizedInstallFailureMessage(job: AgentInstallJob?): String {
    val code = job?.exitCode
    val head = if (code != null) localized(Str.agent_settings_install_failed_code, code) else localized(Str.agent_settings_install_failed)
    val why = job?.error?.trim().orEmpty().replaceFirstChar { it.uppercase() }
    return if (why.isEmpty()) head else "$head $why"
}

/** What the install log box shows: the last [lines] lines (an installer's error is at the end). */
internal fun installLogTail(log: String, lines: Int = 20): String =
    log.trimEnd().lines().takeLast(lines).joinToString("\n").takeLast(4_000)

/** Install job states: idle (null) → running/pending → done | failed (error). */
internal fun normalizeInstallState(state: String?): String = when (state?.lowercase()) {
    "running", "pending" -> "running"
    "done", "success" -> "done"
    "failed", "error" -> "failed"
    else -> state.orEmpty()
}

/** A provider's status in English, for the tests and non-composable callers; the UI shows [localizedStatusLabel]. */
internal fun statusLabel(status: AgentInstallStatus): String = when {
    // The CLI still has its credential file, but the provider refused the login: sign in again.
    status.signInExpired -> "Sign-in expired — sign in again" // i18n-ok: English twin of localizedStatusLabel
    status.authed -> "Authenticated" // i18n-ok: English twin of localizedStatusLabel
    !status.installed -> "Not installed" // i18n-ok: English twin of localizedStatusLabel
    status.kind == "opencode" -> "Ready · free tier" // i18n-ok: English twin of localizedStatusLabel
    else -> "Installed, not authenticated" // i18n-ok: English twin of localizedStatusLabel
}

/** [statusLabel] in the app's language. */
@Composable
internal fun localizedStatusLabel(status: AgentInstallStatus): String = localized(
    when {
        status.signInExpired -> Str.agent_settings_sign_in_expired
        status.authed -> Str.agent_settings_authenticated
        !status.installed -> Str.agent_settings_not_installed
        status.kind == "opencode" -> Str.agent_settings_ready_free_tier
        else -> Str.agent_settings_installed_not_authenticated
    },
)

internal fun prettyProviderName(id: String): String =
    id.split('-', '_')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

/** Load model for the agents list — failure is distinct from a legitimate empty response. */
internal sealed class AgentsLoadState {
    data object Loading : AgentsLoadState()
    data object Empty : AgentsLoadState()
    data class Ready(val statuses: List<AgentInstallStatus>) : AgentsLoadState()
    data class Error(val message: String) : AgentsLoadState()
}

/**
 * Every broker call the Agents screen makes, in one holder.
 *
 * Shapes are desktop's: `null` means "the call failed", distinct from a legitimate empty list, and
 * a mutation returns whether it took. Android's `FleetStore` wrappers were fire-and-forget
 * `Unit`s — retyped in `:shared` rather than dumbing this down, so a failed save now surfaces on
 * both hosts instead of silently clearing the field.
 */
@Immutable
class AgentSettingsActions(
    /**
     * Load agent install/auth statuses.
     * `null` = transport/decode failure; empty list = legitimate empty; non-empty = data.
     */
    val agentStatuses: suspend () -> List<AgentInstallStatus>? = { null },
    val agentStartLogin: suspend (kind: String) -> AgentLoginState? = { null },
    val agentPollLogin: suspend (kind: String) -> AgentLoginState? = { null },
    val agentSendCode: suspend (kind: String, code: String) -> Unit = { _, _ -> },
    val agentCancelLogin: suspend (kind: String) -> Unit = {},
    val agentSaveSecret: suspend (kind: String, value: String) -> Boolean = { _, _ -> false },
    val agentStartInstall: suspend (kind: String) -> AgentInstallJob? = { null },
    val agentPollInstall: suspend (kind: String) -> AgentInstallJob? = { null },
    val openCodeProviders: suspend () -> List<OpenCodeProvider> = { emptyList() },
    val openCodeSetKey: suspend (providerId: String, key: String) -> Boolean = { _, _ -> false },
    val openCodeStartOAuth: suspend (providerId: String, method: Int) -> OpenCodeOAuthStart? =
        { _, _ -> null },
    val openCodeFinishOAuth: suspend (providerId: String, method: Int, code: String) -> Boolean =
        { _, _, _ -> false },
)

/** [AgentSettingsActions] against one paired host — desktop's wiring, unchanged. */
@Composable
fun rememberAgentSettingsActions(app: HostStore): AgentSettingsActions = remember(app) { agentSettingsActions(app) }

/**
 * The plain builder behind [rememberAgentSettingsActions] — for a holder that is itself built
 * inside a `remember` (the launcher's install-an-agent panel, `LauncherActions.agentSetup`).
 */
fun agentSettingsActions(app: HostStore): AgentSettingsActions =
    AgentSettingsActions(
        agentStatuses = { app.agentStatuses() },
        agentStartLogin = { app.startAgentLogin(it) },
        agentPollLogin = { app.agentLoginState(it) },
        agentSendCode = { kind, code -> app.sendAgentLoginCode(kind, code) },
        agentCancelLogin = { app.cancelAgentLogin(it) },
        agentSaveSecret = { kind, value -> app.saveAgentSecret(kind, value) },
        agentStartInstall = { app.startAgentInstall(it) },
        agentPollInstall = { app.agentInstallState(it) },
        openCodeProviders = { app.openCodeProviders() },
        openCodeSetKey = { id, key -> app.setOpenCodeKey(id, key) },
        openCodeStartOAuth = { id, method -> app.startOpenCodeOAuth(id, method) },
        openCodeFinishOAuth = { id, method, code -> app.finishOpenCodeOAuth(id, method, code) },
    )

/**
 * [AgentSettingsActions] against the fleet's ACTIVE host — Android's wiring.
 *
 * Every call resolves `activeApp()` when it runs, so a host switch under the open screen reaches
 * the new broker; the screen itself is remounted by the host's `key(activeHost)` wrapper anyway.
 */
@Composable
fun rememberAgentSettingsActions(fleet: FleetStore): AgentSettingsActions = remember(fleet) { agentSettingsActions(fleet) }

/** The plain builder behind [rememberAgentSettingsActions] (see the [HostStore] twin). */
fun agentSettingsActions(fleet: FleetStore): AgentSettingsActions =
    AgentSettingsActions(
        agentStatuses = { fleet.agentStatuses() },
        agentStartLogin = { fleet.startAgentLogin(it) },
        agentPollLogin = { fleet.agentLoginState(it) },
        agentSendCode = { kind, code -> fleet.agentSendCode(kind, code) },
        agentCancelLogin = { fleet.agentCancelLogin(it) },
        agentSaveSecret = { kind, value -> fleet.agentSaveSecret(kind, value) },
        agentStartInstall = { fleet.startAgentInstall(it) },
        agentPollInstall = { fleet.agentInstallState(it) },
        openCodeProviders = { fleet.openCodeProviders() },
        openCodeSetKey = { id, key -> fleet.openCodeSetKey(id, key) },
        openCodeStartOAuth = { id, method -> fleet.startOpenCodeOAuth(id, method) },
        openCodeFinishOAuth = { id, method, code -> fleet.openCodeFinishOAuth(id, method, code) },
    )

/**
 * The provider list, a provider opening in place (with "‹ Providers & accounts" back) — for the first-run setup wizard,
 * which embeds it with no Settings hub to push a provider's page. Its signature is today's, for the wizard and the
 * launcher's install panel.
 *
 * @param onBack unused since #97: the wizard and the hub own the chrome (kept for the callers).
 * @param topBarShown unused since #97, as [onBack].
 * @param onStatusesChanged every list this screen loads (initial load, Try again, the error auto-retry), for the
 *   wizard, which gates its "Next" on whether any agent is usable. Not called for an empty or failed load: the wizard
 *   keeps its last answer rather than un-gating on a transport blip.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun AgentSettingsScreen(
    actions: AgentSettingsActions,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    topBarShown: Boolean = false,
    onStatusesChanged: (List<AgentInstallStatus>) -> Unit = {},
) {
    val providers = rememberProvidersState(actions, onStatusesChanged = onStatusesChanged)
    // No "Add a login" here: the wizard has no accounts wiring, so it would always fail; Your login still signs the machine in.
    val accounts = remember { AccountsActions() }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    when (val agent = open) {
        null -> ProvidersPage(providers, accounts, onOpenProvider = { open = it }, modifier = modifier, showAccountSettings = false)
        else -> ProviderDetailPage(agent, providers, actions, accounts, modifier = modifier, onBack = { open = null }, backOnPhone = true, showAddLogin = false)
    }
}
