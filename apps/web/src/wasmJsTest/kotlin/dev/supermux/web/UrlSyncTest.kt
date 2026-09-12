package dev.supermux.web

import dev.supermux.ui.nav.Route
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.shell.ShellUiState
import dev.supermux.web.nav.UrlTarget
import dev.supermux.web.nav.applyTarget
import dev.supermux.web.nav.parsePath
import dev.supermux.web.nav.pathFor
import dev.supermux.web.nav.shouldApplyInitialUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test fun theWizardsPathParsesHomeButIsNeverAppliedAsAnInitialUrl() {
        // `/setup` is the wizard's own URL and stays unknown to the router (an old bookmark of it
        // must still land somewhere sane), so the ONLY thing keeping the Done step's launcher
        // alive when the shell mounts is this guard.
        assertEquals(UrlTarget.Screen(Route.Home), parsePath("/setup"))
        assertFalse(shouldApplyInitialUrl("/setup", enabled = true))
        assertFalse(shouldApplyInitialUrl("/setup/", enabled = true))
        assertFalse(shouldApplyInitialUrl("/setup?x=1", enabled = true))

        val ui = ShellUiState()
        ui.openLauncher()
        // What the real sequence does: Done → openLauncher() → onboarded flips → shell mounts.
        if (shouldApplyInitialUrl("/setup", enabled = true)) applyTarget(ui, parsePath("/setup"))
        assertEquals("/new", pathFor(ui.currentRoute, ui.selectedId))
    }

    @Test fun nothingIsAppliedWhileTheWizardIsUp() {
        // Disabled is disabled whatever the address bar says — the shell is not composed.
        for (path in everyPath) assertFalse(shouldApplyInitialUrl(path, enabled = false))
        // And once it IS enabled, every real path still applies.
        for (path in everyPath) assertTrue(shouldApplyInitialUrl(path, enabled = true), path)
    }

    @Test fun destinationsDoNotStack() {
        val ui = ShellUiState()
        applyTarget(ui, parsePath("/usage"))
        applyTarget(ui, parsePath("/settings/voice"))
        assertEquals(2, ui.backStack.size)
        assertEquals("/settings/voice", pathFor(ui.currentRoute, ui.selectedId))
    }
}
