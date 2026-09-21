package dev.supermux.workspace

import dev.supermux.proto.ViewDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ViewTitleTest {
    private fun view(kind: String, state: Map<String, String>) = ViewDto(
        id = "v1", workspaceId = "w1", kind = kind,
        state = JsonObject(state.mapValues { JsonPrimitive(it.value) }),
    )

    @Test
    fun aFileTabIsNamedAfterItsFile() {
        assertEquals("Main.kt", viewTitle(view("editor", mapOf("mode" to "file", "path" to "src/Main.kt"))))
        assertEquals("Files", viewTitle(view("editor", mapOf("mode" to "tree"))))
        assertEquals("Files", viewTitle(view("editor", emptyMap())))
        assertEquals("Changes", viewTitle(view("editor", mapOf("mode" to "diff"))))
    }

    @Test
    fun aChatTabIsNamedAfterItsSession() {
        val bound = view("chat", mapOf("sessionId" to "s1"))
        assertEquals("Fix the launcher", viewTitle(bound, sessionName = { if (it == "s1") "Fix the launcher" else null }))
        // Names not loaded yet (or blank) → the kind's own word.
        assertEquals("Chat", viewTitle(bound, sessionName = { null }))
        assertEquals("Chat", viewTitle(bound))
        // A pending "+ → Chat" tab has no session yet.
        assertEquals("New Chat", viewTitle(view("chat", emptyMap()), sessionName = { "unused" }))
    }
}
