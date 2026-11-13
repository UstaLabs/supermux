package dev.supermux.web

import dev.supermux.ui.nav.Route
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.shell.ShellUiState
import dev.supermux.web.nav.UrlTarget
import dev.supermux.web.nav.applyTarget
import dev.supermux.web.nav.parsePath
import dev.supermux.web.nav.pathFor
import dev.supermux.web.nav.replacesHistoryEntry
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
        "/archived",
        "/displays",
        // Not "/devices" or "/proxies": the machine page's parts since #97 Step 2, they land on /settings/machine
        // (UrlSyncTest.anOldSlugLandsWithItsAnchor…).
        // Not "/agents": that page was removed and lands Home (UrlRoutesTest.theRemovedAgentsPagesHaveNoRoute),
        // so it cannot round-trip.
        "/settings",
        "/settings/general",
        "/settings/providers",
        "/settings/providers/claude",
        "/settings/voice",
        "/settings/voice/glossary",
        "/settings/coding",
        "/settings/memory",
        "/settings/privacy",
        "/settings/machine",
        "/settings/machine/computer",
        "/settings/machine/worktrees",
        // Not Step 1's /settings/machine/status|devices|links: parts of the machine page now (as above).
        // Not "/settings/appearance" or "/settings/updates": old slugs, they land on General (below).
    )

    @Test fun everyPathSurvivesTheRoundTrip() {
        for (path in everyPath) {
            val ui = ShellUiState()
            applyTarget(ui, parsePath(path))
            assertEquals(path, pathFor(ui.currentRoute, ui.selectedId), "round trip of $path")
        }
    }

    @Test fun theOldAppearanceAndUpdatesSlugsLandOnGeneral() {
        for (path in listOf("/settings/appearance", "/settings/updates")) {
            val ui = ShellUiState()
            applyTarget(ui, parsePath(path))
            assertEquals("/settings/general", pathFor(ui.currentRoute, ui.selectedId), path)
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

    @Test fun bareSettingsIsJustSettings() {
        // #97: no section — the index on a phone, General on a wide window — and the URL stays `/settings`.
        val ui = ShellUiState()
        applyTarget(ui, parsePath("/settings"))
        assertEquals(Route.Settings(), ui.currentRoute)
        assertEquals("/settings", pathFor(ui.currentRoute, ui.selectedId))
    }

    @Test fun anOldSlugLandsWithItsAnchorAndItsCanonicalUrlReplacesTheEntry() {
        // `/settings/editor` opens Coding at the code-intelligence row; the address bar then shows `/settings/coding`.
        val ui = ShellUiState()
        applyTarget(ui, parsePath("/settings/editor"))
        assertEquals(Route.Settings(SettingsSection.Coding, anchor = "code-intelligence"), ui.currentRoute)
        val canonical = pathFor(ui.currentRoute, ui.selectedId)
        assertEquals("/settings/coding", canonical)
        // REPLACED, not pushed: one history entry for Settings, so Back leaves Settings instead of re-applying the
        // old slug (which would canonicalise and push again — a Back loop).
        assertTrue(replacesHistoryEntry(current = "/settings/editor", path = canonical))
        val history = mutableListOf("/s/abc", "/settings/editor")
        if (replacesHistoryEntry(history.last(), canonical)) history[history.lastIndex] = canonical else history += canonical
        history.removeAt(history.lastIndex) // Back
        applyTarget(ui, parsePath(history.last()))
        assertEquals(listOf(Route.Home), ui.backStack.toList())
        assertEquals("abc", ui.selectedId)
        // Every old slug canonicalises by replacing.
        val olds = listOf("accounts", "agents", "curator", "editor", "git-hosting", "system", "devices", "proxies", "worktrees")
            .map { "/settings/$it" } +
            listOf("/settings/machine/status", "/settings/machine/devices", "/settings/machine/links", "/devices", "/proxies")
        for (path in olds) {
            val ui2 = ShellUiState()
            applyTarget(ui2, parsePath(path))
            assertTrue(replacesHistoryEntry(path, pathFor(ui2.currentRoute, null)), path)
        }
        // A real move to another page still pushes.
        assertFalse(replacesHistoryEntry("/settings/coding", "/settings/voice"))
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
