package com.swithun.cmpmermaid.core.xychart

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidXyAxisOptions
import com.swithun.cmpmermaid.core.MermaidXyChartOptions
import com.swithun.cmpmermaid.core.MermaidXyChartTheme
import com.swithun.cmpmermaid.core.SceneArrowHead
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
import com.swithun.cmpmermaid.core.xychart.upstream.d3.XyScalePort
import com.swithun.cmpmermaid.core.xychart.upstream.mermaid.XyAxisData
import com.swithun.cmpmermaid.core.xychart.upstream.mermaid.XyBandAxisData
import com.swithun.cmpmermaid.core.xychart.upstream.mermaid.XyBarPlotData
import com.swithun.cmpmermaid.core.xychart.upstream.mermaid.XyChartData
import com.swithun.cmpmermaid.core.xychart.upstream.mermaid.XyChartOrientation
import com.swithun.cmpmermaid.core.xychart.upstream.mermaid.XyLinePlotData
import com.swithun.cmpmermaid.core.xychart.upstream.mermaid.XyLinearAxisData
import com.swithun.cmpmermaid.core.xychart.upstream.mermaid.XyPlotData
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Kotlin translation of Mermaid 12.0.0's XYChartBuilder and renderer.
 */
internal class XyChartLayout {
    fun layout(
        data: XyChartData,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val config = context.options.xyChart
        when (val validation = validate(config, context.theme.xyChart)) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return validation
        }
        val elements = XyOrchestrator(
            config = config,
            data = data,
            theme = context.theme.xyChart,
            context = context,
        ).build()
        // Mermaid: xychartRenderer.ts -> draw keeps overflowing SVG text visible.
        // Compose clips its Canvas, so include the translated text overflow in the viewport.
        val sceneWidth = elements
            .filterIsInstance<SceneText>()
            .fold(config.width) { width, text -> max(width, text.bounds.right) }
        return GMResult.Ok(
            MermaidScene(
                width = sceneWidth,
                height = config.height,
                background = context.theme.xyChart.backgroundColor,
                // Mermaid: xychartRenderer.ts -> draw iterates drawable elements in
                // Orchestrator component order. Later legend/axis groups may cover plot text.
                elements = elements,
                title = data.title.takeIf(String::isNotEmpty),
                accessibilityTitle = data.accessibilityTitle,
                accessibilityDescription = data.accessibilityDescription,
                viewportPadding = XY_VIEWPORT_PADDING,
            ),
        )
    }

    private fun validate(
        config: MermaidXyChartOptions,
        theme: MermaidXyChartTheme,
    ): GMResult<Unit, MermaidError> {
        val positive = listOf(
            "width" to config.width,
            "height" to config.height,
            "titleFontSize" to config.titleFontSize,
            "legendFontSize" to config.legendFontSize,
            "xAxis.labelFontSize" to config.xAxis.labelFontSize,
            "xAxis.titleFontSize" to config.xAxis.titleFontSize,
            "xAxis.tickLength" to config.xAxis.tickLength,
            "xAxis.tickWidth" to config.xAxis.tickWidth,
            "xAxis.axisLineWidth" to config.xAxis.axisLineWidth,
            "yAxis.labelFontSize" to config.yAxis.labelFontSize,
            "yAxis.titleFontSize" to config.yAxis.titleFontSize,
            "yAxis.tickLength" to config.yAxis.tickLength,
            "yAxis.tickWidth" to config.yAxis.tickWidth,
            "yAxis.axisLineWidth" to config.yAxis.axisLineWidth,
        )
        val invalidPositive = positive.firstOrNull { (_, value) ->
            !value.isFinite() || value < 1f
        }
        if (invalidPositive != null) {
            return configurationError("${invalidPositive.first} must be at least 1")
        }
        val nonNegative = listOf(
            "titlePadding" to config.titlePadding,
            "legendPadding" to config.legendPadding,
            "xAxis.labelPadding" to config.xAxis.labelPadding,
            "xAxis.titlePadding" to config.xAxis.titlePadding,
            "yAxis.labelPadding" to config.yAxis.labelPadding,
            "yAxis.titlePadding" to config.yAxis.titlePadding,
        )
        val invalidNonNegative = nonNegative.firstOrNull { (_, value) ->
            !value.isFinite() || value < 0f
        }
        if (invalidNonNegative != null) {
            return configurationError("${invalidNonNegative.first} must be non-negative")
        }
        if (config.chartOrientation !in setOf("vertical", "horizontal")) {
            return configurationError("chartOrientation must be vertical or horizontal")
        }
        if (
            !config.plotReservedSpacePercent.isFinite() ||
            config.plotReservedSpacePercent < 30f
        ) {
            return configurationError("plotReservedSpacePercent must be at least 30")
        }
        val invalidRotation = listOf(
            "xAxis.labelRotation" to config.xAxis.labelRotation,
            "yAxis.labelRotation" to config.yAxis.labelRotation,
        ).firstOrNull { (_, value) -> !value.isFinite() || value !in -90f..90f }
        if (invalidRotation != null) {
            return configurationError("${invalidRotation.first} must be between -90 and 90")
        }
        if (theme.plotColorPalette.isEmpty()) {
            return configurationError("plotColorPalette must contain at least one color")
        }
        return GMResult.Ok(Unit)
    }

    private fun configurationError(message: String): GMResult<Unit, MermaidError> =
        GMResult.Err(MermaidError.Configuration("Mermaid XY Chart $message"))

    companion object {
        private const val XY_VIEWPORT_PADDING = 12f
    }
}

