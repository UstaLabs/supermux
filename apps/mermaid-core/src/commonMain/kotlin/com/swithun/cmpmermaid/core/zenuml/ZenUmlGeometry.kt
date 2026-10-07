package com.swithun.cmpmermaid.core.zenuml

import com.swithun.cmpmermaid.core.CssColorParser
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.TextMetricsRequest
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZEN_UML_FRAGMENT_MIN_WIDTH
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZEN_UML_FRAGMENT_PADDING_X
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZEN_UML_MARGIN
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZEN_UML_MIN_PARTICIPANT_WIDTH
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZEN_UML_OCCURRENCE_SIDE_WIDTH
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZEN_UML_OCCURRENCE_WIDTH
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZEN_UML_PARTICIPANT_HEIGHT
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZEN_UML_PARTICIPANT_TOP
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZEN_UML_STARTER
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZenUmlCoordinates
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZenUmlDivider
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZenUmlDocument
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZenUmlFragment
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZenUmlFragmentKind
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZenUmlMessage
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZenUmlMessageKind
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZenUmlParticipant
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZenUmlStatement
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZenUmlTextType
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZenUmlVerticalCoordinates
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.isZenUmlEmoji
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.resolveZenUmlEmoji
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.resolveZenUmlEmojiInText
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

internal data class ZenUmlParticipantGeometry(
    val source: ZenUmlParticipant,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val showBottom: Boolean,
)

internal data class ZenUmlMessageGeometry(
    val id: String,
    val fromX: Double,
    val toX: Double,
    val y: Double,
    val label: String,
    val kind: ZenUmlMessageKind,
    val number: String,
    val commentStyle: ZenUmlCommentStyle?,
)

internal data class ZenUmlSelfCallGeometry(
    val id: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val label: String,
    val kind: ZenUmlMessageKind,
    val number: String,
    val commentStyle: ZenUmlCommentStyle?,
)

internal data class ZenUmlOccurrenceGeometry(
    val id: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val participantName: String,
)

internal data class ZenUmlReturnGeometry(
    val id: String,
    val fromX: Double,
    val toX: Double,
    val y: Double,
    val label: String,
    val isSelf: Boolean,
    val number: String,
)

internal data class ZenUmlFragmentSectionGeometry(
    val label: String,
    val y: Double,
    val contentInsetLeft: Double = 0.0,
    val labelWidth: Double? = null,
    val innerLabel: String? = null,
    val innerLabelWidth: Double? = null,
    val keyword: String? = null,
    val keywordWidth: Double? = null,
    val detail: String? = null,
    val detailWidth: Double? = null,
)

internal data class ZenUmlFragmentGeometry(
    val id: String,
    val kind: ZenUmlFragmentKind,
    val label: String,
    val labelWidth: Double?,
    var x: Double,
    val y: Double,
    var width: Double,
    val height: Double,
    val headerY: Double,
    val sections: List<ZenUmlFragmentSectionGeometry>,
    val number: String,
)

internal data class ZenUmlDividerGeometry(
    val id: String,
    val y: Double,
    val width: Double,
    val label: String,
)

internal data class ZenUmlCommentGeometry(
    val id: String,
    var x: Double,
    val y: Double,
    val text: String,
    val style: ZenUmlCommentStyle,
    val fragmentComment: Boolean,
)

