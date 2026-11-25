package dev.supermux.desktop.applets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/** #117: ⌘R in an applet window reloads once, whether the menu, the key or both saw the press. */
class AppletReloadsTest {
    @Test fun oneReloadForAPressBothTheMenuAndTheKeySawAndOneForEachLaterPress() = runTest(UnconfinedTestDispatcher()) {
        var now = 1_000L
        val reloads = AppletReloads { now }
        val got = mutableListOf<Unit>()
        val listening = launch { reloads.requests.collect { got += it } }
        assertTrue(reloads.request(), "the key is handled")
        now += 50
        reloads.request() // the menu's accelerator, same press
        assertEquals(1, got.size)
        now += AppletReloads.DEDUP_MILLIS
        reloads.request()
        assertEquals(2, got.size, "a later press reloads again")
        listening.cancel()
    }
}
