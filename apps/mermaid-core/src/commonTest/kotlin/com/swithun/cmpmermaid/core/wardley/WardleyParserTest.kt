package com.swithun.cmpmermaid.core.wardley

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.wardley.upstream.mermaid.WardleyDocument
import com.swithun.cmpmermaid.core.wardley.upstream.mermaid.WardleyFlow
import com.swithun.cmpmermaid.core.wardley.upstream.mermaid.WardleyParser
import com.swithun.cmpmermaid.core.wardley.upstream.mermaid.WardleySourceStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WardleyParserTest {
    @Test
    fun parsesComponentsLinksMetadataAndPercentageCoordinates() {
        val document = parse(
            """
                wardley-beta
                title Coordinate Handling
                accTitle: Accessible map
                accDescr: A strategic value chain
                component "Mobile App" [0.2, 0.4] (build) (inertia)
                component API [30.0, 50.0]
                "Mobile App" +<> API; constraint
            """.trimIndent(),
        )

        assertEquals("Coordinate Handling", document.diagramTitle)
        assertEquals("Accessible map", document.accessibilityTitle)
        assertEquals("A strategic value chain", document.accessibilityDescription)
        val mobile = document.data.nodes.first { node -> node.label == "Mobile App" }
        assertEquals(40f, mobile.x)
        assertEquals(20f, mobile.y)
        assertEquals(WardleySourceStrategy.Build, mobile.sourceStrategy)
        assertTrue(mobile.inertia)
        val link = document.data.links.single()
        assertEquals(WardleyFlow.Bidirectional, link.flow)
        assertEquals("constraint", link.label)
    }

    @Test
    fun parsesEvolutionPipelinesAndResolvesSyntheticIds() {
        val document = parse(
            """
                wardley-beta
                evolution Genesis@0.3 -> Custom / Emerging@0.6 -> Product@0.85 -> Commodity@1.0
                component Data Store [0.5, 0.5]
                pipeline Data Store {
                  component real-time queue [0.3] label [-40, 20]
                  component batch-loader [0.7]
                }
                real-time queue +'sync'> batch-loader
                evolve Data Store 0.8
            """.trimIndent(),
        )

        assertEquals(
            listOf("Genesis", "Custom / Emerging", "Product", "Commodity"),
            document.data.axes.stages,
        )
        assertEquals(listOf(0.3f, 0.6f, 0.85f, 1f), document.data.axes.stageBoundaries)
        val parent = document.data.nodes.first { node -> node.id == "Data Store" }
        assertTrue(parent.isPipelineParent)
        val realtime = document.data.nodes.first { node -> node.label == "real-time queue" }
        assertEquals("Data Store_real-time queue", realtime.id)
        assertTrue(realtime.inPipeline)
        assertEquals(30f, realtime.x ?: 0f, 0.001f)
        assertEquals(parent.y, realtime.y)
        assertEquals(-40f, realtime.labelOffsetX)
        assertEquals(20f, realtime.labelOffsetY)
        assertEquals(realtime.id, document.data.links.single().source)
        assertEquals("Data Store_batch-loader", document.data.links.single().target)
        assertEquals("sync", document.data.links.single().label)
        assertEquals(WardleyFlow.Forward, document.data.links.single().flow)
        assertEquals(80f, document.data.trends.single().targetX)
    }

    @Test
    fun parsesAnnotationsNotesForcesSizeAndHyphenatedNames() {
        val document = parse(
            """
                wardley-beta
                size [1200, 900]
                anchor on-call engineer [0.9, 0.95]
                component foo--bar [0.3, 0.4] label [10, -12] (market)
                note "Critical decision point" [0.65, 0.55]
                annotations [0.10, 0.90]
                annotation 2,[0.50, 0.40] "Performance layer"
                accelerator "Cloud Native" [0.20, 0.85]
                deaccelerator "Legacy Data" [0.40, 0.35]
            """.trimIndent(),
        )

        assertEquals(1200f, document.data.size?.width)
        assertEquals(900f, document.data.size?.height)
        assertEquals("anchor", document.data.nodes[0].className)
        assertEquals(WardleySourceStrategy.Market, document.data.nodes[1].sourceStrategy)
        assertEquals(55f, document.data.notes.single().x)
        assertEquals(65f, document.data.notes.single().y)
        assertEquals(90f, document.data.annotationsBox?.x)
        assertEquals(10f, document.data.annotationsBox?.y)
        assertEquals(40f, document.data.annotations.single().x())
        assertEquals(50f, document.data.annotations.single().y())
        assertEquals(85f, document.data.accelerators.single().x)
        assertEquals(20f, document.data.accelerators.single().y)
        assertEquals(35f, document.data.deaccelerators.single().x)
        assertEquals(40f, document.data.deaccelerators.single().y)
    }

    @Test
    fun keepsUnknownEvolutionTargetsAndLinksCompatibleWithUpstream() {
        val document = parse(
            """
                wardley-beta
                component API [0.6, 0.7]
                Missing -> API
                evolve Missing 0.8
            """.trimIndent(),
        )

        assertEquals("Missing", document.data.links.single().source)
        assertTrue(document.data.trends.isEmpty())
    }

    @Test
    fun returnsStructuredErrorsForGrammarCoordinatesAndLimits() {
        val invalidSources = listOf(
            "wardley\ncomponent A [0.2, 0.3]",
            "wardley-beta\ncomponent A [0, 1]",
            "wardley-beta\ncomponent A [0.2, 101.0]",
            "wardley-beta\npipeline Missing {\ncomponent Child [0.2]\n}",
            "wardley-beta\nnote unquoted [0.2, 0.3]",
            "wardley-beta\nannotation 1,[0.2, 0.3] unquoted",
        )
        invalidSources.forEach { source ->
            assertIs<MermaidError.Parse>(
                assertIs<GMResult.Err<MermaidError>>(parser().parse(source), source).error,
                source,
            )
        }

        val limited = parser(maxEdges = 1).parse(
            """
                wardley-beta
                component A [0.1, 0.1]
                component B [0.2, 0.2]
                A -> B
                B -> A
            """.trimIndent(),
        )
        val limit = assertIs<MermaidError.ResourceLimit>(
            assertIs<GMResult.Err<MermaidError>>(limited).error,
        )
        assertEquals("Wardley links", limit.resource)
        assertEquals(2, limit.actual)
        assertEquals(1, limit.maximum)

        val offset = parser(lineOffset = 7).parse("wardley-beta\ncomponent A [0, 1]")
        val parseError = assertIs<MermaidError.Parse>(
            assertIs<GMResult.Err<MermaidError>>(offset).error,
        )
        assertEquals(9, parseError.line)
    }

    @Test
    fun parsesEverySupportedLinkFlowForm() {
        val document = parse(
            """
                wardley-beta
                component A [0.1, 0.1]
                component B [0.2, 0.2]
                A->B
                A --> B
                A -.-> B
                A +> B
                A +< B
                A +<> B
                A +'backup'> B
            """.trimIndent(),
        )

        assertNull(document.data.links[0].flow)
        assertNull(document.data.links[1].flow)
        assertTrue(document.data.links[2].dashed)
        assertEquals(WardleyFlow.Forward, document.data.links[3].flow)
        assertEquals(WardleyFlow.Backward, document.data.links[4].flow)
        assertEquals(WardleyFlow.Bidirectional, document.data.links[5].flow)
        assertEquals(WardleyFlow.Forward, document.data.links[6].flow)
        assertEquals("backup", document.data.links[6].label)
    }

    private fun com.swithun.cmpmermaid.core.wardley.upstream.mermaid.WardleyAnnotation.x():
        Float = coordinates.single().x

    private fun com.swithun.cmpmermaid.core.wardley.upstream.mermaid.WardleyAnnotation.y():
        Float = coordinates.single().y

    private fun parse(source: String): WardleyDocument =
        assertIs<GMResult.Ok<WardleyDocument>>(parser().parse(source)).value

    private fun parser(
        maxEdges: Int = 500,
        lineOffset: Int = 0,
    ): WardleyParser = WardleyParser(
        options = MermaidRenderOptions(maxEdges = maxEdges),
        diagramTitle = null,
        lineOffset = lineOffset,
    )
}
