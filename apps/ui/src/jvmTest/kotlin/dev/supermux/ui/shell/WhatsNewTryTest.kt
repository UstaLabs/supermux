package dev.supermux.ui.shell

import dev.supermux.ui.nav.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What's new's "Try it" (#90): each leaves the page for the real thing. */
class WhatsNewTryTest {

    @Test fun asking_an_agent_for_a_card_leaves_the_words_in_the_open_chat_unsent() {
        val ui = ShellUiState().apply { selectedId = "s1"; openWhatsNew("0.17.7") }
        assertTrue(ui.askAgentForCard("Show it as a card"))
        assertEquals(Route.Home, ui.currentRoute)
        val (session, attach) = ui.externalAttach!!
        assertEquals("s1", session)
        assertEquals("Show it as a card", attach.text)
        assertFalse(attach.send, "he sends it himself")
        assertTrue(attach.files.isEmpty())
    }

    @Test fun with_no_chat_open_there_is_no_one_to_ask() {
        val ui = ShellUiState().apply { openWhatsNew("0.17.7") }
        assertFalse(ui.askAgentForCard("Show it as a card"))
        assertNull(ui.externalAttach)
    }

    @Test fun a_browser_tab_is_asked_of_the_workspace_on_show_once() {
        val ui = ShellUiState().apply { openWhatsNew("0.17.7") }
        ui.requestBrowserTab()
        assertEquals(Route.Home, ui.currentRoute)
        assertTrue(ui.browserTabRequested)
        ui.consumeBrowserTabRequest()
        assertFalse(ui.browserTabRequested)
    }
}
