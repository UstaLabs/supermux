package dev.supermux.android.terminal

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.theme.HapticKind
import dev.supermux.android.theme.Space
import dev.supermux.android.theme.rememberHaptics
import dev.supermux.net.SpecialKey

/** Tri-state of a sticky bar modifier (like iOS Shift): off → armed-for-one-key → locked. */
enum class ModState { OFF, ONCE, LOCKED }

enum class ModKey { CTRL, ALT }

/** A press reported by the key bar up to [TerminalPanel]. (Named [Mod] not `Modifier`
 *  to avoid colliding with Compose's `Modifier`.) */
sealed interface KeyPress {
    data class Mod(val key: ModKey) : KeyPress
    data class Special(val key: SpecialKey) : KeyPress
    data class Printable(val ch: Char) : KeyPress
}

// On-screen key layout. Gaps render as thin dividers between logical groups.
private sealed interface BarKey {
    data class Mod(val key: ModKey, val label: String) : BarKey
    data class Special(val key: SpecialKey, val label: String) : BarKey
    data class Printable(val ch: Char) : BarKey
    data object Gap : BarKey
}

private val KEYS: List<BarKey> = listOf(
    BarKey.Special(SpecialKey.Escape, "Esc"),
    BarKey.Special(SpecialKey.Tab, "Tab"),
    BarKey.Gap,
    BarKey.Mod(ModKey.CTRL, "Ctrl"),
    BarKey.Mod(ModKey.ALT, "Alt"),
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
 * A horizontally-scrollable row of keys the soft keyboard lacks (Esc/Tab/Ctrl/
 * Alt/arrows/…). Purely presentational: it reports each press up to the panel,
 * which owns the modifier state machine and byte-sending. Ctrl/Alt render their
 * tri-state (off / armed-once / locked) so the active modifier is visible.
 */
@Composable
fun TerminalKeyBar(
    ctrl: ModState,
    alt: ModState,
    onPress: (KeyPress) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    Row(
        modifier
            .background(cs.surfaceContainerHigh)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Space.sm, vertical = 6.dp),
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
                    val state = if (key.key == ModKey.CTRL) ctrl else alt
                    KeyButton(
                        label = key.label,
                        active = state != ModState.OFF,
                        locked = state == ModState.LOCKED,
                        mono = false,
                    ) {
                        haptic(HapticKind.Tick)
                        onPress(KeyPress.Mod(key.key))
                    }
                }

                is BarKey.Special ->
                    KeyButton(label = key.label, active = false, locked = false, mono = false) {
                        haptic(HapticKind.Tick)
                        onPress(KeyPress.Special(key.key))
                    }

                is BarKey.Printable ->
                    KeyButton(label = key.ch.toString(), active = false, locked = false, mono = true) {
                        haptic(HapticKind.Tick)
                        onPress(KeyPress.Printable(key.ch))
                    }
            }
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
            .padding(horizontal = 12.dp),
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
