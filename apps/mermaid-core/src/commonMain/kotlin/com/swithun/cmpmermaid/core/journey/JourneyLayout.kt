package com.swithun.cmpmermaid.core.journey

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidJourneyOptions
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.SceneElement
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneShapeGeometry
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneShapePaint
import com.swithun.cmpmermaid.core.SceneShapePath
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.SceneTextAlignment
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.TextMetrics
import com.swithun.cmpmermaid.core.TextMetricsRequest
import com.swithun.cmpmermaid.core.journey.upstream.mermaid.JourneyDb
import com.swithun.cmpmermaid.core.journey.upstream.mermaid.JourneyTask
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Native translation of Mermaid 12.0.0 journeyRenderer.ts and svgDraw.js.
 */
internal class JourneyLayout {
    fun layout(
        db: JourneyDb,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val options = context.options.journey
        when (val validation = validate(options)) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return validation
        }
        val tasks = db.getTasks()
        if (tasks.size > context.options.maxEdges) {
            return GMResult.Err(
                MermaidError.ResourceLimit(
                    resource = "Journey tasks",
                    actual = tasks.size,
                    maximum = context.options.maxEdges,
                ),
            )
        }
        val actors = db.getActors()
        val actorLayout = when (
            val measured = layoutActors(
                actorNames = actors,
                options = options,
                context = context,
            )
        ) {
            is GMResult.Ok -> measured.value
            is GMResult.Err -> return measured
        }
        val leftMargin = options.leftMargin + actorLayout.maxWidth
        val elements = actorLayout.elements.toMutableList()
        val title = db.diagramTitle?.takeIf(String::isNotBlank)
        val verticalShift = VIEWBOX_TOP_SHIFT

        addTasks(
            tasks = tasks,
            actorPositions = actors.withIndex().associate { indexed ->
                indexed.value to indexed.index
            },
            leftMargin = leftMargin,
            verticalShift = verticalShift,
            context = context,
            elements = elements,
        )

        val stopX = if (tasks.isEmpty()) {
            leftMargin
        } else {
            leftMargin +
                (tasks.lastIndex * (options.taskMargin + options.width)) +
                options.diagramMarginX +
                options.taskMargin
        }
        val boundsStopY = max(
            actors.size * ACTOR_BOUNDS_STEP,
            if (tasks.isEmpty()) 0f else TASK_LINE_BOTTOM,
        )
        val baseHeight = boundsStopY + 2f * options.diagramMarginY
        val width = leftMargin + stopX + 2f * options.diagramMarginX
        val height = baseHeight +
            if (title == null) VIEWPORT_BOTTOM_PADDING else TITLE_EXTRA_HEIGHT +
            VIEWPORT_BOTTOM_PADDING

        elements += path(
            id = "journey-activity-line",
            start = ScenePoint(leftMargin, options.height * 4f + verticalShift),
            end = ScenePoint(
                width - leftMargin - ACTIVITY_ARROW_RETAIN,
                options.height * 4f + verticalShift,
            ),
            color = context.theme.journey.textColor,
            strokeWidth = 4f,
            arrowEnd = SceneArrowHead.Triangle,
            zIndex = 8,
            look = context.options.look,
        )

        if (title != null) {
            val fontSize = resolveTitleFontSize(options.titleFontSize, context.theme.fontSize)
            val measured = when (
                val result = measure(
                    text = title,
                    fontSize = fontSize,
                    maxWidth = UNWRAPPED_TEXT_WIDTH,
                    fontFamily = options.titleFontFamily,
                    weight = SceneTextWeight.Bold,
                    context = context,
                )
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            val baseline = TITLE_BASELINE + verticalShift
            elements += SceneText(
                text = title,
                bounds = SceneRect(
                    left = leftMargin - TEXT_START_INSET,
                    top = baseline - measured.height,
                    right = leftMargin + measured.width,
                    bottom = baseline,
                ),
                color = options.titleColor ?: context.theme.journey.textColor,
                fontSize = fontSize,
                fontFamily = options.titleFontFamily,
                weight = SceneTextWeight.Bold,
                horizontalAlignment = SceneTextAlignment.Start,
                softWrap = false,
                zIndex = 30,
            )
        }

        return GMResult.Ok(
            MermaidScene(
                width = width,
                height = height,
                background = context.theme.background,
                elements = elements.sortedBy(SceneElement::zIndex),
                title = db.diagramTitle,
                accessibilityTitle = db.accessibilityTitle,
                accessibilityDescription = db.accessibilityDescription,
            ),
        )
    }

