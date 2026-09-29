package com.swithun.cmpmermaid.core.statediagram

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StateLayoutTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = metrics(),
        theme = MermaidTheme.FlowchartDefault,
        options = MermaidRenderOptions(look = "neo"),
    )

    @Test
    fun rendersStartEndChoiceAndForkJoinShapes() {
        val scene = render(
            """
            stateDiagram-v2
                state branch <<choice>>
                state split <<fork>>
                state merge <<join>>
                [*] --> split
                split --> branch
                branch --> merge : accepted
                merge --> [*]
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)

        assertEquals(SceneShapeKind.SmallCircle, shapes.getValue("root_start").kind)
        assertEquals(SceneShapeKind.FramedCircle, shapes.getValue("root_end").kind)
        assertEquals(SceneShapeKind.Diamond, shapes.getValue("branch").kind)
        assertEquals(SceneShapeKind.ForkJoin, shapes.getValue("split").kind)
        assertEquals(SceneShapeKind.ForkJoin, shapes.getValue("merge").kind)
        assertTrue(
            scene.elements.filterIsInstance<ScenePath>()
                .filterNot { it.id.endsWith("-label-background") }
                .all { it.arrowEnd == SceneArrowHead.Triangle },
        )
    }

    @Test
    fun rendersDescriptionCompartmentsAndMarkdown() {
        val scene = render(
            """
            stateDiagram-v2
                Service : **Ready**
                Service : _Waiting_
                Service --> Done : **finish**
            """.trimIndent(),
        )
        val state = scene.elements.filterIsInstance<SceneShape>()
            .single { it.id == "Service" }
        val text = scene.elements.filterIsInstance<SceneText>()

        assertEquals(2, state.geometry?.paths?.size)
        assertTrue(text.single { it.text == "Ready" }.spans.any {
            it.weight == SceneTextWeight.Bold
        })
        assertTrue(text.single { it.text == "Waiting" }.spans.any { it.italic })
        assertTrue(text.single { it.text == "finish" }.spans.any {
            it.weight == SceneTextWeight.Bold
        })
    }

    @Test
    fun positionsCurvedEdgeLabelAtUpdatedPathHalfLength() {
        val scene = render(
            """
            stateDiagram-v2
              state risk <<choice>>
              [*] --> Created
              Created --> Validating : submit
              Validating --> risk
              risk --> Declined : blocked
              risk --> Challenging : step up required
              risk --> Authorizing : low risk
              Challenging --> Authorizing : challenge passed
              Challenging --> Declined : challenge failed
              Authorizing --> Authorized : issuer approved
              Authorizing --> Declined : issuer declined
              Authorized --> Capturing : capture requested
              Authorized --> Voided : void requested
              Capturing --> Captured : capture confirmed
              Capturing --> Authorized : retryable failure
              Captured --> PartiallyRefunded : partial refund
              PartiallyRefunded --> PartiallyRefunded : another partial refund
              PartiallyRefunded --> Refunded : full amount refunded
              Captured --> Refunded : full refund
              Captured --> Reversed : chargeback
              Declined --> [*]
              Voided --> [*]
              Refunded --> [*]
              Reversed --> [*]
            """.trimIndent(),
        )
        val label = scene.elements.filterIsInstance<SceneText>()
            .single { it.text == "full amount refunded" }
        val background = scene.elements.filterIsInstance<SceneShape>()
            .single { shape ->
                shape.id.endsWith("-label-background") &&
                    shape.bounds == label.bounds
            }
        val edge = scene.elements.filterIsInstance<ScenePath>()
            .single { it.id == background.id.removeSuffix("-label-background") }
        val expected = edge.points.halfLengthPoint()

        assertEquals(expected.x, label.bounds.center.x, 0.01f)
        assertEquals(expected.y, label.bounds.center.y, 0.01f)
    }

    @Test
    fun rendersNestedCompositeAndConcurrencyRegions() {
        val scene = render(
            """
            stateDiagram-v2
                state Active {
                    [*] --> First
                    First --> [*]
                    --
                    [*] --> Second
                    Second --> [*]
                }
            """.trimIndent(),
        )
        val groups = scene.elements.filterIsInstance<SceneShape>()
            .filter { it.id.startsWith("state-group-") }

        assertTrue(groups.any { it.id == "state-group-Active" })
        assertEquals(
            2,
            groups.count {
                it.id.startsWith("state-group-divider") &&
                    it.strokePattern == SceneStrokePattern.Dashed
            },
        )
    }

    @Test
    fun rendersNotesWithUpstreamDirectedDashedConnectors() {
        val scene = render(
            """
            stateDiagram-v2
                One : First state
                note left of One : Left note
                Two : Second state
                note right of Two : Right note
                One --> Two
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy(SceneShape::id)
        val leftNote = shapes.values.single { it.id.startsWith("One----note") }
        val rightNote = shapes.values.single { it.id.startsWith("Two----note") }
        val dashedPaths = scene.elements.filterIsInstance<ScenePath>()
            .filter { it.strokePattern == SceneStrokePattern.Dashed }

        assertTrue(listOf(leftNote, rightNote).all {
            it.kind == SceneShapeKind.Rectangle && it.geometry?.paths?.size == 1
        })
        assertEquals(context.theme.noteFill, leftNote.fill)
        assertEquals(context.theme.noteStroke, leftNote.stroke)
        assertTrue(
            scene.elements.filterIsInstance<SceneText>()
                .filter { it.text == "Left note" || it.text == "Right note" }
                .all { it.color == context.theme.noteText },
        )
        assertEquals(2, dashedPaths.size)
        assertTrue(dashedPaths.any { it.id == "${leftNote.id}-One" })
        assertTrue(dashedPaths.any { it.id == "Two-${rightNote.id}" })
    }

    @Test
    fun usesUpstreamHtmlLineHeightForStateText() {
        val stateText = renderStateWithNote().elements.filterIsInstance<SceneText>()
            .single { it.text == "Approved" }

        assertEquals(1.5f, stateText.lineHeight)
        assertEquals(21f, stateText.bounds.height, 0.01f)
    }

    @Test
    fun widensStateTextBoundsToConfiguredMinimum() {
        val stateText = renderStateWithNote().elements.filterIsInstance<SceneText>()
            .single { it.text == "Approved" }

        assertEquals(120f, stateText.bounds.width, 0.01f)
    }

    @Test
    fun sizesNoteWithConfiguredMinimumAndFlowchartPadding() {
        val scene = renderStateWithNote()
        val noteText = scene.elements.filterIsInstance<SceneText>()
            .single { it.text == "Review" }
        val noteShape = scene.elements.filterIsInstance<SceneShape>()
            .single { it.id.startsWith("Pending----note") }

        assertEquals(120f, noteText.bounds.width, 0.01f)
        assertEquals(21f, noteText.bounds.height, 0.01f)
        assertEquals(150f, noteShape.bounds.width, 0.01f)
        assertEquals(51f, noteShape.bounds.height, 0.01f)
    }

    @Test
    fun includesInvisibleNoteGroupInSceneBounds() {
        val scene = renderStateWithNote()
        val noteShape = scene.elements.filterIsInstance<SceneShape>()
            .single { it.id.startsWith("Pending----note") }

        assertTrue(noteShape.bounds.top > context.options.diagramPadding)
    }

    @Test
    fun appliesStylesMetadataAndLinks() {
        val scene = render(
            """
            ---
            title: Order lifecycle
            ---
            stateDiagram-v2
                accTitle: Order state diagram
                accDescr: Tracks an order from draft to completion
                classDef active fill:#dcfce7,stroke:#16a34a,color:#14532d
                Draft:::active --> Done
                click Draft href "https://example.com" 
            """.trimIndent(),
        )
        val draft = scene.elements.filterIsInstance<SceneShape>().single { it.id == "Draft" }

        assertEquals(0xFFDCFCE7, draft.fill.argb)
        assertEquals(0xFF16A34A, draft.stroke.argb)
        assertEquals("Order lifecycle", scene.title)
        assertEquals("Order state diagram", scene.accessibilityTitle)
        assertEquals("Tracks an order from draft to completion", scene.accessibilityDescription)
        assertEquals("https://example.com", scene.interactions.single().link)
    }

    @Test
    fun rejectsStateKatexInsteadOfRenderingLiteralMarkup() {
        val result = engine.render(
            """
            stateDiagram-v2
                Math : \(x^2\)
            """.trimIndent(),
            context,
        )

        val error = assertIs<MermaidError.UnsupportedFeature>(
            assertIs<GMResult.Err<MermaidError>>(result).error,
        )
        assertEquals("KaTeX label", error.feature)
    }

    private fun render(
        source: String,
        options: MermaidRenderOptions = context.options,
    ): MermaidScene {
        val result = engine.render(source, context.copy(options = options))
        return assertIs<GMResult.Ok<MermaidScene>>(
            result,
            "Expected State render success:\n$source\n$result",
        ).value
    }

    private fun renderStateWithNote(): MermaidScene = render(
        """
        stateDiagram-v2
            direction LR
            [*] --> Pending
            note right of Pending : Review
            Pending --> Approved
            Approved --> [*]
        """.trimIndent(),
        options = context.options.copy(
            fontSize = 14f,
            flowchartPadding = 15f,
            stateMinNodeWidth = 120f,
        ),
    )

    private fun metrics(): TextMetricProvider = TextMetricProvider { request ->
        val charactersPerLine = (request.maxWidth / 8f).toInt().coerceAtLeast(1)
        val lines = request.text
            .split('\n')
            .sumOf { line ->
                ceil(line.length.toDouble() / charactersPerLine)
                    .toInt()
                    .coerceAtLeast(1)
            }
        TextMetrics(
            width = minOf(
                request.maxWidth,
                request.text.lineSequence().maxOfOrNull(String::length).orZero() * 8f,
            ),
            height = lines * request.fontSize,
            lineCount = lines,
        )
    }

    private fun Int?.orZero(): Int = this ?: 0

    private fun List<com.swithun.cmpmermaid.core.ScenePoint>.halfLengthPoint():
        com.swithun.cmpmermaid.core.ScenePoint {
        val lengths = zipWithNext { first, second ->
            hypot(second.x - first.x, second.y - first.y)
        }
        val target = lengths.sum() / 2f
        var traversed = 0f
        lengths.forEachIndexed { index, length ->
            if (traversed + length >= target && length > 0f) {
                val ratio = (target - traversed) / length
                return com.swithun.cmpmermaid.core.ScenePoint(
                    x = this[index].x + (this[index + 1].x - this[index].x) * ratio,
                    y = this[index].y + (this[index + 1].y - this[index].y) * ratio,
                )
            }
            traversed += length
        }
        return last()
    }
}
