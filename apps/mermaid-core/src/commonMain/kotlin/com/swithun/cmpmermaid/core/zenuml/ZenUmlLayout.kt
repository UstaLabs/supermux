package com.swithun.cmpmermaid.core.zenuml

import com.swithun.cmpmermaid.core.CssColorParser
import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.SceneElement
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneShapeGeometry
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.SceneTextAlignment
import com.swithun.cmpmermaid.core.SceneTextSpan
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.TextMetricsRequest
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.FlowLabelType
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidRenderedText
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidTextPort
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZEN_UML_PARTICIPANT_HEIGHT
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZEN_UML_STARTER
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZenUmlDocument
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZenUmlFragmentKind
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZenUmlMessageKind
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.resolveZenUmlEmoji
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.resolveZenUmlEmojiInText
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * @zenuml/core 3.49.2:
 * src/svg/renderToSvg.ts and src/svg/components.
 */
internal class ZenUmlLayout {
    fun layout(
        document: ZenUmlDocument,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = try {
        val geometry = ZenUmlGeometryBuilder(document, context).build()
        buildScene(geometry, document, context)
    } catch (failure: Exception) {
        GMResult.Err(MermaidError.Unexpected.from(failure, "ZenUML layout failed"))
    }

    private fun buildScene(
        geometry: ZenUmlGeometry,
        document: ZenUmlDocument,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val contentLeft = 11.0 + geometry.frameBorderLeft
        val contentTop = 34.0
        val viewWidth =
            geometry.width + contentLeft + 10.0 + geometry.frameBorderRight + 1.0
        val viewHeight = geometry.height + 47.0
        val elements = mutableListOf<SceneElement>()

        elements += shape(
            id = "zenuml-frame-border",
            left = 0.0,
            top = 0.0,
            width = viewWidth,
            height = viewHeight,
            kind = SceneShapeKind.RoundedRectangle,
            fill = FRAME_BORDER,
            stroke = TRANSPARENT,
            strokeWidth = 0f,
            cornerRadius = 4f,
            zIndex = 0,
        )
        elements += shape(
            id = "zenuml-frame",
            left = 1.0,
            top = 1.0,
            width = viewWidth - 2.0,
            height = viewHeight - 2.0,
            kind = SceneShapeKind.RoundedRectangle,
            fill = WHITE,
            stroke = TRANSPARENT,
            strokeWidth = 0f,
            cornerRadius = 3f,
            zIndex = 1,
        )
        elements += line(
            id = "zenuml-frame-header",
            fromX = 1.0,
            fromY = 33.5,
            toX = viewWidth - 1.0,
            toY = 33.5,
            color = FRAME_BORDER,
            width = 1f,
            zIndex = 3,
        )
        geometry.title?.takeIf(String::isNotBlank)?.let { title ->
            elements += text(
                id = "zenuml-title",
                value = resolveZenUmlEmojiInText(title),
                x = 5.0,
                centerY = 16.75,
                fontSize = 16f,
                color = TEXT,
                weight = SceneTextWeight.Bold,
                alignment = SceneTextAlignment.Start,
                context = context,
                zIndex = 20,
            )
        }

        geometry.groups.forEach { group ->
            elements += shape(
                id = "zenuml-group-${group.name}",
                left = contentLeft + group.x,
                top = contentTop + group.y,
                width = group.width,
                height = group.height,
                kind = SceneShapeKind.Rectangle,
                fill = TRANSPARENT,
                stroke = FRAME_BORDER,
                strokeWidth = 1f,
                strokePattern = SceneStrokePattern.Dashed,
                dashIntervals = listOf(4f, 3f),
                cornerRadius = 0f,
                zIndex = 2,
            )
            if (group.name.isNotEmpty()) {
                elements += shape(
                    id = "zenuml-group-title-bg-${group.name}",
                    left = contentLeft + group.x,
                    top = contentTop + group.y,
                    width = group.width,
                    height = 20f.toDouble(),
                    kind = SceneShapeKind.Rectangle,
                    fill = WHITE,
                    stroke = TRANSPARENT,
                    strokeWidth = 0f,
                    cornerRadius = 0f,
                    zIndex = 3,
                )
                elements += text(
                    id = "zenuml-group-title-${group.name}",
                    value = group.name,
                    x = contentLeft + group.x + group.width / 2.0,
                    centerY = contentTop + group.y + 10.0,
                    fontSize = 13f,
                    color = TEXT,
                    alignment = SceneTextAlignment.Center,
                    context = context,
                    zIndex = 4,
                )
            }
        }

        geometry.participants.forEach { participant ->
            elements += line(
                id = "zenuml-lifeline-${participant.source.name}",
                fromX = contentLeft + participant.x + 0.5,
                fromY = contentTop + participant.y + participant.height,
                toX = contentLeft + participant.x + 0.5,
                toY = contentTop + geometry.lifelineBottom,
                color = FRAME_BORDER,
                width = 1f,
                strokePattern = SceneStrokePattern.Dashed,
                dashIntervals = listOf(5f, 5f),
                zIndex = 4,
            )
        }

        geometry.participants
            .filterNot { participant ->
                participant.source.name in geometry.creationParticipantNames
            }
            .forEach { participant ->
                elements += participantElements(participant, contentLeft, contentTop, context)
            }

        geometry.occurrences.forEach { occurrence ->
            elements += shape(
                id = occurrence.id,
                left = contentLeft + occurrence.x + 1.0,
                top = contentTop + occurrence.y + 1.0,
                width = occurrence.width - 2.0,
                height = occurrence.height - 2.0,
                kind = SceneShapeKind.RoundedRectangle,
                fill = OCCURRENCE_FILL,
                stroke = FRAME_BORDER,
                strokeWidth = 2f,
                cornerRadius = 1f,
                zIndex = 8,
            )
        }

        geometry.participants
            .filter { participant ->
                participant.source.name in geometry.creationParticipantNames
            }
            .forEach { participant ->
                elements += participantElements(participant, contentLeft, contentTop, context)
            }

        geometry.messages.forEach { message ->
            if (message.kind == ZenUmlMessageKind.Creation) {
                elements += creationElements(
                    geometry = message,
                    participant = geometry.participants.firstOrNull { candidate ->
                        candidate.source.name in geometry.creationParticipantNames &&
                            abs(candidate.x - message.toX) < 0.001
                    },
                    offsetX = contentLeft,
                    offsetY = contentTop,
                    context = context,
                )
            } else {
                elements += messageElements(message, contentLeft, contentTop, context)
            }
        }
        geometry.selfCalls.forEach { selfCall ->
            elements += selfCallElements(selfCall, contentLeft, contentTop, context)
        }
        geometry.returns.forEach { returnGeometry ->
            elements += returnElements(returnGeometry, contentLeft, contentTop, context)
        }
        geometry.fragments.forEach { fragment ->
            elements += fragmentElements(fragment, contentLeft, contentTop, context)
        }
        geometry.dividers.forEach { divider ->
            elements += dividerElements(divider, contentLeft, contentTop, context)
        }
        geometry.comments.forEach { comment ->
            val rendered = when (
                val result = MermaidTextPort.render(
                    source = comment.text,
                    labelType = FlowLabelType.Markdown,
                    config = context.options,
                )
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            elements += commentText(comment, rendered, contentLeft, contentTop, context)
        }

        return GMResult.Ok(
            MermaidScene(
                width = viewWidth.toFloat(),
                height = viewHeight.toFloat(),
                background = WHITE,
                elements = elements,
                title = geometry.title,
                accessibilityTitle = document.accessibilityTitle,
                accessibilityDescription = document.accessibilityDescription,
                viewportSizing = MermaidSceneViewportSizing.Intrinsic,
            ),
        )
    }

    private fun participantElements(
        participant: ZenUmlParticipantGeometry,
        offsetX: Double,
        offsetY: Double,
        context: MermaidRenderContext,
    ): List<SceneElement> {
        val source = participant.source
        val centerX = offsetX + participant.x
        val top = offsetY + participant.y
        val fill = source.color
            ?.let { color -> CssColorParser.parse(color.withHashPrefix()) }
            ?: WHITE
        val elements = mutableListOf<SceneElement>()
        elements += shape(
            id = "zenuml-participant-${source.name}",
            left = centerX - participant.width / 2.0 + 1.0,
            top = top + 1.0,
            width = participant.width - 2.0,
            height = participant.height - 2.0,
            kind = SceneShapeKind.RoundedRectangle,
            fill = fill,
            stroke = FRAME_BORDER,
            strokeWidth = 2f,
            cornerRadius = 3f,
            zIndex = 10,
        )
        if (source.name == ZEN_UML_STARTER) {
            elements += vectorShape(
                id = "zenuml-participant-icon-${source.name}",
                left = centerX - 14.0,
                top = top + 8.0,
                width = 24.0,
                height = 24.0,
                definition = ZenUmlParticipantVectors.find("actor") ?: return elements,
                zIndex = 12,
            )
            return elements
        }

        val icon = ZenUmlParticipantVectors.find(source.type)
        val labelWidth = measure(source.displayName, PARTICIPANT_FONT_SIZE, context)
        val stereotypeWidth = source.stereotype?.let { stereotype ->
            measure("«$stereotype»", PARTICIPANT_FONT_SIZE, context)
        } ?: 0.0
        val labelCenterY = top + participant.height / 2.0 - 0.25 +
            if (source.stereotype != null) 8.0 else 0.0
        var labelX = centerX
        var labelAlignment = SceneTextAlignment.Center
        var stereotypeX = centerX
        if (icon != null) {
            val emojiExtra = if (source.emoji != null) 20.0 else 0.0
            val groupWidth = 24.0 + 4.0 + 16.0 + labelWidth + emojiExtra
            val groupX = centerX - groupWidth / 2.0
            val iconX = groupX + 4.0
            val iconY = top + (participant.height - 24.0) / 2.0 +
                if (source.type.equals("boundary", ignoreCase = true)) 2.75 else 0.0
            elements += vectorShape(
                id = "zenuml-participant-icon-${source.name}",
                left = iconX,
                top = iconY,
                width = 24.0,
                height = 24.0,
                definition = icon,
                zIndex = 12,
            )
            val emojiX = iconX + 28.0
            labelX = if (source.emoji != null) emojiX + 24.0 else groupX + 36.0
            labelAlignment = SceneTextAlignment.Start
            stereotypeX = labelX + labelWidth / 2.0
            source.emoji?.let { emoji ->
                elements += text(
                    id = "zenuml-participant-emoji-${source.name}",
                    value = resolveZenUmlEmoji(emoji),
                    x = emojiX,
                    centerY = labelCenterY,
                    fontSize = PARTICIPANT_FONT_SIZE,
                    color = TEXT,
                    alignment = SceneTextAlignment.Start,
                    context = context,
                    zIndex = 13,
                )
            }
        } else if (source.emoji != null) {
            val groupWidth = 20.0 + 8.0 + labelWidth
            val innerColumnWidth = max(groupWidth, stereotypeWidth)
            val groupX = centerX - innerColumnWidth / 2.0
            elements += text(
                id = "zenuml-participant-emoji-${source.name}",
                value = resolveZenUmlEmoji(source.emoji),
                x = groupX,
                centerY = labelCenterY,
                fontSize = PARTICIPANT_FONT_SIZE,
                color = TEXT,
                alignment = SceneTextAlignment.Start,
                context = context,
                zIndex = 13,
            )
            labelX = groupX + 24.0
            labelAlignment = SceneTextAlignment.Start
        }
        source.stereotype?.let { stereotype ->
            elements += text(
                id = "zenuml-participant-stereotype-${source.name}",
                value = "«$stereotype»",
                x = stereotypeX,
                centerY = top + participant.height / 2.0 - 0.25 - 8.0,
                fontSize = PARTICIPANT_FONT_SIZE,
                color = TEXT,
                alignment = SceneTextAlignment.Center,
                context = context,
                zIndex = 13,
            )
        }
        elements += text(
            id = "zenuml-participant-label-${source.name}",
            value = source.displayName,
            x = labelX,
            centerY = labelCenterY,
            fontSize = PARTICIPANT_FONT_SIZE,
            color = TEXT,
            alignment = labelAlignment,
            context = context,
            zIndex = 13,
        )
        return elements
    }

    private fun messageElements(
        geometry: ZenUmlMessageGeometry,
        offsetX: Double,
        offsetY: Double,
        context: MermaidRenderContext,
    ): List<SceneElement> {
        val leftToRight = geometry.fromX < geometry.toX
        val fromX = geometry.fromX + if (leftToRight) 1.0 else 0.0
        val toX = geometry.toX + if (leftToRight) 0.0 else 1.0
        val lineY = geometry.y - 0.5
        val direction = if (leftToRight) 1.0 else -1.0
        val labelX = (geometry.fromX + geometry.toX) / 2.0 - direction * 3.5 + 0.5
        val color = messageColor(geometry.commentStyle)
        val label = resolveZenUmlEmojiInText(geometry.label)
        return buildList {
            add(line(
                id = "${geometry.id}-line",
                fromX = offsetX + fromX,
                fromY = offsetY + lineY,
                toX = offsetX + toX,
                toY = offsetY + lineY,
                color = BLACK,
                width = 2f,
                zIndex = 12,
            ))
            add(
                arrowShape(
                    id = "${geometry.id}-arrow",
                    tipX = offsetX + toX,
                    tipY = offsetY + lineY,
                    pointsLeft = !leftToRight,
                    filled = geometry.kind != ZenUmlMessageKind.Async,
                    zIndex = 13,
                ),
            )
            add(text(
                id = "${geometry.id}-label",
                value = label,
                x = offsetX + labelX,
                centerY = offsetY + geometry.y - 9.0,
                fontSize = MESSAGE_FONT_SIZE,
                color = color,
                weight = if (geometry.commentStyle?.messageBold == true) {
                    SceneTextWeight.Bold
                } else {
                    SceneTextWeight.Normal
                },
                italic = geometry.commentStyle?.messageItalic == true,
                spans = underlineSpan(label, geometry.commentStyle?.messageUnderline == true),
                alignment = SceneTextAlignment.Center,
                context = context,
                zIndex = 15,
            ))
            add(sequenceNumber(
                id = "${geometry.id}-number",
                number = geometry.number,
                x = offsetX + min(fromX, toX) - 4.0,
                centerY = offsetY + geometry.y - 9.0,
                context = context,
            ))
        }
    }

    private fun creationElements(
        geometry: ZenUmlMessageGeometry,
        participant: ZenUmlParticipantGeometry?,
        offsetX: Double,
        offsetY: Double,
        context: MermaidRenderContext,
    ): List<SceneElement> {
        val isRightToLeft = geometry.toX < geometry.fromX
        val targetX = participant?.let { target ->
            if (isRightToLeft) target.x + target.width / 2.0 else target.x - target.width / 2.0
        } ?: geometry.toX
        val fromX = geometry.fromX + if (isRightToLeft) 0.0 else 1.0
        val labelX = fromX + (targetX - fromX) / 2.0 + if (isRightToLeft) 3.5 else -3.0
        val color = messageColor(geometry.commentStyle)
        val label = resolveZenUmlEmojiInText(geometry.label)
        return buildList {
            add(line(
                id = "${geometry.id}-line",
                fromX = offsetX + fromX,
                fromY = offsetY + geometry.y,
                toX = offsetX + targetX,
                toY = offsetY + geometry.y,
                color = BLACK,
                width = 2f,
                strokePattern = SceneStrokePattern.Dashed,
                dashIntervals = listOf(6f, 4f),
                zIndex = 12,
            ))
            add(
                openTipArrowShape(
                    id = "${geometry.id}-arrow",
                    tipX = offsetX + targetX,
                    tipY = offsetY + geometry.y,
                    pointsLeft = isRightToLeft,
                    zIndex = 13,
                ),
            )
            addAll(
                creationLabelElements(
                    id = "${geometry.id}-label",
                    value = label,
                    x = offsetX + labelX,
                    centerY = offsetY + geometry.y - 8.0,
                    color = color,
                    weight = if (geometry.commentStyle?.messageBold == true) {
                        SceneTextWeight.Bold
                    } else {
                        SceneTextWeight.Normal
                    },
                    italic = geometry.commentStyle?.messageItalic == true,
                    underline = geometry.commentStyle?.messageUnderline == true,
                    context = context,
                    zIndex = 15,
                ),
            )
            add(sequenceNumber(
                id = "${geometry.id}-number",
                number = geometry.number,
                x = offsetX + min(fromX, targetX) - 4.0,
                centerY = offsetY + geometry.y - 8.0,
                context = context,
            ))
        }
    }

    private fun selfCallElements(
        geometry: ZenUmlSelfCallGeometry,
        offsetX: Double,
        offsetY: Double,
        context: MermaidRenderContext,
    ): List<SceneElement> {
        val async = geometry.kind == ZenUmlMessageKind.Async
        val svgX = offsetX + geometry.x + 1.0
        val svgY = offsetY + geometry.y + if (async) 20.0 else 14.0
        val label = resolveZenUmlEmojiInText(geometry.label.trim())
        val path = vectorShape(
            id = "${geometry.id}-self-path",
            left = svgX,
            top = svgY,
            width = 30.0,
            height = 24.0,
            definition = ZenUmlVectors.selfCall(async),
            zIndex = 12,
        )
        return listOf(
            path,
            text(
                id = "${geometry.id}-label",
                value = label,
                x = offsetX + geometry.x + 6.0,
                centerY = offsetY + geometry.y + if (async) 9.0 else 7.0,
                fontSize = MESSAGE_FONT_SIZE,
                color = messageColor(geometry.commentStyle),
                weight = if (geometry.commentStyle?.messageBold == true) {
                    SceneTextWeight.Bold
                } else {
                    SceneTextWeight.Normal
                },
                italic = geometry.commentStyle?.messageItalic == true,
                spans = underlineSpan(label, geometry.commentStyle?.messageUnderline == true),
                alignment = SceneTextAlignment.Start,
                context = context,
                zIndex = 15,
            ),
            sequenceNumber(
                id = "${geometry.id}-number",
                number = geometry.number,
                x = offsetX + geometry.x - 3.0,
                centerY = offsetY + geometry.y + 6.0,
                context = context,
            ),
        )
    }

    private fun returnElements(
        geometry: ZenUmlReturnGeometry,
        offsetX: Double,
        offsetY: Double,
        context: MermaidRenderContext,
    ): List<SceneElement> {
        if (geometry.isSelf) {
            return listOf(
                vectorShape(
                    id = "${geometry.id}-icon",
                    left = offsetX + geometry.fromX + 4.0,
                    top = offsetY + geometry.y - 12.0,
                    width = 12.0,
                    height = 12.0,
                    definition = ZenUmlVectors.selfReturn,
                    zIndex = 14,
                ),
                text(
                    id = "${geometry.id}-label",
                    value = resolveZenUmlEmojiInText(geometry.label),
                    x = offsetX + geometry.fromX + 20.0,
                    centerY = offsetY + geometry.y - 6.0,
                    fontSize = MESSAGE_FONT_SIZE,
                    color = TEXT,
                    alignment = SceneTextAlignment.Start,
                    context = context,
                    zIndex = 15,
                ),
            )
        }
        val lineY = floor(geometry.y)
        val labelX = min(geometry.fromX, geometry.toX) +
            abs(geometry.toX - geometry.fromX) / 2.0 +
            if (geometry.toX < geometry.fromX) 3.5 else -3.5
        return listOf(
            line(
                id = "${geometry.id}-line",
                fromX = offsetX + geometry.fromX,
                fromY = offsetY + lineY,
                toX = offsetX + geometry.toX,
                toY = offsetY + lineY,
                color = BLACK,
                width = 2f,
                strokePattern = SceneStrokePattern.Dashed,
                dashIntervals = listOf(6f, 4f),
                zIndex = 12,
            ),
            openTipArrowShape(
                id = "${geometry.id}-arrow",
                tipX = offsetX + geometry.toX,
                tipY = offsetY + lineY,
                pointsLeft = geometry.toX < geometry.fromX,
                zIndex = 13,
            ),
            text(
                id = "${geometry.id}-label",
                value = resolveZenUmlEmojiInText(geometry.label),
                x = offsetX + labelX,
                centerY = offsetY + lineY - 8.0,
                fontSize = MESSAGE_FONT_SIZE,
                color = TEXT,
                alignment = SceneTextAlignment.Center,
                context = context,
                zIndex = 15,
            ),
            sequenceNumber(
                id = "${geometry.id}-number",
                number = geometry.number,
                x = offsetX + min(geometry.fromX, geometry.toX) - 4.0,
                centerY = offsetY + lineY - 8.0,
                context = context,
            ),
        )
    }

    private fun fragmentElements(
        geometry: ZenUmlFragmentGeometry,
        offsetX: Double,
        offsetY: Double,
        context: MermaidRenderContext,
    ): List<SceneElement> {
        val left = offsetX + geometry.x
        val top = offsetY + geometry.y
        val headerTop = offsetY + geometry.headerY
        val elements = mutableListOf<SceneElement>()
        elements += shape(
            id = geometry.id,
            left = left + 0.5,
            top = top + 0.5,
            width = geometry.width - 1.0,
            height = geometry.height - 1.0,
            kind = SceneShapeKind.RoundedRectangle,
            fill = TRANSPARENT,
            stroke = FRAME_BORDER,
            strokeWidth = 1f,
            cornerRadius = 4f,
            zIndex = 14,
        )
        elements += shape(
            id = "${geometry.id}-header",
            left = left + 1.0,
            top = headerTop,
            width = geometry.width - 2.0,
            height = 25.0,
            kind = SceneShapeKind.Rectangle,
            fill = FRAGMENT_HEADER,
            stroke = TRANSPARENT,
            strokeWidth = 0f,
            cornerRadius = 0f,
            zIndex = 14,
        )
        elements += fragmentIcon(geometry, left + 5.0, headerTop)
        elements += text(
            id = "${geometry.id}-kind",
            value = fragmentKindLabel(geometry.kind),
            x = left + 27.0,
            centerY = headerTop + 12.0,
            fontSize = MESSAGE_FONT_SIZE,
            color = BLACK,
            weight = SceneTextWeight.Bold,
            alignment = SceneTextAlignment.Start,
            context = context,
            zIndex = 16,
        )
        elements += sequenceNumber(
            id = "${geometry.id}-number",
            number = geometry.number,
            x = left - 3.0,
            centerY = headerTop + 8.0,
            context = context,
        )
        if (geometry.label.isNotEmpty()) {
            elements += bracketedLabelElements(
                id = "${geometry.id}-condition",
                value = geometry.label,
                measuredInnerWidth = geometry.labelWidth,
                x = left + 1.0,
                centerY = headerTop + 35.0,
                context = context,
            )
        }
        geometry.sections.drop(1).forEachIndexed { index, section ->
            val sectionY = offsetY + section.y + 0.5
            elements += line(
                id = "${geometry.id}-section-$index",
                fromX = left + 1.0 + section.contentInsetLeft,
                fromY = sectionY,
                toX = left + geometry.width - 1.0,
                toY = sectionY,
                color = FRAGMENT_SEPARATOR,
                width = 1f,
                zIndex = 15,
            )
            if (section.label.isNotEmpty()) {
                elements += fragmentSectionLabelElements(
                    id = "${geometry.id}-section-label-$index",
                    section = section,
                    left = left,
                    sectionY = sectionY,
                    context = context,
                )
            }
        }
        return elements
    }

    private fun fragmentSectionLabelElements(
        id: String,
        section: ZenUmlFragmentSectionGeometry,
        left: Double,
        sectionY: Double,
        context: MermaidRenderContext,
    ): List<SceneElement> {
        val centerY = sectionY + 10.0
        val innerLabel = section.innerLabel
        if (innerLabel != null && section.label != "[else]") {
            return bracketedLabelElements(
                id = id,
                value = innerLabel,
                measuredInnerWidth = section.innerLabelWidth,
                x = left + 1.0,
                centerY = centerY,
                context = context,
            )
        }
        val keyword = section.keyword
        val detail = section.detail
        if (keyword != null && detail != null) {
            val keywordWidth = section.keywordWidth ?: measure(keyword, MESSAGE_FONT_SIZE, context)
            val detailWidth = section.detailWidth ?: measure(detail, MESSAGE_FONT_SIZE, context)
            val keywordX = left + 5.0
            val detailX = keywordX + keywordWidth + FRAGMENT_TEXT_PAD_X * 2.0
            return listOf(
                shape(
                    id = "$id-bg",
                    left = keywordX - FRAGMENT_TEXT_PAD_X,
                    top = sectionY + 0.5,
                    width = keywordWidth + detailWidth + FRAGMENT_TEXT_PAD_X * 4.0,
                    height = 20.0,
                    kind = SceneShapeKind.Rectangle,
                    fill = WHITE.withAlpha(FRAGMENT_LABEL_OPACITY),
                    stroke = TRANSPARENT,
                    strokeWidth = 0f,
                    cornerRadius = 0f,
                    zIndex = 16,
                ),
                text(
                    id = "$id-keyword",
                    value = keyword,
                    x = keywordX,
                    centerY = centerY,
                    fontSize = MESSAGE_FONT_SIZE,
                    color = TEXT.withAlpha(FRAGMENT_LABEL_OPACITY),
                    alignment = SceneTextAlignment.Start,
                    context = context,
                    zIndex = 17,
                ),
                text(
                    id = "$id-detail",
                    value = resolveZenUmlEmojiInText(detail),
                    x = detailX,
                    centerY = centerY,
                    fontSize = MESSAGE_FONT_SIZE,
                    color = TEXT.withAlpha(FRAGMENT_LABEL_OPACITY),
                    alignment = SceneTextAlignment.Start,
                    context = context,
                    zIndex = 17,
                ),
            )
        }
        val label = resolveZenUmlEmojiInText(section.label)
        if (section.label == "[else]") {
            return listOf(
                text(
                    id = id,
                    value = label,
                    x = left + 5.0,
                    centerY = centerY,
                    fontSize = MESSAGE_FONT_SIZE,
                    color = BLACK,
                    alignment = SceneTextAlignment.Start,
                    context = context,
                    zIndex = 16,
                ),
            )
        }
        val labelWidth = section.labelWidth ?: measure(label, MESSAGE_FONT_SIZE, context)
        return listOf(
            shape(
                id = "$id-bg",
                left = left + 1.0,
                top = sectionY + 0.5,
                width = labelWidth + FRAGMENT_TEXT_PAD_X * 2.0,
                height = 20.0,
                kind = SceneShapeKind.Rectangle,
                fill = WHITE.withAlpha(FRAGMENT_LABEL_OPACITY),
                stroke = TRANSPARENT,
                strokeWidth = 0f,
                cornerRadius = 0f,
                zIndex = 16,
            ),
            text(
                id = id,
                value = label,
                x = left + 5.0,
                centerY = centerY,
                fontSize = MESSAGE_FONT_SIZE,
                color = BLACK.withAlpha(FRAGMENT_LABEL_OPACITY),
                alignment = SceneTextAlignment.Start,
                context = context,
                zIndex = 17,
            ),
        )
    }

    private fun bracketedLabelElements(
        id: String,
        value: String,
        measuredInnerWidth: Double?,
        x: Double,
        centerY: Double,
        context: MermaidRenderContext,
    ): List<SceneText> {
        val inner = resolveZenUmlEmojiInText(value)
        val innerWidth = measuredInnerWidth ?: measure(inner, MESSAGE_FONT_SIZE, context)
        val innerX = x + FRAGMENT_BRACKET_WIDTH + FRAGMENT_TEXT_PAD_X
        val closeX = innerX + innerWidth + FRAGMENT_TEXT_PAD_X
        return listOf(
            text(
                id = "$id-open",
                value = "[",
                x = x,
                centerY = centerY,
                fontSize = MESSAGE_FONT_SIZE,
                color = BLACK,
                alignment = SceneTextAlignment.Start,
                context = context,
                zIndex = 16,
            ),
            text(
                id = "$id-value",
                value = inner,
                x = innerX,
                centerY = centerY,
                fontSize = MESSAGE_FONT_SIZE,
                color = BLACK.withAlpha(FRAGMENT_LABEL_OPACITY),
                alignment = SceneTextAlignment.Start,
                context = context,
                zIndex = 16,
            ),
            text(
                id = "$id-close",
                value = "]",
                x = closeX,
                centerY = centerY,
                fontSize = MESSAGE_FONT_SIZE,
                color = BLACK,
                alignment = SceneTextAlignment.Start,
                context = context,
                zIndex = 16,
            ),
        )
    }

    private fun fragmentIcon(
        geometry: ZenUmlFragmentGeometry,
        left: Double,
        top: Double,
    ): SceneElement = vectorShape(
        id = "${geometry.id}-icon",
        left = left,
        top = top,
        width = 20.0,
        height = 24.0,
        definition = ZenUmlVectors.fragment(geometry.kind),
        zIndex = 16,
    )

    private fun dividerElements(
        geometry: ZenUmlDividerGeometry,
        offsetX: Double,
        offsetY: Double,
        context: MermaidRenderContext,
    ): List<SceneElement> {
        val label = resolveZenUmlEmojiInText(geometry.label.replace(DIVIDER_TRIM, "").trim())
        val textWidth = measure(label, MESSAGE_FONT_SIZE, context)
        val totalWidth = textWidth + 18.0
        val centerX = geometry.width / 2.0
        val left = centerX - totalWidth / 2.0
        val right = centerX + totalWidth / 2.0
        val y = geometry.y
        return listOf(
            line(
                id = "${geometry.id}-left",
                fromX = offsetX,
                fromY = offsetY + y,
                toX = offsetX + left,
                toY = offsetY + y,
                color = DIVIDER_STROKE,
                width = 1f,
                zIndex = 17,
            ),
            line(
                id = "${geometry.id}-right",
                fromX = offsetX + right,
                fromY = offsetY + y,
                toX = offsetX + geometry.width,
                toY = offsetY + y,
                color = DIVIDER_STROKE,
                width = 1f,
                zIndex = 17,
            ),
            shape(
                id = "${geometry.id}-box",
                left = offsetX + left,
                top = offsetY + y - 14.0,
                width = totalWidth,
                height = 28.0,
                kind = SceneShapeKind.RoundedRectangle,
                fill = DIVIDER_FILL,
                stroke = DIVIDER_STROKE,
                strokeWidth = 1f,
                cornerRadius = 2f,
                zIndex = 18,
            ),
            text(
                id = "${geometry.id}-label",
                value = label,
                x = offsetX + centerX,
                centerY = offsetY + y,
                fontSize = MESSAGE_FONT_SIZE,
                color = COMMENT,
                alignment = SceneTextAlignment.Center,
                context = context,
                zIndex = 19,
            ),
        )
    }

    private fun commentText(
        geometry: ZenUmlCommentGeometry,
        rendered: MermaidRenderedText,
        offsetX: Double,
        offsetY: Double,
        context: MermaidRenderContext,
    ): SceneText {
        val lineCount = max(rendered.text.lines().size, 1)
        val centerY = offsetY + geometry.y - 7.0 + (lineCount - 1) * 10.0
        val color = geometry.style.commentColor
            ?.let(CssColorParser::parse)
            ?: COMMENT
        val spans = rendered.spans + underlineSpan(
            value = rendered.text,
            enabled = geometry.style.commentUnderline,
        )
        return text(
            id = geometry.id,
            value = rendered.text,
            x = offsetX + geometry.x,
            centerY = centerY,
            fontSize = MESSAGE_FONT_SIZE,
            color = color.withAlpha(0.5f),
            weight = if (geometry.style.commentBold) SceneTextWeight.Bold else SceneTextWeight.Normal,
            italic = geometry.style.commentItalic,
            alignment = SceneTextAlignment.Start,
            spans = spans,
            context = context,
            lineHeight = 20f / MESSAGE_FONT_SIZE,
            zIndex = 20,
        )
    }

    private fun sequenceNumber(
        id: String,
        number: String,
        x: Double,
        centerY: Double,
        context: MermaidRenderContext,
    ): SceneText = text(
        id = id,
        value = number,
        x = x,
        centerY = centerY,
        fontSize = 12f,
        color = SEQUENCE_NUMBER,
        weight = SceneTextWeight.Normal,
        alignment = SceneTextAlignment.End,
        context = context,
        zIndex = 15,
    )

    /**
     * @zenuml/core 3.49.2:
     * src/svg/components/creation.ts -> renderGuillemets.
     */
    private fun creationLabelElements(
        id: String,
        value: String,
        x: Double,
        centerY: Double,
        color: SceneColor,
        weight: SceneTextWeight,
        italic: Boolean,
        underline: Boolean,
        context: MermaidRenderContext,
        zIndex: Int,
    ): List<SceneText> {
        val inner = value
            .takeIf { label -> label.length > 2 && label.startsWith("«") && label.endsWith("»") }
            ?.substring(1, value.lastIndex)
        val renderedValue = if (inner != null && inner != "create") {
            // Upstream emits three tspans with a 4px dx before the inner value and close
            // guillemet. Helvetica's 14px space has the same visual width, while keeping
            // the whole SVG <text> node as one SceneText for semantic parity.
            "« $inner »"
        } else {
            value
        }
        return listOf(
            text(
                id = id,
                value = renderedValue,
                x = x,
                centerY = centerY,
                fontSize = MESSAGE_FONT_SIZE,
                color = color,
                weight = weight,
                italic = italic,
                spans = underlineSpan(renderedValue, underline),
                alignment = SceneTextAlignment.Center,
                context = context,
                zIndex = zIndex,
            ),
        )
    }

    private fun underlineSpan(
        value: String,
        enabled: Boolean,
    ): List<SceneTextSpan> =
        if (enabled && value.isNotEmpty()) {
            listOf(SceneTextSpan(start = 0, end = value.length, underline = true))
        } else {
            emptyList()
        }

    private fun text(
        id: String,
        value: String,
        x: Double,
        centerY: Double,
        fontSize: Float,
        color: SceneColor,
        alignment: SceneTextAlignment,
        context: MermaidRenderContext,
        weight: SceneTextWeight = SceneTextWeight.Normal,
        italic: Boolean = false,
        spans: List<SceneTextSpan> = emptyList(),
        lineHeight: Float = 1.2f,
        zIndex: Int,
    ): SceneText {
        val metrics = context.textMetrics.measure(
            TextMetricsRequest(
                text = value,
                fontSize = fontSize,
                maxWidth = MAX_TEXT_WIDTH,
                lineHeight = lineHeight,
                fontFamily = ZEN_UML_FONT_FAMILY,
                weight = weight,
                spans = spans,
            ),
        )
        val width = max(metrics.width.toDouble(), 1.0)
        val height = max(metrics.height.toDouble(), fontSize.toDouble())
        val bounds = when (alignment) {
            SceneTextAlignment.Start -> SceneRect(
                left = (x - 4.0).toFloat(),
                top = (centerY - height / 2.0).toFloat(),
                right = (x + width + 4.0).toFloat(),
                bottom = (centerY + height / 2.0).toFloat(),
            )
            SceneTextAlignment.Center -> SceneRect(
                left = (x - width / 2.0).toFloat(),
                top = (centerY - height / 2.0).toFloat(),
                right = (x + width / 2.0).toFloat(),
                bottom = (centerY + height / 2.0).toFloat(),
            )
            SceneTextAlignment.End -> SceneRect(
                left = (x - width - 4.0).toFloat(),
                top = (centerY - height / 2.0).toFloat(),
                right = (x + 4.0).toFloat(),
                bottom = (centerY + height / 2.0).toFloat(),
            )
        }
        return SceneText(
            text = value,
            bounds = bounds,
            color = color,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontFamily = ZEN_UML_FONT_FAMILY,
            weight = weight,
            italic = italic,
            spans = spans,
            horizontalAlignment = alignment,
            zIndex = zIndex,
            softWrap = false,
        )
    }

    private fun line(
        id: String,
        fromX: Double,
        fromY: Double,
        toX: Double,
        toY: Double,
        color: SceneColor,
        width: Float,
        strokePattern: SceneStrokePattern = SceneStrokePattern.Solid,
        dashIntervals: List<Float> = emptyList(),
        zIndex: Int,
    ): ScenePath {
        val start = ScenePoint(fromX.toFloat(), fromY.toFloat())
        val end = ScenePoint(toX.toFloat(), toY.toFloat())
        return ScenePath(
            id = id,
            points = listOf(start, end),
            commands = listOf(
                ScenePathCommand.MoveTo(start),
                ScenePathCommand.LineTo(end),
            ),
            color = color,
            strokeWidth = width,
            strokePattern = strokePattern,
            curve = "linear",
            look = "classic",
            animated = false,
            dashIntervals = dashIntervals,
            zIndex = zIndex,
        )
    }

    private fun arrowShape(
        id: String,
        tipX: Double,
        tipY: Double,
        pointsLeft: Boolean,
        filled: Boolean,
        zIndex: Int,
    ): SceneShape = vectorShape(
        id = id,
        left = if (pointsLeft) tipX else tipX - ARROW_WIDTH,
        top = tipY - ARROW_HEIGHT / 2.0,
        width = ARROW_WIDTH,
        height = ARROW_HEIGHT,
        definition = ZenUmlVectors.arrow(pointsLeft, filled),
        zIndex = zIndex,
    )

    private fun openTipArrowShape(
        id: String,
        tipX: Double,
        tipY: Double,
        pointsLeft: Boolean,
        zIndex: Int,
    ): SceneShape = vectorShape(
        id = id,
        left = if (pointsLeft) tipX else tipX - OPEN_ARROW_WIDTH,
        top = tipY - OPEN_ARROW_HEIGHT / 2.0,
        width = OPEN_ARROW_WIDTH,
        height = OPEN_ARROW_HEIGHT,
        definition = ZenUmlVectors.openTipArrow(pointsLeft),
        zIndex = zIndex,
    )

    private fun vectorShape(
        id: String,
        left: Double,
        top: Double,
        width: Double,
        height: Double,
        definition: ZenUmlVectorDefinition,
        zIndex: Int,
    ): SceneShape = SceneShape(
        id = id,
        bounds = SceneRect(
            left = left.toFloat(),
            top = top.toFloat(),
            right = (left + width).toFloat(),
            bottom = (top + height).toFloat(),
        ),
        kind = SceneShapeKind.Icon,
        geometry = SceneShapeGeometry(
            paths = definition.paths,
            outline = emptyList(),
            viewBox = definition.viewBox,
            viewportFit = definition.viewportFit,
        ),
        fill = TRANSPARENT,
        stroke = TRANSPARENT,
        strokeWidth = 0f,
        cornerRadius = 0f,
        zIndex = zIndex,
    )

    private fun shape(
        id: String,
        left: Double,
        top: Double,
        width: Double,
        height: Double,
        kind: SceneShapeKind,
        fill: SceneColor,
        stroke: SceneColor,
        strokeWidth: Float,
        cornerRadius: Float,
        zIndex: Int,
        strokePattern: SceneStrokePattern = SceneStrokePattern.Solid,
        dashIntervals: List<Float> = emptyList(),
    ): SceneShape = SceneShape(
        id = id,
        bounds = SceneRect(
            left = left.toFloat(),
            top = top.toFloat(),
            right = (left + width).toFloat(),
            bottom = (top + height).toFloat(),
        ),
        kind = kind,
        fill = fill,
        stroke = stroke,
        strokeWidth = strokeWidth,
        strokePattern = strokePattern,
        dashIntervals = dashIntervals,
        cornerRadius = cornerRadius,
        zIndex = zIndex,
    )

    private fun fragmentKindLabel(kind: ZenUmlFragmentKind): String = when (kind) {
        ZenUmlFragmentKind.Loop -> "Loop"
        ZenUmlFragmentKind.Alt -> "Alt"
        ZenUmlFragmentKind.Par -> "Par"
        ZenUmlFragmentKind.Opt -> "Opt"
        ZenUmlFragmentKind.Section -> "Section"
        ZenUmlFragmentKind.Critical -> "Critical"
        ZenUmlFragmentKind.TryCatchFinally -> "Try"
        ZenUmlFragmentKind.Ref -> "Ref"
    }

    private fun messageColor(style: ZenUmlCommentStyle?): SceneColor =
        style?.messageColor?.let(CssColorParser::parse) ?: BLACK

    private fun String.withHashPrefix(): String = if (startsWith("#")) this else "#$this"

    private fun SceneColor.withAlpha(alpha: Float): SceneColor {
        val clamped = (alpha.coerceIn(0f, 1f) * 255f).toLong()
        return SceneColor((argb and 0x00FFFFFFL) or (clamped shl 24))
    }

    private fun measure(
        text: String,
        fontSize: Float,
        context: MermaidRenderContext,
    ): Double = context.textMetrics.measure(
        TextMetricsRequest(
            text = text,
            fontSize = fontSize,
            maxWidth = MAX_TEXT_WIDTH,
            fontFamily = ZEN_UML_FONT_FAMILY,
            weight = SceneTextWeight.Normal,
        ),
    ).width.toDouble()

    private companion object {
        const val PARTICIPANT_FONT_SIZE = 16f
        const val MESSAGE_FONT_SIZE = 14f
        const val ARROW_WIDTH = 7.0
        const val ARROW_HEIGHT = 10.0
        const val OPEN_ARROW_WIDTH = 5.15
        const val OPEN_ARROW_HEIGHT = 6.5
        const val FRAGMENT_BRACKET_WIDTH = 3.89
        const val FRAGMENT_TEXT_PAD_X = 4.0
        const val FRAGMENT_LABEL_OPACITY = 0.65f
        const val MAX_TEXT_WIDTH = 100_000f
        const val ZEN_UML_FONT_FAMILY = "Helvetica, Verdana, serif"
        val TRANSPARENT = SceneColor(0x00000000)
        val WHITE = SceneColor(0xFFFFFFFF)
        val BLACK = SceneColor(0xFF000000)
        val TEXT = SceneColor(0xFF222222)
        val FRAME_BORDER = SceneColor(0xFF666666)
        val OCCURRENCE_FILL = SceneColor(0xFFDEDEDE)
        val FRAGMENT_HEADER = SceneColor(0x7FDEDEDE)
        val FRAGMENT_SEPARATOR = SceneColor(0xFFE5E7EB)
        val DIVIDER_STROKE = SceneColor(0xFFAAAA33)
        val DIVIDER_FILL = SceneColor(0xFFFFF5AD)
        val COMMENT = SceneColor(0xFF333333)
        val SEQUENCE_NUMBER = SceneColor(0xFF6B7280)
        val DIVIDER_TRIM = Regex("""^=+\s*|\s*=+$""")
    }
}
