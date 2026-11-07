package dev.supermux.ui.keys

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import dev.supermux.editor.core.KeyChord
import dev.supermux.terminal.compose.terminalShortcutOf

/** What a region of the UI is to the keyboard. [opaque] regions (a terminal, a remote display) take every key they can name. */
enum class KeyContext(val opaque: Boolean = false) {
    Window,
    Chat,
    Files,
    Editor,
    Terminal(opaque = true),
    Display(opaque = true),
    Other,
}

/**
 * A region with a [KeyContext]: whether it holds the focus, and the command handlers registered inside it. Both are
 * snapshot state, so a reader in composition (a menu item's `enabled`) follows them.
 */
class KeyScope internal constructor(val context: KeyContext, val viewId: String?, val parent: KeyScope?) : ScopeView {
    /**
     * Per command, its live handlers, oldest first. Several owners can register one command in one scope (two file
     * panes at the window's root): the newest runs, and when it goes, the one before it runs again.
     */
    private val handlers = mutableStateMapOf<String, List<() -> Boolean>>()
    internal var hasFocus by mutableStateOf(false)
    internal var tracker: KeyScopeTracker? = null

    override val opaque: Boolean get() = context.opaque
    override fun handles(commandId: String): Boolean = !handlers[commandId].isNullOrEmpty()
    override fun keeps(chord: KeyChord): Boolean = context == KeyContext.Terminal && terminalKeeps(chord)

    /** Runs [commandId]'s newest handler here; false when there is none, or it declined. */
    fun run(commandId: String): Boolean = handlers[commandId]?.lastOrNull()?.invoke() == true

    /** The window's root scope. */
    val root: KeyScope get() = parent?.root ?: this

    /** This scope, then each ancestor. */
    fun stack(): List<KeyScope> = generateSequence(this) { it.parent }.toList()

    internal fun put(commandId: String, handler: () -> Boolean) {
        handlers[commandId] = handlers[commandId].orEmpty() + handler
    }

    internal fun remove(commandId: String, handler: () -> Boolean) {
        val rest = handlers[commandId].orEmpty().filter { it !== handler }
        if (rest.isEmpty()) handlers.remove(commandId) else handlers[commandId] = rest
    }
}

/** The terminal's own keys (copy, paste, select all, find, zoom: terminal-compose's TerminalShortcuts), which the app never takes from it. */
fun terminalKeeps(chord: KeyChord): Boolean =
    composeKeyOf(chord.key)?.let { terminalShortcutOf(it, chord.ctrl, chord.shift, chord.alt, chord.meta, hasSelection = true) } != null

/** One window's scopes: which hold the focus, and which was used last. Snapshot state, like [KeyScope]'s. */
class KeyScopeTracker {
    private val scopes = mutableStateListOf<KeyScope>()

    /** The scope used last: the innermost one a pointer went down in, or the one the focus came into. */
    internal var lastActive: KeyScope? by mutableStateOf(null)

    internal fun register(scope: KeyScope) {
        scopes += scope
        scope.tracker = this
    }

    internal fun unregister(scope: KeyScope) {
        scopes -= scope
        scope.tracker = null
        if (lastActive === scope) lastActive = null
    }

    /**
     * The focus came into [scope]: it is now the scope used last, unless that is already a scope inside it. The focus
     * reaches a scope and the ones around it in no set order, and the deeper one counts.
     */
    internal fun noteActive(scope: KeyScope) {
        val current = lastActive
        if (current == null || scope !in current.stack()) lastActive = scope
    }

    /**
     * Where a key lands, deepest scope first: the deepest scope holding the focus, then its ancestors. When only the
     * window's root holds it (a click in a transcript or on a sidebar row leaves it there), the scope used last
     * stands in, minus an opaque scope that has no focus: its keys are not going to it.
     */
    fun stack(): List<KeyScope> {
        val focused = scopes.filter { it.hasFocus }
        val deepest = focused.firstOrNull { f -> focused.none { it.parent === f } }
        if (deepest == null || deepest.parent == null) {
            val used = lastActive?.stack()?.dropWhile { it.opaque && !it.hasFocus }
            if (!used.isNullOrEmpty()) return used
        }
        return deepest?.stack() ?: scopes.firstOrNull { it.parent == null }?.let { listOf(it) }.orEmpty()
    }

    /** True while a terminal or remote display holds the focus. */
    val focusIsOpaque: Boolean get() = stack().firstOrNull()?.let { it.opaque && it.hasFocus } == true
}

/** The nearest enclosing scope. */
val LocalKeyScope = staticCompositionLocalOf<KeyScope?> { null }

/**
 * False inside kept-alive content that is hidden (a workspace in the background, a tab not shown): [RegisterCommand]
 * registers nothing there, so it never competes with what is on screen. The keep-alive hosts provide it.
 */
val LocalKeyScopeActive = compositionLocalOf { true }

internal val LocalKeyScopeTracker = staticCompositionLocalOf<KeyScopeTracker?> { null }

/**
 * A child of the current [LocalKeyScope], registered with this window's tracker while composed. Put [keyScope] on
 * its region and [ProvideKeyScope] around its content.
 */
@Composable
fun rememberKeyScope(context: KeyContext, viewId: String? = null): KeyScope {
    val parent = LocalKeyScope.current
    val tracker = LocalKeyScopeTracker.current
    val scope = remember(parent, context, viewId) { KeyScope(context, viewId, parent) }
    DisposableEffect(scope, tracker) {
        tracker?.register(scope)
        onDispose { tracker?.unregister(scope) }
    }
    return scope
}

/**
 * Tracks whether [scope]'s region holds the focus, and makes it the scope used last when a pointer goes down in it or
 * the focus comes into it. Put it on the region that contains the scope's child scopes; the tracker finds the deepest
 * focused scope through these regions.
 */
fun Modifier.keyScope(scope: KeyScope): Modifier = this
    .onFocusChanged {
        val gained = it.hasFocus && !scope.hasFocus
        scope.hasFocus = it.hasFocus
        if (gained) scope.tracker?.noteActive(scope)
    }
    .pointerInput(scope) {
        // Initial pass, outer before inner: the innermost scope under the pointer is the last to write.
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            scope.tracker?.lastActive = scope
        }
    }

@Composable
fun ProvideKeyScope(scope: KeyScope, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalKeyScope provides scope, content = content)
}

/**
 * Registers [handler] for [command] in [scope] (by default the nearest one) while composed, [enabled] and not hidden
 * ([LocalKeyScopeActive]). The handler returns false to decline; the key then goes on as if unbound. Of several
 * registrations of one command in one scope, the newest runs.
 */
@Composable
fun RegisterCommand(
    command: AppCommand,
    enabled: Boolean = true,
    scope: KeyScope? = LocalKeyScope.current,
    handler: () -> Boolean,
) {
    val latest by rememberUpdatedState(handler)
    val active = LocalKeyScopeActive.current
    DisposableEffect(scope, command.id, enabled, active) {
        if (scope == null || !enabled || !active) return@DisposableEffect onDispose { }
        val registered: () -> Boolean = { latest() }
        scope.put(command.id, registered)
        onDispose { scope.remove(command.id, registered) }
    }
}
