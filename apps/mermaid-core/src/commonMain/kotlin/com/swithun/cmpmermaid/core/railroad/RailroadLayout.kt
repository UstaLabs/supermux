package com.swithun.cmpmermaid.core.railroad

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRailroadOptions
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
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.SceneTextAlignment
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.TextMetrics
import com.swithun.cmpmermaid.core.TextMetricsRequest
import com.swithun.cmpmermaid.core.railroad.upstream.mermaid.RailroadAstNode
import com.swithun.cmpmermaid.core.railroad.upstream.mermaid.RailroadDb
import com.swithun.cmpmermaid.core.railroad.upstream.mermaid.RailroadRule
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * Kotlin translation of Mermaid.js 12.0.0
 * packages/mermaid/src/diagrams/railroad/railroadRenderer.ts and styles.ts.
 */
internal class RailroadLayout {
    fun layout(
        db: RailroadDb,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val style = when (val result = RailroadStyle.resolve(db.config, context)) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val renderer = Renderer(style, context)
        val rendered = renderer.renderDiagram(db.getRules())
        return GMResult.Ok(
            MermaidScene(
                width = rendered.width,
                height = rendered.height,
                background = context.theme.background,
                elements = rendered.elements.mapIndexed { index, element ->
                    element.withZIndex(index + 1)
                },
                title = db.diagramTitle,
                accessibilityTitle = db.accessibilityTitle,
                accessibilityDescription = db.accessibilityDescription,
                viewportSizing = if (db.config.useMaxWidth) {
                    MermaidSceneViewportSizing.ResponsiveMaxWidth
                } else {
                    MermaidSceneViewportSizing.Intrinsic
                },
            ),
        )
    }

