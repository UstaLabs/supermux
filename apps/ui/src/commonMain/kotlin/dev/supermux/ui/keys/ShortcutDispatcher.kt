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
import dev.supermux.editor.compose.keyChordOf
import dev.supermux.editor.core.KeyChord

/** One window's keyboard: resolves each key against [keymap] and its [tracker]'s focus stack, and runs the command. */
class ShortcutDispatcher(val os: KeyOs = currentKeyOs, val keymap: Keymap = DefaultKeymap) {
    val tracker = KeyScopeTracker()

    /**
     * Keys the macOS menu bar runs itself. The menu fires its key equivalent even when Compose already used the key,
     * so these are only swallowed here: the focused terminal never sees them, and nothing runs twice.
     */
    var nativeMenuChords: Set<KeyChord> = emptySet()

    /** Keys whose press the app used: their release is swallowed too. */
    private val taken = HashSet<Key>()

    /** The preview pass, before the focused element. */
    fun onPreview(event: KeyEvent): Boolean {
        if (event.type == KeyEventType.KeyUp) return taken.remove(event.key)
        if (event.type != KeyEventType.KeyDown) return false
        val chord = chordOf(event) ?: return false
        if (chord in nativeMenuChords) return take(event)
        return run(chord, KeyPhase.Preview, event)
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

    private fun run(chord: KeyChord, phase: KeyPhase, event: KeyEvent): Boolean {
        val stack = tracker.stack()
        val hit = resolveKey(chord, stack, phase, os, keymap) ?: return false
        return stack[hit.scopeIndex].run(hit.binding.command) && take(event)
    }

    private fun take(event: KeyEvent): Boolean {
        taken += event.key
        return true
    }

    private fun chordOf(event: KeyEvent): KeyChord? {
        val chord = keyChordOf(event) ?: return null
        // Off Apple, Ctrl+Alt is AltGr on many layouts: a chord that typed a character is that character.
        val cp = event.utf16CodePoint
        if (os == KeyOs.Other && chord.ctrl && chord.alt && cp >= 0x20 && cp != 0x7F && cp != 0xFFFF) return null
        return chord
    }
}

val LocalShortcutDispatcher = staticCompositionLocalOf<ShortcutDispatcher?> { null }

/**
 * The root of a window's content: its [KeyContext.Window] scope, and [dispatcher]'s two passes over every key.
 *
 * Dialogs and popups are layers with their own focus; their keys never reach this node, so app shortcuts are off
 * while one is in front. For the same reason focus must not fall out of the window's content: when nothing here
 * holds it (a `clearFocus()`, a focused element that went away), the root takes it back on the next frame, and the
 * scope pressed last stands in for where the user is ([KeyScopeTracker.stack]).
 */
@Composable
fun ShortcutRoot(dispatcher: ShortcutDispatcher, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val root = remember(dispatcher) { KeyScope(KeyContext.Window, viewId = null, parent = null) }
    DisposableEffect(root) {
        dispatcher.tracker.register(root)
        onDispose { dispatcher.tracker.unregister(root) }
    }
    val focus = remember { FocusRequester() }
    var lost by remember { mutableStateOf(true) }
    LaunchedEffect(lost) {
        if (!lost) return@LaunchedEffect
        withFrameNanos { }
        runCatching { focus.requestFocus() }
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
