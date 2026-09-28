package com.swithun.cmpmermaid.core.xychart.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidPreprocessor
import com.swithun.cmpmermaid.core.SceneColor

/**
 * Kotlin translation of Mermaid 12.0.0 xychartDb.ts.
 */
internal class XyDb(
    diagramTitle: String? = null,
    private val plotColorPalette: List<SceneColor>,
    initialOrientation: String = "vertical",
) {
    private var plotIndex = 0
    private var xAxis: XyAxisData = XyBandAxisData(title = "", categories = emptyList())
    private var yAxis = XyLinearAxisData(
        title = "",
        min = Double.POSITIVE_INFINITY,
        max = Double.NEGATIVE_INFINITY,
    )
    private val plots = mutableListOf<XyPlotData>()
    private var hasSetXAxis = false
    private var hasSetYAxis = false
    private var orientation = if (initialOrientation == "horizontal") {
        XyChartOrientation.Horizontal
    } else {
        XyChartOrientation.Vertical
    }
    private var title = diagramTitle.orEmpty()
    private var accessibilityTitle: String? = null
    private var accessibilityDescription: String? = null

    fun setOrientation(value: String) {
        orientation = if (value == "horizontal") {
            XyChartOrientation.Horizontal
        } else {
            XyChartOrientation.Vertical
        }
    }

    fun setXAxisTitle(value: XyText) {
        xAxis = when (val current = xAxis) {
            is XyBandAxisData -> current.copy(title = sanitize(value.text))
            is XyLinearAxisData -> current.copy(title = sanitize(value.text))
        }
    }

    fun setXAxisRangeData(
        min: Double,
        max: Double,
    ): GMResult<Unit, MermaidError> {
        if (!min.isFinite() || !max.isFinite()) {
            return invalidNumber("x-axis")
        }
        xAxis = XyLinearAxisData(title = xAxis.title, min = min, max = max)
        hasSetXAxis = true
        return GMResult.Ok(Unit)
    }

    fun setXAxisBand(categories: List<XyText>) {
        xAxis = XyBandAxisData(
            title = xAxis.title,
            categories = categories.map { category -> sanitize(category.text) },
        )
        hasSetXAxis = true
    }

    fun setYAxisTitle(value: XyText) {
        yAxis = yAxis.copy(title = sanitize(value.text))
    }

    fun setYAxisRangeData(
        min: Double,
        max: Double,
    ): GMResult<Unit, MermaidError> {
        if (!min.isFinite() || !max.isFinite()) {
            return invalidNumber("y-axis")
        }
        yAxis = yAxis.copy(min = min, max = max)
        hasSetYAxis = true
        return GMResult.Ok(Unit)
    }

    fun setLineData(
        title: XyText,
        data: List<XyParsedDataPoint>,
    ): GMResult<Unit, MermaidError> {
        val color = plotColor()
            ?: return GMResult.Err(
                MermaidError.Configuration(
                    "Mermaid XY Chart plotColorPalette must contain at least one color",
                ),
            )
        val transformed = when (val result = transformDataWithoutCategory(data.map { it.value })) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val labels = data.map { point ->
            point.label.takeIf(String::isNotEmpty)?.let(::sanitize).orEmpty()
        }
        plots += XyLinePlotData(
            title = sanitize(title.text),
            stroke = color,
            strokeWidth = 2f,
            data = transformed,
            pointLabels = labels.takeIf { values -> values.any(String::isNotEmpty) },
        )
        plotIndex += 1
        return GMResult.Ok(Unit)
    }

    fun setBarData(
        title: XyText,
        data: List<XyParsedDataPoint>,
    ): GMResult<Unit, MermaidError> {
        val color = plotColor()
            ?: return GMResult.Err(
                MermaidError.Configuration(
                    "Mermaid XY Chart plotColorPalette must contain at least one color",
                ),
            )
        val transformed = when (val result = transformDataWithoutCategory(data.map { it.value })) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        plots += XyBarPlotData(
            title = sanitize(title.text),
            fill = color,
            data = transformed,
        )
        plotIndex += 1
        return GMResult.Ok(Unit)
    }

    fun setDiagramTitle(value: String) {
        title = sanitize(value)
    }

    fun setAccessibilityTitle(value: String) {
        accessibilityTitle = sanitize(value)
    }

    fun setAccessibilityDescription(value: String) {
        accessibilityDescription = sanitize(value)
    }

    fun compile(): GMResult<XyChartData, MermaidError> {
        if (plots.isEmpty()) {
            return GMResult.Err(
                MermaidError.Layout("No Plot to render, please provide a plot with some data"),
            )
        }
        if (!yAxis.min.isFinite() || !yAxis.max.isFinite()) {
            return GMResult.Err(
                MermaidError.Layout("XY Chart could not determine a finite y-axis range"),
            )
        }
        val currentXAxis = xAxis
        if (
            currentXAxis is XyLinearAxisData &&
            (!currentXAxis.min.isFinite() || !currentXAxis.max.isFinite())
        ) {
            return GMResult.Err(
                MermaidError.Layout("XY Chart could not determine a finite x-axis range"),
            )
        }
        return GMResult.Ok(
            XyChartData(
                xAxis = currentXAxis,
                yAxis = yAxis,
                title = title,
                plots = plots.toList(),
                orientation = orientation,
                accessibilityTitle = accessibilityTitle,
                accessibilityDescription = accessibilityDescription,
            ),
        )
    }

    private fun transformDataWithoutCategory(
        source: List<Double>,
    ): GMResult<List<XyDataPoint>, MermaidError> {
        if (source.any { value -> !value.isFinite() }) {
            return invalidNumber("plot")
        }
        if (source.isEmpty()) {
            return GMResult.Ok(emptyList())
        }
        if (!hasSetXAxis) {
            val current = xAxis as? XyLinearAxisData
            val previousMin = current?.min ?: Double.POSITIVE_INFINITY
            val previousMax = current?.max ?: Double.NEGATIVE_INFINITY
            when (
                val result = setXAxisRangeData(
                    min = minOf(previousMin, 1.0),
                    max = maxOf(previousMax, source.size.toDouble()),
                )
            ) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return result
            }
        }

        val visibleData = when (val current = xAxis) {
            is XyBandAxisData -> source.take(current.categories.size)
            is XyLinearAxisData -> source
        }
        if (!hasSetYAxis && visibleData.isNotEmpty()) {
            yAxis = yAxis.copy(
                min = minOf(yAxis.min, visibleData.min()),
                max = maxOf(yAxis.max, visibleData.max()),
            )
        }

        val transformed = when (val current = xAxis) {
            is XyBandAxisData -> current.categories.mapIndexedNotNull { index, category ->
                visibleData.getOrNull(index)?.let { value ->
                    XyDataPoint(x = category, y = value)
                }
            }
            is XyLinearAxisData -> if (source.size == 1) {
                listOf(XyDataPoint(x = formatNumber(current.min), y = source.first()))
            } else {
                val step = (current.max - current.min) / (source.size - 1)
                source.mapIndexed { index, value ->
                    XyDataPoint(
                        x = formatNumber(current.min + index * step),
                        y = value,
                    )
                }
            }
        }
        return GMResult.Ok(transformed)
    }

    private fun plotColor(): SceneColor? =
        plotColorPalette.getOrNull(
            if (plotIndex == 0) 0 else plotIndex % plotColorPalette.size.coerceAtLeast(1),
        )

    private fun sanitize(value: String): String =
        MermaidPreprocessor.decodeEntities(value.trim())

    private fun <T> invalidNumber(owner: String): GMResult<T, MermaidError> =
        GMResult.Err(
            MermaidError.Parse(
                line = 1,
                column = 1,
                message = "XY Chart $owner data must contain finite numbers",
            ),
        )

    private fun formatNumber(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}
