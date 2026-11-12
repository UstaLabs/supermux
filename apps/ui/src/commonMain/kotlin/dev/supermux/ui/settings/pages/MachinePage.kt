// The machine page (#97 Step 2, spec §2.4, §10.12, §13): the header (status, ONE Restart, updates), While you're
// away (this computer's hosting, desktop only), then the machine's own parts — Devices, Shared links, Disk and Limits
// (`MachineParts.kt`). The copy is the approved prototype's (`~/work/proto-97/step2/step2.js`).
//
// Restart (§13): no confirmation and no chat list — sessions survive a restart — only a status line while supermux is
// down. This computer's own supervised supermux restarts through its supervisor ([ThisComputerHosting.restart]),
// every other one through `POST /system/restart`, whose 409 ("no service manager runs it") gets its own line.
// Stop hosting really stops the agents, so it keeps its confirmation naming the working chats.
package dev.supermux.ui.settings.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.BrokerRestart
import dev.supermux.net.UpdateStatus
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.SessionInfo
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.i18n.localizedPlural
import dev.supermux.ui.resources.Res
import dev.supermux.ui.resources.machine_awake
import dev.supermux.ui.resources.machine_awake_help
import dev.supermux.ui.resources.machine_away
import dev.supermux.ui.resources.machine_background
import dev.supermux.ui.resources.machine_background_help
import dev.supermux.ui.resources.machine_battery
import dev.supermux.ui.resources.machine_cancel
import dev.supermux.ui.resources.machine_cant_restart
import dev.supermux.ui.resources.machine_chats
import dev.supermux.ui.resources.machine_check_failed
import dev.supermux.ui.resources.machine_check_updates
import dev.supermux.ui.resources.machine_checking
import dev.supermux.ui.resources.machine_copied
import dev.supermux.ui.resources.machine_copy
import dev.supermux.ui.resources.machine_copy_all
import dev.supermux.ui.resources.machine_didnt_start
import dev.supermux.ui.resources.machine_didnt_start_here
import dev.supermux.ui.resources.machine_didnt_start_reason
import dev.supermux.ui.resources.machine_done
import dev.supermux.ui.resources.machine_elsewhere
import dev.supermux.ui.resources.machine_fallback_name
import dev.supermux.ui.resources.machine_from_anywhere
import dev.supermux.ui.resources.machine_git_install_error
import dev.supermux.ui.resources.machine_git_missing
import dev.supermux.ui.resources.machine_host_on
import dev.supermux.ui.resources.machine_host_on_help
import dev.supermux.ui.resources.machine_hosting_auto_login
import dev.supermux.ui.resources.machine_hosting_lid_uninstall
import dev.supermux.ui.resources.machine_hosting_lid_waiting
import dev.supermux.ui.resources.machine_install_failed
import dev.supermux.ui.resources.machine_install_git
import dev.supermux.ui.resources.machine_installing
import dev.supermux.ui.resources.machine_last_seen
import dev.supermux.ui.resources.machine_lid
import dev.supermux.ui.resources.machine_lid_help
import dev.supermux.ui.resources.machine_log_lines
import dev.supermux.ui.resources.machine_log_text
import dev.supermux.ui.resources.machine_log_title
import dev.supermux.ui.resources.machine_manage
import dev.supermux.ui.resources.machine_manage_go
import dev.supermux.ui.resources.machine_manage_text
import dev.supermux.ui.resources.machine_manage_title
import dev.supermux.ui.resources.machine_managed
import dev.supermux.ui.resources.machine_managed_on
import dev.supermux.ui.resources.machine_names_and
import dev.supermux.ui.resources.machine_not_hosting
import dev.supermux.ui.resources.machine_not_running_here
import dev.supermux.ui.resources.machine_off
import dev.supermux.ui.resources.machine_offline
import dev.supermux.ui.resources.machine_ok
import dev.supermux.ui.resources.machine_on_network
import dev.supermux.ui.resources.machine_paired_server
import dev.supermux.ui.resources.machine_recent_log
import dev.supermux.ui.resources.machine_reconnect
import dev.supermux.ui.resources.machine_relay
import dev.supermux.ui.resources.machine_relay_help
import dev.supermux.ui.resources.machine_restart
import dev.supermux.ui.resources.machine_restart_failed
import dev.supermux.ui.resources.machine_restarting
import dev.supermux.ui.resources.machine_running
import dev.supermux.ui.resources.machine_running_counts
import dev.supermux.ui.resources.machine_service
import dev.supermux.ui.resources.machine_show_log
import dev.supermux.ui.resources.machine_start_hosting
import dev.supermux.ui.resources.machine_starting_line
import dev.supermux.ui.resources.machine_starting_status
import dev.supermux.ui.resources.machine_stop_hosting
import dev.supermux.ui.resources.machine_stop_kept
import dev.supermux.ui.resources.machine_stop_many
import dev.supermux.ui.resources.machine_stop_none
import dev.supermux.ui.resources.machine_stop_one
import dev.supermux.ui.resources.machine_stop_title
import dev.supermux.ui.resources.machine_stop_wait_others
import dev.supermux.ui.resources.machine_stop_wait_yours
import dev.supermux.ui.resources.machine_this_computer
import dev.supermux.ui.resources.machine_try_again
import dev.supermux.ui.resources.machine_turn_on_awake_help
import dev.supermux.ui.resources.machine_turn_on_background_help
import dev.supermux.ui.resources.machine_turn_on_relay_help
import dev.supermux.ui.resources.machine_turn_on_text
import dev.supermux.ui.resources.machine_up_to_date
import dev.supermux.ui.resources.machine_up_to_date_version
import dev.supermux.ui.resources.machine_update
import dev.supermux.ui.resources.machine_update_couldnt_start
import dev.supermux.ui.resources.machine_update_downloading
import dev.supermux.ui.resources.machine_update_downloading_version
import dev.supermux.ui.resources.machine_update_failed
import dev.supermux.ui.resources.machine_update_failed_to
import dev.supermux.ui.resources.machine_update_installed
import dev.supermux.ui.resources.machine_update_installed_version
import dev.supermux.ui.resources.machine_update_installing
import dev.supermux.ui.resources.machine_update_installing_version
import dev.supermux.ui.resources.machine_update_no_answer
import dev.supermux.ui.resources.machine_update_no_reason
import dev.supermux.ui.resources.machine_update_ready
import dev.supermux.ui.resources.machine_update_ready_version
import dev.supermux.ui.resources.machine_update_starting
import dev.supermux.ui.resources.machine_update_title
import dev.supermux.ui.resources.machine_update_you_have
import dev.supermux.ui.resources.machine_updating
import dev.supermux.ui.resources.machine_whats_new
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.settings.HostingActions
import dev.supermux.ui.settings.HostingPhase
import dev.supermux.ui.settings.HostingUiState
import dev.supermux.ui.settings.DevicesSettingsActions
import dev.supermux.ui.settings.ProxiesSettingsActions
import dev.supermux.ui.settings.WorktreesSettingsActions
import dev.supermux.ui.settings.SystemSettingsActions
import dev.supermux.ui.settings.isRunningState
import dev.supermux.ui.settings.kit.KitDot
import dev.supermux.ui.settings.kit.KitDotMark
import dev.supermux.ui.settings.kit.KitGhostButton
import dev.supermux.ui.settings.kit.KitGlyphs
import dev.supermux.ui.settings.kit.KitNote
import dev.supermux.ui.settings.kit.KitPage
import dev.supermux.ui.settings.kit.KitRow
import dev.supermux.ui.settings.kit.KitSectionHead
import dev.supermux.ui.settings.kit.KitSheet
import dev.supermux.ui.settings.kit.KitTextAction
import dev.supermux.ui.settings.kit.KitToggleRow
import dev.supermux.ui.settings.kit.LocalSettingsMachines
import dev.supermux.ui.settings.kit.glyph
import dev.supermux.ui.settings.kit.kitAnchor
import dev.supermux.ui.settings.kit.kitColors
import dev.supermux.ui.settings.kit.kitMetrics
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.usage.UsageActions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The machine's chats: how many, and the names of those working now (Stop hosting names them). */
@Immutable
data class MachineChats(val total: Int = 0, val working: List<String> = emptyList())

