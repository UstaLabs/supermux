package com.swithun.cmpmermaid.core.sequence

import com.swithun.cmpmermaid.core.CssColorParser
import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.SceneElement
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.SceneShadow
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneShapeGeometry
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneShapePaint
import com.swithun.cmpmermaid.core.SceneShapePath
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.TextMetrics
import com.swithun.cmpmermaid.core.TextMetricsRequest
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidHtmlEntityDecoder
import com.swithun.cmpmermaid.core.sequence.upstream.mermaid.SequenceActor
import com.swithun.cmpmermaid.core.sequence.upstream.mermaid.SequenceDb
import com.swithun.cmpmermaid.core.sequence.upstream.mermaid.SequenceLineType
import com.swithun.cmpmermaid.core.sequence.upstream.mermaid.SequenceMessage
import com.swithun.cmpmermaid.core.sequence.upstream.mermaid.SequencePlacement
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Native translation of the sizing and drawing phases in sequenceRenderer.ts,
 * svgDraw.js and actorBands.ts from Mermaid 12.0.0.
 */
internal class SequenceLayout {
    fun layout(
        db: SequenceDb,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val regularMessages = db.getMessages().count { it.type in MESSAGE_TYPES }
        if (regularMessages > context.options.maxEdges) {
            return GMResult.Err(
                MermaidError.ResourceLimit(
                    resource = "maxEdges",
                    actual = regularMessages,
                    maximum = context.options.maxEdges,
                ),
            )
        }
        if (db.getActors().values.any { it.links.isNotEmpty() }) {
            return unsupported("sequence participant link menus")
        }
        if (db.getActors().values.any { it.properties.isNotEmpty() }) {
            return unsupported("sequence participant properties")
        }

        return try {
            GMResult.Ok(buildScene(db, context))
        } catch (failure: Exception) {
            GMResult.Err(
                MermaidError.Unexpected.from(failure, "Sequence layout failed"),
            )
        }
    }

