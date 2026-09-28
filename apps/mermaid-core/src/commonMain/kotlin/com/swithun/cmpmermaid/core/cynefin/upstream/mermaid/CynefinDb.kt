package com.swithun.cmpmermaid.core.cynefin.upstream.mermaid

import com.swithun.cmpmermaid.core.MermaidCynefinOptions
import com.swithun.cmpmermaid.core.MermaidPreprocessor

/**
 * Kotlin translation of Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/cynefin/cynefinDb.ts.
 */
internal class CynefinDb(
    val config: MermaidCynefinOptions,
    diagramTitle: String?,
    val sourceSeedIdentity: String,
) {
    private val domains = linkedMapOf<CynefinDomainName, CynefinDomain>()
    private var transitions: List<CynefinTransition> = emptyList()

    var diagramTitle: String? = diagramTitle
        private set
    var accessibilityTitle: String? = null
        private set
    var accessibilityDescription: String? = null
        private set

    fun getDomains(): Map<CynefinDomainName, CynefinDomain> = domains

    fun getTransitions(): List<CynefinTransition> = transitions

    // Mermaid.js 12.0.0: cynefinDb.ts -> setDomains.
    fun setDomains(blocks: List<CynefinAstDomainBlock>) {
        blocks.forEach { block ->
            domains[block.domain] = CynefinDomain(
                name = block.domain,
                items = block.items.map { label ->
                    CynefinItem(label = decode(label))
                },
            )
        }
    }

    // Mermaid.js 12.0.0: cynefinDb.ts -> setTransitions.
    fun setTransitions(source: List<CynefinAstTransition>) {
        transitions = source
            .filter { transition -> transition.from != transition.to }
            .map { transition ->
                CynefinTransition(
                    from = transition.from,
                    to = transition.to,
                    label = transition.label
                        ?.takeIf(String::isNotEmpty)
                        ?.let(::decode),
                )
            }
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

    // Mermaid.js 12.0.0: cynefinDb.ts -> clear.
    fun clear() {
        domains.clear()
        transitions = emptyList()
        diagramTitle = null
        accessibilityTitle = null
        accessibilityDescription = null
    }

    private fun decode(value: String): String = MermaidPreprocessor.decodeEntities(value)
}
