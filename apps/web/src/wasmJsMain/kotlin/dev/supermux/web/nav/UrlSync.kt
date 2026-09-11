package dev.supermux.web.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.supermux.ui.nav.Route
import dev.supermux.ui.shell.ShellUiState
import kotlinx.browser.window
import kotlinx.coroutines.flow.distinctUntilChanged
import org.w3c.dom.events.Event

/** The address bar's path + query, the exact string [pathFor] produces and [parsePath] reads. */
private fun currentPath(): String = window.location.pathname + window.location.search

/**
 * Put [target] on screen. The shell's own `open*` helpers are used rather than raw
 * [ShellUiState.navigate] so a URL lands in the same state a click would: the launcher keeps its
 * draft, Settings remembers its section.
 *
 * [Route.Home] also CLEARS the selection. Without that, navigating back from `/s/<id>` to `/`
 * would leave `selectedId` set, the shell→URL mirror would immediately push `/s/<id>` again, and
 * the browser's Back button would appear stuck.
 */
fun applyTarget(ui: ShellUiState, target: UrlTarget) {
    when (target) {
        is UrlTarget.Session -> {
            ui.navigate(Route.Home)
            ui.selectSession(target.id)
        }
        is UrlTarget.Screen -> when (val r = target.route) {
            Route.Home -> {
                ui.navigate(Route.Home)
                ui.selectedId = null
            }
            is Route.Settings -> ui.openSettings(r.section)
            is Route.NewSession -> ui.openLauncher(r.draftId.ifBlank { null })
            else -> ui.navigate(r)
        }
    }
}

/**
 * Two-way binding between the address bar and [ShellUiState]. The shell's back stack stays the
 * source of truth; the URL mirrors it (`pushState` on change) and `popstate` (browser
 * back/forward, a `sw.js` click, a typed URL) applies the parsed target back onto the shell.
 *
 * Two loops have to be broken:
 *  - The INITIAL url must not get a history entry pushed over it. The shell→URL mirror is gated
 *    behind `initialApplied`, so its first emission can only happen after the address bar has
 *    already been read; if that emission equals the current path (the normal case) nothing is
 *    pushed at all.
 *  - A `popstate` must not push. It applies the target onto the shell, the mirror recomputes the
 *    same path the browser already shows, and the `current != path` guard drops it.
 */
@Composable
fun UrlSync(ui: ShellUiState) {
    var initialApplied by remember(ui) { mutableStateOf(false) }

    // 1. Initial URL → shell, once.
    LaunchedEffect(ui) {
        applyTarget(ui, parsePath(currentPath()))
        initialApplied = true
    }

    // 2. Shell → URL, only after (1) ran.
    LaunchedEffect(ui, initialApplied) {
        if (!initialApplied) return@LaunchedEffect
        snapshotFlow { pathFor(ui.currentRoute, ui.selectedId) }.distinctUntilChanged().collect { path ->
            if (currentPath() != path) window.history.pushState(null, "", path)
        }
    }

    // 3. Browser back/forward → shell.
    DisposableEffect(ui) {
        val handler: (Event) -> Unit = { applyTarget(ui, parsePath(currentPath())) }
        window.addEventListener("popstate", handler)
        onDispose { window.removeEventListener("popstate", handler) }
    }
}