    private fun buildScene(
        db: SequenceDb,
        context: MermaidRenderContext,
        controlWrapWidths: Map<String, Float>? = null,
    ): MermaidScene {
        val actorKeys = db.getActors().keys.toList()
        val actorIndex = actorKeys.withIndex().associate { (index, id) -> id to index }
        val actors = actorKeys.mapIndexed { index, id ->
            val actor = db.getActors().getValue(id)
            val text = decode(actor.description)
            val textMetrics = measure(
                context = context,
                text = text,
                fontSize = ACTOR_FONT_SIZE,
                maxWidth = if (actor.wrap) ACTOR_WIDTH - 2f * WRAP_PADDING else MAX_TEXT_WIDTH,
            )
            LayoutActor(
                source = actor,
                colorIndex = index,
                text = text,
                textMetrics = textMetrics,
                width = if (actor.wrap) {
                    ACTOR_WIDTH
                } else {
                    max(ACTOR_WIDTH, textMetrics.width + 2f * WRAP_PADDING)
                },
            )
        }
        val actorRowHeight = max(
            ACTOR_HEIGHT,
            actors.maxOfOrNull { actor ->
                if (actor.source.type == "participant" ||
                    actor.source.type == "collections" ||
                    actor.source.type == "queue"
                ) {
                    ACTOR_HEIGHT
                } else {
                    GLYPH_BAND_HEIGHT +
                        GLYPH_LABEL_GAP +
                        actor.textMetrics.height +
                        LABEL_LIFELINE_GAP
                }
            } ?: ACTOR_HEIGHT,
        )
        actors.forEach { it.height = actorRowHeight }
        val gapWidths = calculateGaps(actors, actorIndex, db.getMessages(), context)
        val leftReserve = externalNoteReserve(
            db = db,
            actors = actors,
            actorIndex = actorIndex,
            context = context,
            placement = SequencePlacement.LEFT_OF,
        )
        var cursor = DIAGRAM_MARGIN_X + leftReserve
        actors.forEachIndexed { index, actor ->
            actor.left = cursor
            actor.centerX = cursor + actor.width / 2f
            cursor += actor.width
            if (index < gapWidths.size) {
                cursor += gapWidths[index]
            }
        }
        val rightReserve = externalNoteReserve(
            db = db,
            actors = actors,
            actorIndex = actorIndex,
            context = context,
            placement = SequencePlacement.RIGHT_OF,
        )
        val contentRight = max(cursor + rightReserve, DIAGRAM_MARGIN_X * 2f)
        val titleMetrics = db.diagramTitle
            ?.takeIf(String::isNotBlank)
            ?.let {
                measure(context, decode(it), MESSAGE_FONT_SIZE, MAX_TEXT_WIDTH)
            }
        val hasBoxTitle = db.getBoxes().any { !it.name.isNullOrBlank() }
        val boxTitleHeight = if (hasBoxTitle) {
            db.getBoxes().maxOf { box ->
                measure(
                    context,
                    decode(box.name.orEmpty()),
                    MESSAGE_FONT_SIZE,
                    MAX_TEXT_WIDTH,
                ).height
            }
        } else {
            0f
        }
        val titleOffset = if (titleMetrics == null) 0f else TITLE_HEIGHT
        val actorTop = DIAGRAM_MARGIN_Y +
            titleOffset +
            if (db.getBoxes().isEmpty()) 0f else BOX_MARGIN + boxTitleHeight
        val backgroundElements = mutableListOf<SceneElement>()
        val pathElements = mutableListOf<SceneElement>()
        val foregroundElements = mutableListOf<SceneElement>()
        val activationStarts = linkedMapOf<String, MutableList<Float>>()
        val actorStart = actors.associate { actor ->
            actor.source.id to if (actor.source.id in db.getCreatedActors()) {
                Float.NaN
            } else {
                actorTop
            }
        }.toMutableMap()
        val actorEnd = mutableMapOf<String, Float>()
        val completedFrames = mutableListOf<ControlFrame>()
        val frameStack = mutableListOf<ControlFrame>()
        var vertical = actorTop + actorRowHeight
        var sequenceIndex = 1.0
        var sequenceStep = 1.0
        var sequenceVisible = false

        db.getMessages().forEachIndexed { messageIndex, message ->
            when (message.type) {
                SequenceLineType.AUTONUMBER -> {
                    message.sequenceStart?.let { sequenceIndex = it }
                    message.sequenceStep?.let { sequenceStep = it }
                    sequenceVisible = message.sequenceVisible == true
                }
                SequenceLineType.ACTIVE_START -> {
                    val actor = message.from ?: return@forEachIndexed
                    activationStarts.getOrPut(actor, ::mutableListOf).add(vertical + 2f)
                    touchFrames(frameStack, actorIndex[actor])
                }
                SequenceLineType.ACTIVE_END -> {
                    val actor = message.from ?: return@forEachIndexed
                    val starts = activationStarts[actor].orEmpty()
                    val start = starts.lastOrNull() ?: return@forEachIndexed
                    activationStarts[actor]?.removeAt(starts.lastIndex)
                    val layoutActor = actors.getOrNull(actorIndex[actor] ?: -1)
                        ?: return@forEachIndexed
                    val depth = starts.size - 1
                    foregroundElements += activation(
                        id = "activation-${message.id}",
                        actor = layoutActor,
                        startY = start,
                        endY = max(vertical, start + MIN_ACTIVATION_HEIGHT),
                        depth = depth,
                        context = context,
                    )
                    touchFrames(frameStack, actorIndex[actor])
                }
                SequenceLineType.CENTRAL_CONNECTION,
                SequenceLineType.CENTRAL_CONNECTION_REVERSE,
                -> Unit
                SequenceLineType.NOTE -> {
                    val note = layoutNote(message, actors, actorIndex, vertical, context)
                    vertical = note.bottom
                    foregroundElements += note.elements
                    touchFrames(frameStack, actorIndex[message.from])
                    touchFrames(frameStack, actorIndex[message.to])
                    frameStack.forEach { frame ->
                        frame.includeLoopBounds(note.left, note.right)
                    }
                }
                in CONTROL_START_TYPES -> {
                    val label = controlLabel(message.type)
                    val text = decode(message.message)
                    val titleLines = controlTitleLines(
                        text = "[$text]",
                        maxWidth = controlWrapWidths?.get(message.id),
                        context = context,
                    )
                    val titleHeight = controlTitleHeight(titleLines, context)
                    val frame = ControlFrame(
                        id = message.id,
                        type = message.type,
                        label = label,
                        titleLines = titleLines,
                        startY = vertical + BOX_MARGIN,
                        titleHeight = titleHeight,
                        depth = frameStack.size,
                        fill = if (message.type == SequenceLineType.RECT_START) {
                            CssColorParser.parse(message.message)
                                ?: SceneColor(0x80808080)
                        } else {
                            TRANSPARENT
                        },
                    )
                    frameStack += frame
                    vertical = frame.startY + BOX_MARGIN + frame.titleHeight
                }
                in CONTROL_SECTION_TYPES -> {
                    val frame = frameStack.lastOrNull()
                    if (frame != null) {
                        val titleLines = controlTitleLines(
                            text = "[${decode(message.message)}]",
                            maxWidth = controlWrapWidths?.get(message.id),
                            context = context,
                        )
                        val titleHeight = controlTitleHeight(titleLines, context)
                        frame.sections += FrameSection(
                            id = message.id,
                            y = vertical + BOX_MARGIN,
                            titleLines = titleLines,
                            titleHeight = titleHeight,
                        )
                        vertical += BOX_MARGIN + titleHeight
                    } else {
                        val metrics = measure(
                            context,
                            decode(message.message),
                            MESSAGE_FONT_SIZE,
                            MAX_TEXT_WIDTH,
                        )
                        vertical += BOX_MARGIN + max(metrics.height, LABEL_BOX_HEIGHT)
                    }
                }
                in CONTROL_END_TYPES -> {
                    val frame = frameStack.removeLastOrNull()
                    if (frame != null) {
                        frame.endY = vertical + BOX_MARGIN
                        completedFrames += frame
                        vertical = frame.endY
                        frameStack.forEach { parent ->
                            parent.include(frame.leftIndex)
                            parent.include(frame.rightIndex)
                        }
                    }
                }
                in MESSAGE_TYPES -> {
                    val from = actors.getOrNull(actorIndex[message.from] ?: -1)
                        ?: return@forEachIndexed
                    val to = actors.getOrNull(actorIndex[message.to] ?: -1)
                        ?: return@forEachIndexed
                    val text = decode(message.message)
                    val metrics = measure(
                        context,
                        text,
                        MESSAGE_FONT_SIZE,
                        if (message.wrap) {
                            max(abs(from.centerX - to.centerX), ACTOR_WIDTH)
                        } else {
                            MAX_TEXT_WIDTH
                        },
                    )
                    val messageTop = vertical + MESSAGE_TOP_PADDING
                    val lineY = messageTop + metrics.height + MESSAGE_LINE_GAP
                    val activeFrom = activationStarts[from.source.id].orEmpty().size
                    val activeTo = activationStarts[to.source.id].orEmpty().size
                    val created = db.getCreatedActors()[to.source.id] == messageIndex
                    val destroyedFrom = db.getDestroyedActors()[from.source.id] == messageIndex
                    val destroyedTo = db.getDestroyedActors()[to.source.id] == messageIndex
                    if (created) {
                        actorStart[to.source.id] = lineY - actorRowHeight / 2f
                    }
                    if (destroyedFrom) {
                        actorEnd[from.source.id] = lineY - actorRowHeight / 2f
                    }
                    if (destroyedTo) {
                        actorEnd[to.source.id] = lineY - actorRowHeight / 2f
                    }
                    val sequenceNumberX = if (sequenceVisible) {
                        sequenceNumberX(
                            message = message,
                            from = from,
                            to = to,
                            fromActivationDepth = activeFrom,
                            toActivationDepth = activeTo,
                        )
                    } else {
                        null
                    }
                    val path = messagePath(
                        message = message,
                        from = from,
                        to = to,
                        lineY = lineY,
                        fromActivationDepth = activeFrom,
                        toActivationDepth = activeTo,
                        created = created,
                        destroyedFrom = destroyedFrom,
                        destroyedTo = destroyedTo,
                        sequenceNumberVisible = sequenceVisible,
                        context = context,
                    )
                    val selfMessage = from.source.id == to.source.id
                    val textBounds = messageTextBounds(
                        from = from,
                        to = to,
                        top = messageTop,
                        width = metrics.width,
                        height = metrics.height,
                        self = selfMessage,
                    )
                    pathElements += path
                    foregroundElements += SceneText(
                        text = text,
                        bounds = textBounds,
                        color = context.theme.nodeText,
                        fontSize = MESSAGE_FONT_SIZE,
                        fontFamily = context.theme.fontFamily,
                        weight = SceneTextWeight.Normal,
                        zIndex = 20,
                        softWrap = message.wrap,
                    )
                    if (sequenceVisible) {
                        foregroundElements += sequenceNumber(
                            value = formatSequenceNumber(sequenceIndex),
                            x = sequenceNumberX ?: path.points.first().x,
                            y = lineY,
                            context = context,
                        )
                    }
                    foregroundElements += centralConnectionCircles(
                        message = message,
                        from = from,
                        to = to,
                        lineY = lineY,
                        context = context,
                    )
                    touchFrames(frameStack, actorIndex[from.source.id])
                    touchFrames(frameStack, actorIndex[to.source.id])
                    if (selfMessage) {
                        val selfMessageWidth = max(
                            if (message.wrap) 0f else metrics.width + 2f * WRAP_PADDING,
                            from.width,
                        )
                        frameStack.forEach { frame ->
                            frame.includeLoopBounds(
                                left = from.centerX - selfMessageWidth / 2f,
                                right = from.centerX + selfMessageWidth / 2f,
                            )
                        }
                        touchFrameBounds(
                            frames = frameStack,
                            left = min(
                                from.centerX - selfMessageWidth / 2f,
                                path.points.minOf(ScenePoint::x),
                            ),
                            right = max(
                                from.centerX + selfMessageWidth / 2f,
                                path.points.maxOf(ScenePoint::x),
                            ),
                        )
                    } else {
                        val messageModelWidth = max(
                            max(
                                if (message.wrap) 0f else metrics.width + 2f * WRAP_PADDING,
                                abs(path.points.first().x - path.points.last().x) +
                                    2f * WRAP_PADDING,
                            ),
                            ACTOR_WIDTH,
                        )
                        frameStack.forEach { frame ->
                            // Mermaid's loop preflight includes both horizontal frame margins
                            // around the message model before applying label-box subtraction.
                            frame.includeLoopWidth(messageModelWidth + 3f * BOX_MARGIN)
                        }
                        touchFrameBounds(
                            frames = frameStack,
                            left = min(textBounds.left, path.points.minOf(ScenePoint::x)),
                            right = max(textBounds.right, path.points.maxOf(ScenePoint::x)),
                        )
                    }
                    vertical = lineY + if (from.source.id == to.source.id) {
                        SELF_MESSAGE_HEIGHT
                    } else {
                        0f
                    }
                    if (created || destroyedFrom || destroyedTo) {
                        vertical += actorRowHeight / 2f
                    }
                    sequenceIndex = ((sequenceIndex + sequenceStep) * 100.0)
                        .toInt()
                        .div(100.0)
                }
            }
        }

        while (frameStack.isNotEmpty()) {
            val frame = frameStack.removeLast()
            frame.endY = vertical + BOX_MARGIN
            completedFrames += frame
            vertical = frame.endY
        }
        if (controlWrapWidths == null && completedFrames.isNotEmpty()) {
            val measuredWidths = buildMap {
                completedFrames.forEach { frame ->
                    put(frame.id, frame.loopWidth)
                    frame.sections.forEach { section ->
                        put(section.id, frame.loopWidth)
                    }
                }
            }
            return buildScene(db, context, measuredWidths)
        }
        activationStarts.forEach { (actorId, starts) ->
            val actor = actors.getOrNull(actorIndex[actorId] ?: -1)
                ?: return@forEach
            starts.forEachIndexed { depth, start ->
                foregroundElements += activation(
                    id = "activation-open-$actorId-$depth",
                    actor = actor,
                    startY = start,
                    endY = max(vertical, start + MIN_ACTIVATION_HEIGHT),
                    depth = depth,
                    context = context,
                )
            }
        }

        val footerTop = vertical + BOX_MARGIN * 2f
        actors.forEach { actor ->
            val id = actor.source.id
            val start = actorStart[id].takeUnless { it == null || it.isNaN() }
            val end = actorEnd[id] ?: footerTop
            if (start != null) {
                pathElements += straightPath(
                    id = "lifeline-$id",
                    from = ScenePoint(actor.centerX, start + actor.height),
                    to = ScenePoint(actor.centerX, end),
                    color = context.theme.nodeText,
                    width = 0.5f,
                    strokePattern = SceneStrokePattern.Solid,
                    look = context.options.look,
                    zIndex = 2,
                )
                foregroundElements += actorElements(
                    actor = actor,
                    top = start,
                    suffix = "top",
                    context = context,
                )
            }
            if (id !in db.getDestroyedActors()) {
                foregroundElements += actorElements(
                    actor = actor,
                    top = footerTop,
                    suffix = "bottom",
                    context = context,
                )
            } else {
                val destroyedTop = actorEnd[id]
                if (destroyedTop != null) {
                    foregroundElements += actorElements(
                        actor = actor,
                        top = destroyedTop,
                        suffix = "destroyed",
                        context = context,
                    )
                }
            }
        }
        completedFrames.asReversed().forEach { frame ->
            backgroundElements += controlFrameElements(
                frame = frame,
                actors = actors,
                context = context,
            )
        }
        db.getBoxes().forEach { box ->
            val members = box.actorKeys.mapNotNull { key ->
                actors.getOrNull(actorIndex[key] ?: -1)
            }
            if (members.isNotEmpty()) {
                backgroundElements += boxElements(
                    id = box.id,
                    title = decode(box.name.orEmpty()),
                    fill = CssColorParser.parse(box.fill) ?: TRANSPARENT,
                    left = members.minOf { it.left } - BOX_MARGIN * 2f,
                    right = members.maxOf { it.left + it.width } + BOX_MARGIN * 2f,
                    top = actorTop - BOX_MARGIN - boxTitleHeight,
                    bottom = footerTop + actorRowHeight + BOX_MARGIN,
                    context = context,
                )
            }
        }
        if (titleMetrics != null) {
            foregroundElements += SceneText(
                text = decode(db.diagramTitle.orEmpty()),
                bounds = SceneRect(
                    left = 0f,
                    top = DIAGRAM_MARGIN_Y,
                    right = contentRight,
                    bottom = DIAGRAM_MARGIN_Y + titleMetrics.height,
                ),
                color = context.theme.nodeText,
                fontSize = MESSAGE_FONT_SIZE,
                fontFamily = context.theme.fontFamily,
                weight = SceneTextWeight.Normal,
                zIndex = 30,
            )
        }

        val sceneHeight = footerTop + actorRowHeight + DIAGRAM_MARGIN_Y
        return MermaidScene(
            width = contentRight + DIAGRAM_MARGIN_X,
            height = sceneHeight,
            background = context.theme.background,
            elements = (backgroundElements + pathElements + foregroundElements)
                .sortedBy(SceneElement::zIndex),
            title = db.diagramTitle,
            accessibilityTitle = db.accessibilityTitle,
            accessibilityDescription = db.accessibilityDescription,
        )
    }

