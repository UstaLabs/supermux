package dev.supermux.ui.host

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.host.HostView
import dev.supermux.host.formatLastSeen
import dev.supermux.host.hostDotArgb
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.widgets.AlertDialog
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem
import kotlin.time.Clock

/**
 * Host badge visuals for the merged fleet list (spec §5): a stable per-host color dot, a compact
 * per-row badge, the sidebar footer's [HostSwitcher], and the one-host scope picker.
 *
 * The pure slot/label/filter logic AND the OKLCH dot palette live in the shared
 * [dev.supermux.host] FleetModel (unit-tested on the JVM, reused verbatim by iOS); this file is
 * only the Compose rendering — [hostDotArgb] gives the exact same color per slot on every
 * platform, so a host's dot is its identity and dynamic color never repaints it.
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

/** Compact per-row host badge: identity dot + short host name; an offline host is dimmed. */
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
 * Visible host scope for pages whose reads and actions target one broker. Hidden below two hosts.
 *
 * The anchor's width follows the window, because a [DropdownMenu] anchors to the box it is declared
 * in: on a phone the row IS the control and spans the pane (label left, host right), while on a
 * wide window a full-width anchor would drop a menu the width of the whole settings pane — so there
 * the clickable part is chip-sized ([wrapContentWidth]) and the "Host" label sits outside it.
 */
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
    val compact = LocalWindowWidthClass.current == WindowWidthClass.Compact
    var expanded by remember { mutableStateOf(false) }

    @Composable
    fun Menu() {
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

    @Composable
    fun Anchor(anchorModifier: Modifier) {
        Row(
            anchorModifier
                .clickable { expanded = true }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(
                    horizontal = if (compact) 16.dp else 10.dp,
                    vertical = if (compact) 10.dp else 6.dp,
                )
                .testTag("host_scope_picker"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (compact) {
                Text("Host", color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
            }
            HostDot(selected.colorIndex, size = 9.dp)
            Text(
                selected.displayLabel + if (!selected.online) " · Offline" else "",
                modifier = Modifier.padding(start = 7.dp, end = 5.dp),
                color = cs.onSurface,
                fontSize = if (compact) 14.sp else TextUnit.Unspecified,
                fontWeight = if (compact) FontWeight.Medium else null,
            )
            Text("⌄", color = cs.onSurfaceVariant)
        }
    }

    Column(modifier.fillMaxWidth().background(cs.surfaceContainer)) {
        if (compact) {
            Box { Anchor(Modifier.fillMaxWidth()); Menu() }
        } else {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Host", color = cs.onSurfaceVariant)
                Box { Anchor(Modifier.wrapContentWidth()); Menu() }
            }
        }
        HorizontalDivider()
    }
}

/**
 * The host the [HostSwitcher] names: the filtered host, else the only one. Null reads "All hosts".
 * A stale filter (a forgotten host's id) falls through the same way, so the pill never names a
 * host that is gone.
 */
fun switcherHost(hosts: List<HostView>, filter: String?): HostView? =
    hosts.firstOrNull { it.recordId == filter } ?: hosts.singleOrNull()

/**
 * The host switch at the left of the sidebar footer: the current host's dot and name (or "All
 * hosts") with a ⌄, opening a menu of `All hosts`, every host with its live session count (an
 * OFFLINE host is greyed and carries its last-seen), Rename / Forget for the selected host, and
 * `Add host`. [selected] is a recordId or null (= All).
 *
 * It shows from ONE host up: a device that has paired a single host is exactly the one that needs
 * a visible way to add the next.
 *
 * [nowMs] is a parameter so the offline "· 5m ago" suffix is assertable; it defaults to the wall
 * clock through the multiplatform `kotlin.time.Clock` — the stdlib one every module in this repo
 * now uses; there is no platform wall-clock call in commonMain.
 */
@Composable
fun HostSwitcher(
    hosts: List<HostView>,
    sessions: List<SessionInfo>,
    sessionHost: Map<String, String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onAddHost: () -> Unit,
    onRenameHost: (recordId: String, name: String) -> Unit = { _, _ -> },
    onForgetHost: (recordId: String) -> Unit = {},
    nowMs: Long = Clock.System.now().toEpochMilliseconds(),
    modifier: Modifier = Modifier,
) {
    if (hosts.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val current = switcherHost(hosts, selected)
    var expanded by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<HostView?>(null) }
    var renameText by remember { mutableStateOf("") }
    var forgetTarget by remember { mutableStateOf<HostView?>(null) }

    Box(modifier) {
        Row(
            Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 8.dp)
                .testTag("host_switcher"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (current != null) {
                HostDot(current.colorIndex, size = 8.dp)
            } else {
                Icon(Icons.Filled.Dns, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
            }
            Text(
                current?.displayLabel ?: "All hosts",
                modifier = Modifier.weight(1f, fill = false),
                color = if (current == null || current.online) cs.onSurface else cs.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("⌄", color = cs.onSurfaceVariant, fontSize = 12.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (hosts.size > 1) {
                DropdownMenuItem(
                    text = { Text("All hosts") },
                    leadingIcon = {
                        Icon(Icons.Filled.Dns, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    },
                    trailingIcon = { MenuTrailing(sessions.size, checked = current == null) },
                    onClick = { expanded = false; onSelect(null) },
                    modifier = Modifier.testTag("host_switcher_all"),
                )
            }
            hosts.forEach { h ->
                val count = sessions.count { sessionHost[it.id] == h.recordId }
                val lastSeen = if (!h.online) formatLastSeen(nowMs, h.lastSeenAt) else ""
                DropdownMenuItem(
                    text = {
                        Text(
                            h.displayLabel + if (!h.online) " · ${lastSeen.ifEmpty { "offline" }}" else "",
                            color = if (h.online) Color.Unspecified else cs.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    },
                    leadingIcon = { HostDot(h.colorIndex, size = 9.dp) },
                    trailingIcon = { MenuTrailing(count, checked = current?.recordId == h.recordId) },
                    onClick = { expanded = false; onSelect(h.recordId) },
                    modifier = Modifier.testTag("host_switcher_${h.recordId}"),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (current != null) {
                DropdownMenuItem(
                    text = { Text("Rename ${current.displayLabel}…") },
                    onClick = { expanded = false; renameText = current.displayLabel; renameTarget = current },
                    modifier = Modifier.testTag("host_switcher_rename"),
                )
                DropdownMenuItem(
                    text = { Text("Forget ${current.displayLabel}…", color = cs.error) },
                    onClick = { expanded = false; forgetTarget = current },
                    modifier = Modifier.testTag("host_switcher_forget"),
                )
            }
            DropdownMenuItem(
                text = { Text("Add host…") },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
                onClick = { expanded = false; onAddHost() },
                modifier = Modifier.testTag("host_switcher_add"),
            )
        }
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

/** A menu row's live session count, then a check on the row that is the current scope. */
@Composable
private fun MenuTrailing(count: Int, checked: Boolean) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (count > 0) Text("$count", color = cs.onSurfaceVariant, fontSize = 11.sp)
        Box(Modifier.size(14.dp)) {
            if (checked) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = cs.primary, modifier = Modifier.size(14.dp))
        }
    }
}
