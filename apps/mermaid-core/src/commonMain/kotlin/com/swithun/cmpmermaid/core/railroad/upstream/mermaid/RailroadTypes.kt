package com.swithun.cmpmermaid.core.railroad.upstream.mermaid

/**
 * Kotlin translation of Mermaid.js 12.0.0
 * packages/mermaid/src/diagrams/railroad/railroadTypes.ts.
 */
internal enum class RailroadNotation(
    val header: String,
) {
    Ir("railroad-beta"),
    Ebnf("railroad-ebnf-beta"),
    Abnf("railroad-abnf-beta"),
    Peg("railroad-peg-beta"),
    ;

    companion object {
        fun fromHeader(value: String): RailroadNotation? =
            values().firstOrNull { notation -> notation.header.equals(value, ignoreCase = true) }
    }
}

internal sealed interface RailroadAstNode {
    data class Terminal(
        val value: String,
    ) : RailroadAstNode

    data class NonTerminal(
        val name: String,
    ) : RailroadAstNode

    data class Sequence(
        val elements: List<RailroadAstNode>,
    ) : RailroadAstNode

    data class Choice(
        val alternatives: List<RailroadAstNode>,
    ) : RailroadAstNode

    data class Optional(
        val element: RailroadAstNode,
    ) : RailroadAstNode

    data class Repetition(
        val element: RailroadAstNode,
        val min: Int,
        val max: Int?,
        val separator: RailroadAstNode? = null,
    ) : RailroadAstNode

    data class Special(
        val text: String,
    ) : RailroadAstNode
}

internal data class RailroadRule(
    val name: String,
    val definition: RailroadAstNode,
    val comment: String? = null,
)
