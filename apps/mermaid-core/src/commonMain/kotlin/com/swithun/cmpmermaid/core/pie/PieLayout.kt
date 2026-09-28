package com.swithun.cmpmermaid.core.pie

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidPieTheme
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.SceneAsset
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
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.SceneTextAlignment
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.TextMetrics
import com.swithun.cmpmermaid.core.TextMetricsRequest
import com.swithun.cmpmermaid.core.pie.upstream.mermaid.PieDb
import com.swithun.cmpmermaid.core.pie.upstream.mermaid.PieSection
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Native translation of Mermaid 12.0.0's pieRenderer.ts and D3 pie/arc defaults.
 */
internal class PieLayout {
    fun layout(
        db: PieDb,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val options = context.options
        if (!options.pieTextPosition.isFinite() || options.pieTextPosition !in 0f..1f) {
            return configurationError("Pie textPosition must be between 0 and 1")
        }
        if (!options.pieDonutHole.isFinite()) {
            return configurationError("Pie donutHole must be finite")
        }
        if (options.pieLegendPosition !in LEGEND_POSITIONS) {
            return configurationError(
                "Pie legendPosition must be one of ${LEGEND_POSITIONS.joinToString()}",
            )
        }
        if (options.pieHighlightSlice == HOVER_HIGHLIGHT) {
            return GMResult.Err(
                MermaidError.UnsupportedFeature(
                    feature = "Pie hover highlight",
                    message = "Native Mermaid does not expose pointer-hover state in SceneGraph",
                ),
            )
        }

        val sections = db.getSections()
            .map { (label, value) -> PieSection(label, value) }
        if (sections.size > options.maxEdges) {
            return GMResult.Err(
                MermaidError.ResourceLimit(
                    resource = "Pie sections",
                    actual = sections.size,
                    maximum = options.maxEdges,
                ),
            )
        }
        val measuredLegend = linkedMapOf<String, MeasuredText>()
        sections.forEach { section ->
            val label = if (db.getShowData()) {
                "${section.label} [${formatNumber(section.value)}]"
            } else {
                section.label
            }
            measuredLegend[section.label] = measure(
                text = label,
                fontSize = context.theme.pie.legendTextSize,
                context = context,
            )
        }
        val title = db.diagramTitle
            ?.let { value ->
                measure(
                    text = value,
                    fontSize = context.theme.pie.titleTextSize,
                    context = context,
                )
            }
        return GMResult.Ok(
            buildScene(
                sections = sections,
                showData = db.getShowData(),
                title = title,
                accessibilityTitle = db.accessibilityTitle,
                accessibilityDescription = db.accessibilityDescription,
                legend = measuredLegend,
                context = context,
            ),
        )
    }

    private fun buildScene(
        sections: List<PieSection>,
        showData: Boolean,
        title: MeasuredText?,
        accessibilityTitle: String?,
        accessibilityDescription: String?,
        legend: Map<String, MeasuredText>,
        context: MermaidRenderContext,
    ): MermaidScene {
        val legendPosition = context.options.pieLegendPosition
        val longestTextWidth = legend.values.maxOfOrNull { measured -> measured.metrics.width } ?: 0f
        val totalLegendHeight = sections.size * LEGEND_HEIGHT
        var chartAndLegendWidth = PIE_WIDTH + MARGIN
        var chartAndLegendHeight = HEIGHT
        when (legendPosition) {
            "top", "bottom" -> chartAndLegendHeight += totalLegendHeight
            "left", "right" ->
                chartAndLegendWidth += LEGEND_RECT_SIZE + LEGEND_SPACING + longestTextWidth
        }

        val titleWidth = title?.metrics?.width ?: 0f
        val titleLeft = PIE_WIDTH / 2f - titleWidth / 2f
        val titleRight = PIE_WIDTH / 2f + titleWidth / 2f
        val viewBoxX = min(0f, titleLeft)
        val viewBoxRight = max(chartAndLegendWidth, titleRight)
        val width = viewBoxRight - viewBoxX
        val xShift = -viewBoxX
        val pieOffsetX = if (legendPosition == "left") {
            longestTextWidth + LEGEND_RECT_SIZE + LEGEND_SPACING
        } else {
            0f
        }
        val pieOffsetY = if (legendPosition == "top") {
            totalLegendHeight + LEGEND_HEIGHT
        } else {
            0f
        }
        val pieCenter = ScenePoint(
            x = PIE_WIDTH / 2f + pieOffsetX + xShift,
            y = HEIGHT / 2f + pieOffsetY,
        )
        val elements = mutableListOf<SceneElement>()

        addOuterCircle(
            center = pieCenter,
            theme = context.theme.pie,
            elements = elements,
        )
        addSlices(
            sections = sections,
            center = pieCenter,
            context = context,
            elements = elements,
        )
        addLegend(
            sections = sections,
            showData = showData,
            measured = legend,
            position = legendPosition,
            longestTextWidth = longestTextWidth,
            xShift = xShift,
            theme = context.theme.pie,
            fontFamily = context.theme.fontFamily,
            elements = elements,
        )
        if (title != null) {
            elements += SceneText(
                text = title.text,
                bounds = centeredTextBounds(
                    centerX = PIE_WIDTH / 2f + xShift,
                    centerY = TITLE_BASELINE_Y - context.theme.pie.titleTextSize * BASELINE_SHIFT,
                    metrics = title.metrics,
                ),
                color = context.theme.pie.titleTextColor,
                fontSize = context.theme.pie.titleTextSize,
                fontFamily = context.theme.fontFamily,
                weight = SceneTextWeight.Normal,
            )
        }

        return normalizeViewport(
            MermaidScene(
                width = width,
                height = chartAndLegendHeight,
                background = context.theme.background,
                elements = elements.sortedBy(SceneElement::zIndex),
                title = title?.text,
                accessibilityTitle = accessibilityTitle,
                accessibilityDescription = accessibilityDescription,
            ),
        )
    }

