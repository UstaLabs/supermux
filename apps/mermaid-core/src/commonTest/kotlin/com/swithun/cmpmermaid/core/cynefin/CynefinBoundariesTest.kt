package com.swithun.cmpmermaid.core.cynefin

import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.cynefin.upstream.mermaid.CynefinBoundaries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CynefinBoundariesTest {
    @Test
    fun matchesUpstreamMulberry32Values() {
        val expected = mapOf(
            0.0 to 0.26642920868471265,
            1.0 to 0.6270739405881613,
            42.0 to 0.6011037519201636,
            100.0 to 0.2043598669115454,
            999_999.0 to 0.03664584248326719,
            -50.0 to 0.2312965493183583,
            42.5 to 0.6011037519201636,
        )

        expected.forEach { (seed, value) ->
            assertEquals(
                value,
                CynefinBoundaries.seededRandom(seed),
                absoluteTolerance = 0.000000000000001,
            )
        }
    }

    @Test
    fun matchesUpstreamUtf16StringHashing() {
        assertEquals(1_237_376_062, CynefinBoundaries.hashString("cynefin"))
        assertEquals(950_494_384, CynefinBoundaries.hashString("complex"))
        assertEquals(738_986_075, CynefinBoundaries.hashString("chaotic"))
        assertEquals(1_772_899, CynefinBoundaries.hashString("😀"))
    }

    @Test
    fun resolvesExplicitAndFallbackSeedsLikeUpstream() {
        assertEquals(42.0, CynefinBoundaries.resolveSeed(42f, "mermaid-1"))
        assertEquals(-3.0, CynefinBoundaries.resolveSeed(-3f, "mermaid-2"))
        assertEquals(
            CynefinBoundaries.hashString("mermaid-1").toDouble(),
            CynefinBoundaries.resolveSeed(0f, "mermaid-1"),
        )
        assertNotEquals(
            CynefinBoundaries.resolveSeed(0f, "mermaid-1"),
            CynefinBoundaries.resolveSeed(0f, "mermaid-2"),
        )
    }

    @Test
    fun generatesSevenCubicSegmentsForBothWavyBoundaries() {
        val vertical = CynefinBoundaries.generateFoldPath(800f, 600f, 42.0)
        val horizontal = CynefinBoundaries.generateHorizontalBoundary(800f, 600f, 42.0)

        assertEquals(8, vertical.points.size)
        assertEquals(8, horizontal.points.size)
        assertEquals(1, vertical.commands.count { it is ScenePathCommand.MoveTo })
        assertEquals(7, vertical.commands.count { it is ScenePathCommand.CubicTo })
        assertEquals(1, horizontal.commands.count { it is ScenePathCommand.MoveTo })
        assertEquals(7, horizontal.commands.count { it is ScenePathCommand.CubicTo })
        assertEquals(0f, vertical.points.first().y)
        assertEquals(600f, vertical.points.last().y)
        assertEquals(0f, horizontal.points.first().x)
        assertEquals(800f, horizontal.points.last().x)
    }

    @Test
    fun zeroAmplitudeProducesStraightCenterBoundaries() {
        val vertical = CynefinBoundaries.generateFoldPath(
            width = 800f,
            height = 600f,
            seed = 42.0,
            amplitudeOverride = 0f,
        )
        val horizontal = CynefinBoundaries.generateHorizontalBoundary(
            width = 800f,
            height = 600f,
            seed = 42.0,
            amplitudeOverride = 0f,
        )

        assertTrue(vertical.points.all { point -> point.x == 400f })
        assertTrue(horizontal.points.all { point -> point.y == 300f })
        assertTrue(vertical.commands.filterIsInstance<ScenePathCommand.CubicTo>().all { command ->
            command.control1.x == 400f && command.control2.x == 400f
        })
        assertTrue(
            horizontal.commands.filterIsInstance<ScenePathCommand.CubicTo>().all { command ->
                command.control1.y == 300f && command.control2.y == 300f
            },
        )
    }

    @Test
    fun generatesUpstreamCliffAndConfusionGeometry() {
        val cliff = CynefinBoundaries.generateCliffPath(800f, 600f)
        val confusion = CynefinBoundaries.generateConfusionPath(
            centerX = 400f,
            centerY = 300f,
            radiusX = 120f,
            radiusY = 90f,
        )

        assertEquals(1, cliff.commands.count { it is ScenePathCommand.MoveTo })
        assertEquals(2, cliff.commands.count { it is ScenePathCommand.CubicTo })
        assertEquals(400f, cliff.points.first().x)
        assertEquals(300f, cliff.points.first().y)
        assertEquals(400f, cliff.points.last().x)
        assertEquals(600f, cliff.points.last().y)
        assertEquals(400f, confusion.center.x)
        assertEquals(300f, confusion.center.y)
        assertEquals(120f, confusion.radiusX)
        assertEquals(90f, confusion.radiusY)
    }
}
