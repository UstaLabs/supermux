package dev.supermux.editor.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.editor.core.KeyChord
import dev.supermux.editor.core.commandEnabled
import dev.supermux.editor.core.namedCommand
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** When [EditorAccessories] shows. */
enum class AccessoryVisibility {
    /** While the editor has the focus and a soft keyboard is up (never with a hardware keyboard). */
    AUTO,
    /** Always (a host's own rule; tests). */
    ALWAYS,
}

/**
 * The mobile accessory bar: the keys a soft keyboard lacks, above it, for [view]:
 * `Undo · Redo | Tab · ⇧Tab | ← → ↑ ↓ | Find | hide keyboard`, styled like the terminal's bar.
 *
 * - The host places it (above the keyboard: the bottom of a column padded by the IME insets, as
 *   the sample does); [AccessoryVisibility.AUTO] shows it only while the editor has the focus AND a
 *   soft keyboard is up (its inset at least [MIN_KEYBOARD] tall, so a hardware keyboard's shortcut
 *   strip on an iPad does not count; a browser on a touch device, whose keyboard gives no inset,
 *   counts a focused editor as one with its keyboard up).
 * - The buttons run EDITOR COMMANDS, never key events: Tab, ⇧Tab and the arrows run what that key
 *   is bound to (the state's keymap, then the defaults: a completion list's ↑/↓, a snippet's Tab
 *   work), Undo / Redo / Find the named commands `history.undo`, `history.redo`, `search.open`,
 *   disabled (dimmed, `disabled` for a screen reader) when no plugin provides them or
 *   `commandEnabled` says they cannot run (an empty undo stack).
 * - They never take the focus (taps, not `clickable` / `focusable`): the hidden field keeps it, so
 *   the soft keyboard stays up and the IME keeps its session. The arrows repeat while held.
 * - The trailing button hides the soft keyboard, the focus kept (the terminal's, commit 8b0a6653:
 *   iOS has no back gesture): a tap on the text brings it back.
 */
@Composable
fun EditorAccessories(
    view: EditorView,
    modifier: Modifier = Modifier,
    theme: EditorTheme = EditorTheme.default(),
    visibility: AccessoryVisibility = AccessoryVisibility.AUTO,
) {
    if (visibility == AccessoryVisibility.AUTO) {
        val density = LocalDensity.current
        val ime = WindowInsets.ime.getBottom(density)
        val keyboardUp = with(density) { ime.toDp() } >= MIN_KEYBOARD || (platformInputOnAnyFocus && isTouchFirstPlatform)
        if (!view.focused || !keyboardUp) return
    }
    val canUndo by remember(view) { derivedStateOf { commandEnabled(view.state, UNDO) } }
    val canRedo by remember(view) { derivedStateOf { commandEnabled(view.state, REDO) } }
    val canFind by remember(view) { derivedStateOf { commandEnabled(view.state, FIND) } }
    val keyboard = LocalSoftwareKeyboardController.current
    val haptic = LocalHapticFeedback.current
    val c = AccessoryColors.of(theme)
    fun tick() = runCatching { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    fun key(name: String, shift: Boolean = false) { tick(); runAccessoryKey(view, KeyChord(name, shift = shift)) }
    fun named(id: String) { tick(); runAccessoryCommand(view, id) }
    Row(
        modifier.fillMaxWidth().background(c.bar).padding(horizontal = 8.dp, vertical = 6.dp).testTag(AccessoryTags.BAR),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The keys scroll sideways on a narrow phone; the hide-keyboard key stays pinned at the end.
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccessoryKey(c, "Undo", "Undo", AccessoryTags.UNDO, enabled = canUndo) { named(UNDO) }
            AccessoryKey(c, "Redo", "Redo", AccessoryTags.REDO, enabled = canRedo) { named(REDO) }
            Divider(c)
            AccessoryKey(c, "Tab", "Tab", AccessoryTags.TAB) { key("Tab") }
            AccessoryKey(c, "⇧Tab", "Shift-Tab", AccessoryTags.SHIFT_TAB) { key("Tab", shift = true) }
            Divider(c)
            AccessoryKey(c, "←", "Move left", AccessoryTags.LEFT, repeat = true) { key("ArrowLeft") }
            AccessoryKey(c, "→", "Move right", AccessoryTags.RIGHT, repeat = true) { key("ArrowRight") }
            AccessoryKey(c, "↑", "Move up", AccessoryTags.UP, repeat = true) { key("ArrowUp") }
            AccessoryKey(c, "↓", "Move down", AccessoryTags.DOWN, repeat = true) { key("ArrowDown") }
            Divider(c)
            AccessoryKey(c, "Find", "Find", AccessoryTags.FIND, enabled = canFind) { named(FIND) }
        }
        Divider(c)
        AccessoryKey(c, null, "Hide keyboard", AccessoryTags.HIDE_KEYBOARD) { tick(); keyboard?.hide() }
    }
}

