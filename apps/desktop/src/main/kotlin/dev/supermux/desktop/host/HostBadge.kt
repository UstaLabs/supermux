// Ported from apps/android/src/main/kotlin/dev/supermux/android/host/HostBadge.kt — keep in sync
// until a shared UI module exists. Desktop deltas: bundled drawables → materialIconsExtended
// (ic_plus → Icons.Filled.Add), and a hand hover cursor on the tappable chips.
package dev.supermux.desktop.host

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.oklchToArgb

/**
 * Host badge visuals for the merged fleet list (spec §5): a stable per-host color dot, a compact
 * per-row badge, and the `All · <host…> · +` filter chip row. Colors are authored in OKLCH (like
 * [dev.supermux.desktop.theme.SupermuxSemantics]) and held FIXED so a host's dot is its identity.
 *
 * The pure slot/label/filter logic lives in [FleetModel] (unit-tested on the JVM); this file is
 * only the Compose rendering.
 */

// Six fixed hues spread around the wheel; L/C tuned per theme for a legible small dot.
private val HOST_HUES = doubleArrayOf(195.0, 300.0, 70.0, 22.0, 250.0, 150.0)

/** The fixed dot color for a host color slot ([HostView.colorIndex]), theme-aware. */
@Composable
fun hostDotColor(colorIndex: Int): Color {
    val dark = MaterialTheme.colorScheme.surface.isDark()
    val h = HOST_HUES[((colorIndex % HOST_HUES.size) + HOST_HUES.size) % HOST_HUES.size]
    return if (dark) Color(oklchToArgb(0.74, 0.135, h)) else Color(oklchToArgb(0.55, 0.15, h))
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
            color = if (host.online) cs.onSurfaceVariant else cs.onSurfaceVariant.copy(alpha = 0.6f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * The `All · <host…> · +` filter chip row (spec §5). Each host chip carries its color dot and a
 * live session count; an OFFLINE host is greyed with its last-seen; the trailing `+` chip adds a
 * host. [selected] is a recordId or null (= All).
 */
@Composable
fun HostFilterChips(
    hosts: List<HostView>,
    sessions: List<SessionInfo>,
    sessionHost: Map<String, String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onAddHost: () -> Unit,
    nowMs: Long = System.currentTimeMillis(),
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
            modifier = Modifier.testTag("host_chip_all").pointerHoverIcon(PointerIcon.Hand),
        )
        hosts.forEach { h ->
            val count = sessions.count { sessionHost[it.id] == h.recordId }
            val lastSeen = if (!h.online) formatLastSeen(nowMs, h.lastSeenAt) else ""
            FilterChip(
                selected = selected == h.recordId,
                onClick = { onSelect(h.recordId) },
                leadingIcon = { HostDot(h.colorIndex, size = 9.dp) },
                label = {
                    Text(
                        buildString {
                            append(h.shortLabel)
                            if (count > 0) append("  $count")
                            if (lastSeen.isNotEmpty()) append("  · $lastSeen")
                        },
                        color = if (h.online) cs.onSurface else cs.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                },
                modifier = Modifier.testTag("host_chip_${h.recordId}").pointerHoverIcon(PointerIcon.Hand),
            )
        }
        // Add-host chip: opens the paste / typed-URL flow.
        FilterChip(
            selected = false,
            onClick = onAddHost,
            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = "Add host", modifier = Modifier.size(16.dp)) },
            label = { Text("Add") },
            modifier = Modifier.testTag("host_chip_add").pointerHoverIcon(PointerIcon.Hand),
        )
    }
}
