package dev.supermux.editor.syntax

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val GO = "package main\n\nfunc main() {\n\tprintln(\"ağ 😀\")\n}\n"

/**
 * First use of a grammar from many threads at once. jvmTest forks a JVM per test class, so nothing
 * has loaded go or fsharp in this process before.
 */
class ConcurrentLoadTest {
    private fun <T> race(threads: Int, task: () -> T): List<T> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures = List(threads) { pool.submit(Callable { start.await(); task() }) }
            start.countDown()
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun bundledTablesInflateOnceUnderContention() {
        val sexps = race(8) { SyntaxParser("go").use { p -> p.parse(GO).use { it.sexp() } } }
        assertTrue(sexps.all { it == sexps[0] }, sexps.toSet().toString())
        assertTrue(sexps[0].startsWith("(source_file"), sexps[0])
        assertFalse("ERROR" in sexps[0], sexps[0])
    }

    @Test
    fun providedTablesUnderContention() {
        val blob = testResource("sesz/fsharp.sesz")
        val sexps = race(8) {
            SyntaxLanguages.provideTables("fsharp", blob)
            SyntaxParser("fsharp").use { p -> p.parse(FSHARP_SAMPLE).use { it.sexp() } }
        }
        assertEquals(1, sexps.toSet().size)
        assertTrue(sexps[0].startsWith("(file (named_module"), sexps[0].take(200))
    }
}
