// The composer's two picker controls: the run-settings pill (model · effort · fast · permission mode
// behind ONE panel) and the permission chip, which shows only while the mode is not the agent's own
// default. They used to be four separate pills in a row that did not fit a phone; now the toolbar is
// `+ · run-settings (· mode) … mic · send` on every host. Everything is a pill or a circle with no
// outline — tone alone separates a control from the card.
package dev.supermux.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.ModelInfo
import dev.supermux.net.ReasoningResponse
import dev.supermux.net.effortSpeedometerParams
import dev.supermux.proto.PermissionModeInfo
import dev.supermux.ui.session.AgentLogo
import dev.supermux.ui.session.hasAgentLogo
import dev.supermux.ui.theme.HapticKind
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.rememberHaptics
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem
import dev.supermux.ui.widgets.Speedometer

/**
 * One pill for everything about WHO runs the next turn and HOW: `[logo] Opus 5.5 · high ⚡ ⌄`. Opens
 * the [RunSettingsPanel] — a popover above the pill under a pointer, a bottom sheet under touch —
 * with the model list, the effort segments, the fast switch and the permission mode.
 *
 * [models] null hides the model list (no catalog and no session model), [reasoning] null hides
 * effort, [fastMode] null hides the fast switch, [permission] null hides the Mode row. Tags:
 * `composer-model-pill`, rows `composer-model-<id>`, `composer-reasoning-<id>`, `composer-fast-toggle`.
 */
