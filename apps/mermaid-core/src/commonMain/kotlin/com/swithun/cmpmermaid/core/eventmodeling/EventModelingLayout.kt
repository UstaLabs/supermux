package com.swithun.cmpmermaid.core.eventmodeling

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.SceneElement
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.SceneTextAlignment
import com.swithun.cmpmermaid.core.SceneTextFontFamily
import com.swithun.cmpmermaid.core.SceneTextSpan
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.TextMetricsRequest
import com.swithun.cmpmermaid.core.eventmodeling.upstream.mermaid.EventModelingBox
import com.swithun.cmpmermaid.core.eventmodeling.upstream.mermaid.EventModelingDb
import com.swithun.cmpmermaid.core.eventmodeling.upstream.mermaid.EventModelingDiagramProps
import com.swithun.cmpmermaid.core.eventmodeling.upstream.mermaid.EventModelingRelation
import com.swithun.cmpmermaid.core.eventmodeling.upstream.mermaid.EventModelingSwimlane
import kotlin.math.max

/**
 * Kotlin translation of Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/eventmodeling/renderer.ts -> draw,
 * renderD3Swimlane, renderD3Box, and renderD3Relation.
 */
internal class EventModelingLayout {
    fun layout(
        db: EventModelingDb,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val config = db.config
        if (!config.padding.isFinite()) {
            return configurationError("padding must be finite")
        }
        if (!config.rowHeight.isFinite() || config.rowHeight < 1f) {
            return configurationError("rowHeight must be at least 1")
        }
        val state = when (val built = db.getState(context)) {
            is GMResult.Ok -> built.value
            is GMResult.Err -> return built
        }
        val props = db.getDiagramProps()
        val elements = mutableListOf<SceneElement>()
        state.sortedSwimlanesArray.forEach { swimlane ->
            drawSwimlane(
                swimlane = swimlane,
                maxR = state.maxR,
                props = props,
                context = context,
                elements = elements,
            )
        }
        state.boxes.forEach { box ->
            drawBox(
                box = box,
                props = props,
                context = context,
                elements = elements,
            )
        }
        state.relations.forEachIndexed { index, relation ->
            drawRelation(
                relation = relation,
                index = index,
                props = props,
                context = context,
                elements = elements,
            )
        }

        val contentWidth = if (state.sortedSwimlanesArray.isEmpty()) {
            0f
        } else {
            state.maxR + props.swimlanePadding
        }
        val contentHeight = state.sortedSwimlanesArray.lastOrNull()?.let { swimlane ->
            swimlane.y + swimlane.height
        } ?: 0f
        val padding = config.padding
        val shifted = elements.map { element -> element.translate(padding, padding) }
        return GMResult.Ok(
            MermaidScene(
                width = max(1f, contentWidth + 2f * padding),
                height = max(1f, contentHeight + 2f * padding),
                background = context.theme.background,
                elements = shifted,
                title = db.diagramTitle,
                accessibilityTitle = db.accessibilityTitle,
                accessibilityDescription = db.accessibilityDescription,
                viewportPadding = 0f,
                viewportSizing = if (config.useMaxWidth) {
                    MermaidSceneViewportSizing.ResponsiveMaxWidth
                } else {
                    MermaidSceneViewportSizing.Intrinsic
                },
            ),
        )
    }

    private fun drawSwimlane(
        swimlane: EventModelingSwimlane,
        maxR: Float,
        props: EventModelingDiagramProps,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        elements += SceneShape(
            id = "eventmodeling-swimlane-${swimlane.index}",
            bounds = SceneRect(
                left = 0f,
                top = swimlane.y,
                right = maxR + props.swimlanePadding,
                bottom = swimlane.y + swimlane.height,
            ),
            kind = SceneShapeKind.RoundedRectangle,
            fill = context.theme.eventModeling.swimlaneBackgroundOdd,
            stroke = context.theme.eventModeling.swimlaneBackgroundStroke,
            strokeWidth = 1f,
            cornerRadius = 3f,
            shadow = null,
            zIndex = elements.size + 1,
        )
        val metrics = context.textMetrics.measure(
            TextMetricsRequest(
                text = swimlane.label,
                fontSize = FONT_SIZE,
                maxWidth = props.contentStartX - LANE_LABEL_X,
                fontFamily = context.theme.fontFamily,
                weight = SceneTextWeight.Bold,
            ),
        )
        elements += SceneText(
            text = swimlane.label,
            bounds = SceneRect(
                left = LANE_LABEL_X - START_ALIGNMENT_INSET,
                top = swimlane.y + LANE_LABEL_BASELINE - metrics.height,
                right = props.contentStartX,
                bottom = swimlane.y + LANE_LABEL_BASELINE,
            ),
            color = context.theme.textColor,
            fontSize = FONT_SIZE,
            fontFamily = context.theme.fontFamily,
            weight = SceneTextWeight.Bold,
            horizontalAlignment = SceneTextAlignment.Start,
            softWrap = false,
            zIndex = elements.size + 1,
        )
    }

