package dev.supermux.terminal

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Threading contract of the JVM binding (native/README.md "Threading"). */
class ConcurrencyTest {
    private val size = TerminalSize(40, 6, 8, 16)

    private fun TerminalViewport.rowText(row: Int): String =
        rows.first { it.index == row }.cells.filter { it.width != 0 }.joinToString("") { it.text.ifEmpty { " " } }.trimEnd()

    @Test fun fourThreadsWithOwnEngines() {
        val failure = AtomicReference<Throwable?>(null)
        val start = CyclicBarrier(4)
        val threads = (0 until 4).map { id ->
            thread(name = "st-engine-$id") {
                try {
                    start.await()
                    repeat(25) { cycle ->
                        val engine = createTerminalEngine(size, TerminalLimits(historyLines = 100, historyBytes = 1L shl 20))
                        try {
                            repeat(20) { step ->
                                val marker = "t$id-c$cycle-s$step"
                                engine.feed("\u001b[H\u001b[2K$marker\u001b[6n".encodeToByteArray(), OutputOrigin.LIVE)
                                val frame = engine.viewport(forceFull = step % 3 == 0)
                                engine.acknowledge(frame.generation)
                                assertEquals(marker, engine.viewport(forceFull = true).rowText(0))
                                val responses = engine.drainEffects().filterIsInstance<TerminalEffect.Response>()
                                assertEquals(1, responses.size)
                            }
                        } finally {
                            engine.close()
                        }
                        assertFailsWith<IllegalStateException> { engine.feed(byteArrayOf(0x41), OutputOrigin.LIVE) }
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
        }
        threads.forEach { it.join(120_000) }
        assertTrue(threads.none { it.isAlive }, "threads finished")
        failure.get()?.let { throw it }
    }

    @Test fun closeRacesFeedOnSameEngine() {
        repeat(40) { round ->
            val engine = createTerminalEngine(size, TerminalLimits())
            val running = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>(null)
            var fedAfterClose = 0
            val feeder = thread(name = "st-feeder-$round") {
                var first = true
                while (true) {
                    try {
                        engine.feed("line $round\r\n\u001b[6n".encodeToByteArray(), OutputOrigin.LIVE)
                        engine.viewport()
                        engine.drainEffects()
                    } catch (e: IllegalStateException) {
                        // Only the closed-engine error is acceptable here.
                        if ("closed" !in e.message.orEmpty()) failure.compareAndSet(null, e)
                        break
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                        break
                    }
                    if (first) { running.countDown(); first = false }
                }
                fedAfterClose++
            }
            running.await()
            if (round % 2 == 0) Thread.sleep(1)
            val closer = thread(name = "st-closer-$round") { engine.close() }
            engine.close() // two concurrent closes: idempotent
            closer.join()
            feeder.join(10_000)
            assertTrue(!feeder.isAlive)
            assertNull(failure.get())
            assertEquals(1, fedAfterClose)
            // After close every call throws IllegalStateException; close stays idempotent.
            assertFailsWith<IllegalStateException> { engine.feed(byteArrayOf(0x41), OutputOrigin.LIVE) }
            assertFailsWith<IllegalStateException> { engine.viewport() }
            assertFailsWith<IllegalStateException> { engine.drainEffects() }
            assertFailsWith<IllegalStateException> { engine.paste("x") }
            assertFailsWith<IllegalStateException> { engine.resize(size) }
            assertFailsWith<IllegalStateException> { engine.selectedText() }
            engine.close()
        }
    }

    /**
     * Two threads closing the SAME [RendererLease] must release it once, not twice.
     *
     * A double release decrements the session's lease count for a lease that was only ever taken
     * once, which turns publication off for a SIBLING surface that is still on screen — a frozen
     * terminal with no error anywhere. The check-then-act this replaced could do exactly that; the
     * assertion is on the sibling, which is what the user would notice.
     */
    @Test fun closingOneLeaseTwiceAtOnceDoesNotStrandASibling() = runBlocking {
        repeat(200) { round ->
            val session = TerminalSession.open(size, TerminalLimits(), effects = {})
            try {
                val sibling = session.attachRenderer()
                val doomed = session.attachRenderer()
                session.acknowledge(session.viewports.value.generation)
                val start = CyclicBarrier(2)
                val failure = AtomicReference<Throwable?>(null)
                val racer = thread(name = "st-lease-$round") {
                    try {
                        start.await()
                        doomed.close()
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    }
                }
                start.await()
                doomed.close()
                racer.join(10_000)
                assertNull(failure.get())
                assertTrue(!doomed.active, "the lease is released after either close wins")

                // The sibling still holds a lease, so the session must still publish to it.
                val before = session.viewports.value.generation
                session.receive("round $round".encodeToByteArray())
                withTimeout(10_000) {
                    session.viewports.first { it.generation != before && it.rowText(0) == "round $round" }
                }
                sibling.close()
            } finally {
                session.close()
            }
        }
    }
}