/** The bar's test tags (and the device checks' handles). */
object AccessoryTags {
    const val BAR = "editor_accessory_bar"
    const val UNDO = "editor_key_undo"
    const val REDO = "editor_key_redo"
    const val TAB = "editor_key_tab"
    const val SHIFT_TAB = "editor_key_shift_tab"
    const val LEFT = "editor_key_left"
    const val RIGHT = "editor_key_right"
    const val UP = "editor_key_up"
    const val DOWN = "editor_key_down"
    const val FIND = "editor_key_find"
    const val HIDE_KEYBOARD = "editor_key_hide_keyboard"
}

/** A soft keyboard's inset is at least this tall (an iPad's hardware-keyboard shortcut strip is ~55). */
val MIN_KEYBOARD: Dp = 100.dp

private const val UNDO = "history.undo"
private const val REDO = "history.redo"
private const val FIND = "search.open"

/** The first repeat of a held arrow, then every [REPEAT_EVERY_MS] (a hardware key's feel). */
private const val REPEAT_AFTER_MS = 400L
private const val REPEAT_EVERY_MS = 50L

/** A key's binding (the state's keymap, then the defaults), as a key command runs: guarded and policed. */
internal fun runAccessoryKey(view: EditorView, chord: KeyChord): Boolean = runBindings(view, chord, isApplePlatform)

/** A named command, if some plugin provides it and it can run. */
internal fun runAccessoryCommand(view: EditorView, id: String): Boolean {
    if (!commandEnabled(view.state, id)) return false
    val c = namedCommand(view.state, id) ?: return false
    return view.runningCommand { view.guarded("accessory $id", false) { c.command.run(view) } }
}

/** The bar's colours from the editor's theme: the terminal bar's two surface tones, in the editor's palette. */
private class AccessoryColors(val bar: Color, val key: Color, val ink: Color, val divider: Color) {
    companion object {
        fun of(t: EditorTheme): AccessoryColors {
            fun mix(f: Float) = Color(
                t.background.red + (t.foreground.red - t.background.red) * f,
                t.background.green + (t.foreground.green - t.background.green) * f,
                t.background.blue + (t.foreground.blue - t.background.blue) * f,
            )
            return AccessoryColors(bar = mix(0.07f), key = mix(0.15f), ink = t.foreground, divider = t.foreground.copy(alpha = 0.22f))
        }
    }
}

@Composable
private fun Divider(c: AccessoryColors) {
    Box(Modifier.size(width = 1.dp, height = 22.dp).background(c.divider))
}

/**
 * One key: 40 dp tall, at least 44 wide (the terminal's), a tap (or a held press repeating) runs
 * [onPress]; never focusable. [label] null draws the hide-keyboard icon.
 */
@Composable
private fun AccessoryKey(c: AccessoryColors, label: String?, description: String, tag: String, enabled: Boolean = true, repeat: Boolean = false, onPress: () -> Unit) {
    val latest by rememberUpdatedState(onPress)
    val on by rememberUpdatedState(enabled)
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .height(40.dp)
            .widthIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(c.key.copy(alpha = if (enabled) 1f else 0.45f))
            .pointerInput(repeat) {
                detectTapGestures(onPress = {
                    if (!on) return@detectTapGestures
                    latest()
                    val held = if (repeat) scope.launch {
                        delay(REPEAT_AFTER_MS)
                        while (true) { latest(); delay(REPEAT_EVERY_MS) }
                    } else null
                    tryAwaitRelease()
                    held?.cancel()
                })
            }
            .semantics {
                role = Role.Button
                contentDescription = description
                if (!enabled) disabled()
                onClick(description) { if (on) latest(); on }
            }
            .padding(horizontal = 12.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        val ink = c.ink.copy(alpha = if (enabled) 1f else 0.4f)
        if (label != null) BasicText(label, style = TextStyle(color = ink, fontSize = 15.sp, fontWeight = FontWeight.Medium))
        else KeyboardHideIcon(ink)
    }
}

/** A keyboard with a chevron under it (Material's "keyboard hide"), drawn: the web has no icon font. */
@Composable
private fun KeyboardHideIcon(ink: Color) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.6.dp.toPx())
        drawRoundRect(ink, topLeft = Offset(w * 0.08f, h * 0.12f), size = Size(w * 0.84f, h * 0.5f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = stroke)
        val r = 1.1.dp.toPx()
        for (row in 0..1) for (col in 0..3) drawCircle(ink, r, Offset(w * (0.26f + col * 0.16f), h * (0.28f + row * 0.14f)))
        val chevron = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.36f, h * 0.74f); lineTo(w * 0.5f, h * 0.88f); lineTo(w * 0.64f, h * 0.74f)
        }
        drawPath(chevron, ink, style = stroke)
    }
}
