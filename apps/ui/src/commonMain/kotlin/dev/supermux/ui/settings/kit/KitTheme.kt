// The Settings kit's tones and sizes (#97). Every colour is a MaterialTheme role as SupermuxTheme maps Theme.kt,
// or a wash of one (the #91 rule), so the kit follows light/dark and never paints a container of its own.
package dev.supermux.ui.settings.kit

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.theme.LocalSemantics

/** The prototype's tones (`proto.css`), as roles. */
@Immutable
data class KitColors(
    /** Labels and values the user reads first. */
    val text: Color,
    /** Descriptions, values, scope lines. */
    val dim: Color,
    /** Section heads, hints, placeholders. */
    val faint: Color,
    /** Hairlines between rows, outlines of buttons and option groups, the toggle's off track. */
    val line: Color,
    val accent: Color,
    val onAccent: Color,
    /** The selected option, the selected rail row, field fills. */
    val wash: Color,
    /** Hover and press. */
    val hover: Color,
    /** A row found by search, for ~1.5 s. */
    val flash: Color,
    val ok: Color,
    val warn: Color,
    val danger: Color,
    /** The toggle's and the slider's knob. */
    val knob: Color,
    /** The detail pane and the phone page. */
    val pane: Color,
    /** The wide rail, sheets. */
    val chrome: Color,
)

@Composable
@ReadOnlyComposable
fun kitColors(): KitColors {
    val cs = MaterialTheme.colorScheme
    val sem = LocalSemantics.current
    val dark = cs.surface.luminance() < 0.5f
    return KitColors(
        text = cs.onSurface,
        dim = cs.onSurfaceVariant,
        faint = cs.onSurfaceVariant.copy(alpha = 0.7f),
        line = cs.outlineVariant,
        accent = cs.primary,
        onAccent = cs.onPrimary,
        wash = cs.onSurface.copy(alpha = if (dark) 0.06f else 0.045f),
        hover = cs.onSurface.copy(alpha = if (dark) 0.035f else 0.03f),
        flash = cs.primary.copy(alpha = if (dark) 0.16f else 0.14f),
        ok = sem.success,
        warn = sem.warning,
        danger = cs.error,
        knob = if (dark) cs.onSurface else cs.surface,
        pane = cs.background,
        chrome = cs.surfaceContainer,
    )
}

/** Sizes that differ between a phone (Compact width) and anything wider: the prototype's two layouts. */
@Immutable
data class KitMetrics(
    val phone: Boolean,
    val label: TextUnit,
    val desc: TextUnit,
    val value: TextUnit,
    val navLabel: TextUnit,
    val control: TextUnit,
    val controlHeight: Dp,
    val pageTitle: TextUnit,
    val pagePadH: Dp,
    val pagePadTop: Dp,
)

private val Phone = KitMetrics(
    phone = true, label = 15.sp, desc = 12.5.sp, value = 13.sp, navLabel = 15.sp, control = 13.sp,
    controlHeight = 32.dp, pageTitle = 19.sp, pagePadH = 18.dp, pagePadTop = 6.dp,
)
private val Wide = KitMetrics(
    phone = false, label = 13.5.sp, desc = 12.sp, value = 12.5.sp, navLabel = 13.sp, control = 12.sp,
    controlHeight = 26.dp, pageTitle = 19.sp, pagePadH = 34.dp, pagePadTop = 26.dp,
)

@Composable
@ReadOnlyComposable
fun kitMetrics(): KitMetrics = if (LocalWindowWidthClass.current == WindowWidthClass.Compact) Phone else Wide

/**
 * What "this device" is called on a page: "computer" where supermux can host (desktop), "phone" on a compact
 * touch screen, otherwise "device" (a tablet, the web on a wide window).
 */
@Composable
@ReadOnlyComposable
fun kitDeviceNoun(): String = when {
    LocalPlatform.current.caps.localBroker -> "computer"
    LocalWindowWidthClass.current == WindowWidthClass.Compact && !LocalPointerAvailable.current -> "phone"
    else -> "device"
}
