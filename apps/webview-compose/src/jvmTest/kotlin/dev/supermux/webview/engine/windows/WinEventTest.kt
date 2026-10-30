package dev.supermux.webview.engine.windows

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WinEventTest {
    @Test fun aMessageKeepsWhenTheGlueHadIt() {
        // System.nanoTime's clock (QPC): how long ago the document's content was presented is measured from there.
        assertEquals(WinEvent.Message(3, "0123456789abcdef paint 31.5", arrivedNs = 42L), WinEvent.decode(WinGlue.MESSAGE, 3, 42L, 0L, "0123456789abcdef paint 31.5", null, null, null))
    }

    @Test fun aNavigationsFlagsAndVerdictDecode() {
        val flags = (WinGlue.NAV_MAIN_FRAME or WinGlue.NAV_REDIRECT or ((1 + 1) shl 4)).toLong() or (WinGlue.OWN.toLong() shl 16)
        assertEquals(
            WinEvent.NavStarting(epoch = 3, id = 41, url = "https://a.example/", mainFrame = true, redirect = true, user = false, kind = 1, verdict = WinGlue.OWN),
            WinEvent.decode(WinGlue.NAV_STARTING, 3, 41, flags, "https://a.example/", null, null, null),
        )
    }

    @Test fun aCommitCarriesTheCapturesSequence() {
        assertEquals(
            WinEvent.Committed(epoch = 4, id = 9, errorPage = false, url = "https://a.example/", frameSeq = 17),
            WinEvent.decode(WinGlue.COMMITTED, 4, 9, 17L shl 1, "https://a.example/", null, null, null),
        )
    }

    @Test fun aCompletedNavigationCarriesItsErrorAndStatus() {
        val flags = 1L or (14L shl 8) or (404L shl 16)
        assertEquals(
            WinEvent.Completed(epoch = 2, id = 9, success = true, webError = 14, httpStatus = 404, url = "https://a.example/x"),
            WinEvent.decode(WinGlue.COMPLETED, 2, 9, flags, "https://a.example/x", null, null, null),
        )
    }

    @Test fun aCustomCursorKeepsItsPixels() {
        val data = java.nio.ByteBuffer.allocate(4 * 6).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(1).putInt(2).putInt(0).putInt(1).putInt(0x11223344).putInt(0x55667788.toInt()).array()
        val cursor = WinEvent.decode(WinGlue.CURSOR, 0, 0, 0, null, null, null, data) as WinEvent.Cursor
        assertEquals(0, cursor.systemId)
        val custom = checkNotNull(cursor.custom)
        assertEquals(listOf(1, 2, 0, 1), listOf(custom.width, custom.height, custom.hotX, custom.hotY))
        assertContentEquals(intArrayOf(0x11223344, 0x55667788.toInt()), custom.argb)
    }

    @Test fun aCustomCursorWhosePixelsDontFitIsTheArrow() {
        val data = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(64).putInt(64).putInt(0).putInt(0).array()
        assertEquals(WinEvent.Cursor(32512, null), WinEvent.decode(WinGlue.CURSOR, 0, 0, 0, null, null, null, data))
    }

    @Test fun anUnknownEventIsNothing() {
        assertNull(WinEvent.decode(999, 0, 0, 0, null, null, null, null))
    }
}
