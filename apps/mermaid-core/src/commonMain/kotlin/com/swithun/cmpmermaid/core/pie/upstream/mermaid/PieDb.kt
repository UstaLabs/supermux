package com.swithun.cmpmermaid.core.pie.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidPreprocessor

/**
 * Kotlin translation of Mermaid 12.0.0 pieDb.ts.
 */
internal class PieDb(
    diagramTitle: String? = null,
) {
    private val sections = linkedMapOf<String, Double>()
    private var showData = false

    var diagramTitle: String? = diagramTitle
        private set
    var accessibilityTitle: String? = null
        private set
    var accessibilityDescription: String? = null
        private set

    fun addSection(
        label: String,
        value: Double,
        line: Int,
        column: Int,
    ): GMResult<Unit, MermaidError> {
        val decodedLabel = MermaidPreprocessor.decodeEntities(label)
        if (value < 0.0) {
            return GMResult.Err(
                MermaidError.Parse(
                    line = line,
                    column = column,
                    message = "\"$decodedLabel\" has invalid value: ${formatNumber(value)}. " +
                        "Negative values are not allowed in pie charts. " +
                        "All slice values must be >= 0.",
                ),
            )
        }
        if (decodedLabel !in sections) {
            sections[decodedLabel] = value
        }
        return GMResult.Ok(Unit)
    }

    fun getSections(): Map<String, Double> = sections

    fun setShowData(value: Boolean) {
        showData = value
    }

    fun getShowData(): Boolean = showData

    fun setDiagramTitle(value: String) {
        diagramTitle = MermaidPreprocessor.decodeEntities(value)
    }

    fun setAccessibilityTitle(value: String) {
        accessibilityTitle = MermaidPreprocessor.decodeEntities(value)
    }

    fun setAccessibilityDescription(value: String) {
        accessibilityDescription = MermaidPreprocessor.decodeEntities(value)
    }

    private fun formatNumber(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}
