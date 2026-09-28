package com.swithun.cmpmermaid.core.flowchart.upstream.mermaid

import kotlin.test.Test
import kotlin.test.assertEquals

class MermaidUrlSanitizerTest {
    @Test
    fun blocksUnsafeProtocolsAfterRepeatedDecoding() {
        val unsafeUrls = listOf(
            "javascript:alert(1)",
            "JaVaScRiPt:alert(1)",
            "%6a%61%76%61%73%63%72%69%70%74:alert(1)",
            "java%0ascript:alert(1)",
            "&#106;&#97;vascript:alert(1)",
            "data:text/html,payload",
            "vbscript:msgbox(1)",
        )

        unsafeUrls.forEach { source ->
            assertEquals("about:blank", MermaidUrlSanitizer.sanitize(source), source)
        }
    }

    @Test
    fun preservesSupportedAbsoluteAndRelativeUrls() {
        assertEquals(
            "https://example.com/path?q=1",
            MermaidUrlSanitizer.sanitize(" https://example.com/path?q=1 "),
        )
        assertEquals(
            "mailto:user@example.com",
            MermaidUrlSanitizer.sanitize("mailto:user@example.com"),
        )
        assertEquals("/relative/path", MermaidUrlSanitizer.sanitize("/relative/path"))
        assertEquals("./relative/path", MermaidUrlSanitizer.sanitize("./relative/path"))
    }
}
