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
        assertEquals(ViewTitle.Name("Main.kt"), viewTitleOf(view("editor", mapOf("mode" to "file", "path" to "src/Main.kt"))))
        assertEquals(ViewTitle.Word.FILES, viewTitleOf(view("editor", mapOf("mode" to "tree"))))
        assertEquals(ViewTitle.Word.FILES, viewTitleOf(view("editor", emptyMap())))
        assertEquals(ViewTitle.Word.CHANGES, viewTitleOf(view("editor", mapOf("mode" to "diff"))))
    }

    @Test
    fun aChatTabIsNamedAfterItsSession() {
        val bound = view("chat", mapOf("sessionId" to "s1"))
        assertEquals(ViewTitle.Name("Fix the launcher"), viewTitleOf(bound, sessionName = { if (it == "s1") "Fix the launcher" else null }))
        // Names not loaded yet (or blank) → the kind's own word.
        assertEquals(ViewTitle.Word.CHAT, viewTitleOf(bound, sessionName = { null }))
        assertEquals(ViewTitle.Word.CHAT, viewTitleOf(bound))
        // A pending "+ → Chat" tab has no session yet.
        assertEquals(ViewTitle.Word.NEW_CHAT, viewTitleOf(view("chat", emptyMap()), sessionName = { "unused" }))
    }

    @Test
    fun aFileTabOnAWindowsHostIsNamedAfterItsFile() {
        assertEquals(ViewTitle.Name("Main.kt"), viewTitleOf(view("editor", mapOf("mode" to "file", "path" to "C:\\Users\\me\\proj\\src\\Main.kt"))))
    }

    @Test
    fun aBrowserTabIsNamedAfterItsPage() {
        // The pane writes the page's title to the view, so every client's tab shows it.
        assertEquals(ViewTitle.Name("Vite"), viewTitleOf(view("browser", mapOf("url" to "https://vitejs.dev/guide/")).copy(title = "Vite")))
        // Before the page has a title: its site, or the port on the host.
        assertEquals(ViewTitle.Name("vitejs.dev"), viewTitleOf(view("browser", mapOf("url" to "https://vitejs.dev/guide/"))))
        assertEquals(ViewTitle.Name("localhost:5173"), viewTitleOf(view("browser", mapOf("url" to "http://localhost:5173/login"))))
        assertEquals(ViewTitle.Word.NEW_TAB, viewTitleOf(view("browser", emptyMap())))
    }
}
