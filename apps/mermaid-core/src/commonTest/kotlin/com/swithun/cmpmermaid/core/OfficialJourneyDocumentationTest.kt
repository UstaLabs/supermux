package com.swithun.cmpmermaid.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfficialJourneyDocumentationTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            TextMetrics(
                width = request.text.length * 8f,
                height = request.fontSize * request.lineHeight,
            )
        },
        theme = MermaidTheme.MermaidDefault,
    )

    @Test
    fun rendersEveryOfficialJourneyDocumentationExample() {
        assertEquals(1, officialJourneyDocumentationCases.size)
        val failures = officialJourneyDocumentationCases.mapNotNull { case ->
            when (val result = engine.render(case.source, context)) {
                is GMResult.Ok -> null
                is GMResult.Err -> "${case.id}: ${result.error.message}"
            }
        }

        assertTrue(
            failures.isEmpty(),
            failures.joinToString(
                prefix = "Failed Mermaid 12.0.0 User Journey documentation cases:\n",
                separator = "\n",
            ),
        )
    }
}
