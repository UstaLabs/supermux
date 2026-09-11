package dev.supermux.web

import dev.supermux.ui.nav.Route
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.shell.ShellUiState
import dev.supermux.web.nav.applyTarget
import dev.supermux.web.nav.parsePath
import dev.supermux.web.nav.pathFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `applyTarget` ⇄ `pathFor` round trips. No DOM is touched — [ShellUiState] is plain Compose state
 * — but the file lives in the Karma lane because that is where `:web`'s tests run at all.
 */
class UrlSyncTest {
    private val everyPath = listOf(
        "/",
        "/s/abc",
        "/new",
        "/new?draft=d1",
        "/add-host",
        "/usage",
        "/devices",
        "/archived",
        "/proxies",
        "/displays",
        "/personal-assistants",
        "/settings/agents",
        "/settings/devices",
        "/settings/system",
        "/settings/git-hosting",
        "/settings/proxies",
        "/settings/assistant",
        "/settings/curator",
        "/settings/voice",
        "/settings/editor",
        "/settings/appearance",
        "/settings/updates",
    )

    @Test fun everyPathSurvivesTheRoundTrip() {
        for (path in everyPath) {
            val ui = ShellUiState()
            applyTarget(ui, parsePath(path))
            assertEquals(path, pathFor(ui.currentRoute, ui.selectedId), "round trip of $path")
        }
    }

    @Test fun homeClearsTheSelection() {
        val ui = ShellUiState()
        applyTarget(ui, parsePath("/s/abc"))
        assertEquals("abc", ui.selectedId)
        ui.selectedArchivedWorkspaceId = "ws"
        applyTarget(ui, parsePath("/"))
        assertNull(ui.selectedId)
        assertNull(ui.selectedArchivedWorkspaceId)
        assertEquals("/", pathFor(ui.currentRoute, ui.selectedId))
    }

    @Test fun reapplyingUsageDoesNotToggleItClosed() {
        // `openUsage()` is a TOGGLE; a popstate that re-applies the same URL must not close it.
        val ui = ShellUiState()
        applyTarget(ui, parsePath("/usage"))
        applyTarget(ui, parsePath("/usage"))
        assertTrue(ui.usageOpen)
        assertEquals("/usage", pathFor(ui.currentRoute, ui.selectedId))
    }

    @Test fun unknownPathsLandOnHome() {
        val ui = ShellUiState()
        applyTarget(ui, parsePath("/nope"))
        assertEquals("/", pathFor(ui.currentRoute, ui.selectedId))
    }

    @Test fun bareSettingsNormalisesToAgents() {
        val ui = ShellUiState()
        applyTarget(ui, parsePath("/settings"))
        assertEquals(Route.Settings(SettingsSection.Agents), ui.currentRoute)
        assertEquals("/settings/agents", pathFor(ui.currentRoute, ui.selectedId))
    }

    @Test fun destinationsDoNotStack() {
        val ui = ShellUiState()
        applyTarget(ui, parsePath("/usage"))
        applyTarget(ui, parsePath("/settings/voice"))
        assertEquals(2, ui.backStack.size)
        assertEquals("/settings/voice", pathFor(ui.currentRoute, ui.selectedId))
    }
}