    private fun layoutActors(
        actorNames: List<String>,
        options: MermaidJourneyOptions,
        context: MermaidRenderContext,
    ): GMResult<ActorLayout, MermaidError> {
        val elements = mutableListOf<SceneElement>()
        var maxWidth = 0f
        var yPos = ACTOR_START_Y
        actorNames.forEachIndexed { index, person ->
            val lines = when (
                val result = wrapActorLabel(
                    text = person,
                    maxWidth = options.maxLabelWidth,
                    context = context,
                )
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            val color = context.theme.journey.actorColors.getOrNull(index)
                ?: options.actorColours[index.mod(options.actorColours.size)]
            elements += circle(
                id = "journey-actor-$index",
                center = ScenePoint(ACTOR_CIRCLE_X, yPos + VIEWBOX_TOP_SHIFT),
                radius = ACTOR_RADIUS,
                fill = color,
                stroke = BLACK,
                strokeWidth = 1f,
                zIndex = 15,
            )
            lines.forEachIndexed { lineIndex, line ->
                val measured = when (
                    val result = measure(
                        text = line,
                        fontSize = context.theme.fontSize,
                        maxWidth = options.maxLabelWidth,
                        fontFamily = context.theme.fontFamily,
                        weight = SceneTextWeight.Normal,
                        context = context,
                    )
                ) {
                    is GMResult.Ok -> result.value
                    is GMResult.Err -> return result
                }
                if (
                    measured.width > maxWidth &&
                    measured.width > options.leftMargin - measured.width
                ) {
                    maxWidth = measured.width
                }
                val baseline = yPos + ACTOR_LABEL_BASELINE_OFFSET +
                    lineIndex * ACTOR_LINE_HEIGHT +
                    VIEWBOX_TOP_SHIFT
                elements += SceneText(
                    text = line,
                    bounds = SceneRect(
                        left = ACTOR_LABEL_X - TEXT_START_INSET,
                        top = baseline - measured.height,
                        right = ACTOR_LABEL_X + measured.width,
                        bottom = baseline,
                    ),
                    color = context.theme.journey.textColor,
                    fontSize = context.theme.fontSize,
                    fontFamily = context.theme.fontFamily,
                    weight = SceneTextWeight.Normal,
                    horizontalAlignment = SceneTextAlignment.Start,
                    softWrap = false,
                    zIndex = 20,
                )
            }
            yPos += max(ACTOR_LINE_HEIGHT, lines.size * ACTOR_LINE_HEIGHT)
        }
        return GMResult.Ok(ActorLayout(maxWidth = maxWidth, elements = elements))
    }

    private fun wrapActorLabel(
        text: String,
        maxWidth: Float,
        context: MermaidRenderContext,
    ): GMResult<List<String>, MermaidError> {
        val full = when (
            val result = measure(
                text = text,
                fontSize = context.theme.fontSize,
                maxWidth = UNWRAPPED_TEXT_WIDTH,
                fontFamily = context.theme.fontFamily,
                weight = SceneTextWeight.Normal,
                context = context,
            )
        ) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        if (full.width <= maxWidth) {
            return GMResult.Ok(listOf(text))
        }

        val lines = mutableListOf<String>()
        var currentLine = ""
        text.split(' ').forEach { word ->
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            val testWidth = when (
                val result = measure(
                    text = testLine,
                    fontSize = context.theme.fontSize,
                    maxWidth = UNWRAPPED_TEXT_WIDTH,
                    fontFamily = context.theme.fontFamily,
                    weight = SceneTextWeight.Normal,
                    context = context,
                )
            ) {
                is GMResult.Ok -> result.value.width
                is GMResult.Err -> return result
            }
            if (testWidth <= maxWidth) {
                currentLine = testLine
                return@forEach
            }
            if (currentLine.isNotEmpty()) {
                lines += currentLine
            }
            currentLine = word
            val wordWidth = when (
                val result = measure(
                    text = word,
                    fontSize = context.theme.fontSize,
                    maxWidth = UNWRAPPED_TEXT_WIDTH,
                    fontFamily = context.theme.fontFamily,
                    weight = SceneTextWeight.Normal,
                    context = context,
                )
            ) {
                is GMResult.Ok -> result.value.width
                is GMResult.Err -> return result
            }
            if (wordWidth > maxWidth) {
                var brokenWord = ""
                word.forEach { character ->
                    brokenWord += character
                    val candidate = "$brokenWord-"
                    val candidateWidth = when (
                        val result = measure(
                            text = candidate,
                            fontSize = context.theme.fontSize,
                            maxWidth = UNWRAPPED_TEXT_WIDTH,
                            fontFamily = context.theme.fontFamily,
                            weight = SceneTextWeight.Normal,
                            context = context,
                        )
                    ) {
                        is GMResult.Ok -> result.value.width
                        is GMResult.Err -> return result
                    }
                    if (candidateWidth > maxWidth && brokenWord.length > 1) {
                        lines += "${brokenWord.dropLast(1)}-"
                        brokenWord = character.toString()
                    }
                }
                currentLine = brokenWord
            }
        }
        if (currentLine.isNotEmpty()) {
            lines += currentLine
        }
        return GMResult.Ok(lines)
    }

    private fun addTasks(
        tasks: List<JourneyTask>,
        actorPositions: Map<String, Int>,
        leftMargin: Float,
        verticalShift: Float,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        val options = context.options.journey
        var lastSection = ""
        var sectionNumber = 0
        tasks.forEachIndexed { index, task ->
            val styleCount = options.sectionFills.size
            if (lastSection != task.section) {
                val styleIndex = sectionNumber.mod(styleCount)
                val sectionTaskCount = tasks
                    .drop(index)
                    .takeWhile { candidate -> candidate.section == task.section }
                    .size
                val sectionLeft = taskX(index, leftMargin, options)
                val sectionBounds = SceneRect(
                    left = sectionLeft,
                    top = SECTION_TOP + verticalShift,
                    right = sectionLeft +
                        options.width * sectionTaskCount +
                        options.diagramMarginX * (sectionTaskCount - 1),
                    bottom = SECTION_TOP + options.height + verticalShift,
                )
                elements += box(
                    id = "journey-section-$sectionNumber",
                    bounds = sectionBounds,
                    fill = sectionColor(styleIndex, context),
                    stroke = context.theme.journey.boxStroke,
                    zIndex = 10,
                )
                addBoxText(
                    id = "journey-section-label-$sectionNumber",
                    text = task.section,
                    bounds = sectionBounds,
                    styleIndex = styleIndex,
                    context = context,
                    elements = elements,
                )
                lastSection = task.section
                sectionNumber += 1
            }

            val styleIndex = (sectionNumber - 1).mod(styleCount)
            addTask(
                index = index,
                task = task,
                styleIndex = styleIndex,
                actorPositions = actorPositions,
                leftMargin = leftMargin,
                verticalShift = verticalShift,
                context = context,
                elements = elements,
            )
        }
    }

    private fun addTask(
        index: Int,
        task: JourneyTask,
        styleIndex: Int,
        actorPositions: Map<String, Int>,
        leftMargin: Float,
        verticalShift: Float,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        val options = context.options.journey
        val left = taskX(index, leftMargin, options)
        val top = options.height * 2f + options.diagramMarginY + verticalShift
        val centerX = left + options.width / 2f
        elements += path(
            id = "journey-task-line-$index",
            start = ScenePoint(centerX, top),
            end = ScenePoint(centerX, TASK_LINE_BOTTOM + verticalShift),
            color = context.theme.journey.textColor,
            strokeWidth = 1f,
            pattern = SceneStrokePattern.Dashed,
            dashIntervals = listOf(4f, 2f),
            zIndex = 5,
            look = context.options.look,
        )

        if (task.score.isFinite()) {
            addFace(
                index = index,
                center = ScenePoint(
                    centerX,
                    FACE_SCORE_BASELINE + ((MAX_SCORE - task.score) * SCORE_STEP).toFloat() +
                        verticalShift,
                ),
                score = task.score,
                context = context,
                elements = elements,
            )
        }

        val taskBounds = SceneRect(
            left = left,
            top = top,
            right = left + options.width,
            bottom = top + options.height,
        )
        elements += box(
            id = "journey-task-$index",
            bounds = taskBounds,
            fill = sectionColor(styleIndex, context),
            stroke = context.theme.journey.boxStroke,
            zIndex = 12,
        )
        task.people.forEachIndexed { personIndex, person ->
            val actorIndex = actorPositions[person] ?: 0
            val actorColor = context.theme.journey.actorColors.getOrNull(actorIndex)
                ?: options.actorColours[actorIndex.mod(options.actorColours.size)]
            elements += circle(
                id = "journey-task-$index-actor-$personIndex",
                center = ScenePoint(
                    x = left + TASK_ACTOR_START_X + personIndex * TASK_ACTOR_STEP,
                    y = top,
                ),
                radius = ACTOR_RADIUS,
                fill = actorColor,
                stroke = BLACK,
                strokeWidth = 1f,
                zIndex = 15,
            )
        }
        addBoxText(
            id = "journey-task-label-$index",
            text = task.task,
            bounds = taskBounds,
            styleIndex = styleIndex,
            context = context,
            elements = elements,
        )
    }

    private fun addFace(
        index: Int,
        center: ScenePoint,
        score: Double,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        elements += circle(
            id = "journey-face-$index",
            center = center,
            radius = FACE_RADIUS,
            fill = context.theme.journey.faceColor,
            stroke = context.theme.journey.faceStroke,
            strokeWidth = 2f,
            zIndex = 10,
        )
        listOf(-1f, 1f).forEachIndexed { eyeIndex, direction ->
            elements += circle(
                id = "journey-face-$index-eye-$eyeIndex",
                center = ScenePoint(
                    x = center.x + direction * FACE_RADIUS / 3f,
                    y = center.y - FACE_RADIUS / 3f,
                ),
                radius = EYE_RADIUS,
                fill = context.theme.journey.detailStroke,
                stroke = context.theme.journey.detailStroke,
                strokeWidth = 2f,
                zIndex = 11,
            )
        }
        val mouthCenter = when {
            score > 3.0 -> ScenePoint(center.x, center.y + 2f)
            score < 3.0 -> ScenePoint(center.x, center.y + 7f)
            else -> ScenePoint(center.x, center.y + 7f)
        }
        val points = when {
            score > 3.0 -> arcPoints(mouthCenter, MOUTH_RADIUS, 0.0, PI)
            score < 3.0 -> arcPoints(mouthCenter, MOUTH_RADIUS, PI, 2.0 * PI)
            else -> listOf(
                ScenePoint(center.x - 5f, mouthCenter.y),
                ScenePoint(center.x + 5f, mouthCenter.y),
            )
        }
        elements += polyline(
            id = "journey-face-$index-mouth",
            points = points,
            color = context.theme.journey.detailStroke,
            strokeWidth = 1f,
            zIndex = 12,
            look = context.options.look,
        )
    }

    private fun addBoxText(
        id: String,
        text: String,
        bounds: SceneRect,
        styleIndex: Int,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        val options = context.options.journey
        // Mermaid 12.0.0: svgDraw.js -> _drawTextCandidateFunc.
        when (options.textPlacement) {
            "fo" -> elements += SceneText(
                text = text,
                bounds = bounds,
                color = context.theme.journey.textColor,
                fontSize = context.theme.fontSize,
                lineHeight = 1f,
                fontFamily = context.theme.fontFamily,
                weight = SceneTextWeight.Normal,
                horizontalAlignment = SceneTextAlignment.Center,
                softWrap = true,
                clipToBounds = true,
                zIndex = 20,
            )
            "old" -> elements += SceneText(
                text = text,
                bounds = bounds,
                color = context.theme.journey.textColor,
                fontSize = context.theme.fontSize,
                lineHeight = 1f,
                fontFamily = context.theme.fontFamily,
                weight = SceneTextWeight.Normal,
                horizontalAlignment = SceneTextAlignment.Center,
                softWrap = false,
                zIndex = 20,
            )
            else -> {
                val lines = text.split(HTML_BREAK)
                lines.forEachIndexed { lineIndex, line ->
                    val offsetY = lineIndex * options.taskFontSize -
                        options.taskFontSize * (lines.size - 1) / 2f
                    elements += SceneText(
                        text = line,
                        bounds = bounds.copy(
                            top = bounds.top + offsetY,
                            bottom = bounds.bottom + offsetY,
                        ),
                        color = options.sectionColours[
                            styleIndex.mod(options.sectionColours.size)
                        ],
                        fontSize = options.taskFontSize,
                        lineHeight = 1f,
                        fontFamily = options.taskFontFamily,
                        weight = SceneTextWeight.Normal,
                        horizontalAlignment = SceneTextAlignment.Center,
                        softWrap = false,
                        zIndex = 20,
                    )
                }
            }
        }
    }

    private fun sectionColor(
        styleIndex: Int,
        context: MermaidRenderContext,
    ): SceneColor = context.theme.journey.sectionFills.getOrNull(styleIndex)
        ?: context.options.journey.sectionFills[styleIndex.mod(
            context.options.journey.sectionFills.size,
        )]

    private fun taskX(
        index: Int,
        leftMargin: Float,
        options: MermaidJourneyOptions,
    ): Float = index * (options.taskMargin + options.width) + leftMargin

    private fun box(
        id: String,
        bounds: SceneRect,
        fill: SceneColor,
        stroke: SceneColor,
        zIndex: Int,
    ): SceneShape = SceneShape(
        id = id,
        bounds = bounds,
        kind = SceneShapeKind.RoundedRectangle,
        fill = fill,
        stroke = stroke,
        strokeWidth = 1f,
        cornerRadius = 3f,
        zIndex = zIndex,
    )

    private fun circle(
        id: String,
        center: ScenePoint,
        radius: Float,
        fill: SceneColor,
        stroke: SceneColor,
        strokeWidth: Float,
        zIndex: Int,
    ): SceneShape {
        val points = arcPoints(center = ScenePoint(0f, 0f), radius, 0.0, 2.0 * PI)
        return SceneShape(
            id = id,
            bounds = SceneRect(
                left = center.x - radius,
                top = center.y - radius,
                right = center.x + radius,
                bottom = center.y + radius,
            ),
            kind = SceneShapeKind.Circle,
            geometry = SceneShapeGeometry(
                paths = listOf(
                    SceneShapePath(
                        points = points,
                        closed = true,
                        fill = SceneShapePaint.Fill,
                        stroke = SceneShapePaint.Stroke,
                    ),
                ),
                outline = points,
            ),
            fill = fill,
            stroke = stroke,
            strokeWidth = strokeWidth,
            zIndex = zIndex,
        )
    }

    private fun path(
        id: String,
        start: ScenePoint,
        end: ScenePoint,
        color: SceneColor,
        strokeWidth: Float,
        arrowEnd: SceneArrowHead = SceneArrowHead.None,
        pattern: SceneStrokePattern = SceneStrokePattern.Solid,
        dashIntervals: List<Float> = emptyList(),
        zIndex: Int,
        look: String,
    ): ScenePath = polyline(
        id = id,
        points = listOf(start, end),
        color = color,
        strokeWidth = strokeWidth,
        arrowEnd = arrowEnd,
        pattern = pattern,
        dashIntervals = dashIntervals,
        zIndex = zIndex,
        look = look,
    )

    private fun polyline(
        id: String,
        points: List<ScenePoint>,
        color: SceneColor,
        strokeWidth: Float,
        arrowEnd: SceneArrowHead = SceneArrowHead.None,
        pattern: SceneStrokePattern = SceneStrokePattern.Solid,
        dashIntervals: List<Float> = emptyList(),
        zIndex: Int,
        look: String,
    ): ScenePath = ScenePath(
        id = id,
        points = points,
        commands = points.mapIndexed { index, point ->
            if (index == 0) {
                ScenePathCommand.MoveTo(point)
            } else {
                ScenePathCommand.LineTo(point)
            }
        },
        color = color,
        strokeWidth = strokeWidth,
        strokePattern = pattern,
        arrowEnd = arrowEnd,
        curve = "linear",
        look = look,
        animated = false,
        zIndex = zIndex,
        dashIntervals = dashIntervals,
        markerBackground = WHITE,
    )

    private fun arcPoints(
        center: ScenePoint,
        radius: Float,
        startAngle: Double,
        endAngle: Double,
        segments: Int = 20,
    ): List<ScenePoint> = (0..segments).map { index ->
        val angle = startAngle + (endAngle - startAngle) * index / segments
        ScenePoint(
            x = center.x + cos(angle).toFloat() * radius,
            y = center.y + sin(angle).toFloat() * radius,
        )
    }

    private fun measure(
        text: String,
        fontSize: Float,
        maxWidth: Float,
        fontFamily: String,
        weight: SceneTextWeight,
        context: MermaidRenderContext,
    ): GMResult<TextMetrics, MermaidError> = try {
        GMResult.Ok(
            context.textMetrics.measure(
                TextMetricsRequest(
                    text = text,
                    fontSize = fontSize,
                    maxWidth = maxWidth,
                    lineHeight = 1.2f,
                    fontFamily = fontFamily,
                    weight = weight,
                ),
            ),
        )
    } catch (failure: Exception) {
        GMResult.Err(
            MermaidError.Unexpected.from(failure, "Journey text measurement failed"),
        )
    }

    private fun validate(
        options: MermaidJourneyOptions,
    ): GMResult<Unit, MermaidError> {
        val positive = listOf(
            "diagramMarginX" to options.diagramMarginX,
            "diagramMarginY" to options.diagramMarginY,
            "leftMargin" to options.leftMargin,
            "maxLabelWidth" to options.maxLabelWidth,
            "width" to options.width,
            "height" to options.height,
            "taskFontSize" to options.taskFontSize,
            "taskMargin" to options.taskMargin,
        ).firstOrNull { (_, value) -> !value.isFinite() || value <= 0f }
        if (positive != null) {
            return configurationError("Journey ${positive.first} must be positive")
        }
        if (
            options.actorColours.isEmpty() ||
            options.sectionFills.isEmpty() ||
            options.sectionColours.isEmpty()
        ) {
            return configurationError("Journey color arrays must not be empty")
        }
        if (options.textPlacement !in TEXT_PLACEMENTS) {
            return configurationError(
                "Journey textPlacement must be one of ${TEXT_PLACEMENTS.joinToString()}",
            )
        }
        return GMResult.Ok(Unit)
    }

    private fun resolveTitleFontSize(
        value: String,
        rootFontSize: Float,
    ): Float {
        val normalized = value.trim().lowercase()
        return when {
            normalized.endsWith("px") ->
                normalized.removeSuffix("px").trim().toFloatOrNull()
            normalized.endsWith("em") ->
                normalized.removeSuffix("em").trim().toFloatOrNull()?.times(rootFontSize)
            normalized.endsWith("ex") ->
                normalized.removeSuffix("ex").trim().toFloatOrNull()
                    ?.times(rootFontSize)
                    ?.times(CSS_EX_RATIO)
            else -> normalized.toFloatOrNull()
        }?.takeIf { size -> size.isFinite() && size > 0f } ?: rootFontSize
    }

    private fun <T> configurationError(message: String): GMResult<T, MermaidError> =
        GMResult.Err(MermaidError.Configuration(message))

    private data class ActorLayout(
        val maxWidth: Float,
        val elements: List<SceneElement>,
    )

    private companion object {
        const val ACTOR_CIRCLE_X = 20f
        // Mermaid.js 12.0.0: drawActorLegend + svgDrawCommon.drawText.
        // The legend passes x=40 and drawText offsets its tspan by 2 * boxTextMargin.
        const val ACTOR_LABEL_X = 50f
        const val ACTOR_START_Y = 60f
        const val ACTOR_RADIUS = 7f
        const val ACTOR_LABEL_BASELINE_OFFSET = 7f
        const val ACTOR_LINE_HEIGHT = 20f
        const val ACTOR_BOUNDS_STEP = 50f
        const val TEXT_START_INSET = 4f
        const val SECTION_TOP = 50f
        const val TASK_LINE_BOTTOM = 450f
        const val FACE_SCORE_BASELINE = 300f
        const val SCORE_STEP = 30.0
        const val MAX_SCORE = 5.0
        const val FACE_RADIUS = 15f
        const val EYE_RADIUS = 1.5f
        const val MOUTH_RADIUS = FACE_RADIUS / 2f
        const val TASK_ACTOR_START_X = 14f
        const val TASK_ACTOR_STEP = 10f
        const val TITLE_BASELINE = 25f
        const val TITLE_EXTRA_HEIGHT = 70f
        const val VIEWBOX_TOP_SHIFT = 25f
        const val VIEWPORT_BOTTOM_PADDING = 25f
        const val ACTIVITY_ARROW_RETAIN = 4f
        const val CSS_EX_RATIO = 0.51855f
        const val UNWRAPPED_TEXT_WIDTH = 100_000f
        val HTML_BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
        val TEXT_PLACEMENTS = setOf("fo", "old", "tspan")
        val BLACK = SceneColor(0xFF000000)
        val WHITE = SceneColor(0xFFFFFFFF)
    }
}
