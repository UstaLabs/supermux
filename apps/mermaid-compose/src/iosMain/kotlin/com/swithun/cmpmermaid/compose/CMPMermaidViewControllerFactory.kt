package com.swithun.cmpmermaid.compose

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidRenderErrorInfo
import platform.UIKit.UIViewController

/**
 * Stable Swift/Objective-C entry point for the precompiled CMPMermaid framework.
 */
class CMPMermaidViewControllerFactory {
    fun makeViewController(
        source: String,
        contentDescription: String = "Mermaid diagram",
    ): UIViewController = makeViewController(
        source = source,
        contentDescription = contentDescription,
        onContentSizeChanged = null,
        onError = null,
    )

    fun makeViewController(
        source: String,
        contentDescription: String,
        onContentSizeChanged: ((Double, Double) -> Unit)?,
    ): UIViewController = makeViewController(
        source = source,
        contentDescription = contentDescription,
        onContentSizeChanged = onContentSizeChanged,
        onError = null,
    )

    fun makeViewController(
        source: String,
        contentDescription: String,
        onContentSizeChanged: ((Double, Double) -> Unit)?,
        onError: ((MermaidRenderErrorInfo) -> Unit)?,
    ): UIViewController = ComposeUIViewController {
        MermaidDiagram(
            source = source,
            modifier = Modifier.fillMaxSize(),
            contentDescription = contentDescription,
            onRenderResult = { result ->
                if (result is GMResult.Ok) {
                    val size = result.value.contentSizeWithPadding()
                    onContentSizeChanged?.invoke(
                        size.width.toDouble(),
                        size.height.toDouble(),
                    )
                }
            },
            onError = onError,
        )
    }
}
