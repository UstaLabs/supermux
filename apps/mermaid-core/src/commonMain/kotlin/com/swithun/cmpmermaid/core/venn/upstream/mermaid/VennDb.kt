package com.swithun.cmpmermaid.core.venn.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidVennOptions
import kotlin.math.pow

/**
 * Kotlin translation of Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/venn/vennDB.ts.
 */
internal class VennDb(
    val config: MermaidVennOptions,
    diagramTitle: String?,
) {
    private val subsets = mutableListOf<VennData>()
    private val textNodes = mutableListOf<VennTextData>()
    private val styleEntries = mutableListOf<VennStyleData>()
    private val knownSets = linkedSetOf<String>()

    var diagramTitle: String? = diagramTitle
        private set
    var currentSets: List<String>? = null
        private set

    fun addSubsetData(
        identifierList: List<String>,
        label: String?,
        size: Double?,
    ) {
        val sets = normalizeIdentifierList(identifierList).sorted()
        val resolvedSize = size ?: 10.0 / identifierList.size.toDouble().pow(2)
        currentSets = sets
        if (sets.size == 1) {
            knownSets += sets.first()
        }
        subsets += VennData(
            sets = sets,
            size = resolvedSize,
            label = label?.let(::normalizeText),
        )
    }

    fun addTextData(
        identifierList: List<String>,
        id: String,
        label: String?,
    ) {
        textNodes += VennTextData(
            sets = normalizeIdentifierList(identifierList).sorted(),
            id = normalizeText(id),
            label = label?.let(::normalizeText),
        )
    }

    fun addStyleData(
        identifierList: List<String>,
        data: List<Pair<String, String>>,
    ) {
        styleEntries += VennStyleData(
            targets = normalizeIdentifierList(identifierList).sorted(),
            styles = buildMap {
                data.forEach { (key, value) -> put(key, normalizeText(value)) }
            },
        )
    }

    fun validateUnionIdentifiers(
        identifierList: List<String>,
        line: Int,
        column: Int,
        lineOffset: Int,
    ): GMResult<Unit, MermaidError> {
        val unknown = normalizeIdentifierList(identifierList).filterNot(knownSets::contains)
        return if (unknown.isEmpty()) {
            GMResult.Ok(Unit)
        } else {
            GMResult.Err(
                MermaidError.Parse(
                    line = line + lineOffset,
                    column = column,
                    message = "Parse error on line ${line + lineOffset}, column $column: " +
                        "unknown set identifier: ${unknown.joinToString(", ")}",
                ),
            )
        }
    }

    fun getSubsetData(): List<VennData> = subsets

    fun getTextData(): List<VennTextData> = textNodes

    fun getStyleData(): List<VennStyleData> = styleEntries

    fun setDiagramTitle(value: String) {
        diagramTitle = value
    }

    private fun normalizeIdentifierList(identifierList: List<String>): List<String> =
        identifierList.map(::normalizeText)

    private fun normalizeText(text: String): String {
        val trimmed = text.trim()
        return if (
            trimmed.length >= 2 &&
            trimmed.startsWith('"') &&
            trimmed.endsWith('"')
        ) {
            trimmed.substring(1, trimmed.lastIndex)
        } else {
            trimmed
        }
    }
}