/** Pure: [sessions]' count and the names of those [agents] says are working. */
fun machineChats(sessions: List<SessionInfo>, agents: Map<String, AgentStatus>): MachineChats =
    MachineChats(sessions.size, sessions.filter { agents[it.id]?.busy == true }.map { it.name })

/** The chats of one host (desktop: the machine Settings is about). */
@Composable
fun rememberMachineChats(app: HostStore?): MachineChats {
    if (app == null) return MachineChats()
    val sessions by app.sessions.collectAsState()
    val agents by app.agentState.collectAsState()
    return remember(sessions, agents) { machineChats(sessions, agents) }
}

/** The chats of the fleet's ACTIVE host. */
@Composable
fun rememberMachineChats(fleet: FleetStore): MachineChats {
    val active by fleet.activeHost.collectAsState()
    return rememberMachineChats(remember(active) { fleet.appForRecord(active) ?: fleet.activeApp() })
}

/**
 * This computer's hosting, on its own machine page on desktop (null everywhere else).
 *
 * @property recentLog the log's last lines, shown under "Recent log".
 * @property readLog the whole log for the Show log sheet (newest last).
 * @property startHosting the turn-on sheet's Start hosting, with its three switches.
 * @property restart the supervised restart; returns once it is back (or gave up).
 * @property turnOnError why turning on could not finish setting this computer up, with [retryTurnOn].
 */
@Stable
class ThisComputerHosting(
    val state: HostingUiState,
    val actions: HostingActions,
    val recentLog: List<String> = emptyList(),
    val readLog: suspend () -> List<String> = { emptyList() },
    val startHosting: (background: Boolean, awake: Boolean, relay: Boolean) -> Unit = { _, _, _ -> },
    val restart: suspend () -> Unit = {},
    val turnOnError: String? = null,
    val retryTurnOn: () -> Unit = {},
)

/**
 * The prototype's copy, in the app's language (resources `machine_*`). [stopText] keeps the English the tests assert;
 * the page shows [stopLines].
 */
