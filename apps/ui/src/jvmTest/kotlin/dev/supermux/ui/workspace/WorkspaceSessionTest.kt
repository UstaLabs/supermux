package dev.supermux.ui.workspace

import dev.supermux.proto.ViewDto
import androidx.compose.runtime.mutableStateMapOf
import dev.supermux.ui.editor.DocumentStore
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.WorkspaceFileOpener
import dev.supermux.workspace.groupIdOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceSessionTest {
    @Test
    fun mergePrefersServerOnCollision() {
        val provisional = mapOf(
            "v1" to ViewDto(id = "v1", workspaceId = "w", kind = "editor", title = "stand-in"),
            "v2" to ViewDto(id = "v2", workspaceId = "w", kind = "editor", title = "only-local"),
        )
        val server = mapOf(
            "v1" to ViewDto(id = "v1", workspaceId = "w", kind = "editor", title = "broker"),
        )
        val merged = mergeWorkspaceViews(provisional, server)
        assertEquals("broker", merged["v1"]?.title)
        assertEquals("only-local", merged["v2"]?.title)
    }

    @Test
    fun mergeReturnsServerWhenNoProvisional() {
        val server = mapOf("v1" to ViewDto(id = "v1", workspaceId = "w", kind = "chat"))
        assertSame(server, mergeWorkspaceViews(emptyMap(), server))
    }

    // ── planMovedFileViews: a rename/delete in the Files tree vs the open file tabs ──────────

    private fun fileView(id: String, path: String) = ViewDto(
        id = id, workspaceId = "w", kind = "editor",
        state = JsonObject(mapOf("mode" to JsonPrimitive("file"), "path" to JsonPrimitive(path))),
    )

    private val views = mapOf(
        "tree" to ViewDto(id = "tree", workspaceId = "w", kind = "editor", state = JsonObject(mapOf("mode" to JsonPrimitive("tree")))),
        "a" to fileView("a", "src/a.kt"),
        "b" to fileView("b", "src/ui/b.kt"),
        "c" to fileView("c", "other.kt"),
    )
    private val tree = LayoutNode.Split(
        "row", listOf(0.5, 0.5),
        listOf(
            LayoutNode.Group("g1", listOf("tree"), "tree"),
            LayoutNode.Group("g2", listOf("a", "b", "c"), "a"),
        ),
    )

    @Test
    fun deletingAFolderClosesCleanTabsAndMarksDirtyOnesStale() {
        val steps = planMovedFileViews("/w", "/w/src", null, views, tree, isDirty = { it == "src/ui/b.kt" })
        assertEquals(
            listOf(MovedFileStep.Close("a", "src/a.kt"), MovedFileStep.MarkStale("b", "src/ui/b.kt")),
            steps,
        )
    }

    @Test
    fun renamingAFileReopensACleanTabInItsOwnGroup() {
        val steps = planMovedFileViews("/w", "/w/src/a.kt", "/w/src/z.kt", views, tree, isDirty = { false })
        assertEquals(listOf(MovedFileStep.Reopen("a", "src/a.kt", "src/z.kt", "g2")), steps)
    }

    @Test
    fun renamingAFolderReopensEveryCleanTabUnderItAndKeepsDirtyOnes() {
        val steps = planMovedFileViews("/w", "/w/src", "/w/lib", views, tree, isDirty = { it == "src/a.kt" })
        assertEquals(
            listOf(MovedFileStep.MarkStale("a", "src/a.kt"), MovedFileStep.Reopen("b", "src/ui/b.kt", "lib/ui/b.kt", "g2")),
            steps,
        )
    }

    @Test
    fun anUnrelatedEntryTouchesNothing() {
        assertEquals(emptyList(), planMovedFileViews("/w", "/w/docs", null, views, tree, isDirty = { false }))
    }

    @Test
    fun applyingARenameReplacesCleanTabsAndKeepsDirtyOnesFlaggedStale() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val docs = DocumentStore(fsRead = { Result.success("body:$it") }, fsWrite = { _, _ -> true }, scope = scope)
        docs.open("src/a.kt")
        docs.open("src/ui/b.kt")
        docs.update("src/ui/b.kt", "unsaved")
        val layout = WorkspaceLayoutState(tree)
        val provisional = mutableStateMapOf<String, ViewDto>()
        var minted = 0
        val opener = WorkspaceFileOpener(
            workspaceId = "w",
            treeOf = { layout.tree },
            viewsOf = { provisional + views },
            edit = { layout.edit(it) },
            provisional = provisional,
            reveal = { p, l, e -> docs.openAtLine(p, l, e) },
            post = { id, _, _ -> id },
            scope = scope,
            newId = { "n${++minted}" },
        )
        val ws = WorkspaceSession(
            workspaceId = "w",
            provisionalViews = provisional,
            layoutSync = layout,
            documents = docs,
            previewModes = mutableStateMapOf(),
            viewsById = views,
            fileOpener = opener,
        )
        val closed = mutableListOf<String>()

        ws.applyEntryMoved("/w", "/w/src", "/w/lib", closeView = { closed += it })

        // The clean tab: the new path opened in the SAME group, then the old tab closed.
        assertEquals(listOf("a"), closed)
        assertEquals("g2", groupIdOf(layout.tree, "n1"))
        assertEquals("lib/a.kt", provisional["n1"]?.state?.get("path")?.let { (it as JsonPrimitive).content })
        assertNotNull(docs.get("lib/a.kt"))
        assertNull(docs.get("src/a.kt"))
        // The dirty tab: untouched, its edits kept, and flagged so the banner shows.
        assertEquals("unsaved", docs.get("src/ui/b.kt")?.content)
        assertTrue(docs.isStale("src/ui/b.kt"))
        assertFalse(docs.isStale("src/a.kt"))
    }
}
