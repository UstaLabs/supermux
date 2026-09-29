package com.swithun.cmpmermaid.core.radar

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRadarOptions
import com.swithun.cmpmermaid.core.radar.upstream.mermaid.RadarAstAxis
import com.swithun.cmpmermaid.core.radar.upstream.mermaid.RadarAstEntry
import com.swithun.cmpmermaid.core.radar.upstream.mermaid.RadarDb
import com.swithun.cmpmermaid.core.radar.upstream.mermaid.RadarGraticule
import com.swithun.cmpmermaid.core.radar.upstream.mermaid.RadarParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RadarParserTest {
    @Test
    fun parsesAllHeaderFormsAndGlobalSpacing() {
        listOf(
            "radar-beta",
            "  radar-beta  ",
            "\tradar-beta\t",
            "\n\tradar-beta\n",
            "radar-beta:",
            "radar-beta :",
            "radar-beta:\tticks 3",
        ).forEach { source ->
            assertIs<GMResult.Ok<RadarDb>>(parser().parse(source), source)
        }
    }

    @Test
    fun parsesMetadataLabelsAndEscapes() {
        val db = parse(
            """
            radar-beta title Radar   chart
              accTitle: Accessible radar
              accDescr {
                First   line

                Second line
              }
              axis speed["Speed\nIndex"], quality ['Quality']
              curve current["Current\tPlan"] { 1, 2 }
            """.trimIndent(),
            diagramTitle = "Frontmatter title",
        )

        assertEquals("Radar chart", db.diagramTitle)
        assertEquals("Accessible radar", db.accessibilityTitle)
        assertEquals("First line\nSecond line", db.accessibilityDescription)
        assertEquals(listOf("Speed\nIndex", "Quality"), db.getAxes().map { it.label })
        assertEquals("Current\tPlan", db.getCurves().single().label)
    }

    @Test
    fun preservesPositionalEntriesAndReordersDetailedEntries() {
        val db = parse(
            """
            radar-beta
              axis A["Axis A"], B["Axis B"], C
              curve positional { 3, 2, 1 }
              curve detailed { C: 30, A 10, B: 20, B: 99, extra: 100 }
            """.trimIndent(),
        )

        assertEquals(listOf(3.0, 2.0, 1.0), db.getCurves()[0].entries)
        assertEquals(listOf(10.0, 20.0, 30.0), db.getCurves()[1].entries)
    }

    @Test
    fun appliesDefaultsLastWinsAndTickCap() {
        val defaults = parse("radar-beta").getOptions()
        assertEquals(true, defaults.showLegend)
        assertEquals(5.0, defaults.ticks)
        assertNull(defaults.max)
        assertEquals(0.0, defaults.min)
        assertEquals(RadarGraticule.Circle, defaults.graticule)

        val configured = parse(
            """
            radar-beta
              ticks 12, ticks 33
              showLegend false, showLegend true
              min 1, max 10
              graticule polygon
            """.trimIndent(),
        ).getOptions()
        assertEquals(32.0, configured.ticks)
        assertEquals(true, configured.showLegend)
        assertEquals(1.0, configured.min)
        assertEquals(10.0, configured.max)
        assertEquals(RadarGraticule.Polygon, configured.graticule)
    }

    @Test
    fun returnsStructuredErrorsForInvalidGrammar() {
        val sources = listOf(
            "radar",
            "radar-beta\naxis",
            "radar-beta\naxis A B",
            "radar-beta\naxis A,\ncurve c{1}",
            "radar-beta\naxis A\ncurve",
            "radar-beta\naxis A\ncurve c{}",
            "radar-beta\naxis A, B\ncurve c{1, A:2}",
            "radar-beta\naxis A, B\ncurve c{A 1 B 2}",
            "radar-beta\naxis A\ncurve c{01}",
            "radar-beta\naxis A\ncurve c{.5}",
            "radar-beta\naxis A\ncurve c{1.}",
            "radar-beta\naxis A\ncurve c{1}\ninvalid@symbol",
        )

        sources.forEach { source ->
            val error = assertIs<GMResult.Err<MermaidError>>(parser().parse(source), source).error
            assertIs<MermaidError.Parse>(error)
        }
    }

    @Test
    fun reportsAdjustedLineAndColumnAndMissingReference() {
        val error = assertIs<GMResult.Err<MermaidError>>(
            parser(lineOffset = 7).parse(
                """
                radar-beta
                  axis A, B
                  curve c { A: 1 }
                """.trimIndent(),
            ),
        ).error
        val parse = assertIs<MermaidError.Parse>(error)

        assertEquals(10, parse.line)
        assertEquals(13, parse.column)
        assertEquals("Missing entry for axis B", parse.message)
    }

    @Test
    fun databaseReportsAxesRequirementAndClearsAllState() {
        val db = RadarDb(MermaidRadarOptions(), "Initial")
        val entry = RadarAstEntry(axisName = "A", value = 1.0, line = 2, column = 3)
        val missingAxes = db.computeCurveEntries(listOf(entry))
        assertIs<GMResult.Err<MermaidError>>(missingAxes)

        db.setAxes(listOf(RadarAstAxis("A", null, 1, 1)))
        db.setDiagramTitle("Title")
        db.setAccessibilityTitle("Accessible")
        db.setAccessibilityDescription("Description")
        db.clear()

        assertEquals(emptyList(), db.getAxes())
        assertEquals(emptyList(), db.getCurves())
        assertEquals(5.0, db.getOptions().ticks)
        assertNull(db.diagramTitle)
        assertNull(db.accessibilityTitle)
        assertNull(db.accessibilityDescription)
    }

    private fun parse(
        source: String,
        diagramTitle: String? = null,
    ): RadarDb = assertIs<GMResult.Ok<RadarDb>>(
        parser(diagramTitle = diagramTitle).parse(source),
        source,
    ).value

    private fun parser(
        diagramTitle: String? = null,
        lineOffset: Int = 0,
    ): RadarParser = RadarParser(
        config = MermaidRadarOptions(),
        diagramTitle = diagramTitle,
        lineOffset = lineOffset,
    )
}
