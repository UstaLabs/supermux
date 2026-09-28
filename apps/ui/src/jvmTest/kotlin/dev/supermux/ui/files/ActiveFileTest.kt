package dev.supermux.ui.files

import dev.supermux.proto.ViewDto
import dev.supermux.workspace.LayoutNode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActiveFileTest {
    private fun view(id: String, kind: String, vararg state: Pair<String, String>) =
        ViewDto(id = id, workspaceId = "w", kind = kind, state = JsonObject(state.associate { (k, v) -> k to JsonPrimitive(v) }))

    private fun file(id: String, path: String) = view(id, "editor", "mode" to "file", "path" to path)

    private val views = listOf(
        file("f1", "a.kt"),
        file("f2", "b.kt"),
        file("f3", "c.kt"),
        view("chat", "chat", "sessionId" to "s"),
        view("tree", "editor", "mode" to "tree"),
    ).associateBy { it.id }

    private fun layout(vararg groups: LayoutNode.Group) =
        LayoutNode.Split("row", List(groups.size) { 1.0 / groups.size }, groups.toList())

    @Test fun noFocusPicksTheFirstGroupShowingAFile() {
        val l = layout(LayoutNode.Group("g1", listOf("tree"), "tree"), LayoutNode.Group("g2", listOf("f1", "f2"), "f2"))
        assertEquals("b.kt", activeFilePath(l, views, null))
    }

    @Test fun theFocusedGroupWins() {
        val l = layout(LayoutNode.Group("g1", listOf("f1"), "f1"), LayoutNode.Group("g2", listOf("f2", "f3"), "f3"))
        assertEquals("c.kt", activeFilePath(l, views, "f3"))
        // The focused view's GROUP decides: another file became active in it since.
        assertEquals("c.kt", activeFilePath(l, views, "f2"))
    }

    @Test fun aFocusedGroupNotShowingAFileFallsBack() {
        val l = layout(LayoutNode.Group("g1", listOf("f1"), "f1"), LayoutNode.Group("g2", listOf("f2", "chat"), "chat"))
        assertEquals("a.kt", activeFilePath(l, views, "f2"))
    }

    @Test fun aFocusedViewThatIsGoneFallsBack() {
        val l = layout(LayoutNode.Group("g1", listOf("f1"), "f1"))
        assertEquals("a.kt", activeFilePath(l, views, "closed"))
    }

    @Test fun aGroupWithNoActiveIdUsesItsFirstTab() {
        val l = layout(LayoutNode.Group("g1", listOf("f2", "f1"), null))
        assertEquals("b.kt", activeFilePath(l, views, "f1"))
    }

    @Test fun nullWhenNoGroupShowsAFile() {
        val l = layout(LayoutNode.Group("g1", listOf("tree", "f1"), "tree"), LayoutNode.Group("g2", listOf("chat"), "chat"))
        assertNull(activeFilePath(l, views, null))
        assertNull(activeFilePath(LayoutNode.Group("g"), views, "f1"))
    }

    @Test fun aNewlyActiveFileTakesFocus() {
        assertEquals("f2", nextFocusedFileView(listOf("f1", "chat"), listOf("f1", "f2"), views, "f1"))
        // Nothing new → keep; something new that isn't a file → keep.
        assertEquals("f1", nextFocusedFileView(listOf("f1", "f2"), listOf("f1", "f2"), views, "f1"))
        assertEquals("f3", nextFocusedFileView(listOf("f3", "f1"), listOf("f3", "chat"), views, "f3"))
        // First run: the first file in layout order.
        assertEquals("f1", nextFocusedFileView(emptyList(), listOf("tree", "f1", "f2"), views, null))
        // An id whose view isn't known yet is not a file.
        assertNull(nextFocusedFileView(emptyList(), listOf("pending"), views, null))
    }

    @Test fun viewKinds() {
        assertEquals("a.kt", views.getValue("f1").filePathOrNull())
        assertNull(views.getValue("tree").filePathOrNull())
        assertNull(view("x", "editor", "mode" to "file").filePathOrNull())
        assertTrue(views.getValue("tree").isFilesTreeView())
        assertTrue(view("x", "editor").isFilesTreeView())
        assertFalse(views.getValue("f1").isFilesTreeView())
        assertFalse(view("x", "editor", "mode" to "diff").isFilesTreeView())
        assertFalse(views.getValue("chat").isFilesTreeView())
    }
}
