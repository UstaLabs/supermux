package com.swithun.cmpmermaid.core.xychart.upstream.mermaid

import com.swithun.cmpmermaid.core.SceneColor

internal enum class XyChartOrientation {
    Vertical,
    Horizontal,
}

internal sealed interface XyAxisData {
    val title: String
}

internal data class XyBandAxisData(
    override val title: String,
    val categories: List<String>,
) : XyAxisData

internal data class XyLinearAxisData(
    override val title: String,
    val min: Double,
    val max: Double,
) : XyAxisData

internal data class XyDataPoint(
    val x: String,
    val y: Double,
)

internal data class XyParsedDataPoint(
    val value: Double,
    val label: String,
)

internal data class XyText(
    val text: String,
    val markdown: Boolean = false,
)

internal sealed interface XyPlotData {
    val title: String
    val data: List<XyDataPoint>
}

internal data class XyLinePlotData(
    override val title: String,
    val stroke: SceneColor,
    val strokeWidth: Float,
    override val data: List<XyDataPoint>,
    val pointLabels: List<String>?,
) : XyPlotData

internal data class XyBarPlotData(
    override val title: String,
    val fill: SceneColor,
    override val data: List<XyDataPoint>,
) : XyPlotData

internal data class XyChartData(
    val xAxis: XyAxisData,
    val yAxis: XyLinearAxisData,
    val title: String,
    val plots: List<XyPlotData>,
    val orientation: XyChartOrientation,
    val accessibilityTitle: String?,
    val accessibilityDescription: String?,
)
