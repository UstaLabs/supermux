package dev.supermux.ui.chat

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

// A browser has one thread; this only yields to the event loop first.
internal actual val VideoOpenContext: CoroutineContext = Dispatchers.Default
