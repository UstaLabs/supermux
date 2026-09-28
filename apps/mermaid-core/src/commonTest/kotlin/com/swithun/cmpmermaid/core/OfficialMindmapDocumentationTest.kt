package com.swithun.cmpmermaid.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfficialMindmapDocumentationTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            val lines = request.text.lines()
            TextMetrics(
                width = minOf(
                    request.maxWidth,
                    lines.maxOfOrNull(String::length).orZero() *
                        request.fontSize * 0.55f,
                ),
                height = lines.size * request.fontSize * request.lineHeight,
            )
        },
        theme = MermaidTheme.MermaidDefault,
    )

    @Test
    fun handlesEveryOfficialMindmapDocumentationExample() {
        assertEquals(13, officialMindmapDocumentationCases.size)
        val failures = officialMindmapDocumentationCases.mapNotNull { case ->
            val result = engine.render(case.source, context)
            val expectedUnsupported = case.expectedUnsupportedFeature
            when {
                expectedUnsupported == null && result is GMResult.Ok -> null
                expectedUnsupported == null && result is GMResult.Err ->
                    "${case.id}: ${result.error.message}"
                expectedUnsupported != null &&
                    result is GMResult.Err &&
                    result.error is MermaidError.UnsupportedFeature -> {
                    val error = result.error
                    if (error.feature == expectedUnsupported) {
                        null
                    } else {
                        "${case.id}: expected $expectedUnsupported, got ${error.feature}"
                    }
                }
                else -> "${case.id}: expected UnsupportedFeature($expectedUnsupported), got $result"
            }
        }

        assertTrue(
            failures.isEmpty(),
            failures.joinToString(
                prefix = "Failed Mermaid 12.0.0 Mindmap documentation cases:\n",
                separator = "\n",
            ),
        )
    }

    @Test
    fun keepsUnsupportedDocumentationFeaturesExplicit() {
        assertEquals(
            mapOf(
                "Mindmap icon" to 2,
                "Mindmap CSS class" to 1,
            ),
            officialMindmapDocumentationCases
                .mapNotNull(MermaidMindmapDocCase::expectedUnsupportedFeature)
                .groupingBy { feature -> feature }
                .eachCount(),
        )
        assertEquals(
            10,
            officialMindmapDocumentationCases.count { case ->
                case.expectedUnsupportedFeature == null
            },
        )
    }

    private fun Int?.orZero(): Int = this ?: 0
}