private class XyOrchestrator(
    private val config: MermaidXyChartOptions,
    private val data: XyChartData,
    private val theme: MermaidXyChartTheme,
    private val context: MermaidRenderContext,
) {
    private val textDimensions = XyTextDimensions(context)
    private val title = XyChartTitle(config, data, theme, textDimensions)
    private val plot = XyPlot(config, data, theme, context)
    private val legend = XyLegend(config, data, theme, textDimensions, context)
    private val xAxis = createAxis(data.xAxis, config.xAxis, xAxisTheme(), textDimensions, context)
    private val yAxis = createAxis(data.yAxis, config.yAxis, yAxisTheme(), textDimensions, context)

    fun build(): List<SceneElement> {
        if (data.orientation == XyChartOrientation.Horizontal) {
            calculateHorizontalSpace()
        } else {
            calculateVerticalSpace()
        }
        plot.setAxes(xAxis, yAxis)
        return buildList {
            addAll(title.elements())
            addAll(plot.elements())
            addAll(legend.elements())
            addAll(xAxis.elements())
            addAll(yAxis.elements())
        }
    }

    private fun calculateVerticalSpace() {
        var availableWidth = config.width
        var availableHeight = config.height
        var plotX = 0f
        var plotY = 0f
        var chartWidth = floor(availableWidth * config.plotReservedSpacePercent / 100f)
        var chartHeight = floor(availableHeight * config.plotReservedSpacePercent / 100f)

        var used = plot.calculateSpace(XyDimension(chartWidth, chartHeight))
        availableWidth -= used.width
        availableHeight -= used.height

        used = title.calculateSpace(XyDimension(config.width, availableHeight))
        plotY = used.height
        availableHeight -= used.height

        xAxis.setAxisPosition(XyAxisPosition.Bottom)
        used = xAxis.calculateSpace(XyDimension(availableWidth, availableHeight))
        availableHeight -= used.height

        yAxis.setAxisPosition(XyAxisPosition.Left)
        used = yAxis.calculateSpace(XyDimension(availableWidth, availableHeight))
        plotX = used.width
        availableWidth -= used.width

        val legendSpace = legend.calculateSpace(XyDimension(availableWidth, chartHeight))
        availableWidth -= legendSpace.width
        if (availableWidth > 0f) chartWidth += availableWidth
        if (availableHeight > 0f) chartHeight += availableHeight
        plot.calculateSpace(XyDimension(chartWidth, chartHeight))

        plot.setPosition(XyPoint(plotX, plotY))
        legend.setPosition(
            XyPoint(
                x = plotX + chartWidth,
                y = plotY + max((chartHeight - legendSpace.height) / 2f, 0f),
            ),
        )
        xAxis.setRange(plotX, plotX + chartWidth)
        xAxis.setPosition(XyPoint(plotX, plotY + chartHeight))
        yAxis.setRange(plotY, plotY + chartHeight)
        yAxis.setPosition(XyPoint(0f, plotY))
        if (data.plots.any { it is XyBarPlotData }) {
            xAxis.recalculateOuterPaddingToDrawBar()
        }
    }

    private fun calculateHorizontalSpace() {
        var availableWidth = config.width
        var availableHeight = config.height
        var titleYEnd = 0f
        var plotX = 0f
        var plotY = 0f
        var chartWidth = floor(availableWidth * config.plotReservedSpacePercent / 100f)
        var chartHeight = floor(availableHeight * config.plotReservedSpacePercent / 100f)

        var used = plot.calculateSpace(XyDimension(chartWidth, chartHeight))
        availableWidth -= used.width
        availableHeight -= used.height

        used = title.calculateSpace(XyDimension(config.width, availableHeight))
        titleYEnd = used.height
        availableHeight -= used.height

        xAxis.setAxisPosition(XyAxisPosition.Left)
        used = xAxis.calculateSpace(XyDimension(availableWidth, availableHeight))
        availableWidth -= used.width
        plotX = used.width

        yAxis.setAxisPosition(XyAxisPosition.Top)
        used = yAxis.calculateSpace(XyDimension(availableWidth, availableHeight))
        availableHeight -= used.height
        plotY = titleYEnd + used.height

        val legendSpace = legend.calculateSpace(XyDimension(availableWidth, chartHeight))
        availableWidth -= legendSpace.width
        if (availableWidth > 0f) chartWidth += availableWidth
        if (availableHeight > 0f) chartHeight += availableHeight
        plot.calculateSpace(XyDimension(chartWidth, chartHeight))

        plot.setPosition(XyPoint(plotX, plotY))
        legend.setPosition(
            XyPoint(
                x = plotX + chartWidth,
                y = plotY + max((chartHeight - legendSpace.height) / 2f, 0f),
            ),
        )
        yAxis.setRange(plotX, plotX + chartWidth)
        yAxis.setPosition(XyPoint(plotX, titleYEnd))
        xAxis.setRange(plotY, plotY + chartHeight)
        xAxis.setPosition(XyPoint(0f, plotY))
        if (data.plots.any { it is XyBarPlotData }) {
            xAxis.recalculateOuterPaddingToDrawBar()
        }
    }

    private fun xAxisTheme() = XyAxisTheme(
        title = theme.xAxisTitleColor,
        label = theme.xAxisLabelColor,
        tick = theme.xAxisTickColor,
        line = theme.xAxisLineColor,
    )

    private fun yAxisTheme() = XyAxisTheme(
        title = theme.yAxisTitleColor,
        label = theme.yAxisLabelColor,
        tick = theme.yAxisTickColor,
        line = theme.yAxisLineColor,
    )
}

