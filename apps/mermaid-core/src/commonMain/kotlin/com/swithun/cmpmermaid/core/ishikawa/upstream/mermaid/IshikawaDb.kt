package com.swithun.cmpmermaid.core.ishikawa.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidIshikawaOptions
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidTextPort

/**
 * Kotlin translation of Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/ishikawa/ishikawaDb.ts -> IshikawaDB.
 */
internal class IshikawaDb(
    val config: MermaidIshikawaOptions,
    diagramTitle: String?,
) {
    private var root: IshikawaNode? = null
    private val stack = mutableListOf<StackEntry>()
    private var baseLevel: Int? = null

    var diagramTitle: String? = diagramTitle
        private set

    fun getRoot(): IshikawaNode? = root

    fun addNode(
        rawLevel: Int,
        text: String,
    ): GMResult<Unit, MermaidError> {
        val label = when (val sanitized = MermaidTextPort.sanitizeText(text)) {
            is GMResult.Ok -> sanitized.value
            is GMResult.Err -> return sanitized
        }
        val currentRoot = root
        if (currentRoot == null) {
            val node = IshikawaNode(text = label)
            root = node
            stack.clear()
            stack += StackEntry(level = 0, node = node)
            diagramTitle = label
            return GMResult.Ok(Unit)
        }

        val effectiveBaseLevel = baseLevel ?: rawLevel.also { baseLevel = it }
        val level = (rawLevel - effectiveBaseLevel + 1).coerceAtLeast(1)
        while (stack.size > 1 && stack.last().level >= level) {
            stack.removeAt(stack.lastIndex)
        }
        val parent = stack.lastOrNull()?.node
            ?: return GMResult.Err(
                MermaidError.Layout("Ishikawa hierarchy lost its root node"),
            )
        val node = IshikawaNode(text = label)
        parent.children += node
        stack += StackEntry(level = level, node = node)
        return GMResult.Ok(Unit)
    }

    private data class StackEntry(
        val level: Int,
        val node: IshikawaNode,
    )
}
