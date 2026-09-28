package com.swithun.cmpmermaid.core.timeline

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.MermaidTimelineOptions
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.SceneAsset
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.SceneElement
import com.swithun.cmpmermaid.core.SceneLinearGradient
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.SceneShadow
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneShapeGeometry
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneShapePath
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.SceneTextAlignment
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.TextMetricsRequest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Native translation of Mermaid 12.0.0 timelineRenderer.ts,
 * timelineRendererVertical.ts, svgDraw.js, and styles.js.
 */
internal class TimelineLayout {
    fun layout(
        document: TimelineDocument,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        when (val validation = validate(context.options.timeline, context)) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return validation
        }
        val itemCount = document.tasks.size + document.tasks.sumOf { task -> task.events.size }
        if (itemCount > context.options.maxEdges) {
            return GMResult.Err(
                MermaidError.ResourceLimit(
                    resource = "Timeline items",
                    actual = itemCount,
                    maximum = context.options.maxEdges,
                ),
            )
        }
        val factory = TimelineNodeFactory(context)
        return when (document.direction) {
            TimelineDirection.LR -> layoutLeftToRight(document, context, factory)
            TimelineDirection.TD -> layoutTopDown(document, context, factory)
        }
    }

    private fun layoutLeftToRight(
        document: TimelineDocument,
        context: MermaidRenderContext,
        factory: TimelineNodeFactory,
    ): GMResult<MermaidScene, MermaidError> {
        val options = context.options.timeline
        var maxSectionHeight = 0f
        document.sections.forEach { section ->
            val measured = when (val result = factory.measure(section, LR_NODE_WIDTH, LR_PADDING)) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            maxSectionHeight = max(maxSectionHeight, measured.naturalHeight + LR_EXTRA_HEIGHT)
        }

        // Mermaid 12.0.0 timelineRenderer.ts passes the whole task object to
        // getVirtualNodeHeight. D3 coerces it to this one-line placeholder.
        var maxTaskHeight = if (document.tasks.isEmpty()) {
            0f
        } else {
            when (
                val result = factory.measure(
                    UPSTREAM_TASK_OBJECT_TEXT,
                    LR_NODE_WIDTH,
                    LR_PADDING,
                )
            ) {
                is GMResult.Ok -> result.value.naturalHeight + LR_EXTRA_HEIGHT
                is GMResult.Err -> return result
            }
        }
        var maxEventLineLength = 0f
        document.tasks.forEach { task ->
            var eventLineLength = 0f
            task.events.forEach { event ->
                val measured = when (
                    val result = factory.measure(event, LR_NODE_WIDTH, LR_PADDING)
                ) {
                    is GMResult.Ok -> result.value
                    is GMResult.Err -> return result
                }
                eventLineLength += measured.naturalHeight
            }
            if (task.events.isNotEmpty()) {
                eventLineLength += (task.events.size - 1) * EVENT_SPACING
            }
            maxEventLineLength = max(maxEventLineLength, eventLineLength)
        }

        val elements = mutableListOf<SceneElement>()
        var masterX = LR_MASTER_X + options.leftMargin
        val sectionBeginY = LR_MASTER_Y
        var sectionNumber = 0
        val hasSections = document.sections.isNotEmpty()
        if (hasSections) {
            document.sections.forEach { section ->
                val tasks = document.tasks.filter { task -> task.section == section }
                val sectionWidth = LR_TASK_STEP * max(tasks.size, 1) - LR_SECTION_WIDTH_ADJUST
                when (
                    val result = factory.addNode(
                        elements = elements,
                        text = section,
                        x = masterX,
                        y = sectionBeginY,
                        width = sectionWidth,
                        padding = LR_PADDING,
                        maxHeight = maxSectionHeight,
                        sectionColor = sectionNumber,
                        event = false,
                    )
                ) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return result
                }
                val taskY = LR_MASTER_Y + maxSectionHeight + LR_SECTION_TASK_GAP
                when (
                    val result = addLeftToRightTasks(
                        tasks = tasks,
                        sectionColor = sectionNumber,
                        startX = masterX,
                        taskY = taskY,
                        maxTaskHeight = maxTaskHeight,
                        maxEventLineLength = maxEventLineLength,
                        withoutSections = false,
                        context = context,
                        factory = factory,
                        elements = elements,
                    )
                ) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return result
                }
                masterX += LR_TASK_STEP * max(tasks.size, 1)
                sectionNumber++
            }
        } else {
            when (
                val result = addLeftToRightTasks(
                    tasks = document.tasks,
                    sectionColor = sectionNumber,
                    startX = masterX,
                    taskY = LR_MASTER_Y,
                    maxTaskHeight = maxTaskHeight,
                    maxEventLineLength = maxEventLineLength,
                    withoutSections = true,
                    context = context,
                    factory = factory,
                    elements = elements,
                )
            ) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return result
            }
        }

        val contentBounds = elements.boundsOrDefault()
        addTitle(
            title = document.title,
            x = if (context.options.look == NEO_LOOK) {
                contentBounds.left * 2f + options.leftMargin
            } else {
                contentBounds.width / 2f - options.leftMargin
            },
            context = context,
            elements = elements,
        )?.let { error -> return GMResult.Err(error) }

        val axisY = if (hasSections) {
            maxSectionHeight + maxTaskHeight + LR_AXIS_WITH_SECTION
        } else {
            maxTaskHeight + LR_AXIS_WITHOUT_SECTION
        }
        elements += line(
            id = "timeline-axis",
            start = ScenePoint(options.leftMargin, axisY),
            end = ScenePoint(
                contentBounds.width + LR_AXIS_RIGHT_MARGIN_FACTOR * options.leftMargin,
                axisY,
            ),
            color = factory.lineColor,
            strokeWidth = LR_AXIS_STROKE,
            arrow = true,
            dashed = false,
            context = context,
            zIndex = 8,
        )
        return GMResult.Ok(normalize(document, context, elements))
    }

    private fun addLeftToRightTasks(
        tasks: List<TimelineTask>,
        sectionColor: Int,
        startX: Float,
        taskY: Float,
        maxTaskHeight: Float,
        maxEventLineLength: Float,
        withoutSections: Boolean,
        context: MermaidRenderContext,
        factory: TimelineNodeFactory,
        elements: MutableList<SceneElement>,
    ): GMResult<Unit, MermaidError> {
        var x = startX
        var color = sectionColor
        var currentMaxTaskHeight = maxTaskHeight
        tasks.forEach { task ->
            val taskNode = when (
                val result = factory.addNode(
                    elements = elements,
                    text = task.task,
                    x = x,
                    y = taskY,
                    width = LR_NODE_WIDTH,
                    padding = LR_PADDING,
                    maxHeight = maxTaskHeight,
                    sectionColor = color,
                    event = false,
                )
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            currentMaxTaskHeight = max(currentMaxTaskHeight, taskNode.height)

            var eventY = taskY + LR_EVENT_START_OFFSET
            task.events.forEach { event ->
                val visual = when (
                    val result = factory.addNode(
                        elements = elements,
                        text = event,
                        x = x,
                        y = eventY,
                        width = LR_NODE_WIDTH,
                        padding = LR_PADDING,
                        maxHeight = LR_EVENT_MIN_HEIGHT,
                        sectionColor = color,
                        event = true,
                    )
                ) {
                    is GMResult.Ok -> result.value
                    is GMResult.Err -> return result
                }
                eventY += visual.height + EVENT_SPACING
            }
            elements += line(
                id = "timeline-task-${task.id}-connector",
                start = ScenePoint(x + LR_CONNECTOR_X_OFFSET, taskY + currentMaxTaskHeight),
                end = ScenePoint(
                    x + LR_CONNECTOR_X_OFFSET,
                    taskY + currentMaxTaskHeight + LR_CONNECTOR_TOP_GAP +
                        maxEventLineLength + LR_CONNECTOR_BOTTOM_GAP,
                ),
                color = factory.lineColor,
                strokeWidth = CONNECTOR_STROKE,
                arrow = true,
                dashed = true,
                context = context,
            )
            x += LR_TASK_STEP
            if (withoutSections && !context.options.timeline.disableMulticolor) {
                color++
            }
        }
        return GMResult.Ok(Unit)
    }

    private fun layoutTopDown(
        document: TimelineDocument,
        context: MermaidRenderContext,
        factory: TimelineNodeFactory,
    ): GMResult<MermaidScene, MermaidError> {
        val options = context.options.timeline
        val masterX = TD_MASTER_X + options.leftMargin
        var masterY = TD_MASTER_Y
        val contentTopY = masterY
        val leftWidth = TD_NODE_TOTAL_WIDTH + TD_TASK_AXIS_GAP
        val rightWidth = TD_EVENT_TOTAL_WIDTH + TD_EVENT_AXIS_GAP
        val timelineX = masterX + leftWidth
        val sectionWidth = max(
            TD_MIN_SECTION_WIDTH,
            leftWidth + rightWidth - TD_NODE_PADDING * 2f,
        )

        var maxSectionHeight = 0f
        document.sections.forEach { section ->
            val measured = when (
                val result = factory.measure(section, sectionWidth, TD_NODE_PADDING)
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            maxSectionHeight = max(maxSectionHeight, measured.naturalHeight)
        }

        // Mermaid 12.0.0 timelineRendererVertical.ts repeats the same object
        // coercion as the horizontal renderer during its virtual pre-pass.
        var maxTaskHeight = if (document.tasks.isEmpty()) {
            0f
        } else {
            when (
                val result = factory.measure(
                    UPSTREAM_TASK_OBJECT_TEXT,
                    TD_NODE_WIDTH,
                    TD_NODE_PADDING,
                )
            ) {
                is GMResult.Ok -> result.value.naturalHeight
                is GMResult.Err -> return result
            }
        }
        var maxEventStackHeight = 0f
        document.tasks.forEach { task ->
            var eventStackHeight = 0f
            task.events.forEach { event ->
                val measured = when (
                    val result = factory.measure(event, TD_EVENT_WIDTH, TD_NODE_PADDING)
                ) {
                    is GMResult.Ok -> result.value
                    is GMResult.Err -> return result
                }
                eventStackHeight += measured.naturalHeight
            }
            if (task.events.isNotEmpty()) {
                eventStackHeight += (task.events.size - 1) * EVENT_SPACING
            }
            maxEventStackHeight = max(maxEventStackHeight, eventStackHeight)
        }
        val taskSpacing = max(maxTaskHeight, maxEventStackHeight) + TD_TASK_VERTICAL_GAP
        val elements = mutableListOf<SceneElement>()
        var sectionNumber = 0
        val hasSections = document.sections.isNotEmpty()
        if (hasSections) {
            document.sections.forEach { section ->
                val tasks = document.tasks.filter { task -> task.section == section }
                val sectionNode = when (
                    val result = factory.addNode(
                        elements = elements,
                        text = section,
                        x = timelineX - leftWidth,
                        y = masterY,
                        width = sectionWidth,
                        padding = TD_NODE_PADDING,
                        maxHeight = maxSectionHeight,
                        sectionColor = sectionNumber,
                        event = false,
                    )
                ) {
                    is GMResult.Ok -> result.value
                    is GMResult.Err -> return result
                }
                when (
                    val result = addTopDownTasks(
                        tasks = tasks,
                        sectionColor = sectionNumber,
                        timelineX = timelineX,
                        startY = masterY + sectionNode.height + TD_SECTION_TASK_GAP,
                        maxTaskHeight = maxTaskHeight,
                        taskSpacing = taskSpacing,
                        withoutSections = false,
                        context = context,
                        factory = factory,
                        elements = elements,
                    )
                ) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return result
                }
                val taskCount = tasks.size
                val sectionHeight = sectionNode.height +
                    TD_SECTION_TASK_GAP +
                    taskSpacing * max(taskCount, 1) -
                    if (taskCount > 0) TD_TASK_VERTICAL_GAP * 2f else 0f
                masterY += sectionHeight
                sectionNumber++
            }
        } else {
            when (
                val result = addTopDownTasks(
                    tasks = document.tasks,
                    sectionColor = sectionNumber,
                    timelineX = timelineX,
                    startY = masterY,
                    maxTaskHeight = maxTaskHeight,
                    taskSpacing = taskSpacing,
                    withoutSections = true,
                    context = context,
                    factory = factory,
                    elements = elements,
                )
            ) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return result
            }
        }

        val contentBounds = elements.boundsOrDefault()
        addTitle(
            title = document.title,
            x = contentBounds.width / 2f - options.leftMargin,
            context = context,
            elements = elements,
        )?.let { error -> return GMResult.Err(error) }
        val boundsWithTitle = elements.boundsOrDefault()
        val fontSize = context.options.fontSize ?: context.theme.fontSize
        elements += line(
            id = "timeline-axis",
            start = ScenePoint(timelineX, contentTopY - fontSize * 2f),
            end = ScenePoint(
                timelineX,
                boundsWithTitle.top + boundsWithTitle.height + fontSize * 0.5f +
                    TD_AXIS_BOTTOM_PADDING,
            ),
            color = factory.lineColor,
            strokeWidth = TD_AXIS_STROKE,
            arrow = true,
            dashed = false,
            context = context,
            zIndex = 0,
        )
        return GMResult.Ok(normalize(document, context, elements))
    }

    private fun addTopDownTasks(
        tasks: List<TimelineTask>,
        sectionColor: Int,
        timelineX: Float,
        startY: Float,
        maxTaskHeight: Float,
        taskSpacing: Float,
        withoutSections: Boolean,
        context: MermaidRenderContext,
        factory: TimelineNodeFactory,
        elements: MutableList<SceneElement>,
    ): GMResult<Unit, MermaidError> {
        var y = startY
        var color = sectionColor
        tasks.forEach { task ->
            val taskNode = when (
                val result = factory.measure(
                    task.task,
                    TD_NODE_WIDTH,
                    TD_NODE_PADDING,
                    maxTaskHeight,
                )
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            val taskX = timelineX - TD_TASK_AXIS_GAP - taskNode.width
            when (
                val result = factory.addMeasuredNode(
                    elements = elements,
                    measured = taskNode,
                    x = taskX,
                    y = y,
                    sectionColor = color,
                    event = false,
                )
            ) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return result
            }
            var eventY = y
            task.events.forEachIndexed { eventIndex, event ->
                val eventNode = when (
                    val result = factory.addNode(
                        elements = elements,
                        text = event,
                        x = timelineX + TD_EVENT_AXIS_GAP,
                        y = eventY,
                        width = TD_EVENT_WIDTH,
                        padding = TD_NODE_PADDING,
                        maxHeight = 0f,
                        sectionColor = color,
                        event = false,
                    )
                ) {
                    is GMResult.Ok -> result.value
                    is GMResult.Err -> return result
                }
                val lineY = eventY + eventNode.height / 2f
                elements += line(
                    id = "timeline-task-${task.id}-event-$eventIndex-connector",
                    start = ScenePoint(timelineX, lineY),
                    end = ScenePoint(timelineX + TD_EVENT_AXIS_GAP, lineY),
                    color = factory.lineColor,
                    strokeWidth = CONNECTOR_STROKE,
                    arrow = true,
                    dashed = true,
                    context = context,
                )
                eventY += eventNode.height + EVENT_SPACING
            }
            y += taskSpacing
            if (withoutSections && !context.options.timeline.disableMulticolor) {
                color++
            }
        }
        return GMResult.Ok(Unit)
    }

    private fun addTitle(
        title: String?,
        x: Float,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ): MermaidError? {
        val text = title?.takeIf(String::isNotBlank) ?: return null
        val fontSize = (context.options.fontSize ?: context.theme.fontSize) *
            TITLE_EX_MULTIPLIER * CSS_EX_RATIO
        val metrics = try {
            context.textMetrics.measure(
                TextMetricsRequest(
                    text = text,
                    fontSize = fontSize,
                    maxWidth = MAX_TEXT_WIDTH,
                    lineHeight = 1f,
                    fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
                    weight = SceneTextWeight.Bold,
                ),
            )
        } catch (failure: Exception) {
            return MermaidError.Unexpected.from(failure, "Timeline title measurement failed")
        }
        elements += SceneText(
            text = text,
            bounds = SceneRect(
                left = x,
                top = TITLE_BASELINE - metrics.height,
                right = x + metrics.width,
                bottom = TITLE_BASELINE,
            ),
            color = context.theme.nodeText,
            fontSize = fontSize,
            lineHeight = 1f,
            fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
            weight = SceneTextWeight.Bold,
            horizontalAlignment = SceneTextAlignment.Start,
            softWrap = false,
            zIndex = TITLE_Z_INDEX,
        )
        return null
    }

    private fun normalize(
        document: TimelineDocument,
        context: MermaidRenderContext,
        elements: List<SceneElement>,
    ): MermaidScene {
        val bounds = elements.boundsOrDefault()
        val padding = context.options.timeline.padding
        val dx = padding - bounds.left
        val dy = padding - bounds.top
        return MermaidScene(
            width = max(1f, bounds.width + padding * 2f),
            height = max(1f, bounds.height + padding * 2f),
            background = context.theme.background,
            elements = elements
                .map { element -> element.translate(dx, dy) }
                .sortedBy(SceneElement::zIndex),
            title = document.title,
            accessibilityTitle = document.accessibilityTitle,
            accessibilityDescription = document.accessibilityDescription,
            viewportPadding = 0f,
            viewportSizing = if (context.options.timeline.useMaxWidth) {
                MermaidSceneViewportSizing.ResponsiveMaxWidth
            } else {
                MermaidSceneViewportSizing.Intrinsic
            },
        )
    }

    private fun validate(
        options: MermaidTimelineOptions,
        context: MermaidRenderContext,
    ): GMResult<Unit, MermaidError> {
        val values = listOf(
            "timeline.leftMargin" to options.leftMargin,
            "timeline.padding" to options.padding,
            "fontSize" to (context.options.fontSize ?: context.theme.fontSize),
        )
        val invalid = values.firstOrNull { (_, value) -> !value.isFinite() || value < 0f }
        return if (invalid == null) {
            GMResult.Ok(Unit)
        } else {
            GMResult.Err(
                MermaidError.Configuration(
                    "Mermaid ${invalid.first} must be a non-negative finite number",
                ),
            )
        }
    }

    private companion object {
        const val LR_MASTER_X = 50f
        const val LR_MASTER_Y = 50f
        const val LR_NODE_WIDTH = 150f
        const val LR_PADDING = 20f
        const val LR_EXTRA_HEIGHT = 20f
        const val LR_TASK_STEP = 200f
        const val LR_SECTION_WIDTH_ADJUST = 50f
        const val LR_SECTION_TASK_GAP = 50f
        const val LR_EVENT_START_OFFSET = 200f
        const val LR_EVENT_MIN_HEIGHT = 50f
        const val LR_CONNECTOR_X_OFFSET = 95f
        const val LR_CONNECTOR_TOP_GAP = 100f
        const val LR_CONNECTOR_BOTTOM_GAP = 100f
        const val LR_AXIS_WITH_SECTION = 150f
        const val LR_AXIS_WITHOUT_SECTION = 100f
        const val LR_AXIS_RIGHT_MARGIN_FACTOR = 3f
        const val LR_AXIS_STROKE = 4f
        const val TD_MASTER_X = 50f
        const val TD_MASTER_Y = 50f
        const val TD_NODE_WIDTH = 200f
        const val TD_NODE_PADDING = 5f
        const val TD_NODE_TOTAL_WIDTH = 210f
        const val TD_EVENT_WIDTH = 300f
        const val TD_EVENT_TOTAL_WIDTH = 310f
        const val TD_SECTION_TASK_GAP = 20f
        const val TD_TASK_AXIS_GAP = 20f
        const val TD_TASK_VERTICAL_GAP = 30f
        const val TD_EVENT_AXIS_GAP = 50f
        const val TD_MIN_SECTION_WIDTH = 50f
        const val TD_AXIS_STROKE = 4f
        const val TD_AXIS_BOTTOM_PADDING = 20f
        const val EVENT_SPACING = 10f
        const val CONNECTOR_STROKE = 2f
        const val TITLE_BASELINE = 20f
        const val TITLE_EX_MULTIPLIER = 4f
        const val CSS_EX_RATIO = 0.51855f
        const val MAX_TEXT_WIDTH = 100_000f
        const val UPSTREAM_TASK_OBJECT_TEXT = "[object Object]"
        const val NEO_LOOK = "neo"
        const val TITLE_Z_INDEX = Int.MAX_VALUE
    }
}

