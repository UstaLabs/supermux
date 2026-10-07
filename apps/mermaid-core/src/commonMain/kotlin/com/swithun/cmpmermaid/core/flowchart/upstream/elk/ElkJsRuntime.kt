package com.swithun.cmpmermaid.core.flowchart.upstream.elk

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError

/**
 * Preserves Mermaid's ELK adapter boundary until elkjs has a pure Kotlin translation.
 */
internal object ElkJsRuntime {
    fun layout(@Suppress("UNUSED_PARAMETER") graphJson: String): GMResult<String, MermaidError> {
        return GMResult.Err(
            MermaidError.UnsupportedFeature(
                feature = "ELK layout",
                message = "Native Mermaid does not execute JavaScript; " +
                    "ELK layouts require a pure Kotlin translation of elkjs",
            ),
        )
    }
}