    private fun calculateGaps(
        actors: List<LayoutActor>,
        actorIndex: Map<String, Int>,
        messages: List<SequenceMessage>,
        context: MermaidRenderContext,
    ): MutableList<Float> {
        val gaps = MutableList(max(actors.size - 1, 0)) { ACTOR_MARGIN }
        messages.forEach { message ->
            val fromIndex = actorIndex[message.from] ?: return@forEach
            val toIndex = actorIndex[message.to] ?: return@forEach
            if (fromIndex == toIndex) {
                return@forEach
            }
            val leftIndex = min(fromIndex, toIndex)
            val rightIndex = max(fromIndex, toIndex)
            val metrics = measure(
                context,
                decode(message.message),
                if (message.type == SequenceLineType.NOTE) NOTE_FONT_SIZE else MESSAGE_FONT_SIZE,
                if (message.wrap) {
                    ACTOR_WIDTH - 2f * WRAP_PADDING
                } else {
                    MAX_TEXT_WIDTH
                },
            )
            val required = metrics.width + 2f * WRAP_PADDING
            val actorWidths = (
                actors[leftIndex].width / 2f +
                    actors[rightIndex].width / 2f
                )
            val requiredGap = max(
                ACTOR_MARGIN,
                (required - actorWidths) / (rightIndex - leftIndex),
            )
            for (index in leftIndex until rightIndex) {
                gaps[index] = max(gaps[index], requiredGap)
            }
        }
        return gaps
    }

    private fun externalNoteReserve(
        db: SequenceDb,
        actors: List<LayoutActor>,
        actorIndex: Map<String, Int>,
        context: MermaidRenderContext,
        placement: Int,
    ): Float {
        val edgeIndex = if (placement == SequencePlacement.LEFT_OF) 0 else actorIndex.size - 1
        return db.getMessages()
            .filter {
                it.type == SequenceLineType.NOTE &&
                    it.placement == placement &&
                    actorIndex[it.from] == edgeIndex
            }
            .mapNotNull { message ->
                val actor = actors.getOrNull(actorIndex[message.from] ?: -1)
                    ?: return@mapNotNull null
                val width = sideNoteWidth(message, actor, context)
                max(0f, width - (actor.width - ACTOR_MARGIN) / 2f)
            }
            .maxOrNull()
            ?: 0f
    }

    private fun sideNoteWidth(
        message: SequenceMessage,
        actor: LayoutActor,
        context: MermaidRenderContext,
    ): Float {
        if (message.wrap) {
            return max(ACTOR_WIDTH, actor.width)
        }
        val metrics = measure(
            context,
            decode(message.message),
            NOTE_FONT_SIZE,
            MAX_TEXT_WIDTH,
        )
        return max(max(ACTOR_WIDTH, actor.width), metrics.width + 2f * NOTE_MARGIN)
    }

    private fun layoutNote(
        message: SequenceMessage,
        actors: List<LayoutActor>,
        actorIndex: Map<String, Int>,
        top: Float,
        context: MermaidRenderContext,
    ): NoteLayout {
        val from = actors.getOrNull(actorIndex[message.from] ?: -1)
            ?: return NoteLayout(
                bottom = top,
                left = 0f,
                right = 0f,
                elements = emptyList(),
            )
        val to = actors.getOrNull(actorIndex[message.to] ?: -1) ?: from
        val text = decode(message.message)
        val noteWidth = when {
            message.placement == SequencePlacement.OVER &&
                from.source.id != to.source.id ->
                abs(from.centerX - to.centerX) + ACTOR_MARGIN
            message.wrap -> max(ACTOR_WIDTH, from.width)
            else -> {
                val metrics = measure(context, text, NOTE_FONT_SIZE, MAX_TEXT_WIDTH)
                max(max(ACTOR_WIDTH, from.width), metrics.width + 2f * NOTE_MARGIN)
            }
        }
        val metrics = measure(
            context,
            text,
            NOTE_FONT_SIZE,
            if (message.wrap) {
                max(noteWidth - 2f * WRAP_PADDING, 1f)
            } else {
                MAX_TEXT_WIDTH
            },
        )
        val noteHeight = metrics.height + 2f * NOTE_MARGIN
        val noteTop = top + BOX_MARGIN
        val left = when (message.placement) {
            SequencePlacement.LEFT_OF ->
                from.left - noteWidth + (from.width - ACTOR_MARGIN) / 2f
            SequencePlacement.RIGHT_OF ->
                from.left + (from.width + ACTOR_MARGIN) / 2f
            else -> if (from.source.id == to.source.id) {
                from.centerX - noteWidth / 2f
            } else {
                min(from.centerX, to.centerX) - ACTOR_MARGIN / 2f
            }
        }
        val bounds = SceneRect(left, noteTop, left + noteWidth, noteTop + noteHeight)
        return NoteLayout(
            bottom = bounds.bottom,
            left = bounds.left,
            right = bounds.right,
            elements = listOf(
                SceneShape(
                    id = "note-${message.id}",
                    bounds = bounds,
                    kind = SceneShapeKind.Rectangle,
                    geometry = rectangleGeometry(bounds.width, bounds.height),
                    fill = context.theme.noteFill,
                    stroke = context.theme.noteStroke,
                    strokeWidth = 1f,
                    zIndex = 12,
                ),
                SceneText(
                    text = text,
                    bounds = SceneRect(
                        left = bounds.left + NOTE_MARGIN,
                        top = bounds.top,
                        right = bounds.right - NOTE_MARGIN,
                        bottom = bounds.bottom,
                    ),
                    color = context.theme.noteText,
                    fontSize = NOTE_FONT_SIZE,
                    fontFamily = context.theme.fontFamily,
                    weight = SceneTextWeight.Normal,
                    zIndex = 20,
                ),
            ),
        )
    }

    private fun messagePath(
        message: SequenceMessage,
        from: LayoutActor,
        to: LayoutActor,
        lineY: Float,
        fromActivationDepth: Int,
        toActivationDepth: Int,
        created: Boolean,
        destroyedFrom: Boolean,
        destroyedTo: Boolean,
        sequenceNumberVisible: Boolean,
        context: MermaidRenderContext,
    ): ScenePath {
        val toRight = from.centerX <= to.centerX
        val fromBounds = activationBounds(from, fromActivationDepth)
        val toBounds = activationBounds(to, toActivationDepth)
        var startX = if (toRight) fromBounds.second else fromBounds.first
        var endX = if (toRight) toBounds.first else toBounds.second
        val towardSource: (Float) -> Float = { value -> if (toRight) -value else value }

        if (context.options.look == "neo") {
            if (message.type != SequenceLineType.SOLID_OPEN) {
                endX += towardSource(MESSAGE_ENDPOINT_OFFSET)
            }
            if (message.type in BIDIRECTIONAL_MESSAGE_TYPES) {
                startX -= towardSource(MESSAGE_ENDPOINT_OFFSET)
            }
        }
        if (message.centralConnection == SequenceLineType.CENTRAL_CONNECTION_REVERSE ||
            message.centralConnection == SequenceLineType.CENTRAL_CONNECTION_DUAL
        ) {
            startX += CENTRAL_CONNECTION_BASE_OFFSET +
                if (message.type in BIDIRECTIONAL_MESSAGE_TYPES && !toRight) {
                    -CENTRAL_CONNECTION_BIDIRECTIONAL_OFFSET
                } else {
                    0f
                }
        }
        if (from.source.id != to.source.id) {
            if (message.activate && toActivationDepth <= 0) {
                endX += towardSource(ACTIVATION_WIDTH / 2f - 1f)
            }
            if (message.type !in MESSAGE_END_OFFSET_EXCLUDED_TYPES) {
                endX += towardSource(MESSAGE_ENDPOINT_OFFSET)
            }
            if (message.type in MESSAGE_START_OFFSET_TYPES) {
                startX -= towardSource(MESSAGE_ENDPOINT_OFFSET)
            }
        }
        if (created) {
            endX += towardSource(lifecycleActorHalfWidth(to) + MESSAGE_ENDPOINT_OFFSET)
        }
        if (destroyedFrom) {
            startX -= towardSource(lifecycleActorHalfWidth(from))
        }
        if (destroyedTo) {
            endX += towardSource(lifecycleActorHalfWidth(to) + MESSAGE_ENDPOINT_OFFSET)
        }
        if (sequenceNumberVisible) {
            val hasCentralConnection = message.hasCentralConnection()
            when {
                message.type in BIDIRECTIONAL_MESSAGE_TYPES -> {
                    if (startX < endX) {
                        startX += SEQUENCE_NUMBER_RADIUS * 2f
                    } else {
                        startX -= SEQUENCE_NUMBER_RADIUS
                        if (hasCentralConnection) {
                            startX -= SEQUENCE_NUMBER_CENTRAL_START_OFFSET
                        }
                        if (message.hasReverseCentralConnection()) {
                            startX -= SEQUENCE_NUMBER_REVERSE_CENTRAL_START_OFFSET
                        }
                    }
                }
                message.type in REVERSE_ARROW_MESSAGE_TYPES -> {
                    if (endX > startX) {
                        endX -= SEQUENCE_NUMBER_RADIUS * 2f
                    } else {
                        endX -= SEQUENCE_NUMBER_RADIUS
                        if (message.hasReverseCentralConnection()) {
                            startX -= SEQUENCE_NUMBER_REVERSE_CENTRAL_START_OFFSET
                        }
                    }
                    if (hasCentralConnection) {
                        endX += SEQUENCE_NUMBER_CENTRAL_END_OFFSET
                    }
                }
                else -> startX += SEQUENCE_NUMBER_RADIUS
            }
        }
        val marker = markers(message.type)
        val commands: List<ScenePathCommand>
        val points: List<ScenePoint>
        if (from.source.id == to.source.id) {
            val loopWidth = max(SELF_MESSAGE_WIDTH, from.width / 2f)
            val start = ScenePoint(startX, lineY)
            val firstControl = ScenePoint(startX + loopWidth, lineY - 10f)
            val secondControl = ScenePoint(startX + loopWidth, lineY + 30f)
            val end = ScenePoint(startX, lineY + 20f)
            commands = listOf(
                ScenePathCommand.MoveTo(start),
                ScenePathCommand.CubicTo(firstControl, secondControl, end),
            )
            points = listOf(start, firstControl, secondControl, end)
        } else {
            val start = ScenePoint(startX, lineY)
            val end = ScenePoint(endX, lineY)
            commands = listOf(
                ScenePathCommand.MoveTo(start),
                ScenePathCommand.LineTo(end),
            )
            points = listOf(start, end)
        }
        return ScenePath(
            id = "message-${message.id}",
            points = points,
            commands = commands,
            color = context.theme.nodeText,
            strokeWidth = MESSAGE_STROKE_WIDTH,
            strokePattern = if (message.type in DOTTED_MESSAGE_TYPES) {
                SceneStrokePattern.Dashed
            } else {
                SceneStrokePattern.Solid
            },
            arrowStart = marker.first,
            arrowEnd = marker.second,
            curve = "linear",
            look = "classic",
            animated = false,
            dashIntervals = if (message.type in DOTTED_MESSAGE_TYPES) {
                listOf(3f, 3f)
            } else {
                emptyList()
            },
            zIndex = 7,
        )
    }

