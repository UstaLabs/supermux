package dev.supermux.android.update

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.R
import dev.supermux.update.ClientUpdateStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.launch

/**
 * Startup strip: checks for an app update once and, when available and not
 * dismissed for this latest version, shows "Update available — tap to install".
 */
@Composable
fun AppUpdateBanner(
    onOpenPage: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val http = remember { HttpClient(CIO) }
    var status by remember { mutableStateOf<ClientUpdateStatus?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val s = AppUpdate.check(http, context)
        if (s.updateAvailable && s.latestVersion != null &&
            !AppUpdate.isDismissed(context, s.latestVersion!!)
        ) {
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
            .clickable { onOpenPage() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painterResource(R.drawable.ic_download),
            contentDescription = null,
            tint = cs.onPrimaryContainer,
            modifier = Modifier.size(18.dp),
        )
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
                        val err = AppUpdate.downloadAndInstall(http, context, s.downloadUrl!!)
                        installing = false
                        when (err) {
                            null -> {}
                            "need-permission" -> AppUpdate.openInstallPermissionSettings(context)
                            else -> onOpenPage()
                        }
                    }
                },
                enabled = !installing,
            ) {
                Text(if (installing) "…" else "Update", color = cs.onPrimaryContainer)
            }
        }
        IconButton(
            onClick = {
                s.latestVersion?.let { AppUpdate.dismiss(context, it) }
                dismissed = true
            },
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_x),
                contentDescription = "Dismiss",
                tint = cs.onPrimaryContainer,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
