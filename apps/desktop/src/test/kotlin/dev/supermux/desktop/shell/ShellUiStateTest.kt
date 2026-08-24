package dev.supermux.desktop.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for [ShellUiState.reconcileSessions] — the startup-order bug: app.sessions
 * starts EMPTY until the first WS Snapshot arrives, and reconciling against that transient [] used
 * to wipe the hydrated selection (which the debounced save then persisted back to ui-state.json
 * permanently). An empty live set must be treated as "not loaded yet", not "everything died".
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
        val ui = ShellUiState().apply { navigate(DesktopRoute.Archived) }
        ui.openLauncher()
        assertTrue(ui.launcherOpen)
        assertFalse(ui.archivedOpen)
        assertTrue(ui.overlayOpen)
    }

    @Test fun openArchivedClosesTheLauncherOverlay() {
        val ui = ShellUiState().apply { launcherOpen = true }
        ui.openArchived()
        assertTrue(ui.archivedOpen)
        assertFalse(ui.launcherOpen)
        assertTrue(ui.overlayOpen)
    }

    // ── Usage popover (floating card) — exclusive with launcher + full-pane routes ────────

    @Test fun openUsageClosesTheLauncherAndArchivedOverlays() {
        val ui = ShellUiState().apply { launcherOpen = true }
        ui.openUsage()
        assertTrue(ui.usageOpen)
        assertFalse(ui.launcherOpen)
        assertFalse(ui.archivedOpen)
        assertTrue(ui.overlayOpen)

        val ui2 = ShellUiState().apply { navigate(DesktopRoute.Archived) }
        ui2.openUsage()
        assertTrue(ui2.usageOpen)
        assertFalse(ui2.archivedOpen)
        assertEquals(listOf(DesktopRoute.Home), ui2.backStack.toList())
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
            launcherOpen = true
            navigate(DesktopRoute.Archived)
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

    @Test fun backStackStartsAtHomeOnly() {
        val ui = ShellUiState()
        assertEquals(listOf(DesktopRoute.Home), ui.backStack.toList())
        assertEquals(DesktopRoute.Home, ui.currentRoute)
        assertFalse(ui.overlayOpen)
    }

    @Test fun navigatePushesOverlayAboveHome() {
        val ui = ShellUiState()
        ui.navigate(DesktopRoute.Settings(SettingsSection.Devices))
        assertEquals(
            listOf(DesktopRoute.Home, DesktopRoute.Settings(SettingsSection.Devices)),
            ui.backStack.toList(),
        )
        assertTrue(ui.overlayOpen)
        assertTrue(ui.settingsOpen)
        assertEquals(SettingsSection.Devices, ui.settingsSection)
    }

    @Test fun goBackPopsToHome() {
        val ui = ShellUiState()
        ui.navigate(DesktopRoute.Archived)
        assertTrue(ui.goBack())
        assertEquals(listOf(DesktopRoute.Home), ui.backStack.toList())
        assertFalse(ui.goBack()) // already at Home
        assertFalse(ui.archivedOpen)
    }

    @Test fun navigateIsExclusiveSingleOverlay() {
        val ui = ShellUiState()
        ui.navigate(DesktopRoute.Archived)
        ui.navigate(DesktopRoute.Settings(SettingsSection.Agents))
        // Exclusive policy: stack is [Home, Settings], not [Home, Archived, Settings]
        assertEquals(
            listOf(DesktopRoute.Home, DesktopRoute.Settings(SettingsSection.Agents)),
            ui.backStack.toList(),
        )
        assertFalse(ui.archivedOpen)
        assertTrue(ui.settingsOpen)
    }

    @Test fun usageIsNotOnTheNavStack() {
        val ui = ShellUiState()
        ui.openUsage()
        assertTrue(ui.usageOpen)
        assertEquals(listOf(DesktopRoute.Home), ui.backStack.toList())
        ui.closeUsage()
        assertFalse(ui.usageOpen)
        assertFalse(ui.overlayOpen)
    }
}
