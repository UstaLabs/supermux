package dev.supermux.webview.engine.mac

import androidx.compose.ui.geometry.Rect
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.webkit.profileUuidString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** What `NativePageGlue` hands the glue, packed as `WVPage.m` reads it. */
class NativePageGlueTest {
    @Test fun inputFlagsAreInteractiveShownAndWheel() {
        assertEquals(0, NativePageGlue.inputFlags(interactive = false, shown = false, wheelToPage = false))
        assertEquals(1, NativePageGlue.inputFlags(interactive = true, shown = false, wheelToPage = false))
        assertEquals(2, NativePageGlue.inputFlags(interactive = false, shown = true, wheelToPage = false))
        assertEquals(7, NativePageGlue.inputFlags(interactive = true, shown = true, wheelToPage = true))
    }

    @Test fun headersTravelAsNamesAndValuesInOneOrder() {
        val (names, values) = NativePageGlue.headerArrays(linkedMapOf("Accept" to "text/html", "X-Test" to "1"))
        assertContentEquals(arrayOf("Accept", "X-Test"), names)
        assertContentEquals(arrayOf("text/html", "1"), values)
    }

    /**
     * Minor 2: a released glue's handle is 0, and no call reaches the glue with it (the page it named is freed); the
     * calls that answer say no. Nothing native runs here: a call that reached it would fail to link, or touch nothing.
     */
    @Test fun aReleasedGlueRefusesEveryCall() {
        val glue = NativePageGlue(token = 1, handle = 0)
        glue.place(7L, Rect(0f, 0f, 10f, 10f), Rect(0f, 0f, 10f, 10f), 2f, hidden = false)
        glue.loadUrl(1, "https://a.test/", mapOf("Accept" to "text/html"))
        glue.setMediaSuspended(false)
        glue.answerDialog(3, confirmed = true, text = null)
        assertFalse(glue.restoreState(1, ByteArray(4)))
        assertFalse(glue.showFloor(1))
        glue.release()
    }

    @Test fun eachProfileNamesItsDataStore() {
        assertEquals(0 to null, NativePageGlue.storeOf(WebProfile.Default))
        assertEquals(1 to null, NativePageGlue.storeOf(WebProfile.Ephemeral))
        assertEquals(2 to profileUuidString("work"), NativePageGlue.storeOf(WebProfile.Named("work")))
    }
}
