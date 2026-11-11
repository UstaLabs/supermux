package dev.supermux.ui.keys

import dev.supermux.editor.core.KeyChord
import dev.supermux.proto.ViewDto
import dev.supermux.ui.shell.paneKeyContext
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Browser tab's keys (#105) are #96's registry's: the platform's modifier, never Ctrl on Apple or Meta elsewhere. */
class BrowserKeysTest {
    @Test fun reloadIsCtrlROffAppleAndCmdROnApple() {
        assertTrue(KeyChord("r", ctrl = true) in DefaultKeymap.chordsFor(AppCommands.BrowserReload, KeyOs.Other))
        assertEquals(listOf(KeyChord("r", meta = true)), DefaultKeymap.chordsFor(AppCommands.BrowserReload, KeyOs.Apple))
    }

    @Test fun backAndOpenExternallyUseTheModifierToo() {
        assertTrue(KeyChord("[", ctrl = true) in DefaultKeymap.chordsFor(AppCommands.BrowserBack, KeyOs.Other))
        assertEquals(listOf(KeyChord("[", meta = true)), DefaultKeymap.chordsFor(AppCommands.BrowserBack, KeyOs.Apple))
        assertEquals(listOf(KeyChord("o", ctrl = true, shift = true)), DefaultKeymap.chordsFor(AppCommands.BrowserOpenExternally, KeyOs.Other))
    }

    private fun other(command: AppCommand) = DefaultKeymap.chordsFor(command, KeyOs.Other).toSet()
    private fun apple(command: AppCommand) = DefaultKeymap.chordsFor(command, KeyOs.Apple).toSet()
    private fun keys(spec: String, apple: Boolean) = KeyChord.parse(spec, apple)

    /** #122: Chrome's own keys for a tab work too, as aliases of the same commands (Chrome's list, Windows/Linux column). */
    @Test fun chromesAliasesOffApple() {
        assertEquals(setOf(keys("Ctrl-r", false), keys("F5", false)), other(AppCommands.BrowserReload))
        assertEquals(setOf(keys("Ctrl-[", false), keys("Alt-ArrowLeft", false)), other(AppCommands.BrowserBack))
        assertEquals(setOf(keys("Ctrl-]", false), keys("Alt-ArrowRight", false)), other(AppCommands.BrowserForward))
        assertEquals(setOf(keys("Ctrl-l", false), keys("Alt-d", false), keys("F6", false)), other(AppCommands.BrowserFocusAddress))
        assertEquals(setOf(keys("Ctrl-=", false), keys("Ctrl-Shift-=", false), keys("Ctrl-+", false)), other(AppCommands.BrowserZoomIn))
    }

    /** On Apple only Chrome's ⌘ keys: F5 and the Alt arrows are not Safari's or Chrome's there. */
    @Test fun appleKeepsTheCommandKeysOnly() {
        assertEquals(setOf(keys("Mod-r", true)), apple(AppCommands.BrowserReload))
        assertEquals(setOf(keys("Mod-l", true)), apple(AppCommands.BrowserFocusAddress))
        assertEquals(setOf(keys("Mod-=", true), keys("Mod-Shift-=", true), keys("Mod-+", true)), apple(AppCommands.BrowserZoomIn))
    }

    @Test fun findNextAndPreviousAreChromes() {
        assertEquals(setOf(keys("Ctrl-g", false), keys("F3", false)), other(AppCommands.FindNext))
        assertEquals(setOf(keys("Ctrl-Shift-g", false), keys("Shift-F3", false)), other(AppCommands.FindPrevious))
        assertEquals(setOf(keys("Mod-g", true)), apple(AppCommands.FindNext))
        assertEquals(setOf(keys("Mod-Shift-g", true)), apple(AppCommands.FindPrevious))
    }

    @Test fun inspectIsF12OrTheDevToolsChord() {
        assertEquals(setOf(keys("F12", false), keys("Ctrl-Shift-i", false)), other(AppCommands.BrowserInspect))
        assertEquals(setOf(keys("Mod-Alt-i", true)), apple(AppCommands.BrowserInspect))
    }

    @Test fun screenshotIsModShiftS() {
        assertEquals(setOf(keys("Ctrl-Shift-s", false)), other(AppCommands.BrowserScreenshot))
        assertEquals(setOf(keys("Mod-Shift-s", true)), apple(AppCommands.BrowserScreenshot))
    }

    @Test fun escapeStopsLoading() {
        assertEquals(setOf(keys("Escape", false)), other(AppCommands.BrowserStop))
        assertEquals(setOf(keys("Escape", true)), apple(AppCommands.BrowserStop))
    }

    @Test fun everyBrowserCommandIsListedAndBound() {
        val browser = AppCommands.all.filter { it.group == CommandGroup.Browser }
        assertEquals(12, browser.size)
        for (c in browser) assertTrue(DefaultKeymap.chordsFor(c, KeyOs.Other).isNotEmpty(), c.id)
    }

    @Test fun aBrowserViewIsKeyContextBrowserAndNotOpaque() {
        val view = ViewDto(id = "v1", workspaceId = "w1", kind = "browser", state = JsonObject(emptyMap()))
        assertEquals(KeyContext.Browser, paneKeyContext(view))
        assertFalse(KeyContext.Browser.opaque)
    }
}
