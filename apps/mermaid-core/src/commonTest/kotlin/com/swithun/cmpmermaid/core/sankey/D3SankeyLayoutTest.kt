package com.swithun.cmpmermaid.core.sankey

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidSankeyOptions
import com.swithun.cmpmermaid.core.sankey.upstream.d3.D3SankeyGraph
import com.swithun.cmpmermaid.core.sankey.upstream.d3.D3SankeyLayout
import com.swithun.cmpmermaid.core.sankey.upstream.mermaid.SankeyGraph
import com.swithun.cmpmermaid.core.sankey.upstream.mermaid.SankeyLink
import com.swithun.cmpmermaid.core.sankey.upstream.mermaid.SankeyNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class D3SankeyLayoutTest {
    @Test
    fun matchesD3SankeyZeroPointTwelvePointThreeCoordinates() {
        val a = SankeyNode("A")
        val b = SankeyNode("B")
        val c = SankeyNode("C")
        val d = SankeyNode("D")
        val source = SankeyGraph(
            nodes = listOf(a, b, c, d),
            links = listOf(
                SankeyLink(a, b, 10.0),
                SankeyLink(a, c, 5.0),
                SankeyLink(b, d, 7.0),
                SankeyLink(c, d, 5.0),
            ),
        )

        val graph = assertIs<GMResult.Ok<D3SankeyGraph>>(
            D3SankeyLayout.layout(source, MermaidSankeyOptions()),
        ).value

        val nodes = graph.nodes.associateBy { it.id }
        assertClose(8.543975101516265, nodes.getValue("A").y0)
        assertClose(381.54397510151625, nodes.getValue("A").y1)
        assertClose(295.0, nodes.getValue("B").x0)
        assertClose(248.66666666666669, nodes.getValue("B").y1)
        assertClose(275.6666666666667, nodes.getValue("C").y0)
        assertClose(42.17159366662946, nodes.getValue("D").y0)
        assertClose(340.57159366662944, nodes.getValue("D").y1)

        assertClose(248.66666666666669, graph.links[0].width)
        assertClose(132.8773084348496, graph.links[0].y0)
        assertClose(124.33333333333334, graph.links[0].y1)
        assertClose(278.4049269999628, graph.links[3].y1)
    }

    @Test
    fun rejectsCyclesAndNonFiniteValuesAsStructuredLayoutErrors() {
        val a = SankeyNode("A")
        val b = SankeyNode("B")
        val cycle = SankeyGraph(
            nodes = listOf(a, b),
            links = listOf(SankeyLink(a, b, 1.0), SankeyLink(b, a, 1.0)),
        )
        assertIs<GMResult.Err<MermaidError>>(D3SankeyLayout.layout(cycle, MermaidSankeyOptions()))

        val invalid = SankeyGraph(
            nodes = listOf(a, b),
            links = listOf(SankeyLink(a, b, Double.NaN)),
        )
        assertIs<GMResult.Err<MermaidError>>(
            D3SankeyLayout.layout(invalid, MermaidSankeyOptions()),
        )
    }

    private fun assertClose(
        expected: Double,
        actual: Double,
    ) {
        assertEquals(expected, actual, absoluteTolerance = 1e-9)
    }
}
