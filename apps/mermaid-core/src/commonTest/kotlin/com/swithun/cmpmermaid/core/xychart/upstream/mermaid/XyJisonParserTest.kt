package com.swithun.cmpmermaid.core.xychart.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.SceneColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class XyJisonParserTest {
    @Test
    fun parsesAxesNamedPlotsAndPointLabels() {
        val data = parse(
            """
            xychart
                title "Quarterly performance"
                x-axis "Quarter" [Q1, Q2, Q3, Q4]
                y-axis "Revenue" 0 --> 100
                bar "Actual" [25, 45, 72, 90]
                line "Target" [20 "Start", 40, 60, 80 "Goal"]
            """.trimIndent(),
        )

        assertEquals("Quarterly performance", data.title)
        assertEquals(
            listOf("Q1", "Q2", "Q3", "Q4"),
            assertIs<XyBandAxisData>(data.xAxis).categories,
        )
        assertEquals(0.0, data.yAxis.min)
        assertEquals(100.0, data.yAxis.max)
        assertEquals(2, data.plots.size)
        assertEquals("Actual", assertIs<XyBarPlotData>(data.plots[0]).title)
        assertEquals(
            listOf("Start", "", "", "Goal"),
            assertIs<XyLinePlotData>(data.plots[1]).pointLabels,
        )
    }

    @Test
    fun derivesNumericAxesFromVisibleData() {
        val data = parse(
            """
            xychart-beta
                line [-2, 4, 9]
                bar [3, 7, 5, 100]
            """.trimIndent(),
        )

        val xAxis = assertIs<XyLinearAxisData>(data.xAxis)
        assertEquals(1.0, xAxis.min)
        assertEquals(3.0, xAxis.max)
        assertEquals(-2.0, data.yAxis.min)
        assertEquals(100.0, data.yAxis.max)
        assertEquals(listOf("1", "2", "3"), data.plots[0].data.map(XyDataPoint::x))
        assertEquals(listOf("1", "1.6666666666666665", "2.333333333333333", "3"), data.plots[1].data.map(XyDataPoint::x))
    }

    @Test
    fun truncatesDataToExplicitBandDomainAndParsesMetadata() {
        val data = parse(
            """
            xychart horizontal
                accTitle: Accessible chart
                accDescr {
                  A compact comparison
                }
                x-axis [one, two]
                bar [3, 5, 999]
            """.trimIndent(),
        )

        assertEquals(XyChartOrientation.Horizontal, data.orientation)
        assertEquals("Accessible chart", data.accessibilityTitle)
        assertEquals("A compact comparison", data.accessibilityDescription)
        assertEquals(listOf(3.0, 5.0), data.plots.single().data.map(XyDataPoint::y))
        assertEquals(3.0, data.yAxis.min)
        assertEquals(5.0, data.yAxis.max)
    }

    @Test
    fun omitsMissingValuesFromAnExplicitBandDomain() {
        val data = parse(
            """
            xychart
                x-axis [one, two, three, four]
                line [3, 5]
                bar [2, 4, 6]
            """.trimIndent(),
        )

        assertEquals(
            listOf("one", "two"),
            data.plots[0].data.map(XyDataPoint::x),
        )
        assertEquals(
            listOf("one", "two", "three"),
            data.plots[1].data.map(XyDataPoint::x),
        )
        assertTrue(data.plots.flatMap(XyPlotData::data).all { it.y.isFinite() })
    }

    @Test
    fun reportsEmptyChartAsStructuredLayoutError() {
        val parsed = XyJisonParser(plotColorPalette = palette).parse("xychart")
        val db = assertIs<GMResult.Ok<XyDb>>(parsed).value
        assertTrue(db.compile() is GMResult.Err)
    }

    private fun parse(source: String): XyChartData {
        val parsed = XyJisonParser(plotColorPalette = palette).parse("$source\n")
        val db = assertIs<GMResult.Ok<XyDb>>(parsed, parsed.toString()).value
        return assertIs<GMResult.Ok<XyChartData>>(db.compile()).value
    }

    private companion object {
        val palette = listOf(
            SceneColor(0xFF112233),
            SceneColor(0xFF445566),
        )
    }
}
