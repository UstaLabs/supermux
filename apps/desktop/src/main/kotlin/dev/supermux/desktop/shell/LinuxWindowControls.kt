// The drawn half of the Linux chrome (LinuxWindowChrome.kt): Adwaita-style window buttons at the
// top-right, the thin resize handles along the edges, and a hairline outline so an undecorated
// window still reads as a window against the desktop.
package dev.supermux.desktop.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import java.awt.Cursor

/** A window button's interaction state. */
enum class WindowButtonState { Idle, Hovered, Pressed }

/**
 * The round background of a window button, from the theme so it follows light and dark.
 * Adwaita keeps a faint disc behind every button and deepens it on hover and press; close is
 * neutral too (GNOME does not paint it red).
 */
fun windowButtonBackground(cs: ColorScheme, state: WindowButtonState): Color = when (state) {
    WindowButtonState.Idle -> cs.surfaceVariant.copy(alpha = 0.6f)
    WindowButtonState.Hovered -> cs.onSurfaceVariant.copy(alpha = 0.16f).compositeOver(cs.surfaceVariant)
    WindowButtonState.Pressed -> cs.onSurfaceVariant.copy(alpha = 0.28f).compositeOver(cs.surfaceVariant)
}

/** The glyph colour on a window button. */
fun windowButtonGlyph(cs: ColorScheme): Color = cs.onSurface

/** The keyboard focus ring around a window button. */
fun windowButtonFocusRing(cs: ColorScheme): Color = cs.primary

/** What a window button currently shows, exposed in semantics so tests can read it. */
data class WindowButtonVisual(val state: WindowButtonState, val focusRing: Boolean)

val WindowButtonVisualKey = SemanticsPropertyKey<WindowButtonVisual>("WindowButtonVisual")
var SemanticsPropertyReceiver.windowButtonVisual by WindowButtonVisualKey

enum class WindowButtonKind(val testTag: String, val label: String) {
    Minimise("linux_window_minimise", "Minimise"),
    Maximise("linux_window_maximise", "Maximise"),
    Restore("linux_window_restore", "Restore"),
    Close("linux_window_close", "Close"),
}

/**
 * Minimise, maximise-or-restore and close, in a row of [LinuxWindowControlsWidth] by
 * [LinuxTitleBarHeight]. Each button is a hole in the band's drag region, so pressing one never
 * moves or maximises the window.
 */
@Composable
fun LinuxWindowControls(
    maximised: Boolean,
    onMinimise: () -> Unit,
    onToggleMaximise: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    pointerEpoch: Int = 0,
) {
    Row(
        modifier
            .width(LinuxWindowControlsWidth)
            .height(LinuxTitleBarHeight)
            .padding(horizontal = LinuxWindowButtonGap)
            .testTag("linux_window_controls"),
        horizontalArrangement = Arrangement.spacedBy(LinuxWindowButtonGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WindowButton(WindowButtonKind.Minimise, onMinimise, pointerEpoch)
        WindowButton(if (maximised) WindowButtonKind.Restore else WindowButtonKind.Maximise, onToggleMaximise, pointerEpoch)
        WindowButton(WindowButtonKind.Close, onClose, pointerEpoch)
    }
}

/**
 * One round button. [pointerEpoch] changes when the window is hidden, iconified or deactivated: a
 * fresh interaction source then drops a hover whose exit Compose never saw (the pointer left with
 * the window), so a reopened window does not show a stale hover.
 */
@Composable
private fun WindowButton(kind: WindowButtonKind, onClick: () -> Unit, pointerEpoch: Int) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember(pointerEpoch) { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val state = when {
        pressed -> WindowButtonState.Pressed
        hovered -> WindowButtonState.Hovered
        else -> WindowButtonState.Idle
    }
    val glyph = windowButtonGlyph(cs)
    Box(
        Modifier
            .size(LinuxWindowButtonSize)
            .macTitleBarNoDragRegion("linux-window-${kind.name}")
            .then(if (focused) Modifier.border(2.dp, windowButtonFocusRing(cs), CircleShape) else Modifier)
            .clip(CircleShape)
            .background(windowButtonBackground(cs, state))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = kind.label
                windowButtonVisual = WindowButtonVisual(state, focusRing = focused)
            }
            .testTag(kind.testTag),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(10.dp)) { drawGlyph(kind, glyph) }
    }
}

/** Adwaita's symbolic glyphs, drawn so they stay crisp at any scale. */
private fun DrawScope.drawGlyph(kind: WindowButtonKind, color: Color) {
    val w = size.width
    val stroke = 1.5.dp.toPx()
    when (kind) {
        WindowButtonKind.Minimise ->
            drawLine(color, Offset(0f, w * 0.75f), Offset(w, w * 0.75f), stroke, StrokeCap.Round)
        WindowButtonKind.Maximise ->
            drawRect(color, Offset(stroke / 2, stroke / 2), Size(w - stroke, w - stroke), style = Stroke(stroke))
        WindowButtonKind.Restore -> {
            val s = w * 0.72f
            // The back square shows only above and to the right of the front one.
            drawLine(color, Offset(w - s, stroke / 2), Offset(w - stroke / 2, stroke / 2), stroke)
            drawLine(color, Offset(w - stroke / 2, stroke / 2), Offset(w - stroke / 2, s), stroke)
            drawRect(color, Offset(stroke / 2, w - s), Size(s - stroke, s - stroke / 2), style = Stroke(stroke))
        }
        WindowButtonKind.Close -> {
            drawLine(color, Offset(0f, 0f), Offset(w, w), stroke, StrokeCap.Round)
            drawLine(color, Offset(w, 0f), Offset(0f, w), stroke, StrokeCap.Round)
        }
    }
}

