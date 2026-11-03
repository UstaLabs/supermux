package dev.supermux.ui.chat

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

// Off the AWT thread: the macOS backend's error path blocks on it (see [VideoOpenContext]).
internal actual val VideoOpenContext: CoroutineContext = Dispatchers.Default
