package com.swithun.cmpmermaid.core.treeview

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.CssColorParser
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.MermaidTreeViewOptions
import com.swithun.cmpmermaid.core.SceneAsset
import com.swithun.cmpmermaid.core.SceneAssetKind
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.SceneElement
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.SceneTextAlignment
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.TextMetrics
import com.swithun.cmpmermaid.core.TextMetricsRequest
import com.swithun.cmpmermaid.core.treeview.upstream.mermaid.TreeViewDb
import com.swithun.cmpmermaid.core.treeview.upstream.mermaid.TreeViewIcons
import com.swithun.cmpmermaid.core.treeview.upstream.mermaid.TreeViewNode
import com.swithun.cmpmermaid.core.treeview.upstream.mermaid.TreeViewNodeType
import kotlin.math.max

/**
 * Kotlin translation of Mermaid.js 12.0.0
 * packages/mermaid/src/diagrams/treeView/renderer.ts and styles.ts.
 */
internal class TreeViewLayout {
    fun layout(
        db: TreeViewDb,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val style = when (val result = TreeViewStyle.resolve(db.config, context)) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val renderer = Renderer(db.config, style, context)
        val rendered = renderer.drawTree(db.getRoot())
        val scene = MermaidScene(
            width = rendered.width,
            height = rendered.height,
            background = context.theme.background,
            elements = rendered.elements,
            title = db.diagramTitle,
            accessibilityTitle = db.accessibilityTitle,
            accessibilityDescription = db.accessibilityDescription,
            viewportSizing = if (db.config.useMaxWidth) {
                MermaidSceneViewportSizing.ResponsiveMaxWidth
            } else {
                MermaidSceneViewportSizing.Intrinsic
            },
        )
        return GMResult.Ok(normalizeViewport(scene))
    }

    /**
     * Mermaid.js 12.0.0: treeView/renderer.ts -> final svg viewBox.
     * Official reference isolation: official-mermaid.html -> normalizeViewBox.
     *
     * The browser reference unions the renderer viewBox with SVG getBBox(), then
     * adds 12 px on every side. Scene coordinates are translated because
     * MermaidScene has no negative viewBox origin.
     */
    private fun normalizeViewport(scene: MermaidScene): MermaidScene {
        val rendererViewport = SceneRect(
            left = 0f,
            top = 0f,
            right = scene.width,
            bottom = scene.height,
        )
        val contentBounds = scene.elements
            .mapNotNull(::elementBounds)
            .reduceOrNull(SceneRect::union)
        val union = contentBounds?.let(rendererViewport::union) ?: rendererViewport
        val left = union.left - VIEWBOX_PADDING
        val top = union.top - VIEWBOX_PADDING
        val right = union.right + VIEWBOX_PADDING
        val bottom = union.bottom + VIEWBOX_PADDING
        return scene.copy(
            width = (right - left).coerceAtLeast(1f),
            height = (bottom - top).coerceAtLeast(1f),
            elements = scene.elements.map { element ->
                element.translate(dx = -left, dy = -top)
            },
        )
    }

