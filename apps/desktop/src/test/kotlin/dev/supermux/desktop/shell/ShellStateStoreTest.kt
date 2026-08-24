package dev.supermux.desktop.shell

import androidx.compose.ui.unit.dp
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Persistence proof for the desktop UI-state store: the sidebar snapshot + selected session survive
 * a save→load round-trip through ui-state.json (the same path AppShell debounce-writes and Main
 * hydrates on startup).
 *
 * The snapshot used to carry the whole old shell layout (split fractions + per-session pane flags).
 * That went with SessionDetail — what is on screen inside the detail pane is now the workspace's
 * own tree, stored on the broker — so only the sidebar is still the client's to remember. The
 * last case pins the compatibility that matters: a ui-state.json written by the OLD shell must
 * still restore its sidebar rather than being thrown away as corrupt.
 */
class ShellStateStoreTest {
    private fun tempStore(): ShellStateStore {
        val dir = Files.createTempDirectory("smx-ui-state")
        return ShellStateStore(dir.resolve("ui-state.json"))
    }

    @Test fun missingFileLoadsEmptyDefault() {
        val store = tempStore()
        val loaded = store.load()
        assertNull(loaded.layout)
        assertNull(loaded.selectedId)
    }

    @Test fun snapshotAndSelectionRoundTrip() {
        val store = tempStore()
        val ui = ShellUiState().apply {
            sidebarCollapsed = true
            setSidebarWidth(440.dp)
            collapsedProjectPaths = setOf("/home/a/proj", "/tmp/other")
        }

        store.save(
            PersistedUiState(
                layout = ui.snapshot(),
                selectedId = "s1",
                appearance = "LIGHT",
            ),
        )

        val loaded = store.load()
        assertEquals("s1", loaded.selectedId)
        assertEquals("LIGHT", loaded.appearance)
        val snap = loaded.layout
        assertTrue(snap != null)
        val restored = ShellUiState().apply { restore(snap) }
        assertTrue(restored.sidebarCollapsed)
        assertEquals(440.dp, restored.sidebarWidth)
        assertEquals(setOf("/home/a/proj", "/tmp/other"), restored.collapsedProjectPaths)
    }

    @Test fun oldFileWithoutAppearanceOrCollapsedPathsStillLoads() {
        val dir = Files.createTempDirectory("smx-ui-state")
        val path = dir.resolve("ui-state.json")
        Files.writeString(
            path,
            """{"layout":{"sidebarCollapsed":false,"sidebarWidthDp":320.0},"selectedId":"s1"}""",
        )
        val loaded = ShellStateStore(path).load()
        assertEquals("s1", loaded.selectedId)
        assertEquals(null, loaded.appearance)
        val restored = ShellUiState().apply { loaded.layout?.let { restore(it) } }
        assertTrue(restored.collapsedProjectPaths.isEmpty())
    }

    @Test fun aFileWrittenByTheOldShellStillRestoresItsSidebar() {
        val dir = Files.createTempDirectory("smx-ui-state")
        val path = dir.resolve("ui-state.json")
        // Verbatim shape of the pre-workspace ShellSnapshot: the sidebar fields plus the split
        // fractions / pane maps that no longer exist.
        Files.writeString(
            path,
            """
            {"layout":{"sidebarCollapsed":true,"sidebarWidthDp":400.0,"chatFraction":0.35,
             "workDisplayFraction":0.5,"editorTermFraction":0.5,
             "panes":{"s1":{"chat":true,"editor":true,"terminal":false,"display":false}},
             "native":{"s1":true}},"selectedId":"s1"}
            """.trimIndent(),
        )
        val loaded = ShellStateStore(path).load()
        assertEquals("s1", loaded.selectedId)
        val restored = ShellUiState().apply { loaded.layout?.let { restore(it) } }
        assertTrue(restored.sidebarCollapsed)
        assertEquals(400.dp, restored.sidebarWidth)
    }

    @Test fun oldFileWithoutWindowsKeyLoadsEmptyWindows() {
        val dir = Files.createTempDirectory("smx-ui-state")
        val path = dir.resolve("ui-state.json")
        Files.writeString(
            path,
            """{"layout":{"sidebarCollapsed":false,"sidebarWidthDp":320.0},"selectedId":"s1"}""",
        )
        val loaded = ShellStateStore(path).load()
        assertEquals("s1", loaded.selectedId)
        assertTrue(loaded.windows.isEmpty())
    }

    @Test fun extrasRoundTripIncludingEmptyAndTwoIdClaims() {
        val store = tempStore()
        store.save(
            PersistedUiState(
                selectedId = "s1",
                windows = listOf(
                    PersistedWindowHost(
                        id = "canvas-1",
                        workspaceId = "ws-a",
                        claimedViewIds = emptyList(),
                        x = 10f,
                        y = 20f,
                        width = 800f,
                        height = 600f,
                    ),
                    PersistedWindowHost(
                        id = "extra-1",
                        workspaceId = "ws-b",
                        claimedViewIds = listOf("v1", "v2"),
                        x = 100f,
                        y = 40f,
                        width = 640f,
                        height = 480f,
                    ),
                ),
            ),
        )
        val loaded = store.load()
        assertEquals(2, loaded.windows.size)
        val canvas = loaded.windows[0]
        assertEquals("canvas-1", canvas.id)
        assertEquals("ws-a", canvas.workspaceId)
        assertTrue(canvas.claimedViewIds.isEmpty())
        assertEquals(10f, canvas.x)
        assertEquals(20f, canvas.y)
        assertEquals(800f, canvas.width)
        assertEquals(600f, canvas.height)
        val extra = loaded.windows[1]
        assertEquals("extra-1", extra.id)
        assertEquals("ws-b", extra.workspaceId)
        assertEquals(listOf("v1", "v2"), extra.claimedViewIds)
        assertEquals(100f, extra.x)
        assertEquals(40f, extra.y)
        assertEquals(640f, extra.width)
        assertEquals(480f, extra.height)
    }

    @Test fun sidebarWidthClampsToRange() {
        val ui = ShellUiState()
        ui.setSidebarWidth(50.dp)
        assertEquals(ShellUiState.SIDEBAR_MIN, ui.sidebarWidth)
        ui.setSidebarWidth(999.dp)
        assertEquals(ShellUiState.SIDEBAR_MAX, ui.sidebarWidth)
        ui.setSidebarWidth(300.dp)
        assertEquals(300.dp, ui.sidebarWidth)
    }
}
