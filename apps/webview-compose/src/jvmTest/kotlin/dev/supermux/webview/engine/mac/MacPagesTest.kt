package dev.supermux.webview.engine.mac

import kotlinx.coroutines.test.runTest
import java.util.concurrent.Executor
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MacPagesTest {
    private val queued = mutableListOf<Runnable>()
    private val previous = MacPages.executor

    @BeforeTest fun recordTheMainThread() {
        MacPages.executor = Executor { synchronized(queued) { queued += it } }
    }

    @AfterTest fun restore() {
        MacPages.executor = previous
        MacPages.onMemoryPressure = {}
    }

    private fun runQueued() {
        val due = synchronized(queued) { queued.toList().also { queued.clear() } }
        due.forEach { it.run() }
    }

    @Test fun upcallsReachTheirPageOnTheMainThreadInOrder() {
        val (token, _) = MacPages.newToken()
        val seen = mutableListOf<GlueEvent>()
        MacPages.register(token) { seen += it }
        thread {
            MacGlue.onEvent(token, GlueEvent.STARTED, 1, 0, 0, 0.0, 0.0, "https://a.test/", null, null, null, null)
            MacGlue.onEvent(token, GlueEvent.TICK, 0, 5, 1, 0.0, 0.0, null, null, null, null, null)
        }.join()
        assertTrue(seen.isEmpty(), "nothing runs on the glue's thread")
        runQueued()
        assertEquals(listOf(GlueEvent.Started(1, "https://a.test/"), GlueEvent.Tick(5, inWindow = true)), seen)
        MacPages.unregister(token)
    }

    @Test fun anUnknownOrForgottenPageHearsNothing() {
        val (token, _) = MacPages.newToken()
        val seen = mutableListOf<GlueEvent>()
        MacPages.register(token) { seen += it }
        MacPages.unregister(token)
        MacGlue.onEvent(token, GlueEvent.CRASHED, 0, 0, 0, 0.0, 0.0, null, null, null, null, null)
        MacGlue.onEvent(token + 1000, GlueEvent.CRASHED, 0, 0, 0, 0.0, 0.0, null, null, null, null, null)
        runQueued()
        assertTrue(seen.isEmpty())
    }

    @Test fun creationHandsTheHandleToItsToken() = runTest {
        val (token, created) = MacPages.newToken()
        MacGlue.onEvent(token, GlueEvent.CREATED, 0, 0x1234, 0, 0.0, 0.0, null, null, null, null, null)
        runQueued()
        assertEquals(0x1234L, created.await())
    }

    @Test fun aPageMadeAfterItsCreationWasGivenUpOnIsFreed() {
        val (token, _) = MacPages.newToken()
        val freed = mutableListOf<Long>()
        MacPages.abandon(token) { freed += it }
        MacGlue.onEvent(token, GlueEvent.CREATED, 0, 0x55, 0, 0.0, 0.0, null, null, null, null, null)
        runQueued()
        assertEquals(listOf(0x55L), freed)
    }

    @Test fun memoryPressureReachesItsListener() {
        var pressures = 0
        MacPages.onMemoryPressure = { pressures++ }
        MacGlue.onEvent(0, GlueEvent.MEMORY, 0, 0, 0, 0.0, 0.0, null, null, null, null, null)
        runQueued()
        assertEquals(1, pressures)
    }

    @Test fun anUndecodableUpcallIsDroppedQuietly() {
        MacGlue.onEvent(1, 99, 0, 0, 0, 0.0, 0.0, null, null, null, null, null)
        assertTrue(synchronized(queued) { queued.isEmpty() })
    }
}
