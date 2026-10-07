package com.swithun.cmpmermaid.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfficialGitGraphDocumentationTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            val lines = request.text.lines()
            TextMetrics(
                width = lines.maxOfOrNull(String::length).orZero() *
                    request.fontSize * 0.55f,
                height = lines.size * request.fontSize * request.lineHeight,
            )
        },
        theme = MermaidTheme.MermaidDefault,
    )

    @Test
    fun rendersEveryOfficialGitGraphDocumentationExample() {
        assertEquals(35, officialGitGraphDocumentationCases.size)
        val failures = officialGitGraphDocumentationCases.mapNotNull { case ->
            when (val result = engine.render(case.source, context)) {
                is GMResult.Ok -> null
                is GMResult.Err -> "${case.id}: ${result.error.message}"
            }
        }

        assertTrue(
            failures.isEmpty(),
            failures.joinToString(
                prefix = "Failed Mermaid 12.0.0 Git Graph documentation cases:\n",
                separator = "\n",
            ),
        )
    }

    private fun Int?.orZero(): Int = this ?: 0
}
