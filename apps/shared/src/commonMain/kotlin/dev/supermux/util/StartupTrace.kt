package dev.supermux.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlin.time.TimeSource

/**
 * Timestamped startup / sync milestones, printed as `[startup] +<ms> <name> <detail>` so a cold
 * start can be read back from logcat (Android's System.out), the browser console or stdout.
 * Times are relative to the first touch of this object — `MainActivity.onCreate` on Android,
 * which also logs its own offset from process start.
 */
object StartupTrace {
    private val origin = TimeSource.Monotonic.markNow()
    private val seen = MutableStateFlow<Set<String>>(emptySet())

    fun elapsedMs(): Long = origin.elapsedNow().inWholeMilliseconds

    fun mark(name: String, detail: String = "") {
        println("[startup] +${elapsedMs()}ms $name${if (detail.isEmpty()) "" else " $detail"}")
    }

    /** [mark], but only the first time [name] is seen in this process. */
    fun first(name: String, detail: String = "") {
        if (name !in seen.getAndUpdate { it + name }) mark(name, detail)
    }
}