private class TimelineNodeFactory(
    private val context: MermaidRenderContext,
) {
    private var nodeCount = 0
    private val fontSize = context.options.fontSize ?: context.theme.fontSize
    private val fontFamily = context.options.fontFamily ?: context.theme.fontFamily
    private val redux = context.options.themeName?.lowercase()?.contains("redux") == true ||
        (context.options.themeName == null &&
            context.theme == com.swithun.cmpmermaid.core.MermaidTheme.FlowchartDefault)
    private val reduxColor = context.options.themeName?.lowercase() in REDUX_COLOR_THEMES ||
        (context.options.themeName == null &&
            context.theme == com.swithun.cmpmermaid.core.MermaidTheme.FlowchartDefault)
    private val dark = context.options.themeName?.lowercase()?.contains("dark") == true
    private val neo = context.options.look == NEO_LOOK
    private val gradient = neo &&
        context.theme.timeline.useGradient &&
        context.options.themeName?.lowercase() != "neutral"

    val lineColor: SceneColor = if (redux) {
        context.theme.timeline.nodeBorder
    } else {
        context.theme.timeline.sectionLabelColors.getOrElse(THEME_COLOR_LIMIT - 1) {
            context.theme.edge
        }
    }

    fun measure(
        text: String,
        width: Float,
        padding: Float,
        maxHeight: Float = 0f,
    ): GMResult<TimelineNodeMeasure, MermaidError> {
        val lines = when (val result = wrap(text, width)) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        // Mermaid 12.0.0 svgDraw.js derives node height from SVG getBBox().
        // Its configured timeline font resolves to a 19px box at 16px across
        // Latin and CJK text; Compose platform metrics otherwise vary by glyph.
        val lineBoxHeight = fontSize * SVG_TEXT_BBOX_HEIGHT_RATIO
        val bboxHeight = lineBoxHeight + (lines.size - 1).coerceAtLeast(0) *
            fontSize * TEXT_LINE_HEIGHT
        val naturalHeight = bboxHeight + fontSize * TEXT_LINE_HEIGHT * 0.5f + padding
        return GMResult.Ok(
            TimelineNodeMeasure(
                text = lines.joinToString("\n"),
                width = width + padding * 2f,
                height = max(naturalHeight, maxHeight),
                textHeight = bboxHeight,
                padding = padding,
            ),
        )
    }

    fun addNode(
        elements: MutableList<SceneElement>,
        text: String,
        x: Float,
        y: Float,
        width: Float,
        padding: Float,
        maxHeight: Float,
        sectionColor: Int,
        event: Boolean,
    ): GMResult<TimelineNodeMeasure, MermaidError> {
        val measured = when (val result = measure(text, width, padding, maxHeight)) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        return addMeasuredNode(elements, measured, x, y, sectionColor, event)
    }

    fun addMeasuredNode(
        elements: MutableList<SceneElement>,
        measured: TimelineNodeMeasure,
        x: Float,
        y: Float,
        sectionColor: Int,
        event: Boolean,
    ): GMResult<TimelineNodeMeasure, MermaidError> {
        val paletteIndex = sectionColor.mod(THEME_COLOR_LIMIT)
        val baseFill = if (redux) {
            if (reduxColor) {
                context.theme.borderColorArray.colorAt(
                    paletteIndex,
                    context.theme.timeline.mainBackground,
                )
            } else {
                context.theme.timeline.mainBackground
            }
        } else {
            context.theme.timeline.sectionFills.colorAt(
                paletteIndex,
                context.theme.timeline.mainBackground,
            )
        }
        val baseStroke = when {
            gradient -> context.theme.timeline.nodeBorder
            redux && reduxColor -> context.theme.borderColorArray.colorAt(
                paletteIndex,
                context.theme.timeline.nodeBorder,
            )
            redux -> context.theme.timeline.nodeBorder
            else -> TRANSPARENT
        }
        val baseText = if (redux) {
            context.theme.timeline.nodeBorder
        } else {
            context.theme.timeline.sectionLabelColors.colorAt(
                paletteIndex,
                context.theme.nodeText,
            )
        }
        val fill = if (gradient) context.theme.timeline.mainBackground else baseFill
        val stroke = if (event) baseStroke.brightness(EVENT_BRIGHTNESS) else baseStroke
        val textColor = if (event) baseText.brightness(EVENT_BRIGHTNESS) else baseText
        val shapeFill = if (event) fill.brightness(EVENT_BRIGHTNESS) else fill
        val bounds = SceneRect(
            left = x,
            top = y,
            right = x + measured.width,
            bottom = y + measured.height,
        )
        // Mermaid timelineRenderer appends each complete node wrapper in source order.
        val nodeIndex = nodeCount++
        val shapeId = "timeline-node-$nodeIndex"
        val paintBase = NODE_Z_START + nodeIndex * NODE_Z_STRIDE
        elements += SceneShape(
            id = shapeId,
            bounds = bounds,
            kind = SceneShapeKind.Rectangle,
            geometry = nodeGeometry(measured.width, measured.height, redux),
            fill = shapeFill,
            stroke = stroke,
            strokeWidth = when {
                gradient -> 2f
                redux -> context.theme.strokeWidth
                else -> 0f
            },
            cornerRadius = 0f,
            shadow = if (neo && redux) {
                SceneShadow(
                    color = if (dark) SceneColor(0x33FFFFFF) else SceneColor(0x0F000000),
                    offsetX = 4f,
                    offsetY = 4f,
                    blurRadius = 0f,
                )
            } else {
                null
            },
            strokeGradient = if (gradient) {
                SceneLinearGradient(
                    startColor = context.theme.timeline.gradientStart.let { color ->
                        if (event) color.brightness(EVENT_BRIGHTNESS) else color
                    },
                    endColor = context.theme.timeline.gradientStop.let { color ->
                        if (event) color.brightness(EVENT_BRIGHTNESS) else color
                    },
                )
            } else {
                null
            },
            zIndex = paintBase,
        )
        if (!redux) {
            val bottomColor = context.theme.timeline.sectionInverseColors.colorAt(
                paletteIndex,
                context.theme.nodeStroke,
            ).let { color -> if (event) color.brightness(EVENT_BRIGHTNESS) else color }
            elements += timelineLine(
                id = "$shapeId-bottom",
                start = ScenePoint(bounds.left, bounds.bottom),
                end = ScenePoint(bounds.right, bounds.bottom),
                color = bottomColor,
                strokeWidth = 3f,
                look = context.options.look,
                zIndex = paintBase + 1,
            )
        }
        val reduxOffset = when {
            !redux -> 0f
            event -> 3f - measured.padding / 2f
            else -> measured.padding / 2f
        }
        val textTop = y + (measured.height - measured.textHeight) / 2f + reduxOffset
        elements += SceneText(
            text = measured.text,
            bounds = SceneRect(
                left = x + measured.padding,
                top = textTop,
                right = x + measured.width - measured.padding,
                bottom = textTop + measured.textHeight,
            ),
            color = textColor,
            fontSize = fontSize,
            lineHeight = TEXT_LINE_HEIGHT,
            fontFamily = fontFamily,
            weight = if (redux) SceneTextWeight.Bold else SceneTextWeight.Normal,
            horizontalAlignment = SceneTextAlignment.Center,
            softWrap = false,
            zIndex = paintBase + 2,
        )
        return GMResult.Ok(measured)
    }

    private fun wrap(
        source: String,
        width: Float,
    ): GMResult<List<String>, MermaidError> {
        val tokens = source
            .replace(HTML_BREAK, " $HTML_BREAK ")
            .split(WHITESPACE)
            .filter(String::isNotEmpty)
        val lines = mutableListOf<String>()
        var current = ""
        tokens.forEach { token ->
            if (token == HTML_BREAK) {
                lines += current.trim()
                current = ""
                return@forEach
            }
            if (token.isBlank()) {
                return@forEach
            }
            val candidate = if (current.isEmpty()) token else "$current $token"
            val measuredWidth = try {
                context.textMetrics.measure(
                    TextMetricsRequest(
                        text = candidate,
                        fontSize = fontSize,
                        maxWidth = MAX_TEXT_WIDTH,
                        lineHeight = TEXT_LINE_HEIGHT,
                        fontFamily = fontFamily,
                        weight = if (redux) SceneTextWeight.Bold else SceneTextWeight.Normal,
                    ),
                ).width
            } catch (failure: Exception) {
                return GMResult.Err(
                    MermaidError.Unexpected.from(failure, "Timeline text measurement failed"),
                )
            }
            if (current.isNotEmpty() && measuredWidth > width) {
                lines += current.trim()
                current = token
            } else {
                current = candidate
            }
        }
        if (current.isNotEmpty() || lines.isEmpty()) {
            lines += current.trim()
        }
        return GMResult.Ok(lines)
    }

    private fun nodeGeometry(
        width: Float,
        height: Float,
        square: Boolean,
    ): SceneShapeGeometry {
        val left = -width / 2f
        val right = width / 2f
        val top = -height / 2f
        val bottom = height / 2f
        val outline = if (square) {
            listOf(
                ScenePoint(left, bottom),
                ScenePoint(left, top),
                ScenePoint(right, top),
                ScenePoint(right, bottom),
            )
        } else {
            buildList {
                add(ScenePoint(left, bottom))
                add(ScenePoint(left, top + CORNER_RADIUS))
                addAll(quarterArc(left + CORNER_RADIUS, top + CORNER_RADIUS, PI, PI * 1.5))
                add(ScenePoint(right - CORNER_RADIUS, top))
                addAll(
                    quarterArc(
                        right - CORNER_RADIUS,
                        top + CORNER_RADIUS,
                        PI * 1.5,
                        PI * 2.0,
                    ),
                )
                add(ScenePoint(right, bottom))
            }
        }
        return SceneShapeGeometry(
            paths = listOf(SceneShapePath(points = outline)),
            outline = outline,
        )
    }

    private fun quarterArc(
        centerX: Float,
        centerY: Float,
        start: Double,
        end: Double,
    ): List<ScenePoint> = (1..CORNER_SEGMENTS).map { index ->
        val angle = start + (end - start) * index / CORNER_SEGMENTS
        ScenePoint(
            x = centerX + cos(angle).toFloat() * CORNER_RADIUS,
            y = centerY + sin(angle).toFloat() * CORNER_RADIUS,
        )
    }

    private companion object {
        const val THEME_COLOR_LIMIT = 12
        const val TEXT_LINE_HEIGHT = 1.1f
        const val SVG_TEXT_BBOX_HEIGHT_RATIO = 19f / 16f
        const val EVENT_BRIGHTNESS = 1.2f
        const val MAX_TEXT_WIDTH = 100_000f
        const val CORNER_RADIUS = 5f
        const val CORNER_SEGMENTS = 4
        const val NODE_Z_START = 10
        const val NODE_Z_STRIDE = 3
        const val NEO_LOOK = "neo"
        const val HTML_BREAK = "<br>"
        val WHITESPACE = Regex("""\s+""")
        val REDUX_COLOR_THEMES = setOf("redux-color", "redux-dark-color")
        val TRANSPARENT = SceneColor(0x00000000)
    }
}

