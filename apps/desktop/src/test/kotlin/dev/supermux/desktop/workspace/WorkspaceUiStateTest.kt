package dev.supermux.desktop.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for [WorkspaceUiState.reconcileSessions] — the startup-order bug: app.sessions
 * starts EMPTY until the first WS Snapshot arrives, and reconciling against that transient [] used
 * to wipe the hydrated selection + per-session panes (which the debounced save then persisted back
 * to ui-state.json permanently). An empty live set must be treated as "not loaded yet", not
 * "everything died".
 */
class WorkspaceUiStateTest {
    private fun hydrated(): WorkspaceUiState = WorkspaceUiState().apply {
        selectedId = "s1"
        layout.toggleEditor("s1")
        layout.toggleTerminal("s2")
    }

    @Test fun emptyReconcilePreservesHydratedState() {
        val ui = hydrated()
        ui.reconcileSessions(emptySet())
        assertEquals("s1", ui.selectedId)
        assertTrue(ui.layout.panesFor("s1").editor)
        assertTrue(ui.layout.panesFor("s2").terminal)
    }

    @Test fun nonEmptyReconcilePrunesDeadSessionsAndSelection() {
        val ui = hydrated()
        // s1 genuinely gone, s2 (and a fresh s3) still live.
        ui.reconcileSessions(setOf("s2", "s3"))
        assertNull(ui.selectedId)
        assertFalse(ui.layout.panesFor("s1").editor)
        assertTrue(ui.layout.panesFor("s2").terminal)
    }

    @Test fun reconcileKeepsLiveSelectionAndPanes() {
        val ui = hydrated()
        ui.reconcileSessions(setOf("s1", "s2"))
        assertEquals("s1", ui.selectedId)
        assertTrue(ui.layout.panesFor("s1").editor)
        assertTrue(ui.layout.panesFor("s2").terminal)
    }

    // ── "at most one overlay" invariant (M4e Task 2) ──────────────────────────────────────────────
    // The two full-pane overlays draw opaquely over one another; both being open would leave a
    // stale one surfacing when the other closes. openLauncher()/openArchived() enforce exclusivity.

    @Test fun openLauncherClosesTheArchivedOverlay() {
        val ui = WorkspaceUiState().apply { archivedOpen = true }
        ui.openLauncher()
        assertTrue(ui.launcherOpen)
        assertFalse(ui.archivedOpen)
        assertTrue(ui.overlayOpen)
    }

    @Test fun openArchivedClosesTheLauncherOverlay() {
        val ui = WorkspaceUiState().apply { launcherOpen = true }
        ui.openArchived()
        assertTrue(ui.archivedOpen)
        assertFalse(ui.launcherOpen)
        assertTrue(ui.overlayOpen)
    }

    // ── Usage overlay (M4f Task 2) — the same "at most one overlay" invariant, three-way now ────────

    @Test fun openUsageClosesTheLauncherAndArchivedOverlays() {
        val ui = WorkspaceUiState().apply { launcherOpen = true }
        ui.openUsage()
        assertTrue(ui.usageOpen)
        assertFalse(ui.launcherOpen)
        assertFalse(ui.archivedOpen)
        assertTrue(ui.overlayOpen)

        val ui2 = WorkspaceUiState().apply { archivedOpen = true }
        ui2.openUsage()
        assertTrue(ui2.usageOpen)
        assertFalse(ui2.archivedOpen)
    }

    @Test fun openLauncherClosesTheUsageOverlay() {
        val ui = WorkspaceUiState().apply { usageOpen = true }
        ui.openLauncher()
        assertTrue(ui.launcherOpen)
        assertFalse(ui.usageOpen)
        assertTrue(ui.overlayOpen)
    }

    @Test fun openArchivedClosesTheUsageOverlay() {
        val ui = WorkspaceUiState().apply { usageOpen = true }
        ui.openArchived()
        assertTrue(ui.archivedOpen)
        assertFalse(ui.usageOpen)
        assertTrue(ui.overlayOpen)
    }

    @Test fun openPersonalAssistantsClosesEveryOtherOverlay() {
        val ui = WorkspaceUiState().apply {
            launcherOpen = true
            archivedOpen = true
            usageOpen = true
            lspSettingsOpen = true
        }
        ui.openPersonalAssistants()
        assertTrue(ui.personalAssistantsOpen)
        assertFalse(ui.launcherOpen)
        assertFalse(ui.archivedOpen)
        assertFalse(ui.usageOpen)
        assertFalse(ui.lspSettingsOpen)
        assertTrue(ui.overlayOpen)
    }
}
