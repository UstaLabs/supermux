package dev.supermux.ios

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Turn one of [IosBridge]'s callback-style members into a `suspend` call.
 *
 * This is the adapter the whole bridge design rests on. Swift cannot IMPLEMENT a Kotlin `suspend`
 * function — an `@objc` protocol method has no continuation to resume — so every asynchronous seam
 * crosses the boundary as "call me back with the answer", and the shared `Platform` API, which is
 * suspending, is reconstructed on this side.
 *
 * Two properties make it safe against a Swift side that is not perfectly behaved:
 *
 *  - **`isActive` before resuming.** The awaiting screen can leave (the user navigated away with a
 *    picker still up). Resuming a cancelled continuation would otherwise be an
 *    `IllegalStateException` thrown on whatever UIKit thread fired the callback — a crash, not a
 *    dropped result.
 *  - **Cancellation does not cancel the sheet.** There is no `invokeOnCancellation` dismissing
 *    anything, deliberately: a `UIDocumentPicker` the user is looking at must not vanish because
 *    the composable behind it left, and iOS never re-creates the view controller under a presented
 *    sheet (which is why `pendingPicks` is empty on this platform — see `IosPlatform`).
 *
 * A bridge implementation that never calls back leaves the caller suspended forever, which is why
 * `NoopIosBridge` answers immediately with the "unavailable" value instead of doing nothing.
 */
internal suspend fun <T> awaitCallback(start: (done: (T) -> Unit) -> Unit): T =
    suspendCancellableCoroutine { continuation ->
        start { value ->
            if (continuation.isActive) continuation.resume(value)
        }
    }
