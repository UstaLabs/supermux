package com.swithun.cmpmermaid.core.eventmodeling

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEventModelingOptions
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import com.swithun.cmpmermaid.core.eventmodeling.upstream.mermaid.EventModelingDb
import com.swithun.cmpmermaid.core.eventmodeling.upstream.mermaid.EventModelingContext
import com.swithun.cmpmermaid.core.eventmodeling.upstream.mermaid.EventModelingParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EventModelingLayoutTest {
    @Test
    fun translatesUpstreamLaneBoxRelationAndPaintOrder() {
        val source = """
            eventmodeling
            tf 01 ui UI
            tf 02 cmd AddItem
            tf 03 evt ItemAdded
        """.trimIndent()
        val options = MermaidRenderOptions()
        val db = parse(source, options)
        val state = assertIs<GMResult.Ok<EventModelingContext>>(
            db.getState(context(options)),
        ).value
        val scene = render(db, options)

        assertEquals(listOf(0, 100, 200), state.sortedSwimlanesArray.map { lane -> lane.index })
        assertEquals(listOf(0f, 140f, 280f), state.sortedSwimlanesArray.map { lane -> lane.y })
        assertEquals(250f, state.boxes[0].x)
        assertTrue(state.boxes[1].x < state.boxes[0].r)
        assertEquals(2, state.relations.size)
        assertEquals(30f, scene.elements.filterIsInstance<SceneShape>().first().bounds.left)
        assertEquals(MermaidSceneViewportSizing.ResponsiveMaxWidth, scene.viewportSizing)

        val lanes = scene.elements.filterIsInstance<SceneShape>()
            .filter { shape -> shape.id.startsWith("eventmodeling-swimlane-") }
        val boxes = scene.elements.filterIsInstance<SceneShape>()
            .filter { shape -> shape.id.startsWith("eventmodeling-box-") }
        val relations = scene.elements.filterIsInstance<ScenePath>()
        assertEquals(3, lanes.size)
        assertEquals(3, boxes.size)
        assertEquals(2, relations.size)
        assertTrue(lanes.maxOf(SceneShape::zIndex) < boxes.minOf(SceneShape::zIndex))
        assertTrue(boxes.maxOf(SceneShape::zIndex) < relations.minOf(ScenePath::zIndex))
        assertTrue(relations.all { relation -> relation.arrowEnd == SceneArrowHead.Triangle })
        assertTrue(scene.elements.filterIsInstance<SceneText>().any { text ->
            text.text == "Command/Read Model"
        })
    }

    @Test
    fun preservesUpstreamNamespaceLaneAllocationAndResetBehavior() {
        val options = MermaidRenderOptions()
        val db = parse(
            """
            eventmodeling
            tf 01 ui Cart.UI
            tf 02 cmd Cart.Add
            tf 03 evt Cart.Added
            rf 04 evt Cart.Reset
            tf 05 rmo Cart.View
            tf 06 ui Cart.UI
            """.trimIndent(),
            options,
        )
        val state = assertIs<GMResult.Ok<EventModelingContext>>(
            db.getState(context(options)),
        ).value

        assertEquals(listOf(1, 2, 101, 102, 201, 202), state.swimlanes.keys.sorted())
        assertEquals(
            listOf(
                "UI/A: Cart",
                "UI/A: Cart",
                "C/RM: Cart",
                "C/RM: Cart",
                "Stream: Cart",
                "Stream: Cart",
            ),
            state.sortedSwimlanesArray.map { lane -> lane.label },
        )
        assertEquals(4, state.relations.size)
        assertTrue(state.relations.none { relation ->
            relation.targetBox.frame.name == "04"
        })
    }

    @Test
    fun rendersInlineAndReferencedDataWithUpstreamThemeColors() {
        val options = MermaidRenderOptions(
            eventModeling = MermaidEventModelingOptions(
                padding = 12f,
                rowHeight = 48f,
                useMaxWidth = false,
            ),
        )
        val spacedBlockOpening = "data Payload {  "
        val scene = render(
            parse(
                """
                eventmodeling
                tf 01 cmd AddItem { productId: 7 }
                tf 02 evt ItemAdded [[Payload]]
                $spacedBlockOpening
                  productId: 7
                }
                """.trimIndent(),
                options,
            ),
            options,
        )

        assertEquals(MermaidSceneViewportSizing.Intrinsic, scene.viewportSizing)
        assertEquals(12f, scene.elements.filterIsInstance<SceneShape>().first().bounds.left)
        val boxes = scene.elements.filterIsInstance<SceneShape>()
            .filter { shape -> shape.id.startsWith("eventmodeling-box-") }
        assertEquals(SceneColor(0xFFBCD6FE), boxes[0].fill)
        assertEquals(SceneColor(0xFFFFB778), boxes[1].fill)
        val texts = scene.elements.filterIsInstance<SceneText>()
        assertTrue(texts.any { text -> text.text == "productId: 7" })
        assertTrue(texts.any { text -> text.text == "productId: 7\n" })
        assertTrue(texts.none { text -> text.text.startsWith("{") })
    }

    @Test
    fun preservesUpstreamInlinePayloadEndExclusiveTrimming() {
        val options = MermaidRenderOptions()
        val scene = render(
            parse(
                """
                eventmodeling
                rf 01 cmd Tight {a:1}
                rf 02 cmd Spaced { a:1 }
                """.trimIndent(),
                options,
            ),
            options,
        )

        val payloads = scene.elements.filterIsInstance<SceneText>()
            .filter { text -> text.spans.isNotEmpty() }
            .map(SceneText::text)
        assertEquals(listOf("a:", "a:1"), payloads)
    }

    @Test
    fun enforcesRelationLimitAndConfigurationBounds() {
        val limitedOptions = MermaidRenderOptions(maxEdges = 1)
        val limited = parse(
            """
            eventmodeling
            tf 01 ui UI
            tf 02 cmd Command
            tf 03 evt Event
            """.trimIndent(),
            limitedOptions,
        )
        assertIs<GMResult.Err<*>>(
            limited.getState(context(limitedOptions)),
        )

        val invalidOptions = MermaidRenderOptions(
            eventModeling = MermaidEventModelingOptions(rowHeight = 0f),
        )
        val invalid = EventModelingLayout().layout(
            db = parse("eventmodeling", invalidOptions),
            context = context(invalidOptions),
        )
        assertIs<GMResult.Err<*>>(invalid)
    }

    private fun render(
        db: EventModelingDb,
        options: MermaidRenderOptions,
    ): MermaidScene = assertIs<GMResult.Ok<MermaidScene>>(
        EventModelingLayout().layout(db, context(options)),
    ).value

    private fun parse(
        source: String,
        options: MermaidRenderOptions,
    ): EventModelingDb = assertIs<GMResult.Ok<EventModelingDb>>(
        EventModelingParser(
            options = options,
            diagramTitle = null,
            lineOffset = 0,
        ).parse(source),
    ).value

    private fun context(options: MermaidRenderOptions): MermaidRenderContext =
        MermaidRenderContext(
            textMetrics = TextMetricProvider { request ->
                val lines = request.text.split('\n')
                TextMetrics(
                    width = lines.maxOfOrNull { line ->
                        line.length * request.fontSize * 0.5f
                    } ?: 0f,
                    height = lines.size * request.fontSize * request.lineHeight,
                    lineCount = lines.size,
                )
            },
            theme = MermaidTheme.MermaidDefault,
            options = options,
        )
}
