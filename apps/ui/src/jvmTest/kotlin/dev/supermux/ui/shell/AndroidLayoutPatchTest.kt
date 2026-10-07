package dev.supermux.ui.shell

import dev.supermux.proto.ViewDto
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.WorkspaceFileOpener
import dev.supermux.workspace.fileViewState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidLayoutPatchTest {
    private fun opener(
        isWide: Boolean,
        patchCalls: MutableList<LayoutNode>,
        postCalls: MutableList<String>,
        tree: LayoutNode,
    ): WorkspaceFileOpener {
        var current = tree
        val provisional = mutableMapOf<String, ViewDto>()
        val patch = workspaceLayoutPatch(
            compact = !isWide,
            onPatch = { patchCalls += it },
        )
        val scope = CoroutineScope(Dispatchers.Unconfined)
        return WorkspaceFileOpener(
            workspaceId = "w",
            treeOf = { current },
            viewsOf = { provisional },
            edit = { transform ->
                current = transform(current)
                runBlocking { patch(current) }
            },
            provisional = provisional,
            reveal = { _, _, _ -> },
            post = { id, _: JsonObject, _: String ->
                postCalls += id
                id
            },
            scope = scope,
            newId = { "new-file" },
        )
    }

    @Test fun narrowFileOpenNeverInvokesPatchLayout() {
        val patches = mutableListOf<LayoutNode>()
        val posts = mutableListOf<String>()
        val tree = LayoutNode.Group("g", listOf("chat"), "chat")
        opener(isWide = false, patches, posts, tree).open("src/A.kt")
        assertTrue(patches.isEmpty(), "phone must not PATCH layout")
        assertEquals(listOf("new-file"), posts)
    }

    @Test fun wideFileOpenInvokesPatchLayout() {
        val patches = mutableListOf<LayoutNode>()
        val posts = mutableListOf<String>()
        val tree = LayoutNode.Group("g", listOf("chat"), "chat")
        opener(isWide = true, patches, posts, tree).open("src/A.kt")
        assertTrue(patches.isNotEmpty(), "tablet PATCHes layout on open")
        assertEquals(listOf("new-file"), posts)
    }

    @Test fun fileViewStateIsEditorFile() {
        val state = fileViewState("a.kt")
        assertEquals("file", state["mode"]?.toString()?.trim('"'))
    }
}
