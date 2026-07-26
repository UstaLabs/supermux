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
import dev.supermux.update.ClientUpdateStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.launch

/** Full-pane "Check for updates" screen (File ▸ Check for Updates…). */
@Composable
fun AppUpdateScreen(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val http = remember { HttpClient(CIO) }
    var status by remember { mutableStateOf<ClientUpdateStatus?>(null) }
    var loading by remember { mutableStateOf(true) }
    var installing by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            actionError = null
            status = AppUpdate.check(http)
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        status = AppUpdate.check(http)
        loading = false
    }

    Surface(Modifier.fillMaxSize().testTag("app_update_overlay"), color = cs.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(cs.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("Check for updates", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
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
                            s.notesUrl?.let { url ->
                                Text(
                                    "Release notes",
                                    color = cs.primary,
                                    modifier = Modifier.clickable { AppUpdate.openUrl(url) },
                                )
                            }
                            if (s.canInstall && s.downloadUrl != null) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            installing = true
                                            actionError = null
                                            actionError = AppUpdate.downloadAndOpen(http, s.downloadUrl!!)
                                            installing = false
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
    val http = remember { HttpClient(CIO) }
    var status by remember { mutableStateOf<ClientUpdateStatus?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val s = AppUpdate.check(http)
        if (s.updateAvailable && s.latestVersion != null && !AppUpdate.isDismissed(s.latestVersion!!)) {
            status = s
        }
    }

    val s = status
    if (s == null || dismissed || !s.updateAvailable) return

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
                        installing = true
                        AppUpdate.downloadAndOpen(http, s.downloadUrl!!)
                        installing = false
                    }
                },
                enabled = !installing,
            ) {
                Text(if (installing) "…" else "Update", color = cs.onPrimaryContainer)
            }
        }
        IconButton(onClick = {
            s.latestVersion?.let { AppUpdate.dismiss(it) }
            dismissed = true
        }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = cs.onPrimaryContainer, modifier = Modifier.size(16.dp))
        }
    }
}
