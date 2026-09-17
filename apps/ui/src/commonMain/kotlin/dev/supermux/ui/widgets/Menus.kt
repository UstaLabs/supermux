package dev.supermux.ui.widgets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.theme.Stroke

/**
 * One menu look for the whole app — the popup surface AND the rows in it.
 *
 * ── Why this exists ─────────────────────────────────────────────────────────
 *
 * Ahmet: "in kmp desktop the menus are very ugly".
 *
 * Every desktop menu was raw Material 3 with nothing passed: 48dp rows sized for a thumb, 16sp
 * `bodyLarge`, a 4dp corner, tonal elevation, a full-bleed ripple. That is the Android phone
 * default, and on a Mac — next to a native menu bar drawn by AppKit two pixels above it — it reads
 * as a phone app in a window. Nothing was wrong with the code; the defaults were simply for another
 * platform.
 *
 * These tokens aim at the macOS menu instead: a compact row, 13sp text, a 10dp container with a
 * hairline edge and a soft shadow, and — the detail that does most of the work — a highlight that
 * is an INSET ROUNDED RECT rather than a full-width band, so the accent floats inside the menu
 * instead of touching its walls. Consumers:
 *
 *   • [DropdownMenu] / [DropdownMenuItem] wherever [LocalPointerAvailable] is true — every in-app menu.
 *   • `desktop/ui/DesktopContextMenu.kt`'s `SupermuxContextMenuRepresentation` — the right-click /
 *     text menus, which Compose draws itself and which otherwise look nothing like the app.
 *
 * They are POINTER tokens on purpose. A device with no mouse or touchpad still gets Material3's
 * thumb-sized rows, because a 28dp row is not a touch target — see [DropdownMenuItem]. The signal is
 * [LocalPointerAvailable], NOT `LocalInputMode`: the latter also turns Pointer for a phone with a
 * Bluetooth keyboard, which is still a device you tap with a thumb.
 *
 * Sizes stay in dp (not sp) so a menu row keeps its proportions under the Appearance ▸ Text size
 * multiplier; only the label scales, which is the same thing the platform does.
 */
object MenuStyle {
    /** Container corner. macOS menus are ~6pt; ours is softer to match [dev.supermux.ui.theme.Radii]. */
    val Shape: Shape = RoundedCornerShape(10.dp)

    /** The hover/selection highlight behind one row. */
    val ItemShape: Shape = RoundedCornerShape(6.dp)

    /** Row height — a pointer target, not a 48dp thumb target. */
    val ItemHeight = 28.dp

    /** Row height without a pointer — a thumb target. */
    val TouchItemHeight = 44.dp

    /** How far the highlight is inset from the menu's own edge. */
    val ItemInset = 5.dp

    /** Gap between rows: enough to read the inset highlight as a separate chip. */
    val ItemGap = 1.dp

    /** Text inset INSIDE the highlight. */
    val ItemPadding = 9.dp

    /** Gap between a leading/trailing icon and the label. */
    val IconGap = 8.dp

    /** Longest a label may make a menu before it wraps instead of growing wider. */
    val ItemMaxTextWidth = 320.dp

    /** Vertical padding of the row list inside the container (context menus). */
    val ListPadding = 4.dp

    /** Big and soft, like a floating panel — not M3's tight 3dp component shadow. */
    val ShadowElevation = 16.dp

    /** Content padding for the container of a context menu's row list. */
    val ListPaddingValues = PaddingValues(vertical = ListPadding)

    /** A shade above the panel it opens over, so the menu reads as floating in both modes. */
    val containerColor: Color
        @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh

    /** Hairline edge. The shadow alone is not enough separation in dark mode. */
    val border: BorderStroke
        @Composable get() = BorderStroke(Stroke.hairline, MaterialTheme.colorScheme.outlineVariant)

    /** 13sp compact body — the desktop type scale's menu size. */
    val itemTextStyle: TextStyle
        @Composable get() = MaterialTheme.typography.bodyMedium
}