object MachineCopy {
    val THIS_COMPUTER: String @Composable get() = localized(Res.string.machine_this_computer)
    val PAIRED_SERVER: String @Composable get() = localized(Res.string.machine_paired_server)
    val RESTART: String @Composable get() = localized(Res.string.machine_restart)
    val RESTARTING: String @Composable get() = localized(Res.string.machine_restarting)
    val RESTARTING_STATUS: String @Composable get() = localized(Res.string.machine_restarting)
    val RECONNECT: String @Composable get() = localized(Res.string.machine_reconnect)
    val CHECK: String @Composable get() = localized(Res.string.machine_check_updates)
    val CHECKING: String @Composable get() = localized(Res.string.machine_checking)
    val MANAGED: String @Composable get() = localized(Res.string.machine_managed)
    val NOT_HOSTING: String @Composable get() = localized(Res.string.machine_not_hosting)
    val NOT_RUNNING_HERE: String @Composable get() = localized(Res.string.machine_not_running_here)
    val STARTING_STATUS: String @Composable get() = localized(Res.string.machine_starting_status)
    val DIDNT_START: String @Composable get() = localized(Res.string.machine_didnt_start)
    val AWAY: String @Composable get() = localized(Res.string.machine_away)
    val HOST_ON: String @Composable get() = localized(Res.string.machine_host_on)
    val HOST_ON_HELP: String @Composable get() = localized(Res.string.machine_host_on_help)
    val STARTING_LINE: String @Composable get() = localized(Res.string.machine_starting_line)
    val BACKGROUND: String @Composable get() = localized(Res.string.machine_background)
    val BACKGROUND_HELP: String @Composable get() = localized(Res.string.machine_background_help)
    val AWAKE: String @Composable get() = localized(Res.string.machine_awake)
    val AWAKE_HELP: String @Composable get() = localized(Res.string.machine_awake_help)
    val BATTERY: String @Composable get() = localized(Res.string.machine_battery)
    val LID: String @Composable get() = localized(Res.string.machine_lid)
    val LID_HELP: String @Composable get() = localized(Res.string.machine_lid_help)
    val RELAY: String @Composable get() = localized(Res.string.machine_relay)
    val RELAY_HELP: String @Composable get() = localized(Res.string.machine_relay_help)
    val ON_NETWORK: String @Composable get() = localized(Res.string.machine_on_network)
    val FROM_ANYWHERE: String @Composable get() = localized(Res.string.machine_from_anywhere)
    val OFF: String @Composable get() = localized(Res.string.machine_off)
    val RECENT_LOG: String @Composable get() = localized(Res.string.machine_recent_log)
    val SHOW_LOG: String @Composable get() = localized(Res.string.machine_show_log)
    val STOP: String @Composable get() = localized(Res.string.machine_stop_hosting)
    /** "Run by your system’s service." and what that means for the switches, as one sentence pair. */
    val SERVICE: String @Composable get() = localized(Res.string.machine_service)
    val MANAGE: String @Composable get() = localized(Res.string.machine_manage)
    val MANAGE_TITLE: String @Composable get() = localized(Res.string.machine_manage_title)
    val MANAGE_TEXT: String @Composable get() = localized(Res.string.machine_manage_text)
    val MANAGE_GO: String @Composable get() = localized(Res.string.machine_manage_go)
    val GIT_MISSING: String @Composable get() = localized(Res.string.machine_git_missing)
    val INSTALL_GIT: String @Composable get() = localized(Res.string.machine_install_git)
    val INSTALLING: String @Composable get() = localized(Res.string.machine_installing)
    val INSTALL_FAILED: String @Composable get() = localized(Res.string.machine_install_failed)
    val TRY_AGAIN: String @Composable get() = localized(Res.string.machine_try_again)
    val TURN_ON_TEXT: String @Composable get() = localized(Res.string.machine_turn_on_text)
    val TURN_ON_BACKGROUND_HELP: String @Composable get() = localized(Res.string.machine_turn_on_background_help)
    val TURN_ON_AWAKE_HELP: String @Composable get() = localized(Res.string.machine_turn_on_awake_help)
    val TURN_ON_RELAY_HELP: String @Composable get() = localized(Res.string.machine_turn_on_relay_help)
    val START_HOSTING: String @Composable get() = localized(Res.string.machine_start_hosting)
    val CANCEL: String @Composable get() = localized(Res.string.machine_cancel)
    val LOG_TITLE: String @Composable get() = localized(Res.string.machine_log_title)
    val LOG_TEXT: String @Composable get() = localized(Res.string.machine_log_text)
    val COPY_ALL: String @Composable get() = localized(Res.string.machine_copy_all)
    val COPIED: String @Composable get() = localized(Res.string.machine_copied)
    val COPY: String @Composable get() = localized(Res.string.machine_copy)
    val DONE: String @Composable get() = localized(Res.string.machine_done)
    val OK: String @Composable get() = localized(Res.string.machine_ok)
    val WHATS_NEW: String @Composable get() = localized(Res.string.machine_whats_new)

    @Composable fun scope(local: Boolean) = if (local) THIS_COMPUTER else PAIRED_SERVER
    @Composable fun managedOn(name: String) = localized(Res.string.machine_managed_on, name)
    @Composable fun cantRestart(name: String) = localized(Res.string.machine_cant_restart, name)
    @Composable fun restartFailed(name: String) = localized(Res.string.machine_restart_failed, name)
    @Composable fun elsewhere(name: String) = localized(Res.string.machine_elsewhere, name)
    @Composable fun updateTitle(name: String) = localized(Res.string.machine_update_title, name)
    @Composable fun upToDate(version: String) =
        if (version.isBlank()) localized(Res.string.machine_up_to_date) else localized(Res.string.machine_up_to_date_version, version)
    @Composable fun ready(latest: String?, current: String) = listOfNotNull(
        latest?.let { localized(Res.string.machine_update_ready_version, it) } ?: localized(Res.string.machine_update_ready),
        current.takeIf { it.isNotBlank() }?.let { localized(Res.string.machine_update_you_have, it) },
    ).joinToString(" · ")
    @Composable fun installed(latest: String?) =
        latest?.let { localized(Res.string.machine_update_installed_version, it) } ?: localized(Res.string.machine_update_installed)
    @Composable fun updateFailed(latest: String?, name: String, why: String?): String {
        val reason = why?.trimEnd('.') ?: localized(Res.string.machine_update_no_reason, name)
        return if (latest != null) localized(Res.string.machine_update_failed_to, latest, reason)
        else localized(Res.string.machine_update_failed, reason)
    }
    @Composable fun didntStart(reason: String?) =
        if (reason.isNullOrBlank()) localized(Res.string.machine_didnt_start_here) else localized(Res.string.machine_didnt_start_reason, reason)
    @Composable fun chats(n: Int) = localizedPlural(Res.plurals.machine_chats, n, n)
    @Composable fun running(version: String?, chats: MachineChats) = listOfNotNull(
        version?.takeIf { it.isNotBlank() }?.let { "supermux $it" }, // i18n-ok: the brand and its version
        localized(Res.string.machine_running_counts, chats(chats.total), chats.working.size),
    ).joinToString(" · ")
    @Composable fun offline(lastSeen: String) = if (lastSeen.isBlank()) "" else localized(Res.string.machine_last_seen, lastSeen)

    /** "“a”, “b” and “c”", in the app's language. */
    @Composable fun namesText(names: List<String>): String {
        val q = names.map { "“$it”" }
        return if (q.size > 1) localized(Res.string.machine_names_and, q.dropLast(1).joinToString(", "), q.last())
        else q.firstOrNull().orEmpty()
    }

