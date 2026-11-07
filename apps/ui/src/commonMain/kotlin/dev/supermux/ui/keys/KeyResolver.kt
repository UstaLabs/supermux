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

    /** Whether this scope keeps [chord] for itself even though the app binds it (a terminal's own copy, paste, find, zoom). */
    fun keeps(chord: KeyChord): Boolean = false
}

/** The binding to run, and the index in the stack of the scope whose handler runs it. */
data class KeyHit(val binding: AppBinding, val scopeIndex: Int)

/**
 * The binding [chord] runs in [phase], or null. [stack] is deepest first.
 *
 * The deepest scope with a handler for one of the chord's bindings wins, and that hit runs in one phase only, so a
 * handler that declines is never tried twice for one press: the key goes on as if unbound. It runs in
 * [KeyPhase.Preview] where the app must go first: while a terminal or remote display has the focus, a key that
 * [passesOwner] and the owner does not [keep][ScopeView.keeps] (the owner would otherwise swallow it); anywhere else
 * an [AppBinding.appFirst] key. Every other hit runs in [KeyPhase.Bubble], so the focused element gets first refusal.
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
        candidates.firstOrNull { scope.handles(it.commandId) }?.let { KeyHit(it, i) }
    } ?: return null
    // A hit belongs to one phase only, so a handler that declines is never tried twice for one press.
    val focused = stack.first()
    val early = if (focused.opaque) passesOwner(hit.binding, os) && !focused.keeps(chord) else hit.binding.appFirst
    return hit.takeIf { (phase == KeyPhase.Preview) == early }
}

/** Whether [binding] reaches the app past a focused terminal or remote display: on Apple every ⌘ key and no other, elsewhere only [AppBinding.overOwner] keys. */
fun passesOwner(binding: AppBinding, os: KeyOs): Boolean = if (os == KeyOs.Apple) binding.apple?.meta == true else binding.overOwner
