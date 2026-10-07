package com.swithun.cmpmermaid.core.radar.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidPreprocessor
import com.swithun.cmpmermaid.core.MermaidRadarOptions

/**
 * Kotlin translation of Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/radar/db.ts.
 */
internal class RadarDb(
    val config: MermaidRadarOptions,
    diagramTitle: String?,
) {
    private var axes: List<RadarAxis> = emptyList()
    private var curves: List<RadarCurve> = emptyList()
    private var options: RadarOptions = RadarOptions()

    var diagramTitle: String? = diagramTitle
        private set
    var accessibilityTitle: String? = null
        private set
    var accessibilityDescription: String? = null
        private set

    fun getAxes(): List<RadarAxis> = axes

    fun getCurves(): List<RadarCurve> = curves

    fun getOptions(): RadarOptions = options

    // Mermaid.js 12.0.0: radar/db.ts -> setAxes.
    fun setAxes(source: List<RadarAstAxis>) {
        axes = source.map { axis ->
            RadarAxis(
                name = axis.name,
                label = decode(axis.label ?: axis.name),
            )
        }
    }

    // Mermaid.js 12.0.0: radar/db.ts -> setCurves.
    fun setCurves(source: List<RadarAstCurve>): GMResult<Unit, MermaidError> {
        val resolved = mutableListOf<RadarCurve>()
        source.forEach { curve ->
            val entries = when (val result = computeCurveEntries(curve.entries)) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            resolved += RadarCurve(
                name = curve.name,
                label = decode(curve.label ?: curve.name),
                entries = entries,
            )
        }
        curves = resolved
        return GMResult.Ok(Unit)
    }

    // Mermaid.js 12.0.0: radar/db.ts -> computeCurveEntries.
    fun computeCurveEntries(
        entries: List<RadarAstEntry>,
    ): GMResult<List<Double>, MermaidError> {
        if (entries.firstOrNull()?.axisName == null) {
            return GMResult.Ok(entries.map(RadarAstEntry::value))
        }
        if (axes.isEmpty()) {
            return GMResult.Err(
                MermaidError.Parse(
                    line = entries.first().line,
                    column = entries.first().column,
                    message = "Axes must be populated before curves for reference entries",
                ),
            )
        }
        val values = mutableListOf<Double>()
        axes.forEach { axis ->
            val entry = entries.firstOrNull { candidate -> candidate.axisName == axis.name }
                ?: return GMResult.Err(
                    MermaidError.Parse(
                        line = entries.first().line,
                        column = entries.first().column,
                        message = "Missing entry for axis ${axis.label}",
                    ),
                )
            values += entry.value
        }
        return GMResult.Ok(values)
    }

    // Mermaid.js 12.0.0: radar/db.ts -> setOptions.
    fun setOptions(source: List<RadarAstOption>) {
        var resolved = RadarOptions()
        source.forEach { option ->
            resolved = when (option) {
                is RadarAstOption.ShowLegend ->
                    resolved.copy(showLegend = option.value)
                is RadarAstOption.Ticks ->
                    resolved.copy(ticks = option.value.coerceAtMost(MAX_TICKS))
                is RadarAstOption.Maximum ->
                    resolved.copy(max = option.value)
                is RadarAstOption.Minimum ->
                    resolved.copy(min = option.value)
                is RadarAstOption.Graticule ->
                    resolved.copy(graticule = option.value)
            }
        }
        options = resolved
    }

    fun setDiagramTitle(value: String) {
        if (value.isNotEmpty()) {
            diagramTitle = decode(value)
        }
    }

    fun setAccessibilityTitle(value: String) {
        if (value.isNotEmpty()) {
            accessibilityTitle = decode(value)
        }
    }

    fun setAccessibilityDescription(value: String) {
        if (value.isNotEmpty()) {
            accessibilityDescription = decode(value)
        }
    }

    // Mermaid.js 12.0.0: radar/db.ts -> clear.
    fun clear() {
        axes = emptyList()
        curves = emptyList()
        options = RadarOptions()
        diagramTitle = null
        accessibilityTitle = null
        accessibilityDescription = null
    }

    private fun decode(value: String): String = MermaidPreprocessor.decodeEntities(value)

    private companion object {
        const val MAX_TICKS = 32.0
    }
}
