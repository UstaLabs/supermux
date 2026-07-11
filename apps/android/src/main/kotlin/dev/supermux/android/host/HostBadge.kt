package dev.supermux.android.host

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.R
import dev.supermux.host.hostDotArgb
import dev.supermux.proto.SessionInfo

/**
 * Host badge visuals for the merged fleet list (spec §5): a stable per-host color dot, a compact
 * per-row badge, and the `All · <host…> · +` filter chip row. Colors are authored in OKLCH (like
 * [dev.supermux.android.theme.SupermuxSemantics]) and held FIXED across dynamic color so a host's
 * dot never gets repainted by the wallpaper — its color is its identity.
 *
 * The pure slot/label/filter logic AND the OKLCH dot palette live in the shared [dev.supermux.host]
 * FleetModel (unit-tested on the JVM, reused verbatim by iOS); this file is only the Compose
 * rendering — `hostDotArgb` gives the exact same color per slot on every platform.
 */

/** The fixed dot color for a host color slot ([HostView.colorIndex]), theme-aware — resolved from
 *  the shared [hostDotArgb] so the dot matches iOS exactly. */
@Composable
fun hostDotColor(colorIndex: Int): Color {
    val dark = MaterialTheme.colorScheme.surface.isDark()
    return Color(hostDotArgb(colorIndex, dark))
}

private fun Color.isDark(): Boolean =
    (0.2126f * red + 0.7152f * green + 0.0722f * blue) < 0.5f

/** A small filled dot in the host's identity color. */
@Composable
fun HostDot(colorIndex: Int, size: Dp = 8.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(hostDotColor(colorIndex)))
}

/** Compact per-row host badge: identity dot + short host name. Rendered only in multi-host mode. */
@Composable
fun HostBadge(host: HostView, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.testTag("host_badge_${host.recordId}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HostDot(host.colorIndex, size = 7.dp)
        Text(
            host.shortLabel,
            color = cs.onSurfaceVariant,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * The `All · <host…> · +` filter chip row (spec §5). Each host chip carries its color dot and a
 * live session count; the trailing `+` chip adds a host. [selected] is a recordId or null (= All).
 */
@Composable
fun HostFilterChips(
    hosts: List<HostView>,
    sessions: List<SessionInfo>,
    sessionHost: Map<String, String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onAddHost: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .testTag("host_filter_chips")
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("All") },
            modifier = Modifier.testTag("host_chip_all"),
        )
        hosts.forEach { h ->
            val count = sessions.count { sessionHost[it.id] == h.recordId }
            FilterChip(
                selected = selected == h.recordId,
                onClick = { onSelect(h.recordId) },
                leadingIcon = { HostDot(h.colorIndex, size = 9.dp) },
                label = {
                    Text(
                        buildString {
                            append(h.shortLabel)
                            if (count > 0) append("  $count")
                        },
                        color = if (h.online) cs.onSurface else cs.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                },
                modifier = Modifier.testTag("host_chip_${h.recordId}"),
            )
        }
        // Add-host chip: opens the QR / paste / typed-URL flow.
        FilterChip(
            selected = false,
            onClick = onAddHost,
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_plus),
                    contentDescription = "Add host",
                    modifier = Modifier.size(16.dp),
                )
            },
            label = { Text("Add") },
            modifier = Modifier.testTag("host_chip_add"),
        )
    }
}
