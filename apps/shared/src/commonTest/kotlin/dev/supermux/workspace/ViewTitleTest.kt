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

    @Test
    fun aFileTabOnAWindowsHostIsNamedAfterItsFile() {
        assertEquals("Main.kt", viewTitle(view("editor", mapOf("mode" to "file", "path" to "C:\\Users\\me\\proj\\src\\Main.kt"))))
    }

    @Test
    fun aBrowserTabIsNamedAfterItsPage() {
        // The pane writes the page's title to the view, so every client's tab shows it.
        assertEquals("Vite", viewTitle(view("browser", mapOf("url" to "https://vitejs.dev/guide/")).copy(title = "Vite")))
        // Before the page has a title: its site, or the port on the host.
        assertEquals("vitejs.dev", viewTitle(view("browser", mapOf("url" to "https://vitejs.dev/guide/"))))
        assertEquals("localhost:5173", viewTitle(view("browser", mapOf("url" to "http://localhost:5173/login"))))
        assertEquals("New tab", viewTitle(view("browser", emptyMap())))
    }
}
