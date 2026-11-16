package dev.supermux.desktop.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A page's file input on Windows: its accept list becomes the pattern the open dialog filters by (#157). */
class DesktopPageDeviceTest {
    @Test fun extensionsAndMimeFamiliesBecomeOnePattern() {
        assertEquals(
            "*.png;*.jpg;*.jpeg;*.gif;*.webp;*.bmp;*.svg;*.heic;*.heif;*.avif;*.pdf",
            windowsPattern(listOf("image/*", ".pdf")),
        )
        assertEquals("*.csv;*.txt", windowsPattern(listOf(" .CSV ", "text/plain")))
    }

    @Test fun anEntryWithNoExtensionsToStandForItHidesNothing() {
        assertNull(windowsPattern(listOf(".pdf", "application/x-custom")))
        assertNull(windowsPattern(listOf("model/*")))
        assertNull(windowsPattern(emptyList()))
        assertNull(windowsPattern(listOf(" ")))
    }
}
