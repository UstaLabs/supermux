// The Settings kit's controls (#97): a thin toggle, a quiet option group, ghost buttons, text actions, a stepper
// and a hairline slider — the prototype's `.tg`, `.seg`, `.gb`, `.ta`, `.stp`, `.sl`. No Material component.
package dev.supermux.ui.settings.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.adaptive.LocalPointerAvailable
import kotlin.math.roundToInt

/**
 * On touch (no pointer) a standalone control's hit target grows to 48 dp without changing how it looks. Rows are
 * their own targets (they are at least 48 dp tall on a phone).
 */
@Composable
@ReadOnlyComposable
internal fun Modifier.kitTouchTarget(): Modifier =
    if (LocalPointerAvailable.current) this else this.minimumInteractiveComponentSize()

/**
 * The kit's focus ring: a 1 dp accent outline while [interactionSource] is focused — the ghost button's and the
 * rail row's outline, on every control. Tab and the arrow keys move it; on the desktop a click focuses a control
 * too (Compose's request-focus-on-click), so the ring also follows the pointer there, as those two always have.
 */
@Composable
fun Modifier.kitFocusRing(interactionSource: InteractionSource, shape: Shape): Modifier {
    val focused by interactionSource.collectIsFocusedAsState()
    val accent = kitColors().accent
    return if (focused) this.border(1.dp, accent, shape) else this
}

/** [kitFocusRing] drawn [outset] outside the node's bounds, taking no room in the layout. */
@Composable
private fun Modifier.kitOutsetFocusRing(interactionSource: InteractionSource, outset: Dp, corner: Dp): Modifier {
    val focused by interactionSource.collectIsFocusedAsState()
    val accent = kitColors().accent
    if (!focused) return this
    return drawWithContent {
        drawContent()
        val o = outset.toPx()
        val stroke = 1.dp.toPx()
        drawRoundRect(
            accent,
            topLeft = Offset(-o + stroke / 2, -o + stroke / 2),
            size = Size(size.width + 2 * o - stroke, size.height + 2 * o - stroke),
            cornerRadius = CornerRadius(corner.toPx()),
            style = Stroke(stroke),
        )
    }
}

/**
 * The toggle: a 30×18 track, a 14 dp knob, accent when on. A switch of its own, named by [label] for screen
 * readers; in a row, [KitToggleRow] owns the switch and draws only [KitToggleTrack].
 */