private data class TimelineNodeMeasure(
    val text: String,
    val width: Float,
    val height: Float,
    val textHeight: Float,
    val padding: Float,
) {
    val naturalHeight: Float get() = height
}

private fun line(
    id: String,
    start: ScenePoint,
    end: ScenePoint,
    color: SceneColor,
    strokeWidth: Float,
    arrow: Boolean,
    dashed: Boolean,
    context: MermaidRenderContext,
    zIndex: Int = 5,
): ScenePath = timelineLine(
    id = id,
    start = start,
    end = end,
    color = color,
    strokeWidth = strokeWidth,
    look = context.options.look,
    arrowEnd = if (arrow) SceneArrowHead.Triangle else SceneArrowHead.None,
    dashed = dashed,
    markerBackground = context.theme.background,
    zIndex = zIndex,
)

private fun timelineLine(
    id: String,
    start: ScenePoint,
    end: ScenePoint,
    color: SceneColor,
    strokeWidth: Float,
    look: String,
    arrowEnd: SceneArrowHead = SceneArrowHead.None,
    dashed: Boolean = false,
    markerBackground: SceneColor? = null,
    zIndex: Int,
): ScenePath = ScenePath(
    id = id,
    points = listOf(start, end),
    commands = listOf(
        ScenePathCommand.MoveTo(start),
        ScenePathCommand.LineTo(end),
    ),
    color = color,
    strokeWidth = strokeWidth,
    strokePattern = if (dashed) SceneStrokePattern.Dashed else SceneStrokePattern.Solid,
    arrowEnd = arrowEnd,
    curve = "linear",
    look = look,
    animated = false,
    dashIntervals = if (dashed) listOf(5f, 5f) else emptyList(),
    markerBackground = markerBackground,
    zIndex = zIndex,
)

