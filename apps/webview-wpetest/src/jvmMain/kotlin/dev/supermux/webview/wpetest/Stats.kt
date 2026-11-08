package dev.supermux.webview.wpetest

import java.util.Locale
import kotlin.math.ceil

/** Measurements in one unit, with nearest-rank percentiles. Fill it from one thread. */
class Samples(val name: String) {
    private val values = ArrayList<Double>()

    val count: Int get() = values.size

    fun add(value: Double) {
        values += value
    }

    fun percentile(p: Double): Double {
        require(p in 0.0..100.0) { "percentile out of range: $p" }
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        val rank = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    val p50: Double get() = percentile(50.0)
    val p95: Double get() = percentile(95.0)
    val max: Double get() = values.maxOrNull() ?: Double.NaN

    /** `name_n`, `name_p50`, `name_p95` and `name_max`, ready for [resultLine]; only `name_n=0` when empty. */
    fun pairs(): List<Pair<String, Any?>> =
        if (values.isEmpty()) listOf("${name}_n" to 0)
        else listOf("${name}_n" to count, "${name}_p50" to p50, "${name}_p95" to p95, "${name}_max" to max)
}

/** Two decimals with a dot, whatever the container's locale. */
fun fmt(value: Double): String = if (value.isNaN()) "NaN" else String.format(Locale.ROOT, "%.2f", value)

enum class Verdict { PASS, FAIL, INFO, INCONCLUSIVE, DIFF }

/**
 * One result line, the format every scenario prints and the Results section is filled from:
 * `WPE <scenario> <check> <VERDICT> host=<host> key=value …`. Whitespace inside a value becomes `_`, so a line splits
 * on spaces. DIFF marks a WebKit rule that holds on WPE only with a change.
 */
fun resultLine(scenario: String, check: String, verdict: Verdict, host: String, details: List<Pair<String, Any?>>): String =
    buildString {
        append("WPE ").append(scenario).append(' ').append(check).append(' ').append(verdict.name)
        append(" host=").append(host)
        for ((key, value) in details) {
            val text = when (value) {
                is Double -> fmt(value)
                null -> "null"
                else -> value.toString()
            }
            append(' ').append(key).append('=').append(text.replace(Regex("\\s+"), "_"))
        }
    }