private data class XyDimension(
    val width: Float,
    val height: Float,
)

private data class XyPoint(
    val x: Float,
    val y: Float,
)

private data class XyBounds(
    var x: Float = 0f,
    var y: Float = 0f,
    var width: Float = 0f,
    var height: Float = 0f,
)

private interface XyComponent {
    fun calculateSpace(available: XyDimension): XyDimension
    fun setPosition(point: XyPoint)
    fun elements(): List<SceneElement>
}

private class XyTextDimensions(
    private val context: MermaidRenderContext,
) {
    val fontFamily: String = context.options.fontFamily ?: context.theme.fontFamily

    fun max(
        texts: List<String>,
        fontSize: Float,
    ): TextMetrics {
        var width = 0f
        var height = 0f
        texts.forEach { text ->
            val measured = context.textMetrics.measure(
                TextMetricsRequest(
                    text = text,
                    fontSize = fontSize,
                    maxWidth = MAX_TEXT_WIDTH,
                    fontFamily = fontFamily,
                    weight = SceneTextWeight.Normal,
                ),
            )
            width = max(width, measured.width)
            height = max(height, measured.height)
        }
        return TextMetrics(width, height)
    }

    companion object {
        private const val MAX_TEXT_WIDTH = 100_000f
    }
}

private class XyChartTitle(
    private val config: MermaidXyChartOptions,
    private val data: XyChartData,
    private val theme: MermaidXyChartTheme,
    private val dimensions: XyTextDimensions,
) : XyComponent {
    private val bounds = XyBounds()
    private var visible = false

    override fun calculateSpace(available: XyDimension): XyDimension {
        val measured = dimensions.max(listOf(data.title), config.titleFontSize)
        val requiredWidth = max(measured.width, available.width)
        val requiredHeight = measured.height + 2f * config.titlePadding
        if (
            measured.width <= requiredWidth &&
            measured.height <= requiredHeight &&
            config.showTitle &&
            data.title.isNotEmpty()
        ) {
            bounds.width = requiredWidth
            bounds.height = requiredHeight
            visible = true
        }
        return XyDimension(bounds.width, bounds.height)
    }

    override fun setPosition(point: XyPoint) {
        bounds.x = point.x
        bounds.y = point.y
    }

    override fun elements(): List<SceneElement> {
        if (!visible) return emptyList()
        return listOf(
            sceneText(
                id = "xy-chart-title",
                text = data.title,
                x = bounds.x + bounds.width / 2f,
                y = bounds.y + bounds.height / 2f,
                color = theme.titleColor,
                fontSize = config.titleFontSize,
                horizontal = SceneTextAlignment.Center,
                verticalTop = false,
                dimensions = dimensions,
            ),
        )
    }
}

private class XyLegend(
    private val config: MermaidXyChartOptions,
    private val data: XyChartData,
    private val theme: MermaidXyChartTheme,
    private val dimensions: XyTextDimensions,
    private val context: MermaidRenderContext,
) : XyComponent {
    private val bounds = XyBounds()
    private var visiblePlots = emptyList<XyPlotData>()

    override fun calculateSpace(available: XyDimension): XyDimension {
        visiblePlots = if (config.showLegend) {
            data.plots.filter { plot -> plot.title.isNotEmpty() }
        } else {
            emptyList()
        }
        if (visiblePlots.isEmpty()) {
            bounds.width = 0f
            bounds.height = 0f
            return XyDimension(0f, 0f)
        }
        val layout = legendLayout(config.legendFontSize)
        val text = dimensions.max(visiblePlots.map(XyPlotData::title), layout.fontSize)
        val width = config.legendPadding * 2f +
            layout.markerSize +
            layout.markerSpacing +
            text.width
        val height = config.legendPadding * 2f +
            visiblePlots.size * layout.fontSize +
            (visiblePlots.size - 1) * layout.itemSpacing
        if (width <= available.width && height <= available.height) {
            bounds.width = width
            bounds.height = height
        } else {
            visiblePlots = emptyList()
            bounds.width = 0f
            bounds.height = 0f
        }
        return XyDimension(bounds.width, bounds.height)
    }

    override fun setPosition(point: XyPoint) {
        bounds.x = point.x
        bounds.y = point.y
    }

    override fun elements(): List<SceneElement> {
        if (visiblePlots.isEmpty()) return emptyList()
        val layout = legendLayout(config.legendFontSize)
        val rowHeight = layout.fontSize + layout.itemSpacing
        val startX = bounds.x + config.legendPadding
        val startY = bounds.y + config.legendPadding
        return buildList {
            visiblePlots.forEachIndexed { index, plot ->
                val rowY = startY + index * rowHeight
                when (plot) {
                    is XyBarPlotData -> add(
                        rectangle(
                            id = "xy-legend-bar-$index",
                            left = startX,
                            top = rowY,
                            width = layout.markerSize,
                            height = layout.markerSize,
                            color = plot.fill,
                            zIndex = 12,
                        ),
                    )
                    is XyLinePlotData -> add(
                        line(
                            id = "xy-legend-line-$index",
                            start = ScenePoint(startX, rowY + layout.markerSize / 2f),
                            end = ScenePoint(
                                startX + layout.markerSize,
                                rowY + layout.markerSize / 2f,
                            ),
                            color = plot.stroke,
                            width = plot.strokeWidth,
                            look = context.options.look,
                            zIndex = 12,
                        ),
                    )
                }
                add(
                    sceneText(
                        id = "xy-legend-label-$index",
                        text = plot.title,
                        x = startX + layout.markerSize + layout.markerSpacing,
                        y = rowY + layout.markerSize / 2f,
                        color = theme.legendTextColor,
                        fontSize = layout.fontSize,
                        horizontal = SceneTextAlignment.Start,
                        verticalTop = false,
                        dimensions = dimensions,
                    ),
                )
            }
        }
    }
}

