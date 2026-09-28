package com.swithun.cmpmermaid.core.xychart.upstream.d3

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Kotlin port of the d3-scale linear/band behavior and d3-array ticks used by
 * Mermaid 12's XY Chart builder.
 */
internal object XyScalePort {
    fun linear(
        value: Double,
        domainStart: Double,
        domainEnd: Double,
        rangeStart: Float,
        rangeEnd: Float,
    ): Float {
        if (!value.isFinite()) return Float.NaN
        val span = domainEnd - domainStart
        val parameter = when {
            span.isNaN() -> Double.NaN
            span == 0.0 -> 0.5
            else -> (value - domainStart) / span
        }
        return (rangeStart + parameter * (rangeEnd - rangeStart)).toFloat()
    }

    fun band(
        value: String,
        categories: List<String>,
        rangeStart: Float,
        rangeEnd: Float,
    ): Float {
        val domain = categories.distinct()
        val index = domain.indexOf(value)
        if (index < 0) return rangeStart
        val count = domain.size
        val low = minOf(rangeStart, rangeEnd)
        val high = maxOf(rangeStart, rangeEnd)
        val step = (high - low) / maxOf(1, count - 1)
        val alignedStart = low + (high - low - step * (count - 1)) * 0.5f
        val position = alignedStart + step * index
        return if (rangeEnd < rangeStart) {
            high - (position - low)
        } else {
            position
        }
    }

    fun ticks(
        start: Double,
        stop: Double,
        count: Int = 10,
    ): List<Double> {
        if (count <= 0 || !start.isFinite() || !stop.isFinite()) return emptyList()
        if (start == stop) return listOf(start)
        val reverse = stop < start
        val spec = if (reverse) {
            tickSpec(stop, start, count.toDouble())
        } else {
            tickSpec(start, stop, count.toDouble())
        } ?: return emptyList()
        if (spec.end < spec.start) return emptyList()
        val size = spec.end - spec.start + 1
        return List(size) { index ->
            if (reverse) {
                if (spec.increment < 0.0) {
                    (spec.end - index) / -spec.increment
                } else {
                    (spec.end - index) * spec.increment
                }
            } else if (spec.increment < 0.0) {
                (spec.start + index) / -spec.increment
            } else {
                (spec.start + index) * spec.increment
            }
        }
    }

    fun formatNumber(value: Double): String {
        if (value == 0.0) return "0"
        if (value % 1.0 == 0.0 && abs(value) < Long.MAX_VALUE.toDouble()) {
            return value.toLong().toString()
        }
        return value.toString()
            .replace("E+", "e+")
            .replace("E", "e")
    }

    private fun tickSpec(
        start: Double,
        stop: Double,
        count: Double,
    ): TickSpec? {
        val step = (stop - start) / maxOf(0.0, count)
        if (!step.isFinite() || step <= 0.0) return null
        val power = floor(log10(step))
        val error = step / 10.0.pow(power)
        val factor = when {
            error >= E10 -> 10.0
            error >= E5 -> 5.0
            error >= E2 -> 2.0
            else -> 1.0
        }
        var first: Int
        var last: Int
        val increment: Double
        if (power < 0) {
            val multiplier = 10.0.pow(-power) / factor
            first = jsRound(start * multiplier)
            last = jsRound(stop * multiplier)
            if (first / multiplier < start) first += 1
            if (last / multiplier > stop) last -= 1
            increment = -multiplier
        } else {
            val multiplier = 10.0.pow(power) * factor
            first = jsRound(start / multiplier)
            last = jsRound(stop / multiplier)
            if (first * multiplier < start) first += 1
            if (last * multiplier > stop) last -= 1
            increment = multiplier
        }
        if (last < first && count >= 0.5 && count < 2.0) {
            return tickSpec(start, stop, count * 2.0)
        }
        return TickSpec(first, last, increment)
    }

    private fun jsRound(value: Double): Int = floor(value + 0.5).toInt()

    private data class TickSpec(
        val start: Int,
        val end: Int,
        val increment: Double,
    )

    private val E10 = sqrt(50.0)
    private val E5 = sqrt(10.0)
    private val E2 = sqrt(2.0)
}