    private inner class Renderer(
        private val style: RailroadStyle,
        private val context: MermaidRenderContext,
    ) {
        private val textCache = mutableMapOf<Pair<String, SceneTextWeight>, TextMetrics>()
        private var nextId = 0

        fun renderDiagram(rules: List<RailroadRule>): DiagramResult {
            if (rules.isEmpty()) {
                return DiagramResult(
                    width = EMPTY_WIDTH,
                    height = EMPTY_HEIGHT,
                    elements = emptyList(),
                )
            }
            var y = style.padding
            var maxWidth = 0f
            val elements = mutableListOf<SceneElement>()
            rules.forEach { rule ->
                val rendered = renderRule(rule, y)
                elements += rendered.elements
                y += rendered.height + style.verticalSeparation
                maxWidth = max(maxWidth, rendered.width)
            }
            return DiagramResult(
                width = maxWidth + style.padding * 2f,
                height = y + style.padding,
                elements = elements,
            )
        }

        private fun renderRule(
            rule: RailroadRule,
            y: Float,
        ): RenderResult {
            val ruleName = "${rule.name} ="
            val nameWidth = measure(ruleName, SceneTextWeight.Normal).width + 20f
            val definitionX = nameWidth + 20f
            val definition = renderExpression(rule.definition)
            val baselineY = max(20f, definition.up)
            val definitionY = baselineY - definition.up
            val elements = mutableListOf<SceneElement>()
            elements += definition.elements.translate(definitionX, definitionY + y)

            val nameMetrics = measure(ruleName, SceneTextWeight.Bold)
            elements += SceneText(
                text = ruleName,
                bounds = SceneRect(
                    left = 0f,
                    top = y + baselineY - nameMetrics.height,
                    right = nameMetrics.width,
                    bottom = y + baselineY,
                ),
                color = style.ruleNameColor,
                fontSize = style.fontSize,
                fontFamily = style.fontFamily,
                weight = SceneTextWeight.Bold,
                horizontalAlignment = SceneTextAlignment.Start,
                softWrap = false,
            )

            elements += circle(
                center = ScenePoint(nameWidth, y + baselineY),
                radius = style.markerRadius,
            )
            elements += circle(
                center = ScenePoint(
                    definitionX + definition.width + 10f,
                    y + baselineY,
                ),
                radius = style.markerRadius,
            )
            elements += line(
                listOf(
                    ScenePathCommand.MoveTo(
                        ScenePoint(nameWidth + style.markerRadius, y + baselineY),
                    ),
                    ScenePathCommand.LineTo(ScenePoint(definitionX, y + baselineY)),
                ),
            )
            elements += line(
                listOf(
                    ScenePathCommand.MoveTo(
                        ScenePoint(definitionX + definition.width, y + baselineY),
                    ),
                    ScenePathCommand.LineTo(
                        ScenePoint(
                            definitionX + definition.width + 10f - style.markerRadius,
                            y + baselineY,
                        ),
                    ),
                ),
            )
            return RenderResult(
                width = definitionX + definition.width + 10f + style.markerRadius,
                height = max(
                    40f,
                    definitionY + definition.height + style.padding * 2f,
                ),
                up = baselineY,
                down = 0f,
                elements = elements,
            )
        }

        private fun renderExpression(node: RailroadAstNode): RenderResult = when (node) {
            is RailroadAstNode.Terminal -> renderBox(
                value = node.value,
                rounded = true,
                special = false,
            )
            is RailroadAstNode.NonTerminal -> renderBox(
                value = node.name,
                rounded = false,
                special = false,
            )
            is RailroadAstNode.Sequence -> renderSequence(node.elements)
            is RailroadAstNode.Choice -> renderChoice(node.alternatives)
            is RailroadAstNode.Optional -> renderOptional(node.element)
            is RailroadAstNode.Repetition -> renderRepetition(node.element, node.min)
            is RailroadAstNode.Special -> renderBox(
                value = "? ${node.text} ?",
                rounded = false,
                special = true,
            )
        }

        private fun renderBox(
            value: String,
            rounded: Boolean,
            special: Boolean,
        ): RenderResult {
            val metrics = measure(value, SceneTextWeight.Normal)
            val width = metrics.width + style.padding * 2f
            val height = metrics.height + style.padding * 2f
            val fill = when {
                special -> style.specialFill
                rounded -> style.terminalFill
                else -> style.nonTerminalFill
            }
            val stroke = when {
                special -> style.specialStroke
                rounded -> style.terminalStroke
                else -> style.nonTerminalStroke
            }
            val textColor = if (rounded) {
                style.terminalTextColor
            } else {
                style.nonTerminalTextColor
            }
            return RenderResult(
                width = width,
                height = height,
                up = height / 2f,
                down = height / 2f,
                elements = listOf(
                    SceneShape(
                        id = id(if (special) "special" else if (rounded) "terminal" else "nonterminal"),
                        bounds = SceneRect(0f, 0f, width, height),
                        kind = if (rounded) {
                            SceneShapeKind.RoundedRectangle
                        } else {
                            SceneShapeKind.Rectangle
                        },
                        fill = fill,
                        stroke = stroke,
                        strokeWidth = style.strokeWidth,
                        strokePattern = if (special) {
                            SceneStrokePattern.Dashed
                        } else {
                            SceneStrokePattern.Solid
                        },
                        dashIntervals = if (special) listOf(5f, 3f) else emptyList(),
                        cornerRadius = if (rounded) 10f else 0f,
                        shadow = null,
                    ),
                    centeredText(
                        value = value,
                        centerX = width / 2f,
                        centerY = height / 2f,
                        color = textColor,
                        metrics = metrics,
                        weight = SceneTextWeight.Normal,
                    ),
                ),
            )
        }

        private fun renderSequence(
            nodes: List<RailroadAstNode>,
        ): RenderResult {
            val rendered = nodes.map(::renderExpression)
            val maxUp = rendered.maxOfOrNull(RenderResult::up) ?: 0f
            val maxDown = rendered.maxOfOrNull(RenderResult::down) ?: 0f
            val width = rendered.sumOf { result -> result.width.toDouble() }.toFloat() +
                (rendered.size - 1).coerceAtLeast(0) * style.horizontalSeparation
            val elements = mutableListOf<SceneElement>()
            var x = 0f
            rendered.forEachIndexed { index, child ->
                val y = maxUp - child.up
                elements += child.elements.translate(x, y)
                if (index < rendered.lastIndex) {
                    val lineX1 = x + child.width
                    val lineX2 = lineX1 + style.horizontalSeparation
                    elements += line(
                        listOf(
                            ScenePathCommand.MoveTo(ScenePoint(lineX1, maxUp)),
                            ScenePathCommand.LineTo(ScenePoint(lineX2, maxUp)),
                        ),
                    )
                }
                x += child.width + style.horizontalSeparation
            }
            return RenderResult(
                width = width,
                height = maxUp + maxDown,
                up = maxUp,
                down = maxDown,
                elements = elements,
            )
        }

        private fun renderChoice(
            nodes: List<RailroadAstNode>,
        ): RenderResult {
            val rendered = nodes.map(::renderExpression)
            val maxWidth = rendered.maxOfOrNull(RenderResult::width) ?: 0f
            val totalHeight = rendered.sumOf { result -> result.height.toDouble() }.toFloat() +
                (rendered.size - 1).coerceAtLeast(0) * style.verticalSeparation
            val arcRadius = style.arcRadius
            val totalWidth = maxWidth + arcRadius * 4f
            val centerY = totalHeight / 2f
            val elements = mutableListOf<SceneElement>()
            var y = 0f
            rendered.forEach { child ->
                val elementCenterY = y + child.up
                val elementX = arcRadius * 2f + (maxWidth - child.width) / 2f
                elements += child.elements.translate(elementX, y)
                elements += choiceEntryPath(
                    elementX = elementX,
                    elementCenterY = elementCenterY,
                    centerY = centerY,
                    radius = arcRadius,
                )
                elements += choiceExitPath(
                    child = child,
                    elementX = elementX,
                    elementCenterY = elementCenterY,
                    centerY = centerY,
                    totalWidth = totalWidth,
                    radius = arcRadius,
                )
                y += child.height + style.verticalSeparation
            }
            return RenderResult(
                width = totalWidth,
                height = totalHeight,
                up = centerY,
                down = totalHeight - centerY,
                elements = elements,
            )
        }

        private fun choiceEntryPath(
            elementX: Float,
            elementCenterY: Float,
            centerY: Float,
            radius: Float,
        ): ScenePath {
            val builder = PathBuilder().moveTo(0f, centerY)
            if (elementCenterY == centerY) {
                builder.lineTo(elementX, elementCenterY)
            } else {
                val below = elementCenterY > centerY
                builder
                    .arcTo(
                        radius = radius,
                        clockwise = below,
                        x = radius,
                        y = centerY + if (below) radius else -radius,
                    )
                    .lineTo(
                        radius,
                        elementCenterY - if (below) radius else -radius,
                    )
                    .arcTo(
                        radius = radius,
                        clockwise = !below,
                        x = radius * 2f,
                        y = elementCenterY,
                    )
                    .lineTo(elementX, elementCenterY)
            }
            return line(builder.build())
        }

        private fun choiceExitPath(
            child: RenderResult,
            elementX: Float,
            elementCenterY: Float,
            centerY: Float,
            totalWidth: Float,
            radius: Float,
        ): ScenePath {
            val rightStart = elementX + child.width
            val rightLaneX = totalWidth - radius * 2f
            val builder = PathBuilder().moveTo(rightStart, elementCenterY)
            if (elementCenterY == centerY) {
                builder.lineTo(totalWidth, centerY)
            } else {
                val below = elementCenterY > centerY
                builder
                    .lineTo(rightLaneX, elementCenterY)
                    .arcTo(
                        radius = radius,
                        clockwise = !below,
                        x = totalWidth - radius,
                        y = elementCenterY + if (below) -radius else radius,
                    )
                    .lineTo(
                        totalWidth - radius,
                        centerY + if (below) radius else -radius,
                    )
                    .arcTo(
                        radius = radius,
                        clockwise = below,
                        x = totalWidth,
                        y = centerY,
                    )
            }
            return line(builder.build())
        }

        private fun renderOptional(node: RailroadAstNode): RenderResult {
            val inner = renderExpression(node)
            val radius = style.arcRadius
            val arcHeight = radius * 2f
            val totalWidth = inner.width + radius * 4f
            val totalHeight = inner.height + arcHeight
            val elementX = radius * 2f
            val elementY = arcHeight
            val centerY = elementY + inner.up
            val elements = mutableListOf<SceneElement>()
            elements += inner.elements.translate(elementX, elementY)
            elements += line(
                PathBuilder()
                    .moveTo(0f, centerY)
                    .lineTo(radius * 2f, centerY)
                    .build(),
            )
            elements += line(
                PathBuilder()
                    .moveTo(elementX + inner.width, centerY)
                    .lineTo(totalWidth, centerY)
                    .build(),
            )
            elements += line(
                PathBuilder()
                    .moveTo(0f, centerY)
                    .arcTo(radius, clockwise = false, radius, centerY - radius)
                    .lineTo(radius, radius)
                    .arcTo(radius, clockwise = true, radius * 2f, 0f)
                    .lineTo(totalWidth - radius * 2f, 0f)
                    .arcTo(radius, clockwise = true, totalWidth - radius, radius)
                    .lineTo(totalWidth - radius, centerY - radius)
                    .arcTo(radius, clockwise = false, totalWidth, centerY)
                    .build(),
            )
            return RenderResult(
                width = totalWidth,
                height = totalHeight,
                up = centerY,
                down = totalHeight - centerY,
                elements = elements,
            )
        }

        private fun renderRepetition(
            node: RailroadAstNode,
            minimum: Int,
        ): RenderResult {
            val inner = renderExpression(node)
            val radius = style.arcRadius
            val arcHeight = radius * 2f
            val hasBypass = minimum == 0
            val totalWidth = inner.width + radius * 4f
            val totalHeight = inner.height + arcHeight + if (hasBypass) arcHeight else 0f
            val elementX = radius * 2f
            val elementY = if (hasBypass) arcHeight else 0f
            val centerY = elementY + inner.up
            val elements = mutableListOf<SceneElement>()
            elements += inner.elements.translate(elementX, elementY)
            elements += line(
                PathBuilder()
                    .moveTo(0f, centerY)
                    .lineTo(radius * 2f, centerY)
                    .build(),
            )
            elements += line(
                PathBuilder()
                    .moveTo(elementX + inner.width, centerY)
                    .lineTo(totalWidth, centerY)
                    .build(),
            )
            val loopY = elementY + inner.height + radius
            elements += line(
                PathBuilder()
                    .moveTo(elementX + inner.width, centerY)
                    .arcTo(
                        radius,
                        clockwise = true,
                        elementX + inner.width + radius,
                        centerY + radius,
                    )
                    .lineTo(elementX + inner.width + radius, loopY)
                    .arcTo(
                        radius,
                        clockwise = true,
                        elementX + inner.width,
                        loopY + radius,
                    )
                    .lineTo(radius * 2f, loopY + radius)
                    .arcTo(radius, clockwise = true, radius, loopY)
                    .lineTo(radius, centerY + radius)
                    .arcTo(radius, clockwise = true, radius * 2f, centerY)
                    .build(),
            )
            if (hasBypass) {
                elements += line(
                    PathBuilder()
                        .moveTo(0f, centerY)
                        .arcTo(radius, clockwise = false, radius, centerY - radius)
                        .lineTo(radius, radius)
                        .arcTo(radius, clockwise = true, radius * 2f, 0f)
                        .lineTo(totalWidth - radius * 2f, 0f)
                        .arcTo(radius, clockwise = true, totalWidth - radius, radius)
                        .lineTo(totalWidth - radius, centerY - radius)
                        .arcTo(radius, clockwise = false, totalWidth, centerY)
                        .build(),
                )
            }
            return RenderResult(
                width = totalWidth,
                height = totalHeight,
                up = centerY,
                down = totalHeight - centerY,
                elements = elements,
            )
        }

        private fun centeredText(
            value: String,
            centerX: Float,
            centerY: Float,
            color: SceneColor,
            metrics: TextMetrics,
            weight: SceneTextWeight,
        ): SceneText = SceneText(
            text = value,
            bounds = SceneRect(
                left = centerX - metrics.width / 2f,
                top = centerY - metrics.height / 2f,
                right = centerX + metrics.width / 2f,
                bottom = centerY + metrics.height / 2f,
            ),
            color = color,
            fontSize = style.fontSize,
            fontFamily = style.fontFamily,
            weight = weight,
            horizontalAlignment = SceneTextAlignment.Center,
            softWrap = false,
        )

        private fun circle(
            center: ScenePoint,
            radius: Float,
        ): SceneShape = SceneShape(
            id = id("marker"),
            bounds = SceneRect(
                left = center.x - radius,
                top = center.y - radius,
                right = center.x + radius,
                bottom = center.y + radius,
            ),
            kind = SceneShapeKind.Circle,
            fill = style.markerFill,
            stroke = TRANSPARENT,
            strokeWidth = 0f,
            cornerRadius = radius,
            shadow = null,
        )

        private fun line(commands: List<ScenePathCommand>): ScenePath = ScenePath(
            id = id("line"),
            points = commands.flatMap { command ->
                when (command) {
                    is ScenePathCommand.MoveTo -> listOf(command.point)
                    is ScenePathCommand.LineTo -> listOf(command.point)
                    is ScenePathCommand.QuadraticTo -> listOf(command.control, command.end)
                    is ScenePathCommand.CubicTo ->
                        listOf(command.control1, command.control2, command.end)
                    is ScenePathCommand.ArcTo -> listOf(command.end)
                }
            },
            commands = commands,
            color = style.lineColor,
            strokeWidth = style.strokeWidth,
            look = "classic",
            animated = false,
        )

        private fun measure(
            text: String,
            weight: SceneTextWeight,
        ): TextMetrics = textCache.getOrPut(text to weight) {
            context.textMetrics.measure(
                TextMetricsRequest(
                    text = text,
                    fontSize = style.fontSize,
                    maxWidth = UNWRAPPED_TEXT_WIDTH,
                    fontFamily = style.fontFamily,
                    weight = weight,
                ),
            )
        }

        private fun id(role: String): String {
            nextId += 1
            return "railroad-$role-$nextId"
        }
    }

