package com.swithun.cmpmermaid.core.railroad.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidPreprocessor
import com.swithun.cmpmermaid.core.MermaidRailroadOptions
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidTextPort

/**
 * Kotlin translation of Mermaid.js 12.0.0
 * packages/mermaid/src/diagrams/railroad/railroadDb.ts.
 */
internal class RailroadDb(
    val config: MermaidRailroadOptions,
    diagramTitle: String?,
) {
    private val rules = mutableListOf<RailroadRule>()
    private val ruleMap = linkedMapOf<String, RailroadRule>()

    var diagramTitle: String? = diagramTitle
        private set
    var accessibilityTitle: String? = null
        private set
    var accessibilityDescription: String? = null
        private set

    fun setDiagramTitle(value: String): GMResult<Unit, MermaidError> =
        sanitize(value).map { sanitized ->
            diagramTitle = sanitized
        }

    fun setAccessibilityTitle(value: String): GMResult<Unit, MermaidError> =
        sanitize(value).map { sanitized ->
            accessibilityTitle = sanitized.trimStart()
        }

    fun setAccessibilityDescription(value: String): GMResult<Unit, MermaidError> =
        sanitize(value).map { sanitized ->
            accessibilityDescription = sanitized.replace(INDENTED_NEWLINE, "\n")
        }

    fun addRule(rule: RailroadRule): GMResult<Unit, MermaidError> {
        val name = when (val sanitized = sanitize(rule.name)) {
            is GMResult.Ok -> sanitized.value
            is GMResult.Err -> return sanitized
        }
        val definition = when (val sanitized = sanitize(rule.definition)) {
            is GMResult.Ok -> sanitized.value
            is GMResult.Err -> return sanitized
        }
        val comment = when (val source = rule.comment) {
            null -> null
            else -> when (val sanitized = sanitize(source)) {
                is GMResult.Ok -> sanitized.value
                is GMResult.Err -> return sanitized
            }
        }
        val stored = RailroadRule(
            name = name,
            definition = definition,
            comment = comment,
        )
        rules += stored
        ruleMap[name] = stored
        return GMResult.Ok(Unit)
    }

    fun getRules(): List<RailroadRule> = rules.toList()

    fun getRule(name: String): RailroadRule? = ruleMap[name]

    private fun sanitize(
        node: RailroadAstNode,
    ): GMResult<RailroadAstNode, MermaidError> {
        return when (node) {
            is RailroadAstNode.Terminal ->
                sanitize(node.value).map(RailroadAstNode::Terminal)
            is RailroadAstNode.NonTerminal ->
                sanitize(node.name).map(RailroadAstNode::NonTerminal)
            is RailroadAstNode.Special ->
                sanitize(node.text).map(RailroadAstNode::Special)
            is RailroadAstNode.Sequence -> sanitizeNodes(node.elements).map(
                RailroadAstNode::Sequence,
            )
            is RailroadAstNode.Choice -> sanitizeNodes(node.alternatives).map(
                RailroadAstNode::Choice,
            )
            is RailroadAstNode.Optional -> sanitize(node.element).map(
                RailroadAstNode::Optional,
            )
            is RailroadAstNode.Repetition -> {
                val element = when (val sanitized = sanitize(node.element)) {
                    is GMResult.Ok -> sanitized.value
                    is GMResult.Err -> return sanitized
                }
                val separator = when (val source = node.separator) {
                    null -> null
                    else -> when (val sanitized = sanitize(source)) {
                        is GMResult.Ok -> sanitized.value
                        is GMResult.Err -> return sanitized
                    }
                }
                GMResult.Ok(node.copy(element = element, separator = separator))
            }
        }
    }

    private fun sanitizeNodes(
        nodes: List<RailroadAstNode>,
    ): GMResult<List<RailroadAstNode>, MermaidError> {
        val sanitized = mutableListOf<RailroadAstNode>()
        nodes.forEach { node ->
            when (val result = sanitize(node)) {
                is GMResult.Ok -> sanitized += result.value
                is GMResult.Err -> return result
            }
        }
        return GMResult.Ok(sanitized)
    }

    private fun sanitize(value: String): GMResult<String, MermaidError> =
        MermaidTextPort.sanitizeText(value).map { sanitized ->
            MermaidPreprocessor.decodeEntities(sanitized)
        }

    private inline fun <T, R> GMResult<T, MermaidError>.map(
        transform: (T) -> R,
    ): GMResult<R, MermaidError> = when (this) {
        is GMResult.Ok -> GMResult.Ok(transform(value))
        is GMResult.Err -> this
    }

    private companion object {
        val INDENTED_NEWLINE = Regex("""\n\s+""")
    }
}
