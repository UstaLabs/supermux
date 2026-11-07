package dev.supermux.ui.keys

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

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

/** A region with a [KeyContext]: whether it holds the focus, and the command handlers registered inside it. */
class KeyScope internal constructor(val context: KeyContext, val viewId: String?, val parent: KeyScope?) : ScopeView {
    private val handlers = LinkedHashMap<String, () -> Boolean>()
    internal var hasFocus = false
    internal var tracker: KeyScopeTracker? = null

    override val opaque: Boolean get() = context.opaque
    override fun handles(commandId: String): Boolean = commandId in handlers

    /** Runs [commandId]'s handler here; false when there is none, or it declined. */
    fun run(commandId: String): Boolean = handlers[commandId]?.invoke() == true

    /** The window's root scope. */
    val root: KeyScope get() = parent?.root ?: this

    /** This scope, then each ancestor. */
    fun stack(): List<KeyScope> = generateSequence(this) { it.parent }.toList()

    internal fun put(commandId: String, handler: () -> Boolean) {
        handlers[commandId] = handler
    }

    internal fun remove(commandId: String, handler: () -> Boolean) {
        if (handlers[commandId] === handler) handlers.remove(commandId)
    }
}

/** One window's scopes: which hold the focus, and which a pointer went down in last. */
class KeyScopeTracker {
    private val scopes = ArrayList<KeyScope>()
    internal var lastPressed: KeyScope? = null

    internal fun register(scope: KeyScope) {
        scopes += scope
        scope.tracker = this
    }

    internal fun unregister(scope: KeyScope) {
        scopes -= scope
        if (lastPressed === scope) lastPressed = null
    }

    /**
     * Where a key lands, deepest scope first: the deepest scope holding the focus, then its ancestors. When only the
     * window's root holds it (a click in a transcript or on a sidebar row leaves it there), the scope pressed last
     * stands in, minus an opaque scope that has no focus: its keys are not going to it.
     */
    fun stack(): List<KeyScope> {
        val focused = scopes.filter { it.hasFocus }
        val deepest = focused.firstOrNull { f -> focused.none { it.parent === f } }
        if (deepest == null || deepest.parent == null) {
            val pressed = lastPressed?.stack()?.dropWhile { it.opaque && !it.hasFocus }
            if (!pressed.isNullOrEmpty()) return pressed
        }
        return deepest?.stack() ?: scopes.firstOrNull { it.parent == null }?.let { listOf(it) }.orEmpty()
    }

    /** True while a terminal or remote display holds the focus. */
    val focusIsOpaque: Boolean get() = stack().firstOrNull()?.let { it.opaque && it.hasFocus } == true
}

/** The nearest enclosing scope. */
val LocalKeyScope = staticCompositionLocalOf<KeyScope?> { null }

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

/** Tracks whether [scope]'s region holds the focus, and makes it the scope pressed last when a pointer goes down in it. */
fun Modifier.keyScope(scope: KeyScope): Modifier = this
    .onFocusChanged { scope.hasFocus = it.hasFocus }
    .pointerInput(scope) {
        // Initial pass, outer before inner: the innermost scope under the pointer is the last to write.
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            scope.tracker?.lastPressed = scope
        }
    }

@Composable
fun ProvideKeyScope(scope: KeyScope, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalKeyScope provides scope, content = content)
}

/**
 * Registers [handler] for [command] in [scope] (by default the nearest one) while composed and [enabled]. The
 * handler returns false to decline; the key then goes on as if unbound.
 */
@Composable
fun RegisterCommand(
    command: AppCommand,
    enabled: Boolean = true,
    scope: KeyScope? = LocalKeyScope.current,
    handler: () -> Boolean,
) {
    val latest by rememberUpdatedState(handler)
    DisposableEffect(scope, command.id, enabled) {
        if (scope == null || !enabled) return@DisposableEffect onDispose { }
        val registered: () -> Boolean = { latest() }
        scope.put(command.id, registered)
        onDispose { scope.remove(command.id, registered) }
    }
}
