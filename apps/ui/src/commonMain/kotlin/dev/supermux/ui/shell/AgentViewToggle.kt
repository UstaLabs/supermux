// Shared Chat ⇄ Native main-view switch (UI cluster B, task B3): one implementation for both apps.
// The signature was already identical on both sides; only the geometry differed, so it branches on
// [LocalPointerAvailable] — no mouse/touchpad keeps Android's filled 28dp thumb pill, a real
// pointer keeps desktop's bordered 24dp one. Icons are Material (`Outlined.AutoAwesome` /
// `Outlined.Terminal`); the `pointerHoverIcon(Hand)` is harmless on touch.
package dev.supermux.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.adaptive.LocalPointerAvailable

/**
 * The shell header's Chat ⇄ Native main-view switch — a two-segment rounded pill (native match for
 * the iOS `AgentViewToggle`) that flips the chat column between the transcript and the agent's raw
 * ("Native") terminal. The caller shows it only for agents that have a native view (claude) and
 * only while the chat pane is visible.
 *
 * Crucially it is a LABELLED pill, so it reads as its own control rather than a bare terminal icon
 * sitting next to the terminal *pane* toggle — that adjacency was the "duplicate / ugly icons" the
 * tablet header showed before.
 *
 * No pointer device: Android's filled track (`surfaceContainerHighest`), 9dp pill, 28dp segments with 10dp
 * horizontal padding, the selected one an accent `primary`/`onPrimary` chip, labels always SemiBold
 * (thumb-scale legibility). With a mouse or touchpad: desktop's quieter hairline chip — a 1dp `outlineVariant`
 * border and no track fill, 8dp pill, 24dp segments with 9dp padding, and the macOS/M3
 * segmented-control convention that the SELECTED segment is a raised `surfaceContainerHigh` chip in
 * the plain `onSurface` label colour, so the header keeps one accent (the live status dot).
 *
 * The branch asks [LocalPointerAvailable], NOT [dev.supermux.ui.adaptive.LocalInputMode]: the latter
 * folds a hardware keyboard in, and a touch tablet with a Bluetooth keyboard attached must still get
 * thumb-sized segments (the repo contract in `ui/adaptive/InputMode.kt` — anything sizing a hit
 * target asks this one).
 */
@Composable
fun AgentViewToggle(
    nativeView: Boolean,
    onSetNative: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** Drop the labels (icons only) when the header is too narrow for them. */
    iconOnly: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val touch = !LocalPointerAvailable.current
    val trackShape = RoundedCornerShape(if (touch) 9.dp else 8.dp)
    Row(
        modifier
            .clip(trackShape)
            .then(
                if (touch) Modifier.background(cs.surfaceContainerHighest)
                else Modifier.border(1.dp, cs.outlineVariant, trackShape),
            )
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Segment("Chat", Icons.Outlined.AutoAwesome, selected = !nativeView, touch = touch, iconOnly = iconOnly, tag = "agent_view_chat") { onSetNative(false) }
        Segment("Native", Icons.Outlined.Terminal, selected = nativeView, touch = touch, iconOnly = iconOnly, tag = "agent_view_native") { onSetNative(true) }
    }
}

@Composable
private fun Segment(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    touch: Boolean,
    iconOnly: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val fill = when {
        !selected -> Color.Transparent
        touch -> cs.primary
        else -> cs.surfaceContainerHigh
    }
    val content = when {
        !selected -> cs.onSurfaceVariant
        touch -> cs.onPrimary
        else -> cs.onSurface
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(fill)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .height(if (touch) 28.dp else 24.dp)
            .padding(horizontal = if (touch) 10.dp else 9.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = if (iconOnly) label else null,
            tint = content,
            modifier = Modifier.size(13.dp),
        )
        if (!iconOnly) {
            Text(
                label,
                color = content,
                fontSize = 12.sp,
                fontWeight = if (touch || selected) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }
}
