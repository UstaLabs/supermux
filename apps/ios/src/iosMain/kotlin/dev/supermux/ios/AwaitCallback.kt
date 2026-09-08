package dev.supermux.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSThread
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
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
    // `withContext(Main)` is not a nicety: [start] ends in Swift presenting a view controller, and
    // UIKit presentation from a background thread is undefined behaviour — it corrupts the view
    // hierarchy or simply does nothing. These seams are called from shared screens that are free to
    // run them on an IO dispatcher (an attachment save does its own I/O first), so the hop belongs
    // HERE, once, rather than as a `DispatchQueue.main` scattered through every Swift member where
    // one could be forgotten. The completion may still arrive on any thread; resuming a
    // continuation is thread-safe.
    withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            start { value ->
                if (continuation.isActive) continuation.resume(value)
            }
        }
    }

/**
 * Run [block] on the main thread, now if we are already there.
 *
 * The counterpart of [awaitCallback] for the seams that return nothing and cannot suspend —
 * `openUrl`, `copyToClipboard`, haptics. They look harmless, but each ends in a UIKit call
 * (`UIApplication`, `UIPasteboard`, `UIFeedbackGenerator`) that is main-thread-only, and a shared
 * screen may fire any of them from a coroutine on another dispatcher.
 *
 * The already-on-main fast path matters for haptics specifically: dispatching a tap that is meant
 * to coincide with a touch would delay it to the next runloop turn, which is exactly the kind of
 * lag that makes a haptic feel detached from the gesture that caused it.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun onMainThread(block: () -> Unit) {
    if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue()) { block() }
}
