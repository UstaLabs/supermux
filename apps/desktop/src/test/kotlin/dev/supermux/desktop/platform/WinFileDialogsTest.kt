package dev.supermux.desktop.platform

import kotlin.test.Test
import kotlin.test.assertContentEquals

/** Windows' dialogs are reached by GUID: its first three parts little-endian, the last eight bytes as written. */
class WinFileDialogsTest {
    @Test fun aGuidIsLaidOutAsWindowsReadsIt() {
        assertContentEquals(
            byteArrayOf(0x1E, 0x6D, 0x82.toByte(), 0x43, 0x18, 0xE7.toByte(), 0xEE.toByte(), 0x42,
                0xBC.toByte(), 0x55, 0xA1.toByte(), 0xE2.toByte(), 0x61, 0xC3.toByte(), 0x7B, 0xFE.toByte()),
            WinFileDialogs.guidBytes("{43826D1E-E718-42EE-BC55-A1E261C37BFE}"),
        )
        assertContentEquals(
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0xC0.toByte(), 0, 0, 0, 0, 0, 0, 0x46),
            WinFileDialogs.guidBytes("{00000000-0000-0000-C000-000000000046}"),
        )
    }
}
