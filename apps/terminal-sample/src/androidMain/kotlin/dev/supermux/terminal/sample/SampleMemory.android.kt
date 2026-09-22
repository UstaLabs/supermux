package dev.supermux.terminal.sample

import java.io.File

/**
 * Android memory. The same two numbers as the desktop actual and for the same reason: Android's
 * `/proc/self/status` is readable by the app's own process, and the engine's scrollback is native
 * memory that `Runtime` cannot see.
 *
 * `Debug.getNativeHeapAllocatedSize()` is deliberately not used: it counts the malloc heap only,
 * and part of what a terminal holds is mapped rather than malloc'd, so RSS is both simpler and
 * more complete.
 */
actual fun readSampleMemory(): SampleMemory {
    val runtime = Runtime.getRuntime()
    val total = runtime.totalMemory()
    val used = total - runtime.freeMemory()
    val rss = procRssBytes()
    return SampleMemory(
        heapUsedBytes = used,
        heapTotalBytes = total,
        rssBytes = rss,
        source = if (rss > 0) "Runtime + /proc/self/status" else "Runtime (proc unreadable)",
    )
}

private fun procRssBytes(): Long = try {
    File("/proc/self/status").useLines { lines ->
        lines.firstOrNull { it.startsWith("VmRSS:") }
            ?.substringAfter(':')
            ?.trim()
            ?.removeSuffix(" kB")
            ?.trim()
            ?.toLongOrNull()
            ?.times(1024)
            ?: 0L
    }
} catch (_: Throwable) {
    0L
}
