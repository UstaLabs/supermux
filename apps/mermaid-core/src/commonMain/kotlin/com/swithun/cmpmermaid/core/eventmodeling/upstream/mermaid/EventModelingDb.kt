package com.swithun.cmpmermaid.core.eventmodeling.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidEventModelingOptions
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.TextMetricsRequest
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.FlowLabelType
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidTextPort
import kotlin.math.max
import kotlin.math.min

/**
 * Kotlin translation of Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/eventmodeling/db.ts.
 */
internal class EventModelingDb(
    val config: MermaidEventModelingOptions,
    private val ast: EventModelingAst,
    frontmatterTitle: String?,
    private val maxEdges: Int,
) {
    val diagramTitle: String? = ast.title?.takeIf(String::isNotEmpty) ?: frontmatterTitle
    val accessibilityTitle: String? = ast.accessibilityTitle?.takeIf(String::isNotEmpty)
    val accessibilityDescription: String? =
        ast.accessibilityDescription?.takeIf(String::isNotEmpty)

    fun getAst(): EventModelingAst = ast

    fun getDiagramProps(): EventModelingDiagramProps = DIAGRAM_PROPS

    fun getState(
        context: MermaidRenderContext,
    ): GMResult<EventModelingContext, MermaidError> {
        var state = EventModelingContext()
        ast.frames.forEachIndexed { index, frame ->
            val textProps = when (
                val calculated = calculateTextProps(
                    frame = frame,
                    dataEntities = ast.dataEntities,
                    context = context,
                )
            ) {
                is GMResult.Ok -> calculated.value
                is GMResult.Err -> return calculated
            }
            state = evolveFramePositioned(
                state = state,
                frame = frame,
                index = index,
                textProps = textProps,
                context = context,
            )

            val sourceFrames = if (frame.sourceFrameNames.isNotEmpty()) {
                ast.frames.filter { candidate ->
                    candidate.name in frame.sourceFrameNames
                }
            } else {
                listOf(null)
            }
            sourceFrames.forEach { sourceFrame ->
                val relation = decidePositionRelation(
                    state = state,
                    frame = frame,
                    sourceFrame = sourceFrame,
                    index = index,
                    context = context,
                )
                if (relation != null) {
                    val nextCount = state.relations.size + 1
                    if (nextCount > maxEdges) {
                        return GMResult.Err(
                            MermaidError.ResourceLimit(
                                resource = "Event Modeling relations",
                                actual = nextCount,
                                maximum = maxEdges,
                            ),
                        )
                    }
                    state = state.copy(relations = state.relations + relation)
                }
            }
        }
        return GMResult.Ok(
            state.copy(
                sortedSwimlanesArray = sortedSwimlanesArray(state.swimlanes),
            ),
        )
    }

    // Mermaid.js 12.0.0: db.ts -> calculateTextProps.
    private fun calculateTextProps(
        frame: EventModelingFrame,
        dataEntities: List<EventModelingDataEntity>,
        context: MermaidRenderContext,
    ): GMResult<EventModelingTextProps, MermaidError> {
        val name = extractName(frame.entityIdentifier).orEmpty()
        val renderedName = when (
            val rendered = MermaidTextPort.render(
                source = name,
                labelType = FlowLabelType.String,
                config = context.options,
            )
        ) {
            is GMResult.Ok -> rendered.value.text
            is GMResult.Err -> return rendered
        }
        val wrappedName = wrapLabel(renderedName, context)
        var renderedData: String? = null
        var referenceAddsBreak = false

        frame.dataInlineValue?.let { inline ->
            if (!inline.startsWith("{") || !inline.endsWith("}")) {
                return GMResult.Err(
                    MermaidError.UnsupportedFeature(
                        feature = "quoted Event Modeling inline data",
                        message = "Mermaid.js 12.0.0 accepts quoted inline data in its grammar " +
                            "but its renderer requires a brace-delimited value",
                    ),
                )
            }
            // Preserve db.ts' end-exclusive `lastIndexOf('}') - 1` behavior.
            val contentAfterOpeningBrace = inline.drop(1)
            val upstreamEndExclusive =
                (contentAfterOpeningBrace.lastIndexOf('}') - 1).coerceAtLeast(0)
            renderedData = contentAfterOpeningBrace
                .substring(0, upstreamEndExclusive)
                .trim()
        }
        frame.dataReference?.let { reference ->
            val dataEntity = dataEntities.find { entity -> entity.name == reference }
                ?: return GMResult.Err(
                    MermaidError.Layout(
                        "Event Modeling data entity '$reference' was not found",
                    ),
                )
            renderedData = dataEntity.dataBlockValue
                .let { block ->
                    // Preserve db.ts' two end-exclusive substring operations.
                    val contentAfterOpening =
                        block.substring((block.indexOf("{\n") + 2).coerceAtLeast(0))
                    val upstreamEndExclusive =
                        (contentAfterOpening.lastIndexOf('}') - 1).coerceAtLeast(0)
                    contentAfterOpening.substring(0, upstreamEndExclusive)
                }
                .trim()
            referenceAddsBreak = true
        }

        val wrappedData = renderedData?.let { value ->
            val rendered = when (
                val result = MermaidTextPort.render(
                    source = value,
                    labelType = FlowLabelType.String,
                    config = context.options,
                )
            ) {
                is GMResult.Ok -> result.value.text
                is GMResult.Err -> return result
            }
            wrapLabel(rendered, context)
        }
        val measurementHtml = buildString {
            append("<b>")
            append(wrappedName.replace("\n", "<br/>"))
            append("</b>")
            if (wrappedData != null) {
                append("<br/><br/><code style=\"text-align: left; display: block;max-width:")
                append(DIAGRAM_PROPS.textMaxWidth.toInt())
                append("px\">")
                append(wrappedData.replace(" ", "&nbsp;").replace("\n", "<br/>"))
                if (referenceAddsBreak) {
                    append("<br/>")
                }
                append("</code>")
            }
        }
        val measurementText = measurementHtml.replace(HTML_BREAK, "\n")
        val metrics = context.textMetrics.measure(
            TextMetricsRequest(
                text = measurementText,
                fontSize = BOX_FONT_SIZE,
                maxWidth = UNWRAPPED_TEXT_WIDTH,
                fontFamily = CLASSIC_FONT_FAMILY,
                weight = SceneTextWeight.Bold,
            ),
        )
        if (!metrics.width.isFinite() || !metrics.height.isFinite()) {
            return GMResult.Err(
                MermaidError.Layout(
                    "Event Modeling text metrics must be finite for frame '${frame.name}'",
                ),
            )
        }
        val displayText = buildString {
            append(wrappedName)
            if (wrappedData != null) {
                append("\n\n")
                append(wrappedData)
                if (referenceAddsBreak) {
                    append('\n')
                }
            }
        }
        return GMResult.Ok(
            EventModelingTextProps(
                displayText = displayText,
                nameLength = wrappedName.length,
                dataStart = wrappedData?.let { wrappedName.length + 2 },
                width = if (wrappedData == null) metrics.width else metrics.width / 3f,
                height = metrics.height,
            ),
        )
    }

    // Mermaid.js 12.0.0: db.ts -> wrapLabel call sites and utils.ts -> wrapLabel.
    private fun wrapLabel(
        label: String,
        context: MermaidRenderContext,
    ): String {
        if (label.isEmpty() || label.contains('\n')) {
            return label
        }
        val words = label.split(' ').filter(String::isNotEmpty)
        val completed = mutableListOf<String>()
        var nextLine = ""
        words.forEachIndexed { index, word ->
            val wordWidth = measure("$word ", context)
            val nextLineWidth = measure(nextLine, context)
            when {
                wordWidth > DIAGRAM_PROPS.textMaxWidth -> {
                    val broken = breakString(word, context)
                    if (nextLine.isNotEmpty()) {
                        completed += nextLine
                    }
                    completed += broken.first
                    nextLine = broken.second
                }
                nextLineWidth + wordWidth >= DIAGRAM_PROPS.textMaxWidth -> {
                    if (nextLine.isNotEmpty()) {
                        completed += nextLine
                    }
                    nextLine = word
                }
                else -> nextLine = listOf(nextLine, word)
                    .filter(String::isNotEmpty)
                    .joinToString(" ")
            }
            if (index == words.lastIndex && nextLine.isNotEmpty()) {
                completed += nextLine
            }
        }
        return completed.joinToString("\n")
    }

    private fun breakString(
        word: String,
        context: MermaidRenderContext,
    ): Pair<List<String>, String> {
        val lines = mutableListOf<String>()
        var current = ""
        word.forEachIndexed { index, character ->
            val next = current + character
            if (measure(next, context) >= DIAGRAM_PROPS.textMaxWidth) {
                val last = index == word.lastIndex
                lines += if (last) next else "$next-"
                current = ""
            } else {
                current = next
            }
        }
        return lines to current
    }

    private fun measure(
        text: String,
        context: MermaidRenderContext,
    ): Float = context.textMetrics.measure(
        TextMetricsRequest(
            text = text,
            fontSize = BOX_FONT_SIZE,
            maxWidth = UNWRAPPED_TEXT_WIDTH,
            fontFamily = CLASSIC_FONT_FAMILY,
            weight = SceneTextWeight.Bold,
        ),
    ).width

    // Mermaid.js 12.0.0: db.ts -> evolveFramePositioned.
    private fun evolveFramePositioned(
        state: EventModelingContext,
        frame: EventModelingFrame,
        index: Int,
        textProps: EventModelingTextProps,
        context: MermaidRenderContext,
    ): EventModelingContext {
        val swimlaneProps = calculateSwimlaneProps(frame, state.swimlanes)
        val swimlane = state.swimlanes[swimlaneProps.index] ?: EventModelingSwimlane(
            index = swimlaneProps.index,
            label = swimlaneProps.label,
            // Mermaid.js 12.0.0 does not copy SwimlaneProps.namespace here.
            namespace = null,
            r = 0f,
            y = swimlaneProps.index * DIAGRAM_PROPS.swimlaneMinHeight +
                DIAGRAM_PROPS.swimlaneGap,
            height = DIAGRAM_PROPS.swimlaneMinHeight,
            maxHeight = DIAGRAM_PROPS.swimlaneMinHeight,
        )
        val previousSwimlane = state.previousSwimlaneNumber?.let(state.swimlanes::get)
        val lastBox = state.boxes.lastOrNull()
        val measuredDimension = EventModelingDimension(
            width = textProps.width + 2f * DIAGRAM_PROPS.boxTextPadding,
            height = textProps.height + 2f * DIAGRAM_PROPS.boxTextPadding,
        )
        val dimension = EventModelingDimension(
            width = max(
                DIAGRAM_PROPS.boxMinWidth,
                min(DIAGRAM_PROPS.boxMaxWidth, measuredDimension.width),
            ) + 2f * DIAGRAM_PROPS.boxPadding,
            height = max(
                DIAGRAM_PROPS.boxMinHeight,
                min(DIAGRAM_PROPS.boxMaxHeight, measuredDimension.height),
            ) + 2f * DIAGRAM_PROPS.boxPadding,
        )
        val x = calculateX(swimlane, previousSwimlane, lastBox)
        val r = x + dimension.width + DIAGRAM_PROPS.boxPadding
        val maxR = calculateMaxRight(state.swimlanes.values.toList(), r)

        swimlane.r = x + dimension.width
        swimlane.maxHeight = max(swimlane.maxHeight, dimension.height)
        swimlane.height =
            max(DIAGRAM_PROPS.swimlaneMinHeight, swimlane.maxHeight) +
            2f * DIAGRAM_PROPS.swimlanePadding

        val box = EventModelingBox(
            x = x,
            y = DIAGRAM_PROPS.swimlanePadding + swimlane.y,
            r = r,
            dimension = dimension,
            leftSibling = false,
            swimlane = swimlane,
            visual = visualProps(frame, context),
            text = textProps,
            frame = frame,
            index = index,
        )
        val nextSwimlanes = state.swimlanes + (swimlane.index to swimlane)
        val sorted = sortedSwimlanesArray(nextSwimlanes)
        sorted.firstOrNull()?.y = 0f
        for (swimlaneIndex in 1 until sorted.size) {
            val current = sorted[swimlaneIndex]
            val previous = sorted[swimlaneIndex - 1]
            current.y = previous.y + previous.height + DIAGRAM_PROPS.swimlaneGap
        }
        return state.copy(
            boxes = state.boxes + box,
            swimlanes = nextSwimlanes,
            previousSwimlaneNumber = swimlaneProps.index,
            previousFrame = frame,
            maxR = maxR,
        )
    }

    // Mermaid.js 12.0.0: db.ts -> decidePositionRelation/evolveRelationPositioned.
    private fun decidePositionRelation(
        state: EventModelingContext,
        frame: EventModelingFrame,
        sourceFrame: EventModelingFrame?,
        index: Int,
        context: MermaidRenderContext,
    ): EventModelingRelation? {
        if (
            frame.kind == EventModelingFrameKind.ResetFrame ||
            (index == 0 && frame.sourceFrameNames.isEmpty())
        ) {
            return null
        }
        val targetBox = state.boxes.find { box -> box.frame.name == frame.name } ?: return null
        val sourceBox = if (sourceFrame != null) {
            state.boxes.find { box -> box.frame.name == sourceFrame.name }
        } else {
            findBoxByLineIndex(
                boxes = state.boxes,
                targetSwimlane = targetBox.swimlane.index,
                lineIndex = index - 1,
            )
        } ?: return null
        return EventModelingRelation(
            visual = EventModelingVisualProps(
                fill = TRANSPARENT,
                stroke = context.theme.eventModeling.relationStroke ?: context.theme.edge,
            ),
            source = ScenePoint(sourceBox.x, sourceBox.y),
            target = ScenePoint(targetBox.x, targetBox.y),
            sourceBox = sourceBox,
            targetBox = targetBox,
        )
    }

    private fun calculateSwimlaneProps(
        frame: EventModelingFrame,
        swimlanes: Map<Int, EventModelingSwimlane>,
    ): EventModelingSwimlaneProps {
        val namespace = extractNamespace(frame.entityIdentifier)
        val existing = findSwimlaneByNamespace(swimlanes, namespace)
        return when (frame.modelEntityType) {
            "ui", "pcr", "processor" -> when {
                existing != null -> EventModelingSwimlaneProps(
                    index = existing.index,
                    label = existing.namespace ?: DIAGRAM_PROPS.labelUiAutomation,
                )
                namespace != null -> EventModelingSwimlaneProps(
                    index = findNextAvailableIndex(swimlanes, 0, 100),
                    label = DIAGRAM_PROPS.labelUiAutomationPrefix + namespace,
                )
                else -> EventModelingSwimlaneProps(
                    index = 0,
                    label = DIAGRAM_PROPS.labelUiAutomation,
                )
            }
            "rmo", "readmodel", "cmd", "command" -> when {
                existing != null -> EventModelingSwimlaneProps(
                    index = existing.index,
                    label = existing.namespace ?: DIAGRAM_PROPS.labelCommandReadModel,
                )
                namespace != null -> EventModelingSwimlaneProps(
                    index = findNextAvailableIndex(swimlanes, 100, 200),
                    label = DIAGRAM_PROPS.labelCommandReadModelPrefix + namespace,
                )
                else -> EventModelingSwimlaneProps(
                    index = 100,
                    label = DIAGRAM_PROPS.labelCommandReadModel,
                )
            }
            else -> when {
                existing != null -> EventModelingSwimlaneProps(
                    index = existing.index,
                    label = existing.namespace ?: DIAGRAM_PROPS.labelEvents,
                )
                namespace != null -> EventModelingSwimlaneProps(
                    index = findNextAvailableIndex(swimlanes, 200, 300),
                    label = DIAGRAM_PROPS.labelEventsPrefix + namespace,
                )
                else -> EventModelingSwimlaneProps(
                    index = 200,
                    label = DIAGRAM_PROPS.labelEvents,
                )
            }
        }
    }

    private fun visualProps(
        frame: EventModelingFrame,
        context: MermaidRenderContext,
    ): EventModelingVisualProps {
        val theme = context.theme.eventModeling
        return when (frame.modelEntityType) {
            "ui" -> EventModelingVisualProps(theme.uiFill, theme.uiStroke)
            "pcr", "processor" ->
                EventModelingVisualProps(theme.processorFill, theme.processorStroke)
            "rmo", "readmodel" ->
                EventModelingVisualProps(theme.readModelFill, theme.readModelStroke)
            "cmd", "command" ->
                EventModelingVisualProps(theme.commandFill, theme.commandStroke)
            "evt", "event" ->
                EventModelingVisualProps(theme.eventFill, theme.eventStroke)
            else -> EventModelingVisualProps(
                fill = SceneColor(0xFFFF0000),
                stroke = SceneColor(0xFF000000),
            )
        }
    }

    private fun calculateX(
        swimlane: EventModelingSwimlane,
        previousSwimlane: EventModelingSwimlane?,
        lastBox: EventModelingBox?,
    ): Float = when {
        previousSwimlane == null -> DIAGRAM_PROPS.contentStartX
        previousSwimlane.index == swimlane.index && swimlane.r != 0f ->
            swimlane.r + DIAGRAM_PROPS.boxPadding
        lastBox == null -> DIAGRAM_PROPS.contentStartX
        else -> lastBox.r - DIAGRAM_PROPS.boxOverlap + DIAGRAM_PROPS.boxPadding
    }

    private fun calculateMaxRight(
        swimlanes: List<EventModelingSwimlane>,
        swimlaneR: Float,
    ): Float = (swimlanes.map(EventModelingSwimlane::r) + swimlaneR).maxOrNull() ?: swimlaneR

    private fun sortedSwimlanesArray(
        swimlanes: Map<Int, EventModelingSwimlane>,
    ): List<EventModelingSwimlane> = swimlanes.values.sortedBy(EventModelingSwimlane::index)

    private fun findSwimlaneByNamespace(
        swimlanes: Map<Int, EventModelingSwimlane>,
        namespace: String?,
    ): EventModelingSwimlane? {
        if (namespace.isNullOrEmpty()) {
            return null
        }
        return swimlanes.values.find { swimlane -> swimlane.namespace == namespace }
    }

    private fun findNextAvailableIndex(
        swimlanes: Map<Int, EventModelingSwimlane>,
        boundaryMin: Int,
        boundaryMax: Int,
    ): Int = (
        swimlanes.keys
            .filter { index -> index > boundaryMin && index < boundaryMax }
            .maxOrNull()
            ?.coerceAtLeast(boundaryMin)
            ?: boundaryMin
        ) + 1

    private fun findBoxByLineIndex(
        boxes: List<EventModelingBox>,
        targetSwimlane: Int,
        lineIndex: Int,
    ): EventModelingBox? {
        if (lineIndex < 0) {
            return null
        }
        for (candidateIndex in lineIndex downTo 0) {
            val box = boxes.getOrNull(candidateIndex) ?: continue
            if (box.swimlane.index != targetSwimlane) {
                return box
            }
        }
        return null
    }

    private fun extractNamespace(entityIdentifier: String): String? =
        entityIdentifier.split('.').takeIf { parts -> parts.size == 2 }?.first()

    private fun extractName(entityIdentifier: String): String? =
        entityIdentifier.split('.').let { parts ->
            if (parts.size == 2) parts[1] else entityIdentifier
        }

    private companion object {
        const val BOX_FONT_SIZE = 16f
        const val UNWRAPPED_TEXT_WIDTH = 100_000f
        const val CLASSIC_FONT_FAMILY = "\"trebuchet ms\", verdana, arial, sans-serif"
        val HTML_BREAK = Regex("""</?br\s*/?>""", RegexOption.IGNORE_CASE)
        val TRANSPARENT = SceneColor(0x00000000)
        val DIAGRAM_PROPS = EventModelingDiagramProps()
    }
}

internal data class EventModelingDiagramProps(
    val swimlaneMinHeight: Float = 70f,
    val swimlanePadding: Float = 15f,
    val swimlaneGap: Float = 10f,
    val boxPadding: Float = 10f,
    val boxOverlap: Float = 90f,
    val boxDefaultY: Float = 0f,
    val boxMinWidth: Float = 80f,
    val boxMaxWidth: Float = 450f,
    val boxMinHeight: Float = 80f,
    val boxMaxHeight: Float = 750f,
    val contentStartX: Float = 250f,
    val textMaxWidth: Float = 430f,
    val boxTextFontWeight: String = "bold",
    val boxTextPadding: Float = 10f,
    val swimlaneTextFontWeight: String = "bold",
    val labelUiAutomation: String = "UI/Automation",
    val labelUiAutomationPrefix: String = "UI/A: ",
    val labelCommandReadModel: String = "Command/Read Model",
    val labelCommandReadModelPrefix: String = "C/RM: ",
    val labelEvents: String = "Events",
    val labelEventsPrefix: String = "Stream: ",
)
