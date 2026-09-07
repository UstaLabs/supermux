package dev.supermux.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.unit.dp
import dev.supermux.ui.nav.Route
import dev.supermux.ui.nav.SettingsSection

/**
 * Regression tests for [ShellUiState.reconcileSessions] — the startup-order bug: app.sessions
 * starts EMPTY until the first WS Snapshot arrives, and reconciling against that transient [] used
 * to wipe the hydrated selection (which the debounced save then persisted back to ui-state.json
 * permanently). An empty live set must be treated as "not loaded yet", not "everything died".
 *
 * Cluster G8 folded desktop's `launcherOpen`/`usageOpen` booleans into `Route.NewSession` /
 * `Route.Usage` on the SAME back stack, so the exclusivity cases below now read the stack; the
 * user-visible rule they pin ("at most one destination above Home") is unchanged.
 *
 * These used to also assert the per-session PANE state was preserved/pruned alongside the
 * selection. There is no per-session pane state any more: the old shell's four fixed panes went
 * with SessionDetail, and a workspace's panes live in its broker-stored layout tree. The selection
 * half of each case is kept.
 */
class WorkspaceUiStateTest {
    private fun hydrated(): ShellUiState = ShellUiState().apply {
        selectedId = "s1"
        sidebarCollapsed = true
    }

    @Test fun emptyReconcilePreservesHydratedState() {
        val ui = hydrated()
        ui.reconcileSessions(emptySet())
        assertEquals("s1", ui.selectedId)
        assertTrue(ui.sidebarCollapsed)
    }

    @Test fun nonEmptyReconcileDropsASelectionWhoseSessionIsGone() {
        val ui = hydrated()
        // s1 genuinely gone, s2 (and a fresh s3) still live.
        ui.reconcileSessions(setOf("s2", "s3"))
        assertNull(ui.selectedId)
    }

    @Test fun reconcileKeepsALiveSelection() {
        val ui = hydrated()
        ui.reconcileSessions(setOf("s1", "s2"))
        assertEquals("s1", ui.selectedId)
    }

    @Test fun selectArchivedWorkspaceDoesNotPutTheWorkspaceIdInSelectedId() {
        val ui = hydrated()
        ui.selectArchivedWorkspace("w-archived")
        assertEquals("w-archived", ui.selectedArchivedWorkspaceId)
        assertNull(ui.selectedId)
    }

    @Test fun selectSessionClearsArchivedSelection() {
        val ui = hydrated()
        ui.selectArchivedWorkspace("w-archived")
        ui.selectSession("s1")
        assertEquals("s1", ui.selectedId)
        assertNull(ui.selectedArchivedWorkspaceId)
    }

    // ── "at most one overlay" invariant (M4e Task 2) ──────────────────────────────────────────────
    // The two full-pane overlays draw opaquely over one another; both being open would leave a
    // stale one surfacing when the other closes. openLauncher()/openArchived() enforce exclusivity.

    @Test fun openLauncherClosesTheArchivedOverlay() {
        val ui = ShellUiState().apply { navigate(Route.Archived) }
        ui.openLauncher()
        assertTrue(ui.launcherOpen)
        assertFalse(ui.archivedOpen)
        assertTrue(ui.overlayOpen)
    }

    @Test fun openArchivedClosesTheLauncherOverlay() {
        val ui = ShellUiState().apply { openLauncher() }
        ui.openArchived()
        assertTrue(ui.archivedOpen)
        assertFalse(ui.launcherOpen)
        assertTrue(ui.overlayOpen)
    }

    // ── Usage popover (floating card) — exclusive with launcher + full-pane routes ────────

    @Test fun openUsageClosesTheLauncherAndArchivedOverlays() {
        val ui = ShellUiState().apply { openLauncher() }
        ui.openUsage()
        assertTrue(ui.usageOpen)
        assertFalse(ui.launcherOpen)
        assertFalse(ui.archivedOpen)
        assertTrue(ui.overlayOpen)

        val ui2 = ShellUiState().apply { navigate(Route.Archived) }
        ui2.openUsage()
        assertTrue(ui2.usageOpen)
        assertFalse(ui2.archivedOpen)
        // Usage REPLACES Archived on the one stack (G8's fold) rather than sitting beside it.
        assertEquals(listOf(Route.Home, Route.Usage), ui2.backStack.toList())
    }

