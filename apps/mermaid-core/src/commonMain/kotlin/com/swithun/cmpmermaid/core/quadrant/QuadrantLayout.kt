package com.swithun.cmpmermaid.core.quadrant

import com.swithun.cmpmermaid.core.CssColorParser
import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidQuadrantChartOptions
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.SceneAsset
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
import com.swithun.cmpmermaid.core.mermaidAdjustSaturationAndLightness
import com.swithun.cmpmermaid.core.mermaidDarken
import com.swithun.cmpmermaid.core.mermaidLighten
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Kotlin translation of Mermaid 12.0.0 quadrantBuilder.ts and quadrantRenderer.ts.
 */
internal class QuadrantLayout {
    fun layout(
        document: QuadrantDocument,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val config = context.options.quadrantChart
        when (val validation = validate(config)) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return validation
        }
        if (document.points.size > context.options.maxEdges) {
            return GMResult.Err(
                MermaidError.ResourceLimit(
                    resource = "Quadrant points",
                    actual = document.points.size,
                    maximum = context.options.maxEdges,
                ),
            )
        }
        val theme = when (val resolved = resolveTheme(context)) {
            is GMResult.Ok -> resolved.value
            is GMResult.Err -> return resolved
        }
        val elements = QuadrantSceneBuilder(
            document = document,
            config = config,
            theme = theme,
            context = context,
        ).build()
        // Mermaid's SVG export includes the full rendered bbox plus 12px on each
        // side. Compose clips its Canvas, so translate that bbox into the scene.
        val contentBounds = elements
            .mapNotNull(::elementBounds)
            .fold(SceneRect(0f, 0f, config.chartWidth, config.chartHeight), SceneRect::union)
        val dx = QUADRANT_VIEWPORT_PADDING - contentBounds.left
        val dy = QUADRANT_VIEWPORT_PADDING - contentBounds.top
        return GMResult.Ok(
            MermaidScene(
                width = contentBounds.width + QUADRANT_VIEWPORT_PADDING * 2f,
                height = contentBounds.height + QUADRANT_VIEWPORT_PADDING * 2f,
                background = context.theme.background,
                // Mermaid: quadrantRenderer.ts appends DOM groups in this exact
                // order, including circle/text pairs for each data point.
                elements = elements.map { element -> element.translate(dx, dy) },
                title = document.title,
                accessibilityTitle = document.accessibilityTitle,
                accessibilityDescription = document.accessibilityDescription,
                viewportPadding = 0f,
                viewportSizing = if (config.useMaxWidth) {
                    MermaidSceneViewportSizing.ResponsiveMaxWidth
                } else {
                    MermaidSceneViewportSizing.Intrinsic
                },
            ),
        )
    }

    private fun validate(
        config: MermaidQuadrantChartOptions,
    ): GMResult<Unit, MermaidError> {
        val nonNegative = listOf(
            "chartWidth" to config.chartWidth,
            "chartHeight" to config.chartHeight,
            "titleFontSize" to config.titleFontSize,
            "titlePadding" to config.titlePadding,
            "quadrantPadding" to config.quadrantPadding,
            "xAxisLabelPadding" to config.xAxisLabelPadding,
            "yAxisLabelPadding" to config.yAxisLabelPadding,
            "xAxisLabelFontSize" to config.xAxisLabelFontSize,
            "yAxisLabelFontSize" to config.yAxisLabelFontSize,
            "quadrantLabelFontSize" to config.quadrantLabelFontSize,
            "quadrantTextTopPadding" to config.quadrantTextTopPadding,
            "pointTextPadding" to config.pointTextPadding,
            "pointLabelFontSize" to config.pointLabelFontSize,
            "pointRadius" to config.pointRadius,
            "quadrantInternalBorderStrokeWidth" to
                config.quadrantInternalBorderStrokeWidth,
            "quadrantExternalBorderStrokeWidth" to
                config.quadrantExternalBorderStrokeWidth,
        )
        val invalid = nonNegative.firstOrNull { (_, value) -> !value.isFinite() || value < 0f }
        if (invalid != null) {
            return configurationError("${invalid.first} must be a non-negative finite number")
        }
        if (config.xAxisPosition !in setOf("top", "bottom")) {
            return configurationError("xAxisPosition must be top or bottom")
        }
        if (config.yAxisPosition !in setOf("left", "right")) {
            return configurationError("yAxisPosition must be left or right")
        }
        return GMResult.Ok(Unit)
    }

    private fun resolveTheme(
        context: MermaidRenderContext,
    ): GMResult<QuadrantTheme, MermaidError> {
        val primary = context.theme.nodeFill
        val primaryText = context.theme.nodeText
        // Mermaid: themes/theme-helpers.js -> mkBorder.
        val primaryBorder = primary.mermaidAdjustSaturationAndLightness(
            saturationAmount = -40.0,
            lightnessAmount = if (context.theme.background.isDark()) 10.0 else -10.0,
        )
        val inherited = QuadrantTheme(
            quadrantFills = listOf(
                primary,
                primary.adjustRgb(5),
                primary.adjustRgb(10),
                primary.adjustRgb(15),
            ),
            quadrantTextFills = listOf(
                primaryText,
                primaryText.adjustRgb(-5),
                primaryText.adjustRgb(-10),
                primaryText.adjustRgb(-15),
            ),
            pointFill = if (primary.isDark()) {
                primary.mermaidLighten(10.0)
            } else {
                primary.mermaidDarken(10.0)
            },
            pointTextFill = primaryText,
            xAxisTextFill = primaryText,
            yAxisTextFill = primaryText,
            internalBorderStrokeFill = primaryBorder,
            externalBorderStrokeFill = primaryBorder,
            titleFill = primaryText,
        )
        val variables = context.options.themeVariables
        fun color(name: String): GMResult<SceneColor?, MermaidError> {
            val value = variables[name] ?: return GMResult.Ok(null)
            val parsed = CssColorParser.parse(value)
                ?: value.takeIf(BARE_HEX_COLOR::matches)
                    ?.let { bareHex -> CssColorParser.parse("#$bareHex") }
            return parsed?.let { color -> GMResult.Ok(color) }
                ?: GMResult.Err(
                    MermaidError.Configuration(
                        "Mermaid theme variable '$name' has invalid value '$value'",
                    ),
                )
        }
        val resolved = linkedMapOf<String, SceneColor?>()
        QUADRANT_THEME_VARIABLES.forEach { name ->
            when (val parsed = color(name)) {
                is GMResult.Ok -> resolved[name] = parsed.value
                is GMResult.Err -> return parsed
            }
        }
        return GMResult.Ok(
            inherited.copy(
                quadrantFills = List(4) { index ->
                    resolved["quadrant${index + 1}Fill"] ?: inherited.quadrantFills[index]
                },
                quadrantTextFills = List(4) { index ->
                    resolved["quadrant${index + 1}TextFill"]
                        ?: inherited.quadrantTextFills[index]
                },
                pointFill = resolved["quadrantPointFill"] ?: inherited.pointFill,
                pointTextFill =
                    resolved["quadrantPointTextFill"] ?: inherited.pointTextFill,
                xAxisTextFill =
                    resolved["quadrantXAxisTextFill"] ?: inherited.xAxisTextFill,
                yAxisTextFill =
                    resolved["quadrantYAxisTextFill"] ?: inherited.yAxisTextFill,
                internalBorderStrokeFill =
                    resolved["quadrantInternalBorderStrokeFill"]
                        ?: inherited.internalBorderStrokeFill,
                externalBorderStrokeFill =
                    resolved["quadrantExternalBorderStrokeFill"]
                        ?: inherited.externalBorderStrokeFill,
                titleFill = resolved["quadrantTitleFill"] ?: inherited.titleFill,
            ),
        )
    }

    private fun configurationError(message: String): GMResult<Unit, MermaidError> =
        GMResult.Err(MermaidError.Configuration("Mermaid Quadrant Chart $message"))

    companion object {
        private val BARE_HEX_COLOR = Regex("""(?:[0-9A-Fa-f]{3}|[0-9A-Fa-f]{6})""")
        private const val QUADRANT_VIEWPORT_PADDING = 12f
        private val QUADRANT_THEME_VARIABLES = listOf(
            "quadrant1Fill",
            "quadrant2Fill",
            "quadrant3Fill",
            "quadrant4Fill",
            "quadrant1TextFill",
            "quadrant2TextFill",
            "quadrant3TextFill",
            "quadrant4TextFill",
            "quadrantPointFill",
            "quadrantPointTextFill",
            "quadrantXAxisTextFill",
            "quadrantYAxisTextFill",
            "quadrantInternalBorderStrokeFill",
            "quadrantExternalBorderStrokeFill",
            "quadrantTitleFill",
        )
    }
}

