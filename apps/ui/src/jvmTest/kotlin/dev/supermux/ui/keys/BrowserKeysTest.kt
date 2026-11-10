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
        assertEquals(listOf(KeyChord("r", ctrl = true)), DefaultKeymap.chordsFor(AppCommands.BrowserReload, KeyOs.Other))
        assertEquals(listOf(KeyChord("r", meta = true)), DefaultKeymap.chordsFor(AppCommands.BrowserReload, KeyOs.Apple))
    }

    @Test fun backAndOpenExternallyUseTheModifierToo() {
        assertEquals(listOf(KeyChord("[", ctrl = true)), DefaultKeymap.chordsFor(AppCommands.BrowserBack, KeyOs.Other))
        assertEquals(listOf(KeyChord("[", meta = true)), DefaultKeymap.chordsFor(AppCommands.BrowserBack, KeyOs.Apple))
        assertEquals(listOf(KeyChord("o", ctrl = true, shift = true)), DefaultKeymap.chordsFor(AppCommands.BrowserOpenExternally, KeyOs.Other))
    }

    @Test fun everyBrowserCommandIsListedAndBound() {
        val browser = AppCommands.all.filter { it.group == CommandGroup.Browser }
        assertEquals(8, browser.size)
        for (c in browser) assertTrue(DefaultKeymap.chordsFor(c, KeyOs.Other).isNotEmpty(), c.id)
    }

    @Test fun aBrowserViewIsKeyContextBrowserAndNotOpaque() {
        val view = ViewDto(id = "v1", workspaceId = "w1", kind = "browser", state = JsonObject(emptyMap()))
        assertEquals(KeyContext.Browser, paneKeyContext(view))
        assertFalse(KeyContext.Browser.opaque)
    }
}