private fun List<SceneElement>.boundsOrDefault(): SceneRect =
    mapNotNull(::elementBounds).reduceOrNull(SceneRect::union)
        ?: SceneRect(0f, 0f, 1f, 1f)

private fun elementBounds(element: SceneElement): SceneRect? = when (element) {
    is SceneAsset -> element.bounds
    is SceneShape -> element.bounds
    is SceneText -> element.bounds
    is ScenePath -> {
        val first = element.points.firstOrNull()
        if (first == null) null else element.points.drop(1).fold(
            SceneRect(first.x, first.y, first.x, first.y),
        ) { bounds, point ->
            SceneRect(
                left = min(bounds.left, point.x),
                top = min(bounds.top, point.y),
                right = max(bounds.right, point.x),
                bottom = max(bounds.bottom, point.y),
            )
        }
    }
}

private fun SceneElement.translate(
    dx: Float,
    dy: Float,
): SceneElement = when (this) {
    is SceneAsset -> copy(bounds = bounds.translate(dx, dy))
    is SceneShape -> copy(bounds = bounds.translate(dx, dy))
    is SceneText -> copy(
        bounds = bounds.translate(dx, dy),
        rotationPivot = rotationPivot?.translate(dx, dy),
    )
    is ScenePath -> copy(
        points = points.map { point -> point.translate(dx, dy) },
        commands = commands.map { command -> command.translate(dx, dy) },
    )
}

