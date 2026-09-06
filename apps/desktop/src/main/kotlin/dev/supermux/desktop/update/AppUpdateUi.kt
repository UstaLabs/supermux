package dev.supermux.desktop.update

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.platform.UpdatePhase
import kotlinx.coroutines.launch

/** Full-pane "Check for updates" screen (File ▸ Check for Updates…). */
@Composable
fun AppUpdateScreen(onBack: () -> Unit, topBarShown: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    // Cluster G1: the check/download/install machine is `Platform.updates` now, so this page holds
    // no HttpClient and no status of its own — the same seam the banner reads, hence one answer.
    val updater = LocalPlatform.current.updates
    val update by updater.status.collectAsState()
    val status = update.release
    val loading = update.phase == UpdatePhase.Checking || update.phase == UpdatePhase.Idle
    val installing = update.busy
    val actionError = update.error

    fun refresh() {
        scope.launch { updater.check() }
    }

    LaunchedEffect(Unit) { updater.check() }

    Surface(Modifier.fillMaxSize().testTag("app_update_overlay"), color = cs.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    // Under the hub's own bar this row is just the Recheck action, so it must not
                    // paint a second full-width raised strip beneath it.
                    .then(if (topBarShown) Modifier else Modifier.background(cs.surfaceContainerHigh))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // [topBarShown]: the Settings hub already painted this page's title and Back
                // (its compact detail chrome), so only Recheck is left to draw.
                if (topBarShown) {
                    Spacer(Modifier.weight(1f))
                } else {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        "Check for updates",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                TextButton(onClick = { refresh() }, enabled = !loading && !installing) {
                    Text("Recheck")
                }
            }
            if (loading && status == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Column(
                    Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Desktop app", color = cs.onSurfaceVariant, fontSize = 12.sp)
                    Text("supermux $DESKTOP_APP_VERSION", style = MaterialTheme.typography.titleMedium)
                    val s = status
                    when {
                        s == null -> Text("Couldn't check for updates.", color = cs.error)
                        s.lastError != null && s.latestVersion == null ->
                            Text(s.lastError!!, color = cs.error, fontSize = 12.sp)
                        s.updateAvailable -> {
                            Text("Update available: ${s.latestVersion}", color = cs.onSurface)
                            s.notesUrl?.let {
                                Text(
                                    "Release notes",
                                    color = cs.primary,
                                    modifier = Modifier.clickable { updater.openReleaseNotes() },
                                )
                            }
                            if (s.canInstall && s.downloadUrl != null) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            val installer = updater.download { _, _ -> }
                                            if (installer != null) updater.install(installer)
                                        }
                                    },
                                    enabled = !installing,
                                ) {
                                    if (installing) {
                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Downloading…")
                                    } else {
                                        Icon(Icons.Default.Download, contentDescription = null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Download & install")
                                    }
                                }
                                Text(
                                    "Downloads the latest .${AppUpdate.installerExtension()} and opens it.",
                                    color = cs.onSurfaceVariant,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                        else -> Text("You're up to date", color = cs.onSurfaceVariant)
                    }
                    actionError?.let { Text(it, color = cs.error, fontSize = 12.sp) }
                }
            }
        }
    }
}

/** Startup strip when a newer desktop release is available. */
@Composable
fun AppUpdateBanner(
    onOpenPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val updater = LocalPlatform.current.updates
    val update by updater.status.collectAsState()
    var dismissed by remember { mutableStateOf(false) }
    val installing = update.busy

    LaunchedEffect(Unit) { updater.check() }

    val s = update.release
    if (s == null || dismissed || update.dismissed || !s.updateAvailable) return

    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .background(cs.primaryContainer)
            .clickable(onClick = onOpenPage)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("app_update_banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = cs.onPrimaryContainer, modifier = Modifier.size(18.dp))
        Text(
            "Update available: ${s.latestVersion}",
            color = cs.onPrimaryContainer,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        if (s.canInstall && s.downloadUrl != null) {
            TextButton(
                onClick = {
                    scope.launch {
                        val installer = updater.download { _, _ -> }
                        if (installer != null) updater.install(installer)
                    }
                },
                enabled = !installing,
            ) {
                Text(if (installing) "…" else "Update", color = cs.onPrimaryContainer)
            }
        }
        IconButton(onClick = {
            updater.dismiss()
            dismissed = true
        }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = cs.onPrimaryContainer, modifier = Modifier.size(16.dp))
        }
    }
}
