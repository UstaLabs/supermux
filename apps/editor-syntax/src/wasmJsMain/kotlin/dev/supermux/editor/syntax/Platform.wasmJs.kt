package dev.supermux.editor.syntax

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.Promise

// The worker shares the UI thread here: slices of 8 ms leave half of a 60 Hz frame to everything else.
internal actual val PLATFORM_PARSE_SLICE_MICROS: Long = 8_000

internal actual val platformSliceYield: (suspend () -> Unit)? = { loaderNextTask().await() }

private fun jsMessage(e: JsAny): String = js("String((e && e.message) || e)")

/** Await a loader promise; a rejection becomes a [SyntaxException] with the loader's message. */
internal suspend fun <T : JsAny?> Promise<T>.await(): T = suspendCancellableCoroutine { cont ->
    then(
        { value -> if (cont.isActive) cont.resume(value); null },
        { error -> if (cont.isActive) cont.resumeWithException(SyntaxException(jsMessage(error), -2)); null },
    )
}