internal data class ZenUmlGroupGeometry(
    val name: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

internal data class ZenUmlCommentStyle(
    val commentColor: String? = null,
    val messageColor: String? = null,
    val commentBold: Boolean = false,
    val messageBold: Boolean = false,
    val commentItalic: Boolean = false,
    val messageItalic: Boolean = false,
    val commentUnderline: Boolean = false,
    val messageUnderline: Boolean = false,
)

internal data class ZenUmlGeometry(
    val width: Double,
    val height: Double,
    val frameBorderLeft: Double,
    val frameBorderRight: Double,
    val title: String?,
    val participants: List<ZenUmlParticipantGeometry>,
    val lifelineBottom: Double,
    val messages: List<ZenUmlMessageGeometry>,
    val selfCalls: List<ZenUmlSelfCallGeometry>,
    val occurrences: List<ZenUmlOccurrenceGeometry>,
    val creationParticipantNames: Set<String>,
    val fragments: List<ZenUmlFragmentGeometry>,
    val dividers: List<ZenUmlDividerGeometry>,
    val returns: List<ZenUmlReturnGeometry>,
    val comments: List<ZenUmlCommentGeometry>,
    val groups: List<ZenUmlGroupGeometry>,
)

/**
 * @zenuml/core 3.49.2:
 * src/svg/buildGeometry.ts, buildParticipantGeometry.ts,
 * buildStatementGeometry.ts, buildFragmentGeometry.ts and computeReturnDebt.ts.
 */
internal class ZenUmlGeometryBuilder(
    private val document: ZenUmlDocument,
    private val context: MermaidRenderContext,
) {
    private val widthProvider = com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZenUmlWidthProvider {
            text,
            _,
        ->
        round(
            context.textMetrics.measure(
                TextMetricsRequest(
                    text = text.trim(),
                    fontSize = PARTICIPANT_FONT_SIZE,
                    maxWidth = MAX_TEXT_WIDTH,
                    fontFamily = ZEN_UML_FONT_FAMILY,
                    weight = SceneTextWeight.Normal,
                ),
            ).width.toDouble(),
        )
    }
    private val coordinates = ZenUmlCoordinates(document, widthProvider)
    private val vertical = ZenUmlVerticalCoordinates(document)
    private val flatStatements = flattenStatements(document.statements)
    private val returnDebt = computeReturnDebt(flatStatements)

    fun build(): ZenUmlGeometry {
        val frame = buildFrameTree(
            statements = document.statements,
            parent = null,
            origin = ZEN_UML_STARTER,
        ).firstOrNull()
        val frameBorderLeft = frame?.let { ZEN_UML_FRAGMENT_PADDING_X * longestPath(it, true) } ?: 0.0
        val frameBorderRight = frame?.let { ZEN_UML_FRAGMENT_PADDING_X * longestPath(it, false) } ?: 0.0
        val participants = buildParticipants()
        val messages = mutableListOf<ZenUmlMessageGeometry>()
        val selfCalls = mutableListOf<ZenUmlSelfCallGeometry>()
        val occurrences = mutableListOf<ZenUmlOccurrenceGeometry>()
        val returns = mutableListOf<ZenUmlReturnGeometry>()
        val fragments = mutableListOf<ZenUmlFragmentGeometry>()
        val dividers = mutableListOf<ZenUmlDividerGeometry>()
        val comments = mutableListOf<ZenUmlCommentGeometry>()
        val creations = mutableSetOf<String>()
        var maxReturnBottom = 0.0
        val diagramCoordinateWidth = coordinates.getWidth()
        val childCounts = flatStatements
            .groupBy { info -> info.number.substringBeforeLast(".", "") }
            .mapValues { (_, children) ->
                children.maxOfOrNull { child -> child.number.substringAfterLast(".").toInt() } ?: 0
            }

        flatStatements.forEach { info ->
            val coordinate = vertical.getStatementCoordinate(info.statement) ?: return@forEach
            val adjust = returnDebt.adjustment[info.statement.id] ?: 0.0
            val comment = parseComment(info.statement.comment)
            if (comment.text.isNotEmpty() && info.statement !is ZenUmlFragment) {
                val message = info.statement as? ZenUmlMessage
                val participant = when {
                    message?.kind == ZenUmlMessageKind.Creation &&
                        coordinates.getPosition(message.from) > coordinates.getPosition(message.to) ->
                        message.to
                    message != null -> message.from
                    else -> null
                }
                val commentX = participant?.let(coordinates::getPosition) ?: 10.0
                val occurrenceOffset =
                    if (info.senderOccurrenceDepth >= 1) ZEN_UML_OCCURRENCE_SIDE_WIDTH + 1.0 else 1.0
                val codeSpanPadding = if (comment.text.trimStart().startsWith("`")) 2.0 else 0.0
                comments += ZenUmlCommentGeometry(
                    id = "zenuml-comment-${info.statement.id}",
                    x = commentX + occurrenceOffset + codeSpanPadding,
                    y = coordinate.top + adjust + COMMENT_FONT_ASCENT,
                    text = comment.text,
                    style = comment.style,
                    fragmentComment = false,
                )
            }

            when (val statement = info.statement) {
                is ZenUmlMessage -> when (statement.kind) {
                    ZenUmlMessageKind.Sync,
                    ZenUmlMessageKind.Async,
                    -> {
                        val commentHeight = commentHeight(statement.comment)
                        val messageHeight = if (statement.isSelf) {
                            if (statement.kind == ZenUmlMessageKind.Async) 44.0 else 30.0
                        } else {
                            16.0
                        }
                        var fromX = coordinates.getPosition(statement.from)
                        val toX = coordinates.getPosition(statement.to)
                        val isLeftToRight = fromX < toX
                        if (info.senderOccurrenceDepth >= 1 && !statement.isSelf) {
                            fromX = if (isLeftToRight) {
                                fromX + info.senderOccurrenceDepth * ZEN_UML_OCCURRENCE_SIDE_WIDTH
                            } else {
                                fromX - ZEN_UML_OCCURRENCE_SIDE_WIDTH +
                                    (info.senderOccurrenceDepth - 1) * ZEN_UML_OCCURRENCE_SIDE_WIDTH
                            }
                        }
                        val targetDepth = info.targetOccurrenceDepth
                        val nestingOffset = targetDepth * ZEN_UML_OCCURRENCE_SIDE_WIDTH
                        val messageY = coordinate.top + adjust + commentHeight + messageHeight - 0.5
                        if (statement.isSelf) {
                            val async = statement.kind == ZenUmlMessageKind.Async
                            selfCalls += ZenUmlSelfCallGeometry(
                                id = "zenuml-message-${statement.id}",
                                x = if (info.senderOccurrenceDepth >= 1) {
                                    fromX + ZEN_UML_OCCURRENCE_SIDE_WIDTH +
                                        (info.senderOccurrenceDepth - 1) * ZEN_UML_OCCURRENCE_SIDE_WIDTH
                                } else {
                                    fromX
                                },
                                y = coordinate.top + adjust + commentHeight,
                                width = if (async) 28.0 else ZEN_UML_OCCURRENCE_WIDTH,
                                height = if (async) 44.0 else messageHeight,
                                label = statement.label,
                                kind = statement.kind,
                                number = info.number,
                                commentStyle = comment.style,
                            )
                        } else {
                            val arrowToX = when {
                                statement.kind == ZenUmlMessageKind.Sync && isLeftToRight ->
                                    toX - ZEN_UML_OCCURRENCE_SIDE_WIDTH + nestingOffset
                                statement.kind == ZenUmlMessageKind.Sync ->
                                    toX + ZEN_UML_OCCURRENCE_SIDE_WIDTH + nestingOffset
                                statement.kind == ZenUmlMessageKind.Async &&
                                    targetDepth > 0 &&
                                    isLeftToRight -> toX - nestingOffset
                                statement.kind == ZenUmlMessageKind.Async && targetDepth > 0 ->
                                    toX + nestingOffset
                                else -> toX
                            }
                            messages += ZenUmlMessageGeometry(
                                id = "zenuml-message-${statement.id}",
                                fromX = fromX,
                                toX = arrowToX,
                                y = messageY,
                                label = statement.label,
                                kind = statement.kind,
                                number = info.number,
                                commentStyle = comment.style,
                            )
                        }
                        if (statement.kind == ZenUmlMessageKind.Sync) {
                            val occurrenceY = messageY - 1.5
                            var occurrenceHeight =
                                coordinate.height - messageHeight - commentHeight + 2.0
                            occurrenceHeight += returnDebt.inner[statement.id] ?: 0.0
                            if (statement.assignment != null && !statement.isSelf && statement.body.isNotEmpty()) {
                                occurrenceHeight += 4.0
                            }
                            if (occurrenceHeight > 0.0) {
                                occurrences += ZenUmlOccurrenceGeometry(
                                    id = "zenuml-occurrence-${statement.id}",
                                    x = toX - ZEN_UML_OCCURRENCE_SIDE_WIDTH,
                                    y = occurrenceY,
                                    width = ZEN_UML_OCCURRENCE_WIDTH,
                                    height = occurrenceHeight,
                                    participantName = statement.to,
                                )
                            }
                            if (statement.assignment != null && !statement.isSelf) {
                                val returnY = occurrenceY + occurrenceHeight - 2.0
                                val returnFromX = if (isLeftToRight) {
                                    toX - ZEN_UML_OCCURRENCE_SIDE_WIDTH + nestingOffset + 1.0
                                } else {
                                    toX + ZEN_UML_OCCURRENCE_SIDE_WIDTH + nestingOffset + 1.0
                                }
                                var senderX = fromX
                                if (info.senderOccurrenceDepth == 0) {
                                    senderX += if (isLeftToRight) 2.0 else 1.0
                                } else if (isLeftToRight) {
                                    senderX += 2.0
                                }
                                returns += ZenUmlReturnGeometry(
                                    id = "zenuml-assignment-return-${statement.id}",
                                    fromX = returnFromX,
                                    toX = senderX,
                                    y = returnY,
                                    label = statement.assignment.assignee,
                                    isSelf = false,
                                    number = "${info.number}.${(childCounts[info.number] ?: 0) + 1}",
                                )
                                maxReturnBottom = max(maxReturnBottom, returnY + 46.0)
                            }
                        }
                    }
                    ZenUmlMessageKind.Creation -> {
                        creations += statement.to
                        var fromX = coordinates.getPosition(statement.from)
                        val toX = coordinates.getPosition(statement.to)
                        if (info.senderOccurrenceDepth >= 1) {
                            val offset = ZEN_UML_OCCURRENCE_SIDE_WIDTH +
                                (info.senderOccurrenceDepth - 1) * ZEN_UML_OCCURRENCE_SIDE_WIDTH
                            fromX += if (fromX < toX) offset else -offset
                        }
                        val target = participants.firstOrNull { participant ->
                            participant.source.name == statement.to
                        }
                        val messageY = target?.let { participant ->
                            participant.y + ZEN_UML_PARTICIPANT_HEIGHT / 2.0
                        } ?: coordinate.top + 20.0
                        messages += ZenUmlMessageGeometry(
                            id = "zenuml-creation-${statement.id}",
                            fromX = fromX,
                            toX = toX,
                            y = messageY,
                            label = statement.label,
                            kind = ZenUmlMessageKind.Creation,
                            number = info.number,
                            commentStyle = comment.style,
                        )
                        val occurrenceY = target?.let { participant ->
                            participant.y + ZEN_UML_PARTICIPANT_HEIGHT - 2.0
                        } ?: coordinate.top + 38.0
                        val occurrenceHeight = max(
                            coordinate.top + coordinate.height - occurrenceY,
                            24.0,
                        )
                        occurrences += ZenUmlOccurrenceGeometry(
                            id = "zenuml-occurrence-${statement.id}",
                            x = toX - ZEN_UML_OCCURRENCE_SIDE_WIDTH,
                            y = occurrenceY,
                            width = ZEN_UML_OCCURRENCE_WIDTH,
                            height = occurrenceHeight,
                            participantName = statement.to,
                        )
                        statement.assignment?.let { assignment ->
                            val returnY = occurrenceY + occurrenceHeight - 2.0
                            val rawFromX = coordinates.getPosition(statement.from)
                            val leftToRight = rawFromX < toX
                            val senderNest =
                                max(info.senderOccurrenceDepth - 1, 0) * ZEN_UML_OCCURRENCE_SIDE_WIDTH
                            val senderReturnX = if (info.senderOccurrenceDepth >= 1) {
                                rawFromX + senderNest +
                                    if (leftToRight) ZEN_UML_OCCURRENCE_SIDE_WIDTH + 2.0
                                    else -ZEN_UML_OCCURRENCE_SIDE_WIDTH
                            } else {
                                rawFromX
                            }
                            val createdReturnX = if (leftToRight) {
                                toX - ZEN_UML_OCCURRENCE_SIDE_WIDTH + 1.0
                            } else {
                                toX + ZEN_UML_OCCURRENCE_SIDE_WIDTH + 1.0
                            }
                            returns += ZenUmlReturnGeometry(
                                id = "zenuml-assignment-return-${statement.id}",
                                fromX = createdReturnX,
                                toX = senderReturnX,
                                y = returnY,
                                label = assignment.assignee,
                                isSelf = false,
                                number = "${info.number}.${(childCounts[info.number] ?: 0) + 1}",
                            )
                            maxReturnBottom = max(maxReturnBottom, returnY + 46.0)
                        }
                    }
                    ZenUmlMessageKind.Return -> {
                        val rawFromX = coordinates.getPosition(statement.from)
                        val rawToX = coordinates.getPosition(statement.to)
                        val reverse = rawToX < rawFromX
                        val fromX = if (reverse) {
                            if (info.senderOccurrenceDepth == 0) {
                                rawFromX
                            } else {
                                (
                                    if (info.senderOccurrenceDepth <= 1) {
                                        rawFromX
                                    } else {
                                        rawFromX +
                                            ZEN_UML_OCCURRENCE_SIDE_WIDTH *
                                            (info.senderOccurrenceDepth - 1)
                                    }
                                    ) - ZEN_UML_OCCURRENCE_SIDE_WIDTH
                            }
                        } else {
                            rawFromX +
                                ZEN_UML_OCCURRENCE_SIDE_WIDTH * info.senderOccurrenceDepth +
                                1.0
                        }
                        var toX = if (reverse) {
                            rawToX +
                                ZEN_UML_OCCURRENCE_SIDE_WIDTH * info.targetOccurrenceDepth
                        } else if (info.targetOccurrenceDepth == 0) {
                            rawToX
                        } else {
                            (
                                if (info.targetOccurrenceDepth <= 1) {
                                    rawToX
                                } else {
                                    rawToX +
                                        ZEN_UML_OCCURRENCE_SIDE_WIDTH *
                                        (info.targetOccurrenceDepth - 1)
                                }
                                ) - ZEN_UML_OCCURRENCE_SIDE_WIDTH
                        }
                        if (reverse) toX += 1.0
                        val returnOffset = if (coordinate.height == 0.0) 16.5 else 15.5
                        val returnY = coordinate.top + adjust + returnOffset
                        returns += ZenUmlReturnGeometry(
                            id = "zenuml-return-${statement.id}",
                            fromX = fromX,
                            toX = toX,
                            y = returnY,
                            label = statement.label,
                            isSelf = statement.isSelf,
                            number = info.number,
                        )
                        maxReturnBottom = max(
                            maxReturnBottom,
                            returnY + if (coordinate.height == 0.0) 45.5 else 44.5,
                        )
                    }
                }
                is ZenUmlFragment -> {
                    val fragmentCommentHeight = commentHeight(statement.comment)
                    val localNames = collectParticipantNames(statement, info.origin)
                    val orderedNames = coordinates.orderedParticipantNames()
                    val left = orderedNames.firstOrNull(localNames::contains)
                    val right = orderedNames.lastOrNull(localNames::contains)
                    val fragmentFrame = buildFrameTree(
                        statements = listOf(statement),
                        parent = null,
                        origin = info.origin,
                    ).firstOrNull()
                    val fragmentLeftBorder =
                        fragmentFrame?.let { ZEN_UML_FRAGMENT_PADDING_X * longestPath(it, true) } ?: 0.0
                    val fragmentRightBorder =
                        fragmentFrame?.let { ZEN_UML_FRAGMENT_PADDING_X * longestPath(it, false) } ?: 0.0
                    val x: Double
                    val width: Double
                    if (left != null && right != null) {
                        val participantWidth =
                            coordinates.distance(left, right) + coordinates.half(left) + coordinates.half(right)
                        val selfExtra = collectMessages(statement.sections.flatMap { section -> section.statements })
                            .filter(ZenUmlMessage::isSelf)
                            .maxOfOrNull { message ->
                                coordinates.getMessageWidth(message) -
                                    coordinates.distance(message.from, right) -
                                    coordinates.half(right)
                            }
                            ?.coerceAtLeast(0.0)
                            ?: 0.0
                        x = coordinates.getPosition(left) - coordinates.half(left)
                        width = max(participantWidth, ZEN_UML_FRAGMENT_MIN_WIDTH) +
                            fragmentLeftBorder +
                            fragmentRightBorder +
                            selfExtra
                    } else {
                        x = 0.0
                        width = max(ZEN_UML_FRAGMENT_MIN_WIDTH, coordinates.getWidth())
                    }
                    val sections = buildFragmentSections(statement, coordinate.top)
                    fragments += ZenUmlFragmentGeometry(
                        id = "zenuml-fragment-${statement.id}",
                        kind = statement.kind,
                        label = statement.label,
                        labelWidth = statement.label.takeIf(String::isNotEmpty)?.let { label ->
                            measure(resolveZenUmlEmojiInText(label), MESSAGE_FONT_SIZE)
                        },
                        x = x,
                        y = coordinate.top + adjust,
                        width = width,
                        height = coordinate.height,
                        headerY = coordinate.top + adjust + 1.0 + fragmentCommentHeight,
                        sections = sections,
                        number = info.number,
                    )
                    if (comment.text.isNotEmpty()) {
                        comments += ZenUmlCommentGeometry(
                            id = "zenuml-comment-${statement.id}",
                            x = x + 1.0,
                            y = coordinate.top + adjust + 1.0 + COMMENT_FONT_ASCENT,
                            text = comment.text,
                            style = comment.style,
                            fragmentComment = true,
                        )
                    }
                }
                is ZenUmlDivider -> {
                    dividers += ZenUmlDividerGeometry(
                        id = "zenuml-divider-${statement.id}",
                        y = coordinate.top + adjust + coordinate.height / 2.0,
                        width = diagramCoordinateWidth,
                        label = statement.label,
                    )
                }
            }
        }

        offsetNestedOccurrences(occurrences)
        val participantWidth = contentParticipantWidth()
        val rightParticipant = coordinates.orderedParticipantNames().lastOrNull().orEmpty()
        val selfExtra = collectMessages(document.statements)
            .filter(ZenUmlMessage::isSelf)
            .maxOfOrNull { message ->
                coordinates.getMessageWidth(message) -
                    coordinates.distance(message.from, rightParticipant) -
                    coordinates.half(rightParticipant)
            }
            ?.coerceAtLeast(0.0)
            ?: 0.0
        var diagramWidth = max(participantWidth, ZEN_UML_FRAGMENT_MIN_WIDTH) + selfExtra
        messages.forEach { message ->
            val labelWidth = measure(message.label, MESSAGE_FONT_SIZE)
            val rightExtent = (message.fromX + message.toX) / 2.0 + labelWidth / 2.0 + 10.0
            diagramWidth = max(diagramWidth, rightExtent)
        }
        returns.forEach { returnGeometry ->
            val labelWidth = measure(returnGeometry.label, MESSAGE_FONT_SIZE)
            val rightExtent =
                (returnGeometry.fromX + returnGeometry.toX) / 2.0 + labelWidth / 2.0 + 10.0
            diagramWidth = max(diagramWidth, rightExtent)
        }

        fragments.forEach { fragment -> fragment.x -= frameBorderLeft }
        comments.filter(ZenUmlCommentGeometry::fragmentComment).forEach { comment ->
            comment.x -= frameBorderLeft
        }
        fragments.forEach { inner ->
            val depth = fragments.count { outer ->
                outer !== inner &&
                    outer.x <= inner.x &&
                    outer.y <= inner.y &&
                    outer.x + outer.width >= inner.x + inner.width &&
                    outer.y + outer.height >= inner.y + inner.height
            }
            inner.x += depth * ZEN_UML_FRAGMENT_PADDING_X
        }
        val contentRight = diagramWidth + frameBorderRight
        fragments.forEach { fragment ->
            val depth = fragments.count { outer ->
                outer !== fragment &&
                    outer.x <= fragment.x &&
                    outer.y <= fragment.y &&
                    outer.x + outer.width >= fragment.x + fragment.width &&
                    outer.y + outer.height >= fragment.y + fragment.height
            }
            val targetRight = contentRight - depth * ZEN_UML_FRAGMENT_PADDING_X
            val currentRight = fragment.x + fragment.width
            if (currentRight >= targetRight - 20.0 && currentRight < targetRight) {
                fragment.width = targetRight - fragment.x
            }
        }

        val maxOccurrenceBottom = occurrences.maxOfOrNull { occurrence ->
            occurrence.y + occurrence.height
        } ?: 0.0
        val maxOtherY = maxOf(
            messages.maxOfOrNull(ZenUmlMessageGeometry::y) ?: 0.0,
            selfCalls.maxOfOrNull { self -> self.y + self.height } ?: 0.0,
            fragments.maxOfOrNull { fragment -> fragment.y + fragment.height } ?: 0.0,
            dividers.maxOfOrNull(ZenUmlDividerGeometry::y) ?: 0.0,
        )
        val diagramHeight = maxOf(
            vertical.getTotalHeight() + 28.0,
            maxOccurrenceBottom + 13.0,
            maxOtherY + 13.0,
            maxReturnBottom,
        )
        val groups = buildGroups(participants, diagramHeight)
        return ZenUmlGeometry(
            width = diagramWidth,
            height = diagramHeight,
            frameBorderLeft = frameBorderLeft,
            frameBorderRight = frameBorderRight,
            title = document.title,
            participants = participants,
            lifelineBottom = diagramHeight + ZEN_UML_PARTICIPANT_HEIGHT - ZEN_UML_PARTICIPANT_TOP,
            messages = messages,
            selfCalls = selfCalls,
            occurrences = occurrences,
            creationParticipantNames = creations,
            fragments = fragments,
            dividers = dividers,
            returns = returns.sortedBy(ZenUmlReturnGeometry::y).enforceReturnGap(),
            comments = comments,
            groups = groups,
        )
    }

    private fun buildParticipants(): List<ZenUmlParticipantGeometry> =
        document.participants.map { participant ->
            val labelWidth = measure(participant.displayName, PARTICIPANT_FONT_SIZE)
            val stereotypeWidth = participant.stereotype?.let { stereotype ->
                measure("«$stereotype»", PARTICIPANT_FONT_SIZE)
            } ?: 0.0
            val iconWidth = if (participant.type != null) 28.0 else 0.0
            val emojiWidth = if (participant.emoji != null) 20.0 else 0.0
            val padding = 16.0
            var width = min(
                maxOf(
                    labelWidth + padding + iconWidth + emojiWidth,
                    stereotypeWidth + 8.0,
                    ZEN_UML_MIN_PARTICIPANT_WIDTH,
                ),
                250.0,
            )
            if (participant.name == ZEN_UML_STARTER) width = min(width, 80.0)
            val creationTop = vertical.getCreationTop(participant.name)
            ZenUmlParticipantGeometry(
                source = participant,
                x = coordinates.getPosition(participant.name),
                y = creationTop?.plus(8.0)?.coerceAtLeast(ZEN_UML_PARTICIPANT_TOP)
                    ?: ZEN_UML_PARTICIPANT_TOP,
                width = width,
                height = ZEN_UML_PARTICIPANT_HEIGHT,
                showBottom = creationTop == null && participant.name != ZEN_UML_STARTER,
            )
        }

    private fun buildFragmentSections(
        fragment: ZenUmlFragment,
        fragmentTop: Double,
    ): List<ZenUmlFragmentSectionGeometry> {
        if (fragment.kind == ZenUmlFragmentKind.Par) {
            val statements = fragment.sections.firstOrNull()?.statements.orEmpty()
            if (statements.size <= 1) return emptyList()
            return buildList {
                add(ZenUmlFragmentSectionGeometry("", fragmentTop))
                statements.drop(1).forEach { statement ->
                    val coordinate = vertical.getStatementCoordinate(statement) ?: return@forEach
                    add(
                        ZenUmlFragmentSectionGeometry(
                            label = "",
                            y = coordinate.top - 1.0,
                        ),
                    )
                }
            }
        }
        if (fragment.sections.size <= 1) return emptyList()
        return fragment.sections.mapIndexed { index, section ->
            if (index == 0) {
                sectionGeometry(section.label, fragmentTop)
            } else {
                val first = section.statements.firstOrNull()
                val top = first
                    ?.let(vertical::getStatementCoordinate)
                    ?.top
                    ?.minus(37.0)
                    ?: fragmentTop
                sectionGeometry(section.label, top)
            }
        }
    }

    private fun sectionGeometry(
        label: String,
        y: Double,
    ): ZenUmlFragmentSectionGeometry {
        val innerLabel = BRACKETED_SECTION
            .matchEntire(label)
            ?.groupValues
            ?.get(1)
            ?.trim()
        val splitAt = label.indexOf(' ')
        val hasSplitDetail = splitAt > 0 &&
            !label.startsWith("finally") &&
            !label.startsWith("[")
        val keyword = if (hasSplitDetail) label.substring(0, splitAt) else null
        val detail = if (hasSplitDetail) label.substring(splitAt + 1) else null
        return ZenUmlFragmentSectionGeometry(
            label = label,
            y = y,
            labelWidth = label.takeIf(String::isNotEmpty)?.let { value ->
                measure(resolveZenUmlEmojiInText(value), MESSAGE_FONT_SIZE)
            },
            innerLabel = innerLabel,
            innerLabelWidth = innerLabel?.let { value ->
                measure(resolveZenUmlEmojiInText(value), MESSAGE_FONT_SIZE)
            },
            keyword = keyword,
            keywordWidth = keyword?.let { value -> measure(value, MESSAGE_FONT_SIZE) },
            detail = detail,
            detailWidth = detail?.let { value -> measure(value, MESSAGE_FONT_SIZE) },
        )
    }

    private fun contentParticipantWidth(): Double {
        val names = coordinates.orderedParticipantNames()
        val left = names.firstOrNull() ?: return ZEN_UML_FRAGMENT_MIN_WIDTH
        val right = names.lastOrNull() ?: return ZEN_UML_FRAGMENT_MIN_WIDTH
        return coordinates.distance(left, right) + coordinates.half(left) + coordinates.half(right)
    }

    private fun buildGroups(
        participants: List<ZenUmlParticipantGeometry>,
        diagramHeight: Double,
    ): List<ZenUmlGroupGeometry> =
        participants
            .filter { participant -> participant.source.groupId != null }
            .groupBy { participant -> participant.source.groupId.orEmpty() }
            .map { (name, members) ->
                val minLeft = members.minOf { participant -> participant.x - participant.width / 2.0 }
                val maxRight = members.maxOf { participant -> participant.x + participant.width / 2.0 }
                val minY = members.minOf(ZenUmlParticipantGeometry::y)
                val y = minY - 18.5
                ZenUmlGroupGeometry(
                    name = name,
                    x = minLeft - 2.0,
                    y = y,
                    width = maxRight - minLeft + 4.0,
                    height = max(0.0, diagramHeight - y + 13.0),
                )
            }

    private fun measure(
        text: String,
        fontSize: Float,
    ): Double = context.textMetrics.measure(
        TextMetricsRequest(
            text = text.trim(),
            fontSize = fontSize,
            maxWidth = MAX_TEXT_WIDTH,
            fontFamily = ZEN_UML_FONT_FAMILY,
            weight = SceneTextWeight.Normal,
        ),
    ).width.toDouble()

    private fun parseComment(raw: String?): ParsedComment {
        if (raw.isNullOrBlank()) return ParsedComment("", ZenUmlCommentStyle())
        val lines = raw.trim().lines().toMutableList()
        val last = lines.lastOrNull().orEmpty()
        val directive = COMMENT_DIRECTIVES.matchEntire(last)
        if (directive == null) {
            return ParsedComment(raw.trim(), ZenUmlCommentStyle())
        }
        val commentTokens = directive.groupValues[1].splitStyles()
        val messageTokens = directive.groupValues[2].splitStyles()
        val commonTokens = directive.groupValues[3].splitStyles()
        val emojiNames = commonTokens.mapNotNull { token ->
            when {
                token.startsWith(":") && token.endsWith(":") && token.length > 2 ->
                    token.substring(1, token.lastIndex)
                isZenUmlEmoji(token) -> token
                else -> null
            }
        }
        val styleTokens = commonTokens.filterNot { token ->
            token in emojiNames || (token.startsWith(":") && token.endsWith(":"))
        }
        val remaining = directive.groupValues[4]
        lines[lines.lastIndex] = remaining
        val baseText = lines.joinToString("\n").trim()
        val emojiPrefix = emojiNames.joinToString(separator = "", transform = ::resolveZenUmlEmoji)
        val text = when {
            emojiPrefix.isEmpty() -> baseText
            baseText.isEmpty() -> emojiPrefix
            else -> "$emojiPrefix $baseText"
        }
        return ParsedComment(
            text = text,
            style = ZenUmlCommentStyle(
                commentColor = colorOf(styleTokens + commentTokens),
                messageColor = colorOf(styleTokens + messageTokens),
                commentBold = (styleTokens + commentTokens).any(::isBold),
                messageBold = (styleTokens + messageTokens).any(::isBold),
                commentItalic = (styleTokens + commentTokens).any(::isItalic),
                messageItalic = (styleTokens + messageTokens).any(::isItalic),
                commentUnderline = (styleTokens + commentTokens).any(::isUnderline),
                messageUnderline = (styleTokens + messageTokens).any(::isUnderline),
            ),
        )
    }

    private fun List<ZenUmlReturnGeometry>.enforceReturnGap(): List<ZenUmlReturnGeometry> {
        if (size < 2) return this
        val adjusted = toMutableList()
        for (index in 1 until adjusted.size) {
            val previous = adjusted[index - 1]
            val current = adjusted[index]
            if (current.y - previous.y < 16.0) {
                adjusted[index] = current.copy(y = previous.y + 16.0)
            }
        }
        return adjusted
    }

    private fun offsetNestedOccurrences(occurrences: MutableList<ZenUmlOccurrenceGeometry>) {
        occurrences.indices.forEach { innerIndex ->
            val inner = occurrences[innerIndex]
            val depth = occurrences.indices.count { outerIndex ->
                if (innerIndex == outerIndex) {
                    false
                } else {
                    val outer = occurrences[outerIndex]
                    outer.participantName == inner.participantName &&
                        outer.y <= inner.y &&
                        outer.y + outer.height >= inner.y + inner.height
                }
            }
            if (depth > 0) {
                occurrences[innerIndex] = inner.copy(
                    x = inner.x + depth * ZEN_UML_OCCURRENCE_SIDE_WIDTH,
                )
            }
        }
    }

    private data class ParsedComment(
        val text: String,
        val style: ZenUmlCommentStyle,
    )

    private data class FlatStatement(
        val statement: ZenUmlStatement,
        val number: String,
        val depth: Int,
        val origin: String,
        val parentBlockKind: ZenUmlMessageKind?,
        val senderOccurrenceDepth: Int,
        val targetOccurrenceDepth: Int,
        val sectionReset: Boolean,
    )

    private fun flattenStatements(statements: List<ZenUmlStatement>): List<FlatStatement> =
        walkBlock(
            statements = statements,
            origin = ZEN_UML_STARTER,
            activeOccurrences = emptyMap(),
            parentNumber = "",
            depth = 0,
            parentBlockKind = null,
            indexOffset = 0,
            sectionReset = false,
        )

    private fun walkBlock(
        statements: List<ZenUmlStatement>,
        origin: String,
        activeOccurrences: Map<String, Int>,
        parentNumber: String,
        depth: Int,
        parentBlockKind: ZenUmlMessageKind?,
        indexOffset: Int,
        sectionReset: Boolean,
    ): List<FlatStatement> = buildList {
        statements.forEachIndexed { index, statement ->
            val ordinal = indexOffset + index + 1
            val number = if (parentNumber.isEmpty()) "$ordinal" else "$parentNumber.$ordinal"
            val message = statement as? ZenUmlMessage
            add(
                FlatStatement(
                    statement = statement,
                    number = number,
                    depth = depth,
                    origin = origin,
                    parentBlockKind = parentBlockKind,
                    senderOccurrenceDepth = message?.let { activeOccurrences[it.from] } ?: 0,
                    targetOccurrenceDepth = message?.let { activeOccurrences[it.to] } ?: 0,
                    sectionReset = sectionReset && index == 0,
                ),
            )
            when (statement) {
                is ZenUmlMessage -> if (statement.body.isNotEmpty()) {
                    val innerOccurrences = activeOccurrences.toMutableMap()
                    innerOccurrences[statement.to] = (innerOccurrences[statement.to] ?: 0) + 1
                    addAll(
                        walkBlock(
                            statements = statement.body,
                            origin = statement.to,
                            activeOccurrences = innerOccurrences,
                            parentNumber = number,
                            depth = depth + 1,
                            parentBlockKind = statement.kind,
                            indexOffset = 0,
                            sectionReset = false,
                        ),
                    )
                }
                is ZenUmlFragment -> {
                    var offset = 0
                    statement.sections.forEachIndexed { sectionIndex, section ->
                        addAll(
                            walkBlock(
                                statements = section.statements,
                                origin = origin,
                                activeOccurrences = activeOccurrences,
                                parentNumber = number,
                                depth = depth + 1,
                                parentBlockKind = null,
                                indexOffset = offset,
                                sectionReset = sectionIndex > 0,
                            ),
                        )
                        offset += section.statements.size
                    }
                }
                is ZenUmlDivider -> Unit
            }
        }
    }

    private data class ReturnDebt(
        val adjustment: Map<Int, Double>,
        val inner: Map<Int, Double>,
    )

    private fun computeReturnDebt(statements: List<FlatStatement>): ReturnDebt {
        val adjustment = mutableMapOf<Int, Double>()
        val inner = mutableMapOf<Int, Double>()
        val debtByDepth = mutableListOf(0.0)
        val directDebtByDepth = mutableListOf(0.0)
        val ownerByDepth = mutableListOf<ZenUmlMessage?>(null)
        val hasNonReturnChild = mutableListOf(false)
        val nonBlockAssignmentShift = mutableListOf(0.0)
        var maxDepth = 0

        fun closeDepth() {
            val closedDebt = debtByDepth[maxDepth]
            val directDebt = directDebtByDepth[maxDepth]
            val owner = ownerByDepth[maxDepth]
            val occurrenceDebt = closedDebt - directDebt + max(directDebt - 16.0, 0.0)
            if (owner != null && occurrenceDebt > 0.0) {
                inner[owner.id] = occurrenceDebt
            }
            val mixed = hasNonReturnChild[maxDepth]
            debtByDepth.removeAt(maxDepth)
            directDebtByDepth.removeAt(maxDepth)
            ownerByDepth.removeAt(maxDepth)
            hasNonReturnChild.removeAt(maxDepth)
            nonBlockAssignmentShift.removeAt(maxDepth)
            maxDepth--
            if (owner?.kind == ZenUmlMessageKind.Sync) {
                debtByDepth[maxDepth] += occurrenceDebt
                if (owner.assignment != null && !owner.isSelf) {
                    debtByDepth[maxDepth] += 4.0
                }
                if (mixed && occurrenceDebt == 0.0) {
                    Unit
                }
            }
        }

        statements.forEach { info ->
            while (maxDepth > info.depth) closeDepth()
            while (maxDepth < info.depth) {
                maxDepth++
                debtByDepth += 0.0
                directDebtByDepth += 0.0
                ownerByDepth += null
                hasNonReturnChild += false
                nonBlockAssignmentShift += 0.0
            }
            if (info.sectionReset) {
                debtByDepth[info.depth] = 0.0
                directDebtByDepth[info.depth] = 0.0
            }
            adjustment[info.statement.id] = (0..info.depth).sumOf { depth ->
                debtByDepth[depth] + nonBlockAssignmentShift[depth]
            }
            val message = info.statement as? ZenUmlMessage
            if (message?.kind == ZenUmlMessageKind.Return && !message.isSelf) {
                val coordinate = vertical.getStatementCoordinate(message)
                if (coordinate?.height == 0.0) {
                    val firstAtDepth = directDebtByDepth[info.depth] == 0.0
                    debtByDepth[info.depth] += 16.0
                    directDebtByDepth[info.depth] += 16.0
                    if (
                        firstAtDepth &&
                        adjustment.getValue(info.statement.id) != 0.0 &&
                        info.parentBlockKind == ZenUmlMessageKind.Sync
                    ) {
                        nonBlockAssignmentShift[info.depth] += 1.0
                    }
                }
            } else {
                hasNonReturnChild[info.depth] = true
            }
            if (
                message != null &&
                message.kind in setOf(ZenUmlMessageKind.Sync, ZenUmlMessageKind.Creation) &&
                message.body.isNotEmpty()
            ) {
                if (info.depth + 1 > maxDepth) {
                    maxDepth++
                    debtByDepth += 0.0
                    directDebtByDepth += 0.0
                    ownerByDepth += message
                    hasNonReturnChild += false
                    nonBlockAssignmentShift += 0.0
                } else {
                    ownerByDepth[info.depth + 1] = message
                    debtByDepth[info.depth + 1] = 0.0
                    directDebtByDepth[info.depth + 1] = 0.0
                    hasNonReturnChild[info.depth + 1] = false
                }
            }
        }
        while (maxDepth > 0) closeDepth()
        return ReturnDebt(adjustment = adjustment, inner = inner)
    }

    private data class FragmentFrame(
        val left: String,
        val right: String,
        val children: MutableList<FragmentFrame> = mutableListOf(),
    )

    private fun buildFrameTree(
        statements: List<ZenUmlStatement>,
        parent: FragmentFrame?,
        origin: String,
    ): List<FragmentFrame> {
        val roots = mutableListOf<FragmentFrame>()
        statements.forEach { statement ->
            when (statement) {
                is ZenUmlMessage -> roots += buildFrameTree(
                    statements = statement.body,
                    parent = parent,
                    origin = statement.to,
                )
                is ZenUmlFragment -> {
                    val names = collectParticipantNames(statement, origin)
                    val ordered = coordinates.orderedParticipantNames()
                    val frame = FragmentFrame(
                        left = ordered.firstOrNull(names::contains).orEmpty(),
                        right = ordered.lastOrNull(names::contains).orEmpty(),
                    )
                    if (parent != null) parent.children += frame else roots += frame
                    statement.sections.forEach { section ->
                        buildFrameTree(
                            statements = section.statements,
                            parent = frame,
                            origin = origin,
                        )
                    }
                }
                is ZenUmlDivider -> Unit
            }
        }
        return roots
    }

    private fun longestPath(
        frame: FragmentFrame,
        left: Boolean,
    ): Int {
        if (frame.children.isEmpty()) return 1
        val matching = frame.children.filter { child ->
            if (left) child.left == frame.left else child.right == frame.right
        }
        return (matching.maxOfOrNull { child -> longestPath(child, left) } ?: 0) + 1
    }

    private fun collectParticipantNames(
        fragment: ZenUmlFragment,
        origin: String,
    ): Set<String> = buildSet {
        add(origin)
        if (fragment.kind == ZenUmlFragmentKind.Ref) {
            fragment.label.split(",").map(String::trim).filter(String::isNotEmpty).forEach(::add)
        }
        fragment.sections.forEach { section ->
            addAll(collectParticipantNames(section.statements))
        }
    }

    private fun collectParticipantNames(statements: List<ZenUmlStatement>): Set<String> =
        buildSet {
            statements.forEach { statement ->
                when (statement) {
                    is ZenUmlMessage -> {
                        add(statement.from)
                        add(statement.to)
                        addAll(collectParticipantNames(statement.body))
                    }
                    is ZenUmlFragment -> statement.sections.forEach { section ->
                        addAll(collectParticipantNames(section.statements))
                    }
                    is ZenUmlDivider -> Unit
                }
            }
        }

    private fun collectMessages(statements: List<ZenUmlStatement>): List<ZenUmlMessage> =
        buildList {
            statements.forEach { statement ->
                when (statement) {
                    is ZenUmlMessage -> {
                        add(statement)
                        addAll(collectMessages(statement.body))
                    }
                    is ZenUmlFragment -> statement.sections.forEach { section ->
                        addAll(collectMessages(section.statements))
                    }
                    is ZenUmlDivider -> Unit
                }
            }
        }

    private fun commentHeight(comment: String?): Double =
        comment?.takeIf(String::isNotBlank)?.trim()?.lines()?.size?.times(20.0) ?: 0.0

    private fun String.splitStyles(): List<String> =
        split(",").map(String::trim).filter(String::isNotEmpty)

    private fun colorOf(styles: List<String>): String? =
        styles.lastOrNull { style -> CssColorParser.parse(style) != null }

    private fun isBold(style: String): Boolean = style.lowercase() in setOf("bold", "bolder")

    private fun isItalic(style: String): Boolean = style.lowercase() in setOf("italic", "oblique")

    private fun isUnderline(style: String): Boolean = style.lowercase() == "underline"

    private companion object {
        const val PARTICIPANT_FONT_SIZE = 16f
        const val MESSAGE_FONT_SIZE = 14f
        const val MAX_TEXT_WIDTH = 100_000f
        const val COMMENT_FONT_ASCENT = 15.0
        const val ZEN_UML_FONT_FAMILY = "Helvetica, Verdana, serif"
        val BRACKETED_SECTION = Regex("""^\[\s*(.*?)\s*]$""")
        val COMMENT_DIRECTIVES = Regex(
            """^\s*(?:<([^>]*)>\s*)?(?:\(([^)]*)\)\s*)?(?:\[([^]]*)]\s*)?(.*)$""",
        )
    }
}