@Composable
fun KitToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val src = remember { MutableInteractionSource() }
    Box(
        modifier
            .toggleable(
                value = checked,
                interactionSource = src,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics { contentDescription = label }
            .kitTouchTarget()
            .padding(vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        // The ring sits 2 dp outside the track: on the track's own edge it would vanish into the accent when on. It is
        // drawn there, not laid out there, so a standalone toggle ends where a row's own toggle ends.
        KitToggleTrack(checked, enabled, Modifier.kitOutsetFocusRing(src, outset = 2.dp, corner = 11.dp))
    }
}

/** The toggle's track and knob, drawn only: no role, no state, no click. */
@Composable
internal fun KitToggleTrack(checked: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    val c = kitColors()
    val knobX by animateDpAsState(if (checked) 14.dp else 2.dp, tween(120), label = "kit-toggle-knob")
    val track by animateColorAsState(if (checked) c.accent else c.line, tween(120), label = "kit-toggle-track")
    Box(
        modifier
            .size(30.dp, 18.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(9.dp))
            .background(track),
    ) {
        Box(Modifier.offset(x = knobX, y = 2.dp).size(14.dp).clip(CircleShape).background(c.knob))
    }
}

/** A quiet option group: a 1 dp outline, the chosen option on the wash. [fill] spreads it over the width (phones). */
@Composable
fun <T> KitOptions(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fill: Boolean = false,
    tag: ((T) -> String)? = null,
) {
    val c = kitColors()
    Row(
        modifier
            .border(1.dp, c.line, RoundedCornerShape(7.dp))
            .padding(2.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (o in options) {
            // Keyed, so a segment keeps its own state when the options change.
            key(o) {
                OptionSegment(
                    text = label(o),
                    on = o == selected,
                    enabled = enabled,
                    onClick = { if (o != selected) onSelect(o) },
                    modifier = Modifier
                        .then(if (fill) Modifier.weight(1f) else Modifier)
                        .then(if (tag != null) Modifier.testTag(tag(o)) else Modifier),
                )
            }
        }
    }
}

@Composable
private fun OptionSegment(text: String, on: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = kitColors()
    val m = kitMetrics()
    // One per segment for its focus ring; the segment is keyed by its option, so this follows the option.
    val src = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(5.dp)
    Box(
        modifier
            .heightIn(min = if (m.phone) 30.dp else 24.dp)
            .clip(shape)
            .background(if (on) c.wash else Color.Transparent)
            .selectable(
                selected = on,
                interactionSource = src,
                indication = null,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .kitFocusRing(src, shape)
            .padding(horizontal = if (m.phone) 9.dp else 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = m.control,
            color = when {
                !enabled -> c.faint
                on -> c.text
                else -> c.dim
            },
            maxLines = 1,
        )
    }
}

/** The ghost button: 26 dp (32 on phones), a hairline outline, 6 dp corners; [destructive] writes in the error tone. */
@Composable
fun KitGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    leading: ImageVector? = null,
) {
    val c = kitColors()
    val m = kitMetrics()
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    val focused by src.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    val ink = when {
        !enabled -> c.faint
        destructive -> c.danger
        else -> c.text
    }
    Row(
        modifier
            .kitTouchTarget()
            .heightIn(min = m.controlHeight)
            .clip(shape)
            .background(if (enabled && (hovered || pressed)) c.hover else Color.Transparent)
            .border(1.dp, if (focused) c.accent else c.line, shape)
            .clickable(interactionSource = src, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leading != null) Icon(leading, contentDescription = null, tint = ink, modifier = Modifier.size(14.dp))
        Text(text, fontSize = m.control, color = ink, maxLines = 1)
    }
}

/** A text action in the accent tone ("Try again", "Recent events"); underlined while hovered. */
@Composable
fun KitTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: ImageVector? = null,
) {
    TextActionBase(text, onClick, modifier, enabled, leading, touchTarget = true)
}

/**
 * [KitTextAction] with the 48 dp touch target optional: inside a field it must not make the field taller, and
 * Compose's own touch-target extension already widens a small target there.
 */
@Composable
internal fun TextActionBase(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: ImageVector? = null,
    touchTarget: Boolean = true,
) {
    val c = kitColors()
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val ink = if (enabled) c.accent else c.faint
    Row(
        modifier
            .then(if (touchTarget) Modifier.kitTouchTarget() else Modifier)
            .clickable(interactionSource = src, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .kitFocusRing(src, RoundedCornerShape(4.dp))
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (leading != null) Icon(leading, contentDescription = null, tint = ink, modifier = Modifier.size(14.dp))
        Text(
            text,
            fontSize = 12.5.sp,
            color = ink,
            textDecoration = if (hovered && enabled) TextDecoration.Underline else null,
        )
    }
}

/**
 * − value +, clamped to [range]. [label] is the row's label: the buttons are "Decrease ‹label›" and "Increase
 * ‹label›", and the value is announced as it changes. Tags: `<tag>_minus`, `<tag>_value`, `<tag>_plus`.
 */
@Composable
fun KitStepper(
    value: Int,
    onChange: (Int) -> Unit,
    range: IntRange,
    label: String,
    modifier: Modifier = Modifier,
    tag: String = "stepper",
) {
    val c = kitColors()
    val m = kitMetrics()
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        StepButton("−", "Decrease $label", value > range.first, { onChange((value - 1).coerceIn(range)) }, "${tag}_minus")
        Text(
            "$value",
            fontSize = m.value,
            color = c.text,
            modifier = Modifier
                .widthIn(min = 26.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("${tag}_value"),
            textAlign = TextAlign.Center,
        )
        StepButton("+", "Increase $label", value < range.last, { onChange((value + 1).coerceIn(range)) }, "${tag}_plus")
    }
}

@Composable
private fun StepButton(glyph: String, description: String, enabled: Boolean, onClick: () -> Unit, tag: String) {
    val c = kitColors()
    val m = kitMetrics()
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .kitTouchTarget()
            .size(if (m.phone) 32.dp else 24.dp)
            .clip(shape)
            .background(if (hovered && enabled) c.wash else Color.Transparent)
            .clickable(interactionSource = src, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .kitFocusRing(src, shape)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = 15.sp, color = if (enabled) c.dim else c.faint.copy(alpha = 0.4f))
    }
}

/**
 * A hairline slider: a 2 dp track, the filled part in the accent, a 12 dp knob ringed in the accent. Tap or drag
 * on it, ←/→ (Home/End for the ends) when focused, or set it through accessibility, which reads [label] and
 * [valueText]. Values snap to [step], and [onChange] runs only when the snapped value moves. [onChangeFinished]
 * runs when a drag ends or is cancelled, a tap lands, or a key or accessibility moves the value.
 */
@Composable
fun KitSlider(
    value: Float,
    onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    label: String,
    valueText: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onChangeFinished: () -> Unit = {},
) {
    require(step > 0f) { "KitSlider's step must be positive, was $step" }
    require(range.endInclusive > range.start) { "KitSlider's range must go somewhere, was $range" }
    val c = kitColors()
    val m = kitMetrics()
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onChangeFinished)
    // The measured width, kept without causing a recomposition.
    val width = remember { intArrayOf(1) }
    fun snap(v: Float): Float {
        val steps = ((v - range.start) / step).roundToInt()
        return (range.start + steps * step).coerceIn(range.start, range.endInclusive)
    }
    fun at(x: Float): Float = range.start + (x / width[0]).coerceIn(0f, 1f) * (range.endInclusive - range.start)
    // Moves to target, snapped; says whether the value moved.
    fun moveTo(target: Float): Boolean {
        val next = snap(target)
        if (next == current) return false
        change(next)
        return true
    }
    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    val src = remember { MutableInteractionSource() }
    Box(
        modifier
            .width(if (m.phone) 120.dp else 150.dp)
            .height(18.dp)
            .onSizeChanged { size -> width[0] = size.width.coerceAtLeast(1) }
            .semantics {
                // No role on purpose: Compose has no slider role; range info + setProgress is what screen readers use.
                contentDescription = label
                stateDescription = valueText
                progressBarRangeInfo = ProgressBarRangeInfo(value, range, steps = ((range.endInclusive - range.start) / step).roundToInt() - 1)
                if (enabled) {
                    setProgress { target ->
                        if (moveTo(target)) finished()
                        true
                    }
                } else {
                    disabled()
                }
            }
            .focusable(enabled = enabled, interactionSource = src)
            .kitFocusRing(src, RoundedCornerShape(4.dp))
            .onKeyEvent { e ->
                if (!enabled || e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val target = when (e.key) {
                    Key.DirectionRight, Key.DirectionUp -> current + step
                    Key.DirectionLeft, Key.DirectionDown -> current - step
                    Key.MoveHome -> range.start
                    Key.MoveEnd -> range.endInclusive
                    else -> return@onKeyEvent false
                }
                if (moveTo(target)) finished()
                true
            }
            .pointerInput(range, step, enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { o ->
                    moveTo(at(o.x))
                    finished()
                }
            }
            .pointerInput(range, step, enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(onDragEnd = { finished() }, onDragCancel = { finished() }) { ch, _ ->
                    moveTo(at(ch.position.x))
                }
            }
            // Not Modifier.alpha: below 1 it clips, and the knob overhangs the track by half at either end.
            .then(if (enabled) Modifier else Modifier.graphicsLayer(alpha = 0.45f)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(c.line))
        Box(Modifier.fillMaxWidth(fraction).height(2.dp).clip(RoundedCornerShape(1.dp)).background(c.accent))
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier
                    .offset(x = 6.dp)
                    // Unbounded: at the minimum the filled part is 0 wide and would squeeze the knob to nothing.
                    // Aligned to its end and shifted by half, the knob is centred on the value at every value.
                    .wrapContentWidth(Alignment.End, unbounded = true)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(c.knob)
                    .border(1.5.dp, c.accent, CircleShape)
                    .testTag("kit_slider_knob"),
            )
        }
    }
}