private class QuadrantSceneBuilder(
    private val document: QuadrantDocument,
    private val config: MermaidQuadrantChartOptions,
    private val theme: QuadrantTheme,
    private val context: MermaidRenderContext,
) {
    private val hasPoints = document.points.isNotEmpty()
    private val showXAxis = document.xAxisLeftText.isNotEmpty() ||
        document.xAxisRightText.isNotEmpty()
    private val showYAxis = document.yAxisBottomText.isNotEmpty() ||
        document.yAxisTopText.isNotEmpty()
    private val showTitle = !document.title.isNullOrEmpty()
    private val xAxisPosition = if (hasPoints) "bottom" else config.xAxisPosition
    private val space = calculateSpace()

    fun build(): List<SceneElement> = buildList {
        addQuadrants(this)
        addBorders(this)
        addPoints(this)
        addAxisLabels(this)
        addTitle(this)
    }

    private fun calculateSpace(): QuadrantSpace {
        val xAxisSpace = if (showXAxis) {
            config.xAxisLabelPadding * 2f + config.xAxisLabelFontSize
        } else {
            0f
        }
        val yAxisSpace = if (showYAxis) {
            config.yAxisLabelPadding * 2f + config.yAxisLabelFontSize
        } else {
            0f
        }
        val titleSpace = if (showTitle) {
            config.titleFontSize + config.titlePadding * 2f
        } else {
            0f
        }
        val xAxisTop = if (xAxisPosition == "top") xAxisSpace else 0f
        val xAxisBottom = if (xAxisPosition == "bottom") xAxisSpace else 0f
        val yAxisLeft = if (config.yAxisPosition == "left") yAxisSpace else 0f
        val yAxisRight = if (config.yAxisPosition == "right") yAxisSpace else 0f
        return QuadrantSpace(
            left = config.quadrantPadding + yAxisLeft,
            top = config.quadrantPadding + xAxisTop + titleSpace,
            width = config.chartWidth -
                config.quadrantPadding * 2f -
                yAxisLeft -
                yAxisRight,
            height = config.chartHeight -
                config.quadrantPadding * 2f -
                xAxisTop -
                xAxisBottom -
                titleSpace,
            titleSpace = titleSpace,
        )
    }

    private fun addQuadrants(elements: MutableList<SceneElement>) {
        val quadrants = listOf(
            QuadrantArea(space.left + space.halfWidth, space.top),
            QuadrantArea(space.left, space.top),
            QuadrantArea(space.left, space.top + space.halfHeight),
            QuadrantArea(space.left + space.halfWidth, space.top + space.halfHeight),
        )
        quadrants.forEachIndexed { index, area ->
            elements += SceneShape(
                id = "quadrant-area-${index + 1}",
                bounds = SceneRect(
                    left = area.x,
                    top = area.y,
                    right = area.x + space.halfWidth,
                    bottom = area.y + space.halfHeight,
                ),
                kind = SceneShapeKind.Rectangle,
                fill = theme.quadrantFills[index],
                stroke = TRANSPARENT,
                strokeWidth = 0f,
                cornerRadius = 0f,
                shadow = null,
                zIndex = 10,
            )
            val text = document.quadrantTexts[index]
            if (text.isNotEmpty()) {
                elements += text(
                    value = text,
                    x = area.x + space.halfWidth / 2f,
                    y = if (hasPoints) {
                        area.y + config.quadrantTextTopPadding
                    } else {
                        area.y + space.halfHeight / 2f
                    },
                    color = theme.quadrantTextFills[index],
                    fontSize = config.quadrantLabelFontSize,
                    alignment = SceneTextAlignment.Center,
                    verticalTop = hasPoints,
                    zIndex = 11,
                )
            }
        }
    }

    private fun addBorders(elements: MutableList<SceneElement>) {
        val halfExternal = config.quadrantExternalBorderStrokeWidth / 2f
        val lines = listOf(
            QuadrantLine(
                space.left - halfExternal,
                space.top,
                space.right + halfExternal,
                space.top,
                config.quadrantExternalBorderStrokeWidth,
                theme.externalBorderStrokeFill,
            ),
            QuadrantLine(
                space.right,
                space.top + halfExternal,
                space.right,
                space.bottom - halfExternal,
                config.quadrantExternalBorderStrokeWidth,
                theme.externalBorderStrokeFill,
            ),
            QuadrantLine(
                space.left - halfExternal,
                space.bottom,
                space.right + halfExternal,
                space.bottom,
                config.quadrantExternalBorderStrokeWidth,
                theme.externalBorderStrokeFill,
            ),
            QuadrantLine(
                space.left,
                space.top + halfExternal,
                space.left,
                space.bottom - halfExternal,
                config.quadrantExternalBorderStrokeWidth,
                theme.externalBorderStrokeFill,
            ),
            QuadrantLine(
                space.left + space.halfWidth,
                space.top + halfExternal,
                space.left + space.halfWidth,
                space.bottom - halfExternal,
                config.quadrantInternalBorderStrokeWidth,
                theme.internalBorderStrokeFill,
            ),
            QuadrantLine(
                space.left + halfExternal,
                space.top + space.halfHeight,
                space.right - halfExternal,
                space.top + space.halfHeight,
                config.quadrantInternalBorderStrokeWidth,
                theme.internalBorderStrokeFill,
            ),
        )
        lines.forEachIndexed { index, line ->
            val start = ScenePoint(line.x1, line.y1)
            val end = ScenePoint(line.x2, line.y2)
            elements += ScenePath(
                id = "quadrant-border-$index",
                points = listOf(start, end),
                commands = listOf(
                    ScenePathCommand.MoveTo(start),
                    ScenePathCommand.LineTo(end),
                ),
                color = line.color,
                strokeWidth = line.width,
                strokePattern = SceneStrokePattern.Solid,
                arrowStart = SceneArrowHead.None,
                arrowEnd = SceneArrowHead.None,
                curve = "linear",
                look = context.options.look,
                animated = false,
                zIndex = 12,
            )
        }
    }

    private fun addPoints(elements: MutableList<SceneElement>) {
        document.points.forEachIndexed { index, point ->
            val classStyle = point.className?.let(document.classes::get)
            val style = classStyle?.merge(point.style) ?: point.style
            val x = space.left + point.x * space.width
            val y = space.top + (1f - point.y) * space.height
            val radius = style.radius ?: config.pointRadius
            val fill = style.color ?: theme.pointFill
            elements += SceneShape(
                id = "quadrant-point-$index",
                bounds = SceneRect(
                    left = x - radius,
                    top = y - radius,
                    right = x + radius,
                    bottom = y + radius,
                ),
                kind = SceneShapeKind.Circle,
                fill = fill,
                stroke = style.strokeColor ?: theme.pointFill,
                strokeWidth = style.strokeWidth ?: 0f,
                cornerRadius = radius,
                shadow = null,
                zIndex = 15,
            )
            elements += text(
                value = point.text,
                x = x,
                y = y + config.pointTextPadding,
                color = theme.pointTextFill,
                fontSize = config.pointLabelFontSize,
                alignment = SceneTextAlignment.Center,
                verticalTop = true,
                zIndex = 16,
                lineHeight = POINT_LABEL_LINE_HEIGHT,
                horizontalScale = SVG_POINT_LABEL_HORIZONTAL_SCALE,
                boundsWidthScale = SVG_POINT_LABEL_BOUNDS_WIDTH_SCALE,
            )
        }
    }

    private fun addAxisLabels(elements: MutableList<SceneElement>) {
        val splitX = document.xAxisRightText.isNotEmpty()
        val xY = if (xAxisPosition == "top") {
            config.xAxisLabelPadding + space.titleSpace
        } else {
            config.xAxisLabelPadding + space.bottom + config.quadrantPadding
        }
        if (document.xAxisLeftText.isNotEmpty()) {
            elements += text(
                value = document.xAxisLeftText,
                x = space.left + if (splitX) space.halfWidth / 2f else 0f,
                y = xY,
                color = theme.xAxisTextFill,
                fontSize = config.xAxisLabelFontSize,
                alignment = if (splitX) SceneTextAlignment.Center else SceneTextAlignment.Start,
                verticalTop = true,
                zIndex = 20,
            )
        }
        if (document.xAxisRightText.isNotEmpty()) {
            elements += text(
                value = document.xAxisRightText,
                x = space.left + space.halfWidth +
                    if (splitX) space.halfWidth / 2f else 0f,
                y = xY,
                color = theme.xAxisTextFill,
                fontSize = config.xAxisLabelFontSize,
                alignment = if (splitX) SceneTextAlignment.Center else SceneTextAlignment.Start,
                verticalTop = true,
                zIndex = 20,
            )
        }

        val splitY = document.yAxisTopText.isNotEmpty()
        val yX = if (config.yAxisPosition == "left") {
            config.yAxisLabelPadding
        } else {
            config.yAxisLabelPadding + space.right + config.quadrantPadding
        }
        if (document.yAxisBottomText.isNotEmpty()) {
            elements += text(
                value = document.yAxisBottomText,
                x = yX,
                y = space.bottom - if (splitY) space.halfHeight / 2f else 0f,
                color = theme.yAxisTextFill,
                fontSize = config.yAxisLabelFontSize,
                alignment = if (splitY) SceneTextAlignment.Center else SceneTextAlignment.Start,
                verticalTop = true,
                rotation = -90f,
                zIndex = 20,
            )
        }
        if (document.yAxisTopText.isNotEmpty()) {
            elements += text(
                value = document.yAxisTopText,
                x = yX,
                y = space.top + space.halfHeight -
                    if (splitY) space.halfHeight / 2f else 0f,
                color = theme.yAxisTextFill,
                fontSize = config.yAxisLabelFontSize,
                alignment = if (splitY) SceneTextAlignment.Center else SceneTextAlignment.Start,
                verticalTop = true,
                rotation = -90f,
                zIndex = 20,
            )
        }
    }

    private fun addTitle(elements: MutableList<SceneElement>) {
        val title = document.title ?: return
        elements += text(
            value = title,
            x = config.chartWidth / 2f,
            y = config.titlePadding,
            color = theme.titleFill,
            fontSize = config.titleFontSize,
            alignment = SceneTextAlignment.Center,
            verticalTop = true,
            zIndex = 21,
        )
    }

    private fun text(
        value: String,
        x: Float,
        y: Float,
        color: SceneColor,
        fontSize: Float,
        alignment: SceneTextAlignment,
        verticalTop: Boolean,
        zIndex: Int,
        rotation: Float = 0f,
        lineHeight: Float = 1f,
        horizontalScale: Float? = null,
        boundsWidthScale: Float = 1f,
    ): SceneText {
        val measured = measure(
            text = value,
            fontSize = fontSize,
            lineHeight = lineHeight,
            horizontalScale = horizontalScale,
        )
        // SVG getBBox includes the browser glyph box. Compose reports its
        // untransformed single-line layout size, so preserve that SVG contract
        // explicitly for point labels.
        val metrics = measured.copy(
            width = measured.width * boundsWidthScale,
            height = max(measured.height, fontSize * lineHeight),
        )
        val left = when (alignment) {
            SceneTextAlignment.Start -> x
            SceneTextAlignment.Center -> x - metrics.width / 2f
            SceneTextAlignment.End -> x - metrics.width
        }
        val top = if (verticalTop) y else y - metrics.height / 2f
        return SceneText(
            text = value,
            bounds = SceneRect(
                left = left,
                top = top,
                right = left + metrics.width.coerceAtLeast(1f),
                bottom = top + metrics.height.coerceAtLeast(fontSize),
            ),
            color = color,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
            weight = SceneTextWeight.Normal,
            horizontalAlignment = alignment,
            rotationDegrees = rotation,
            rotationPivot = ScenePoint(x, y),
            zIndex = zIndex,
            softWrap = false,
            horizontalScale = horizontalScale,
        )
    }

    private fun measure(
        text: String,
        fontSize: Float,
        lineHeight: Float,
        horizontalScale: Float?,
    ): TextMetrics = context.textMetrics.measure(
        TextMetricsRequest(
            text = text,
            fontSize = fontSize,
            maxWidth = MAX_TEXT_WIDTH,
            lineHeight = lineHeight,
            fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
            weight = SceneTextWeight.Normal,
            horizontalScale = horizontalScale,
        ),
    )

    companion object {
        private val TRANSPARENT = SceneColor(0x00000000)
        private const val MAX_TEXT_WIDTH = 100_000f
        private const val POINT_LABEL_LINE_HEIGHT = 7f / 6f
        private const val SVG_POINT_LABEL_HORIZONTAL_SCALE = 1f
        private const val SVG_POINT_LABEL_BOUNDS_WIDTH_SCALE = 1f / 1.06f
    }
}

