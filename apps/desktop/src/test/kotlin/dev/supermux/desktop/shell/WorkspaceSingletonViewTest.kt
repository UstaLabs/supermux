// Ahmet: "changes and explorer should be always single so trying to open while it is already there
// should be no-op."
//
// Pure lookup, no Compose: the "+" hands a kind to [openSingletonView] and either reveals what it
// finds or creates a view.
package dev.supermux.desktop.shell

import dev.supermux.proto.ViewDto
import dev.supermux.workspace.LayoutNode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun editor(id: String, mode: String?) = ViewDto(
    id = id, workspaceId = "w1", kind = "editor",
    state = JsonObject(if (mode == null) emptyMap() else mapOf("mode" to JsonPrimitive(mode))),
)

private fun terminal(id: String) = ViewDto(id = id, workspaceId = "w1", kind = "terminal", state = JsonObject(emptyMap()))

class WorkspaceSingletonViewTest {

    private val tree = LayoutNode.Split(
        "row", listOf(0.34, 0.33, 0.33),
        listOf(
            LayoutNode.Group("g-term", listOf("t1"), "t1"),
            LayoutNode.Group("g-files", listOf("files"), "files"),
            LayoutNode.Group("g-diff", listOf("diff"), "diff"),
        ),
    )
    private val views = mapOf(
        "t1" to terminal("t1"),
        "files" to editor("files", "tree"),
        "diff" to editor("diff", "diff"),
    )

    @Test
    fun anOpenFilesOrChangesPaneIsFoundWithTheGroupHoldingIt() {
        assertEquals("files" to "g-files", openSingletonView(tree, views, NewViewKind.EDITOR))
        assertEquals("diff" to "g-diff", openSingletonView(tree, views, NewViewKind.DIFF))
    }

    @Test
    fun anEditorWithNoModeAtAllCountsAsTheFilesPane() {
        val layout = LayoutNode.Group("only", listOf("e1"), "e1")
        assertEquals("e1" to "only", openSingletonView(layout, mapOf("e1" to editor("e1", null)), NewViewKind.EDITOR))
    }

    @Test
    fun aFilePaneIsNeitherOfThem() {
        val layout = LayoutNode.Group("only", listOf("f1"), "f1")
        val open = mapOf("f1" to editor("f1", "file"))
        assertNull(openSingletonView(layout, open, NewViewKind.EDITOR))
        assertNull(openSingletonView(layout, open, NewViewKind.DIFF))
    }

    // Several chats and terminals is the point of them, so the "+" must never dedupe those.
    @Test
    fun nonSingletonKindsAlwaysMakeANewView() {
        assertNull(openSingletonView(tree, views, NewViewKind.TERMINAL))
        assertNull(openSingletonView(tree, views, NewViewKind.CHAT))
        assertNull(openSingletonView(tree, views, NewViewKind.DISPLAY))
    }

    // A view the layout does not hold is not open — the pick has to be free to place a fresh one,
    // the same fall-through planFileOpen does for a file.
    @Test
    fun aViewOutsideTheLayoutDoesNotBlockANewOne() {
        val layout = LayoutNode.Group("g-term", listOf("t1"), "t1")
        assertNull(openSingletonView(layout, views, NewViewKind.EDITOR))
        assertNull(openSingletonView(layout, views, NewViewKind.DIFF))
    }
}
