package com.swithun.cmpmermaid.compose

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual fun mermaidRenderDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate
