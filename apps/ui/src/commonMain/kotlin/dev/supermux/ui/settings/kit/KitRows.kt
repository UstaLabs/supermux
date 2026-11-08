// The Settings kit's rows (#97): a label with an optional line under it and the control at the end, a hairline
// under each; section heads, notes, status lines, button rows and the Advanced fold. The prototype's `.row`,
// `.shead`, `.note`, `.status`, `.btns`, `.fold`.
package dev.supermux.ui.settings.kit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.theme.MonoFontFamily

/**
 * One setting. [stacked] puts the control under the label (option rows on phones); [indent] marks a sub-row
 * ("Also on battery"); [dim] greys a row whose parent is off; [hot] paints the label in the accent (a clickable
 * row under the pointer); [divider] draws the hairline under it.
 */
@Composable
fun KitRow(
    label: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    badge: String? = null,
    indent: Boolean = false,
    dim: Boolean = false,
    stacked: Boolean = false,
    hot: Boolean = false,
    divider: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = kitColors()
    val m = kitMetrics()
    val labels: @Composable () -> Unit = {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    fontSize = m.label,
                    color = when {
                        dim -> c.faint
                        hot -> c.accent
                        else -> c.text
                    },
                )
                if (badge != null) Text(badge, fontSize = 11.sp, color = c.faint, modifier = Modifier.padding(start = 6.dp))
            }
            if (description != null) {
                Text(
                    description,
                    fontSize = m.desc,
                    color = if (dim) c.faint else c.dim,
                    lineHeight = (m.desc.value * 1.45f).sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
    Column(modifier.fillMaxWidth()) {
        // On a phone the row is the touch target: at least 48 dp tall.
        val pad = Modifier
            .heightIn(min = if (m.phone) 48.dp else 0.dp)
            .padding(start = if (indent) 18.dp else 0.dp, top = 11.dp, bottom = 11.dp)
        if (stacked) {
            Column(pad.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                labels()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = trailing)
            }
        } else {
            Row(pad.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.weight(1f)) { labels() }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = trailing)
            }
        }
        if (divider) KitHairline()
    }
}

@Composable
fun KitHairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(kitColors().line))
}

/** A row that IS a switch: the whole row toggles, and carries the label for screen readers and tests. */
@Composable
fun KitToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    badge: String? = null,
    indent: Boolean = false,
    divider: Boolean = true,
) {
    KitRow(
        label = label,
        modifier = modifier.toggleable(
            value = checked,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
        description = description,
        badge = badge,
        indent = indent,
        dim = !enabled,
        divider = divider,
    ) { KitToggle(checked, onCheckedChange = null, enabled = enabled) }
}

/** A row with a quiet option group: at its end when wide, under the label (and full width) on a phone. */
@Composable
fun <T> KitOptionsRow(
    label: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    optionLabel: (T) -> String,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    optionTag: ((T) -> String)? = null,
    divider: Boolean = true,
) {
    val phone = kitMetrics().phone
    KitRow(label, modifier, description = description, dim = !enabled, stacked = phone, divider = divider) {
        KitOptions(
            options, selected, onSelect, optionLabel,
            modifier = if (phone) Modifier.fillMaxWidth() else Modifier,
            enabled = enabled,
            fill = phone,
            tag = optionTag,
        )
    }
}

/** A value at the end ("English, Turkish"); with [onClick] the row opens something and shows a chevron. */
@Composable
fun KitValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    onClick: (() -> Unit)? = null,
    mono: Boolean = false,
    dim: Boolean = false,
    indent: Boolean = false,
    divider: Boolean = true,
) {
    val c = kitColors()
    val m = kitMetrics()
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val click = if (onClick != null) {
        Modifier.clickable(interactionSource = src, indication = null, role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    KitRow(label, modifier.then(click), description = description, dim = dim, indent = indent, hot = hovered && onClick != null, divider = divider) {
        Text(
            value,
            fontSize = m.value,
            color = c.dim,
            fontFamily = if (mono) MonoFontFamily else null,
            maxLines = 1,
        )
        if (onClick != null) Icon(KitGlyphs.Chevron, contentDescription = null, tint = c.faint, modifier = Modifier.size(14.dp))
    }
}

/** A label, what it is now, and one ghost button ("Last tidy-up · Never · [Run now]"). */
@Composable
fun KitActionRow(
    label: String,
    button: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    buttonTag: String? = null,
    divider: Boolean = true,
) {
    KitRow(label, modifier, description = description, divider = divider) {
        KitGhostButton(
            button, onClick,
            modifier = if (buttonTag != null) Modifier.testTag(buttonTag) else Modifier,
            enabled = enabled,
            destructive = destructive,
        )
    }
}

@Composable
fun KitStepperRow(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
    range: IntRange,
    modifier: Modifier = Modifier,
    description: String? = null,
    tag: String = "stepper",
    divider: Boolean = true,
) {
    KitRow(label, modifier, description = description, divider = divider) { KitStepper(value, onChange, range, tag = tag) }
}

@Composable
fun KitSliderRow(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    valueText: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    sliderTag: String? = null,
    onChangeFinished: () -> Unit = {},
    divider: Boolean = true,
) {
    val c = kitColors()
    KitRow(label, modifier, description = description, divider = divider) {
        Text(valueText, fontSize = kitMetrics().value, color = c.dim)
        KitSlider(
            value, onChange, range, step,
            modifier = if (sliderTag != null) Modifier.testTag(sliderTag) else Modifier,
            onChangeFinished = onChangeFinished,
        )
    }
}

/** A small-caps head ("CODE INTELLIGENCE") with an optional note at its end ("on studio-mini"). */
@Composable
fun KitSectionHead(
    text: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    noteContent: (@Composable () -> Unit)? = null,
) {
    val c = kitColors()
    Row(
        modifier.fillMaxWidth().padding(top = 26.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.63.sp,
            color = c.faint,
            modifier = Modifier.weight(1f),
        )
        when {
            noteContent != null -> noteContent()
            note != null -> Text(note, fontSize = 11.5.sp, color = c.faint)
        }
    }
}

/** A quiet paragraph under rows. */
@Composable
fun KitNote(text: String, modifier: Modifier = Modifier) {
    val c = kitColors()
    Text(text, fontSize = 12.sp, lineHeight = 18.sp, color = c.dim, modifier = modifier.padding(top = 10.dp))
}

enum class KitDot { Ok, Off, Warn }

@Composable
fun KitDotMark(dot: KitDot, modifier: Modifier = Modifier) {
    val c = kitColors()
    val tone = when (dot) {
        KitDot.Ok -> c.ok
        KitDot.Off -> c.faint
        KitDot.Warn -> c.warn
    }
    Box(modifier.size(6.dp).clip(CircleShape).background(tone))
}

/** "● Running · supermux 0.17.5" */
@Composable
fun KitStatusLine(text: String, modifier: Modifier = Modifier, dot: KitDot? = null) {
    val c = kitColors()
    Row(
        modifier.padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (dot != null) KitDotMark(dot)
        Text(text, fontSize = 12.5.sp, color = c.dim)
    }
}

/** A row of ghost buttons under a section ("Recent events · What we send"). */
@Composable
fun KitButtons(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/**
 * The "Advanced ›" disclosure. Opens itself when the page was opened for one of [anchors] (a search hit inside).
 */
@Composable
fun KitFold(
    label: String,
    modifier: Modifier = Modifier,
    anchors: Set<String> = emptySet(),
    tag: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = kitColors()
    val registry = LocalKitAnchors.current
    var open by rememberSaveable { mutableStateOf(false) }
    val target = registry?.target
    LaunchedEffect(target) { if (target != null && target in anchors) open = true }
    val turn by animateFloatAsState(if (open) 90f else 0f, tween(120), label = "kit-fold")
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { open = !open }
                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                .then(if (tag != null) Modifier.testTag(tag) else Modifier)
                .padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(KitGlyphs.Chevron, contentDescription = null, tint = c.dim, modifier = Modifier.size(14.dp).rotate(turn))
            Text(label, fontSize = 12.5.sp, color = c.dim)
        }
        if (open) content()
    }
}
