package com.swithun.cmpmermaid.core.quadrant

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.SceneColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QuadrantParserTest {
    @Test
    fun parsesUpstreamLabelsStylesAndPrependedPointOrder() {
        val result = parser().parse(
            """
            QuadRantChart
              title Analytics and Business Intelligence Platforms
              x-AxIs "Completeness of Vision ❤" --> "High reach"
              y-AxIs Ability to Execute --> "High impact"
              quadrant-1 Leaders
              First:::priority: [0.2, 0.3]
              "Second : 東京": [1, 0] radius: 12, color: #ff3300
              classDef priority color: #109060, radius: 10, stroke-color: #310085, stroke-width: 4px
            """.trimIndent(),
        )
        val document = assertIs<GMResult.Ok<QuadrantDocument>>(result).value

        assertEquals("Analytics and Business Intelligence Platforms", document.title)
        assertEquals("Completeness of Vision ❤", document.xAxisLeftText)
        assertEquals("High reach", document.xAxisRightText)
        assertEquals("Ability to Execute", document.yAxisBottomText)
        assertEquals("High impact", document.yAxisTopText)
        assertEquals("Leaders", document.quadrantTexts[0])
        assertEquals(listOf("Second : 東京", "First"), document.points.map(QuadrantPoint::text))
        assertEquals(12f, document.points.first().style.radius)
        assertEquals(SceneColor(0xFFFF3300), document.points.first().style.color)
        assertEquals(
            QuadrantPointStyle(
                radius = 10f,
                color = SceneColor(0xFF109060),
                strokeColor = SceneColor(0xFF310085),
                strokeWidth = 4f,
            ),
            document.classes["priority"],
        )
    }

    @Test
    fun parsesCommentsSemicolonsEntitiesAndMultilineAccessibility() {
        val result = parser().parse(
            """
            %% leading comment
            quadrantChart; title Encoded &amp; visible
              accTitle: Accessible chart
              accDescr {
                First line
                Second line
              }
              x-axis Left --> Right %% trailing comment
              "A &lt; B": [0.4, 0.6]
            """.trimIndent(),
        )
        val document = assertIs<GMResult.Ok<QuadrantDocument>>(
            result,
            "Expected multiline accessibility source to parse: $result",
        ).value

        assertEquals("Encoded & visible", document.title)
        assertEquals("Accessible chart", document.accessibilityTitle)
        assertTrue(document.accessibilityDescription.orEmpty().contains("First line"))
        assertTrue(document.accessibilityDescription.orEmpty().contains("Second line"))
        assertEquals("A < B", document.points.single().text)
    }

    @Test
    fun returnsStructuredParseErrorsForMalformedUpstreamInputs() {
        val invalidSources = listOf(
            "quadrant-1 Missing header",
            "quadrantChart\nquadrant-5 Invalid",
            "quadrantChart\nPoint: [0.2, 1.1]",
            "quadrantChart\nPoint: [0.2, 0.4] radius: large",
            "quadrantChart\nPoint: [0.2, 0.4] color: ffaa",
            "quadrantChart\nPoint: [0.2, 0.4] stroke-width: 30",
            "quadrantChart\nclassDef invalid unsupported: value",
            "quadrantChart\nx-axis",
            "quadrantChart\naccDescr { unterminated",
        )

        invalidSources.forEach { source ->
            val result = parser().parse(source)
            val error = assertIs<GMResult.Err<MermaidError>>(result).error
            assertIs<MermaidError.Parse>(error)
        }
    }

    @Test
    fun appliesFrontmatterLineOffsetToParseErrors() {
        val result = QuadrantParser(
            diagramTitle = null,
            lineOffset = 6,
        ).parse(
            """
            quadrantChart
              Broken: [2, 0]
            """.trimIndent(),
        )
        val error = assertIs<GMResult.Err<MermaidError>>(result).error
        val parse = assertIs<MermaidError.Parse>(error)

        assertEquals(8, parse.line)
    }

    private fun parser(): QuadrantParser = QuadrantParser(
        diagramTitle = null,
        lineOffset = 0,
    )
}
