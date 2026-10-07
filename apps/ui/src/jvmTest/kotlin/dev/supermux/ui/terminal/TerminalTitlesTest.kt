package dev.supermux.ui.terminal

import dev.supermux.proto.ViewDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A terminal tab wears its program's title, unless the user named the tab. */
class TerminalTitlesTest {
    private fun view(id: String, kind: String, title: String? = null) = ViewDto(
        id = id, workspaceId = "w1", kind = kind, title = title,
        state = JsonObject(mapOf("terminalId" to JsonPrimitive("main"))),
    )

    @AfterTest fun clear() {
        TerminalTitles.forget("t1")
        TerminalTitles.forget("c1")
    }

    @Test fun aProgramTitleNamesTheTerminalTab() {
        assertEquals("main", liveViewTitle(view("t1", "terminal")))
        TerminalTitles.set("t1", "ahmet@box: ~/src")
        assertEquals("ahmet@box: ~/src", liveViewTitle(view("t1", "terminal")))
    }

    @Test fun aTitleTheUserGaveTheViewWins() {
        TerminalTitles.set("t1", "vim")
        assertEquals("build", liveViewTitle(view("t1", "terminal", title = "build")))
    }

    @Test fun aBlankTitleFallsBackAndOnlyTerminalsAreAffected() {
        TerminalTitles.set("t1", "vim")
        TerminalTitles.set("t1", "  ")
        assertEquals("main", liveViewTitle(view("t1", "terminal")))
        TerminalTitles.set("c1", "not a terminal")
        assertEquals("New Chat", liveViewTitle(view("c1", "chat")))
    }
}
