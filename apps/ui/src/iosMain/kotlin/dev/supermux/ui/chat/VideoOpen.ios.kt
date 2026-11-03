package dev.supermux.ui.chat

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

// Unchanged from before: not yet checked against the iOS backend.
internal actual val VideoOpenContext: CoroutineContext = Dispatchers.Default
