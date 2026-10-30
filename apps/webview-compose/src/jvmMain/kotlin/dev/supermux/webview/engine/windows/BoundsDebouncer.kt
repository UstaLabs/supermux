package dev.supermux.webview.engine.windows

/**
 * When the engine follows the surface's bounds (spec §7): moves apply at once; a size change waits until the size has
 * not changed for [settleNanos], so a live resize draws the last frame scaled and resizes the engine once at the end.
 * Not thread-safe: the surface calls it on the UI thread.
 */
internal class BoundsDebouncer(private val settleNanos: Long) {
    /** Pixels in the window's (the Compose root's) coordinates. */
    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
        fun sameSize(other: Rect): Boolean = width == other.width && height == other.height
    }

    var applied: Rect? = null
        private set
    private var pending: Rect? = null
    private var pendingSince = 0L

    val hasPending: Boolean get() = pending != null

    /** Feeds the latest bounds; returns the bounds to apply now, or null. */
    fun update(bounds: Rect, now: Long): Rect? {
        val current = applied
        if (current == null) {
            applied = bounds
            return bounds
        }
        if (bounds.sameSize(current)) {
            pending = null
            if (bounds == current) return null
            applied = bounds
            return bounds
        }
        val waiting = pending
        if (waiting == null || !waiting.sameSize(bounds)) pendingSince = now
        pending = bounds
        return null
    }

    /** Call regularly; returns the waiting bounds once their size has settled, or null. */
    fun poll(now: Long): Rect? {
        val waiting = pending ?: return null
        if (now - pendingSince < settleNanos) return null
        pending = null
        applied = waiting
        return waiting
    }
}