    private fun drawBox(
        box: EventModelingBox,
        props: EventModelingDiagramProps,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        val top = box.swimlane.y + props.swimlanePadding
        val bounds = SceneRect(
            left = box.x,
            top = top,
            right = box.x + box.dimension.width,
            bottom = top + box.dimension.height,
        )
        elements += SceneShape(
            id = "eventmodeling-box-${box.index}",
            bounds = bounds,
            kind = SceneShapeKind.RoundedRectangle,
            fill = box.visual.fill,
            stroke = box.visual.stroke,
            strokeWidth = 1f,
            cornerRadius = 3f,
            shadow = null,
            zIndex = elements.size + 1,
        )
        val contentBounds = SceneRect(
            left = bounds.left + props.boxPadding,
            top = bounds.top + props.boxPadding,
            right = bounds.right - props.boxPadding,
            bottom = bounds.bottom - props.boxPadding,
        )
        val dataStart = box.text.dataStart
        if (dataStart == null) {
            elements += SceneText(
                text = box.text.displayText,
                bounds = contentBounds,
                color = context.theme.textColor,
                fontSize = FONT_SIZE,
                fontFamily = CLASSIC_FONT_FAMILY,
                weight = SceneTextWeight.Bold,
                horizontalAlignment = SceneTextAlignment.Center,
                softWrap = false,
                clipToBounds = true,
                zIndex = elements.size + 1,
            )
            return
        }

        val name = box.text.displayText.substring(0, box.text.nameLength)
        val data = box.text.displayText.substring(dataStart)
        val nameHeight = measureHeight(name, FONT_SIZE, context)
        val dataHeight = measureHeight(data, CODE_FONT_SIZE, context)
        val groupHeight = nameHeight + BLANK_LINE_HEIGHT + dataHeight
        val groupTop = contentBounds.center.y - groupHeight / 2f
        elements += SceneText(
            text = name,
            bounds = SceneRect(
                left = contentBounds.left,
                top = groupTop,
                right = contentBounds.right,
                bottom = groupTop + nameHeight,
            ),
            color = context.theme.textColor,
            fontSize = FONT_SIZE,
            fontFamily = CLASSIC_FONT_FAMILY,
            weight = SceneTextWeight.Bold,
            horizontalAlignment = SceneTextAlignment.Center,
            softWrap = false,
            clipToBounds = true,
            zIndex = elements.size + 1,
        )
        val dataTop = groupTop + nameHeight + BLANK_LINE_HEIGHT
        elements += SceneText(
            text = data,
            bounds = SceneRect(
                left = contentBounds.left - START_ALIGNMENT_INSET,
                top = dataTop,
                right = contentBounds.right,
                bottom = minOf(contentBounds.bottom, dataTop + dataHeight),
            ),
            color = context.theme.textColor,
            fontSize = CODE_FONT_SIZE,
            fontFamily = CLASSIC_FONT_FAMILY,
            weight = SceneTextWeight.Normal,
            spans = listOf(
                SceneTextSpan(
                    start = 0,
                    end = data.length,
                    fontFamily = SceneTextFontFamily.Monospace,
                ),
            ),
            horizontalAlignment = SceneTextAlignment.Start,
            softWrap = false,
            clipToBounds = true,
            zIndex = elements.size + 1,
        )
    }

    private fun drawRelation(
        relation: EventModelingRelation,
        index: Int,
        props: EventModelingDiagramProps,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        val sourceTop = relation.sourceBox.swimlane.y + props.swimlanePadding
        val targetTop = relation.targetBox.swimlane.y + props.swimlanePadding
        val upwards = sourceTop > targetTop
        val source = ScenePoint(
            x = relation.sourceBox.x + relation.sourceBox.dimension.width * 2f / 3f,
            y = if (upwards) {
                sourceTop
            } else {
                sourceTop + relation.sourceBox.dimension.height
            },
        )
        val target = ScenePoint(
            x = relation.targetBox.x + relation.targetBox.dimension.width / 3f,
            y = if (upwards) {
                targetTop + relation.targetBox.dimension.height
            } else {
                targetTop
            },
        )
        elements += ScenePath(
            id = "eventmodeling-relation-$index",
            points = listOf(source, target),
            commands = listOf(
                ScenePathCommand.MoveTo(source),
                ScenePathCommand.LineTo(target),
            ),
            color = relation.visual.stroke,
            strokeWidth = 1f,
            arrowEnd = SceneArrowHead.Triangle,
            arrowColor = context.theme.eventModeling.arrowhead ?: context.theme.edge,
            curve = "linear",
            look = context.options.look,
            animated = false,
            markerBackground = context.theme.background,
            zIndex = elements.size + 1,
        )
    }

    private fun measureHeight(
        text: String,
        fontSize: Float,
        context: MermaidRenderContext,
    ): Float = context.textMetrics.measure(
        TextMetricsRequest(
            text = text,
            fontSize = fontSize,
            maxWidth = UNWRAPPED_TEXT_WIDTH,
            fontFamily = CLASSIC_FONT_FAMILY,
            weight = SceneTextWeight.Normal,
        ),
    ).height

    private fun ScenePoint.translate(
        dx: Float,
        dy: Float,
    ): ScenePoint = ScenePoint(x + dx, y + dy)

    private fun SceneElement.translate(
        dx: Float,
        dy: Float,
    ): SceneElement = when (this) {
        is SceneShape -> copy(bounds = bounds.translate(dx, dy))
        is SceneText -> copy(
            bounds = bounds.translate(dx, dy),
            rotationPivot = rotationPivot?.translate(dx, dy),
        )
        is ScenePath -> copy(
            points = points.map { point -> point.translate(dx, dy) },
            commands = commands.map { command -> command.translate(dx, dy) },
        )
        else -> this
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

    private fun configurationError(detail: String): GMResult.Err<MermaidError> =
        GMResult.Err(
            MermaidError.Configuration(
                "Mermaid Event Modeling configuration $detail",
            ),
        )

    private companion object {
        const val FONT_SIZE = 16f
        const val CODE_FONT_SIZE = 13f
        const val BLANK_LINE_HEIGHT = 19.2f
        const val LANE_LABEL_X = 30f
        const val LANE_LABEL_BASELINE = 30f
        const val START_ALIGNMENT_INSET = 4f
        const val UNWRAPPED_TEXT_WIDTH = 100_000f
        const val CLASSIC_FONT_FAMILY = "\"trebuchet ms\", verdana, arial, sans-serif"
    }
}
