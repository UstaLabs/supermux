package dev.supermux.state

import kotlinx.coroutines.CoroutineScope

/**
 * UI state that must outlive the composition showing it (card #89).
 *
 * An Android rotation, fold, split-screen resize, dark-mode, font-scale or locale change recreates
 * the activity, and everything its composition remembered goes with it. On every host a width-class
 * change (a phone turned to landscape, a Fold opened, an iPad split) rebuilds a surface at a
 * different place in the tree, where neither `remember` nor `rememberSaveable` finds it again. The
 * fleet outlives both (Android's view model keeps it through a recreation), so it keeps this:
 * values keyed EXPLICITLY by a session, workspace or request id, never by position, so the phone
 * layout and the wide one find the same instance.
 *
 * Compose-free on purpose, like the walkthrough states a [HostStore] keeps for `:ui`: it stores
 * whatever holder `:ui` puts in, and [scope] runs the work that must finish even when the screen
 * that started it is gone (a save, a spawn, the last words of a dictation). Read and written on the
 * UI thread only (from composition and from [scope], which runs there on every host).
 */
class RetainedUiState(val scope: CoroutineScope) {
    private val values = HashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getOrPut(key: String, create: () -> T): T = values.getOrPut(key, create) as T
}
