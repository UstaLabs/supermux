// Cluster G3: the accessory key bar is SHARED. It was Android's (`android/terminal/TerminalKeyBar.kt`)
// and moved here verbatim — the tri-state machine it renders already lived in `:ui`
// (`TerminalKeySink`, cluster G1), so nothing about the bar was ever Android-specific. Its call
// sites gate it on `LocalInputMode == Touch` (a mouse-driven client has the real keys).
package dev.supermux.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.theme.HapticKind
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.rememberHaptics
import dev.supermux.net.SpecialKey

// On-screen key layout. Gaps render as thin dividers between logical groups.
private sealed interface BarKey {
    data class Mod(val key: TerminalModKey, val label: String) : BarKey
    data class Special(val key: SpecialKey, val label: String) : BarKey
    data class Printable(val ch: Char) : BarKey
    data object Gap : BarKey
}

private val KEYS: List<BarKey> = listOf(
    BarKey.Special(SpecialKey.Escape, "Esc"),
    BarKey.Special(SpecialKey.Tab, "Tab"),
    BarKey.Gap,
    BarKey.Mod(TerminalModKey.CTRL, "Ctrl"),
    BarKey.Mod(TerminalModKey.ALT, "Alt"),
    BarKey.Gap,
    BarKey.Special(SpecialKey.ArrowLeft, "←"),
    BarKey.Special(SpecialKey.ArrowDown, "↓"),
    BarKey.Special(SpecialKey.ArrowUp, "↑"),
    BarKey.Special(SpecialKey.ArrowRight, "→"),
    BarKey.Gap,
    BarKey.Special(SpecialKey.Home, "Home"),
    BarKey.Special(SpecialKey.End, "End"),
    BarKey.Special(SpecialKey.PageUp, "PgUp"),
    BarKey.Special(SpecialKey.PageDown, "PgDn"),
    BarKey.Gap,
    BarKey.Printable('|'),
    BarKey.Printable('~'),
    BarKey.Printable('/'),
    BarKey.Printable('-'),
)

/**
 * The bar bound to ONE terminal surface's [TerminalKeySink] — the shape every call site uses.
 *
 * The sink belongs to the surface, so a bar drawn outside the pane's own subtree (pinned above the
 * soft keyboard, as [TerminalTabs] draws it) still types into that pane's pty and shares one
 * modifier state with the grid's real keyboard.
 */
@Composable
fun TerminalKeyBar(keys: TerminalKeySink, modifier: Modifier = Modifier) {
    TerminalKeyBar(
        ctrl = keys.ctrl,
        alt = keys.alt,
        onPress = { keys.press(it) },
        onHideKeyboard = { keys.hideKeyboard() },
        modifier = modifier,
    )
}

/**
 * A horizontally-scrollable row of keys the soft keyboard lacks (Esc/Tab/Ctrl/
 * Alt/arrows/…), plus a trailing "hide keyboard" button. Purely presentational: it reports each
 * press up to its caller, which owns the modifier state machine and byte-sending. Ctrl/Alt render
 * their tri-state (off / armed-once / locked) so the active modifier is visible.
 *
 * The bar is drawn only under [dev.supermux.ui.adaptive.InputMode.Touch] (see [TerminalPane] /
 * `TerminalTabs`), so [onHideKeyboard] never appears on desktop or web, where there is no soft
 * keyboard to hide. It is shown on Android too, alongside Esc/Tab/Ctrl — even though the system
 * back gesture already dismisses the IME there — because the bar has no other per-OS branch and a
 * redundant, always-reachable button is cheaper to reason about (and to test) than teaching this
 * row which touch platform it is running on.
 */
@Composable
fun TerminalKeyBar(
    ctrl: TerminalModState,
    alt: TerminalModState,
    onPress: (TerminalKey) -> Unit,
    onHideKeyboard: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    Row(
        modifier
            .background(cs.surfaceContainerHigh)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Space.sm, vertical = 6.dp)
            .testTag("terminal_key_bar"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KEYS.forEach { key ->
            when (key) {
                is BarKey.Gap ->
                    Box(
                        Modifier
                            .size(width = 1.dp, height = 22.dp)
                            .background(cs.outlineVariant),
                    )

                is BarKey.Mod -> {
                    val state = if (key.key == TerminalModKey.CTRL) ctrl else alt
                    KeyButton(
                        label = key.label,
                        active = state != TerminalModState.OFF,
                        locked = state == TerminalModState.LOCKED,
                        mono = false,
                    ) {
                        haptic.perform(HapticKind.Tick)
                        onPress(TerminalKey.Mod(key.key))
                    }
                }

                is BarKey.Special ->
                    KeyButton(label = key.label, active = false, locked = false, mono = false) {
                        haptic.perform(HapticKind.Tick)
                        onPress(TerminalKey.Special(key.key))
                    }

                is BarKey.Printable ->
                    KeyButton(label = key.ch.toString(), active = false, locked = false, mono = true) {
                        haptic.perform(HapticKind.Tick)
                        onPress(TerminalKey.Printable(key.ch))
                    }
            }
        }
        // Trailing: dismiss the soft keyboard. A divider first, same as every other logical group
        // above — this one is not a pty key at all, and the gap says so.
        Box(
            Modifier
                .size(width = 1.dp, height = 22.dp)
                .background(cs.outlineVariant),
        )
        KeyIconButton(icon = Icons.Filled.KeyboardHide, contentDescription = "Hide keyboard") {
            haptic.perform(HapticKind.Tick)
            onHideKeyboard()
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    active: Boolean,
    locked: Boolean,
    mono: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .height(40.dp)
            .widthIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) cs.primary else cs.surfaceContainerHighest)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp)
            // Per-key tag (new in G3): the shared bar is now driven by tests through the strip.
            .testTag("terminal_key_$label"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (active) cs.onPrimary else cs.onSurface,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
        // Lock pip: modifier held (locked) vs armed for a single key (once).
        if (locked) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 3.dp, end = 3.dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(cs.onPrimary),
            )
        }
    }
}

/**
 * An accessory key whose face is an icon rather than a label — same box, size and touch target as
 * [KeyButton], for the one bar entry ([TerminalKeyBar]'s "hide keyboard") that has no character or
 * [TerminalKey] to render as text.
 */
@Composable
private fun KeyIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .height(40.dp)
            .widthIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(cs.surfaceContainerHighest)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp)
            .testTag("terminal_key_hide_keyboard"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = cs.onSurface, modifier = Modifier.size(20.dp))
    }
}
