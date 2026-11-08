package dev.supermux.webview.testing

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The test driver's window copies, as the glue hands them over (from webview-compose's DesktopPlatformTest). */
class MacEngineTestingTest {
    @Test fun aWindowCopyCarriesItsSizeFirst() {
        val copied = MacEngineTesting.copiedOf(byteArrayOf(0, 0, 0, 2, 0, 0, 0, 1, 1, 2, 3, 4, 5, 6, 7, 8))
        assertEquals(2, copied!!.width)
        assertEquals(1, copied.height)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), copied.rgba)
        assertNull(MacEngineTesting.copiedOf(byteArrayOf(0, 0, 0, 2, 0, 0, 0, 2, 1, 2, 3, 4)), "too short for 2 x 2")
    }
}
