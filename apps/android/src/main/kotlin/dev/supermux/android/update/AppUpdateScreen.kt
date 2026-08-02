package dev.supermux.android.update

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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.R
import dev.supermux.android.settings.SettingsCaption
import dev.supermux.android.settings.SettingsSectionHeader
import dev.supermux.update.ClientUpdateStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.launch

/**
 * Settings → Check for updates. Polls supermux.dev/versions.json (GitHub fallback)
 * for a newer Android APK and offers one-click download + install.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUpdatePage(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val http = remember { HttpClient(CIO) }

    var status by remember { mutableStateOf<ClientUpdateStatus?>(null) }
    var loading by remember { mutableStateOf(true) }
    var installing by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var downloadLabel by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            actionError = null
            status = AppUpdate.check(http, context)
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        status = AppUpdate.check(http, context)
        loading = false
    }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Check for updates", color = cs.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = cs.onSurface)
                    }
                },
                actions = {
                    TextButton(onClick = { refresh() }, enabled = !loading && !installing) {
                        Text("Recheck")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surfaceContainerHigh),
            )
        },
        containerColor = cs.background,
    ) { padding ->
        if (loading && status == null) {
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
                SettingsSectionHeader("APP")
                val s = status
                val current = s?.currentVersion ?: AppUpdate.currentVersionName(context)
                val code = s?.currentVersionCode ?: AppUpdate.currentVersionCode(context)
                Column {
                    Text("supermux $current", color = cs.onSurface, fontSize = 14.sp)
                    Text("versionCode $code", color = cs.onSurfaceVariant, fontSize = 12.sp)
                }

                when {
                    s == null -> Text("Couldn't check for updates.", color = cs.error, fontSize = 13.sp)
                    s.lastError != null && s.latestVersion == null ->
                        Text(s.lastError!!, color = cs.error, fontSize = 12.sp)
                    s.updateAvailable -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_download),
                                contentDescription = null,
                                tint = cs.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                "Update available: ${s.latestVersion}",
                                color = cs.onSurface,
                                fontSize = 14.sp,
                            )
                        }
                        s.notesUrl?.let { notes ->
                            Row(
                                Modifier.clickable { AppUpdate.openNotes(context, notes) },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text("Release notes", color = cs.primary, fontSize = 14.sp)
                                Icon(
                                    painterResource(R.drawable.ic_external_link),
                                    contentDescription = null,
                                    tint = cs.primary,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                        if (s.canInstall && s.downloadUrl != null) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        installing = true
                                        actionError = null
                                        downloadLabel = "Starting download…"
                                        val err = AppUpdate.downloadAndInstall(
                                            http,
                                            context,
                                            s.downloadUrl!!,
                                        ) { received, total ->
                                            downloadLabel =
                                                AppUpdateNotifier.formatDownloadProgress(received, total)
                                        }
                                        installing = false
                                        downloadLabel = null
                                        when (err) {
                                            null -> {}
                                            "need-permission" -> {
                                                actionError =
                                                    "Allow installing apps from this source, then try again."
                                                AppUpdate.openInstallPermissionSettings(context)
                                            }
                                            else -> actionError = err
                                        }
                                    }
                                },
                                enabled = !installing,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (installing) {
                                    CircularProgressIndicator(
                                        color = cs.onPrimary,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(downloadLabel ?: "Downloading…")
                                } else {
                                    Text("Download & install")
                                }
                            }
                            SettingsCaption(
                                "One-tap installs the latest release APK over this build. " +
                                    "Progress also appears in the notification bar.",
                            )
                        } else {
                            Text(
                                "Update is available but no APK URL was published for this release.",
                                color = cs.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    else -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                tint = cs.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Text("You're up to date", color = cs.onSurfaceVariant, fontSize = 14.sp)
                        }
                    }
                }
                actionError?.let { Text(it, color = cs.error, fontSize = 12.sp) }
            }
        }
    }
}