private data class XyLegendLayout(
    val fontSize: Float,
    val markerSize: Float,
    val markerSpacing: Float,
    val itemSpacing: Float,
)

private fun legendLayout(fontSize: Float) = XyLegendLayout(
    fontSize = fontSize,
    markerSize = fontSize * 0.75f,
    markerSpacing = fontSize * 0.35f,
    itemSpacing = fontSize * 0.5f,
)

private enum class XyAxisPosition {
    Left,
    Top,
    Bottom,
}

private data class XyAxisTheme(
    val title: SceneColor,
    val label: SceneColor,
    val tick: SceneColor,
    val line: SceneColor,
)

private interface XyAxis : XyComponent {
    fun value(value: String): Float
    fun setAxisPosition(position: XyAxisPosition)
    fun setRange(start: Float, end: Float)
    fun outerPadding(): Float
    fun tickDistance(): Float
    fun recalculateOuterPaddingToDrawBar()
}

private fun createAxis(
    data: XyAxisData,
    config: MermaidXyAxisOptions,
    theme: XyAxisTheme,
    dimensions: XyTextDimensions,
    context: MermaidRenderContext,
): XyAxis = when (data) {
    is XyBandAxisData -> XyBandAxis(data, config, theme, dimensions, context)
    is XyLinearAxisData -> XyLinearAxis(data, config, theme, dimensions, context)
}

