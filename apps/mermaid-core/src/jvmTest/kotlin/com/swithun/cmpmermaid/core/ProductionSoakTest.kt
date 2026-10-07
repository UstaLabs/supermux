package com.swithun.cmpmermaid.core

import com.swithun.cmpmermaid.core.generated.StabilityCorpusCase
import com.swithun.cmpmermaid.core.generated.productionCorpusCases
import kotlin.math.ceil
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProductionSoakTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            val charactersPerLine = (request.maxWidth / (request.fontSize * 0.55f))
                .toInt()
                .coerceAtLeast(1)
            val lineCount = request.text.lineSequence().sumOf { line ->
                ceil(line.length.toDouble() / charactersPerLine)
                    .toInt()
                    .coerceAtLeast(1)
            }
            TextMetrics(
                width = min(
                    request.maxWidth,
                    request.text.lineSequence()
                        .maxOfOrNull(String::length)
                        .orZero() * request.fontSize * 0.55f,
                ),
                height = lineCount * request.fontSize * request.lineHeight,
            )
        },
    )

    @Test
    fun repeatedlyRendersProductionCorpusWithinJvmBudget() {
        repeat(WARMUP_ROUNDS) {
            productionCorpusCases.forEach(::render)
        }
        forceGc()
        val memoryBefore = usedHeapBytes()
        val durations = mutableListOf<Long>()
        val startedAt = System.nanoTime()

        repeat(MEASURED_ROUNDS) {
            productionCorpusCases.forEach { case ->
                val renderStartedAt = System.nanoTime()
                render(case)
                durations += System.nanoTime() - renderStartedAt
            }
        }

        val totalMillis = nanosToMillis(System.nanoTime() - startedAt)
        forceGc()
        val retainedHeapBytes = (usedHeapBytes() - memoryBefore).coerceAtLeast(0L)
        val sortedDurations = durations.sorted()
        val percentileIndex = ceil(sortedDurations.size * 0.95)
            .toInt()
            .coerceIn(1, sortedDurations.size) - 1
        val p95Millis = nanosToMillis(sortedDurations[percentileIndex])

        println(
            "Production corpus soak: renders=${durations.size}, " +
                "totalMs=$totalMillis, p95Ms=$p95Millis, " +
                "retainedHeapBytes=$retainedHeapBytes",
        )
        assertTrue(
            totalMillis <= MAX_TOTAL_MILLIS,
            "Production corpus took ${totalMillis}ms; budget is ${MAX_TOTAL_MILLIS}ms",
        )
        assertTrue(
            p95Millis <= MAX_P95_MILLIS,
            "Production corpus P95 was ${p95Millis}ms; budget is ${MAX_P95_MILLIS}ms",
        )
        assertTrue(
            retainedHeapBytes <= MAX_RETAINED_HEAP_BYTES,
            "Production corpus retained $retainedHeapBytes bytes; " +
                "budget is $MAX_RETAINED_HEAP_BYTES bytes",
        )
    }

    private fun render(case: StabilityCorpusCase) {
        val result = engine.render(
            source = case.source,
            context = context.copy(
                options = MermaidRenderOptions(layout = case.layout),
            ),
        )
        assertIs<GMResult.Ok<MermaidScene>>(
            result,
            "${case.id} failed with ${(result as? GMResult.Err)?.error}",
        )
    }

    private fun forceGc() {
        repeat(3) {
            System.gc()
            Thread.sleep(25)
        }
    }

    private fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun nanosToMillis(nanos: Long): Long = nanos / 1_000_000L

    private fun Int?.orZero(): Int = this ?: 0

    private companion object {
        const val WARMUP_ROUNDS = 2
        const val MEASURED_ROUNDS = 5
        const val MAX_TOTAL_MILLIS = 45_000L
        const val MAX_P95_MILLIS = 500L
        const val MAX_RETAINED_HEAP_BYTES = 64L * 1024L * 1024L
    }
}
