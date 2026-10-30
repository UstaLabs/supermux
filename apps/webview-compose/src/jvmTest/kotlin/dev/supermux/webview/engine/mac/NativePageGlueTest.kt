package dev.supermux.webview.engine.mac

import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.webkit.profileUuidString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

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

    @Test fun eachProfileNamesItsDataStore() {
        assertEquals(0 to null, NativePageGlue.storeOf(WebProfile.Default))
        assertEquals(1 to null, NativePageGlue.storeOf(WebProfile.Ephemeral))
        assertEquals(2 to profileUuidString("work"), NativePageGlue.storeOf(WebProfile.Named("work")))
    }
}