private abstract class XyBaseAxis(
    private val axisConfig: MermaidXyAxisOptions,
    protected val title: String,
    private val theme: XyAxisTheme,
    private val dimensions: XyTextDimensions,
    private val context: MermaidRenderContext,
) : XyAxis {
    protected val bounds = XyBounds()
    protected var position = XyAxisPosition.Left
    private var rangeStart = 0f
    private var rangeEnd = 10f
    private var showTitle = false
    private var showLabel = false
    private var showTick = false
    private var showAxisLine = false
    private var padding = 0f
    private var titleTextHeight = 0f
    private var labelTextHeight = 0f
    private val rotationRadians =
        if (axisConfig.labelRotation in -90f..90f) {
            axisConfig.labelRotation * kotlin.math.PI.toFloat() / 180f
        } else {
            0f
        }

    override fun setRange(start: Float, end: Float) {
        rangeStart = start
        rangeEnd = end
        if (position == XyAxisPosition.Left) {
            bounds.height = end - start
        } else {
            bounds.width = end - start
        }
        recalculateScale()
    }

    protected fun range(): Pair<Float, Float> =
        (rangeStart + padding) to (rangeEnd - padding)

    override fun setAxisPosition(position: XyAxisPosition) {
        this.position = position
        setRange(rangeStart, rangeEnd)
    }

    override fun outerPadding(): Float = padding

    override fun tickDistance(): Float {
        val tickCount = ticks().size
        if (tickCount == 0) return 0f
        val range = range()
        return abs(range.first - range.second) / tickCount
    }

    override fun recalculateOuterPaddingToDrawBar() {
        if (0.7f * tickDistance() > padding * 2f) {
            padding = floor(0.7f * tickDistance() / 2f)
        }
        recalculateScale()
    }

    override fun calculateSpace(available: XyDimension): XyDimension {
        if (position == XyAxisPosition.Left) {
            calculateVertical(available)
        } else {
            calculateHorizontal(available)
        }
        recalculateScale()
        return XyDimension(bounds.width, bounds.height)
    }

    override fun setPosition(point: XyPoint) {
        bounds.x = point.x
        bounds.y = point.y
    }

    override fun elements(): List<SceneElement> = when (position) {
        XyAxisPosition.Left -> leftElements()
        XyAxisPosition.Bottom -> bottomElements()
        XyAxisPosition.Top -> topElements()
    }

    protected abstract fun ticks(): List<String>
    protected abstract fun recalculateScale()

    private fun labelDimension(): TextMetrics =
        dimensions.max(ticks(), axisConfig.labelFontSize)

    private fun calculateHorizontal(available: XyDimension) {
        var availableHeight = available.height
        if (axisConfig.showAxisLine && availableHeight > axisConfig.axisLineWidth) {
            availableHeight -= axisConfig.axisLineWidth
            showAxisLine = true
        }
        if (axisConfig.showLabel) {
            val required = labelDimension()
            padding = min(required.width / 2f, 0.2f * available.width)
            var height = required.height
            if (position == XyAxisPosition.Bottom && rotationRadians != 0f) {
                height = max(
                    height,
                    abs(sin(rotationRadians) * required.width) +
                        abs(cos(rotationRadians) * required.height),
                )
            }
            height += axisConfig.labelPadding * 2f
            labelTextHeight = required.height
            if (height <= availableHeight) {
                availableHeight -= height
                showLabel = true
            }
        }
        if (axisConfig.showTick && availableHeight >= axisConfig.tickLength) {
            showTick = true
            availableHeight -= axisConfig.tickLength
        }
        if (axisConfig.showTitle && title.isNotEmpty()) {
            val required = dimensions.max(listOf(title), axisConfig.titleFontSize)
            val height = required.height + axisConfig.titlePadding * 2f
            titleTextHeight = required.height
            if (height <= availableHeight) {
                availableHeight -= height
                showTitle = true
            }
        }
        bounds.width = available.width
        bounds.height = available.height - availableHeight
    }

    private fun calculateVertical(available: XyDimension) {
        var availableWidth = available.width
        if (axisConfig.showAxisLine && availableWidth > axisConfig.axisLineWidth) {
            availableWidth -= axisConfig.axisLineWidth
            showAxisLine = true
        }
        if (axisConfig.showLabel) {
            val required = labelDimension()
            padding = min(required.height / 2f, 0.2f * available.height)
            val width = required.width + axisConfig.labelPadding * 2f
            if (width <= availableWidth) {
                availableWidth -= width
                showLabel = true
            }
        }
        if (axisConfig.showTick && availableWidth >= axisConfig.tickLength) {
            showTick = true
            availableWidth -= axisConfig.tickLength
        }
        if (axisConfig.showTitle && title.isNotEmpty()) {
            val required = dimensions.max(listOf(title), axisConfig.titleFontSize)
            val width = required.height + axisConfig.titlePadding * 2f
            titleTextHeight = required.height
            if (width <= availableWidth) {
                availableWidth -= width
                showTitle = true
            }
        }
        bounds.width = available.width - availableWidth
        bounds.height = available.height
    }

    private fun rotationOffset(width: Boolean): Float {
        if (rotationRadians == 0f) return 0f
        val size = labelDimension()
        return sin(rotationRadians) * if (width) size.width / 2f else size.height / 2f
    }

    private fun leftElements(): List<SceneElement> = buildList {
        if (showAxisLine) {
            val x = bounds.x + bounds.width - axisConfig.axisLineWidth / 2f
            add(axisLine("xy-left-axis-line", x, bounds.y, x, bounds.y + bounds.height))
        }
        if (showLabel) {
            ticks().forEachIndexed { index, tick ->
                add(
                    sceneText(
                        id = "xy-left-axis-label-$index",
                        text = tick,
                        x = bounds.x + bounds.width -
                            axisConfig.labelPadding -
                            (if (showTick) axisConfig.tickLength else 0f) -
                            (if (showAxisLine) axisConfig.axisLineWidth else 0f),
                        y = value(tick),
                        color = theme.label,
                        fontSize = axisConfig.labelFontSize,
                        horizontal = SceneTextAlignment.End,
                        verticalTop = false,
                        dimensions = dimensions,
                    ),
                )
            }
        }
        if (showTick) {
            val x = bounds.x + bounds.width -
                (if (showAxisLine) axisConfig.axisLineWidth else 0f)
            ticks().forEachIndexed { index, tick ->
                add(axisLine("xy-left-axis-tick-$index", x, value(tick), x - axisConfig.tickLength, value(tick), tick = true))
            }
        }
        if (showTitle) {
            val x = bounds.x + axisConfig.titlePadding
            val y = bounds.y + bounds.height / 2f
            add(
                sceneText(
                    id = "xy-left-axis-title",
                    text = title,
                    x = x,
                    y = y,
                    color = theme.title,
                    fontSize = axisConfig.titleFontSize,
                    horizontal = SceneTextAlignment.Center,
                    verticalTop = true,
                    dimensions = dimensions,
                    rotation = 270f,
                ),
            )
        }
    }

    private fun bottomElements(): List<SceneElement> = buildList {
        if (showAxisLine) {
            val y = bounds.y + axisConfig.axisLineWidth / 2f
            add(axisLine("xy-bottom-axis-line", bounds.x, y, bounds.x + bounds.width, y))
        }
        if (showLabel) {
            ticks().forEachIndexed { index, tick ->
                val x = value(tick) + rotationOffset(width = false)
                val y = bounds.y +
                    axisConfig.labelPadding +
                    (if (showTick) axisConfig.tickLength else 0f) +
                    (if (showAxisLine) axisConfig.axisLineWidth else 0f) +
                    abs(rotationOffset(width = true))
                add(
                    sceneText(
                        id = "xy-bottom-axis-label-$index",
                        text = tick,
                        x = x,
                        y = y,
                        color = theme.label,
                        fontSize = axisConfig.labelFontSize,
                        horizontal = SceneTextAlignment.Center,
                        verticalTop = true,
                        dimensions = dimensions,
                        rotation = axisConfig.labelRotation,
                    ),
                )
            }
        }
        if (showTick) {
            val y = bounds.y + if (showAxisLine) axisConfig.axisLineWidth else 0f
            ticks().forEachIndexed { index, tick ->
                add(axisLine("xy-bottom-axis-tick-$index", value(tick), y, value(tick), y + axisConfig.tickLength, tick = true))
            }
        }
        if (showTitle) {
            add(
                sceneText(
                    id = "xy-bottom-axis-title",
                    text = title,
                    x = rangeStart + (rangeEnd - rangeStart) / 2f,
                    y = bounds.y + bounds.height - axisConfig.titlePadding - titleTextHeight,
                    color = theme.title,
                    fontSize = axisConfig.titleFontSize,
                    horizontal = SceneTextAlignment.Center,
                    verticalTop = true,
                    dimensions = dimensions,
                ),
            )
        }
    }

    private fun topElements(): List<SceneElement> = buildList {
        if (showAxisLine) {
            val y = bounds.y + bounds.height - axisConfig.axisLineWidth / 2f
            add(axisLine("xy-top-axis-line", bounds.x, y, bounds.x + bounds.width, y))
        }
        if (showLabel) {
            ticks().forEachIndexed { index, tick ->
                add(
                    sceneText(
                        id = "xy-top-axis-label-$index",
                        text = tick,
                        x = value(tick),
                        y = bounds.y +
                            (if (showTitle) {
                                titleTextHeight + axisConfig.titlePadding * 2f
                            } else {
                                0f
                            }) +
                            axisConfig.labelPadding,
                        color = theme.label,
                        fontSize = axisConfig.labelFontSize,
                        horizontal = SceneTextAlignment.Center,
                        verticalTop = true,
                        dimensions = dimensions,
                    ),
                )
            }
        }
        if (showTick) {
            val y = bounds.y
            ticks().forEachIndexed { index, tick ->
                val bottom = y + bounds.height -
                    (if (showAxisLine) axisConfig.axisLineWidth else 0f)
                add(axisLine("xy-top-axis-tick-$index", value(tick), bottom, value(tick), bottom - axisConfig.tickLength, tick = true))
            }
        }
        if (showTitle) {
            add(
                sceneText(
                    id = "xy-top-axis-title",
                    text = title,
                    x = bounds.x + bounds.width / 2f,
                    y = bounds.y + axisConfig.titlePadding,
                    color = theme.title,
                    fontSize = axisConfig.titleFontSize,
                    horizontal = SceneTextAlignment.Center,
                    verticalTop = true,
                    dimensions = dimensions,
                ),
            )
        }
    }

    private fun axisLine(
        id: String,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        tick: Boolean = false,
    ): ScenePath = line(
        id = id,
        start = ScenePoint(x1, y1),
        end = ScenePoint(x2, y2),
        color = if (tick) theme.tick else theme.line,
        width = if (tick) axisConfig.tickWidth else axisConfig.axisLineWidth,
        look = context.options.look,
        zIndex = 15,
    )
}

