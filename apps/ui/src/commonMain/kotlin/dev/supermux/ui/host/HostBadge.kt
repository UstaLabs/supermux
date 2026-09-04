package dev.supermux.ui.host

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.host.HostView
import dev.supermux.host.formatLastSeen
import dev.supermux.host.hostDotArgb
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.adaptive.isSecondaryButtonPress
import dev.supermux.ui.widgets.AlertDialog
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem
import kotlin.time.Clock

/**
 * Host badge visuals for the merged fleet list (spec §5): a stable per-host color dot, a compact
 * per-row badge, the `All · <host…> · +` filter chip row, and the one-host scope picker.
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

/** Visible host scope for pages whose reads and actions target one broker. Hidden below two hosts. */
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
                    .pointerHoverIcon(PointerIcon.Hand)
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
 * live session count; an OFFLINE host is greyed and carries its last-seen; the trailing `+` chip
 * adds a host. [selected] is a recordId or null (= All).
 *
 * The Rename / Forget menu ([onRenameHost] / [onForgetHost]) opens the way the machine expects:
 * a LONG-PRESS where there is no pointing device, a RIGHT-CLICK where there is one. Both paths are
 * always wired — a docked tablet with a mouse gets the desktop gesture without losing the touch one
 * for its screen — and they key on [LocalPointerAvailable], not the keyboard-inclusive input mode.
 *
 * [nowMs] is a parameter so the offline "· 5m ago" suffix is assertable; it defaults to the wall
 * clock through the multiplatform `kotlin.time.Clock` (there is no platform wall-clock call in
 * commonMain, and kotlinx-datetime's `Clock` is gone from the 0.7 line this build resolves).
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
    nowMs: Long = Clock.System.now().toEpochMilliseconds(),
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val pointer = LocalPointerAvailable.current
    // Host-actions menu: which chip's menu is open, and the target of the rename/forget dialogs.
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
            modifier = Modifier.testTag("host_chip_all").pointerHoverIcon(PointerIcon.Hand),
        )
        hosts.forEach { h ->
            val count = sessions.count { sessionHost[it.id] == h.recordId }
            val lastSeen = if (!h.online) formatLastSeen(nowMs, h.lastSeenAt) else ""
            Box {
                FilterChip(
                    selected = selected == h.recordId,
                    // Tap / long-press / right-click are handled by the overlay below; the chip is
                    // visual-only so one node cannot own two conflicting gesture detectors.
                    onClick = {},
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
                Box(
                    Modifier
                        .matchParentSize()
                        .combinedClickable(
                            onClick = { onSelect(h.recordId) },
                            // Touch gesture: kept live even with a pointer attached, because a
                            // touchscreen does not stop working when a mouse is plugged in.
                            onLongClick = { menuFor = h.recordId },
                        )
                        .then(
                            // Pointer gesture: secondary-button press (through the
                            // `isSecondaryButtonPress` seam, because Compose declares
                            // `isSecondaryPressed` per platform). Only installed where a pointing
                            // device exists, so a phone never carries a dead detector.
                            if (pointer) {
                                Modifier.pointerInput(h.recordId) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            if (event.isSecondaryButtonPress()) {
                                                event.changes.forEach { it.consume() }
                                                menuFor = h.recordId
                                            }
                                        }
                                    }
                                }
                            } else {
                                Modifier
                            },
                        )
                        .pointerHoverIcon(PointerIcon.Hand)
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
            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = "Add host", modifier = Modifier.size(16.dp)) },
            label = { Text("Add") },
            modifier = Modifier.testTag("host_chip_add").pointerHoverIcon(PointerIcon.Hand),
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