    private fun activationBounds(
        actor: LayoutActor,
        depth: Int,
    ): Pair<Float, Float> {
        if (depth <= 0) {
            return actor.centerX - 1f to actor.centerX + 1f
        }
        val center = actor.centerX + activationOffset(depth)
        return center - ACTIVATION_WIDTH / 2f to center + ACTIVATION_WIDTH / 2f
    }

    private fun lifecycleActorHalfWidth(actor: LayoutActor): Float =
        if (actor.source.type in NARROW_LIFECYCLE_ACTOR_TYPES) {
            ACTOR_TYPE_WIDTH / 2f
        } else {
            actor.width / 2f
        }

    private fun sequenceNumberX(
        message: SequenceMessage,
        from: LayoutActor,
        to: LayoutActor,
        fromActivationDepth: Int,
        toActivationDepth: Int,
    ): Float {
        val fromBounds = activationBounds(from, fromActivationDepth)
        val toBounds = activationBounds(to, toActivationDepth)
        val left = min(fromBounds.first, toBounds.first)
        val right = max(fromBounds.second, toBounds.second)
        val isLeftToRight = from.centerX <= to.centerX
        return when {
            from.source.id == to.source.id -> left + 1f
            message.type in REVERSE_ARROW_MESSAGE_TYPES ->
                if (isLeftToRight) right - 1f else left + 1f
            isLeftToRight -> left + 1f
            else -> right - 1f
        }
    }

    private fun SequenceMessage.hasCentralConnection(): Boolean =
        centralConnection == SequenceLineType.CENTRAL_CONNECTION ||
            hasReverseCentralConnection()

    private fun SequenceMessage.hasReverseCentralConnection(): Boolean =
        centralConnection == SequenceLineType.CENTRAL_CONNECTION_REVERSE ||
            centralConnection == SequenceLineType.CENTRAL_CONNECTION_DUAL

    private fun messageTextBounds(
        from: LayoutActor,
        to: LayoutActor,
        top: Float,
        width: Float,
        height: Float,
        self: Boolean,
    ): SceneRect = if (self) {
        SceneRect(
            left = from.centerX - width / 2f,
            top = top,
            right = from.centerX + width / 2f,
            bottom = top + height,
        )
    } else {
        SceneRect(
            left = min(from.centerX, to.centerX),
            top = top,
            right = max(from.centerX, to.centerX),
            bottom = top + height,
        )
    }

    private fun activation(
        id: String,
        actor: LayoutActor,
        startY: Float,
        endY: Float,
        depth: Int,
        context: MermaidRenderContext,
    ): SceneShape {
        val left = actor.centerX + activationOffset(depth + 1) - ACTIVATION_WIDTH / 2f
        return SceneShape(
            id = id,
            bounds = SceneRect(
                left = left,
                top = startY,
                right = left + ACTIVATION_WIDTH,
                bottom = endY,
            ),
            kind = SceneShapeKind.Rectangle,
            fill = context.theme.colorFill(actor.colorIndex),
            stroke = context.theme.colorStroke(actor.colorIndex),
            strokeWidth = 1f,
            shadow = if (context.options.look == "neo") context.theme.dropShadow else null,
            zIndex = 9,
        )
    }

    private fun actorElements(
        actor: LayoutActor,
        top: Float,
        suffix: String,
        context: MermaidRenderContext,
    ): List<SceneElement> {
        val bounds = SceneRect(
            left = actor.left,
            top = top,
            right = actor.left + actor.width,
            bottom = top + actor.height,
        )
        val fill = context.theme.colorFill(actor.colorIndex)
        val stroke = context.theme.colorStroke(actor.colorIndex)
        val shadow = if (context.options.look == "neo") context.theme.dropShadow else null
        val type = actor.source.type
        val shape = when (type) {
            "actor" -> SceneShape(
                id = "actor-${actor.source.id}-$suffix",
                bounds = bounds,
                kind = SceneShapeKind.Person,
                geometry = actorGeometry(actor.width, actor.height, actor.textMetrics.height),
                fill = TRANSPARENT,
                stroke = stroke,
                strokeWidth = 2f,
                zIndex = 10,
            )
            "boundary" -> glyphActorShape(
                actor,
                bounds,
                suffix,
                SceneShapeKind.Circle,
                boundaryGeometry(actor.height, actor.textMetrics.height),
                fill,
                stroke,
                shadow,
            )
            "control" -> glyphActorShape(
                actor,
                bounds,
                suffix,
                SceneShapeKind.Circle,
                controlGeometry(actor.height, actor.textMetrics.height),
                fill,
                stroke,
                shadow,
            )
            "entity" -> glyphActorShape(
                actor,
                bounds,
                suffix,
                SceneShapeKind.Circle,
                entityGeometry(actor.height, actor.textMetrics.height),
                fill,
                stroke,
                shadow,
            )
            "database" -> glyphActorShape(
                actor,
                bounds,
                suffix,
                SceneShapeKind.Cylinder,
                databaseGeometry(actor.width, actor.height, actor.textMetrics.height),
                fill,
                stroke,
                shadow,
            )
            "collections" -> SceneShape(
                id = "actor-${actor.source.id}-$suffix",
                bounds = bounds,
                kind = SceneShapeKind.Rectangle,
                geometry = collectionsGeometry(actor.width, actor.height),
                fill = fill,
                stroke = stroke,
                strokeWidth = context.theme.strokeWidth,
                shadow = shadow,
                zIndex = 10,
            )
            "queue" -> SceneShape(
                id = "actor-${actor.source.id}-$suffix",
                bounds = bounds,
                kind = SceneShapeKind.Rectangle,
                geometry = queueGeometry(actor.width, actor.height),
                fill = fill,
                stroke = stroke,
                strokeWidth = context.theme.strokeWidth,
                shadow = shadow,
                zIndex = 10,
            )
            else -> SceneShape(
                id = "actor-${actor.source.id}-$suffix",
                bounds = bounds,
                kind = SceneShapeKind.RoundedRectangle,
                fill = fill,
                stroke = stroke,
                strokeWidth = context.theme.strokeWidth,
                cornerRadius = if (context.options.look == "neo") 6f else 3f,
                shadow = shadow,
                zIndex = 10,
            )
        }
        val layoutBounds = when {
            type in GLYPH_ACTOR_TYPES -> SceneRect(
                left = actor.left,
                top = top + actor.height - actor.textMetrics.height - LABEL_LIFELINE_GAP,
                right = actor.left + actor.width,
                bottom = top + actor.height - LABEL_LIFELINE_GAP,
            )
            type == "collections" -> SceneRect(
                left = actor.left - COLLECTION_OFFSET,
                top = top + COLLECTION_OFFSET,
                right = actor.left + actor.width - COLLECTION_OFFSET,
                bottom = top + actor.height,
            )
            else -> bounds
        }
        val textBounds = if (actor.source.wrap) {
            layoutBounds
        } else {
            SceneRect(
                left = layoutBounds.center.x - actor.textMetrics.width / 2f,
                top = layoutBounds.center.y - actor.textMetrics.height / 2f,
                right = layoutBounds.center.x + actor.textMetrics.width / 2f,
                bottom = layoutBounds.center.y + actor.textMetrics.height / 2f,
            )
        }
        return listOf(
            shape,
            SceneText(
                text = actor.text,
                bounds = textBounds,
                color = context.theme.nodeText,
                fontSize = ACTOR_FONT_SIZE,
                fontFamily = context.theme.fontFamily,
                weight = SceneTextWeight.Normal,
                zIndex = 20,
            ),
        )
    }

    private fun glyphActorShape(
        actor: LayoutActor,
        bounds: SceneRect,
        suffix: String,
        kind: SceneShapeKind,
        geometry: SceneShapeGeometry,
        fill: SceneColor,
        stroke: SceneColor,
        shadow: SceneShadow?,
    ): SceneShape = SceneShape(
        id = "actor-${actor.source.id}-$suffix",
        bounds = bounds,
        kind = kind,
        geometry = geometry,
        fill = fill,
        stroke = stroke,
        strokeWidth = 2f,
        shadow = shadow,
        zIndex = 10,
    )

