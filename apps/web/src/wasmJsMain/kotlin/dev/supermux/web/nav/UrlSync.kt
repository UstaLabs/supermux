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
                // BOTH selections, because the sidebar's archived fold is a second highlight:
                // leaving it set would paint an archived workspace as "open" on a bare `/`.
                ui.selectedId = null
                ui.selectedArchivedWorkspaceId = null
            }
            is Route.Settings -> ui.openSettings(r.section)
            is Route.NewSession -> ui.openLauncher(r.draftId.ifBlank { null })
            else -> ui.navigate(r)
        }
    }
}

/** The path the address bar shows while the first-run setup wizard owns the screen. */
const val SETUP_PATH = "/setup"

/**
 * Should [UrlSync]'s one-shot "address bar → shell" apply run for [path]?
 *
 * No while the wizard is up ([enabled] false) — the shell is not on screen to apply anything to.
 * And no for the wizard's OWN path once the shell finally mounts: the Done step calls
 * `ui.openLauncher()` and only then flips `onboarded`, so by the time this runs the shell already
 * holds the destination the user asked for (`/new`). `parsePath("/setup")` is [Route.Home] (the
 * address bar is user input and unknown paths go Home — see [parsePath]), so applying it here
 * would throw that launcher away and land on `/`. The shell→URL mirror rewrites `/setup` to the
 * real path a frame later either way.
 *
 * Pure, and tested as such: the decision is the part worth pinning, and it needs no DOM.
 */
fun shouldApplyInitialUrl(path: String, enabled: Boolean): Boolean =
    enabled && path.substringBefore('?').trimEnd('/') != SETUP_PATH

/**
 * Two-way binding between the address bar and [ShellUiState]. The shell's back stack stays the
 * source of truth; the URL mirrors it (`pushState` on a new destination, `replaceState` when the
 * path is only being normalised) and `popstate` (browser
 * back/forward, a `sw.js` click, a typed URL) applies the parsed target back onto the shell.
 *
 * Two loops have to be broken:
 *  - The INITIAL url must not get a history entry pushed over it. The shell→URL mirror is gated
 *    behind `initialApplied`, so its first emission can only happen after the address bar has
 *    already been read; if that emission equals the current path (the normal case) nothing is
 *    pushed at all.
 *  - A `popstate` must not push. It applies the target onto the shell, the mirror recomputes the
 *    same path the browser already shows, and the `current != path` guard drops it.
 *
 * Two flags, not one, because there are THREE states and only the middle one writes a URL:
 *  - [enabled] false — no shell on screen. Nothing is applied, mirrored, or listened for: there is
 *    no `ShellUiState` worth driving, and the initial apply is DEFERRED to the moment the shell
 *    mounts. This is why the composable is hoisted above the wizard/shell branch rather than
 *    living inside the shell's: it must see the wizard in order to stay out of its way.
 *  - [wizard] true (and [enabled] false) — the wizard owns the screen, so the address bar reads
 *    [SETUP_PATH] and a reload during setup comes back to setup.
 *  - neither — `onboarded` has not arrived yet. The URL is LEFT ALONE. Stamping `/setup` here
 *    would eat the deep link the tab was opened with: every cold load spends a WS round trip in
 *    this state, and `/s/<id>` would come back as `/` a second later.
 */
@Composable
fun UrlSync(ui: ShellUiState, enabled: Boolean = true, wizard: Boolean = false) {
    var initialApplied by remember(ui) { mutableStateOf(false) }

    // 0. Wizard on screen: the address bar says so, and nothing below runs.
    LaunchedEffect(ui, enabled, wizard) {
        if (enabled || !wizard) return@LaunchedEffect
        if (currentPath() != SETUP_PATH) window.history.replaceState(null, "", SETUP_PATH)
    }

    // 1. Initial URL → shell, once, and not before the shell is there.
    LaunchedEffect(ui, enabled) {
        if (!enabled || initialApplied) return@LaunchedEffect
        val path = currentPath()
        if (shouldApplyInitialUrl(path, enabled = true)) applyTarget(ui, parsePath(path))
        initialApplied = true
    }

    // 2. Shell → URL, only after (1) ran.
    LaunchedEffect(ui, initialApplied, enabled) {
        if (!initialApplied || !enabled) return@LaunchedEffect
        snapshotFlow { pathFor(ui.currentRoute, ui.selectedId) }.distinctUntilChanged().collect { path ->
            val current = currentPath()
            if (current == path) return@collect
            // NORMALISATION (`/settings` → `/settings/agents`, `/nope` → `/`, `/s/a?x=1` → `/s/a`)
            // must REPLACE the entry, not push one: the typed URL and the canonical URL are the
            // same destination, and pushing would put the un-normalised path one step back — so
            // Back would re-apply it, the mirror would push again, and Back would be trapped.
            if (parsePath(current) == parsePath(path)) window.history.replaceState(null, "", path)
            else window.history.pushState(null, "", path)
        }
    }

    // 3. Browser back/forward → shell.
    DisposableEffect(ui, enabled) {
        val handler: (Event) -> Unit = { if (enabled) applyTarget(ui, parsePath(currentPath())) }
        if (enabled) window.addEventListener("popstate", handler)
        onDispose { if (enabled) window.removeEventListener("popstate", handler) }
    }
}
