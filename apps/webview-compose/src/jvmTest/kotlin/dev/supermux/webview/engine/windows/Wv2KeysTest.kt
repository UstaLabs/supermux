package dev.supermux.webview.engine.windows

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #122: which accelerators a page gets first (Chrome's rule), and which the app keeps. Flags: 1 Ctrl, 2 Shift, 4 Alt, 8 Win. */
class Wv2KeysTest {
    @Test fun aPageGetsTheEditorAndFindKeysFirst() {
        assertTrue(Wv2Keys.pageFirst('B'.code, 1))
        assertTrue(Wv2Keys.pageFirst('K'.code, 1))
        assertTrue(Wv2Keys.pageFirst('F'.code, 1))
        assertTrue(Wv2Keys.pageFirst('R'.code, 1))
        assertTrue(Wv2Keys.pageFirst(0x74, 0)) // F5
        assertTrue(Wv2Keys.pageFirst(0x25, 4)) // Alt+Left
    }

    @Test fun theAppKeepsTabsWindowsAndItsOwnKeys() {
        for (key in listOf('T', 'W', 'N')) {
            assertFalse(Wv2Keys.pageFirst(key.code, 1), "Ctrl+$key")
            assertFalse(Wv2Keys.pageFirst(key.code, 1 or 2), "Ctrl+Shift+$key")
        }
        for (digit in '1'..'9') assertFalse(Wv2Keys.pageFirst(digit.code, 1), "Ctrl+$digit")
        assertFalse(Wv2Keys.pageFirst(0x09, 1)) // Ctrl+Tab
        assertFalse(Wv2Keys.pageFirst(0x21, 1)) // Ctrl+PgUp
        assertFalse(Wv2Keys.pageFirst(0x22, 1)) // Ctrl+PgDn
        assertFalse(Wv2Keys.pageFirst(0xDC, 1)) // Ctrl+\
        assertFalse(Wv2Keys.pageFirst(0xDC, 1 or 4)) // Ctrl+Alt+\
        assertFalse(Wv2Keys.pageFirst(0xBC, 1)) // Ctrl+,
        assertFalse(Wv2Keys.pageFirst(0x73, 4)) // Alt+F4
        assertFalse(Wv2Keys.pageFirst('E'.code, 8)) // Win+E: the system's
    }

    /** The key script's answer: whether the page used the key, and which key (its virtual-key code). */
    @Test fun aVerdictNamesItsKey() {
        assertEquals(true to 'K'.code, Wv2Keys.verdict("used:75"))
        assertEquals(false to 'K'.code, Wv2Keys.verdict("unused:75"))
        assertNull(Wv2Keys.verdict("used"))
        assertNull(Wv2Keys.verdict("maybe:75"))
    }

    /** A modifier's own keydown (Ctrl, Shift, Alt, Meta) is no shortcut: the script says nothing about it. */
    @Test fun theScriptSkipsAModifiersOwnKeydown() {
        val source = Json.parseToJsonElement(Wv2Keys.scriptParams).jsonObject["source"]!!.jsonPrimitive.content
        assertTrue("Control|Shift|Alt|Meta" in source, source)
    }
}
