package dev.supermux.webview.engine.ios

import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import platform.Foundation.NSThread
import platform.Foundation.NSTimer
import platform.posix.usleep
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The tests' own waiting: what they wait for arrives however late the run loop gets to it. */
class IosTestSupportTest {
    private val engine = IosWebEngine.create(EngineConfig(WebProfile.Ephemeral))
    private val log = EventLog(engine)

    @AfterTest fun releaseAll() {
        log.stop()
        engine.release()
    }

    /**
     * On a loaded machine the run loop can serve something slow before the main queue, where Dispatchers.Main delivers
     * the engine's events, until a fixed time window is gone: here a timer due before the event is queued.
     */
    @Test fun aLogSeesWhatTheEngineQueuedEvenIfTheRunLoopGetsToItLate() {
        NSTimer.scheduledTimerWithTimeInterval(0.0, repeats = false) { _ -> NSThread.sleepForTimeInterval(0.3) }
        usleep(20_000u)
        engine.onTouch()
        assertEquals(listOf(EngineEvent.FocusChanged(true)), log.settle().filterIsInstance<EngineEvent.FocusChanged>())
    }
}
