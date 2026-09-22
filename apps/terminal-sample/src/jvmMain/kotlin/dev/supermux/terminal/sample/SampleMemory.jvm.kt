package dev.supermux.terminal.sample

import java.io.File

/**
 * Desktop JVM memory.
 *
 * Both numbers, and in this order of importance:
 * - **RSS** from `/proc/self/status` (Linux only). It is the one that can see the ENGINE: the
 *   scrollback, the parser state and the codec buffers are native memory, invisible to any heap
 *   graph. A terminal that leaks leaks here.
 * - **Heap** from `Runtime`, which is what the Kotlin side costs.
 *
 * On a platform without `/proc` the RSS is reported as 0 and [SampleMemory.source] says so — never
 * a guess, and never the heap dressed up as RSS.
 */
actual fun readSampleMemory(): SampleMemory {
    val runtime = Runtime.getRuntime()
    val total = runtime.totalMemory()
    val used = total - runtime.freeMemory()
    val rss = linuxRssBytes()
    return SampleMemory(
        heapUsedBytes = used,
        heapTotalBytes = total,
        rssBytes = rss,
        source = if (rss > 0) "Runtime + /proc/self/status" else "Runtime (no /proc on this host)",
    )
}

private val statusFile = File("/proc/self/status")

private fun linuxRssBytes(): Long {
    if (!statusFile.canRead()) return 0L
    return try {
        statusFile.useLines { lines ->
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
}