    private fun controlFrameElements(
        frame: ControlFrame,
        actors: List<LayoutActor>,
        context: MermaidRenderContext,
    ): List<SceneElement> {
        val leftActor = actors.getOrNull(frame.leftIndex.coerceAtLeast(0))
        val rightActor = actors.getOrNull(frame.rightIndex.coerceAtLeast(0))
        val baseLeft = (leftActor?.centerX ?: actors.firstOrNull()?.centerX ?: DIAGRAM_MARGIN_X) -
            ACTOR_MARGIN / 2f
        val baseRight = (rightActor?.centerX ?: actors.lastOrNull()?.centerX ?: ACTOR_WIDTH) +
            ACTOR_MARGIN / 2f
        val nestedInset = frame.depth * BOX_MARGIN
        val indexedLeft = baseLeft + nestedInset
        val indexedRight = baseRight - nestedInset
        val left = if (frame.contentLeft.isFinite()) {
            min(indexedLeft, frame.contentLeft)
        } else {
            indexedLeft
        }
        val contentRight = if (frame.contentRight.isFinite()) {
            max(indexedRight, frame.contentRight)
        } else {
            indexedRight
        }
        val right = max(contentRight, left + LABEL_BOX_WIDTH)
        val bounds = SceneRect(left, frame.startY, max(right, left + LABEL_BOX_WIDTH), frame.endY)
        if (frame.type == SequenceLineType.RECT_START) {
            return listOf(
                SceneShape(
                    id = "sequence-rect-${frame.id}",
                    bounds = bounds,
                    kind = SceneShapeKind.Rectangle,
                    fill = frame.fill,
                    stroke = TRANSPARENT,
                    strokeWidth = 0f,
                    zIndex = 1,
                ),
            )
        }
        val result = mutableListOf<SceneElement>()
        result += SceneShape(
            id = "control-${frame.id}",
            bounds = bounds,
            kind = SceneShapeKind.Rectangle,
            geometry = rectangleGeometry(bounds.width, bounds.height),
            fill = TRANSPARENT,
            stroke = context.theme.nodeStroke,
            strokeWidth = 2f,
            strokePattern = SceneStrokePattern.Dotted,
            dashIntervals = listOf(2f, 2f),
            zIndex = 4,
        )
        val labelBounds = SceneRect(
            left = bounds.left,
            top = bounds.top,
            right = min(bounds.right, bounds.left + LABEL_BOX_WIDTH),
            bottom = bounds.top + LABEL_BOX_HEIGHT +
                if (context.options.look == "neo") 15f else 0f,
        )
        result += SceneShape(
            id = "control-label-${frame.id}",
            bounds = labelBounds,
            kind = SceneShapeKind.TaggedRectangle,
            geometry = labelGeometry(labelBounds.width, labelBounds.height),
            fill = context.theme.nodeFill,
            stroke = context.theme.nodeStroke,
            strokeWidth = 1f,
            shadow = if (context.options.look == "neo") context.theme.dropShadow else null,
            zIndex = 5,
        )
        val labelTextWidth = max(
            labelBounds.width,
            measure(
                context = context,
                text = frame.label,
                fontSize = MESSAGE_FONT_SIZE,
                maxWidth = MAX_TEXT_WIDTH,
            ).width,
        )
        result += SceneText(
            text = frame.label,
            bounds = SceneRect(
                left = labelBounds.center.x - labelTextWidth / 2f,
                top = labelBounds.top,
                right = labelBounds.center.x + labelTextWidth / 2f,
                bottom = labelBounds.bottom,
            ),
            color = context.theme.nodeText,
            fontSize = MESSAGE_FONT_SIZE,
            fontFamily = context.theme.fontFamily,
            weight = SceneTextWeight.Normal,
            zIndex = 20,
        )
        if (frame.titleLines.any(String::isNotBlank)) {
            result += controlTitleElements(
                lines = frame.titleLines,
                left = labelBounds.right,
                right = bounds.right,
                top = frame.startY + BOX_MARGIN,
                context = context,
            )
        }
        frame.sections.forEachIndexed { index, section ->
            result += straightPath(
                id = "control-section-${frame.id}-$index",
                from = ScenePoint(bounds.left, section.y),
                to = ScenePoint(bounds.right, section.y),
                color = context.theme.nodeStroke,
                width = 1f,
                strokePattern = SceneStrokePattern.Dashed,
                look = context.options.look,
                zIndex = 4,
            )
            if (section.titleLines.any(String::isNotBlank)) {
                result += controlTitleElements(
                    lines = section.titleLines,
                    left = bounds.left + LABEL_BOX_WIDTH,
                    right = bounds.right,
                    top = section.y + BOX_MARGIN,
                    context = context,
                )
            }
        }
        return result
    }

    private fun controlTitleElements(
        lines: List<String>,
        left: Float,
        right: Float,
        top: Float,
        context: MermaidRenderContext,
    ): List<SceneText> {
        val centerX = (left + right) / 2f
        var cursorY = top
        return lines.map { line ->
            val metrics = measure(context, line, MESSAGE_FONT_SIZE, MAX_TEXT_WIDTH)
            SceneText(
                text = line,
                bounds = SceneRect(
                    left = centerX - metrics.width / 2f,
                    top = cursorY,
                    right = centerX + metrics.width / 2f,
                    bottom = cursorY + metrics.height,
                ),
                color = context.theme.nodeText,
                fontSize = MESSAGE_FONT_SIZE,
                fontFamily = context.theme.fontFamily,
                weight = SceneTextWeight.Normal,
                zIndex = 20,
                softWrap = false,
            ).also {
                cursorY += metrics.height
            }
        }
    }

    private fun controlTitleHeight(
        lines: List<String>,
        context: MermaidRenderContext,
    ): Float = max(
        LABEL_BOX_HEIGHT,
        lines.sumOf { line ->
            measure(context, line, MESSAGE_FONT_SIZE, MAX_TEXT_WIDTH).height.toDouble()
        }.toFloat(),
    )

    // Mermaid: src/utils.ts -> wrapLabel
    private fun controlTitleLines(
        text: String,
        maxWidth: Float?,
        context: MermaidRenderContext,
    ): List<String> {
        if (maxWidth == null || text.contains('\n')) {
            return text.split('\n')
        }
        // Mermaid's SVG getBBox preflight is narrower than Compose's glyph measurement.
        // Keep the upstream wrap boundary while rendering with the native font metrics.
        val availableWidth = max(
            (maxWidth - 2f * WRAP_PADDING) * SVG_WRAP_WIDTH_SCALE,
            1f,
        )
        val words = text.split(' ').filter(String::isNotBlank)
        if (words.isEmpty()) {
            return listOf(text)
        }
        val completed = mutableListOf<String>()
        var nextLine = ""
        words.forEachIndexed { index, word ->
            val wordWidth = measure(
                context,
                "$word ",
                MESSAGE_FONT_SIZE,
                MAX_TEXT_WIDTH,
            ).width
            val nextLineWidth = measure(
                context,
                nextLine,
                MESSAGE_FONT_SIZE,
                MAX_TEXT_WIDTH,
            ).width
            if (wordWidth > availableWidth) {
                if (nextLine.isNotEmpty()) {
                    completed += nextLine
                }
                val broken = breakControlTitleWord(word, availableWidth, context)
                completed += broken.dropLast(1)
                nextLine = broken.lastOrNull().orEmpty()
            } else if (nextLineWidth + wordWidth >= availableWidth) {
                if (nextLine.isNotEmpty()) {
                    completed += nextLine
                }
                nextLine = word
            } else {
                nextLine = listOf(nextLine, word)
                    .filter(String::isNotEmpty)
                    .joinToString(" ")
            }
            if (index == words.lastIndex && nextLine.isNotEmpty()) {
                completed += nextLine
            }
        }
        return completed.filter(String::isNotEmpty)
    }

    private fun breakControlTitleWord(
        word: String,
        maxWidth: Float,
        context: MermaidRenderContext,
    ): List<String> {
        val lines = mutableListOf<String>()
        var current = ""
        word.forEachIndexed { index, character ->
            val candidate = current + character
            if (
                measure(context, candidate, MESSAGE_FONT_SIZE, MAX_TEXT_WIDTH).width >= maxWidth &&
                current.isNotEmpty()
            ) {
                lines += "$current-"
                current = character.toString()
            } else {
                current = candidate
            }
            if (index == word.lastIndex && current.isNotEmpty()) {
                lines += current
            }
        }
        return lines.ifEmpty { listOf(word) }
    }

    private fun boxElements(
        id: String,
        title: String,
        fill: SceneColor,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        context: MermaidRenderContext,
    ): List<SceneElement> = buildList {
        add(
            SceneShape(
                id = id,
                bounds = SceneRect(left, top, right, bottom),
                kind = SceneShapeKind.Rectangle,
                fill = fill,
                stroke = BOX_STROKE,
                strokeWidth = 1f,
                zIndex = 0,
            ),
        )
        if (title.isNotBlank()) {
            add(
                SceneText(
                    text = title,
                    bounds = SceneRect(
                        left = left + BOX_MARGIN,
                        top = top,
                        right = right - BOX_MARGIN,
                        bottom = top + ACTOR_FONT_SIZE * 1.4f + BOX_MARGIN,
                    ),
                    color = context.theme.nodeText,
                    fontSize = MESSAGE_FONT_SIZE,
                    fontFamily = context.theme.fontFamily,
                    weight = SceneTextWeight.Normal,
                    zIndex = 20,
                ),
            )
        }
    }

    private fun sequenceNumber(
        value: String,
        x: Float,
        y: Float,
        context: MermaidRenderContext,
    ): List<SceneElement> {
        val bounds = SceneRect(
            x - SEQUENCE_NUMBER_RADIUS,
            y - SEQUENCE_NUMBER_RADIUS,
            x + SEQUENCE_NUMBER_RADIUS,
            y + SEQUENCE_NUMBER_RADIUS,
        )
        val textBounds = SceneRect(
            x - SEQUENCE_NUMBER_TEXT_HALF_WIDTH,
            y - SEQUENCE_NUMBER_RADIUS,
            x + SEQUENCE_NUMBER_TEXT_HALF_WIDTH,
            y + SEQUENCE_NUMBER_RADIUS,
        )
        val fontSize = when {
            value.length > 5 -> 7f
            value.length > 3 -> 9f
            else -> 12f
        }
        return listOf(
            SceneShape(
                id = "sequence-number-circle-$value-$x-$y",
                bounds = bounds,
                kind = SceneShapeKind.Circle,
                geometry = circleGeometry(SEQUENCE_NUMBER_RADIUS),
                fill = context.theme.nodeText,
                stroke = context.theme.nodeText,
                strokeWidth = 1f,
                zIndex = 16,
            ),
            SceneText(
                text = value,
                bounds = textBounds,
                color = SEQUENCE_NUMBER_TEXT,
                fontSize = fontSize,
                fontFamily = context.theme.fontFamily,
                weight = SceneTextWeight.Normal,
                zIndex = 20,
            ),
        )
    }