private class XyBandAxis(
    private val data: XyBandAxisData,
    config: MermaidXyAxisOptions,
    theme: XyAxisTheme,
    dimensions: XyTextDimensions,
    context: MermaidRenderContext,
) : XyBaseAxis(config, data.title, theme, dimensions, context) {
    override fun value(value: String): Float {
        val range = range()
        return XyScalePort.band(value, data.categories, range.first, range.second)
    }

    override fun ticks(): List<String> = data.categories

    override fun recalculateScale() = Unit
}

private class XyLinearAxis(
    private val data: XyLinearAxisData,
    config: MermaidXyAxisOptions,
    theme: XyAxisTheme,
    dimensions: XyTextDimensions,
    context: MermaidRenderContext,
) : XyBaseAxis(config, data.title, theme, dimensions, context) {
    private var domainStart = data.min
    private var domainEnd = data.max

    override fun value(value: String): Float {
        val number = value.toDoubleOrNull() ?: return Float.NaN
        val range = range()
        return XyScalePort.linear(number, domainStart, domainEnd, range.first, range.second)
    }

    override fun ticks(): List<String> =
        XyScalePort.ticks(domainStart, domainEnd).map(XyScalePort::formatNumber)

    override fun recalculateScale() {
        if (position == XyAxisPosition.Left) {
            domainStart = data.max
            domainEnd = data.min
        } else {
            domainStart = data.min
            domainEnd = data.max
        }
    }
}

