// Providers & accounts (#97): one provider's sign-in and install, shared by the list and the provider's page. The
// holders live at the Providers section (`rememberProvidersState`), above both views, so going from the list to a
// provider and back keeps a login or an install that is under way; the poll loops run there too.
//
// The state machines are today's Agents screen's, moved out of its row unchanged: the bounded login/install polls,
// resuming a login or install the machine is already running (closing and reopening Settings does not lose it), and
// the load model that tells a failed load from an empty one.
package dev.supermux.ui.settings.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.supermux.net.AgentInstallJob
import dev.supermux.net.AgentInstallStatus
import dev.supermux.net.AgentLoginState
import dev.supermux.ui.settings.AgentSettingsActions
import dev.supermux.ui.settings.AgentsLoadState
import dev.supermux.ui.settings.LOGIN_KINDS
import dev.supermux.ui.settings.isActiveLoginPhase
import dev.supermux.ui.settings.kit.currentMachineOnline
import dev.supermux.ui.settings.normalizeInstallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val POLL_INTERVAL_MS = 1500L
/** ~2 minutes of polling before surfacing a timeout. */
private const val POLL_MAX_TICKS = 80
/** Consecutive null/failed poll responses before treating the machine as gone. */
private const val POLL_NULL_STREAK_LIMIT = 8

/** One provider's CLI sign-in and install, as both views show them. */
@Stable
class ProviderState internal constructor(val kind: String) {
    var login by mutableStateOf<AgentLoginState?>(null)
        internal set
    var loginActive by mutableStateOf(false)
        internal set
    var loginStartFailed by mutableStateOf(false)
        internal set
    var loginTimedOut by mutableStateOf(false)
        internal set
    /** Bumped by every [startLogin]: a new run of the login loop even when one has just ended. */
    internal var loginRun by mutableIntStateOf(0)
    /** The login was resumed from the machine: the loop must not start a new one. */
    internal var loginResumed by mutableStateOf(false)

    var install by mutableStateOf<AgentInstallJob?>(null)
        internal set
    var installActive by mutableStateOf(false)
        internal set
    var installRequestFailed by mutableStateOf(false)
        internal set
    var installTimedOut by mutableStateOf(false)
        internal set
    internal var installResumed by mutableStateOf(false)
    /** Local only: the user stopped watching an install that may still run on the machine. */
    var installStoppedWatching by mutableStateOf(false)
        internal set

    /** Sign in (or again): a fresh login, never one a stale phase from a timeout or failure would skip. */
    fun startLogin() {
        login = null
        loginResumed = false
        loginStartFailed = false
        loginTimedOut = false
        loginActive = true
        loginRun++
    }

    /** Cancel on the machine, and forget the login here. */
    fun cancelLogin(scope: CoroutineScope, actions: AgentSettingsActions) {
        scope.launch { actions.agentCancelLogin(kind) }
        loginActive = false
        login = null
        loginStartFailed = false
        loginTimedOut = false
    }

    /** Puts away a finished login's last word ("Signed in.", a failure). */
    fun dismissLogin() {
        loginActive = false
        login = null
    }

    /** Install — or watch again an install still running after "Stop watching", without starting another. */
    fun startInstall() {
        if (installActive) return
        installRequestFailed = false
        installTimedOut = false
        if (installStoppedWatching && normalizeInstallState(install?.state) == "running") {
            installResumed = true
        } else {
            install = null
            installResumed = false
        }
        installStoppedWatching = false
        installActive = true
    }

    /** Local only: there is no way to cancel an install on the machine. The job keeps running and can be watched again. */
    fun stopWatchingInstall() {
        installActive = false
        installTimedOut = false
        installStoppedWatching = true
    }
}

/** The provider list of one machine, and a [ProviderState] per provider on it. */
@Stable
class ProvidersState internal constructor() {
    internal var load by mutableStateOf<AgentsLoadState>(AgentsLoadState.Loading)
    internal var reloadKey by mutableIntStateOf(0)
    private val holders = HashMap<String, ProviderState>()

    val statuses: List<AgentInstallStatus> get() = (load as? AgentsLoadState.Ready)?.statuses.orEmpty()

    fun status(kind: String): AgentInstallStatus? = statuses.firstOrNull { it.kind == kind }

    /** The holder for [kind]: the same one for the list and the provider's page. */
    fun holder(kind: String): ProviderState = holders.getOrPut(kind) { ProviderState(kind) }

    fun reload() {
        reloadKey++
    }
}

/**
 * The machine's providers, loaded (and reloaded after a sign-in, a saved token or an install), with every provider's
 * login and install loops running here. Remember it above the list and the provider pages.
 *
 * @param enabled false for an offline machine: nothing loads (another host would answer for it).
 * @param onStatusesChanged every list that loaded (not an empty or failed one) — the setup wizard gates on it.
 */