    private class PathBuilder {
        private val commands = mutableListOf<ScenePathCommand>()
        private var current: ScenePoint? = null

        fun moveTo(
            x: Float,
            y: Float,
        ): PathBuilder {
            val point = ScenePoint(x, y)
            commands += ScenePathCommand.MoveTo(point)
            current = point
            return this
        }

        fun lineTo(
            x: Float,
            y: Float,
        ): PathBuilder {
            val point = ScenePoint(x, y)
            commands += ScenePathCommand.LineTo(point)
            current = point
            return this
        }

        fun arcTo(
            radius: Float,
            clockwise: Boolean,
            x: Float,
            y: Float,
        ): PathBuilder {
            val start = current
            val end = ScenePoint(x, y)
            if (start == null || radius <= 0f || start == end) {
                return lineTo(x, y)
            }
            val firstCenter = ScenePoint(start.x, end.y)
            val firstCross = cross(
                start.x - firstCenter.x,
                start.y - firstCenter.y,
                end.x - firstCenter.x,
                end.y - firstCenter.y,
            )
            val center = if ((firstCross > 0f) == clockwise) {
                firstCenter
            } else {
                ScenePoint(end.x, start.y)
            }
            val startAngle = atan2(
                (start.y - center.y).toDouble(),
                (start.x - center.x).toDouble(),
            )
            var endAngle = atan2(
                (end.y - center.y).toDouble(),
                (end.x - center.x).toDouble(),
            )
            if (clockwise) {
                while (endAngle <= startAngle) {
                    endAngle += PI * 2.0
                }
            } else {
                while (endAngle >= startAngle) {
                    endAngle -= PI * 2.0
                }
            }
            val delta = endAngle - startAngle
            val tangent = (4.0 / 3.0 * tan(delta / 4.0)).toFloat()
            val control1 = ScenePoint(
                x = start.x - tangent * radius * sin(startAngle).toFloat(),
                y = start.y + tangent * radius * cos(startAngle).toFloat(),
            )
            val control2 = ScenePoint(
                x = end.x + tangent * radius * sin(endAngle).toFloat(),
                y = end.y - tangent * radius * cos(endAngle).toFloat(),
            )
            commands += ScenePathCommand.CubicTo(control1, control2, end)
            current = end
            return this
        }

