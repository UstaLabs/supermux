package com.swithun.cmpmermaid.compose

import android.content.Context
import android.util.AttributeSet
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidRenderErrorInfo

/**
 * Stable Android View entry point for hosts that do not compile Compose source.
 */
class CMPMermaidView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AbstractComposeView(context, attrs, defStyleAttr) {
    private var sourceState by mutableStateOf("")
    private var contentDescriptionState by mutableStateOf("Mermaid diagram")
    private var contentSizeListener: ((Float, Float) -> Unit)? = null
    private var errorListener: ((MermaidRenderErrorInfo) -> Unit)? = null
    private var lastContentSize: MermaidContentSize? = null

    fun setMermaidSource(source: String?) {
        val normalized = source.orEmpty()
        if (sourceState != normalized) {
            lastContentSize = null
            sourceState = normalized
        }
    }

    fun setMermaidContentDescription(contentDescription: String?) {
        contentDescriptionState = contentDescription.orEmpty()
    }

    fun setMermaidContentSizeListener(listener: ((Float, Float) -> Unit)?) {
        contentSizeListener = listener
        lastContentSize?.let { size -> listener?.invoke(size.width, size.height) }
    }

    fun setMermaidErrorListener(
        listener: ((MermaidRenderErrorInfo) -> Unit)?,
    ) {
        errorListener = listener
    }

    @Composable
    override fun Content() {
        MermaidDiagram(
            source = sourceState,
            modifier = Modifier.fillMaxSize(),
            contentDescription = contentDescriptionState,
            onRenderResult = { result ->
                if (result is GMResult.Ok) {
                    val size = result.value.contentSizeWithPadding()
                    if (lastContentSize != size) {
                        lastContentSize = size
                        contentSizeListener?.invoke(size.width, size.height)
                    }
                }
            },
            onError = { error -> errorListener?.invoke(error) },
        )
    }
}
