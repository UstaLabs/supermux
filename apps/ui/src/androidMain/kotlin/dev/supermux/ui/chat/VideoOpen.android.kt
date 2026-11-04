package dev.supermux.ui.chat

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

// ExoPlayer is bound to the main looper it was created on.
internal actual val VideoOpenContext: CoroutineContext = Dispatchers.Main.immediate
