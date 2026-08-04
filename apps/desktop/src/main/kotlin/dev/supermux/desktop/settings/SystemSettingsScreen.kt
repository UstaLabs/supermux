// Ported from apps/android/.../settings/SystemSettingsScreen.kt.
// Desktop adaptations:
//   - Scaffold/TopAppBar → hub detail chrome (no nested Back; hub owns navigation)
//   - LocalContext openUrl → openInBrowser
//   - painterResource icons → Material Icons
//   - sp/dp hardcodes → theme Space / MaterialTheme.typography
//   - checkUpdate() wired as UPDATES "Recheck" (BrokerApi force-poll; Android AppViewModel has it)
//   - Restart dialog text states plainly that this kills the desktop↔broker connection
//   - Distinct from update/AppUpdate.kt (File ▸ "Check for Updates…") — this updates the *broker*
//   - testTags for compose UI tests + SM_SYSTEM headless verification
package dev.supermux.desktop.settings

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.desktop.theme.Space
import dev.supermux.desktop.ui.openInBrowser
import dev.supermux.net.RunUpdateResult
import dev.supermux.net.UpdateStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Broker system / maintenance: update status + self-update + restart.
 *
 * This is **not** the desktop app's own self-update ([dev.supermux.desktop.update.AppUpdateScreen]);
 * that lives under File ▸ "Check for Updates…". Everything here targets the **active host's broker**.
 */
@Composable
fun SystemSettingsScreen(
    updateStatus: suspend () -> UpdateStatus?,
    checkUpdate: suspend () -> UpdateStatus?,
    runUpdate: suspend () -> RunUpdateResult?,
    restartBroker: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf<UpdateStatus?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var showRestartConfirm by remember { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }
    var runError by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }

    suspend fun loadStatus(forceCheck: Boolean = false) {
        val s = if (forceCheck) checkUpdate() else updateStatus()
        if (s != null) {
            status = s
            loadError = null
        } else if (status == null) {
            loadError = "Couldn't load update status."
        }
        loading = false
        checking = false
    }

    LaunchedEffect(Unit) { loadStatus(forceCheck = false) }

    Column(
        modifier
            .fillMaxSize()
            .testTag("system_settings_screen"),
    ) {
        if (loading && status == null) {
            Box(
                Modifier.fillMaxSize().weight(1f, fill = true),
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
                    .weight(1f, fill = true)
                    .verticalScroll(rememberScrollState())
                    .padding(Space.lg)
                    .testTag("system_settings_body"),
                verticalArrangement = Arrangement.spacedBy(Space.lg),
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
                                scope.launch { loadStatus(forceCheck = true) }
                            },
                            enabled = !checking && !updating && !restarting,
                            modifier = Modifier.testTag("system_recheck"),
                        ) {
                            if (checking) {
                                CircularProgressIndicator(
                                    Modifier.size(14.dp),
                                    color = cs.primary,
                                    strokeWidth = 2.dp,
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

                    if (s.state != "idle") {
                        StateRow(s.state)
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

                    s.notesUrl?.let { notes ->
                        Text(
                            "Release notes",
                            color = cs.primary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .clickable { openInBrowser(notes) }
                                .testTag("system_release_notes"),
                        )
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
                                    updating = true
                                    val result = runUpdate()
                                    when {
                                        result == null -> {
                                            runError = "Couldn't reach the broker."
                                            updating = false
                                        }
                                        result.started -> {
                                            for (i in 0 until 120) {
                                                delay(1500)
                                                val fresh = updateStatus() ?: continue
                                                status = fresh
                                                if (!isRunningState(fresh.state)) break
                                            }
                                            updating = false
                                        }
                                        else -> {
                                            runError = result.instruction ?: result.error
                                            updating = false
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("system_update_broker"),
                        ) {
                            Text(if (s.state == "failed") "Retry update" else "Update broker")
                        }
                    }
                    if (updating) {
                        StateRow(status?.state?.takeIf { isRunningState(it) } ?: "checking")
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("system_restart_broker"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = cs.error,
                        contentColor = cs.onError,
                    ),
                ) {
                    if (restarting) {
                        CircularProgressIndicator(
                            color = cs.onError,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(Space.sm))
                        Text("Restarting…")
                    } else {
                        Text("Restart broker")
                    }
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
                        restartBroker()
                        scope.launch {
                            delay(4000)
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
    state == "checking" || state == "downloading" || state == "swapping"

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
                modifier = Modifier.size(16.dp),
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
                modifier = Modifier.size(16.dp),
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        modifier = Modifier.testTag("system_update_state"),
    ) {
        if (failed) {
            Icon(
                Icons.Filled.Close,
                contentDescription = null,
                tint = cs.error,
                modifier = Modifier.size(14.dp),
            )
        } else {
            CircularProgressIndicator(
                color = cs.primary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            stateLabel(state),
            color = if (failed) cs.error else cs.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

internal fun stateLabel(state: String): String = when (state) {
    "checking" -> "Checking…"
    "downloading" -> "Downloading…"
    "swapping" -> "Swapping…"
    "restart-required" -> "Restart required"
    "failed" -> "Failed"
    else -> state
}

/** Epoch-millis (Double) → "Checked Xm/Xh/Xd ago", or null when unset. */
internal fun lastCheckedText(raw: Double?, nowMs: Long = System.currentTimeMillis()): String? {
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
