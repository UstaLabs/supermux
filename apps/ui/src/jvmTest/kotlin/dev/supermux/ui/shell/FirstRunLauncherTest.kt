package dev.supermux.ui.shell

import dev.supermux.ui.nav.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #128: with no sessions the app opens straight on starting one (the launcher, whose first-run step is "Where should
 * your agents work?") instead of an empty "select a session" pane. Once per launch, and never for someone with sessions.
 */
class FirstRunLauncherTest {

    @Test fun no_sessions_once_they_have_loaded_opens_the_launcher() {
        val ui = ShellUiState()
        assertTrue(ui.openLauncherOnFirstRun(loaded = true, hasSessions = false))
        assertTrue(ui.launcherOpen)
    }

    @Test fun nothing_happens_before_the_sessions_have_loaded() {
        val ui = ShellUiState()
        assertFalse(ui.openLauncherOnFirstRun(loaded = false, hasSessions = false))
        assertEquals(Route.Home, ui.currentRoute)
        // ...and the decision is still to come.
        assertTrue(ui.openLauncherOnFirstRun(loaded = true, hasSessions = false))
    }

    @Test fun someone_with_sessions_is_never_taken_anywhere() {
        val ui = ShellUiState()
        assertFalse(ui.openLauncherOnFirstRun(loaded = true, hasSessions = true))
        assertEquals(Route.Home, ui.currentRoute)
        // Archiving the last one later doesn't push them into the launcher either.
        assertFalse(ui.openLauncherOnFirstRun(loaded = true, hasSessions = false))
        assertEquals(Route.Home, ui.currentRoute)
    }

    @Test fun closing_it_keeps_it_closed_for_this_launch() {
        val ui = ShellUiState()
        ui.openLauncherOnFirstRun(loaded = true, hasSessions = false)
        ui.closeLauncher()
        assertFalse(ui.openLauncherOnFirstRun(loaded = true, hasSessions = false))
        assertEquals(Route.Home, ui.currentRoute)
    }

    @Test fun a_session_already_selected_or_another_page_open_is_left_alone() {
        val selected = ShellUiState().apply { selectedId = "s1" }
        assertFalse(selected.openLauncherOnFirstRun(loaded = true, hasSessions = false))
        val elsewhere = ShellUiState().apply { openSettings() }
        assertFalse(elsewhere.openLauncherOnFirstRun(loaded = true, hasSessions = false))
        assertTrue(elsewhere.settingsOpen)
    }
}

/** #134 (ADEMark): a normal launch is ready to type in the chat it opens on (with a pointer; a phone waits for a tap). */
class LaunchComposerFocusTest {
    @Test fun the_open_chat_gets_the_cursor_once_at_launch() {
        val ui = ShellUiState().apply { selectedId = "s1" }
        ui.focusComposerOnLaunch(pointer = true)
        assertEquals("s1", ui.composerFocusFor)
        ui.composerFocusFor = null
        ui.focusComposerOnLaunch(pointer = true)
        assertEquals(null, ui.composerFocusFor, "once per launch, not every time the shell recomposes")
    }

    @Test fun a_phone_or_no_open_chat_gets_nothing() {
        val phone = ShellUiState().apply { selectedId = "s1" }
        phone.focusComposerOnLaunch(pointer = false)
        assertEquals(null, phone.composerFocusFor)
        val none = ShellUiState()
        none.focusComposerOnLaunch(pointer = true)
        assertEquals(null, none.composerFocusFor)
    }
}
