package dev.supermux.webview.sample

import kotlin.test.Test
import kotlin.test.assertEquals

/** A page's `accept` list, as the system document picker takes it: MIME types only. */
class FileTypesTest {
    private val known = mapOf("pdf" to "application/pdf", "png" to "image/png", "csv" to "text/csv")

    private fun types(vararg accept: String) = pickerTypes(accept.toList()) { known[it] }

    @Test fun extensionsBecomeTheirMimeTypes() {
        assertEquals(listOf("application/pdf"), types(".pdf"))
        assertEquals(listOf("application/pdf", "image/png"), types(".PDF", " .png "))
    }

    @Test fun mimeTypesAndWildcardsStayAsTheyAre() {
        assertEquals(listOf("image/*", "application/pdf"), types("image/*", "application/pdf", ".pdf"))
    }

    /** Nothing asked, or an extension nobody knows: anything may be picked, and the page checks what it gets. */
    @Test fun anythingWhenNothingOrSomethingUnknownIsAsked() {
        assertEquals(listOf("*/*"), types())
        assertEquals(listOf("*/*"), types("", " "))
        assertEquals(listOf("*/*"), types(".pdf", ".xyz"))
    }
}
