package com.swithun.cmpmermaid.compose

import com.swithun.cmpmermaid.core.MermaidScene

internal data class MermaidContentSize(
    val width: Float,
    val height: Float,
)

internal fun MermaidScene.contentSizeWithPadding(): MermaidContentSize {
    val padding = viewportPadding.coerceAtLeast(0f)
    return MermaidContentSize(
        width = (width + padding * 2f).coerceAtLeast(1f),
        height = (height + padding * 2f).coerceAtLeast(1f),
    )
}
