package dev.supermux.android.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.R
import dev.supermux.net.RunUpdateResult
import dev.supermux.net.UpdateStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ─── System settings (update status + restart broker) ──────────────────────────
//
// Parity with iOS SystemSettingsView: broker update-status display plus, for binary
// installs with an update available, an "Update broker" button that triggers the
// broker's self-updater and polls until it settles. Source/docker installs can't
// self-update (the broker's instruction is shown instead). A destructive
// Restart-broker action with a confirm dialog rounds it out. (The *app* updates
// out-of-band; this updates the *broker*.)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemSettingsPage(
    onBack: () -> Unit,
    updateStatus: suspend () -> UpdateStatus?,
    runUpdate: suspend () -> RunUpdateResult?,
    restartBroker: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf<UpdateStatus?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var showRestartConfirm by remember { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }
    var runError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val s = updateStatus()
        if (s != null) {
            status = s
            loadError = null
        } else {
            loadError = "Couldn't load update status."
        }
        loading = false
    }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("System", color = cs.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = cs.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surfaceContainerHigh),
            )
        },
        containerColor = cs.background,
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = cs.primary)
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // ── Updates section ──
                SettingsSectionHeader("UPDATES")
                val s = status
                if (loadError != null && s == null) {
                    Text(loadError!!, color = cs.error, fontSize = 13.sp)
                } else if (s != null) {
                    // Version row
                    Column {
                        Text("supermux ${s.current}", color = cs.onSurface, fontSize = 14.sp)
                        if (s.commit.isNotEmpty()) {
                            Text(
                                s.commit.take(8),
                                color = cs.onSurfaceVariant,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }

                    // Availability
                    UpdateAvailabilityRow(s)

                    // State (when not idle)
                    if (s.state != "idle") StateRow(s.state)

                    // Last checked
                    lastCheckedText(s.lastChecked)?.let {
                        Text(it, color = cs.onSurfaceVariant, fontSize = 12.sp)
                    }

                    // Last error
                    s.lastError?.let { Text(it, color = cs.error, fontSize = 12.sp) }

                    // Release notes link
                    s.notesUrl?.let { notes ->
                        Row(
                            Modifier.clickable { openUrl(context, notes) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("Release notes", color = cs.primary, fontSize = 14.sp)
                            Icon(
                                painterResource(R.drawable.ic_chevron_right),
                                contentDescription = null,
                                tint = cs.primary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }

                    // Update broker — binary self-updater only. Source/docker can't
                    // self-update; hide while an update is in flight (the state row
                    // shows progress) and once staged (restart-required needs a restart).
                    if (s.mode == "binary" &&
                        (s.updateAvailable || s.state == "failed") &&
                        !isRunningState(s.state) &&
                        s.state != "restart-required"
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    runError = null
                                    val result = runUpdate()
                                    when {
                                        result == null -> runError = "Couldn't reach the broker."
                                        result.started -> for (i in 0 until 120) {
                                            delay(1500)
                                            val fresh = updateStatus() ?: continue
                                            status = fresh
                                            if (!isRunningState(fresh.state)) break
                                        }
                                        else -> runError = result.instruction ?: result.error
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (s.state == "failed") "Retry update" else "Update broker")
                        }
                    }
                    runError?.let { Text(it, color = cs.error, fontSize = 12.sp) }
                } else {
                    Text("Status unavailable", color = cs.onSurfaceVariant, fontSize = 13.sp)
                }

                HorizontalDivider(color = cs.outlineVariant)

                // ── Maintenance section ──
                SettingsSectionHeader("MAINTENANCE")
                Button(
                    onClick = { showRestartConfirm = true },
                    enabled = !restarting,
                    modifier = Modifier.fillMaxWidth(),
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
                        Spacer(Modifier.width(8.dp))
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
            onDismissRequest = { showRestartConfirm = false },
            title = { Text("Restart the broker?") },
            text = { Text("Sessions will reconnect automatically.") },
            confirmButton = {
                TextButton(onClick = {
                    showRestartConfirm = false
                    restarting = true
                    restartBroker()
                    scope.launch {
                        delay(4000)
                        restarting = false
                    }
                }) { Text("Restart", color = cs.error) }
            },
            dismissButton = {
                TextButton(onClick = { showRestartConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

private fun isRunningState(state: String): Boolean =
    state == "checking" || state == "downloading" || state == "swapping"

@Composable
private fun UpdateAvailabilityRow(s: UpdateStatus) {
    val cs = MaterialTheme.colorScheme
    when {
        s.disabled -> Text("Update checks disabled.", color = cs.onSurfaceVariant, fontSize = 12.sp)
        s.updateAvailable -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_download),
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                "Update available" + (s.latest?.let { ": $it" } ?: ""),
                color = cs.onSurface,
                fontSize = 14.sp,
            )
        }
        else -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(16.dp),
            )
            Text("Up to date", color = cs.onSurfaceVariant, fontSize = 14.sp)
        }
    }
}

@Composable
private fun StateRow(state: String) {
    val cs = MaterialTheme.colorScheme
    val failed = state == "failed"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (failed) {
            Icon(
                painterResource(R.drawable.ic_x),
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
            fontSize = 12.sp,
        )
    }
}

private fun stateLabel(state: String): String = when (state) {
    "checking" -> "Checking…"
    "downloading" -> "Downloading…"
    "swapping" -> "Swapping…"
    "restart-required" -> "Restart required"
    "failed" -> "Failed"
    else -> state
}

/** Epoch-millis (Double) → "Checked Xm/Xh/Xd ago", or null when unset. */
private fun lastCheckedText(raw: Double?): String? {
    val ms = raw ?: return null
    if (ms <= 0) return null
    val diff = (System.currentTimeMillis() - ms.toLong()).coerceAtLeast(0)
    val rel = when {
        diff < 60_000L -> "<1m ago"
        diff < 3_600_000L -> "${diff / 60_000L}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
        else -> "${diff / 86_400_000L}d ago"
    }
    return "Checked $rel"
}