private fun ScenePathCommand.translate(
    dx: Float,
    dy: Float,
): ScenePathCommand = when (this) {
    is ScenePathCommand.MoveTo -> copy(point = point.translate(dx, dy))
    is ScenePathCommand.LineTo -> copy(point = point.translate(dx, dy))
    is ScenePathCommand.QuadraticTo -> copy(
        control = control.translate(dx, dy),
        end = end.translate(dx, dy),
    )
    is ScenePathCommand.CubicTo -> copy(
        control1 = control1.translate(dx, dy),
        control2 = control2.translate(dx, dy),
        end = end.translate(dx, dy),
    )
    is ScenePathCommand.ArcTo -> copy(end = end.translate(dx, dy))
}

private fun ScenePoint.translate(
    dx: Float,
    dy: Float,
): ScenePoint = ScenePoint(x + dx, y + dy)

private fun List<SceneColor>.colorAt(
    index: Int,
    fallback: SceneColor,
): SceneColor = getOrNull(index.mod(12)) ?: fallback

private fun SceneColor.brightness(factor: Float): SceneColor {
    val alpha = (argb ushr 24) and 0xFF
    val red = ((((argb ushr 16) and 0xFF) * factor).roundToInt()).coerceIn(0, 255)
    val green = ((((argb ushr 8) and 0xFF) * factor).roundToInt()).coerceIn(0, 255)
    val blue = (((argb and 0xFF) * factor).roundToInt()).coerceIn(0, 255)
    return SceneColor(
        (alpha shl 24) or
            (red.toLong() shl 16) or
            (green.toLong() shl 8) or
            blue.toLong(),
    )
}