/**
 * The app's one menu surface. Same drop-in contract as the surfaces in `Dialogs.kt`: it carries the
 * SAME NAME as `androidx.compose.material3.DropdownMenu`, so a call site opts in by changing one
 * import line.
 *
 * With a pointer ([LocalPointerAvailable]) it wears [MenuStyle]; without one it stays on
 * Material3's defaults, which is exactly what the phone shipped before this file existed. Density is
 * the one thing the two platforms genuinely disagree about, and a mouse/touchpad — not a keyboard —
 * is what settles it.
 */
@Composable
fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    // Only while it is actually open: a closed menu is composed all over the app and would
    // otherwise pin every desktop terminal hidden forever.
    if (expanded) ModalHost {}
    // One look on every host (the desktop design); only the row height adapts to touch.
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        shape = MenuStyle.Shape,
        containerColor = MenuStyle.containerColor,
        // Tonal elevation would tint the container a second time on top of the explicit
        // containerColor; the shadow alone carries the "floating" read.
        tonalElevation = 0.dp,
        // Android draws a menu in its own popup window sized to the menu, which clips a big soft
        // shadow at its edges; a touch host gets a tight one that fits inside that window.
        shadowElevation = if (LocalPointerAvailable.current) MenuStyle.ShadowElevation else 3.dp,
        border = MenuStyle.border,
        content = content,
    )
}

/**
 * A menu row. Compact and macOS-flavoured when a pointer is available; plain Material3 (48dp thumb
 * rows) when there is none.
 *
 * Only the parameters the two apps actually pass exist here; M3's `colors` and `contentPadding` are
 * deliberately absent because the whole point is that no call site styles a menu row any more.
 *
 * The pointer branch is a reimplementation rather than a wrapper because M3 pins the row at
 * `sizeIn(minHeight = 48.dp)` and fills the entire width with its indication — the two things that
 * make the menus look wrong on a desktop — and neither is reachable through a parameter. Semantics
 * are unchanged either way: a clickable row carrying the label's text, so every existing
 * `onNodeWithText(...)` / `testTag` assertion still matches.
 */
@Composable
fun DropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    // Same design with or without a pointer; touch only gets a thumb-sized row and a press
    // highlight in place of hover.
    val touch = !LocalPointerAvailable.current
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    // clickable() already feeds hover into the source, so no separate hoverable().
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val active = (hovered || pressed) && enabled
    val contentColor = when {
        !enabled -> cs.onSurfaceVariant.copy(alpha = 0.5f)
        active -> cs.onPrimary
        else -> cs.onSurface
    }
    // Provided AROUND the Row, not inside it: CompositionLocalProvider's content is a plain lambda,
    // and nesting it would drop the RowScope that `weight` needs.
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Row(
            modifier
                .fillMaxWidth()
                .padding(horizontal = MenuStyle.ItemInset, vertical = MenuStyle.ItemGap)
                .clip(MenuStyle.ItemShape)
                .background(if (active) cs.primary else Color.Transparent)
                // No indication: a macOS menu row highlights on hover and then just closes — a
                // ripple expanding under the cursor belongs to touch.
                .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
                .heightIn(min = if (touch) MenuStyle.TouchItemHeight else MenuStyle.ItemHeight)
                .padding(horizontal = MenuStyle.ItemPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                leadingIcon()
                Spacer(Modifier.width(MenuStyle.IconGap))
            }
            Box(Modifier.widthIn(max = MenuStyle.ItemMaxTextWidth)) {
                ProvideTextStyle(MenuStyle.itemTextStyle.copy(color = contentColor)) { text() }
            }
            if (trailingIcon != null) {
                // Weighted so the trailing mark pins to the right edge; the min width keeps a sane
                // gap when the menu is only as wide as its longest label.
                Spacer(Modifier.weight(1f).defaultMinSize(minWidth = MenuStyle.IconGap * 2))
                trailingIcon()
            }
        }
    }
}