    private fun centralConnectionCircles(
        message: SequenceMessage,
        from: LayoutActor,
        to: LayoutActor,
        lineY: Float,
        context: MermaidRenderContext,
    ): List<SceneElement> {
        val centers = when (message.centralConnection) {
            SequenceLineType.CENTRAL_CONNECTION -> listOf(to.centerX)
            SequenceLineType.CENTRAL_CONNECTION_REVERSE -> listOf(from.centerX)
            SequenceLineType.CENTRAL_CONNECTION_DUAL -> listOf(from.centerX, to.centerX)
            else -> emptyList()
        }
        return centers.mapIndexed { index, center ->
            SceneShape(
                id = "central-${message.id}-$index",
                bounds = SceneRect(center - 5f, lineY - 5f, center + 5f, lineY + 5f),
                kind = SceneShapeKind.Circle,
                geometry = circleGeometry(5f),
                fill = context.theme.nodeText,
                stroke = context.theme.nodeText,
                strokeWidth = 1.5f,
                zIndex = 16,
            )
        }
    }

    private fun markers(type: Int): Pair<SceneArrowHead, SceneArrowHead> = when (type) {
        SequenceLineType.SOLID,
        SequenceLineType.DOTTED,
        -> SceneArrowHead.None to SceneArrowHead.Triangle
        SequenceLineType.BIDIRECTIONAL_SOLID,
        SequenceLineType.BIDIRECTIONAL_DOTTED,
        -> SceneArrowHead.Triangle to SceneArrowHead.Triangle
        SequenceLineType.SOLID_CROSS,
        SequenceLineType.DOTTED_CROSS,
        -> SceneArrowHead.None to SceneArrowHead.SequenceCross
        SequenceLineType.SOLID_POINT,
        SequenceLineType.DOTTED_POINT,
        -> SceneArrowHead.None to SceneArrowHead.Async
        SequenceLineType.SOLID_TOP,
        SequenceLineType.SOLID_TOP_DOTTED,
        -> SceneArrowHead.None to SceneArrowHead.HalfTriangleTop
        SequenceLineType.SOLID_BOTTOM,
        SequenceLineType.SOLID_BOTTOM_DOTTED,
        -> SceneArrowHead.None to SceneArrowHead.HalfTriangleBottom
        SequenceLineType.STICK_TOP,
        SequenceLineType.STICK_TOP_DOTTED,
        -> SceneArrowHead.None to SceneArrowHead.HalfOpenTop
        SequenceLineType.STICK_BOTTOM,
        SequenceLineType.STICK_BOTTOM_DOTTED,
        -> SceneArrowHead.None to SceneArrowHead.HalfOpenBottom
        SequenceLineType.SOLID_ARROW_TOP_REVERSE,
        SequenceLineType.SOLID_ARROW_TOP_REVERSE_DOTTED,
        -> SceneArrowHead.HalfTriangleBottom to SceneArrowHead.None
        SequenceLineType.SOLID_ARROW_BOTTOM_REVERSE,
        SequenceLineType.SOLID_ARROW_BOTTOM_REVERSE_DOTTED,
        -> SceneArrowHead.HalfTriangleTop to SceneArrowHead.None
        SequenceLineType.STICK_ARROW_TOP_REVERSE,
        SequenceLineType.STICK_ARROW_TOP_REVERSE_DOTTED,
        -> SceneArrowHead.HalfOpenBottom to SceneArrowHead.None
        SequenceLineType.STICK_ARROW_BOTTOM_REVERSE,
        SequenceLineType.STICK_ARROW_BOTTOM_REVERSE_DOTTED,
        -> SceneArrowHead.HalfOpenTop to SceneArrowHead.None
        else -> SceneArrowHead.None to SceneArrowHead.None
    }

    private fun straightPath(
        id: String,
        from: ScenePoint,
        to: ScenePoint,
        color: SceneColor,
        width: Float,
        strokePattern: SceneStrokePattern,
        look: String,
        zIndex: Int,
    ): ScenePath = ScenePath(
        id = id,
        points = listOf(from, to),
        commands = listOf(
            ScenePathCommand.MoveTo(from),
            ScenePathCommand.LineTo(to),
        ),
        color = color,
        strokeWidth = width,
        strokePattern = strokePattern,
        arrowStart = SceneArrowHead.None,
        arrowEnd = SceneArrowHead.None,
        curve = "linear",
        look = look,
        animated = false,
        zIndex = zIndex,
    )

    private fun measure(
        context: MermaidRenderContext,
        text: String,
        fontSize: Float,
        maxWidth: Float,
    ): TextMetrics = context.textMetrics.measure(
        TextMetricsRequest(
            text = text,
            fontSize = fontSize,
            maxWidth = maxWidth,
            lineHeight = 1.2f,
            fontFamily = context.theme.fontFamily,
            weight = SceneTextWeight.Normal,
        ),
    )

    private fun touchFrames(
        frames: List<ControlFrame>,
        index: Int?,
    ) {
        if (index == null) {
            return
        }
        frames.forEach { it.include(index) }
    }

    private fun touchFrameBounds(
        frames: List<ControlFrame>,
        left: Float,
        right: Float,
    ) {
        frames.forEachIndexed { index, frame ->
            val margin = (frames.size - index) * BOX_MARGIN
            frame.includeBounds(left - margin, right + margin)
        }
    }

    private fun activationOffset(depth: Int): Float =
        if (depth <= 0) 0f else (depth - 1) * ACTIVATION_WIDTH / 2f

    private fun decode(source: String): String =
        MermaidHtmlEntityDecoder.decode(source).replace(HTML_BREAK, "\n")