    /** The Stop hosting sheet's two lines, in the app's language. */
    @Composable fun stopLines(chats: MachineChats): Pair<String, String> {
        val w = chats.working
        val first = when (w.size) {
            0 -> localized(Res.string.machine_stop_none)
            1 -> localized(Res.string.machine_stop_one, namesText(w))
            else -> localized(Res.string.machine_stop_many, w.size, namesText(w))
        }
        val others = (chats.total - w.size).coerceAtLeast(0)
        val wait = when {
            others == 0 -> ""
            w.isEmpty() -> localized(Res.string.machine_stop_wait_yours, chats(others))
            else -> localized(Res.string.machine_stop_wait_others, chats(others))
        }
        val kept = localized(Res.string.machine_stop_kept)
        return first to if (wait.isEmpty()) kept else "$wait $kept"
    }

    /** "“a”, “b” and “c”" in English, for [stopText]. */
    fun names(names: List<String>): String {
        val q = names.map { "“$it”" }
        return if (q.size > 1) q.dropLast(1).joinToString(", ") + " and " + q.last() else q.firstOrNull().orEmpty() // i18n-ok: English twin for the tests
    }

    /** The Stop hosting sheet's two lines in English: the tests' copy of [stopLines]. */
    fun stopText(chats: MachineChats): Pair<String, String> {
        val w = chats.working
        val first = when (w.size) {
            0 -> "This stops supermux on this computer. No chat is working right now." // i18n-ok: English twin for the tests
            1 -> "This stops supermux and the chat working right now: ${names(w)}. It stops mid-reply." // i18n-ok: English twin for the tests
            else -> "This stops supermux and the ${w.size} chats working right now: ${names(w)}. They stop mid-reply." // i18n-ok: English twin for the tests
        }
        val others = (chats.total - w.size).coerceAtLeast(0)
        val n = if (others == 1) "1 chat" else "$others chats" // i18n-ok: English twin for the tests
        val wait = when {
            others == 0 -> ""
            w.isEmpty() -> "Your $n wait until you host again. " // i18n-ok: English twin for the tests
            else -> "The other $n wait until you host again. " // i18n-ok: English twin for the tests
        }
        return first to "${wait}Nothing is deleted, and supermux no longer starts with this computer." // i18n-ok: English twin for the tests
    }
}

/**
 * The one line a machine's parts show instead of their rows while supermux isn't there to ask: this computer not
 * hosting (or still starting, or failed), another machine offline; null while it is running.
 */
@Composable
internal fun machineQuiet(hosting: ThisComputerHosting?, up: Boolean, name: String, lastSeen: String): String? = when {
    hosting != null -> when (hosting.state.phase) {
        HostingPhase.Off -> MachinePartsCopy.NOT_HOSTING
        HostingPhase.Starting -> MachinePartsCopy.STARTING
        HostingPhase.Failed -> MachinePartsCopy.NOT_RUNNING
        else -> null
    }
    !up -> MachinePartsCopy.offline(name, lastSeen)
    else -> null
}

/**
 * The machine page of the machine Settings is about.
 *
 * @param system that machine's update status, update and restart calls.
 * @param chats its chats, for the status line and the Stop hosting sheet.
 * @param hosting this computer's hosting when the page is this computer's own, on desktop; null elsewhere, where
 *   While you're away is one line.
 * @param devices, links, worktrees, usage that machine's paired devices, shared links, chat worktrees and limits:
 *   today's actions holders. [usage] null leaves Limits with its head only.
 * @param onOpenUsage "Open full usage": the Usage screen.
 * @param onOpenWorktrees the Disk row: the machine's Worktrees page.
 * @param pairTimings how long a pairing code lives and how often its sheet looks for the device that used it.
 */
