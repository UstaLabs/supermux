package dev.supermux.webview.layouttest

import java.util.Locale
import kotlin.math.roundToInt

/** Frame intervals in milliseconds, against the display's nominal interval. */
data class FrameSummary(
    val count: Int,
    val p50: Double,
    val p95: Double,
    val p99: Double,
    val max: Double,
    /** Intervals longer than 1.5 nominal intervals: frames the display showed twice. */
    val late: Int,
) {
    fun line(): String = if (count == 0) "frames=0" else
        "frames=$count p50=${p50.ms()} p95=${p95.ms()} p99=${p99.ms()} max=${max.ms()} late=$late"
}

private fun Double.ms(): String = String.format(Locale.ROOT, "%.1fms", this)

object FrameStats {
    /** Nearest-rank percentiles of [intervalsMillis]; [nominalMillis] is the display's frame interval. */
    fun summarize(intervalsMillis: List<Double>, nominalMillis: Double): FrameSummary {
        if (intervalsMillis.isEmpty()) return FrameSummary(0, 0.0, 0.0, 0.0, 0.0, 0)
        val sorted = intervalsMillis.sorted()
        fun at(q: Double): Double = sorted[((sorted.size - 1) * q).roundToInt()]
        return FrameSummary(
            count = sorted.size,
            p50 = at(0.50),
            p95 = at(0.95),
            p99 = at(0.99),
            max = sorted.last(),
            late = sorted.count { it > nominalMillis * 1.5 },
        )
    }

    /** The intervals between consecutive [timestampsNanos], in milliseconds. */
    fun intervals(timestampsNanos: List<Long>): List<Double> =
        timestampsNanos.zipWithNext { a, b -> (b - a) / 1_000_000.0 }
}
