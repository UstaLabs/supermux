package dev.supermux.webview.sample

import androidx.compose.ui.graphics.ImageBitmap
import dev.supermux.webview.WebContent
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import kotlin.math.ceil
import kotlin.math.max
import kotlin.time.TimeSource

/**
 * The engines [delegate] makes, counted for the perf lab: how many were created and destroyed (the churn), how many
 * exist now, and how many pages and snapshots they made. It wraps each engine to see its calls, so it counts any
 * engine, the fake or a real one. Main thread only, like the pool that calls it.
 */
class EngineCounter(private val delegate: WebEngineFactory) : WebEngineFactory {
    /**
     * Hears what each engine is asked to do, with how long the call took (the calling thread's time, the main thread's for
     * all but the suspending calls): the chat's probe (`-chat-probe`) lines its slow frames up against it. Null: nothing
     * is timed.
     */
    var trace: ((String) -> Unit)? = null

    /** [block], timed for [trace] as [what], if anyone listens. */
    private inline fun <T> timed(what: String, block: () -> T): T {
        val listener = trace ?: return block()
        val start = TimeSource.Monotonic.markNow()
        val result = block()
        val micros = start.elapsedNow().inWholeMicroseconds
        listener("$what ${micros / 1000}.${(micros % 1000) / 100} ms")
        return result
    }

    /** Engines created. */
    var created: Int = 0
        private set

    /** Engines released: destroyed for good. */
    var destroyed: Int = 0
        private set

    /** Creations that gave no engine. */
    var unavailable: Int = 0
        private set

    /** Pages started: loads, and restores that took. */
    var loads: Int = 0
        private set

    /** Snapshots taken: each a copy of a page's pixels. */
    var snapshots: Int = 0
        private set

    /** Engines that exist now: created, and not yet released. */
    val existing: Int get() = created - destroyed

    /** The most engines that existed at once since [resetPeak]. Exact: it moves at every creation. */
    var peakEngines: Int = 0
        private set

    /** Starts [peakEngines] again from the engines that exist now. */
    fun resetPeak() {
        peakEngines = existing
    }

    override suspend fun create(config: EngineConfig): WebEngineResult = when (val result = timed("engine create") { delegate.create(config) }) {
        is WebEngineResult.Created -> {
            created++
            peakEngines = max(peakEngines, existing)
            WebEngineResult.Created(Counted(result.engine, created))
        }
        is WebEngineResult.Unavailable -> {
            unavailable++
            result
        }
    }

    /** [engine] as the pool sees it: every call goes through to it, and the ones that count are counted. */
    private inner class Counted(private val engine: WebEngine, private val number: Int) : WebEngine by engine {
        private var released = false

        override fun load(content: WebContent) {
            loads++
            // platform-ok: url (a content key's last segment)
            timed("engine $number load ${content.key.substringAfterLast('/')}") { engine.load(content) }
        }

        override fun restoreState(state: ByteArray, content: WebContent): Boolean =
            // platform-ok: url (a content key's last segment)
            timed("engine $number restore ${content.key.substringAfterLast('/')}") { engine.restoreState(state, content) }.also { if (it) loads++ }

        override suspend fun snapshot(): ImageBitmap? = timed("engine $number snapshot") { engine.snapshot() }?.also { snapshots++ }

        override suspend fun saveState(): ByteArray? = timed("engine $number save") { engine.saveState() }

        override suspend fun reset(): Boolean = timed("engine $number reset") { engine.reset() }

        override fun replayState() = timed("engine $number replay") { engine.replayState() }

        override fun setVisible(visible: Boolean) = timed("engine $number visible $visible") { engine.setVisible(visible) }

        override fun configure(setup: dev.supermux.webview.engine.PageSetup) = timed("engine $number configure") { engine.configure(setup) }

        override fun release() {
            if (!released) {
                released = true
                destroyed++
            }
            timed("engine $number release") { engine.release() }
        }
    }
}

/** Frame intervals, or frame costs, of one run, in nanoseconds. Plain fields: measuring recomposes nothing. */
class FrameStats {
    private var values = LongArray(1_024)

    /** How many were added. */
    var count: Int = 0
        private set

    fun add(nanos: Long) {
        if (count == values.size) values = values.copyOf(count * 2)
        values[count++] = nanos
    }

    fun clear() {
        count = 0
    }

    /** Percentiles by nearest rank, and the frames over 1.5 times the median: dropped, at any refresh rate. */
    fun summary(): FrameSummary? {
        if (count == 0) return null
        val sorted = values.copyOf(count).apply { sort() }
        fun rank(q: Double): Long = sorted[(ceil(q * count).toInt() - 1).coerceIn(0, count - 1)]
        val median = rank(0.5)
        return FrameSummary(count, median, rank(0.95), rank(0.99), sorted[count - 1], sorted.count { it * 2 > median * 3 })
    }
}

/** A run's frames: how many, the 50th, 95th and 99th percentile and the longest, in nanoseconds, and the dropped. */
data class FrameSummary(val frames: Int, val p50: Long, val p95: Long, val p99: Long, val max: Long, val dropped: Int) {
    /** "p50 16.7 · p95 17.1 · p99 18.0 · max 33.4 ms" */
    fun percentiles(): String = "p50 ${millis(p50)} · p95 ${millis(p95)} · p99 ${millis(p99)} · max ${millis(max)} ms"
}

