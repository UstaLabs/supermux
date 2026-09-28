package com.swithun.cmpmermaid.core.sankey

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.CssColorParser
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidSankeyOptions
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.SceneBlendMode
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.SceneElement
import com.swithun.cmpmermaid.core.SceneLinearGradient
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.SceneTextAlignment
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.TextMetricsRequest
import com.swithun.cmpmermaid.core.sankey.upstream.d3.D3SankeyGraph
import com.swithun.cmpmermaid.core.sankey.upstream.d3.D3SankeyLayout
import com.swithun.cmpmermaid.core.sankey.upstream.d3.D3SankeyNode
import com.swithun.cmpmermaid.core.sankey.upstream.mermaid.SankeyDb
import kotlin.math.floor
import kotlin.math.max

/**
 * Kotlin translation of Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/sankey/sankeyRenderer.ts and styles.js.
 */
internal class SankeyLayout {
    fun layout(
        db: SankeyDb,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val config = db.config
        when (val validation = validate(config)) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return validation
        }
        val graph = when (val result = D3SankeyLayout.layout(db.getGraph(), config)) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val nodeColors = graph.nodes.mapIndexed { index, node ->
            config.nodeColors[node.id] ?: TABLEAU_10[index % TABLEAU_10.size]
        }
        val colorByNode = graph.nodes
            .mapIndexed { index, node -> node.id to nodeColors[index] }
            .toMap()
        val elements = mutableListOf<SceneElement>()
        drawNodes(graph, nodeColors, elements)
        drawLabels(graph, config, context, elements)
        when (
            val links = drawLinks(
                graph = graph,
                config = config,
                colorByNode = colorByNode,
                context = context,
                elements = elements,
            )
        ) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return links
        }
        return GMResult.Ok(
            normalizeViewport(
                MermaidScene(
                    width = config.width,
                    height = config.height,
                    background = context.theme.background,
                    elements = elements,
                    title = db.diagramTitle?.takeIf(String::isNotEmpty),
                    accessibilityTitle = db.accessibilityTitle,
                    accessibilityDescription = db.accessibilityDescription,
                    viewportPadding = 0f,
                    viewportSizing = if (config.useMaxWidth) {
                        MermaidSceneViewportSizing.ResponsiveMaxWidth
                    } else {
                        MermaidSceneViewportSizing.Intrinsic
                    },
                ),
            ),
        )
    }

    private fun drawNodes(
        graph: D3SankeyGraph,
        colors: List<SceneColor>,
        elements: MutableList<SceneElement>,
    ) {
        graph.nodes.forEachIndexed { index, node ->
            elements += SceneShape(
                id = "sankey-node-$index",
                bounds = node.bounds(),
                kind = SceneShapeKind.Rectangle,
                fill = colors[index],
                stroke = TRANSPARENT,
                strokeWidth = 0f,
                cornerRadius = 0f,
                shadow = null,
                zIndex = elements.size + 1,
            )
        }
    }

    private fun drawLabels(
        graph: D3SankeyGraph,
        config: MermaidSankeyOptions,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        val centralLayer = graph.nodes.maxByOrNull(D3SankeyNode::value)?.layer ?: 0
        if (config.labelStyle == "outlined") {
            graph.nodes.forEach { node ->
                elements += label(
                    node = node,
                    centralLayer = centralLayer,
                    config = config,
                    context = context,
                    outlined = true,
                    zIndex = elements.size + 1,
                )
            }
        }
        graph.nodes.forEach { node ->
            elements += label(
                node = node,
                centralLayer = centralLayer,
                config = config,
                context = context,
                outlined = false,
                zIndex = elements.size + 1,
            )
        }
    }

    private fun label(
        node: D3SankeyNode,
        centralLayer: Int,
        config: MermaidSankeyOptions,
        context: MermaidRenderContext,
        outlined: Boolean,
        zIndex: Int,
    ): SceneText {
        val placeBefore = if (config.labelStyle == "outlined") {
            node.layer < centralLayer
        } else {
            node.x0 >= config.width / 2f
        }
        val anchorX = if (placeBefore) node.x0.toFloat() - LABEL_GAP else node.x1.toFloat() + LABEL_GAP
        val alignment = if (placeBefore) SceneTextAlignment.End else SceneTextAlignment.Start
        val text = if (config.showValues) {
            "${node.id} ${config.prefix}${formatValue(node.value)}${config.suffix}"
        } else {
            node.id
        }
        val metrics = context.textMetrics.measure(
            TextMetricsRequest(
                text = text,
                fontSize = LABEL_FONT_SIZE,
                maxWidth = UNWRAPPED_TEXT_WIDTH,
                fontFamily = context.theme.fontFamily,
                weight = SceneTextWeight.Normal,
            ),
        )
        val centerY = ((node.y0 + node.y1) / 2.0).toFloat() -
            if (config.showValues) LABEL_BASELINE_OFFSET else 0f
        val bounds = when (alignment) {
            SceneTextAlignment.Start -> SceneRect(
                left = anchorX - TEXT_ALIGNMENT_INSET,
                top = centerY - metrics.height / 2f,
                right = anchorX - TEXT_ALIGNMENT_INSET + metrics.width,
                bottom = centerY + metrics.height / 2f,
            )
            SceneTextAlignment.End -> SceneRect(
                left = anchorX + TEXT_ALIGNMENT_INSET - metrics.width,
                top = centerY - metrics.height / 2f,
                right = anchorX + TEXT_ALIGNMENT_INSET,
                bottom = centerY + metrics.height / 2f,
            )
            SceneTextAlignment.Center -> SceneRect(
                left = anchorX - metrics.width / 2f,
                top = centerY - metrics.height / 2f,
                right = anchorX + metrics.width / 2f,
                bottom = centerY + metrics.height / 2f,
            )
        }
        return SceneText(
            text = text,
            bounds = bounds,
            color = context.theme.textColor,
            fontSize = LABEL_FONT_SIZE,
            fontFamily = context.theme.fontFamily,
            weight = SceneTextWeight.Normal,
            horizontalAlignment = alignment,
            softWrap = false,
            outlineColor = if (outlined) context.theme.nodeFill else null,
            outlineWidth = if (outlined) OUTLINE_WIDTH else 0f,
            zIndex = zIndex,
        )
    }

    private fun drawLinks(
        graph: D3SankeyGraph,
        config: MermaidSankeyOptions,
        colorByNode: Map<String, SceneColor>,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ): GMResult<Unit, MermaidError> {
        val fixedColor = if (
            config.linkColor != "gradient" &&
            config.linkColor != "source" &&
            config.linkColor != "target"
        ) {
            CssColorParser.parse(config.linkColor)
                ?: return configurationError("linkColor '${config.linkColor}' is invalid")
        } else {
            null
        }
        graph.links.forEach { link ->
            val sourceColor = colorByNode.getValue(link.source.id)
            val targetColor = colorByNode.getValue(link.target.id)
            val start = ScenePoint(link.source.x1.toFloat(), link.y0.toFloat())
            val end = ScenePoint(link.target.x0.toFloat(), link.y1.toFloat())
            val middleX = (start.x + end.x) / 2f
            elements += ScenePath(
                id = "sankey-link-${link.index}",
                points = listOf(
                    start,
                    ScenePoint(middleX, start.y),
                    ScenePoint(middleX, end.y),
                    end,
                ),
                commands = listOf(
                    ScenePathCommand.MoveTo(start),
                    ScenePathCommand.CubicTo(
                        control1 = ScenePoint(middleX, start.y),
                        control2 = ScenePoint(middleX, end.y),
                        end = end,
                    ),
                ),
                color = when (config.linkColor) {
                    "target" -> targetColor
                    "gradient", "source" -> sourceColor
                    else -> fixedColor ?: sourceColor
                },
                strokeWidth = max(1.0, link.width).toFloat(),
                strokePattern = SceneStrokePattern.Solid,
                arrowStart = SceneArrowHead.None,
                arrowEnd = SceneArrowHead.None,
                curve = "bumpX",
                look = context.options.look,
                animated = false,
                strokeGradient = if (config.linkColor == "gradient") {
                    SceneLinearGradient(sourceColor, targetColor)
                } else {
                    null
                },
                opacity = LINK_OPACITY,
                blendMode = SceneBlendMode.Multiply,
                zIndex = elements.size + 1,
            )
        }
        return GMResult.Ok(Unit)
    }

    private fun normalizeViewport(scene: MermaidScene): MermaidScene {
        val content = scene.elements.mapNotNull { element ->
            when (element) {
                is SceneShape -> element.bounds
                is SceneText -> element.paintedBounds()
                is ScenePath -> element.points.boundingRect()
                else -> null
            }
        }.reduceOrNull(SceneRect::union) ?: return scene
        return scene.copy(
            width = content.width.coerceAtLeast(1f),
            height = content.height.coerceAtLeast(1f),
            elements = scene.elements.map { element ->
                element.translate(-content.left, -content.top)
            },
        )
    }

    private fun SceneText.paintedBounds(): SceneRect = when (horizontalAlignment) {
        SceneTextAlignment.Start -> bounds.translate(TEXT_ALIGNMENT_INSET, 0f)
        SceneTextAlignment.Center -> bounds
        SceneTextAlignment.End -> bounds.translate(-TEXT_ALIGNMENT_INSET, 0f)
    }

    private fun SceneElement.translate(
        dx: Float,
        dy: Float,
    ): SceneElement = when (this) {
        is SceneShape -> copy(bounds = bounds.translate(dx, dy))
        is SceneText -> copy(bounds = bounds.translate(dx, dy))
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

    private fun ScenePoint.translate(
        dx: Float,
        dy: Float,
    ): ScenePoint = ScenePoint(x + dx, y + dy)

    private fun D3SankeyNode.bounds(): SceneRect = SceneRect(
        left = x0.toFloat(),
        top = y0.toFloat(),
        right = x1.toFloat(),
        bottom = y1.toFloat(),
    )

    private fun List<ScenePoint>.boundingRect(): SceneRect? {
        if (isEmpty()) {
            return null
        }
        return SceneRect(
            left = minOf(ScenePoint::x),
            top = minOf(ScenePoint::y),
            right = maxOf(ScenePoint::x),
            bottom = maxOf(ScenePoint::y),
        )
    }

    private fun validate(
        config: MermaidSankeyOptions,
    ): GMResult<Unit, MermaidError> {
        listOf(
            "width" to config.width,
            "height" to config.height,
        ).firstOrNull { (_, value) -> !value.isFinite() || value <= 0f }?.let { invalid ->
            return configurationError("${invalid.first} must be positive")
        }
        listOf(
            "nodeWidth" to config.nodeWidth,
            "nodePadding" to config.nodePadding,
        ).firstOrNull { (_, value) -> !value.isFinite() || value < 0f }?.let { invalid ->
            return configurationError("${invalid.first} must be non-negative")
        }
        if (config.nodeAlignment !in NODE_ALIGNMENTS) {
            return configurationError("nodeAlignment '${config.nodeAlignment}' is invalid")
        }
        if (config.labelStyle !in LABEL_STYLES) {
            return configurationError("labelStyle '${config.labelStyle}' is invalid")
        }
        return GMResult.Ok(Unit)
    }

    private fun formatValue(value: Double): String {
        // JavaScript Math.round used by sankeyRenderer.ts rounds half values toward +infinity.
        val rounded = floor(value * 100.0 + 0.5) / 100.0
        return if (
            rounded.isFinite() &&
            rounded >= Long.MIN_VALUE.toDouble() &&
            rounded <= Long.MAX_VALUE.toDouble() &&
            rounded == rounded.toLong().toDouble()
        ) {
            rounded.toLong().toString()
        } else {
            rounded.toString()
        }
    }

    private fun <T> configurationError(message: String): GMResult<T, MermaidError> =
        GMResult.Err(MermaidError.Configuration("Mermaid Sankey $message"))

    private companion object {
        val TRANSPARENT = SceneColor(0x00000000)
        val TABLEAU_10 = listOf(
            0xFF4E79A7,
            0xFFF28E2C,
            0xFFE15759,
            0xFF76B7B2,
            0xFF59A14F,
            0xFFEDC949,
            0xFFAF7AA1,
            0xFFFF9DA7,
            0xFF9C755F,
            0xFFBAB0AB,
        ).map(::SceneColor)
        val NODE_ALIGNMENTS = setOf("left", "right", "center", "justify")
        val LABEL_STYLES = setOf("legacy", "outlined")
        const val LABEL_FONT_SIZE = 14f
        const val LABEL_GAP = 6f
        const val LABEL_BASELINE_OFFSET = 5f
        const val TEXT_ALIGNMENT_INSET = 4f
        const val OUTLINE_WIDTH = 4f
        const val LINK_OPACITY = 0.5f
        const val UNWRAPPED_TEXT_WIDTH = 100_000f
    }
}