@Composable
fun MachinePage(
    system: SystemSettingsActions,
    modifier: Modifier = Modifier,
    chats: MachineChats = MachineChats(),
    hosting: ThisComputerHosting? = null,
    devices: DevicesSettingsActions = DevicesSettingsActions(),
    links: ProxiesSettingsActions = ProxiesSettingsActions(),
    worktrees: WorktreesSettingsActions = WorktreesSettingsActions(),
    usage: UsageActions? = null,
    onOpenUsage: () -> Unit = {},
    onOpenWorktrees: () -> Unit = {},
    anchor: String? = null,
    onAnchorShown: () -> Unit = {},
    pollDelayMs: Long = 1_500L,
    pollAttempts: Int = 120,
    pairTimings: PairTimings = PairTimings(),
) {
    val machine = LocalSettingsMachines.current?.current
    val local = hosting != null || machine?.local == true
    val name = if (hosting != null) machine?.name?.takeIf { machine.local } ?: MachineCopy.THIS_COMPUTER
    else machine?.name ?: localized(Res.string.machine_fallback_name)
    val glyph = if (local) KitGlyphs.Laptop else machine?.glyph() ?: KitGlyphs.Server
    val phone = kitMetrics().phone
    val scope = rememberCoroutineScope()

    // Is supermux there to talk to? This computer's page knows from its supervisor; any other from the connection.
    val phase = hosting?.state?.phase
    val up = if (hosting != null) phase == HostingPhase.Running else machine?.online ?: true

    var status by remember { mutableStateOf<UpdateStatus?>(null) }
    var checking by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    var checkFailed by remember { mutableStateOf(false) }
    var updating by remember { mutableStateOf(false) }
    var runError by remember { mutableStateOf<String?>(null) }
    var restarting by remember { mutableStateOf(false) }
    var cantRestart by remember { mutableStateOf(false) }
    var restartFailed by remember { mutableStateOf(false) }

    LaunchedEffect(up) { if (up) system.updateStatus()?.let { status = it } }
    // Resolved here: runUpdate's coroutine can't read resources.
    val noAnswer = localized(Res.string.machine_update_no_answer, name)
    val couldntStart = localized(Res.string.machine_update_couldnt_start)

    /** Waits for supermux to go down and come back (or to keep answering, for a restart quicker than a poll). */
    suspend fun awaitBack() {
        var sawDown = false
        var upInARow = 0
        repeat(pollAttempts) {
            delay(pollDelayMs)
            val s = system.updateStatus()
            if (s == null) {
                sawDown = true
                upInARow = 0
            } else {
                status = s
                upInARow++
                if (sawDown || upInARow >= 3) return
            }
        }
    }

    fun restart() {
        if (restarting) return
        cantRestart = false
        restartFailed = false
        restarting = true
        scope.launch {
            try {
                if (hosting != null && phase == HostingPhase.Running && !hosting.state.readOnly) {
                    hosting.restart()
                } else {
                    when (system.restart()) {
                        BrokerRestart.Accepted -> awaitBack()
                        BrokerRestart.CantRestartItself -> cantRestart = true
                        BrokerRestart.Failed -> restartFailed = true
                    }
                }
                system.updateStatus()?.let { status = it }
            } finally {
                restarting = false
            }
        }
    }

    fun runUpdate() {
        if (updating) return
        runError = null
        updating = true
        scope.launch {
            try {
                val r = system.runUpdate()
                when {
                    r == null -> runError = noAnswer
                    r.started -> {
                        var sawRunning = false
                        for (i in 0 until pollAttempts) {
                            delay(pollDelayMs)
                            val fresh = system.updateStatus() ?: continue
                            status = fresh
                            if (isRunningState(fresh.state)) {
                                sawRunning = true
                                continue
                            }
                            // Accepted but still idle has not started yet: only an outcome, or a run seen to end, settles it.
                            if (fresh.state != "idle" || sawRunning) break
                        }
                    }
                    else -> runError = r.instruction ?: r.error ?: couldntStart
                }
            } finally {
                updating = false
            }
        }
    }

    KitPage(
        name,
        modifier,
        scope = {
            ScopeLine(MachineCopy.scope(local), if (phone) glyph else null)
        },
        anchor = anchor,
        onAnchorShown = onAnchorShown,
        tag = "machine_page",
        titleGlyph = glyph,
    ) {
        // ── Header ──
        val down = restarting || (hosting != null && phase == HostingPhase.Restarting)
        val (dot, lead, rest) = when {
            hosting != null && phase == HostingPhase.Off -> Triple(KitDot.Off, MachineCopy.NOT_HOSTING, MachineCopy.NOT_RUNNING_HERE)
            hosting != null && phase == HostingPhase.Starting && !restarting -> Triple(KitDot.Warn, MachineCopy.STARTING_STATUS, "")
            hosting != null && phase == HostingPhase.Failed -> Triple(KitDot.Danger, MachineCopy.DIDNT_START, "")
            down -> Triple(KitDot.Warn, MachineCopy.RESTARTING_STATUS, MachineCopy.RECONNECT)
            !up -> Triple(KitDot.Off, localized(Res.string.machine_offline), MachineCopy.offline(machine?.lastSeen.orEmpty()))
            else -> Triple(KitDot.Ok, localized(Res.string.machine_running), MachineCopy.running(status?.current, chats))
        }
        Column(Modifier.kitAnchor("updates")) {
        HeaderStatus(dot, lead, rest, Modifier.kitAnchor("status").testTag("machine_status"))

        if (up || down) {
            val s = status
            val managed = s?.mode == "managed"
            FlowRow(
                Modifier.padding(top = 12.dp).kitAnchor("restart"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                KitGhostButton(
                    if (down) MachineCopy.RESTARTING else MachineCopy.RESTART,
                    ::restart,
                    Modifier.testTag("machine_restart"),
                    enabled = !down,
                )
                when {
                    managed -> Words(if (phone) MachineCopy.managedOn(name) else MachineCopy.MANAGED, "machine_updates_words")
                    s?.disabled == true -> Unit
                    checking -> KitGhostButton(MachineCopy.CHECKING, {}, Modifier.testTag("machine_check_updates"), enabled = false)
                    checked && s != null && !s.updateAvailable && s.state != "restart-required" ->
                        Words(MachineCopy.upToDate(s.current), "machine_updates_words")
                    else -> KitGhostButton(
                        MachineCopy.CHECK,
                        {
                            checking = true
                            checkFailed = false
                            scope.launch {
                                val fresh = system.checkUpdate()
                                if (fresh != null) status = fresh else checkFailed = true
                                checked = fresh != null
                                checking = false
                            }
                        },
                        Modifier.testTag("machine_check_updates"),
                    )
                }
            }
            if (checkFailed) Words(localized(Res.string.machine_check_failed, name), "machine_check_failed", Modifier.padding(top = 8.dp))
            if (cantRestart) {
                MachineLine(KitGlyphs.Warn, MachineCopy.cantRestart(name), Tone.Warn, Modifier.testTag("machine_cant_restart")) {
                    KitTextAction(MachineCopy.OK, { cantRestart = false }, Modifier.testTag("machine_cant_restart_ok"))
                }
            }
            if (restartFailed) {
                MachineLine(KitGlyphs.Warn, MachineCopy.restartFailed(name), Tone.Warn, Modifier.testTag("machine_restart_failed")) {
                    KitTextAction(MachineCopy.OK, { restartFailed = false })
                }
            }
            if (s != null && !managed) UpdateRow(s, name, updating, runError, ::runUpdate, ::restart, down)
        }
        }

        // ── While you're away ──
        KitSectionHead(MachineCopy.AWAY, Modifier.kitAnchor("away"))
        if (hosting == null) {
            KitNote(MachineCopy.elsewhere(name), Modifier.testTag("machine_away_note"))
        } else {
            Column(Modifier.kitAnchor("host-on")) { Away(hosting, chats) }
        }

        // ── The machine's own parts: one quiet line each while supermux isn't there to ask ──
        val quiet = machineQuiet(hosting, up, name, machine?.lastSeen.orEmpty())
        // Reaching it from anywhere is off on this computer: a pairing link must use its address on this network.
        val pairOrigin = hosting?.state?.takeIf { phase == HostingPhase.Running && !it.relay }?.localUrl?.takeIf { it.isNotBlank() }
        DevicesSection(devices, name, quiet, pairOrigin, pairTimings)
        LinksSection(links, name, quiet, chats.working)
        DiskSection(worktrees, name, quiet, onOpenWorktrees)
        LimitsSection(usage, quiet, onOpenUsage)
    }
}

/** "Update supermux on ‹machine›": only once there is something to say (ready, updating, restart to finish, failed). */
@Composable
private fun UpdateRow(
    s: UpdateStatus,
    name: String,
    updating: Boolean,
    runError: String?,
    onUpdate: () -> Unit,
    onRestart: () -> Unit,
    down: Boolean,
) {
    val c = kitColors()
    val platform = LocalPlatform.current
    val running = updating || isRunningState(s.state)
    val failed = s.state == "failed" || runError != null
    val finish = s.state == "restart-required"
    if (!(running || failed || finish || s.updateAvailable)) return
    val desc: String
    var danger = false
    when {
        running -> desc = when (s.state) {
            "downloading" -> s.latest?.let { localized(Res.string.machine_update_downloading_version, it) }
                ?: localized(Res.string.machine_update_downloading)
            "swapping" -> s.latest?.let { localized(Res.string.machine_update_installing_version, it) }
                ?: localized(Res.string.machine_update_installing)
            "checking" -> localized(Res.string.machine_checking)
            else -> localized(Res.string.machine_update_starting)
        }
        finish -> desc = MachineCopy.installed(s.latest)
        failed -> {
            desc = MachineCopy.updateFailed(s.latest, name, runError ?: s.lastError)
            danger = true
        }
        else -> desc = MachineCopy.ready(s.latest, s.current)
    }
    Column(Modifier.padding(top = 12.dp).fillMaxWidth().kitAnchor("update").testTag("machine_update_row")) {
        dev.supermux.ui.settings.kit.KitHairline()
        Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(MachineCopy.updateTitle(name), fontSize = kitMetrics().label, color = c.text)
                Text(
                    desc,
                    fontSize = 12.5.sp,
                    lineHeight = 19.sp,
                    color = if (danger) c.danger else c.dim,
                    modifier = Modifier.padding(top = 2.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("machine_update_desc"),
                )
                val notes = s.notesUrl
                if (notes != null && !running && !failed && !finish) {
                    KitTextAction(MachineCopy.WHATS_NEW, { platform.openUrl(notes) }, Modifier.testTag("machine_whats_new"))
                }
            }
            when {
                running -> KitGhostButton(localized(Res.string.machine_updating), {}, Modifier.testTag("machine_update_button"), enabled = false)
                finish -> KitGhostButton(MachineCopy.RESTART, onRestart, Modifier.testTag("machine_update_button"), enabled = !down)
                failed -> KitGhostButton(MachineCopy.TRY_AGAIN, onUpdate, Modifier.testTag("machine_update_button"))
                else -> KitGhostButton(localized(Res.string.machine_update), onUpdate, Modifier.testTag("machine_update_button"))
            }
        }
        dev.supermux.ui.settings.kit.KitHairline()
    }
}

