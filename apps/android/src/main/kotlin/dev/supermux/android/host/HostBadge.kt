package dev.supermux.android.host

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/** Visible host scope for pages whose reads and actions target one broker. */
@Composable
fun HostScopePicker(
    hosts: List<HostView>,
    selectedHostId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (hosts.size < 2) return
    val selected = hosts.firstOrNull { it.recordId == selectedHostId } ?: hosts.first()
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().background(cs.surfaceContainer)) {
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag("host_scope_picker"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Host", color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                HostDot(selected.colorIndex, size = 9.dp)
                Text(
                    selected.displayLabel + if (!selected.online) " · Offline" else "",
                    modifier = Modifier.padding(start = 7.dp, end = 5.dp),
                    color = cs.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text("⌄", color = cs.onSurfaceVariant)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                hosts.forEach { host ->
                    DropdownMenuItem(
                        text = { Text(host.displayLabel + if (!host.online) " (offline)" else "") },
                        leadingIcon = { HostDot(host.colorIndex, size = 10.dp) },
                        onClick = { expanded = false; onSelect(host.recordId) },
                    )
                }
            }
        }
        HorizontalDivider()
    }
}

/**
 * The `All · <host…> · +` filter chip row (spec §5). Each host chip carries its color dot and a
 * live session count; the trailing `+` chip adds a host. [selected] is a recordId or null (= All).
 * Long-pressing a host chip opens a Rename / Forget menu ([onRenameHost] / [onForgetHost]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HostFilterChips(
    hosts: List<HostView>,
    sessions: List<SessionInfo>,
    sessionHost: Map<String, String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onAddHost: () -> Unit,
    onRenameHost: (recordId: String, name: String) -> Unit = { _, _ -> },
    onForgetHost: (recordId: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    // Long-press host-actions: which chip's menu is open, and the target of the rename/forget dialogs.
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<HostView?>(null) }
    var renameText by remember { mutableStateOf("") }
    var forgetTarget by remember { mutableStateOf<HostView?>(null) }

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
            Box {
                FilterChip(
                    selected = selected == h.recordId,
                    // Tap + long-press are handled by the overlay below; keep the chip visual-only.
                    onClick = {},
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
                Box(
                    Modifier
                        .matchParentSize()
                        .combinedClickable(
                            onClick = { onSelect(h.recordId) },
                            onLongClick = { menuFor = h.recordId },
                        )
                        .testTag("host_chip_press_${h.recordId}"),
                )
                DropdownMenu(expanded = menuFor == h.recordId, onDismissRequest = { menuFor = null }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = { menuFor = null; renameText = h.displayLabel; renameTarget = h },
                    )
                    DropdownMenuItem(
                        text = { Text("Forget", color = cs.error) },
                        onClick = { menuFor = null; forgetTarget = h },
                    )
                }
            }
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

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename host") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.testTag("host_rename_field"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onRenameHost(target.recordId, renameText.trim()); renameTarget = null },
                    enabled = renameText.trim().isNotBlank(),
                    modifier = Modifier.testTag("host_rename_confirm"),
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }
    forgetTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { forgetTarget = null },
            title = { Text("Forget host?") },
            text = { Text("Removes \"${target.displayLabel}\" and its sessions from this device. You'll need a new pairing link to add it again.") },
            confirmButton = {
                TextButton(
                    onClick = { onForgetHost(target.recordId); forgetTarget = null },
                    modifier = Modifier.testTag("host_forget_confirm"),
                ) { Text("Forget", color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { forgetTarget = null }) { Text("Cancel") } },
        )
    }
}
