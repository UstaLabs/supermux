package dev.supermux.state

import dev.supermux.proto.ServerFrame

/** Host-side holder for per-session walkthrough/review state (desktop: a Compose `WalkthroughState`).
 *  The store creates one per session on first use and applies every walkthrough frame to it exactly once,
 *  in arrival order, whether or not any UI is showing it. */
interface WalkthroughSeam<T : Any> {
    fun create(sessionId: String): T
    fun apply(state: T, frame: ServerFrame)
}