@Composable
fun rememberProvidersState(
    actions: AgentSettingsActions,
    enabled: Boolean = currentMachineOnline(),
    onStatusesChanged: (List<AgentInstallStatus>) -> Unit = {},
): ProvidersState {
    val state = remember { ProvidersState() }
    val calls by rememberUpdatedState(actions)
    val report by rememberUpdatedState(onStatusesChanged)

    LaunchedEffect(state, state.reloadKey, enabled) {
        if (!enabled) return@LaunchedEffect
        // Keep the rows on screen while refreshing.
        if (state.load !is AgentsLoadState.Ready) state.load = AgentsLoadState.Loading
        val result = calls.agentStatuses()
        state.load = when {
            result == null -> AgentsLoadState.Error("Couldn't load agent statuses.")
            result.isEmpty() -> AgentsLoadState.Empty
            else -> AgentsLoadState.Ready(result).also { report(result) }
        }
    }
    // While in Error, retry by itself, so a reconnect recovers without closing and reopening.
    LaunchedEffect(state, state.load, state.reloadKey, enabled) {
        if (!enabled || state.load !is AgentsLoadState.Error) return@LaunchedEffect
        while (isActive) {
            delay(ERROR_AUTO_RETRY_MS)
            val result = calls.agentStatuses() ?: continue
            state.load = if (result.isEmpty()) AgentsLoadState.Empty else AgentsLoadState.Ready(result).also { report(result) }
            break
        }
    }
    state.statuses.forEach { status ->
        key(status.kind) { ProviderEffects(state.holder(status.kind), status, calls, onAuthChanged = state::reload) }
    }
    return state
}

/** One provider's resume, login loop and install loop (today's `AgentRow` effects). */
@Composable
private fun ProviderEffects(
    p: ProviderState,
    status: AgentInstallStatus,
    actions: AgentSettingsActions,
    onAuthChanged: () -> Unit,
) {
    val isLoginKind = status.kind in LOGIN_KINDS

    // Resume a login or install the machine is running when this (re)enters composition, so closing and reopening
    // Settings does not drop it.
    LaunchedEffect(status.kind, status.installed, status.authed) {
        if (!status.authed && isLoginKind && status.installed && !p.loginActive) {
            val s = actions.agentPollLogin(status.kind)
            if (s != null && isActiveLoginPhase(s.phase)) {
                p.login = s
                p.loginResumed = true
                p.loginActive = true
            }
        }
        if (!status.installed && !p.installActive) {
            val job = actions.agentPollInstall(status.kind)
            if (job != null && normalizeInstallState(job.state) == "running") {
                p.install = job
                p.installResumed = true
                p.installActive = true
            }
        }
    }

    // The login loop. Resumed from the machine (or holding an active phase), it does NOT start again — that would mint
    // a new session. A timeout or a failed start clears [login], so the next Sign in is not taken for one in progress.
    LaunchedEffect(p.loginActive, p.loginRun, status.kind) {
        if (!p.loginActive) return@LaunchedEffect
        p.loginStartFailed = false
        p.loginTimedOut = false
        val alreadyInProgress = p.loginResumed || isActiveLoginPhase(p.login?.phase)
        p.loginResumed = false
        if (!alreadyInProgress) {
            p.login = null
            val started = actions.agentStartLogin(status.kind)
            if (started == null) {
                p.loginStartFailed = true
                p.login = null
                p.loginActive = false
                return@LaunchedEffect
            }
            p.login = started
        }
        var ticks = 0
        var nullStreak = 0
        while (isActive) {
            delay(POLL_INTERVAL_MS)
            ticks++
            val s = actions.agentPollLogin(status.kind)
            if (s == null) {
                nullStreak++
                if (nullStreak >= POLL_NULL_STREAK_LIMIT || ticks >= POLL_MAX_TICKS) {
                    p.loginTimedOut = true
                    p.login = null
                    p.loginActive = false
                    break
                }
                continue
            }
            nullStreak = 0
            p.login = s
            when (s.phase) {
                "success" -> {
                    // [login] keeps the success for "Signed in."; the provider reloads as signed in.
                    p.loginActive = false
                    onAuthChanged()
                    break
                }
                "failed", "cancelled" -> break
                else -> if (ticks >= POLL_MAX_TICKS) {
                    p.loginTimedOut = true
                    p.login = null
                    p.loginActive = false
                    break
                }
            }
        }
    }

    // The install loop; resumed like the login (no second start for a job already running).
    LaunchedEffect(p.installActive, status.kind) {
        if (!p.installActive) return@LaunchedEffect
        p.installTimedOut = false
        val alreadyInProgress = p.installResumed || normalizeInstallState(p.install?.state) == "running"
        p.installResumed = false
        if (!alreadyInProgress) {
            val initial = actions.agentStartInstall(status.kind)
            if (initial == null) {
                p.installRequestFailed = true
                p.installActive = false
                return@LaunchedEffect
            }
            p.install = initial
        }
        var ticks = 0
        var nullStreak = 0
        while (isActive) {
            when (normalizeInstallState(p.install?.state)) {
                "done" -> {
                    onAuthChanged()
                    p.installActive = false
                    break
                }
                "failed" -> {
                    p.installActive = false
                    break
                }
            }
            delay(1_000L)
            ticks++
            val next = actions.agentPollInstall(status.kind)
            if (next == null) {
                nullStreak++
                if (nullStreak >= POLL_NULL_STREAK_LIMIT || ticks >= POLL_MAX_TICKS) {
                    p.installTimedOut = true
                    p.installActive = false
                    break
                }
                continue
            }
            nullStreak = 0
            p.install = next
            if (ticks >= POLL_MAX_TICKS) {
                p.installTimedOut = true
                p.installActive = false
                break
            }
        }
    }
}
