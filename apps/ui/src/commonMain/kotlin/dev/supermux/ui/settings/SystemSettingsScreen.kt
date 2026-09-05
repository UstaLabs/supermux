// The one broker System/maintenance settings screen for both apps (cluster E3).
//
// Base = desktop's `settings/SystemSettingsScreen.kt`: the UPDATES "Recheck" (force-poll through
// `checkUpdate`), the load-error/Retry and action-error states, the bounded update poll loop with
// its honest timeout messages, the single progress row, a restart that reports failure, and every
// test tag. Android's page was the same screen minus Recheck, minus the error states, with a
// fire-and-forget restart on a blind four-second spinner and `R.drawable` icons — it contributes
// the Compact branch (its own `TopAppBar` when the hub did not paint one, and the chevron on the
// release-notes link a thumb needs to see the row is tappable).
//
// This is the ACTIVE HOST'S BROKER, never the app itself: desktop's own updater is File ▸ "Check
// for Updates…" (`AppUpdate`, cluster G).
package dev.supermux.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import dev.supermux.net.RunUpdateResult
import dev.supermux.net.UpdateStatus
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Space
import dev.supermux.ui.widgets.AlertDialog
import dev.supermux.ui.widgets.SettingsCaption
import dev.supermux.ui.widgets.SettingsDetailMaxWidth
import dev.supermux.ui.widgets.SettingsSectionHeader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Every broker call the System screen makes, in one holder.
 *
 * `restartBroker` is suspend→Boolean (desktop's shape): a 5xx or an unreachable broker surfaces as
 * an error instead of a spinner that ends on a timer. Android's `FleetStore` wrapper was
 * fire-and-forget and is retyped in `:shared` rather than dumbing this down.
 */
@Immutable
class SystemSettingsActions(
    /** Cached broker update status; `null` = transport/decode failure. */
    val updateStatus: suspend () -> UpdateStatus? = { null },
    /** Force a fresh upstream check (the UPDATES "Recheck" button). */
    val checkUpdate: suspend () -> UpdateStatus? = { null },
    /** Start the broker's self-updater. */
    val runUpdate: suspend () -> RunUpdateResult? = { null },
    /** True when the restart POST was accepted. */
    val restartBroker: suspend () -> Boolean = { false },
)

/** [SystemSettingsActions] against one paired host — desktop's wiring. */
@Composable
fun rememberSystemSettingsActions(app: HostStore): SystemSettingsActions = remember(app) {
    SystemSettingsActions(
        updateStatus = { app.updateStatus() },
        checkUpdate = { app.checkUpdate() },
        runUpdate = { app.runUpdate() },
        restartBroker = { app.restartBroker() },
    )
}

/** [SystemSettingsActions] against the fleet's ACTIVE host — Android's wiring. */
@Composable
fun rememberSystemSettingsActions(fleet: FleetStore): SystemSettingsActions = remember(fleet) {
    SystemSettingsActions(
        updateStatus = { fleet.updateStatus() },
        checkUpdate = { fleet.checkUpdate() },
        runUpdate = { fleet.runUpdate() },
        restartBroker = { fleet.restartBroker() },
    )
}

/**
 * Broker system / maintenance: update status + self-update + restart.
 *
 * @param onBack leave the screen; only reachable from the Compact top bar this screen paints for
 *   itself (pass the hub's `SettingsSlotScope.onClose`).
 * @param topBarShown the hub already painted a `TopAppBar` for this detail.
 * @param updatePollAttempts max status polls after runUpdate starts (production: 120 × 1.5s ≈ 3
 *   min). Tests shorten it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemSettingsScreen(
    actions: SystemSettingsActions,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    topBarShown: Boolean = false,
    updatePollAttempts: Int = 120,
    updatePollDelayMs: Long = 1500L,
) {
    val cs = MaterialTheme.colorScheme
    val compact = LocalWindowWidthClass.current == WindowWidthClass.Compact
    if (compact && !topBarShown) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("System", color = cs.onSurface) },
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("system_settings_back"),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = cs.onSurface,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = cs.surfaceContainerHigh,
                    ),
                )
            },
            containerColor = cs.background,
        ) { padding ->
            SystemSettingsBody(
                actions, modifier.padding(padding), updatePollAttempts, updatePollDelayMs,
            )
        }
    } else {
        SystemSettingsBody(actions, modifier, updatePollAttempts, updatePollDelayMs)
    }
}

@Composable
private fun SystemSettingsBody(
    actions: SystemSettingsActions,
    modifier: Modifier = Modifier,
    updatePollAttempts: Int = 120,
    updatePollDelayMs: Long = 1500L,
) {
    val platform = LocalPlatform.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    // Touch hosts get the chevron Android's row had — a pointer keeps desktop's bare text link.
    val pointer = LocalPointerAvailable.current

    var status by remember { mutableStateOf<UpdateStatus?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    /** Action-level errors that keep prior status visible (failed Recheck, update 5xx, etc.). */
    var actionError by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var showRestartConfirm by remember { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }
    var runError by remember { mutableStateOf<String?>(null) }
    var restartError by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }

    suspend fun loadStatus(forceCheck: Boolean = false) {
        val s = if (forceCheck) actions.checkUpdate() else actions.updateStatus()
        if (s != null) {
            status = s
            loadError = null
            if (forceCheck) actionError = null
        } else if (status == null) {
            loadError = "Couldn't load update status."
        } else if (forceCheck) {
            // Prior status stays on screen; surface that Recheck failed so stale data is not blessed.
            actionError = "Couldn't recheck for updates."
        }
        loading = false
        checking = false
    }

    LaunchedEffect(Unit) { loadStatus(forceCheck = false) }

    Box(
        modifier
            .fillMaxSize()
            .testTag("system_settings_screen"),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (loading && status == null) {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = cs.primary,
                    modifier = Modifier.testTag("system_settings_loading"),
                )
            }
        } else {
            Column(
                Modifier
                    .widthIn(max = SettingsDetailMaxWidth)
                    .fillMaxWidth()
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(Space.lg)
                    .testTag("system_settings_body"),
                verticalArrangement = Arrangement.spacedBy(Space.lg),
                horizontalAlignment = Alignment.Start,
            ) {
                // Caption keeps broker-update distinct from File ▸ "Check for Updates…" (app).
                SettingsCaption(
                    "Broker on the active host — not this desktop app. " +
                        "Use File ▸ \"Check for Updates…\" to update supermux desktop.",
                    modifier = Modifier.testTag("system_broker_vs_app_caption"),
                )

                // ── Updates section ──
                SettingsSectionHeader(
                    title = "UPDATES",
                    trailing = {
                        TextButton(
                            onClick = {
                                if (checking || updating) return@TextButton
                                checking = true
                                actionError = null
                                scope.launch { loadStatus(forceCheck = true) }
                            },
                            enabled = !checking && !updating && !restarting,
                            modifier = Modifier.testTag("system_recheck"),
                        ) {
                            if (checking) {
                                CircularProgressIndicator(
                                    Modifier.size(Space.md),
                                    color = cs.primary,
                                    strokeWidth = Space.xs / 2,
                                )
                            } else {
                                Text("Recheck")
                            }
                        }
                    },
                )

                val s = status
                if (loadError != null && s == null) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(Space.md),
                        modifier = Modifier.testTag("system_settings_error"),
                    ) {
                        Text(
                            loadError!!,
                            color = cs.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        OutlinedButton(
                            onClick = {
                                loading = true
                                scope.launch { loadStatus(forceCheck = false) }
                            },
                            modifier = Modifier.testTag("system_settings_retry"),
                        ) { Text("Retry") }
                    }
                } else if (s != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                        Text(
                            "supermux ${s.current}",
                            color = cs.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.testTag("system_broker_version"),
                        )
                        if (s.commit.isNotEmpty()) {
                            Text(
                                s.commit.take(8),
                                color = cs.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = MonoFontFamily,
                                modifier = Modifier.testTag("system_broker_commit"),
                            )
                        }
                    }

                    UpdateAvailabilityRow(s)

                    // Single progress row: prefer live status when non-idle; otherwise a
                    // brief "checking" while the POST is in flight and status hasn't moved yet.
                    // Avoids the dual "Downloading…" rows when both `updating` and state are set.
                    when {
                        s.state != "idle" -> StateRow(s.state)
                        // "starting", not "checking": this row appears while an UPDATE POST is in
                        // flight and the broker status hasn't moved off idle yet. Labelling it
                        // "Checking…" described the wrong operation entirely.
                        updating -> StateRow("starting")
                    }

                    lastCheckedText(s.lastChecked)?.let {
                        Text(
                            it,
                            color = cs.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.testTag("system_last_checked"),
                        )
                    }

                    s.lastError?.let {
                        Text(
                            it,
                            color = cs.error,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.testTag("system_last_error"),
                        )
                    }

                    actionError?.let {
                        Text(
                            it,
                            color = cs.error,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.testTag("system_action_error"),
                        )
                    }

                    s.notesUrl?.let { notes ->
                        Row(
                            Modifier
                                .clickable { platform.openUrl(notes) }
                                .testTag("system_release_notes"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space.xs),
                        ) {
                            Text(
                                "Release notes",
                                color = cs.primary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (!pointer) {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = cs.primary,
                                    modifier = Modifier.size(Space.md + Space.xs),
                                )
                            }
                        }
                    }

                    // Update broker — binary self-updater only. Source/docker can't
                    // self-update; hide while an update is in flight and once staged.
                    if (s.mode == "binary" &&
                        (s.updateAvailable || s.state == "failed") &&
                        !isRunningState(s.state) &&
                        s.state != "restart-required" &&
                        !updating
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    runError = null
                                    actionError = null
                                    updating = true
                                    val result = actions.runUpdate()
                                    when {
                                        result == null -> {
                                            runError = "Couldn't reach the broker."
                                            updating = false
                                        }
                                        result.started -> {
                                            var settled = false
                                            var sawRunning = false
                                            for (i in 0 until updatePollAttempts) {
                                                delay(updatePollDelayMs)
                                                val fresh = actions.updateStatus() ?: continue
                                                status = fresh
                                                if (isRunningState(fresh.state)) {
                                                    sawRunning = true
                                                    continue
                                                }
                                                // A non-running state is only "settled" once it is
                                                // an OUTCOME (restart-required / failed) or we
                                                // watched a run finish. A broker that accepted the
                                                // request but still reports `idle` has NOT started
                                                // yet — treating that as settled rendered nothing
                                                // at all: no progress, no error, the screen
                                                // identical to before the click.
                                                if (fresh.state != "idle" || sawRunning) {
                                                    settled = true
                                                    break
                                                }
                                            }
                                            if (!settled) {
                                                runError = if (sawRunning) {
                                                    "Update is still running — check again later."
                                                } else {
                                                    "The broker accepted the update but hasn't " +
                                                        "started it yet — check again shortly."
                                                }
                                            }
                                            updating = false
                                        }
                                        else -> {
                                            // 500 {} and similar leave instruction/error null —
                                            // never silently clear the action as if nothing happened.
                                            runError = result.instruction
                                                ?: result.error
                                                ?: "Couldn't start the update."
                                            updating = false
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.testTag("system_update_broker"),
                        ) {
                            Text(if (s.state == "failed") "Retry update" else "Update broker")
                        }
                    }
                    runError?.let {
                        Text(
                            it,
                            color = cs.error,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.testTag("system_run_error"),
                        )
                    }
                } else {
                    Text(
                        "Status unavailable",
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("system_status_unavailable"),
                    )
                }

                HorizontalDivider(color = cs.outlineVariant)

                // ── Maintenance section ──
                SettingsSectionHeader("MAINTENANCE")
                SettingsCaption(
                    "Restart kills this app's connection to the broker. " +
                        "The app reconnects automatically when the broker is back.",
                    modifier = Modifier.testTag("system_restart_warning"),
                )
                Button(
                    onClick = { showRestartConfirm = true },
                    enabled = !restarting,
                    modifier = Modifier.testTag("system_restart_broker"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = cs.error,
                        contentColor = cs.onError,
                    ),
                ) {
                    if (restarting) {
                        CircularProgressIndicator(
                            color = cs.onError,
                            strokeWidth = Space.xs / 2,
                            modifier = Modifier.size(Space.lg),
                        )
                        Spacer(Modifier.width(Space.sm))
                        Text("Restarting…")
                    } else {
                        Text("Restart broker")
                    }
                }
                restartError?.let {
                    Text(
                        it,
                        color = cs.error,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.testTag("system_restart_error"),
                    )
                }
                SettingsCaption("Sessions will reconnect automatically.")
            }
        }
    }

    if (showRestartConfirm) {
        AlertDialog(
            onDismissRequest = { if (!restarting) showRestartConfirm = false },
            title = { Text("Restart the broker?") },
            text = {
                Text(
                    "This kills your connection to the broker on the active host. " +
                        "Sessions will reconnect automatically once it is back.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestartConfirm = false
                        restarting = true
                        restartError = null
                        scope.launch {
                            val ok = actions.restartBroker()
                            if (!ok) {
                                restartError = "Couldn't restart the broker."
                            }
                            restarting = false
                        }
                    },
                    modifier = Modifier.testTag("system_restart_confirm"),
                ) { Text("Restart", color = cs.error) }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRestartConfirm = false },
                    modifier = Modifier.testTag("system_restart_cancel"),
                ) { Text("Cancel") }
            },
            modifier = Modifier.testTag("system_restart_dialog"),
        )
    }
}

