package dev.supermux.ui.keys

import dev.supermux.editor.core.KeyChord

/** [Preview]: before the focused element sees the key. [Bubble]: after it left the key unused. */
enum class KeyPhase { Preview, Bubble }

/** One scope of the focus stack, as the resolver sees it. */
interface ScopeView {
    /** A terminal or remote display: it takes every key it can name, so the app claims its own keys there first. */
    val opaque: Boolean

    /** Whether a handler for [commandId] is registered here right now. */
    fun handles(commandId: String): Boolean
}

/** The binding to run, and the index in the stack of the scope whose handler runs it. */
data class KeyHit(val binding: AppBinding, val scopeIndex: Int)

/**
 * The binding [chord] runs in [phase], or null. [stack] is deepest first.
 *
 * The deepest scope with a handler for one of the chord's bindings wins. In [KeyPhase.Bubble] it runs. In
 * [KeyPhase.Preview] it runs only where the app must go first: while a terminal or remote display has the focus, a
 * key that [passesOwner] (the owner would otherwise swallow it); anywhere else an [AppBinding.appFirst] key. Every
 * other key waits for the bubble, so the focused text box or editor gets first refusal.
 */
fun resolveKey(
    chord: KeyChord,
    stack: List<ScopeView>,
    phase: KeyPhase,
    os: KeyOs,
    keymap: Keymap = DefaultKeymap,
): KeyHit? {
    val candidates = keymap.bindingsFor(chord, os)
    if (candidates.isEmpty()) return null
    val hit = stack.withIndex().firstNotNullOfOrNull { (i, scope) ->
        candidates.firstOrNull { scope.handles(it.command) }?.let { KeyHit(it, i) }
    } ?: return null
    return when (phase) {
        KeyPhase.Bubble -> hit
        KeyPhase.Preview -> hit.takeIf {
            if (stack.first().opaque) passesOwner(it.binding, chord, os) else it.binding.appFirst
        }
    }
}

/** Whether [binding] reaches the app past a focused terminal or remote display: on Apple every ⌘ key, elsewhere only [AppBinding.overOwner] keys. */
fun passesOwner(binding: AppBinding, chord: KeyChord, os: KeyOs): Boolean =
    binding.overOwner || (os == KeyOs.Apple && chord.meta)
