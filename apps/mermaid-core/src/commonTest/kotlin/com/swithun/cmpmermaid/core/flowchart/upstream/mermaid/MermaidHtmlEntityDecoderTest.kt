package com.swithun.cmpmermaid.core.flowchart.upstream.mermaid

import kotlin.test.Test
import kotlin.test.assertEquals

class MermaidHtmlEntityDecoderTest {
    @Test
    fun decodesWhatwgNamedAndNumericReferences() {
        assertEquals(
            "© € Α ≂̸ 😀",
            MermaidHtmlEntityDecoder.decode(
                "&copy; &euro; &Alpha; &NotEqualTilde; &#x1F600;",
            ),
        )
    }

    @Test
    fun preservesUnknownReferencesAndDecodesOnlyOneLayer() {
        assertEquals(
            "&bogus; &copy;",
            MermaidHtmlEntityDecoder.decode("&bogus; &amp;copy;"),
        )
    }

    @Test
    fun appliesHtmlNumericReplacementRules() {
        assertEquals(
            "€ \uFFFD \uFFFD",
            MermaidHtmlEntityDecoder.decode("&#x80; &#0; &#xD800;"),
        )
    }
}
