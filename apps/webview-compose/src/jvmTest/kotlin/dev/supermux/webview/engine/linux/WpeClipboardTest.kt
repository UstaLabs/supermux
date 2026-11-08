package dev.supermux.webview.engine.linux

import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WpeClipboardTest {
    private val system = Clipboard("test")
    private val told = mutableListOf<String?>()
    private val clipboard = WpeClipboard({ system }) { told += it }

    @Test fun webKitIsToldWhatTheSystemsClipboardHoldsWhenItChanged() {
        system.setContents(StringSelection("from another app"), null)
        clipboard.sync()
        clipboard.sync()
        assertEquals(listOf<String?>("from another app"), told, "once, until it changes")
        system.setContents(StringSelection("again"), null)
        clipboard.sync()
        assertEquals(listOf<String?>("from another app", "again"), told)
    }

    @Test fun aPagesCopyGoesToTheSystemsClipboardAndIsntToldBack() {
        // WebKit keeps the page's own copy (its HTML too): told back as plain text, a paste in the page would lose it.
        clipboard.onPageCopied("copied in a page")
        assertEquals("copied in a page", system.getData(DataFlavor.stringFlavor))
        clipboard.sync()
        assertTrue(told.isEmpty(), "$told")
    }

    @Test fun noClipboardIsNoText() {
        val none = WpeClipboard({ null }) { told += it }
        none.sync()
        assertEquals(listOf<String?>(null), told)
    }

    @Test fun pasteShortcuts() {
        assertTrue(WpeClipboard.isPaste(KeyEvent.VK_V, ctrl = true, shift = false))
        assertTrue(WpeClipboard.isPaste(KeyEvent.VK_V, ctrl = true, shift = true))
        assertTrue(WpeClipboard.isPaste(KeyEvent.VK_INSERT, ctrl = false, shift = true))
        assertFalse(WpeClipboard.isPaste(KeyEvent.VK_V, ctrl = false, shift = false))
        assertFalse(WpeClipboard.isPaste(KeyEvent.VK_C, ctrl = true, shift = false))
    }
}