private class XyPlot(
    private val config: MermaidXyChartOptions,
    private val data: XyChartData,
    private val theme: MermaidXyChartTheme,
    private val context: MermaidRenderContext,
) : XyComponent {
    private val bounds = XyBounds()
    private var xAxis: XyAxis? = null
    private var yAxis: XyAxis? = null

    fun setAxes(
        xAxis: XyAxis,
        yAxis: XyAxis,
    ) {
        this.xAxis = xAxis
        this.yAxis = yAxis
    }

    override fun calculateSpace(available: XyDimension): XyDimension {
        bounds.width = available.width
        bounds.height = available.height
        return available
    }

    override fun setPosition(point: XyPoint) {
        bounds.x = point.x
        bounds.y = point.y
    }

    override fun elements(): List<SceneElement> {
        val horizontalAxis = xAxis ?: return emptyList()
        val verticalAxis = yAxis ?: return emptyList()
        val firstPlotLabels = data.plots.firstOrNull()?.data.orEmpty().map { point ->
            XyScalePort.formatNumber(point.y)
        }
        return buildList {
            data.plots.forEachIndexed { index, plot ->
                when (plot) {
                    is XyLinePlotData -> addAll(
                        lineElements(plot, index, horizontalAxis, verticalAxis),
                    )
                    is XyBarPlotData -> {
                        val bars = barElements(plot, index, horizontalAxis, verticalAxis)
                        addAll(bars)
                        if (config.showDataLabel) {
                            addAll(barLabels(bars, firstPlotLabels, index))
                        }
                    }
                }
            }
        }
    }

    private fun lineElements(
        plot: XyLinePlotData,
        index: Int,
        xAxis: XyAxis,
        yAxis: XyAxis,
    ): List<SceneElement> {
        val points = plot.data.map { point ->
            val x = xAxis.value(point.x)
            val y = yAxis.value(XyScalePort.formatNumber(point.y))
            if (data.orientation == XyChartOrientation.Horizontal) {
                ScenePoint(y, x)
            } else {
                ScenePoint(x, y)
            }
        }.filter { point -> point.x.isFinite() && point.y.isFinite() }
        if (points.isEmpty()) return emptyList()
        val path = ScenePath(
            id = "xy-line-$index",
            points = points,
            commands = buildList {
                add(ScenePathCommand.MoveTo(points.first()))
                points.drop(1).forEach { point ->
                    add(ScenePathCommand.LineTo(point))
                }
            },
            color = plot.stroke,
            strokeWidth = plot.strokeWidth,
            strokePattern = SceneStrokePattern.Solid,
            arrowStart = SceneArrowHead.None,
            arrowEnd = SceneArrowHead.None,
            curve = "linear",
            look = context.options.look,
            animated = false,
            zIndex = 8,
        )
        val labels = plot.pointLabels.orEmpty().mapIndexedNotNull { pointIndex, label ->
            if (label.isEmpty()) return@mapIndexedNotNull null
            val point = points.getOrNull(pointIndex) ?: return@mapIndexedNotNull null
            val x: Float
            val y: Float
            val alignment: SceneTextAlignment
            if (data.orientation == XyChartOrientation.Horizontal) {
                x = point.x + 10f
                y = point.y
                alignment = SceneTextAlignment.Start
            } else {
                x = point.x
                y = point.y - 10f
                alignment = SceneTextAlignment.Center
            }
            sceneText(
                id = "xy-line-$index-label-$pointIndex",
                text = label,
                x = x,
                y = y,
                color = plot.stroke,
                fontSize = 12f,
                horizontal = alignment,
                verticalTop = false,
                dimensions = XyTextDimensions(context),
                zIndex = 18,
            )
        }
        return listOf(path) + labels
    }

    private fun barElements(
        plot: XyBarPlotData,
        index: Int,
        xAxis: XyAxis,
        yAxis: XyAxis,
    ): List<SceneShape> {
        val barWidth = min(xAxis.outerPadding() * 2f, xAxis.tickDistance()) * 0.95f
        val half = barWidth / 2f
        return plot.data.mapIndexedNotNull { pointIndex, point ->
            val x = xAxis.value(point.x)
            val y = yAxis.value(XyScalePort.formatNumber(point.y))
            val left: Float
            val top: Float
            val width: Float
            val height: Float
            if (data.orientation == XyChartOrientation.Horizontal) {
                left = bounds.x
                top = x - half
                width = y - bounds.x
                height = barWidth
            } else {
                left = x - half
                top = y
                width = barWidth
                height = bounds.y + bounds.height - y
            }
            if (
                !left.isFinite() ||
                !top.isFinite() ||
                !width.isFinite() ||
                !height.isFinite() ||
                width < 0f ||
                height < 0f
            ) {
                null
            } else {
                rectangle(
                    id = "xy-bar-$index-$pointIndex",
                    left = left,
                    top = top,
                    width = width,
                    height = height,
                    color = plot.fill,
                    zIndex = 7,
                )
            }
        }
    }

    private fun barLabels(
        bars: List<SceneShape>,
        labels: List<String>,
        plotIndex: Int,
    ): List<SceneText> {
        val valid = bars.filter { bar -> bar.bounds.width > 0f && bar.bounds.height > 0f }
        if (valid.isEmpty()) return emptyList()
        return if (data.orientation == XyChartOrientation.Horizontal) {
            horizontalBarLabels(valid, labels, plotIndex)
        } else {
            verticalBarLabels(valid, labels, plotIndex)
        }
    }

    private fun horizontalBarLabels(
        bars: List<SceneShape>,
        labels: List<String>,
        plotIndex: Int,
    ): List<SceneText> {
        // Mermaid: xychartRenderer.ts -> draw(rect) calculates this from the
        // in-bar fit even when labels are placed outside the bar.
        val candidates = bars.mapIndexed { index, bar ->
            var candidate = bar.bounds.height * 0.7f
            val label = labels.getOrElse(index) { "" }
            while (
                candidate > 0f &&
                candidate * label.length * 0.7f > bar.bounds.width - 10f
            ) {
                candidate -= 1f
            }
            candidate
        }
        val configuredFontSize = floor(candidates.minOrNull() ?: 0f)
        // A negative SVG font-size attribute is invalid. Browsers ignore it
        // and use the inherited initial font size, which is 16px here.
        val fontSize = if (configuredFontSize < 0f) 16f else configuredFontSize
        if (fontSize <= 0f) return emptyList()
        val dimensions = XyTextDimensions(context)
        return bars.mapIndexed { index, bar ->
            sceneText(
                id = "xy-bar-$plotIndex-label-$index",
                text = labels.getOrElse(index) { "" },
                x = if (config.showDataLabelOutsideBar) {
                    bar.bounds.right + 10f
                } else {
                    bar.bounds.right - 10f
                },
                y = bar.bounds.center.y,
                color = theme.dataLabelColor,
                fontSize = fontSize,
                horizontal = if (config.showDataLabelOutsideBar) {
                    SceneTextAlignment.Start
                } else {
                    SceneTextAlignment.End
                },
                verticalTop = false,
                dimensions = dimensions,
                zIndex = 18,
            )
        }
    }

    private fun verticalBarLabels(
        bars: List<SceneShape>,
        labels: List<String>,
        plotIndex: Int,
    ): List<SceneText> {
        val candidates = bars.mapIndexed { index, bar ->
            val label = labels.getOrElse(index) { "" }
            var fontSize = if (label.isEmpty()) 0f else bar.bounds.width / (label.length * 0.7f)
            while (fontSize > 0f) {
                val textWidth = fontSize * label.length * 0.7f
                val horizontalFits = textWidth <= bar.bounds.width
                val verticalFits = bar.bounds.top + 10f + fontSize <= bar.bounds.bottom
                if (horizontalFits && verticalFits) break
                fontSize -= 1f
            }
            fontSize
        }
        val fontSize = floor(candidates.minOrNull() ?: 0f)
        if (fontSize <= 0f) return emptyList()
        val dimensions = XyTextDimensions(context)
        return bars.mapIndexed { index, bar ->
            val targetY = if (config.showDataLabelOutsideBar) {
                bar.bounds.top - 10f - fontSize
            } else {
                bar.bounds.top + 10f
            }
            sceneText(
                id = "xy-bar-$plotIndex-label-$index",
                text = labels.getOrElse(index) { "" },
                x = bar.bounds.center.x,
                y = targetY,
                color = theme.dataLabelColor,
                fontSize = fontSize,
                horizontal = SceneTextAlignment.Center,
                verticalTop = true,
                dimensions = dimensions,
                zIndex = 18,
            )
        }
    }
}

