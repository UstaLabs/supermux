package com.swithun.cmpmermaid.core.radar.upstream.mermaid

internal data class RadarAst(
    val title: String? = null,
    val accessibilityTitle: String? = null,
    val accessibilityDescription: String? = null,
    val axes: List<RadarAstAxis> = emptyList(),
    val curves: List<RadarAstCurve> = emptyList(),
    val options: List<RadarAstOption> = emptyList(),
)

internal data class RadarAstAxis(
    val name: String,
    val label: String?,
    val line: Int,
    val column: Int,
)

internal data class RadarAstCurve(
    val name: String,
    val label: String?,
    val entries: List<RadarAstEntry>,
    val line: Int,
    val column: Int,
)

internal data class RadarAstEntry(
    val axisName: String?,
    val value: Double,
    val line: Int,
    val column: Int,
)

internal sealed interface RadarAstOption {
    val name: String

    data class ShowLegend(
        val value: Boolean,
    ) : RadarAstOption {
        override val name: String = "showLegend"
    }

    data class Ticks(
        val value: Double,
    ) : RadarAstOption {
        override val name: String = "ticks"
    }

    data class Maximum(
        val value: Double,
    ) : RadarAstOption {
        override val name: String = "max"
    }

    data class Minimum(
        val value: Double,
    ) : RadarAstOption {
        override val name: String = "min"
    }

    data class Graticule(
        val value: RadarGraticule,
    ) : RadarAstOption {
        override val name: String = "graticule"
    }
}

internal data class RadarAxis(
    val name: String,
    val label: String,
)

internal data class RadarCurve(
    val name: String,
    val label: String,
    val entries: List<Double>,
)

internal data class RadarOptions(
    val showLegend: Boolean = true,
    val ticks: Double = 5.0,
    val max: Double? = null,
    val min: Double = 0.0,
    val graticule: RadarGraticule = RadarGraticule.Circle,
)

internal enum class RadarGraticule {
    Circle,
    Polygon,
}
