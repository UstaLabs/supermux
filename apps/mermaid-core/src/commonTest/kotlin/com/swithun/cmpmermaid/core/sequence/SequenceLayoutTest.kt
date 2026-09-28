package com.swithun.cmpmermaid.core.sequence

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneShapeGeometry
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SequenceLayoutTest {
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            val charactersPerLine = (request.maxWidth / 8f).toInt().coerceAtLeast(1)
            val lines = ceil(request.text.length.toDouble() / charactersPerLine)
                .toInt()
                .coerceAtLeast(1)
            TextMetrics(
                width = minOf(request.maxWidth, request.text.length * 8f),
                height = lines * request.fontSize * request.lineHeight,
            )
        },
        options = MermaidRenderOptions(look = "neo"),
    )
    private val engine = MermaidEngine()

    @Test
    fun laysOutActorsLifelinesAndMessageWithoutOverlap() {
        val scene = render(
            """
            sequenceDiagram
                participant A as Client
                participant B as Service
                A->>B:Request
                B-->>A:Response
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy { it.id }
        val paths = scene.elements.filterIsInstance<ScenePath>().associateBy { it.id }

        val first = shapes.getValue("actor-A-top")
        val second = shapes.getValue("actor-B-top")
        assertTrue(first.bounds.right < second.bounds.left)
        assertTrue(paths.getValue("lifeline-A").points.all { it.x == first.bounds.center.x })
        assertTrue(paths.getValue("lifeline-B").points.all { it.x == second.bounds.center.x })
        assertEquals(SceneStrokePattern.Solid, paths.getValue("lifeline-A").strokePattern)
        assertEquals(context.theme.colorFill(0), first.fill)
        assertEquals(context.theme.colorFill(1), second.fill)
        assertEquals(SceneArrowHead.Triangle, paths.getValue("message-0").arrowEnd)
        assertEquals(SceneStrokePattern.Dashed, paths.getValue("message-1").strokePattern)
    }

    @Test
    fun rendersSelfMessageWithCubicPath() {
        val scene = render(
            """
            sequenceDiagram
                Alice->>Alice:Think
            """.trimIndent(),
        )

        val message = scene.elements.filterIsInstance<ScenePath>()
            .single { it.id == "message-0" }
        assertTrue(message.commands.any { it is ScenePathCommand.CubicTo })
        assertTrue(message.points.maxOf { it.x } > message.points.first().x)
    }

    @Test
    fun keepsSelfMessageTextAndPathInsideControlFrame() {
        val scene = render(
            """
            sequenceDiagram
                participant API as Session gateway
                opt audit enabled
                    API->>API:Append audit event
                end
            """.trimIndent(),
        )
        val frame = scene.elements.filterIsInstance<SceneShape>()
            .single { shape ->
                shape.id.startsWith("control-") &&
                    !shape.id.startsWith("control-label-")
            }
        val path = scene.elements.filterIsInstance<ScenePath>()
            .single { element ->
                element.id.startsWith("message-") &&
                    element.commands.any { command -> command is ScenePathCommand.CubicTo }
            }
        val text = scene.elements.filterIsInstance<SceneText>()
            .single { element -> element.text == "Append audit event" }

        assertFalse(text.softWrap)
        assertTrue(text.bounds.width >= 144f)
        assertTrue(frame.bounds.left <= text.bounds.left)
        assertTrue(frame.bounds.right >= text.bounds.right)
        assertTrue(frame.bounds.left <= path.points.minOf(ScenePoint::x))
        assertTrue(frame.bounds.right >= path.points.maxOf(ScenePoint::x))
    }

    @Test
    fun wrapsControlTitlesToTheUpstreamLoopContentWidth() {
        val scene = render(
            """
            sequenceDiagram
                participant API as Session gateway
                opt audit enabled
                    API->>API:Append audit event
                end
            """.trimIndent(),
        )
        val titleLines = scene.elements.filterIsInstance<SceneText>()
            .filter { it.text == "[audit" || it.text == "enabled]" }
            .sortedBy { it.bounds.top }

        assertEquals(listOf("[audit", "enabled]"), titleLines.map(SceneText::text))
        assertTrue(titleLines[0].bounds.bottom <= titleLines[1].bounds.top)
    }

    @Test
    fun rendersNotesActivationsAndNestedControlFrames() {
        val scene = render(
            """
            sequenceDiagram
                participant A
                participant B
                A->>+B:Request
                alt Accepted
                    B-->>A:Done
                else Rejected
                    B--xA:Failed
                end
                Note over A,B:Shared state
                B-->>-A:Close
            """.trimIndent(),
        )

        val shapes = scene.elements.filterIsInstance<SceneShape>()
        val notes = shapes.filter { it.id.startsWith("note-") }
        assertTrue(shapes.any { it.id.startsWith("activation-") })
        assertTrue(shapes.any { it.id.startsWith("control-") })
        assertTrue(shapes.any { it.id.startsWith("control-label-") })
        assertTrue(notes.all { it.kind == SceneShapeKind.Rectangle })
        assertTrue(notes.all { it.fill == context.theme.noteFill })
        assertTrue(notes.all { it.stroke == context.theme.noteStroke })
        assertEquals(
            context.theme.noteText,
            scene.elements.filterIsInstance<SceneText>()
                .single { it.text == "Shared state" }
                .color,
        )
        assertTrue(scene.width > 0f)
        assertTrue(scene.height > 0f)
    }

    @Test
    fun positionsSideNotesUsingMermaid12ActorMarginFormula() {
        val scene = render(
            """
            sequenceDiagram
                participant A
                participant B
                Note left of A: Before
                Note right of B: After
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy { it.id }
        val actorA = shapes.getValue("actor-A-top")
        val actorB = shapes.getValue("actor-B-top")
        val leftNote = shapes.getValue("note-0")
        val rightNote = shapes.getValue("note-1")

        assertClose(actorA.bounds.left + 50f, leftNote.bounds.right)
        assertClose(actorB.bounds.left + 100f, rightNote.bounds.left)
    }

    @Test
    fun wrapsExplicitMessagesAndNotesWithoutExpandingActorSpacing() {
        val scene = render(
            """
            sequenceDiagram
                participant A
                participant B
                A->>B:wrap:This message requests wrapping across the available space
                Note over A,B:wrap:A shared note with enough text to wrap naturally
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy { it.id }
        val texts = scene.elements.filterIsInstance<SceneText>()
        val actorA = shapes.getValue("actor-A-top")
        val actorB = shapes.getValue("actor-B-top")
        val message = texts.single {
            it.text == "This message requests wrapping across the available space"
        }
        val note = shapes.getValue("note-1")
        val noteText = texts.single {
            it.text == "A shared note with enough text to wrap naturally"
        }

        assertClose(200f, actorB.bounds.center.x - actorA.bounds.center.x)
        assertTrue(message.bounds.height > 20f)
        assertClose(250f, note.bounds.width)
        assertClose(note.bounds.width - 20f, noteText.bounds.width)
        assertEquals(SceneColor(0xFFFFF5AD), note.fill)
        assertEquals(SceneColor(0xFFFACC15), note.stroke)
    }

    @Test
    fun rendersHtmlLineBreaksAsNativeNewlines() {
        val scene = render(
            """
            sequenceDiagram
                participant A as First<br/>Second
                A->>B:Hello<br>again
                Note over A,B:Shared<br />state
            """.trimIndent(),
        )
        val texts = scene.elements.filterIsInstance<SceneText>().map(SceneText::text)

        assertTrue("First\nSecond" in texts)
        assertTrue("Hello\nagain" in texts)
        assertTrue("Shared\nstate" in texts)
        assertTrue(texts.none { "<br" in it })
    }

    @Test
    fun insetsNestedControlFramesAndUsesOfficialNeoStyling() {
        val scene = render(
            """
            sequenceDiagram
                loop Retry
                    A->>B:Request
                    alt Success
                        B-->>A:Result
                    else Failure
                        opt Recoverable
                            A->>B:Retry
                        end
                    end
                end
            """.trimIndent(),
        )
        val frames = scene.elements.filterIsInstance<SceneShape>()
            .filter { it.id.startsWith("control-") && !it.id.startsWith("control-label-") }
            .sortedByDescending { it.bounds.width }
        val labels = scene.elements.filterIsInstance<SceneText>().map { it.text }

        assertEquals(3, frames.size)
        assertTrue(frames.all { it.strokePattern == SceneStrokePattern.Dotted })
        assertTrue(frames.all { it.dashIntervals == listOf(2f, 2f) })
        assertTrue(frames.zipWithNext().all { (outer, inner) ->
            inner.bounds.left > outer.bounds.left && inner.bounds.right < outer.bounds.right
        })
        assertTrue("[Retry]" in labels)
        assertTrue("[Success]" in labels)
        assertTrue("[Failure]" in labels)
        assertTrue("[Recoverable]" in labels)
    }

    @Test
    fun allowsControlLabelsToOverflowTheFixedMermaidLabelBox() {
        val scene = render(
            """
            sequenceDiagram
                critical Establish connection
                    A->>B:Connect
                end
            """.trimIndent(),
        )
        val labelShape = scene.elements.filterIsInstance<SceneShape>()
            .single { it.id.startsWith("control-label-") }
        val labelText = scene.elements.filterIsInstance<SceneText>()
            .single { it.text == "critical" }

        assertEquals(50f, labelShape.bounds.width)
        assertTrue(labelText.bounds.width > labelShape.bounds.width)
        assertClose(labelShape.bounds.center.x, labelText.bounds.center.x)
    }

    @Test
    fun fillsCentralConnectionCirclesWithSignalColor() {
        val scene = render(
            """
            sequenceDiagram
                A->>()B:Destination
                A()->>B:Source
                A()->>()B:Both
            """.trimIndent(),
        )
        val circles = scene.elements.filterIsInstance<SceneShape>()
            .filter { it.id.startsWith("central-") }

        assertEquals(4, circles.size)
        assertTrue(circles.all { it.fill == context.theme.nodeText })
    }

    @Test
    fun givesDecimalSequenceNumbersUnclippedTextBounds() {
        val scene = render(
            """
            sequenceDiagram
                autonumber 3 0.5
                A->>B:First
                B-->>A:Second
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>()
            .filter { it.id.startsWith("sequence-number-circle-") }
            .associateBy { it.id.substringAfter("sequence-number-circle-").substringBefore("-") }
        val labels = scene.elements.filterIsInstance<SceneText>()
            .filter { it.text == "3" || it.text == "3.5" }
            .associateBy { it.text }

        assertEquals(setOf("3", "3.5"), shapes.keys)
        assertEquals(setOf("3", "3.5"), labels.keys)
        assertTrue(shapes.values.all { it.bounds.width == 12f })
        assertTrue(shapes.values.all { it.bounds.height == 12f })
        assertTrue(labels.getValue("3.5").bounds.width > shapes.getValue("3.5").bounds.width)
        assertEquals(12f, labels.getValue("3.5").fontSize)
    }

    @Test
    fun placesSequenceNumberOutsideActiveSourceAndShortensMessageLine() {
        val scene = render(
            """
            sequenceDiagram
                autonumber
                A->>+B:Activate
                B->>C:Forward
            """.trimIndent(),
        )
        val number = scene.elements.filterIsInstance<SceneShape>()
            .single { it.id.startsWith("sequence-number-circle-2-") }
        val activation = scene.elements.filterIsInstance<SceneShape>()
            .single { it.id == "activation-open-B-0" }
        val message = scene.elements.filterIsInstance<ScenePath>()
            .filter { it.id.startsWith("message-") }
            .single { path -> abs(path.points.first().y - number.bounds.center.y) < 0.001f }

        assertTrue(number.bounds.right < message.points.first().x)
        assertTrue(scene.elements.indexOf(activation) < scene.elements.indexOf(number))
    }

    @Test
    fun mapsHalfReverseAndBidirectionalMarkers() {
        val scene = render(
            """
            sequenceDiagram
                A-|\B:Top
                A/|-B:Reverse
                A<<->>B:Both
                A-)B:Async
                A-xB:Cross
            """.trimIndent(),
        )
        val messages = scene.elements.filterIsInstance<ScenePath>()
            .filter { it.id.startsWith("message-") }
            .associateBy { it.id }

        assertEquals(SceneArrowHead.HalfTriangleTop, messages.getValue("message-0").arrowEnd)
        assertEquals(SceneArrowHead.HalfTriangleBottom, messages.getValue("message-1").arrowStart)
        assertEquals(SceneArrowHead.Triangle, messages.getValue("message-2").arrowStart)
        assertEquals(SceneArrowHead.Triangle, messages.getValue("message-2").arrowEnd)
        assertEquals(SceneArrowHead.Async, messages.getValue("message-3").arrowEnd)
        assertEquals(SceneArrowHead.SequenceCross, messages.getValue("message-4").arrowEnd)
    }

    @Test
    fun appliesMermaid12MessageEndpointOffsets() {
        val scene = render(
            """
            sequenceDiagram
                A->>B:Solid
                B-->>A:Dotted
                A->B:Open
                B-->A:Dotted open
                A<<->>B:Both
                A()->>B:Central source
                A->>+B:Activate
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy { it.id }
        val messages = scene.elements.filterIsInstance<ScenePath>()
            .filter { it.id.startsWith("message-") }
            .sortedBy { it.id.substringAfter("message-").toInt() }
        val centerA = shapes.getValue("actor-A-top").bounds.center.x
        val centerB = shapes.getValue("actor-B-top").bounds.center.x

        assertEquals(7, messages.size)
        assertClose(centerB - 7f, messages[0].points.last().x)
        assertClose(centerA + 7f, messages[1].points.last().x)
        assertClose(centerB - 1f, messages[2].points.last().x)
        assertClose(centerA + 4f, messages[3].points.last().x)
        assertClose(centerA + 7f, messages[4].points.first().x)
        assertClose(centerB - 7f, messages[4].points.last().x)
        assertClose(centerA + 5f, messages[5].points.first().x)
        assertClose(centerB - 11f, messages[6].points.last().x)
    }

    @Test
    fun targetsNarrowActorGlyphForCreateAndDestroyMessages() {
        val scene = render(
            """
            sequenceDiagram
                participant Client
                create actor Worker
                Client->>Worker:Start
                destroy Worker
                Client-xWorker:Stop
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>().associateBy { it.id }
        val messages = scene.elements.filterIsInstance<ScenePath>()
            .filter { it.id.startsWith("message-") }
            .sortedBy { it.id.substringAfter("message-").toInt() }
        val workerCenter = shapes.getValue("actor-Worker-destroyed").bounds.center.x

        assertEquals(2, messages.size)
        assertClose(workerCenter - 28f, messages[0].points.last().x)
        assertClose(workerCenter - 28f, messages[1].points.last().x)
    }

    @Test
    fun reportsCreatedActorTextAtItsMeasuredGlyphBounds() {
        val scene = render(
            """
            sequenceDiagram
                participant Client
                create participant Worker as CSV worker
                Client->>Worker:Start isolated task
                destroy Worker
                Worker-->>Client:Final result
            """.trimIndent(),
        )
        val texts = scene.elements.filterIsInstance<SceneText>()
        val createdWorker = texts
            .filter { it.text == "CSV worker" }
            .minBy { it.bounds.top }
        val startMessage = texts.single { it.text == "Start isolated task" }

        assertTrue(createdWorker.bounds.height < 20f)
        assertTrue(startMessage.bounds.bottom <= createdWorker.bounds.top)
    }

    @Test
    fun usesMermaid12NeoActorBandGeometry() {
        val scene = render(
            """
            sequenceDiagram
                actor A
                participant B@{ type: "boundary" }
                participant C@{ type: "control" }
                participant E@{ type: "entity" }
                participant D@{ type: "database" }
                participant K@{ type: "collections" }
                participant Q@{ type: "queue" }
                A->>Q:All types
            """.trimIndent(),
        )
        val shapes = scene.elements.filterIsInstance<SceneShape>()
            .filter { it.id.endsWith("-top") }
            .associateBy { it.id }

        val actor = shapes.getValue("actor-A-top")
        val boundary = shapes.getValue("actor-B-top")
        val control = shapes.getValue("actor-C-top")
        val entity = shapes.getValue("actor-E-top")
        val database = shapes.getValue("actor-D-top")
        val collections = shapes.getValue("actor-K-top")
        val queue = shapes.getValue("actor-Q-top")

        assertClose(44f, actor.geometry.getValue().pointHeight())
        assertClose(44f, boundary.geometry.getValue().paths.first().points.pointWidth())
        assertTrue(boundary.geometry.getValue().points().minOf(ScenePoint::x) <= -55f)
        assertClose(44f, control.geometry.getValue().paths.first().points.pointWidth())
        assertEquals(2, control.geometry.getValue().paths.size)
        assertClose(44f, entity.geometry.getValue().paths.first().points.pointWidth())
        assertClose(50f, database.geometry.getValue().pointWidth())
        assertClose(44f, database.geometry.getValue().pointHeight())
        assertClose(
            collections.bounds.width + 6f,
            collections.geometry.getValue().pointWidth(),
        )
        assertClose(queue.bounds.width, queue.geometry.getValue().pointWidth())
        assertClose(queue.bounds.height, queue.geometry.getValue().pointHeight())
    }

    private fun render(source: String): MermaidScene {
        val result = engine.render(source, context)
        return assertIs<GMResult.Ok<MermaidScene>>(result, result.toString()).value
    }

    private fun SceneShapeGeometry?.getValue(): SceneShapeGeometry =
        assertNotNull(this)

    private fun SceneShapeGeometry.points(): List<ScenePoint> =
        paths.flatMap { it.points }

    private fun SceneShapeGeometry.pointWidth(): Float =
        points().maxOf(ScenePoint::x) - points().minOf(ScenePoint::x)

    private fun SceneShapeGeometry.pointHeight(): Float =
        points().maxOf(ScenePoint::y) - points().minOf(ScenePoint::y)

    private fun List<ScenePoint>.pointWidth(): Float =
        maxOf(ScenePoint::x) - minOf(ScenePoint::x)

    private fun assertClose(
        expected: Float,
        actual: Float,
    ) {
        assertTrue(
            abs(expected - actual) < 0.01f,
            "Expected $expected but was $actual",
        )
    }
}
