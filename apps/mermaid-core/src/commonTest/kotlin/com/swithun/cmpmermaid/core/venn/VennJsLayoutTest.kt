package com.swithun.cmpmermaid.core.venn

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.venn.upstream.mermaid.VennData
import com.swithun.cmpmermaid.core.venn.upstream.vennjs.VennCircle
import com.swithun.cmpmermaid.core.venn.upstream.vennjs.VennGeometry
import com.swithun.cmpmermaid.core.venn.upstream.vennjs.VennLayoutArea
import com.swithun.cmpmermaid.core.venn.upstream.vennjs.VennLayoutEngine
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VennJsLayoutTest {
    @Test
    fun matchesUpstreamCircleIntersectionPrimitives() {
        assertClose(0.0, VennGeometry.circleArea(10.0, 0.0))
        assertClose(PI * 50.0, VennGeometry.circleArea(10.0, 10.0))
        assertClose(PI * 100.0, VennGeometry.circleArea(10.0, 20.0))
        assertClose(122.83696986087568, VennGeometry.circleOverlap(10.0, 10.0, 10.0))

        val points = VennGeometry.circleCircleIntersection(
            VennCircle(0.0, 0.0, 10.0, "A"),
            VennCircle(10.0, 0.0, 10.0, "B"),
        )
        assertEquals(2, points.size)
        assertClose(5.0, points[0].x)
        assertClose(5.0, points[1].x)
        assertClose(-points[0].y, points[1].y)
    }

    @Test
    fun matchesUpstreamTwoSetLayoutCoordinates() {
        val areas = assertIs<GMResult.Ok<List<VennLayoutArea>>>(
            VennLayoutEngine.layout(
                source = listOf(
                    VennData(listOf("A"), 10.0),
                    VennData(listOf("B"), 10.0),
                    VennData(listOf("A", "B"), 2.5),
                ),
                width = 800.0,
                height = 450.0,
                padding = 15.0,
            ),
        ).value

        val first = areas[0].circles.single()
        val second = areas[1].circles.single()
        assertClose(266.712035265571, first.x, tolerance = 1e-5)
        assertClose(533.287964734429, second.x, tolerance = 1e-5)
        assertClose(225.0, first.y, tolerance = 1e-5)
        assertClose(210.0, first.radius, tolerance = 1e-5)
        assertClose(400.0, areas[2].text.x, tolerance = 1e-4)
        assertClose(225.0, areas[2].text.y, tolerance = 1e-4)
    }

    @Test
    fun synthesizesOnlyMissingPairwiseAreasForHigherArityUnion() {
        val source = listOf(
            VennData(listOf("A"), 20.0),
            VennData(listOf("B"), 12.0),
            VennData(listOf("C"), 8.0),
            VennData(listOf("A", "B"), 3.0),
            VennData(listOf("A", "B", "C"), 1.0),
        )
        val rendered = VennLayoutEngine.ensurePairwiseSubsets(source)

        assertEquals(7, rendered.size)
        assertEquals(
            listOf("A|C", "B|C"),
            rendered.drop(source.size).map { data -> data.sets.joinToString("|") },
        )
        assertEquals(listOf(2.0, 2.0), rendered.drop(source.size).map(VennData::size))
    }

    @Test
    fun preservesFiniteMultiCircleIntersectionAreas() {
        val area = VennGeometry.intersectionArea(
            listOf(
                VennCircle(0.501, 0.32, 0.629, "A"),
                VennCircle(0.945, 0.022, 1.015, "B"),
                VennCircle(0.021, 0.863, 0.261, "C"),
                VennCircle(0.528, 0.09, 0.676, "D"),
            ),
        ).area

        assertTrue(area.isFinite())
        assertTrue(kotlin.math.abs(area - 0.0008914) < 0.0001)
    }

    private fun assertClose(
        expected: Double,
        actual: Double,
        tolerance: Double = 1e-8,
    ) {
        assertTrue(
            kotlin.math.abs(expected - actual) <= tolerance,
            "Expected $expected +/- $tolerance, actual $actual",
        )
    }
}