    @Test fun openLauncherClosesTheUsageOverlay() {
        val ui = ShellUiState().apply { openUsage() }
        ui.openLauncher()
        assertTrue(ui.launcherOpen)
        assertFalse(ui.usageOpen)
        assertTrue(ui.overlayOpen)
    }

    @Test fun openArchivedClosesTheUsageOverlay() {
        val ui = ShellUiState().apply { openUsage() }
        ui.openArchived()
        assertTrue(ui.archivedOpen)
        assertFalse(ui.usageOpen)
        assertTrue(ui.overlayOpen)
    }

    @Test fun openPersonalAssistantsClosesEveryOtherOverlay() {
        val ui = ShellUiState().apply {
            openLauncher()
            navigate(Route.Archived)
            openUsage()
            openLspSettings()
        }
        ui.openPersonalAssistants()
        assertTrue(ui.personalAssistantsOpen)
        assertTrue(ui.settingsOpen)
        assertEquals(SettingsSection.PersonalAssistants, ui.settingsSection)
        assertFalse(ui.launcherOpen)
        assertFalse(ui.archivedOpen)
        assertFalse(ui.usageOpen)
        assertFalse(ui.lspSettingsOpen)
        assertTrue(ui.overlayOpen)
    }

    @Test fun openSettingsClosesEveryOtherOverlayAndSelectsAgents() {
        val ui = ShellUiState().apply {
            openUsage()
            openLspSettings()
        }
        ui.openSettings(SettingsSection.Agents)
        assertTrue(ui.settingsOpen)
        assertEquals(SettingsSection.Agents, ui.settingsSection)
        assertFalse(ui.usageOpen)
        assertFalse(ui.lspSettingsOpen)
        assertTrue(ui.overlayOpen)
    }

    @Test fun openLspSettingsRoutesThroughTheSettingsHub() {
        val ui = ShellUiState()
        ui.openLspSettings()
        assertTrue(ui.settingsOpen)
        assertTrue(ui.lspSettingsOpen)
        assertEquals(SettingsSection.EditorLsp, ui.settingsSection)
    }

    // ── Nav3 back stack is the source of truth ──────────────────────────────────────────────────

    @Test fun sidebarWidthClampsToRange() {
        // Moved from desktop's `ShellStateStoreTest` with the state it clamps (cluster G8).
        val ui = ShellUiState()
        ui.setSidebarWidth(50.dp)
        assertEquals(ShellUiState.SIDEBAR_MIN, ui.sidebarWidth)
        ui.setSidebarWidth(999.dp)
        assertEquals(ShellUiState.SIDEBAR_MAX, ui.sidebarWidth)
        ui.setSidebarWidth(300.dp)
        assertEquals(300.dp, ui.sidebarWidth)
    }

    @Test fun backStackStartsAtHomeOnly() {
        val ui = ShellUiState()
        assertEquals(listOf(Route.Home), ui.backStack.toList())
        assertEquals(Route.Home, ui.currentRoute)
        assertFalse(ui.overlayOpen)
    }

    @Test fun navigatePushesOverlayAboveHome() {
        val ui = ShellUiState()
        ui.navigate(Route.Settings(SettingsSection.Devices))
        assertEquals(
            listOf(Route.Home, Route.Settings(SettingsSection.Devices)),
            ui.backStack.toList(),
        )
        assertTrue(ui.overlayOpen)
        assertTrue(ui.settingsOpen)
        assertEquals(SettingsSection.Devices, ui.settingsSection)
    }

    @Test fun goBackPopsToHome() {
        val ui = ShellUiState()
        ui.navigate(Route.Archived)
        assertTrue(ui.goBack())
        assertEquals(listOf(Route.Home), ui.backStack.toList())
        assertFalse(ui.goBack()) // already at Home
        assertFalse(ui.archivedOpen)
    }