/** Edge handle thickness; corners take a longer grip so a diagonal resize is easy to hit. */
private val ResizeEdge = 4.dp
private val ResizeCorner = 12.dp

/**
 * Everything the Linux chrome draws over the content: the outline and the edge handles (only
 * while the window is not maximised) and the buttons. Emit it last in the window's root Box.
 */
@Composable
fun BoxScope.LinuxWindowChromeOverlay(install: LinuxWindowChromeInstall) {
    val cs = MaterialTheme.colorScheme
    if (!install.maximised) {
        Box(Modifier.matchParentSize().border(1.dp, cs.outlineVariant))
        ResizeHandles(install.startResize)
    }
    LinuxWindowControls(
        maximised = install.maximised,
        onMinimise = { WindowControlActions.minimise(install.target) },
        onToggleMaximise = { WindowControlActions.toggleMaximise(install.target) },
        onClose = { WindowControlActions.close(install.target) },
        modifier = Modifier.align(Alignment.TopEnd),
        pointerEpoch = install.pointerEpoch,
    )
}

@Composable
private fun BoxScope.ResizeHandles(start: (WmMoveResizeDirection) -> Unit) {
    @Composable
    fun handle(direction: WmMoveResizeDirection, cursor: Int, modifier: Modifier) = Box(
        modifier
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(cursor)))
            .pointerInput(direction) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // The WM resizes with the primary button only (the request names button 1).
                    if (currentEvent.buttons.isPrimaryPressed) {
                        down.consume()
                        start(direction)
                    }
                }
            }
            .testTag("linux_resize_${direction.name}"),
    )
    handle(WmMoveResizeDirection.Top, Cursor.N_RESIZE_CURSOR, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(ResizeEdge))
    handle(WmMoveResizeDirection.Bottom, Cursor.S_RESIZE_CURSOR, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(ResizeEdge))
    handle(WmMoveResizeDirection.Left, Cursor.W_RESIZE_CURSOR, Modifier.align(Alignment.CenterStart).fillMaxHeight().width(ResizeEdge))
    handle(WmMoveResizeDirection.Right, Cursor.E_RESIZE_CURSOR, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(ResizeEdge))
    handle(WmMoveResizeDirection.TopLeft, Cursor.NW_RESIZE_CURSOR, Modifier.align(Alignment.TopStart).size(ResizeCorner))
    handle(WmMoveResizeDirection.TopRight, Cursor.NE_RESIZE_CURSOR, Modifier.align(Alignment.TopEnd).size(ResizeCorner))
    handle(WmMoveResizeDirection.BottomLeft, Cursor.SW_RESIZE_CURSOR, Modifier.align(Alignment.BottomStart).size(ResizeCorner))
    handle(WmMoveResizeDirection.BottomRight, Cursor.SE_RESIZE_CURSOR, Modifier.align(Alignment.BottomEnd).size(ResizeCorner))
}

/**
 * Keeps this element's content out from under the window buttons: when the element sits in the
 * top band flush with the window's right edge (the top-right tab strip), its content is inset by
 * [LocalWindowChromeInsets]' end width. Its own size and background are unchanged, so the strip
 * still runs to the edge behind the buttons. A no-op where the insets are zero.
 */
fun Modifier.avoidWindowControls(): Modifier = this then AvoidWindowControlsElement

private data object AvoidWindowControlsElement : ModifierNodeElement<AvoidWindowControlsNode>() {
    override fun create() = AvoidWindowControlsNode()

    override fun update(node: AvoidWindowControlsNode) = Unit

    override fun InspectorInfo.inspectableProperties() {
        name = "avoidWindowControls"
    }
}

private class AvoidWindowControlsNode :
    Modifier.Node(),
    LayoutModifierNode,
    GlobalPositionAwareModifierNode,
    CompositionLocalConsumerModifierNode {
    // Read in measure: a change re-measures the content.
    private var endInsetPx by mutableIntStateOf(0)

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val inset = if (constraints.hasBoundedWidth) endInsetPx.coerceAtMost(constraints.maxWidth) else 0
        val placeable = measurable.measure(constraints.offset(horizontal = -inset))
        val width = (placeable.width + inset).coerceIn(constraints.minWidth, constraints.maxWidth)
        return layout(width, placeable.height) { placeable.place(0, 0) }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val insets = currentValueOf(LocalWindowChromeInsets)
        val next = endInsetFor(coordinates, insets)
        if (next != endInsetPx) endInsetPx = next
    }

    private fun endInsetFor(coordinates: LayoutCoordinates, insets: ChromeInsets): Int {
        if (insets.end <= 0.dp) return 0
        val density = currentValueOf(LocalDensity)
        val pos = coordinates.positionInRoot()
        val rootWidth = coordinates.findRootCoordinates().size.width
        return if (touchesTopEnd(pos.y, pos.x + coordinates.size.width, rootWidth.toFloat(), with(density) { insets.band.toPx() })) {
            with(density) { insets.end.roundToPx() }
        } else {
            0
        }
    }
}

/** True when an element whose top is at [top] and right edge at [right] reaches the top-right band. */
internal fun touchesTopEnd(top: Float, right: Float, rootWidth: Float, bandPx: Float): Boolean =
    top < bandPx && right >= rootWidth - 1f
