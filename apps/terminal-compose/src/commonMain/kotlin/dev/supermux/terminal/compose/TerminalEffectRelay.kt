package dev.supermux.terminal.compose

import androidx.compose.runtime.staticCompositionLocalOf
import dev.supermux.terminal.TerminalEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The host's bridge from engine effects to this surface.
 *
 * The HOST creates the session — it owns the transport, and `Response`/`Input` bytes are its to
 * write — so the composable never sees effects on its own. A host that wants [Terminal]'s `onTitle`
 * callback to fire installs a relay:
 *
 * ```kotlin
 * val relay = remember { TerminalEffectRelay() }
 * val session = remember { TerminalSession.open(size, effects = relay.wrap(::writeToPty)) }
 * CompositionLocalProvider(LocalTerminalEffects provides relay) {
 *     Terminal(session, onTitle = { tab.title = it })
 * }
 * ```
 *
 * Without one, `onTitle` simply never fires — the surface reports only what it is actually told.
 *
 * [wrap] keeps the host's own consumer in the chain and never changes ordering: effects still run
 * on the session's owner coroutine, in queue order.
 */
class TerminalEffectRelay {
    private val titleState = MutableStateFlow<String?>(null)

    /** The newest OSC 0/2 window title, or null until the program sets one. */
    val title: StateFlow<String?> get() = titleState

    /** Feed one effect in. Safe from the session's owner coroutine; never blocks. */
    fun emit(effect: TerminalEffect) {
        if (effect is TerminalEffect.Title) titleState.value = effect.value
    }

    /** A consumer that records what this relay cares about and forwards everything to [downstream]. */
    fun wrap(downstream: (TerminalEffect) -> Unit): (TerminalEffect) -> Unit = { effect ->
        emit(effect)
        downstream(effect)
    }
}

/** The relay [Terminal] reads titles from; the default one never produces anything. */
val LocalTerminalEffects = staticCompositionLocalOf { TerminalEffectRelay() }