private data class QuadrantTheme(
    val quadrantFills: List<SceneColor>,
    val quadrantTextFills: List<SceneColor>,
    val pointFill: SceneColor,
    val pointTextFill: SceneColor,
    val xAxisTextFill: SceneColor,
    val yAxisTextFill: SceneColor,
    val internalBorderStrokeFill: SceneColor,
    val externalBorderStrokeFill: SceneColor,
    val titleFill: SceneColor,
)

private data class QuadrantSpace(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val titleSpace: Float,
) {
    val halfWidth: Float get() = width / 2f
    val halfHeight: Float get() = height / 2f
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

private data class QuadrantArea(
    val x: Float,
    val y: Float,
)

private data class QuadrantLine(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val width: Float,
    val color: SceneColor,
)

private fun SceneColor.adjustRgb(amount: Int): SceneColor {
    val alpha = (argb shr 24) and 0xFF
    val red = (((argb shr 16) and 0xFF) + amount).coerceIn(0L, 255L)
    val green = (((argb shr 8) and 0xFF) + amount).coerceIn(0L, 255L)
    val blue = ((argb and 0xFF) + amount).coerceIn(0L, 255L)
    return SceneColor((alpha shl 24) or (red shl 16) or (green shl 8) or blue)
}

private fun SceneColor.isDark(): Boolean {
    val red = (argb shr 16) and 0xFF
    val green = (argb shr 8) and 0xFF
    val blue = argb and 0xFF
    return red * 299L + green * 587L + blue * 114L < 128_000L
}

private fun elementBounds(element: SceneElement): SceneRect? = when (element) {
    is SceneAsset -> element.bounds.finiteOrNull()
    is SceneShape -> element.bounds.finiteOrNull()
    is SceneText -> {
        val bounds = element.bounds.finiteOrNull()
        val pivot = element.rotationPivot ?: bounds?.center
        if (
            bounds == null ||
            pivot == null ||
            !pivot.x.isFinite() ||
            !pivot.y.isFinite() ||
            !element.rotationDegrees.isFinite()
        ) {
            null
        } else {
            rotatedRectPoints(bounds, element.rotationDegrees, pivot).boundsOrNull()
        }
    }
    is ScenePath -> buildList {
        addAll(element.points)
        element.commands.forEach { command ->
            when (command) {
                is ScenePathCommand.MoveTo -> add(command.point)
                is ScenePathCommand.LineTo -> add(command.point)
                is ScenePathCommand.QuadraticTo -> {
                    add(command.control)
                    add(command.end)
                }
                is ScenePathCommand.CubicTo -> {
                    add(command.control1)
                    add(command.control2)
                    add(command.end)
                }
                is ScenePathCommand.ArcTo -> add(command.end)
            }
        }
    }.boundsOrNull()
}

private fun rotatedRectPoints(
    bounds: SceneRect,
    degrees: Float,
    pivot: ScenePoint,
): List<ScenePoint> {
    val points = listOf(
        ScenePoint(bounds.left, bounds.top),
        ScenePoint(bounds.right, bounds.top),
        ScenePoint(bounds.right, bounds.bottom),
        ScenePoint(bounds.left, bounds.bottom),
    )
    if (degrees == 0f) return points
    val radians = degrees * PI.toFloat() / 180f
    val cosine = cos(radians)
    val sine = sin(radians)
    return points.map { point ->
        val dx = point.x - pivot.x
        val dy = point.y - pivot.y
        ScenePoint(
            x = pivot.x + dx * cosine - dy * sine,
            y = pivot.y + dx * sine + dy * cosine,
        )
    }
}

private fun List<ScenePoint>.boundsOrNull(): SceneRect? {
    val finite = filter { point -> point.x.isFinite() && point.y.isFinite() }
    val first = finite.firstOrNull() ?: return null
    return finite.drop(1).fold(
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

private fun SceneRect.finiteOrNull(): SceneRect? = takeIf {
    left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()
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