/** While you're away on this computer's own page: today's hosting, in kit rows. */
@Composable
private fun ColumnScope.Away(h: ThisComputerHosting, chats: MachineChats) {
    val st = h.state
    val a = h.actions
    val platform = LocalPlatform.current
    var turnOn by rememberSaveable { mutableStateOf(false) }
    var stop by rememberSaveable { mutableStateOf(false) }
    var showLog by rememberSaveable { mutableStateOf(false) }
    var manage by rememberSaveable { mutableStateOf(false) }

    when (st.phase) {
        HostingPhase.Off -> KitRow(
            MachineCopy.HOST_ON,
            Modifier,
            description = MachineCopy.HOST_ON_HELP,
        ) { KitGhostButton(MachineCopy.HOST_ON, { turnOn = true }, Modifier.testTag("machine_host_on")) }
        HostingPhase.Starting, HostingPhase.Restarting -> {
            MachineLine(KitGlyphs.Restart, MachineCopy.STARTING_LINE, Tone.Dim, Modifier.testTag("machine_starting"))
            if (h.recentLog.isNotEmpty()) LogLines(h.recentLog.takeLast(3))
        }
        HostingPhase.Failed -> {
            MachineLine(KitGlyphs.Warn, MachineCopy.didntStart(st.failReason), Tone.Danger, Modifier.testTag("machine_failed"))
            val tail = st.logTail.ifEmpty { h.recentLog }
            if (tail.isNotEmpty()) LogLines(tail.takeLast(3))
            Buttons {
                KitGhostButton(MachineCopy.TRY_AGAIN, a::retry, Modifier.testTag("machine_retry"))
                KitGhostButton(MachineCopy.SHOW_LOG, { showLog = true }, Modifier.testTag("machine_show_log"))
            }
        }
        HostingPhase.Running -> {
            val ro = st.readOnly
            if (ro) {
                MachineLine(KitGlyphs.Server, MachineCopy.SERVICE, Tone.Dim, Modifier.kitAnchor("service").testTag("machine_service")) {
                    KitGhostButton(MachineCopy.MANAGE, { manage = true }, Modifier.testTag("machine_manage"))
                }
            }
            KitToggleRow(
                MachineCopy.BACKGROUND, st.background, a::setBackground,
                Modifier.kitAnchor("keep-running").testTag("machine_keep_running"),
                description = MachineCopy.BACKGROUND_HELP, enabled = !ro,
            )
            st.backgroundError?.let { Line(it, Tone.Danger, "machine_background_error") }
            st.power?.keepAwake?.let { ka ->
                KitToggleRow(
                    MachineCopy.AWAKE, ka.enabled, a::setKeepAwake,
                    Modifier.kitAnchor("keep-awake").testTag("machine_keep_awake"),
                    description = MachineCopy.AWAKE_HELP, enabled = ka.switchEnabled && !ro,
                )
                ka.warning?.let { Line(it, Tone.Warn, "machine_keep_awake_warning") }
                ka.note?.let { Line(it, Tone.Dim, "machine_keep_awake_note") }
                ka.error?.let { Line(it, Tone.Danger, "machine_keep_awake_error") }
                if (ka.enabled && ka.showOnBattery) {
                    KitToggleRow(
                        MachineCopy.BATTERY, ka.onBattery, a::setKeepAwakeOnBattery,
                        Modifier.testTag("machine_battery"), indent = true, enabled = ka.switchEnabled && !ro,
                    )
                }
            }
            st.power?.lidClosed?.let { lid ->
                KitToggleRow(
                    MachineCopy.LID, lid.on, a::setLidClosed, Modifier.testTag("machine_lid"),
                    description = MachineCopy.LID_HELP, indent = true, enabled = lid.enabled && !lid.busy && !ro,
                )
                (if (lid.busy) localized(Res.string.machine_hosting_lid_waiting) else lid.note)?.let { Line(it, Tone.Dim, "machine_lid_note") }
                lid.error?.let { Line(it, Tone.Danger, "machine_lid_error") }
                if (lid.installed) {
                    KitTextAction(localized(Res.string.machine_hosting_lid_uninstall), a::uninstallLidHelper, Modifier.testTag("machine_lid_uninstall"), enabled = !lid.busy)
                }
            }
            if (st.power?.autoLoginHint == true) KitNote(localized(Res.string.machine_hosting_auto_login), Modifier.testTag("machine_auto_login_hint"))
            KitToggleRow(
                MachineCopy.RELAY, st.relay, a::setRelay,
                Modifier.kitAnchor("reach-anywhere").testTag("machine_relay"),
                description = MachineCopy.RELAY_HELP, enabled = !ro,
            )
            st.checkoutMoved?.let { Line(it, Tone.Warn, "machine_checkout_moved") }
            AddressRow(MachineCopy.ON_NETWORK, st.localUrl, "lan", Modifier.kitAnchor("address"))
            AddressRow(MachineCopy.FROM_ANYWHERE, st.relayUrl.takeIf { st.relay || ro }, "relay", Modifier)
            if (st.gitMissing) GitLine(st, a)
            // Recent log
            Column(Modifier.fillMaxWidth().kitAnchor("log").padding(vertical = 11.dp).testTag("machine_log")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(MachineCopy.RECENT_LOG, fontSize = kitMetrics().label, color = kitColors().text, modifier = Modifier.weight(1f))
                    KitTextAction(MachineCopy.SHOW_LOG, { showLog = true }, Modifier.testTag("machine_show_log"))
                }
                if (h.recentLog.isNotEmpty()) LogLines(h.recentLog.takeLast(3))
            }
            dev.supermux.ui.settings.kit.KitHairline()
            if (!ro) {
                Buttons { KitGhostButton(MachineCopy.STOP, { stop = true }, Modifier.testTag("machine_stop_hosting"), destructive = true) }
            }
        }
    }
    h.turnOnError?.let { why ->
        MachineLine(KitGlyphs.Warn, why, Tone.Danger, Modifier.testTag("machine_turn_on_error")) {
            KitGhostButton(MachineCopy.TRY_AGAIN, h.retryTurnOn, Modifier.testTag("machine_turn_on_retry"))
        }
    }

    if (turnOn) TurnOnSheet(onDismiss = { turnOn = false }) { bg, awake, relay ->
        turnOn = false
        h.startHosting(bg, awake, relay)
    }
    if (stop) {
        val (first, second) = MachineCopy.stopLines(chats)
        KitSheet(
            localized(Res.string.machine_stop_title),
            onDismiss = { stop = false },
            tag = "machine_stop_sheet",
            buttons = {
                KitGhostButton(MachineCopy.CANCEL, { stop = false }, Modifier.testTag("machine_stop_cancel"))
                KitGhostButton(MachineCopy.STOP, {
                    stop = false
                    a.setHosting(false)
                }, Modifier.testTag("machine_stop_confirm"), destructive = true)
            },
        ) {
            SheetText(first, Modifier.testTag("machine_stop_text"))
            SheetText(second, Modifier.testTag("machine_stop_text_2"))
        }
    }
    if (manage) {
        KitSheet(
            MachineCopy.MANAGE_TITLE,
            onDismiss = { manage = false },
            description = MachineCopy.MANAGE_TEXT,
            tag = "machine_manage_sheet",
            buttons = {
                KitGhostButton(MachineCopy.CANCEL, { manage = false })
                KitGhostButton(MachineCopy.MANAGE_GO, {
                    manage = false
                    a.manageIt()
                }, Modifier.testTag("machine_manage_confirm"))
            },
        )
    }
    if (showLog) {
        var lines by remember { mutableStateOf<List<String>?>(null) }
        var copied by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { lines = h.readLog() }
        KitSheet(
            MachineCopy.LOG_TITLE,
            onDismiss = { showLog = false },
            description = MachineCopy.LOG_TEXT,
            tag = "machine_log_sheet",
            buttons = {
                lines?.let { Words(localizedPlural(Res.plurals.machine_log_lines, it.size, it.size), "machine_log_count") }
                KitGhostButton(if (copied) MachineCopy.COPIED else MachineCopy.COPY_ALL, {
                    platform.copyToClipboard(lines.orEmpty().joinToString("\n"))
                    copied = true
                }, Modifier.testTag("machine_log_copy"), enabled = !copied && !lines.isNullOrEmpty())
                KitGhostButton(MachineCopy.DONE, { showLog = false }, Modifier.testTag("machine_log_done"))
            },
        ) {
            Text(
                lines?.joinToString("\n") ?: "…",
                fontFamily = MonoFontFamily,
                fontSize = 11.5.sp,
                lineHeight = 18.sp,
                color = kitColors().dim,
                modifier = Modifier.padding(top = 12.dp).testTag("machine_log_lines"),
            )
        }
    }
}