@Composable
internal fun RunSettingsControl(
    pointer: Boolean,
    /** A narrow composer: effort shows as the gauge alone, not as text. */
    compact: Boolean,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    agent: String?,
    models: List<ModelInfo>?,
    modelCurrent: String?,
    onPickModel: (String) -> Unit,
    reasoning: ReasoningResponse?,
    reasoningCurrent: String?,
    onPickReasoning: (String) -> Unit,
    fastMode: Boolean?,
    onToggleFast: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** The permission modes, picked from the panel's Mode row. */
    permission: RunSettingsPermission? = null,
    /** Drawn at the top of the panel — the launcher puts its agent row and account choice here. */
    header: (@Composable (sheet: Boolean) -> Unit)? = null,
    /** Extra marks on the pill before its chevron (the launcher's "added account" glyph). */
    pillExtra: (@Composable () -> Unit)? = null,
    /** The launcher's account choice, as a drill-in row of the panel. */
    account: RunSettingsAccount? = null,
) {
    val cs = MaterialTheme.colorScheme
    val modelLabel = models?.let { shortModelName(composerModelLabel(modelCurrent, it), it) }
    val effortLabel = reasoning?.let { composerReasoningLabel(it, reasoningCurrent) }

    // Where the pill sits in the window, so the popover can be capped to the roomier side of it.
    val density = LocalDensity.current
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    var anchorBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    Box(
        modifier
            .testTag("composer-model-picker")
            .onGloballyPositioned { anchorBounds = it.boundsInWindow() },
    ) {
        Row(
            modifier = Modifier
                .testTag("composer-model-pill")
                .toolbarControl(pointer, open, tonal = true)
                .clickable { onOpenChange(true) }
                .padding(start = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (agent != null && hasAgentLogo(agent)) {
                AgentLogo(agent, size = 13.dp)
            } else {
                Icon(Icons.Filled.AutoAwesome, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(13.dp))
            }
            Text(
                text = modelLabel ?: effortLabel ?: "Settings",
                color = cs.onSurface.copy(alpha = 0.85f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (modelLabel != null && reasoning != null && effortLabel != null) {
                if (compact) {
                    val (gaugeLevels, gaugeValue) = effortSpeedometerParams(reasoningCurrent, reasoning.levels)
                    Speedometer(
                        levels = gaugeLevels,
                        value = gaugeValue,
                        tint = cs.onSurfaceVariant,
                        activeTint = cs.primary,
                        iconSize = 14.dp,
                        testTag = "composer-pill-effort-gauge",
                    )
                } else {
                    Text(effortLabel, color = cs.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
                }
            }
            if (fastMode == true) {
                Icon(Icons.Filled.Bolt, "Fast mode on", tint = cs.tertiary, modifier = Modifier.size(13.dp))
            }
            pillExtra?.invoke()
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = cs.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp),
            )
        }

        val panel: @Composable (sheet: Boolean) -> Unit = { sheet ->
            RunSettingsPanel(
                sheet = sheet,
                agent = agent,
                header = header,
                models = models,
                modelCurrent = modelCurrent,
                onPickModel = onPickModel,
                reasoning = reasoning,
                reasoningCurrent = reasoningCurrent,
                onPickReasoning = onPickReasoning,
                fastMode = fastMode,
                onToggleFast = onToggleFast,
                permission = permission,
                onDismiss = { onOpenChange(false) },
                account = account,
            )
        }
        if (open) {
            if (pointer) {
                RunSettingsPopover(
                    onDismiss = { onOpenChange(false) },
                    maxHeight = if (windowHeightPx > 0 && anchorBounds != androidx.compose.ui.geometry.Rect.Zero) {
                        val room = maxOf(anchorBounds.top, windowHeightPx - anchorBounds.bottom) - 12f
                        with(density) { room.coerceAtLeast(0f).toDp() }
                    } else {
                        Dp.Unspecified
                    },
                ) { panel(false) }
            } else {
                ComposerSheet(onDismiss = { onOpenChange(false) }) { panel(true) }
            }
        }
    }
}

/** The permission modes the run-settings panel's Mode row picks from. */
internal class RunSettingsPermission(
    val modes: List<PermissionModeInfo>,
    sessionMode: String?,
    val onPick: (String) -> Unit,
) {
    val current: PermissionModeInfo? =
        modes.find { it.id == sessionMode } ?: modes.find { it.default } ?: modes.firstOrNull()
}

/** The ink a non-default permission mode is tinted with: teal for Plan, amber for the rest. */
@Composable
internal fun permissionInk(modeId: String): Color =
    if (modeId == "plan") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary

/**
 * The permission mode. At the agent's own default it is a quiet shield in a circle — nothing to
 * read, nothing to worry about. Any other mode tints itself and says its name ("Plan", "Ask"…), so
 * a session that will not edit, or that will stop to ask, is visible at a glance. [compact] keeps
 * the tint but drops the name. Same tags as before: `permissions-pill`, rows `permissions-<id>`.
 */
@Composable
internal fun PermissionControl(
    modes: List<PermissionModeInfo>,
    sessionMode: String?,
    pointer: Boolean,
    /** A narrow composer: the tinted icon alone; the mode's name is in the menu. */
    compact: Boolean,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onPick: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val current = modes.find { it.id == sessionMode } ?: modes.find { it.default } ?: modes.first()
    val loud = !current.default
    val ink = if (loud) permissionInk(current.id) else cs.onSurfaceVariant
    val labelled = loud && !compact
    Box(Modifier.testTag("permissions-picker")) {
        Row(
            modifier = Modifier
                .testTag("permissions-pill")
                .toolbarControl(pointer, open)
                .then(if (loud) Modifier.background(ink.copy(alpha = 0.14f)) else Modifier)
                .clickable { onOpenChange(true) }
                .then(
                    if (labelled) Modifier.padding(start = 9.dp, end = 12.dp)
                    else Modifier.width(if (pointer) 32.dp else 36.dp),
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            Icon(
                if (current.id == "plan") Icons.Outlined.Visibility else Icons.Outlined.Shield,
                "Permissions: ${current.label}",
                tint = ink,
                modifier = Modifier.size(if (pointer) 16.dp else 19.dp),
            )
            if (labelled) {
                Text(current.label, color = ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
        if (pointer) {
            DropdownMenu(expanded = open, onDismissRequest = { onOpenChange(false) }) {
                SectionLabel("Permissions", sheet = false)
                modes.forEach { mode ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(mode.label)
                                Text(
                                    mode.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = cs.onSurfaceVariant,
                                )
                            }
                        },
                        trailingIcon = { if (mode.id == current.id) CheckMark() },
                        modifier = Modifier.testTag("permissions-${mode.id}"),
                        onClick = { onOpenChange(false); onPick(mode.id) },
                    )
                }
            }
        }
        if (!pointer && open) {
            ComposerSheet(onDismiss = { onOpenChange(false) }) {
                SectionLabel("Permissions", sheet = true)
                modes.forEach { mode ->
                    SheetRow(
                        label = mode.label,
                        selected = mode.id == current.id,
                        modifier = Modifier.testTag("permissions-${mode.id}"),
                        supporting = mode.description,
                    ) { onOpenChange(false); onPick(mode.id) }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, sheet: Boolean) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = if (sheet) 12.sp else 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(
            start = if (sheet) 20.dp else 12.dp,
            end = 12.dp,
            top = if (sheet) 12.dp else 6.dp,
            bottom = 4.dp,
        ),
    )
}

@Composable
private fun CheckMark() {
    Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposerSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.surfaceContainerLow,
        contentColor = cs.onSurface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) { content() }
    }
}

@Composable
private fun SheetRow(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    Row(
        modifier
            .fillMaxWidth()
            .clickable { haptic.perform(HapticKind.Tick); onClick() }
            .background(if (selected) cs.onSurface.copy(alpha = 0.05f) else Color.Transparent)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = cs.onSurface,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            )
            if (supporting != null) {
                Spacer(Modifier.height(2.dp))
                Text(supporting, color = cs.onSurfaceVariant, fontSize = 12.sp)
            }
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            CheckMark()
        }
    }
}

/**
 * The toolbar's control shape: a fixed-height pill (a circle when it is as wide as it is tall). [tonal]
 * controls carry a soft fill at rest so they read as tappable; the others are ink on the card until
 * hovered, pressed or opened. Never an outline.
 */
@Composable
private fun Modifier.toolbarControl(pointer: Boolean, open: Boolean, tonal: Boolean = false): Modifier {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Radii.pill)
    return this
        .height(if (pointer) 32.dp else 36.dp)
        .clip(shape)
        .background(
            when {
                open -> cs.onSurface.copy(alpha = 0.11f)
                tonal -> cs.onSurface.copy(alpha = 0.06f)
                else -> Color.Transparent
            },
        )
}
