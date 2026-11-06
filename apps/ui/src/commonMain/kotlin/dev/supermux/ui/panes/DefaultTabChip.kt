package dev.supermux.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The default tab chip: an icon, a label and a close affordance, as a sheet tab.
 *
 * This is what a pane strip draws when the caller supplies no `tabSlot`. It is
 * deliberately content-free — it takes the font and the icon rather than reaching for them, and its
 * only colour source is [MaterialTheme] — so it can live in the pane layer. A caller that needs
 * more (an editor's unsaved-changes dot, a loading spinner) passes its own `tabSlot` instead.
 *
 * The strip owns position, size, gestures, the `view-tab-<id>` tag and the tab's background — the
 * active tab is cut from its pane's sheet, a hovered one wears a ghost. This owns what sits on it
 * and the `tab-close-<id>` tag: the × shows on the active tab and under the pointer only.
 */
@Composable
fun DefaultTabChip(
    itemId: String,
    title: String,
    state: TabSlotState,
    labelFont: FontFamily,
    onClose: (String) -> Unit,
    /**
     * Accessibility label for the close affordance. The caller supplies it because only the caller
     * knows what a tab holds — "view", "file", "tab" are all content vocabulary.
     */
    closeLabel: String = "Close view",
    /**
     * A status mark (e.g. a chat's unread), null for none: a badge on [icon], or a dot before the
     * label without one. The caller picks the colour, so this layer stays content-free.
     */
    dot: Color? = null,
    /** What the tab holds, at 16 dp before the label: an agent's mark, a terminal, a file. */
    icon: (@Composable () -> Unit)? = null,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    TabChipRow(hover) {
        if (icon != null) {
            TabIcon(badge = dot) { icon() }
        } else if (dot != null) {
            Box(Modifier.padding(end = 6.dp).size(6.dp).background(dot, CircleShape).testTag("tab-dot-$itemId"))
        }
        TabTitle(title, state, labelFont, emphasis = dot != null)
        TabClose(
            visible = state.selected || hovered,
            onClick = { onClose(itemId) },
            label = closeLabel,
            modifier = Modifier.testTag("tab-close-$itemId"),
        )
    }
}

/** A sheet tab's row: the tab's full share, its icon 11 dp in, its × 6 dp from the end. */
@Composable
internal fun TabChipRow(
    hover: MutableInteractionSource,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    // Touch: a roomier chip — a wider tap area; the pointer look is unchanged.
    val touch = !dev.supermux.ui.adaptive.LocalPointerAvailable.current
    Row(
        modifier
            .fillMaxSize()
            .hoverable(hover)
            .padding(start = if (touch) 16.dp else 11.dp, end = if (touch) 10.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A tab's 16 dp icon, with [badge] (unread) on its top-right corner, ringed in the frame's tone. */
@Composable
internal fun TabIcon(badge: Color?, icon: @Composable () -> Unit) {
    val ring = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(Modifier.padding(end = 7.dp).size(16.dp), contentAlignment = Alignment.Center) {
        icon()
        if (badge != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 3.dp, y = (-3).dp)
                    .size(10.dp)
                    .background(ring, CircleShape)
                    .padding(1.5.dp)
                    .background(badge, CircleShape),
            )
        }
    }
}

/**
 * A tab's label: the theme's text tone when it is active or [emphasis]ed (unread), quieter
 * otherwise, and fading out at the end rather than cut short with an ellipsis.
 */
@Composable
internal fun RowScope.TabTitle(title: String, state: TabSlotState, font: FontFamily, emphasis: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val touch = !dev.supermux.ui.adaptive.LocalPointerAvailable.current
    var clipped by remember { mutableStateOf(false) }
    Text(
        text = title,
        color = if (state.selected || emphasis) cs.onSurface else cs.onSurfaceVariant,
        fontFamily = font,
        fontSize = if (touch) 13.sp else 12.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { clipped = it.hasVisualOverflow },
        modifier = Modifier.weight(1f).fadeEnd(enabled = clipped, width = 18.dp),
    )
}

/** The tab's ×, 6 dp after the label; its 16 dp stay reserved while hidden, so nothing shifts. */
@Composable
internal fun TabClose(visible: Boolean, onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Spacer(Modifier.width(6.dp))
    Box(
        modifier
            .size(16.dp)
            .alpha(if (visible) 1f else 0f)
            .background(if (hovered && visible) cs.onSurface.copy(alpha = 0.11f) else Color.Transparent, CircleShape)
            .hoverable(hover)
            .then(if (visible) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Close,
            contentDescription = label,
            tint = if (hovered) cs.onSurface else cs.onSurfaceVariant,
            modifier = Modifier.size(11.dp),
        )
    }
}

/** Fades the last [width] of this element out, for text that runs past its room. */
internal fun Modifier.fadeEnd(enabled: Boolean, width: Dp): Modifier =
    if (!enabled) this else this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val w = width.toPx()
            drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - w, endX = size.width),
                topLeft = Offset(size.width - w, 0f),
                size = Size(w, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