/** The perf lab's numbers at one instant: the pool, the engines, and the memory the runtime holds. */
data class LabStats(
    /** Engines leased to views now. */
    val live: Int,
    /** Live pages whose views left (a list item that scrolled away), parked for the next view of them. */
    val parked: Int,
    /** The pool's budget now: [maxLive], halved during memory pressure. */
    val budget: Int,
    val maxLive: Int,
    /** Engines kept blank for reuse. */
    val spares: Int,
    /** Engines that exist: live, parked, spares, and those being created, reset or evicted. */
    val engines: Int,
    val created: Int,
    val destroyed: Int,
    /** Pages started, and snapshots taken, since the lab began. */
    val loads: Int,
    val snapshots: Int,
    /** The snapshot cache: what it stores, encoded, how many, and its bound. */
    val cacheBytes: Long,
    val cacheCount: Int,
    val cacheMaxBytes: Long,
    /** The snapshots it keeps decoded, ready to draw: their pixels, and how many. */
    val decodedBytes: Long,
    val decodedCount: Int,
    /** Snapshot pixels held by the views on screen as their covers; mostly the cache's own decoded images. */
    val coverBytes: Long,
    val coverCount: Int,
    /** Page memory: the pages it remembers, and what they cost. */
    val pages: Int,
    val pageBytes: Long,
) {
    fun enginesLine(): String = buildString {
        append("engines  live $live of $budget")
        if (budget < maxLive) append(" (memory pressure: $maxLive normally)")
        append(" · parked $parked · spares $spares · in existence $engines · created $created · destroyed $destroyed")
        append(" · pages started $loads · snapshots $snapshots")
    }

    fun memoryLine(): String =
        "memory   snapshot cache ${size(cacheBytes)} of ${size(cacheMaxBytes)}, $cacheCount kept, " +
            "${size(decodedBytes)} decoded ($decodedCount) · covers on screen ${size(coverBytes)} ($coverCount views) · " +
            "page memory $pages pages, ${size(pageBytes)}"
}

/** What one scripted run measured. Counts are the run's own; peaks were sampled every frame. */
data class RunReport(
    val name: String,
    /** "LazyColumn · 200 web views" */
    val setup: String,
    /** Frame intervals while the list moved. */
    val frames: FrameSummary?,
    val created: Int,
    val destroyed: Int,
    val loads: Int,
    val snapshots: Int,
    val peakLive: Int,
    val maxLive: Int,
    /** The most pages parked at once, live for the next view of them. */
    val peakParked: Int,
    /** The most engines in existence at once, exact, and the most the pool may keep: maxLive plus maxIdle. */
    val peakEngines: Int,
    val maxEngines: Int,
    val peakCoverBytes: Long,
    val peakCoverCount: Int,
    /**
     * The snapshot cache and page memory at the end: what the cache stores, its bound, the pictures it keeps, and what
     * it keeps decoded.
     */
    val cacheBytes: Long,
    val cacheMaxBytes: Long,
    val cacheCount: Int,
    val decodedBytes: Long,
    val decodedCount: Int,
    val pages: Int,
    val pageBytes: Long,
) {
    fun summary(): String = buildString {
        append("$name, $setup: ")
        val f = frames
        append(if (f == null) "no frames" else "${f.frames} frames, ${f.percentiles()}, ${f.dropped} dropped")
        append("; engines created $created, destroyed $destroyed, peak live $peakLive/$maxLive, peak parked $peakParked, ")
        append("peak in existence $peakEngines/$maxEngines; pages started $loads, snapshots $snapshots; ")
        append("covers up to ${size(peakCoverBytes)} ($peakCoverCount views); ")
        append("cache ${size(cacheBytes)} of ${size(cacheMaxBytes)}, $cacheCount snapshots kept, ")
        append("${size(decodedBytes)} decoded ($decodedCount); ")
        append("page memory $pages pages, ${size(pageBytes)}")
    }

    /** The line the lab prints for each run. */
    fun line(): String = "perf-lab: ${summary()}"
}

/** Peaks and frames of one run, sampled as it goes. */
internal class RunRecorder(private val start: LabStats) {
    val frames = FrameStats()
    private var peakLive = start.live
    private var peakParked = start.parked
    private var peakCoverBytes = start.coverBytes
    private var peakCoverCount = start.coverCount

    fun sample(now: LabStats) {
        peakLive = max(peakLive, now.live)
        peakParked = max(peakParked, now.parked)
        peakCoverBytes = max(peakCoverBytes, now.coverBytes)
        peakCoverCount = max(peakCoverCount, now.coverCount)
    }

    fun report(name: String, setup: String, end: LabStats, peakEngines: Int, maxEngines: Int): RunReport {
        sample(end)
        return RunReport(
            name = name,
            setup = setup,
            frames = frames.summary(),
            created = end.created - start.created,
            destroyed = end.destroyed - start.destroyed,
            loads = end.loads - start.loads,
            snapshots = end.snapshots - start.snapshots,
            peakLive = peakLive,
            maxLive = end.maxLive,
            peakParked = peakParked,
            peakEngines = peakEngines,
            maxEngines = maxEngines,
            peakCoverBytes = peakCoverBytes,
            peakCoverCount = peakCoverCount,
            cacheBytes = end.cacheBytes,
            cacheMaxBytes = end.cacheMaxBytes,
            cacheCount = end.cacheCount,
            decodedBytes = end.decodedBytes,
            decodedCount = end.decodedCount,
            pages = end.pages,
            pageBytes = end.pageBytes,
        )
    }
}

/** [nanos] in milliseconds, with one decimal: "16.7". */
internal fun millis(nanos: Long): String {
    val tenths = (nanos + 50_000) / 100_000
    return "${tenths / 10}.${tenths % 10}"
}

/** [bytes] for people: "512 B", "812 KB", "12.4 MB". */
internal fun size(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024 * 1_024 -> "${(bytes + 512) / 1_024} KB"
    else -> {
        val tenths = (bytes * 10 + (1L shl 19)) / (1L shl 20)
        "${tenths / 10}.${tenths % 10} MB"
    }
}
