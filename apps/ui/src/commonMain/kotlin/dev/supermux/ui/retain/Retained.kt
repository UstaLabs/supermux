// What survives a configuration change (card #89). See `dev.supermux.state.RetainedUiState` for
// why: an Android recreation drops every composition, and a width-class change rebuilds a surface
// somewhere else in the tree, so `remember` and `rememberSaveable` both lose what the user typed.
package dev.supermux.ui.retain

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.supermux.state.RetainedUiState
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope

/** The fleet's [RetainedUiState], provided by the shell. Null outside one (a test, a preview). */
val LocalRetainedUiState = staticCompositionLocalOf<RetainedUiState?> { null }

/**
 * `remember` that survives an Android recreation and a move to another place in the tree (the
 * Compact↔wide swap). [key] names the value explicitly and must be unique across the app: put the
 * session, workspace or request id in it. Without a [LocalRetainedUiState] this is `remember(key)`.
 */
@Composable
fun <T : Any> rememberRetained(key: String, create: () -> T): T {
    val retained = LocalRetainedUiState.current
    return remember(retained, key) { retained?.getOrPut(key, create) ?: create() }
}

/**
 * Where work runs that must finish even if this screen is gone by then (a save, a spawn, a
 * dictation's last words): the fleet's scope, or this composition's own without a [LocalRetainedUiState].
 */
@Composable
fun rememberRetainedScope(): CoroutineScope = LocalRetainedUiState.current?.scope ?: rememberCoroutineScope()

/**
 * The opening of the page on display, for [rememberRetainedOnPage] (#89). A page whose forms must
 * start empty each time the user opens it (Settings' sections, Add host) gets a new value per
 * opening, kept through a recreation and the Compact↔wide swap. Null: no page, so nothing resets.
 */
val LocalRetainedPage = staticCompositionLocalOf<String?> { null }

/** A new [LocalRetainedPage] value: unique, so no earlier opening's forms match it. */
fun newRetainedPageOpening(): String = Random.nextLong().toString(36)

/**
 * Gives [content] a page opening of its own ([LocalRetainedPage]), saved with the composition: a
 * recreation and the swap keep it, a new opening of the page composes a new one. Put it where only
 * a new opening composes it anew, above any Compact↔wide branch: a nav entry, a dialog's caller.
 */
@Composable
fun RetainedPage(content: @Composable () -> Unit) {
    val opening = rememberSaveable { newRetainedPageOpening() }
    CompositionLocalProvider(LocalRetainedPage provides opening, content = content)
}

/**
 * [rememberRetained] for a form on the page on display: a recreation and the Compact↔wide swap
 * find it again, and the next opening of the page ([LocalRetainedPage]) starts from [create], as a
 * new composition did before #89. One instance per [key], replaced rather than piled up, so a
 * secret typed and then left is dropped at the next opening at the latest, and only ever in memory.
 */
@Composable
fun <T : Any> rememberRetainedOnPage(key: String, create: () -> T): T {
    val opening = LocalRetainedPage.current
    val slot = rememberRetained(key) { PageSlot(opening, create()) }
    val value = remember(slot, opening) {
        if (slot.opening != opening) {
            slot.opening = opening
            slot.value = create()
        }
        slot.value
    }
    @Suppress("UNCHECKED_CAST")
    return value as T
}

/** One [rememberRetainedOnPage] value and the page opening it belongs to. */
private class PageSlot(var opening: String?, var value: Any)
