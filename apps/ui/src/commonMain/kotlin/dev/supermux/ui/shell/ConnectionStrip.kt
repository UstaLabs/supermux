package dev.supermux.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.host.HostView
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.Stroke
import kotlinx.coroutines.delay
import kotlin.time.Clock

/** Card #121: what the connection strip says, if anything. */
enum class ConnectionBanner { None, Connecting, Offline }

/** A drop shorter than this (or a launch's first dial) draws nothing. */
const val CONNECT_GRACE_MS = 2_000L
/** Past this the strip stops saying "Connecting…" and says the app is offline. */
const val OFFLINE_AFTER_MS = 8_000L
/** How long a Retry says "Connecting…" before the strip falls back to "Offline". */
const val RETRY_SHOWS_MS = 4_000L

/**
 * The strip's state. Nothing while any paired host is connected (or none is paired); while all are
 * down, nothing for the first [CONNECT_GRACE_MS], then "Connecting…", then "Offline" from
 * [OFFLINE_AFTER_MS]. A Retry says "Connecting…" again.
 */
fun connectionBanner(anyHost: Boolean, anyOnline: Boolean, offlineForMs: Long, retrying: Boolean): ConnectionBanner = when {
    !anyHost || anyOnline -> ConnectionBanner.None
    retrying -> ConnectionBanner.Connecting
    offlineForMs < CONNECT_GRACE_MS -> ConnectionBanner.None
    offlineForMs < OFFLINE_AFTER_MS -> ConnectionBanner.Connecting
    else -> ConnectionBanner.Offline
}

/** The strip's state and its Retry, provided once by the shell and read by the list and the chat. */
class ConnectionStatus(val banner: ConnectionBanner = ConnectionBanner.None, val retry: () -> Unit = {})

val LocalConnectionStatus = staticCompositionLocalOf { ConnectionStatus() }

/** [connectionBanner] over the fleet's hosts, re-evaluated as time passes while they are down. */
@Composable
fun rememberConnectionStatus(hosts: List<HostView>, retryNow: () -> Unit): ConnectionStatus {
    val anyHost = hosts.isNotEmpty()
    val anyOnline = hosts.any { it.online }
    val now = { Clock.System.now().toEpochMilliseconds() }
    // At launch every host is down, so the grace below also covers the first dial.
    val downSince = remember(anyOnline) { now() }
    var retryAt by remember { mutableLongStateOf(0L) }
    // A state that changes only when the banner does, so the shell recomposes on a change, not on each tick.
    val banner by produceState(ConnectionBanner.None, anyHost, anyOnline, downSince, retryAt) {
        while (true) {
            val t = now()
            value = connectionBanner(anyHost, anyOnline, t - downSince, retrying = t - retryAt < RETRY_SHOWS_MS)
            if (value == ConnectionBanner.None && (anyOnline || !anyHost)) break
            delay(500)
        }
    }
    return remember(banner) {
        ConnectionStatus(banner) {
            retryAt = now()
            retryNow()
        }
    }
}

/**
 * The thin strip from the approved prototype: above the chat list ([inChat] false, with Retry
 * while offline) or above the composer ([inChat] true). Draws nothing while connected.
 */
@Composable
fun ConnectionStrip(inChat: Boolean, modifier: Modifier = Modifier) {
    val status = LocalConnectionStatus.current
    if (status.banner == ConnectionBanner.None) return
    val cs = MaterialTheme.colorScheme
    val offline = status.banner == ConnectionBanner.Offline
    val text = when {
        !offline -> localized(Str.connection_connecting)
        inChat -> localized(Str.connection_offline_in_chat)
        else -> localized(Str.connection_offline_saved_chats)
    }
    Column(modifier.fillMaxWidth().testTag("connection_strip")) {
        if (inChat) HorizontalDivider(thickness = Stroke.hairline, color = cs.outline)
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (inChat) Modifier else Modifier.background(cs.surfaceVariant))
                .padding(horizontal = Space.md, vertical = if (inChat) Space.xs else 6.dp),
            horizontalArrangement = if (inChat) Arrangement.Center else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (offline) {
                Box(Modifier.size(7.dp).background(LocalSemantics.current.warning, CircleShape))
            } else {
                CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.size(Space.sm))
            Text(text, color = cs.onSurfaceVariant, fontSize = 12.5.sp)
            if (offline && !inChat) {
                Spacer(Modifier.weight(1f))
                Text(
                    localized(Str.connection_retry),
                    color = cs.primary,
                    fontSize = 12.5.sp,
                    modifier = Modifier.testTag("connection_retry").clickable(onClick = status.retry),
                )
            }
        }
        if (!inChat) HorizontalDivider(thickness = Stroke.hairline, color = cs.outline)
    }
}