internal fun isRunningState(state: String): Boolean =
    // "starting" is the client-only pseudo-state (see stateLabel) — counted as running so the row
    // gets a spinner rather than bare text. The broker never sends it, so broker-state callers
    // (the Update-button gate, the poll loop) are unaffected.
    state == "starting" || state == "checking" || state == "downloading" || state == "swapping"

@Composable
private fun UpdateAvailabilityRow(s: UpdateStatus) {
    val cs = MaterialTheme.colorScheme
    when {
        s.disabled -> Text(
            "Update checks disabled.",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.testTag("system_update_disabled"),
        )
        s.updateAvailable -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            modifier = Modifier.testTag("system_update_available"),
        ) {
            Icon(
                Icons.Filled.Download,
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(Space.lg),
            )
            Text(
                "Update available" + (s.latest?.let { ": $it" } ?: ""),
                color = cs.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        else -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            modifier = Modifier.testTag("system_up_to_date"),
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(Space.lg),
            )
            Text(
                "Up to date",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun StateRow(state: String) {
    val cs = MaterialTheme.colorScheme
    val failed = state == "failed"
    val running = isRunningState(state)
    val restartRequired = state == "restart-required"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        modifier = Modifier.testTag("system_update_state"),
    ) {
        when {
            failed -> Icon(
                Icons.Filled.Close,
                contentDescription = null,
                tint = cs.error,
                modifier = Modifier
                    .size(Space.md)
                    .testTag("system_update_state_failed_icon"),
            )
            running -> CircularProgressIndicator(
                color = cs.primary,
                strokeWidth = Space.xs / 2,
                modifier = Modifier
                    .size(Space.md)
                    .testTag("system_update_state_spinner"),
            )
            restartRequired -> Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier
                    .size(Space.md)
                    .testTag("system_update_state_restart_icon"),
            )
            // other terminal labels: text only
        }
        Text(
            stateLabel(state),
            color = if (failed) cs.error else cs.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

internal fun stateLabel(state: String): String = when (state) {
    // Client-only pseudo-state: the update POST is in flight and the broker still reports idle.
    // Never sent by the broker (its states are checking/downloading/swapping/restart-required/failed).
    "starting" -> "Starting update…"
    "checking" -> "Checking…"
    "downloading" -> "Downloading…"
    "swapping" -> "Swapping…"
    "restart-required" -> "Restart required"
    "failed" -> "Failed"
    else -> state
}

/** Epoch-millis (Double) → "Checked Xm/Xh/Xd ago", or null when unset. */
internal fun lastCheckedText(
    raw: Double?,
    nowMs: Long = Clock.System.now().toEpochMilliseconds(),
): String? {
    val ms = raw ?: return null
    if (ms <= 0) return null
    val diff = (nowMs - ms.toLong()).coerceAtLeast(0)
    val rel = when {
        diff < 60_000L -> "<1m ago"
        diff < 3_600_000L -> "${diff / 60_000L}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
        else -> "${diff / 86_400_000L}d ago"
    }
    return "Checked $rel"
}