/** The prototype's "Host on this computer" sheet: three switches, then Start hosting. */
@Composable
private fun TurnOnSheet(onDismiss: () -> Unit, onStart: (Boolean, Boolean, Boolean) -> Unit) {
    var bg by rememberSaveable { mutableStateOf(true) }
    var awake by rememberSaveable { mutableStateOf(true) }
    var relay by rememberSaveable { mutableStateOf(true) }
    KitSheet(
        MachineCopy.HOST_ON,
        onDismiss = onDismiss,
        description = MachineCopy.TURN_ON_TEXT,
        tag = "machine_turn_on_sheet",
        buttons = {
            KitGhostButton(MachineCopy.CANCEL, onDismiss, Modifier.testTag("machine_turn_on_cancel"))
            KitGhostButton(MachineCopy.START_HOSTING, { onStart(bg, awake, relay) }, Modifier.testTag("machine_turn_on_start"))
        },
    ) {
        Column(Modifier.padding(top = 8.dp)) {
            KitToggleRow(MachineCopy.BACKGROUND, bg, { bg = it }, Modifier.testTag("machine_turn_on_background"), description = MachineCopy.TURN_ON_BACKGROUND_HELP)
            KitToggleRow(MachineCopy.AWAKE, awake, { awake = it }, Modifier.testTag("machine_turn_on_awake"), description = MachineCopy.TURN_ON_AWAKE_HELP)
            KitToggleRow(MachineCopy.RELAY, relay, { relay = it }, Modifier.testTag("machine_turn_on_relay"), description = MachineCopy.TURN_ON_RELAY_HELP, divider = false)
        }
    }
}

