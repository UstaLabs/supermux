package dev.supermux.desktop.workspace

import androidx.compose.ui.unit.dp
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Persistence proof for the desktop UI-state store: a WorkspaceLayout snapshot + selected session
 * survive a save→load round-trip through ui-state.json (the same path WorkspaceRoot debounce-writes
 * and Main hydrates on startup).
 */
class WorkspaceStateStoreTest {
    private fun tempStore(): WorkspaceStateStore {
        val dir = Files.createTempDirectory("smx-ui-state")
        return WorkspaceStateStore(dir.resolve("ui-state.json"))
    }

    @Test fun missingFileLoadsEmptyDefault() {
        val store = tempStore()
        val loaded = store.load()
        assertNull(loaded.layout)
        assertNull(loaded.selectedId)
    }

    @Test fun snapshotAndSelectionRoundTrip() {
        val store = tempStore()
        val layout = WorkspaceLayout().apply {
            sidebarCollapsed = true
            setSidebarWidth(440.dp)
            setChatFraction(0.3f)
            toggleEditor("s1")
            toggleTerminal("s1")
            setNativeView("s1", true)
        }

        store.save(PersistedUiState(layout = layout.snapshot(), selectedId = "s1"))

        val loaded = store.load()
        assertEquals("s1", loaded.selectedId)
        val snap = loaded.layout
        assertTrue(snap != null)
        val restored = WorkspaceLayout().apply { restore(snap) }
        assertTrue(restored.sidebarCollapsed)
        assertEquals(440.dp, restored.sidebarWidth)
        assertEquals(0.3f, restored.chatFraction)
        assertTrue(restored.panesFor("s1").editor)
        assertTrue(restored.panesFor("s1").terminal)
        assertTrue(restored.nativeView("s1"))
    }
}
