package com.swithun.cmpmermaid.core.agentflow.upstream.mermaid

/**
 * Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/agentflow/shapes.ts
 * packages/mermaid/src/diagrams/agentflow/colorSlots.ts
 */
internal object AgentflowShapes {
    const val DEFAULT_SHAPE = "roundedRect"

    val aliases = mapOf(
        "task" to "roundedRect",
        "tool" to "subroutine",
        "input" to "lean-right",
        "decision" to "diamond",
        "refdoc" to "lin-doc",
        "action" to "hexagon",
        "round" to "rect",
    )

    val removed = setOf(
        "doc",
        "stadium",
        "terminal",
        "circle",
        "trapezoid",
        "inv_trapezoid",
        "inv-trapezoid",
        "doublecircle",
        "double-circle",
        "typeDeclaration",
        "procs",
        "lean_left",
        "lean-left",
        "in-out",
        "cylinder",
        "ellipse",
        "odd",
        "tag-rect",
        "tagged-rectangle",
        "delay",
        "half-rounded-rectangle",
        "lin-rect",
        "lined-rectangle",
        "win-pane",
        "window-pane",
        "curv-trap",
        "curved-trapezoid",
    )

    val allowed = setOf(
        "roundedRect",
        "subroutine",
        "subprocess",
        "subproc",
        "framed-rectangle",
        "lean-right",
        "diamond",
        "lin-doc",
        "lined-document",
        "hexagon",
        "hex",
        "connector",
        "collapsedGroup",
    )

    val kindSlots = mapOf(
        AgentflowVertexKind.Tool to 0,
        AgentflowVertexKind.Task to 1,
        AgentflowVertexKind.Decision to 2,
        AgentflowVertexKind.Input to 3,
        AgentflowVertexKind.ReferenceDocument to 4,
        AgentflowVertexKind.Connector to 5,
        AgentflowVertexKind.Action to 6,
    )

    fun resolveAlias(shape: String?): String? =
        shape?.let { aliases[it] ?: it }

    fun containerSlot(
        ordinal: Int,
        paletteLength: Int,
    ): Int {
        val kindCount = kindSlots.size
        val slotCount = (paletteLength - kindCount).coerceAtLeast(1)
        val slot = kindCount + ordinal % slotCount
        return if (paletteLength > 0) slot % paletteLength else slot
    }
}
