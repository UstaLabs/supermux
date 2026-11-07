package dev.supermux.webview.wintest

import java.lang.management.ManagementFactory

/**
 * CPU time of this JVM, in total and for the threads a captured frame passes through, read before and after a run.
 * It shows what no timer around our own calls sees: skiko plays Compose's frame back on its render thread
 * (`skiko-dispatcher-to-block-on`), and that is where Skia uploads the frame's image to the GPU.
 */
class CpuSnapshot private constructor(private val processNs: Long, private val groupNs: Map<String, Long>) {
    /** CPU milliseconds per frame since [before], for the process and each thread group, over [frames] frames. */
    fun perFrameSince(before: CpuSnapshot, frames: Int): List<Pair<String, Any?>> {
        val n = frames.coerceAtLeast(1)
        val out = ArrayList<Pair<String, Any?>>()
        out += "process_ms_per_frame" to (processNs - before.processNs) / 1e6 / n
        for (group in GROUPS) {
            out += "${group}_ms_per_frame" to ((groupNs[group] ?: 0L) - (before.groupNs[group] ?: 0L)) / 1e6 / n
        }
        out += "frames" to frames
        return out
    }

    companion object {
        /** The UI thread (Compose records the frame), skiko's render thread, our native threads (the capture copies). */
        private val GROUPS = listOf("edt", "skiko_render", "wv2_native")

        fun take(): CpuSnapshot {
            val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
            val threads = ManagementFactory.getThreadMXBean()
            val groups = HashMap<String, Long>()
            for (info in threads.getThreadInfo(threads.allThreadIds)) {
                if (info == null) continue
                val group = when {
                    info.threadName.startsWith("AWT-EventQueue") -> "edt"
                    info.threadName.startsWith("skiko-dispatcher-to-block-on") -> "skiko_render"
                    info.threadName.startsWith("wv2-native") -> "wv2_native"
                    else -> continue
                }
                val cpu = threads.getThreadCpuTime(info.threadId)
                if (cpu > 0) groups.merge(group, cpu, Long::plus)
            }
            return CpuSnapshot(os?.processCpuTime ?: 0L, groups)
        }
    }
}
