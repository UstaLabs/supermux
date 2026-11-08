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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntSize
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
 * The toggle: a 30×18 track, a 14 dp knob, accent when on. With [onCheckedChange] it is its own switch (role,
 * state, click); without it, it only draws, because a [KitToggleRow] owns the switch for the whole row.
 */
@Composable
fun KitToggle(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
) {
    val c = kitColors()
    val knobX by animateDpAsState(if (checked) 14.dp else 2.dp, tween(120), label = "kit-toggle-knob")
    val track by animateColorAsState(if (checked) c.accent else c.line, tween(120), label = "kit-toggle-track")
    val interactive = if (onCheckedChange != null) {
        Modifier
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics { if (label != null) contentDescription = label }
            .kitTouchTarget()
    } else {
        Modifier
    }
    Box(modifier.then(interactive).padding(vertical = 3.dp), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier
                .size(30.dp, 18.dp)
                .alpha(if (enabled) 1f else 0.45f)
                .clip(RoundedCornerShape(9.dp))
                .background(track),
        ) {
            Box(Modifier.offset(x = knobX, y = 2.dp).size(14.dp).clip(CircleShape).background(c.knob))
        }
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
    val m = kitMetrics()
    Row(
        modifier
            .border(1.dp, c.line, RoundedCornerShape(7.dp))
            .padding(2.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (o in options) {
            val on = o == selected
            Box(
                Modifier
                    .then(if (fill) Modifier.weight(1f) else Modifier)
                    .height(if (m.phone) 30.dp else 24.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (on) c.wash else Color.Transparent)
                    .selectable(
                        selected = on,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { if (!on) onSelect(o) },
                    )
                    .padding(horizontal = if (m.phone) 9.dp else 10.dp)
                    .then(if (tag != null) Modifier.testTag(tag(o)) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(o),
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
            .height(m.controlHeight)
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
    val c = kitColors()
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val ink = if (enabled) c.accent else c.faint
    Row(
        modifier
            .kitTouchTarget()
            .clickable(interactionSource = src, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 2.dp),
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

/** − value +, clamped to [range]. Tags: `<tag>_minus`, `<tag>_value`, `<tag>_plus`. */
@Composable
fun KitStepper(value: Int, onChange: (Int) -> Unit, range: IntRange, modifier: Modifier = Modifier, tag: String = "stepper") {
    val c = kitColors()
    val m = kitMetrics()
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        StepButton("−", "Smaller", value > range.first, { onChange((value - 1).coerceIn(range)) }, "${tag}_minus")
        Text(
            "$value",
            fontSize = m.value,
            color = c.text,
            modifier = Modifier.widthIn(min = 26.dp).testTag("${tag}_value"),
            textAlign = TextAlign.Center,
        )
        StepButton("+", "Larger", value < range.last, { onChange((value + 1).coerceIn(range)) }, "${tag}_plus")
    }
}

@Composable
private fun StepButton(glyph: String, description: String, enabled: Boolean, onClick: () -> Unit, tag: String) {
    val c = kitColors()
    val m = kitMetrics()
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Box(
        Modifier
            .kitTouchTarget()
            .size(if (m.phone) 32.dp else 24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (hovered && enabled) c.wash else Color.Transparent)
            .clickable(interactionSource = src, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = 15.sp, color = if (enabled) c.dim else c.faint.copy(alpha = 0.4f))
    }
}

/**
 * A hairline slider: a 2 dp track, the filled part in the accent, a 12 dp knob ringed in the accent. Tap or drag
 * on it, ←/→ when focused, or set it through accessibility. Values snap to [step]. [onChangeFinished] runs when a
 * drag ends, a tap lands or a key steps.
 */
@Composable
fun KitSlider(
    value: Float,
    onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    modifier: Modifier = Modifier,
    onChangeFinished: () -> Unit = {},
) {
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
    fun at(x: Float): Float = snap(range.start + (x / width[0]).coerceIn(0f, 1f) * (range.endInclusive - range.start))
    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    Box(
        modifier
            .width(if (m.phone) 120.dp else 150.dp)
            .height(18.dp)
            .onSizeChanged { size: IntSize -> width[0] = size.width.coerceAtLeast(1) }
            .semantics {
                // No role on purpose: Compose has no slider role; range info + setProgress is what screen readers use.
                progressBarRangeInfo = ProgressBarRangeInfo(value, range, steps = ((range.endInclusive - range.start) / step).roundToInt() - 1)
                setProgress { target ->
                    change(snap(target))
                    finished()
                    true
                }
            }
            .focusable(interactionSource = src)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val d = when (e.key) {
                    Key.DirectionRight, Key.DirectionUp -> step
                    Key.DirectionLeft, Key.DirectionDown -> -step
                    else -> return@onKeyEvent false
                }
                change(snap(current + d))
                finished()
                true
            }
            .pointerInput(range, step) {
                detectTapGestures { o ->
                    change(at(o.x))
                    finished()
                }
            }
            .pointerInput(range, step) {
                detectHorizontalDragGestures(onDragEnd = { finished() }) { ch, _ -> change(at(ch.position.x)) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(c.line))
        Box(Modifier.fillMaxWidth(fraction).height(2.dp).clip(RoundedCornerShape(1.dp)).background(c.accent))
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier
                    .offset(x = 6.dp)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(c.knob)
                    .border(if (focused) 2.dp else 1.5.dp, c.accent, CircleShape),
            )
        }
    }
}
