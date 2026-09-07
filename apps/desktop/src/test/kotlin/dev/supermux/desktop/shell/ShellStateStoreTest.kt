package dev.supermux.desktop.shell

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Persistence proof for the desktop UI-state store.
 *
 * Since cluster G8 this file carries ONE thing the client still owns alone: the DETACHED WINDOW
 * bounds. The sidebar chrome and the selected session moved to the shared settings store
 * (`SettingsKeys.SHELL_*`, through `UiPrefs` — see `UiPrefsTest.seedShellState…`), which Android
 * reads too. Every legacy field stays DECODABLE so `Main.kt` can drain a pre-G8 file into that
 * store exactly once — which is what the read-side cases below pin.
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
        // The write side is the WINDOWS only now; the legacy fields still round-trip so the
        // one-way drain in `Main.kt` can read a file an older build wrote.
        val store = tempStore()
        store.save(
            PersistedUiState(
                layout = SidebarSnapshot(
                    sidebarCollapsed = true,
                    sidebarWidthDp = 440f,
                    collapsedProjectPaths = listOf("/home/a/proj", "/tmp/other"),
                ),
                selectedId = "s1",
                appearance = "LIGHT",
            ),
        )

        val loaded = store.load()
        assertEquals("s1", loaded.selectedId)
        assertEquals("LIGHT", loaded.appearance)
        val snap = loaded.layout
        assertTrue(snap != null)
        assertTrue(snap.sidebarCollapsed)
        assertEquals(440f, snap.sidebarWidthDp)
        assertEquals(listOf("/home/a/proj", "/tmp/other"), snap.collapsedProjectPaths)
    }

    @Test fun anOldFileWithCollapsedPathsStillRestoresThemForTheOneWayMigration() {
        // The field stays DECODABLE so AppShell can hand a pre-F1 ui-state.json's value to
        // `UiPrefs.seedCollapsedProjectPaths` once. Only the write side went away.
        val dir = Files.createTempDirectory("smx-ui-state")
        val path = dir.resolve("ui-state.json")
        Files.writeString(
            path,
            """{"layout":{"sidebarCollapsed":false,"sidebarWidthDp":320.0,""" +
                """"collapsedProjectPaths":["/home/a/proj"]},"selectedId":null}""",
        )
        assertEquals(
            listOf("/home/a/proj"),
            ShellStateStore(path).load().layout?.collapsedProjectPaths,
        )
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
        assertTrue(loaded.layout?.collapsedProjectPaths.orEmpty().isEmpty())
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
        assertTrue(loaded.layout?.sidebarCollapsed == true)
        assertEquals(400f, loaded.layout?.sidebarWidthDp)
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

    // `sidebarWidthClampsToRange` moved to `:ui`'s `ShellUiStateTest` with the state it clamps.
}
