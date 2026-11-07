// What survives a configuration change (card #89). See `dev.supermux.state.RetainedUiState` for
// why: an Android recreation drops every composition, and a width-class change rebuilds a surface
// somewhere else in the tree, so `remember` and `rememberSaveable` both lose what the user typed.
package dev.supermux.ui.retain

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import dev.supermux.state.RetainedUiState
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