        fun build(): List<ScenePathCommand> = commands.toList()

        private fun cross(
            ax: Float,
            ay: Float,
            bx: Float,
            by: Float,
        ): Float = ax * by - ay * bx
    }

    private data class RenderResult(
        val width: Float,
        val height: Float,
        val up: Float,
        val down: Float,
        val elements: List<SceneElement>,
    )

    private data class DiagramResult(
        val width: Float,
        val height: Float,
        val elements: List<SceneElement>,
    )

    private data class RailroadStyle(
        val padding: Float,
        val verticalSeparation: Float,
        val horizontalSeparation: Float,
        val arcRadius: Float,
        val fontSize: Float,
        val fontFamily: String,
        val terminalFill: SceneColor,
        val terminalStroke: SceneColor,
        val terminalTextColor: SceneColor,
        val nonTerminalFill: SceneColor,
        val nonTerminalStroke: SceneColor,
        val nonTerminalTextColor: SceneColor,
        val lineColor: SceneColor,
        val strokeWidth: Float,
        val markerFill: SceneColor,
        val specialFill: SceneColor,
        val specialStroke: SceneColor,
        val ruleNameColor: SceneColor,
        val markerRadius: Float,
    ) {
        companion object {
            fun resolve(
                options: MermaidRailroadOptions,
                context: MermaidRenderContext,
            ): GMResult<RailroadStyle, MermaidError> {
                val numeric = listOf(
                    "padding" to options.padding,
                    "verticalSeparation" to options.verticalSeparation,
                    "horizontalSeparation" to options.horizontalSeparation,
                    "arcRadius" to options.arcRadius,
                    "strokeWidth" to options.strokeWidth,
                    "markerRadius" to options.markerRadius,
                ) + listOfNotNull(options.fontSize?.let { "fontSize" to it })
                numeric.firstOrNull { (_, value) -> !value.isFinite() || value < 0f }
                    ?.let { invalid ->
                        return GMResult.Err(
                            MermaidError.Configuration(
                                "Mermaid Railroad ${invalid.first} must be finite and non-negative",
                            ),
                        )
                    }
                return GMResult.Ok(
                    RailroadStyle(
                        padding = options.padding,
                        verticalSeparation = options.verticalSeparation,
                        horizontalSeparation = options.horizontalSeparation,
                        arcRadius = options.arcRadius,
                        fontSize = options.fontSize ?: context.theme.fontSize,
                        fontFamily = options.fontFamily ?: context.theme.fontFamily,
                        terminalFill = options.terminalFill ?: context.theme.groupFill,
                        terminalStroke = options.terminalStroke ?: context.theme.groupStroke,
                        terminalTextColor =
                            options.terminalTextColor ?: context.theme.groupText,
                        nonTerminalFill = options.nonTerminalFill ?: context.theme.nodeFill,
                        nonTerminalStroke =
                            options.nonTerminalStroke ?: context.theme.nodeStroke,
                        nonTerminalTextColor =
                            options.nonTerminalTextColor ?: context.theme.nodeText,
                        lineColor = options.lineColor ?: context.theme.edge,
                        strokeWidth = options.strokeWidth,
                        markerFill = options.markerFill ?: context.theme.edge,
                        specialFill = options.specialFill ?: context.theme.noteFill,
                        specialStroke = options.specialStroke ?: context.theme.noteStroke,
                        ruleNameColor = options.ruleNameColor ?: context.theme.nodeText,
                        markerRadius = options.markerRadius,
                    ),
                )
            }
        }
    }

    private fun List<SceneElement>.translate(
        dx: Float,
        dy: Float,
    ): List<SceneElement> = map { element ->
        when (element) {
            is SceneShape -> element.copy(bounds = element.bounds.translate(dx, dy))
            is SceneText -> element.copy(
                bounds = element.bounds.translate(dx, dy),
                rotationPivot = element.rotationPivot?.translate(dx, dy),
            )
            is ScenePath -> element.copy(
                points = element.points.map { point -> point.translate(dx, dy) },
                commands = element.commands.map { command -> command.translate(dx, dy) },
            )
            else -> element
        }
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

    private fun SceneElement.withZIndex(value: Int): SceneElement = when (this) {
        is SceneShape -> copy(zIndex = value)
        is SceneText -> copy(zIndex = value)
        is ScenePath -> copy(zIndex = value)
        else -> this
    }

    private companion object {
        val TRANSPARENT = SceneColor(0x00000000)
        const val EMPTY_WIDTH = 200f
        const val EMPTY_HEIGHT = 100f
        const val UNWRAPPED_TEXT_WIDTH = 100_000f
    }
}
