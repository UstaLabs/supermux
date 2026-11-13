package dev.supermux.ui.keys

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalWindowInfo
import dev.supermux.editor.compose.keyChordOf
import dev.supermux.editor.core.KeyChord
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * One window's keyboard: resolves each key against [keymap] and its [tracker]'s focus stack, and runs the command.
 * [timeSource] times held keys and the macOS menu's echoes; a test passes a `TestTimeSource`.
 */
class ShortcutDispatcher(
    val os: KeyOs = currentKeyOs,
    val keymap: Keymap = DefaultKeymap,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    val tracker = KeyScopeTracker()

    /**
     * A native menu bar's key equivalents. The menu fires one on its own: macOS's even after Compose used the key (the
     * JDK hands the key to Java first, then lets the menu act), Swing's window menu whenever Compose left the key. So a
     * menu item's action may be the echo of a key press the dispatcher already judged. [onWindowKey] notes the presses
     * of these keys, and [invokeFromMenu] ignores the echo.
     */
    var nativeMenuChords: Set<KeyChord> = emptySet()

    /** A key whose press the app used: its [chord], the character it types (-1 for none), when it last went down. */
    private class Taken(val chord: KeyChord, val char: Int, var pressedAt: TimeMark)

    /** The keys held down whose press the app used: their auto-repeat, typed echo and release are the app's too. */
    private val taken = HashMap<Key, Taken>()

    /** When each [nativeMenuChords] key was last pressed in this window. */
    private val menuKeyPresses = HashMap<KeyChord, TimeMark>()

    /** The preview pass, before the focused element. */
    fun onPreview(event: KeyEvent): Boolean {
        if (event.type == KeyEventType.KeyUp) return taken.remove(event.key) != null
        if (event.type != KeyEventType.KeyDown) {
            // A typed character (AWT's KEY_TYPED). Linux AWT follows a used Ctrl+, Ctrl+/ or Ctrl+. with the character
            // it types, Ctrl and all, and a text field would type it.
            val char = printableChar(event)
            return char != -1 && taken.values.any { it.char == char }
        }
        val chord = chordOf(event)
        val held = taken[event.key]
        if (held != null) {
            // The same chord again before its release is the key's auto-repeat: the command ran on the press. Repeats
            // come every few tens of ms, after a delay of at most ~2 s; an entry older than that is a release that
            // went elsewhere (a dialog the command opened took it).
            if (held.chord == chord && held.pressedAt.elapsedNow() < 2.seconds) {
                held.pressedAt = timeSource.markNow()
                return true
            }
            // Another chord, or that stale entry: the release went elsewhere, and this is a new press.
            taken.remove(event.key)
        }
        return chord != null && run(chord, KeyPhase.Preview, event)
    }

    /** The bubble pass, after the focused element left the key unused. */
    fun onBubble(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val chord = chordOf(event) ?: return false
        return run(chord, KeyPhase.Bubble, event)
    }

    /** Runs [command] where the focus is, as a menu item does. False when nothing there can run it. */
    fun invoke(command: AppCommand): Boolean =
        tracker.stack().firstOrNull { it.handles(command.id) }?.run(command.id) == true

    /**
     * Whether [invoke] would find a handler for [command] where the focus is. Observable: it reads snapshot state, so
     * a menu item's `enabled` computed from it follows the focus and the registrations.
     */
    fun canInvoke(command: AppCommand): Boolean = tracker.stack().any { it.handles(command.id) }

    /**
     * For the desktop window's own preview hook, which sees every key before Compose sends it to a layer (a dialog's
     * keys too): notes a press of a [nativeMenuChords] key, for [invokeFromMenu].
     */
    fun onWindowKey(event: KeyEvent) {
        if (event.type != KeyEventType.KeyDown) return
        val chord = keyChordOf(event) ?: return
        if (chord in nativeMenuChords) menuKeyPresses[chord] = timeSource.markNow()
    }

    /**
     * A menu item's action. Within a second of a press of one of [command]'s keys it is the macOS menu echoing that
     * press, which the dispatcher already judged (ran it, or left it to a dialog or the focused element): ignored, so
     * false. Otherwise it is a click, and [command] runs where the focus is.
     */
    fun invokeFromMenu(command: AppCommand): Boolean {
        val echoed = keymap.chordsFor(command, os).firstOrNull { c -> menuKeyPresses[c]?.let { it.elapsedNow() < 1.seconds } == true }
        if (echoed == null) return invoke(command)
        menuKeyPresses.remove(echoed)
        return false
    }

    /** Forgets the keys held down, for a window that lost the focus: their releases go to another window. */
    fun forgetHeldKeys() {
        taken.clear()
        menuKeyPresses.clear()
    }

    private fun run(chord: KeyChord, phase: KeyPhase, event: KeyEvent): Boolean {
        val stack = tracker.stack()
        val hit = resolveKey(chord, stack, phase, os, keymap) ?: return false
        if (!stack[hit.scopeIndex].run(hit.binding.commandId)) return false
        taken[event.key] = Taken(chord, printableChar(event), timeSource.markNow())
        return true
    }

    private fun chordOf(event: KeyEvent): KeyChord? {
        val chord = keyChordOf(event) ?: return null
        // Off Apple, Ctrl+Alt is AltGr on many layouts: a chord that typed a character is that character.
        if (os == KeyOs.Other && chord.ctrl && chord.alt && printableChar(event) != -1) return null
        return chord
    }

    /** The character [event] types, or -1: control characters, DEL and AWT's CHAR_UNDEFINED type none. */
    private fun printableChar(event: KeyEvent): Int =
        event.utf16CodePoint.takeIf { it >= 0x20 && it != 0x7F && it != 0xFFFF } ?: -1
}

val LocalShortcutDispatcher = staticCompositionLocalOf<ShortcutDispatcher?> { null }

/**
 * The root of a window's content: its [KeyContext.Window] scope, and [dispatcher]'s two passes over every key.
 *
 * Focusable dialogs and popups (Compose layers with their own focus) keep their keys: they never reach this node, so
 * app shortcuts are off while one is in front. For the same reason focus must not fall out of the window's content:
 * when nothing here holds it (a `clearFocus()`, a focused element that went away), the root takes it back on the next
 * frame, and the scope used last stands in for where the user is ([KeyScopeTracker.stack]).
 */
@Composable
fun ShortcutRoot(dispatcher: ShortcutDispatcher, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val root = remember(dispatcher) { KeyScope(KeyContext.Window, viewId = null, parent = null) }
    DisposableEffect(root) {
        dispatcher.tracker.register(root)
        onDispose { dispatcher.tracker.unregister(root) }
    }
    // A key held down when the window loses the focus is released in another window: its release never comes here.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) { if (!windowFocused) dispatcher.forgetHeldKeys() }
    val focus = remember { FocusRequester() }
    var lost by remember { mutableStateOf(true) }
    LaunchedEffect(lost) {
        if (!lost) return@LaunchedEffect
        withFrameNanos { }
        // Something may have taken the focus during that frame (an autofocused field): only an empty window is reclaimed.
        if (lost) runCatching { focus.requestFocus() }
    }
    CompositionLocalProvider(
        LocalKeyScopeTracker provides dispatcher.tracker,
        LocalShortcutDispatcher provides dispatcher,
        LocalKeyScope provides root,
    ) {
        Box(
            modifier
                .onPreviewKeyEvent(dispatcher::onPreview)
                .onKeyEvent(dispatcher::onBubble)
                .keyScope(root)
                .onFocusChanged { lost = !it.hasFocus }
                .focusRequester(focus)
                .focusable(),
            content = content,
        )
    }
}