private fun sceneText(
    id: String,
    text: String,
    x: Float,
    y: Float,
    color: SceneColor,
    fontSize: Float,
    horizontal: SceneTextAlignment,
    verticalTop: Boolean,
    dimensions: XyTextDimensions,
    rotation: Float = 0f,
    zIndex: Int = 20,
): SceneText {
    val measured = dimensions.max(listOf(text), fontSize)
    val left = when (horizontal) {
        SceneTextAlignment.Start -> x - 4f
        SceneTextAlignment.Center -> x - measured.width / 2f
        SceneTextAlignment.End -> x + 4f - measured.width
    }
    val top = if (verticalTop) y else y - measured.height / 2f
    return SceneText(
        text = text,
        bounds = SceneRect(
            left = left,
            top = top,
            right = left + measured.width.coerceAtLeast(1f),
            bottom = top + measured.height.coerceAtLeast(fontSize),
        ),
        color = color,
        fontSize = fontSize,
        fontFamily = dimensions.fontFamily,
        weight = SceneTextWeight.Normal,
        horizontalAlignment = horizontal,
        rotationDegrees = rotation,
        rotationPivot = ScenePoint(x, y),
        zIndex = zIndex,
        softWrap = false,
    )
}

private fun rectangle(
    id: String,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    color: SceneColor,
    zIndex: Int,
): SceneShape = SceneShape(
    id = id,
    bounds = SceneRect(left, top, left + width, top + height),
    kind = SceneShapeKind.Rectangle,
    fill = color,
    stroke = color,
    strokeWidth = 0f,
    cornerRadius = 0f,
    shadow = null,
    zIndex = zIndex,
)

private fun line(
    id: String,
    start: ScenePoint,
    end: ScenePoint,
    color: SceneColor,
    width: Float,
    look: String,
    zIndex: Int,
): ScenePath = ScenePath(
    id = id,
    points = listOf(start, end),
    commands = listOf(
        ScenePathCommand.MoveTo(start),
        ScenePathCommand.LineTo(end),
    ),
    color = color,
    strokeWidth = width,
    strokePattern = SceneStrokePattern.Solid,
    arrowStart = SceneArrowHead.None,
    arrowEnd = SceneArrowHead.None,
    curve = "linear",
    look = look,
    animated = false,
    zIndex = zIndex,
)