    /**
     * Mermaid.js 12.0.0: pieRenderer.ts -> final svg viewBox.
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

    private fun ScenePoint.translate(
        dx: Float,
        dy: Float,
    ): ScenePoint = ScenePoint(x + dx, y + dy)

    private fun addOuterCircle(
        center: ScenePoint,
        theme: MermaidPieTheme,
        elements: MutableList<SceneElement>,
    ) {
        val outerRadius = RADIUS + theme.outerStrokeWidth / 2f
        val points = arcPoints(
            radius = outerRadius,
            startAngle = 0.0,
            endAngle = FULL_CIRCLE,
        )
        elements += SceneShape(
            id = "pie-outer-circle",
            bounds = circleBounds(center, outerRadius),
            kind = SceneShapeKind.Circle,
            geometry = SceneShapeGeometry(
                paths = listOf(
                    SceneShapePath(
                        points = points,
                        fill = SceneShapePaint.None,
                        stroke = SceneShapePaint.Stroke,
                    ),
                ),
                outline = points,
            ),
            fill = TRANSPARENT,
            stroke = theme.outerStrokeColor,
            strokeWidth = theme.outerStrokeWidth,
            zIndex = 4,
        )
    }

    private fun addSlices(
        sections: List<PieSection>,
        center: ScenePoint,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        val sum = sections.sumOf(PieSection::value)
        if (sum <= 0.0 || !sum.isFinite()) {
            return
        }
        val visible = sections.withIndex()
            .filter { (_, section) -> section.value / sum * 100.0 >= MIN_VISIBLE_PERCENT }
        val visibleSum = visible.sumOf { (_, section) -> section.value }
        if (visibleSum <= 0.0 || !visibleSum.isFinite()) {
            return
        }
        val innerRadius = context.options.pieDonutHole
            .takeIf { value -> value > 0f && value <= MAX_DONUT_HOLE }
            ?.times(RADIUS)
            ?: 0f
        var angle = 0.0
        visible.forEach { indexed ->
            val section = indexed.value
            val startAngle = angle
            val endAngle = startAngle + section.value / visibleSum * FULL_CIRCLE
            angle = endAngle
            val highlighted = context.options.pieHighlightSlice == section.label
            val scale = if (highlighted) HIGHLIGHT_SCALE else 1f
            val scaledOuterRadius = RADIUS * scale
            val scaledInnerRadius = innerRadius * scale
            val geometry = arcGeometry(
                outerRadius = scaledOuterRadius,
                innerRadius = scaledInnerRadius,
                startAngle = startAngle,
                endAngle = endAngle,
            )
            val color = paletteColor(context.theme.pie.colors, indexed.index)
            val opacity = if (highlighted) 1f else context.theme.pie.opacity
            elements += SceneShape(
                id = "pie-slice-${indexed.index}",
                bounds = circleBounds(center, scaledOuterRadius),
                kind = SceneShapeKind.Circle,
                geometry = geometry,
                fill = color.withOpacity(opacity),
                stroke = context.theme.pie.strokeColor.withOpacity(opacity),
                strokeWidth = context.theme.pie.strokeWidth,
                zIndex = 10 + if (highlighted) 1 else 0,
            )

            val percentage = section.value / sum * 100.0
            if (jsRound(percentage) != 0L) {
                val text = "${jsRound(percentage)}%"
                val metrics = measure(
                    text = text,
                    fontSize = context.theme.pie.sectionTextSize,
                    context = context,
                )
                val centroidAngle = (startAngle + endAngle) / 2.0 - PI / 2.0
                val textRadius = RADIUS * context.options.pieTextPosition
                elements += SceneText(
                    text = text,
                    bounds = centeredTextBounds(
                        centerX = center.x + cos(centroidAngle).toFloat() * textRadius,
                        centerY = center.y +
                            sin(centroidAngle).toFloat() * textRadius -
                            context.theme.pie.sectionTextSize * BASELINE_SHIFT,
                        metrics = metrics.metrics,
                    ),
                    color = context.theme.pie.sectionTextColor,
                    fontSize = context.theme.pie.sectionTextSize,
                    fontFamily = context.theme.fontFamily,
                    weight = SceneTextWeight.Normal,
                    zIndex = 20,
                )
            }
        }
    }

    private fun addLegend(
        sections: List<PieSection>,
        showData: Boolean,
        measured: Map<String, MeasuredText>,
        position: String,
        longestTextWidth: Float,
        xShift: Float,
        theme: MermaidPieTheme,
        fontFamily: String,
        elements: MutableList<SceneElement>,
    ) {
        val offset = LEGEND_HEIGHT * sections.size / 2f
        sections.forEachIndexed { index, section ->
            val groupX = when (position) {
                "left" -> PIE_WIDTH / 2f - RADIUS - LEGEND_RECT_SIZE - LEGEND_SPACING
                "right" -> PIE_WIDTH / 2f + 12f * LEGEND_RECT_SIZE
                else -> PIE_WIDTH / 2f -
                    longestTextWidth / 2f -
                    (LEGEND_RECT_SIZE + LEGEND_SPACING)
            } + xShift
            val groupY = when (position) {
                "top" -> HEIGHT / 2f + index * LEGEND_HEIGHT - RADIUS
                "bottom" -> HEIGHT / 2f + index * LEGEND_HEIGHT + RADIUS + LEGEND_HEIGHT
                else -> HEIGHT / 2f + index * LEGEND_HEIGHT - offset
            }
            val color = paletteColor(theme.colors, index)
            elements += SceneShape(
                id = "pie-legend-swatch-$index",
                bounds = SceneRect(
                    left = groupX,
                    top = groupY,
                    right = groupX + LEGEND_RECT_SIZE,
                    bottom = groupY + LEGEND_RECT_SIZE,
                ),
                kind = SceneShapeKind.Rectangle,
                fill = color,
                stroke = color,
                strokeWidth = 1f,
                cornerRadius = 0f,
                zIndex = 30,
            )
            val value = measured.getValue(section.label)
            val legendText = if (showData) {
                "${section.label} [${formatNumber(section.value)}]"
            } else {
                section.label
            }
            val textLeft = groupX + LEGEND_RECT_SIZE + LEGEND_SPACING
            elements += SceneText(
                text = legendText,
                bounds = SceneRect(
                    left = textLeft - START_ALIGNMENT_INSET,
                    top = groupY + LEGEND_RECT_SIZE / 2f - value.metrics.height / 2f,
                    right = textLeft - START_ALIGNMENT_INSET + value.metrics.width,
                    bottom = groupY + LEGEND_RECT_SIZE / 2f + value.metrics.height / 2f,
                ),
                color = theme.legendTextColor,
                fontSize = theme.legendTextSize,
                fontFamily = fontFamily,
                weight = SceneTextWeight.Normal,
                horizontalAlignment = SceneTextAlignment.Start,
                zIndex = 31,
            )
        }
    }

    private fun arcGeometry(
        outerRadius: Float,
        innerRadius: Float,
        startAngle: Double,
        endAngle: Double,
    ): SceneShapeGeometry {
        val outer = arcPoints(outerRadius, startAngle, endAngle)
        val fullCircle = endAngle - startAngle >= FULL_CIRCLE - ANGLE_EPSILON
        if (fullCircle && innerRadius <= 0f) {
            return SceneShapeGeometry(
                paths = listOf(SceneShapePath(points = outer)),
                outline = outer,
            )
        }
        val inner = if (innerRadius > 0f) {
            arcPoints(innerRadius, startAngle, endAngle).asReversed()
        } else {
            listOf(ScenePoint(0f, 0f))
        }
        val fillPoints = outer + inner
        if (!fullCircle || innerRadius <= 0f) {
            return SceneShapeGeometry(
                paths = listOf(SceneShapePath(points = fillPoints)),
                outline = fillPoints,
            )
        }
        return SceneShapeGeometry(
            paths = listOf(
                SceneShapePath(
                    points = fillPoints,
                    fill = SceneShapePaint.Fill,
                    stroke = SceneShapePaint.None,
                ),
                SceneShapePath(
                    points = outer,
                    fill = SceneShapePaint.None,
                    stroke = SceneShapePaint.Stroke,
                ),
                SceneShapePath(
                    points = inner,
                    fill = SceneShapePaint.None,
                    stroke = SceneShapePaint.Stroke,
                ),
            ),
            outline = outer,
        )
    }

    private fun arcPoints(
        radius: Float,
        startAngle: Double,
        endAngle: Double,
    ): List<ScenePoint> {
        val span = (endAngle - startAngle).coerceAtLeast(0.0)
        val steps = ceil(span / ARC_STEP_RADIANS).toInt().coerceAtLeast(1)
        return List(steps + 1) { index ->
            val angle = startAngle + span * index / steps - PI / 2.0
            ScenePoint(
                x = cos(angle).toFloat() * radius,
                y = sin(angle).toFloat() * radius,
            )
        }
    }

    private fun circleBounds(
        center: ScenePoint,
        radius: Float,
    ): SceneRect = SceneRect(
        left = center.x - radius,
        top = center.y - radius,
        right = center.x + radius,
        bottom = center.y + radius,
    )

    private fun centeredTextBounds(
        centerX: Float,
        centerY: Float,
        metrics: TextMetrics,
    ): SceneRect = SceneRect(
        left = centerX - metrics.width / 2f,
        top = centerY - metrics.height / 2f,
        right = centerX + metrics.width / 2f,
        bottom = centerY + metrics.height / 2f,
    )

    private fun measure(
        text: String,
        fontSize: Float,
        context: MermaidRenderContext,
    ): MeasuredText = MeasuredText(
        text = text,
        metrics = context.textMetrics.measure(
            TextMetricsRequest(
                text = text,
                fontSize = fontSize,
                maxWidth = UNWRAPPED_TEXT_WIDTH,
                fontFamily = context.theme.fontFamily,
                weight = SceneTextWeight.Normal,
            ),
        ),
    )

    private fun paletteColor(
        colors: List<SceneColor>,
        index: Int,
    ): SceneColor =
        colors.getOrNull(index.mod(colors.size.coerceAtLeast(1))) ?: BLACK

    private fun formatNumber(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    private fun jsRound(value: Double): Long = floor(value + 0.5).toLong()

    private fun SceneColor.withOpacity(opacity: Float): SceneColor {
        val alpha = ((argb ushr 24) and 0xFF).toInt()
        val adjusted = (alpha * opacity.coerceIn(0f, 1f)).roundToInt()
        return SceneColor((argb and 0x00FFFFFF) or (adjusted.toLong() shl 24))
    }

    private fun configurationError(message: String): GMResult.Err<MermaidError> =
        GMResult.Err(MermaidError.Configuration(message))

    private data class MeasuredText(
        val text: String,
        val metrics: TextMetrics,
    )

    private companion object {
        val BLACK = SceneColor(0xFF000000)
        val TRANSPARENT = SceneColor(0x00000000)
        val LEGEND_POSITIONS = setOf("top", "bottom", "left", "right", "center")
        const val HOVER_HIGHLIGHT = "hover"
        const val HEIGHT = 450f
        const val PIE_WIDTH = 450f
        const val MARGIN = 40f
        const val RADIUS = 185f
        const val LEGEND_RECT_SIZE = 18f
        const val LEGEND_SPACING = 4f
        const val LEGEND_HEIGHT = LEGEND_RECT_SIZE + LEGEND_SPACING
        const val TITLE_BASELINE_Y = 25f
        const val BASELINE_SHIFT = 0.25f
        const val START_ALIGNMENT_INSET = 4f
        const val VIEWBOX_PADDING = 12f
        const val MIN_VISIBLE_PERCENT = 1.0
        const val MAX_DONUT_HOLE = 0.9f
        const val HIGHLIGHT_SCALE = 1.05f
        const val FULL_CIRCLE = PI * 2.0
        const val ARC_STEP_RADIANS = PI / 90.0
        const val ANGLE_EPSILON = 1e-9
        const val UNWRAPPED_TEXT_WIDTH = 100_000f
    }
}
