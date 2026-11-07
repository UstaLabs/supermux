package dev.supermux.desktop.platform

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A link reaches the browser. AWT's browse was the only way: where it cannot browse (a Linux desktop without GNOME's
 * libraries, or a headless-ish session) a click did nothing, and a browse that threw was only logged.
 */
class BrowserLauncherTest {
    private val opened = mutableListOf<String>()
    private val browsed = mutableListOf<URI>()

    @Test
    fun `where AWT cannot browse, the OS opener takes the link`() {
        openUrlWith("https://example.com/a?b=1&c=2", browse = null) { opened += it }
        assertEquals(listOf("https://example.com/a?b=1&c=2"), opened)
    }

    @Test
    fun `when AWT's browse fails, the OS opener takes the link`() {
        openUrlWith("https://example.com/", browse = { throw java.io.IOException("no default browser") }) { opened += it }
        assertEquals(listOf("https://example.com/"), opened)
    }

    @Test
    fun `AWT's browse is used when it works, and the opener is not`() {
        openUrlWith("https://example.com/x", browse = { browsed += it }) { opened += it }
        assertEquals(listOf(URI("https://example.com/x")), browsed)
        assertEquals(emptyList(), opened)
    }

    @Test
    fun `a link that may not open reaches neither the browser nor the OS opener`() {
        val captured = mutableListOf<String>()
        openInBrowserOverride = { captured += it }
        try {
            for (url in listOf("javascript:alert(1)", "file://host/share/x.exe", "file:///Applications/Calculator.app", "vbscript:x")) {
                openInBrowser(url)
            }
            openInBrowser("https://example.com/")
        } finally {
            openInBrowserOverride = null
        }
        assertEquals(listOf("https://example.com/"), captured)
    }

    @Test
    fun `text that is not a URL is opened by neither`() {
        openUrlWith("https://exa mple.com/ bad", browse = { browsed += it }) { opened += it }
        assertEquals(emptyList(), browsed)
        assertEquals(emptyList(), opened)
    }
}