    @Test fun navigateIsExclusiveSingleOverlay() {
        val ui = ShellUiState()
        ui.navigate(Route.Archived)
        ui.navigate(Route.Settings(SettingsSection.Agents))
        // Exclusive policy: stack is [Home, Settings], not [Home, Archived, Settings]
        assertEquals(
            listOf(Route.Home, Route.Settings(SettingsSection.Agents)),
            ui.backStack.toList(),
        )
        assertFalse(ui.archivedOpen)
        assertTrue(ui.settingsOpen)
    }

    @Test fun usageIsNotOnTheNavStack() {
        // Cluster G8: it IS on the stack now (`Route.Usage`) — one source of truth for every
        // destination on both hosts. What has not changed is what the user sees on a wide host:
        // the entry paints nothing, Home stays composed underneath, and the sidebar footer draws
        // the anchored popover exactly as before. Opening it again toggles it closed.
        val ui = ShellUiState()
        ui.openUsage()
        assertTrue(ui.usageOpen)
        assertEquals(listOf(Route.Home, Route.Usage), ui.backStack.toList())
        ui.openUsage()
        assertFalse(ui.usageOpen)
        assertEquals(listOf(Route.Home), ui.backStack.toList())
        ui.openUsage()
        ui.closeUsage()
        assertFalse(ui.usageOpen)
        assertFalse(ui.overlayOpen)
    }

    @Test fun theLauncherCarriesItsDraftIdOnTheRoute() {
        val ui = ShellUiState()
        ui.openLauncher(draftId = "d1")
        assertEquals(Route.NewSession("d1"), ui.currentRoute)
        assertEquals("d1", ui.launcherDraftId)
        // Selecting a session leaves the launcher — and the draft goes with the route.
        ui.selectSession("s9")
        assertFalse(ui.launcherOpen)
        assertNull(ui.launcherDraftId)
        assertEquals("s9", ui.selectedId)
    }

    @Test fun theSaverRoundTripsTheStackTheSelectionAndTheSidebar() {
        val ui = ShellUiState().apply {
            selectedId = "s1"
            selectedArchivedWorkspaceId = "w-arch"
            sidebarCollapsed = true
            setSidebarWidth(400.dp)
            navigate(Route.Settings(SettingsSection.Voice))
        }
        val saved = with(ShellUiState.Saver) { TestSaverScope.save(ui) }!!
        val restored = ShellUiState.Saver.restore(saved)!!
        assertEquals(listOf(Route.Home, Route.Settings(SettingsSection.Voice)), restored.backStack.toList())
        assertEquals("s1", restored.selectedId)
        assertEquals("w-arch", restored.selectedArchivedWorkspaceId)
        assertTrue(restored.sidebarCollapsed)
        assertEquals(400.dp, restored.sidebarWidth)
    }

    @Test
    fun navigating_to_an_android_only_route_fails_at_the_call_site() {
        // It no longer fails: cluster G8 made ONE root render every member of the union, so the
        // guard desktop needed (it had no `entry<>` for Android's destinations) is gone. Each push
        // still REPLACES whatever was open — the exclusivity rule that guard sat next to.
        val ui = ShellUiState()
        listOf(
            Route.NewSession("d1"), Route.Usage, Route.Appearance, Route.Devices, Route.Proxies,
            Route.AddHost, Route.Displays, Route.Settings(SettingsSection.Voice), Route.Archived,
            Route.AppUpdate,
        ).forEach {
            ui.navigate(it)
            assertEquals(listOf(Route.Home, it), ui.backStack.toList())
        }
        ui.navigate(Route.Home)
        assertEquals(listOf(Route.Home), ui.backStack.toList())
    }
}

/** Everything is saveable in these cases; the real scope only rejects non-Bundle values. */
private object TestSaverScope : SaverScope {
    override fun canBeSaved(value: Any): Boolean = true
}