    private fun elementBounds(element: SceneElement): SceneRect? = when (element) {
        is SceneAsset -> element.bounds
        is SceneShape -> element.bounds
        is SceneText -> element.bounds
        is ScenePath -> {
            val first = element.points.firstOrNull()
            if (first == null) null else element.points.drop(1).fold(
                SceneRect(first.x, first.y, first.x, first.y),
            ) { bounds, point ->
                bounds.union(SceneRect(point.x, point.y, point.x, point.y))
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

    private class Renderer(
        private val config: MermaidTreeViewOptions,
        private val style: TreeViewStyle,
        private val context: MermaidRenderContext,
    ) {
        private var nextId = 0
        private var totalHeight = 0f
        private var totalWidth = 0f
        private val rows = mutableListOf<RenderedRow>()
        private val connectorElements = mutableListOf<SceneElement>()

        fun drawTree(root: TreeViewNode): RenderedTree {
            processNode(root)
            val descriptionElements = mutableListOf<SceneElement>()
            val rowsWithDescriptions = rows.filter { row -> row.node.description != null }
            if (rowsWithDescriptions.isNotEmpty()) {
                val descriptionX = rows.maxOf(RenderedRow::labelRightEdge) + DESCRIPTION_GAP
                rowsWithDescriptions.forEach { row ->
                    val description = row.node.description ?: return@forEach
                    val metrics = measure(description, SceneTextWeight.Normal)
                    descriptionElements += SceneText(
                        text = description,
                        bounds = SceneRect(
                            left = descriptionX,
                            top = row.centerY - metrics.height / 2f,
                            right = descriptionX + metrics.width,
                            bottom = row.centerY + metrics.height / 2f,
                        ),
                        color = style.descriptionColor,
                        fontSize = style.fontSize,
                        fontFamily = style.fontFamily,
                        weight = SceneTextWeight.Normal,
                        italic = true,
                        horizontalAlignment = SceneTextAlignment.Start,
                        softWrap = false,
                        zIndex = 20,
                    )
                    totalWidth = max(totalWidth, descriptionX + metrics.width + config.paddingX)
                }
            }

            val highlightElements = mutableListOf<SceneElement>()
            rows.forEach { row ->
                if (row.node.cssClass?.split(WHITESPACE)?.contains(HIGHLIGHT_CLASS) == true) {
                    val width = totalWidth - row.bounds.left + HIGHLIGHT_EXTRA_WIDTH
                    highlightElements += SceneShape(
                        id = id("highlight"),
                        bounds = SceneRect(
                            left = row.bounds.left,
                            top = row.bounds.top + 1f,
                            right = row.bounds.left + width,
                            bottom = row.bounds.bottom - 1f,
                        ),
                        kind = SceneShapeKind.RoundedRectangle,
                        fill = style.highlightBackground,
                        stroke = style.highlightStroke,
                        strokeWidth = 1f,
                        cornerRadius = 3f,
                        shadow = null,
                        zIndex = 1,
                    )
                    totalWidth = max(totalWidth, row.bounds.left + width + 2f)
                }
            }

            return RenderedTree(
                width = totalWidth,
                height = totalHeight,
                elements = highlightElements +
                    connectorElements +
                    rows.flatMap(RenderedRow::elements) +
                    descriptionElements,
            )
        }

        private fun processNode(
            node: TreeViewNode,
            depth: Int = 0,
        ) {
            val indent = depth * (config.rowIndent + config.paddingX)
            val row = positionLabel(indent, totalHeight, node)
            rows += row
            // Mermaid.js 12.0.0: treeView/renderer.ts -> drawTree.drawNode.
            connectorElements += line(
                x1 = indent - config.rowIndent,
                y1 = row.centerY,
                x2 = indent,
                y2 = row.centerY,
            )
            totalWidth = max(totalWidth, indent + row.bounds.width)
            totalHeight += row.bounds.height

            node.children.forEach { child -> processNode(child, depth + 1) }
            if (node.children.isNotEmpty()) {
                val lastChild = rows.last { row -> row.node === node.children.last() }
                connectorElements += line(
                    x1 = row.bounds.left + config.paddingX,
                    y1 = row.bounds.bottom,
                    x2 = row.bounds.left + config.paddingX,
                    y2 = lastChild.bounds.center.y + config.lineThickness / 2f,
                )
            }
        }

        private fun positionLabel(
            x: Float,
            y: Float,
            node: TreeViewNode,
        ): RenderedRow {
            val weight = if (node.nodeType == TreeViewNodeType.Directory) {
                SceneTextWeight.Bold
            } else {
                SceneTextWeight.Normal
            }
            val metrics = measure(node.name, weight)
            val height = metrics.height + config.paddingY * 2f
            val icon = TreeViewIcons.getNodeIcon(node, config)
            val showIcon = icon != null
            val labelX = x + config.paddingX + if (showIcon) ICON_SIZE + ICON_GAP else 0f
            val centerY = y + height / 2f
            val elements = mutableListOf<SceneElement>()
            if (icon != null) {
                elements += renderIcon(
                    icon = icon,
                    bounds = SceneRect(
                        left = x + config.paddingX,
                        top = y + config.paddingY,
                        right = x + config.paddingX + ICON_SIZE,
                        bottom = y + config.paddingY + ICON_SIZE,
                    ),
                )
            }
            elements += SceneText(
                text = node.name,
                bounds = SceneRect(
                    left = labelX,
                    top = centerY - metrics.height / 2f,
                    right = labelX + metrics.width,
                    bottom = centerY + metrics.height / 2f,
                ),
                color = style.labelColor,
                fontSize = style.fontSize,
                fontFamily = style.fontFamily,
                weight = weight,
                horizontalAlignment = SceneTextAlignment.Start,
                softWrap = false,
                zIndex = 20,
            )
            val width = metrics.width + config.paddingX * 2f +
                if (showIcon) ICON_SIZE + ICON_GAP else 0f
            return RenderedRow(
                node = node,
                bounds = SceneRect(x, y, x + width, y + height),
                labelRightEdge = labelX + metrics.width,
                centerY = centerY,
                elements = elements,
            )
        }

        private fun renderIcon(
            icon: String,
            bounds: SceneRect,
        ): List<SceneElement> = when (icon) {
            TreeViewIcons.BUILT_IN_FILE -> builtInFileIcon(bounds)
            TreeViewIcons.BUILT_IN_FOLDER -> builtInFolderIcon(bounds)
            else -> listOf(
                SceneAsset(
                    id = id("icon"),
                    source = icon,
                    bounds = bounds,
                    kind = SceneAssetKind.Icon,
                    tint = style.iconColor,
                    zIndex = 15,
                ),
            )
        }

        private fun builtInFileIcon(bounds: SceneRect): List<SceneElement> {
            fun point(x: Float, y: Float): ScenePoint = ScenePoint(
                x = bounds.left + bounds.width * x / 24f,
                y = bounds.top + bounds.height * y / 24f,
            )
            val points = listOf(
                point(6f, 2f),
                point(13.17f, 2f),
                point(20f, 8.83f),
                point(20f, 20f),
                point(4f, 20f),
                point(4f, 4f),
            )
            return listOf(
                ScenePath(
                    id = id("file-icon"),
                    points = points,
                    commands = points.toLineCommands(),
                    color = style.iconColor,
                    strokeWidth = 1f,
                    curve = "linear",
                    look = "classic",
                    animated = false,
                    fillColor = style.iconColor,
                    closed = true,
                    zIndex = 15,
                ),
                linePath(
                    role = "file-fold",
                    points = listOf(point(13.5f, 3.2f), point(13.5f, 8.5f), point(18.8f, 8.5f)),
                    color = context.theme.background,
                    strokeWidth = 1.25f,
                    zIndex = 16,
                ),
            )
        }

        private fun builtInFolderIcon(bounds: SceneRect): List<SceneElement> {
            fun point(x: Float, y: Float): ScenePoint = ScenePoint(
                x = bounds.left + bounds.width * x / 24f,
                y = bounds.top + bounds.height * y / 24f,
            )
            val points = listOf(
                point(2f, 6f),
                point(9.17f, 6f),
                point(12.83f, 8f),
                point(22f, 8f),
                point(22f, 20f),
                point(2f, 20f),
            )
            return listOf(
                ScenePath(
                    id = id("folder-icon"),
                    points = points,
                    commands = points.toLineCommands(),
                    color = style.iconColor,
                    strokeWidth = 1f,
                    curve = "linear",
                    look = "classic",
                    animated = false,
                    fillColor = style.iconColor,
                    closed = true,
                    zIndex = 15,
                ),
            )
        }

        private fun line(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
        ): ScenePath = linePath(
            role = "line",
            points = listOf(ScenePoint(x1, y1), ScenePoint(x2, y2)),
            color = style.lineColor,
            strokeWidth = config.lineThickness,
            zIndex = 5,
        )

        private fun linePath(
            role: String,
            points: List<ScenePoint>,
            color: SceneColor,
            strokeWidth: Float,
            zIndex: Int,
        ): ScenePath = ScenePath(
            id = id(role),
            points = points,
            commands = points.toLineCommands(),
            color = color,
            strokeWidth = strokeWidth,
            curve = "linear",
            look = "classic",
            animated = false,
            zIndex = zIndex,
        )

        private fun measure(
            text: String,
            weight: SceneTextWeight,
        ): TextMetrics = context.textMetrics.measure(
            TextMetricsRequest(
                text = text,
                fontSize = style.fontSize,
                maxWidth = MAX_TEXT_WIDTH,
                fontFamily = style.fontFamily,
                weight = weight,
            ),
        )

        private fun id(role: String): String = "treeview-$role-${nextId++}"
    }

    private data class RenderedRow(
        val node: TreeViewNode,
        val bounds: SceneRect,
        val labelRightEdge: Float,
        val centerY: Float,
        val elements: List<SceneElement>,
    )

    private data class RenderedTree(
        val width: Float,
        val height: Float,
        val elements: List<SceneElement>,
    )

    private data class TreeViewStyle(
        val fontSize: Float,
        val fontFamily: String?,
        val labelColor: SceneColor,
        val lineColor: SceneColor,
        val iconColor: SceneColor,
        val descriptionColor: SceneColor,
        val highlightBackground: SceneColor,
        val highlightStroke: SceneColor,
    ) {
        companion object {
            fun resolve(
                config: MermaidTreeViewOptions,
                context: MermaidRenderContext,
            ): GMResult<TreeViewStyle, MermaidError> {
                val numeric = listOf(
                    "rowIndent" to config.rowIndent,
                    "paddingX" to config.paddingX,
                    "paddingY" to config.paddingY,
                    "lineThickness" to config.lineThickness,
                )
                numeric.firstOrNull { (_, value) -> !value.isFinite() || value < 0f }
                    ?.let { (name, _) ->
                        return configurationError("treeView.$name must be finite and non-negative")
                    }
                val variables = context.options.themeVariables
                val fontSize = when (
                    val result = parseFontSize(
                        variables["treeView.labelFontSize"],
                        DEFAULT_FONT_SIZE,
                    )
                ) {
                    is GMResult.Ok -> result.value
                    is GMResult.Err -> return result
                }
                return GMResult.Ok(
                    TreeViewStyle(
                        fontSize = fontSize,
                        fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
                        labelColor = when (
                            val result = parseColor(
                                variables["treeView.labelColor"],
                                DEFAULT_LABEL_COLOR,
                                "treeView.labelColor",
                            )
                        ) {
                            is GMResult.Ok -> result.value
                            is GMResult.Err -> return result
                        },
                        lineColor = when (
                            val result = parseColor(
                                variables["treeView.lineColor"],
                                DEFAULT_LINE_COLOR,
                                "treeView.lineColor",
                            )
                        ) {
                            is GMResult.Ok -> result.value
                            is GMResult.Err -> return result
                        },
                        iconColor = when (
                            val result = parseColor(
                                variables["treeView.iconColor"],
                                DEFAULT_ICON_COLOR,
                                "treeView.iconColor",
                            )
                        ) {
                            is GMResult.Ok -> result.value
                            is GMResult.Err -> return result
                        },
                        descriptionColor = when (
                            val result = parseColor(
                                variables["treeView.descriptionColor"],
                                DEFAULT_DESCRIPTION_COLOR,
                                "treeView.descriptionColor",
                            )
                        ) {
                            is GMResult.Ok -> result.value
                            is GMResult.Err -> return result
                        },
                        highlightBackground = when (
                            val result = parseColor(
                                variables["treeView.highlightBg"],
                                DEFAULT_HIGHLIGHT_BACKGROUND,
                                "treeView.highlightBg",
                            )
                        ) {
                            is GMResult.Ok -> result.value
                            is GMResult.Err -> return result
                        },
                        highlightStroke = when (
                            val result = parseColor(
                                variables["treeView.highlightStroke"],
                                DEFAULT_HIGHLIGHT_STROKE,
                                "treeView.highlightStroke",
                            )
                        ) {
                            is GMResult.Ok -> result.value
                            is GMResult.Err -> return result
                        },
                    ),
                )
            }

            private fun parseFontSize(
                value: String?,
                fallback: Float,
            ): GMResult<Float, MermaidError> {
                if (value == null) {
                    return GMResult.Ok(fallback)
                }
                val parsed = FONT_SIZE.matchEntire(value.trim())
                    ?.groupValues
                    ?.get(1)
                    ?.toFloatOrNull()
                return if (parsed != null && parsed.isFinite() && parsed >= 0f) {
                    GMResult.Ok(parsed)
                } else {
                    configurationError(
                        "themeVariables.treeView.labelFontSize must be a non-negative px value",
                    )
                }
            }

            private fun parseColor(
                value: String?,
                fallback: SceneColor,
                path: String,
            ): GMResult<SceneColor, MermaidError> {
                if (value == null) {
                    return GMResult.Ok(fallback)
                }
                return CssColorParser.parse(value)?.let { color -> GMResult.Ok(color) }
                    ?: configurationError("themeVariables.$path must be a valid color")
            }

            private fun <T> configurationError(
                message: String,
            ): GMResult<T, MermaidError> = GMResult.Err(
                MermaidError.Configuration("Mermaid $message"),
            )

            private val FONT_SIZE = Regex("""^([0-9]+(?:\.[0-9]+)?)(?:px)?$""")
            private val DEFAULT_LABEL_COLOR = SceneColor(0xFF000000)
            private val DEFAULT_LINE_COLOR = SceneColor(0xFF000000)
            private val DEFAULT_ICON_COLOR = SceneColor(0xFF546E7A)
            private val DEFAULT_DESCRIPTION_COLOR = SceneColor(0xFF6A9955)
            private val DEFAULT_HIGHLIGHT_BACKGROUND = SceneColor(0x1AFFC107)
            private val DEFAULT_HIGHLIGHT_STROKE = SceneColor(0xFFFFC107)
            private const val DEFAULT_FONT_SIZE = 16f
        }
    }

    private companion object {
        const val ICON_SIZE = 14f
        const val ICON_GAP = 4f
        const val DESCRIPTION_GAP = 16f
        const val HIGHLIGHT_EXTRA_WIDTH = 8f
        const val HIGHLIGHT_CLASS = "highlight"
        const val MAX_TEXT_WIDTH = 100_000f
        const val VIEWBOX_PADDING = 12f
        val WHITESPACE = Regex("""\s+""")
    }
}

private fun ScenePoint.translate(
    dx: Float,
    dy: Float,
): ScenePoint = ScenePoint(x + dx, y + dy)

private fun List<ScenePoint>.toLineCommands(): List<ScenePathCommand> =
    mapIndexed { index, point ->
        if (index == 0) {
            ScenePathCommand.MoveTo(point)
        } else {
            ScenePathCommand.LineTo(point)
        }
    }