    private fun formatSequenceNumber(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

    private fun rectangleGeometry(width: Float, height: Float): SceneShapeGeometry {
        val points = listOf(
            ScenePoint(-width / 2f, -height / 2f),
            ScenePoint(width / 2f, -height / 2f),
            ScenePoint(width / 2f, height / 2f),
            ScenePoint(-width / 2f, height / 2f),
        )
        return SceneShapeGeometry(
            paths = listOf(SceneShapePath(points)),
            outline = points,
        )
    }

    private fun labelGeometry(width: Float, height: Float): SceneShapeGeometry {
        val left = -width / 2f
        val top = -height / 2f
        val right = width / 2f
        val bottom = height / 2f
        val cut = min(8f, height / 3f)
        val points = listOf(
            ScenePoint(left, top),
            ScenePoint(right, top),
            ScenePoint(right, bottom - cut),
            ScenePoint(right - cut * 1.2f, bottom),
            ScenePoint(left, bottom),
        )
        return SceneShapeGeometry(listOf(SceneShapePath(points)), points)
    }

    private fun actorGeometry(
        width: Float,
        height: Float,
        textHeight: Float,
    ): SceneShapeGeometry {
        val glyphBottom = glyphBottom(height, textHeight)
        val glyphScale = GLYPH_BAND_HEIGHT / ACTOR_GLYPH_HEIGHT
        val centerX = 0f
        val gy: (Float) -> Float = { offset ->
            glyphBottom - (ACTOR_GLYPH_BOTTOM - offset) * glyphScale
        }
        val gx: (Float) -> Float = { offset -> offset * glyphScale }
        val head = circlePoints(centerX, gy(10f), 15f * glyphScale)
        val outline = listOf(
            ScenePoint(-width / 2f, -height / 2f),
            ScenePoint(width / 2f, -height / 2f),
            ScenePoint(width / 2f, height / 2f),
            ScenePoint(-width / 2f, height / 2f),
        )
        return SceneShapeGeometry(
            paths = listOf(
                SceneShapePath(
                    points = head,
                    fill = SceneShapePaint.None,
                ),
                SceneShapePath(
                    points = listOf(
                        ScenePoint(centerX, gy(25f)),
                        ScenePoint(centerX, gy(45f)),
                    ),
                    closed = false,
                    fill = SceneShapePaint.None,
                ),
                SceneShapePath(
                    points = listOf(
                        ScenePoint(gx(-ACTOR_TYPE_WIDTH / 2f), gy(33f)),
                        ScenePoint(gx(ACTOR_TYPE_WIDTH / 2f), gy(33f)),
                    ),
                    closed = false,
                    fill = SceneShapePaint.None,
                ),
                SceneShapePath(
                    points = listOf(
                        ScenePoint(gx(-ACTOR_TYPE_WIDTH / 2f), gy(60f)),
                        ScenePoint(centerX, gy(45f)),
                    ),
                    closed = false,
                    fill = SceneShapePaint.None,
                ),
                SceneShapePath(
                    points = listOf(
                        ScenePoint(centerX, gy(45f)),
                        ScenePoint(gx(ACTOR_TYPE_WIDTH / 2f - 2f), gy(60f)),
                    ),
                    closed = false,
                    fill = SceneShapePaint.None,
                ),
            ),
            outline = outline,
        )
    }

    private fun boundaryGeometry(
        height: Float,
        textHeight: Float,
    ): SceneShapeGeometry {
        val centerY = glyphBottom(height, textHeight) - ROUND_GLYPH_RADIUS
        val circle = circlePoints(0f, centerY, ROUND_GLYPH_RADIUS)
        val paths = listOf(
            SceneShapePath(circle),
            SceneShapePath(
                points = listOf(
                    ScenePoint(-ROUND_GLYPH_RADIUS * 2.5f, centerY),
                    ScenePoint(-15f, centerY),
                ),
                closed = false,
                fill = SceneShapePaint.None,
            ),
            SceneShapePath(
                points = listOf(
                    ScenePoint(-ROUND_GLYPH_RADIUS * 2.5f, centerY - 10f),
                    ScenePoint(-ROUND_GLYPH_RADIUS * 2.5f, centerY + 10f),
                ),
                closed = false,
                fill = SceneShapePaint.None,
            ),
        )
        return SceneShapeGeometry(
            paths = paths,
            outline = listOf(
                ScenePoint(-ROUND_GLYPH_RADIUS * 2.5f, centerY - ROUND_GLYPH_RADIUS),
                ScenePoint(ROUND_GLYPH_RADIUS, centerY - ROUND_GLYPH_RADIUS),
                ScenePoint(ROUND_GLYPH_RADIUS, centerY + ROUND_GLYPH_RADIUS),
                ScenePoint(-ROUND_GLYPH_RADIUS * 2.5f, centerY + ROUND_GLYPH_RADIUS),
            ),
        )
    }

    private fun controlGeometry(
        height: Float,
        textHeight: Float,
    ): SceneShapeGeometry {
        val centerY = glyphBottom(height, textHeight) - ROUND_GLYPH_RADIUS
        val circle = circlePoints(0f, centerY, ROUND_GLYPH_RADIUS)
        val markerPosition = ScenePoint(0f, centerY - ROUND_GLYPH_RADIUS)
        val markerReference = ScenePoint(11f, 5.8f)
        val markerAngle = CONTROL_MARKER_ANGLE_DEGREES * PI / 180.0
        val markerPoints = listOf(
            ScenePoint(14.4f, 5.6f),
            ScenePoint(7.2f, 10.4f),
            ScenePoint(8.8f, 5.6f),
            ScenePoint(7.2f, 0.8f),
        ).map { point ->
            val x = point.x - markerReference.x
            val y = point.y - markerReference.y
            ScenePoint(
                x = markerPosition.x +
                    (x * cos(markerAngle) - y * sin(markerAngle)).toFloat(),
                y = markerPosition.y +
                    (x * sin(markerAngle) + y * cos(markerAngle)).toFloat(),
            )
        }
        return SceneShapeGeometry(
            paths = listOf(
                SceneShapePath(circle),
                SceneShapePath(
                    points = markerPoints,
                    fill = SceneShapePaint.Stroke,
                    stroke = SceneShapePaint.Stroke,
                    strokeWidth = 1.2f,
                ),
            ),
            outline = circle,
        )
    }

    private fun entityGeometry(
        height: Float,
        textHeight: Float,
    ): SceneShapeGeometry {
        val centerY = glyphBottom(height, textHeight) - ROUND_GLYPH_RADIUS
        val circle = circlePoints(0f, centerY, ROUND_GLYPH_RADIUS)
        return SceneShapeGeometry(
            paths = listOf(
                SceneShapePath(circle),
                SceneShapePath(
                    points = listOf(
                        ScenePoint(-ROUND_GLYPH_RADIUS, centerY + ROUND_GLYPH_RADIUS),
                        ScenePoint(ROUND_GLYPH_RADIUS, centerY + ROUND_GLYPH_RADIUS),
                    ),
                    closed = false,
                    fill = SceneShapePaint.None,
                    strokeWidth = 2f,
                ),
            ),
            outline = circle,
        )
    }

    private fun databaseGeometry(
        width: Float,
        height: Float,
        textHeight: Float,
    ): SceneShapeGeometry {
        val cylinderWidth = width / 3f
        val radiusX = cylinderWidth / 2f
        val radiusY = radiusX / (2.5f + cylinderWidth / 50f)
        val glyphBottom = glyphBottom(height, textHeight)
        val topCenterY = glyphBottom - GLYPH_BAND_HEIGHT + radiusY
        val bottomCenterY = glyphBottom - radiusY
        val outer = ellipseArcPoints(
            centerX = 0f,
            centerY = topCenterY,
            radiusX = radiusX,
            radiusY = radiusY,
            startDegrees = 180f,
            endDegrees = 360f,
        ) + listOf(
            ScenePoint(radiusX, bottomCenterY),
        ) + ellipseArcPoints(
            centerX = 0f,
            centerY = bottomCenterY,
            radiusX = radiusX,
            radiusY = radiusY,
            startDegrees = 0f,
            endDegrees = 180f,
        )
        val top = ellipsePoints(0f, topCenterY, radiusX, radiusY)
        return SceneShapeGeometry(
            paths = listOf(
                SceneShapePath(outer),
                SceneShapePath(
                    points = top,
                    closed = true,
                    fill = SceneShapePaint.None,
                ),
            ),
            outline = outer,
        )
    }

    private fun collectionsGeometry(width: Float, height: Float): SceneShapeGeometry {
        val rectangleHeight = height - COLLECTION_OFFSET
        val back = rectanglePoints(
            width = width,
            height = rectangleHeight,
            offsetX = -COLLECTION_OFFSET,
            offsetY = COLLECTION_OFFSET / 2f,
        )
        val main = rectanglePoints(
            width = width,
            height = rectangleHeight,
            offsetY = -COLLECTION_OFFSET / 2f,
        )
        return SceneShapeGeometry(
            paths = listOf(SceneShapePath(back), SceneShapePath(main)),
            outline = listOf(
                ScenePoint(-width / 2f - COLLECTION_OFFSET, -height / 2f + COLLECTION_OFFSET),
                ScenePoint(width / 2f, -height / 2f),
                ScenePoint(width / 2f, height / 2f - COLLECTION_OFFSET),
                ScenePoint(-width / 2f - COLLECTION_OFFSET, height / 2f),
            ),
        )
    }

    private fun queueGeometry(width: Float, height: Float): SceneShapeGeometry {
        val radiusY = height / 2f
        val radiusX = radiusY / (2.5f + height / 50f)
        val leftCenterX = -width / 2f + radiusX
        val rightCenterX = width / 2f - radiusX
        val outer = ellipseArcPoints(
            centerX = leftCenterX,
            centerY = 0f,
            radiusX = radiusX,
            radiusY = radiusY,
            startDegrees = -90f,
            endDegrees = -270f,
        ) + listOf(
            ScenePoint(rightCenterX, radiusY),
        ) + ellipseArcPoints(
            centerX = rightCenterX,
            centerY = 0f,
            radiusX = radiusX,
            radiusY = radiusY,
            startDegrees = 90f,
            endDegrees = -90f,
        )
        val rightInnerArc = ellipseArcPoints(
            centerX = rightCenterX,
            centerY = 0f,
            radiusX = radiusX,
            radiusY = radiusY,
            startDegrees = -90f,
            endDegrees = -270f,
        )
        return SceneShapeGeometry(
            paths = listOf(
                SceneShapePath(outer),
                SceneShapePath(
                    points = rightInnerArc,
                    closed = false,
                    fill = SceneShapePaint.None,
                ),
            ),
            outline = outer,
        )
    }

    private fun glyphBottom(
        height: Float,
        textHeight: Float,
    ): Float = height / 2f - LABEL_LIFELINE_GAP - textHeight - GLYPH_LABEL_GAP

    private fun ellipseArcPoints(
        centerX: Float,
        centerY: Float,
        radiusX: Float,
        radiusY: Float,
        startDegrees: Float,
        endDegrees: Float,
    ): List<ScenePoint> {
        val stepCount = CIRCLE_SEGMENTS / 2
        return (0..stepCount).map { index ->
            val progress = index.toFloat() / stepCount
            val angle = (startDegrees + (endDegrees - startDegrees) * progress) * PI / 180.0
            ScenePoint(
                x = centerX + cos(angle).toFloat() * radiusX,
                y = centerY + sin(angle).toFloat() * radiusY,
            )
        }
    }

    private fun rectanglePoints(
        width: Float,
        height: Float,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ): List<ScenePoint> = listOf(
        ScenePoint(-width / 2f + offsetX, -height / 2f + offsetY),
        ScenePoint(width / 2f + offsetX, -height / 2f + offsetY),
        ScenePoint(width / 2f + offsetX, height / 2f + offsetY),
        ScenePoint(-width / 2f + offsetX, height / 2f + offsetY),
    )

    private fun circleGeometry(radius: Float): SceneShapeGeometry {
        val points = circlePoints(0f, 0f, radius)
        return SceneShapeGeometry(listOf(SceneShapePath(points)), points)
    }

    private fun circlePoints(
        centerX: Float,
        centerY: Float,
        radius: Float,
    ): List<ScenePoint> = (0 until CIRCLE_SEGMENTS).map { index ->
        val angle = 2.0 * PI * index / CIRCLE_SEGMENTS
        ScenePoint(
            x = centerX + cos(angle).toFloat() * radius,
            y = centerY + sin(angle).toFloat() * radius,
        )
    }

    private fun ellipsePoints(
        centerX: Float,
        centerY: Float,
        radiusX: Float,
        radiusY: Float,
    ): List<ScenePoint> = (0 until CIRCLE_SEGMENTS).map { index ->
        val angle = 2.0 * PI * index / CIRCLE_SEGMENTS
        ScenePoint(
            x = centerX + cos(angle).toFloat() * radiusX,
            y = centerY + sin(angle).toFloat() * radiusY,
        )
    }

    private fun <T> unsupported(feature: String): GMResult<T, MermaidError> =
        GMResult.Err(MermaidError.UnsupportedFeature(feature))

    private data class LayoutActor(
        val source: SequenceActor,
        val colorIndex: Int,
        val text: String,
        val textMetrics: TextMetrics,
        var width: Float,
        var height: Float = ACTOR_HEIGHT,
        var left: Float = 0f,
        var centerX: Float = 0f,
    )

    private data class NoteLayout(
        val bottom: Float,
        val left: Float,
        val right: Float,
        val elements: List<SceneElement>,
    )

    private data class FrameSection(
        val id: String,
        val y: Float,
        val titleLines: List<String>,
        val titleHeight: Float,
    )

    private data class ControlFrame(
        val id: String,
        val type: Int,
        val label: String,
        val titleLines: List<String>,
        val startY: Float,
        val titleHeight: Float,
        val depth: Int,
        val fill: SceneColor,
        var endY: Float = startY,
        var leftIndex: Int = Int.MAX_VALUE,
        var rightIndex: Int = Int.MIN_VALUE,
        var contentLeft: Float = Float.POSITIVE_INFINITY,
        var contentRight: Float = Float.NEGATIVE_INFINITY,
        var loopLeft: Float = Float.POSITIVE_INFINITY,
        var loopRight: Float = Float.NEGATIVE_INFINITY,
        var loopWidth: Float = 0f,
        val sections: MutableList<FrameSection> = mutableListOf(),
    ) {
        fun include(index: Int) {
            if (index == Int.MAX_VALUE || index == Int.MIN_VALUE) {
                return
            }
            leftIndex = min(leftIndex, index)
            rightIndex = max(rightIndex, index)
        }

        fun includeBounds(left: Float, right: Float) {
            contentLeft = min(contentLeft, left)
            contentRight = max(contentRight, right)
        }

        fun includeLoopBounds(left: Float, right: Float) {
            loopLeft = min(loopLeft, left)
            loopRight = max(loopRight, right)
            loopWidth = max(loopWidth, abs(loopRight - loopLeft)) - LABEL_BOX_WIDTH
        }

        fun includeLoopWidth(width: Float) {
            loopWidth = max(loopWidth, width) - LABEL_BOX_WIDTH
        }
    }

    private companion object {
        const val ACTOR_WIDTH = 150f
        const val ACTOR_HEIGHT = 65f
        const val ACTOR_MARGIN = 50f
        const val ACTOR_FONT_SIZE = 14f
        const val MESSAGE_FONT_SIZE = 16f
        const val NOTE_FONT_SIZE = 14f
        const val DIAGRAM_MARGIN_X = 50f
        const val DIAGRAM_MARGIN_Y = 10f
        const val BOX_MARGIN = 10f
        const val NOTE_MARGIN = 10f
        const val WRAP_PADDING = 10f
        const val LABEL_BOX_WIDTH = 50f
        const val LABEL_BOX_HEIGHT = 20f
        const val MESSAGE_TOP_PADDING = 10f
        const val MESSAGE_LINE_GAP = 10f
        const val SELF_MESSAGE_WIDTH = 60f
        const val SELF_MESSAGE_HEIGHT = 30f
        const val ACTIVATION_WIDTH = 10f
        const val MIN_ACTIVATION_HEIGHT = 12f
        const val TITLE_HEIGHT = 40f
        const val GLYPH_BAND_HEIGHT = 44f
        const val GLYPH_LABEL_GAP = 6f
        const val LABEL_LIFELINE_GAP = 6f
        const val ACTOR_TYPE_WIDTH = 36f
        const val ACTOR_GLYPH_BOTTOM = 60f
        const val ACTOR_GLYPH_HEIGHT = 65f
        const val ROUND_GLYPH_RADIUS = 22f
        const val CONTROL_MARKER_ANGLE_DEGREES = 172.5
        const val COLLECTION_OFFSET = 6f
        const val SVG_WRAP_WIDTH_SCALE = 1.1f
        const val MESSAGE_STROKE_WIDTH = 1.5f
        const val SEQUENCE_NUMBER_RADIUS = 6f
        const val SEQUENCE_NUMBER_TEXT_HALF_WIDTH = 18f
        const val SEQUENCE_NUMBER_CENTRAL_START_OFFSET = 5f
        const val SEQUENCE_NUMBER_REVERSE_CENTRAL_START_OFFSET = 7.5f
        const val SEQUENCE_NUMBER_CENTRAL_END_OFFSET = 15f
        const val MESSAGE_ENDPOINT_OFFSET = 3f
        const val CENTRAL_CONNECTION_BASE_OFFSET = 4f
        const val CENTRAL_CONNECTION_BIDIRECTIONAL_OFFSET = 6f
        const val MAX_TEXT_WIDTH = 10_000f
        const val CIRCLE_SEGMENTS = 24

        val TRANSPARENT = SceneColor(0x00000000)
        val BOX_STROKE = SceneColor(0x80000000)
        val SEQUENCE_NUMBER_TEXT = SceneColor(0xFFFFFFFF)
        val HTML_BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)

        val GLYPH_ACTOR_TYPES = setOf(
            "actor",
            "boundary",
            "control",
            "entity",
            "database",
        )
        val NARROW_LIFECYCLE_ACTOR_TYPES = setOf(
            "actor",
            "control",
            "entity",
            "database",
        )
        val BIDIRECTIONAL_MESSAGE_TYPES = setOf(
            SequenceLineType.BIDIRECTIONAL_SOLID,
            SequenceLineType.BIDIRECTIONAL_DOTTED,
        )
        val REVERSE_ARROW_MESSAGE_TYPES = setOf(
            SequenceLineType.SOLID_ARROW_TOP_REVERSE,
            SequenceLineType.SOLID_ARROW_BOTTOM_REVERSE,
            SequenceLineType.STICK_ARROW_TOP_REVERSE,
            SequenceLineType.STICK_ARROW_BOTTOM_REVERSE,
            SequenceLineType.SOLID_ARROW_TOP_REVERSE_DOTTED,
            SequenceLineType.SOLID_ARROW_BOTTOM_REVERSE_DOTTED,
            SequenceLineType.STICK_ARROW_TOP_REVERSE_DOTTED,
            SequenceLineType.STICK_ARROW_BOTTOM_REVERSE_DOTTED,
        )
        val MESSAGE_END_OFFSET_EXCLUDED_TYPES = setOf(
            SequenceLineType.SOLID_OPEN,
            SequenceLineType.DOTTED_OPEN,
            SequenceLineType.STICK_TOP,
            SequenceLineType.STICK_BOTTOM,
            SequenceLineType.STICK_TOP_DOTTED,
            SequenceLineType.STICK_BOTTOM_DOTTED,
            SequenceLineType.SOLID_ARROW_TOP_REVERSE_DOTTED,
            SequenceLineType.SOLID_ARROW_BOTTOM_REVERSE_DOTTED,
            SequenceLineType.STICK_ARROW_TOP_REVERSE,
            SequenceLineType.STICK_ARROW_BOTTOM_REVERSE,
            SequenceLineType.STICK_ARROW_TOP_REVERSE_DOTTED,
            SequenceLineType.STICK_ARROW_BOTTOM_REVERSE_DOTTED,
            SequenceLineType.SOLID_ARROW_TOP_REVERSE,
            SequenceLineType.SOLID_ARROW_BOTTOM_REVERSE,
        )
        val MESSAGE_START_OFFSET_TYPES = setOf(
            SequenceLineType.BIDIRECTIONAL_SOLID,
            SequenceLineType.BIDIRECTIONAL_DOTTED,
            SequenceLineType.SOLID_ARROW_TOP_REVERSE_DOTTED,
            SequenceLineType.SOLID_ARROW_BOTTOM_REVERSE_DOTTED,
            SequenceLineType.SOLID_ARROW_TOP_REVERSE,
            SequenceLineType.SOLID_ARROW_BOTTOM_REVERSE,
        )
        val MESSAGE_TYPES = setOf(
            SequenceLineType.SOLID_OPEN,
            SequenceLineType.DOTTED_OPEN,
            SequenceLineType.SOLID,
            SequenceLineType.SOLID_CROSS,
            SequenceLineType.DOTTED_CROSS,
            SequenceLineType.DOTTED,
            SequenceLineType.SOLID_POINT,
            SequenceLineType.DOTTED_POINT,
            SequenceLineType.BIDIRECTIONAL_SOLID,
            SequenceLineType.BIDIRECTIONAL_DOTTED,
            SequenceLineType.SOLID_TOP,
            SequenceLineType.SOLID_BOTTOM,
            SequenceLineType.STICK_TOP,
            SequenceLineType.STICK_BOTTOM,
            SequenceLineType.SOLID_TOP_DOTTED,
            SequenceLineType.SOLID_BOTTOM_DOTTED,
            SequenceLineType.STICK_TOP_DOTTED,
            SequenceLineType.STICK_BOTTOM_DOTTED,
            SequenceLineType.SOLID_ARROW_TOP_REVERSE,
            SequenceLineType.SOLID_ARROW_BOTTOM_REVERSE,
            SequenceLineType.STICK_ARROW_TOP_REVERSE,
            SequenceLineType.STICK_ARROW_BOTTOM_REVERSE,
            SequenceLineType.SOLID_ARROW_TOP_REVERSE_DOTTED,
            SequenceLineType.SOLID_ARROW_BOTTOM_REVERSE_DOTTED,
            SequenceLineType.STICK_ARROW_TOP_REVERSE_DOTTED,
            SequenceLineType.STICK_ARROW_BOTTOM_REVERSE_DOTTED,
        )
        val DOTTED_MESSAGE_TYPES = setOf(
            SequenceLineType.DOTTED_OPEN,
            SequenceLineType.DOTTED,
            SequenceLineType.DOTTED_CROSS,
            SequenceLineType.DOTTED_POINT,
            SequenceLineType.BIDIRECTIONAL_DOTTED,
            SequenceLineType.SOLID_TOP_DOTTED,
            SequenceLineType.SOLID_BOTTOM_DOTTED,
            SequenceLineType.STICK_TOP_DOTTED,
            SequenceLineType.STICK_BOTTOM_DOTTED,
            SequenceLineType.SOLID_ARROW_TOP_REVERSE_DOTTED,
            SequenceLineType.SOLID_ARROW_BOTTOM_REVERSE_DOTTED,
            SequenceLineType.STICK_ARROW_TOP_REVERSE_DOTTED,
            SequenceLineType.STICK_ARROW_BOTTOM_REVERSE_DOTTED,
        )
        val CONTROL_START_TYPES = setOf(
            SequenceLineType.LOOP_START,
            SequenceLineType.RECT_START,
            SequenceLineType.OPT_START,
            SequenceLineType.ALT_START,
            SequenceLineType.PAR_START,
            SequenceLineType.PAR_OVER_START,
            SequenceLineType.CRITICAL_START,
            SequenceLineType.BREAK_START,
        )
        val CONTROL_SECTION_TYPES = setOf(
            SequenceLineType.ALT_ELSE,
            SequenceLineType.PAR_AND,
            SequenceLineType.CRITICAL_OPTION,
        )
        val CONTROL_END_TYPES = setOf(
            SequenceLineType.LOOP_END,
            SequenceLineType.RECT_END,
            SequenceLineType.OPT_END,
            SequenceLineType.ALT_END,
            SequenceLineType.PAR_END,
            SequenceLineType.CRITICAL_END,
            SequenceLineType.BREAK_END,
        )

        fun controlLabel(type: Int): String = when (type) {
            SequenceLineType.LOOP_START -> "loop"
            SequenceLineType.OPT_START -> "opt"
            SequenceLineType.ALT_START -> "alt"
            SequenceLineType.PAR_START,
            SequenceLineType.PAR_OVER_START,
            -> "par"
            SequenceLineType.CRITICAL_START -> "critical"
            SequenceLineType.BREAK_START -> "break"
            else -> ""
        }
    }
}