/** git missing: the line, and Install git where this computer has a one-click install. */
@Composable
private fun GitLine(st: HostingUiState, a: HostingActions) {
    val req = st.gitRequirement ?: return
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val missing = MachineCopy.GIT_MISSING
    val installError = req.installError?.let { localized(Res.string.machine_git_install_error, it) }
    val installFailed = MachineCopy.INSTALL_FAILED
    val text = buildString {
        append(missing)
        installError?.let { append(" ").append(it) }
        if (failed) append(" ").append(installFailed)
    }
    MachineLine(KitGlyphs.Warn, text, Tone.Warn, Modifier.kitAnchor("git").testTag("machine_git")) {
        if (req.installable) {
            val installing = busy || req.installing
            KitGhostButton(
                if (installing) MachineCopy.INSTALLING else MachineCopy.INSTALL_GIT,
                {
                    busy = true
                    failed = false
                    scope.launch {
                        failed = !a.installGit()
                        busy = false
                    }
                },
                Modifier.testTag("machine_install_git"),
                enabled = !installing,
            )
        }
    }
    if (!req.installable && req.hint.isNotBlank()) KitNote(req.hint, Modifier.testTag("machine_git_hint"))
}

@Composable
private fun AddressRow(label: String, url: String?, key: String, modifier: Modifier) {
    val platform = LocalPlatform.current
    var copied by remember(url) { mutableStateOf(false) }
    KitRow(label, modifier.testTag("machine_address_$key"), descriptionTag = "machine_address_${key}_text", description = url ?: MachineCopy.OFF) {
        if (url != null) {
            KitGhostButton(
                if (copied) MachineCopy.COPIED else MachineCopy.COPY,
                {
                    platform.copyToClipboard(url)
                    copied = true
                },
                Modifier.testTag("machine_copy_$key"),
                leading = if (copied) KitGlyphs.Check else KitGlyphs.Copy,
            )
        }
    }
}

private enum class Tone { Dim, Warn, Danger }

@Composable
private fun toneColor(t: Tone): Color {
    val c = kitColors()
    return when (t) {
        Tone.Dim -> c.dim
        Tone.Warn -> c.warn
        Tone.Danger -> c.danger
    }
}

/** The prototype's `.line`: a glyph, words, and maybe one button at the end. */
@Composable
private fun MachineLine(
    glyph: ImageVector,
    text: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val color = toneColor(tone)
    Row(
        modifier.fillMaxWidth().padding(top = 10.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(glyph, contentDescription = null, tint = color, modifier = Modifier.padding(top = 2.dp).size(15.dp))
        Text(
            text,
            fontSize = 12.5.sp,
            lineHeight = 19.sp,
            color = color,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        trailing?.invoke()
    }
}

@Composable
private fun Line(text: String, tone: Tone, tag: String) {
    Text(text, fontSize = 12.sp, lineHeight = 18.sp, color = toneColor(tone), modifier = Modifier.padding(bottom = 6.dp).testTag(tag))
}

@Composable
private fun SheetText(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 12.5.sp, lineHeight = 19.sp, color = kitColors().dim, modifier = modifier.padding(top = 6.dp))
}

@Composable
private fun Words(text: String, tag: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 12.5.sp, color = kitColors().dim, modifier = modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** The log's last lines: mono, behind a hairline on the left. */
@Composable
private fun LogLines(lines: List<String>) {
    val c = kitColors()
    Row(Modifier.padding(top = 8.dp).fillMaxWidth()) {
        Box(Modifier.width(1.dp).heightIn(min = 18.dp * lines.size).background(c.line))
        Text(
            lines.joinToString("\n"),
            fontFamily = MonoFontFamily,
            fontSize = 11.5.sp,
            lineHeight = 18.sp,
            color = c.dim,
            maxLines = lines.size,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 10.dp).testTag("machine_log_tail"),
        )
    }
}

/** "● Running · supermux 0.17.5 · 14 chats, 3 working": the lead in the text tone, the rest dim. */
@Composable
private fun HeaderStatus(dot: KitDot, lead: String, rest: String, modifier: Modifier) {
    val c = kitColors()
    Row(
        modifier.padding(top = 14.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KitDotMark(dot)
        Text(
            buildAnnotatedString {
                append(lead)
                if (rest.isNotBlank()) withStyle(SpanStyle(color = c.dim)) { append(" · $rest") }
            },
            fontSize = 13.sp,
            color = c.text,
        )
    }
}

@Composable
private fun ScopeLine(text: String, glyph: ImageVector?) {
    val c = kitColors()
    Row(
        Modifier.padding(top = 6.dp).semantics(mergeDescendants = true) {}.testTag("machine_scope"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (glyph != null) Icon(glyph, contentDescription = null, tint = c.dim, modifier = Modifier.size(14.dp))
        Text(text, fontSize = 12.5.sp, color = c.dim)
    }
}
