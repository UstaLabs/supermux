package dev.supermux.terminal

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The session contract: one owner coroutine, a bounded mailbox, ordered commands, one
 * unacknowledged frame at a time, and hold breaking for synchronized output. Runs against
 * [RecordingTerminalEngine] on every platform (jvmTest, wasmJsBrowserTest, iosSimulatorArm64Test) —
 * it needs no native engine, so it says the same thing everywhere.
 *
 * The session's owner runs on a [VirtualClock] that is also its [kotlin.time.TimeSource], so
 * `advanceUntilIdle()` / `advanceTimeBy()` drive the loop deterministically and nothing sleeps for
 * real. Calls that do not suspend (`receive` with budget left, every non-blocking enqueue) return
 * without giving the owner a turn, which is what makes the backpressure assertions possible.
 */
class TerminalSessionTest {
    private val size = TerminalSize(20, 4, 8, 16)

    // ------------------------------------------------------------------ harness ----

    private suspend fun VirtualClockScope.openSession(
        engine: RecordingTerminalEngine,
        config: TerminalSessionConfig = TerminalSessionConfig(),
        effects: (TerminalEffect) -> Unit = {},
        onEngineError: (Throwable) -> Unit = {},
    ): TerminalSession = TerminalSession.open(
        size = size,
        limits = TerminalLimits(),
        colors = null,
        config = config,
        context = clock,
        effects = effects,
        onEngineError = onEngineError,
        engineFactory = { _, _ -> engine },
        timeSource = clock,
    )

    /** Tell the session the frame it just published was drawn. */
    private fun TerminalSession.acknowledgeCurrent() = acknowledge(viewports.value.generation)

    // ------------------------------------------------------------------ ordering ----

    @Test fun resetBetweenTwoFeedsLeavesOnlyTheSecondVisible() = terminalTest {
        val engine = RecordingTerminalEngine(size)
        val session = openSession(engine)
        session.acknowledgeCurrent()

        session.receive("A".encodeToByteArray())
        session.reset()
        session.receive("B".encodeToByteArray())
        clock.advanceUntilIdle()

        assertEquals(listOf("feed(LIVE,A)", "reset", "feed(LIVE,B)"), engine.mutations())
        assertEquals("B", engine.screenText())
        assertEquals("B", session.viewports.value.rowTextOrNull(0))
        session.close()
    }

    @Test fun localInputIsOrderedWithServerOutput() = terminalTest {
        val engine = RecordingTerminalEngine(size)
        val session = openSession(engine)

        session.receive("out".encodeToByteArray())
        assertTrue(session.key(TerminalKey(TerminalKeys.A, "a", Modifiers.NONE, KeyAction.PRESS)).accepted)
        session.receive("more".encodeToByteArray())
        assertTrue(session.focus(true).accepted)
        clock.advanceUntilIdle()

        assertEquals(
            listOf("feed(LIVE,out)", "key(4,a)", "feed(LIVE,more)", "focus(true)"),
            engine.mutations(),
        )
        session.close()
    }

    @Test fun replayOutputIsFedWithItsOrigin() = terminalTest {
        val engine = RecordingTerminalEngine(size)
        val effects = mutableListOf<TerminalEffect>()
        val session = openSession(engine, effects = { effects += it })

        session.receive("history\u0007".encodeToByteArray(), OutputOrigin.REPLAY)
        session.receive("live\u0007".encodeToByteArray(), OutputOrigin.LIVE)
        clock.advanceUntilIdle()

        assertEquals(listOf("feed(REPLAY,history\\a)", "feed(LIVE,live\\a)"), engine.mutations())
        assertEquals(listOf<TerminalEffect>(TerminalEffect.Bell), effects, "REPLAY must not produce effects")
        session.close()
    }

    // ------------------------------------------------------------------ frame cadence ----

    @Test fun publishesAtMostOneUnacknowledgedFrameAndMergesDirtyRows() = terminalTest {
        val engine = RecordingTerminalEngine(size)
        val session = openSession(engine)

        val first = session.viewports.value
        assertTrue(first.full, "the first frame is always full")
        session.acknowledgeCurrent()

        session.receive("a".encodeToByteArray())
        clock.advanceUntilIdle()
        val second = session.viewports.value
        assertNotEquals(first.generation, second.generation)
        assertFalse(second.full)
        assertEquals(listOf(0), second.rows.map { it.index })

        // Unacknowledged: output keeps being parsed, no further frame is published.
        session.receive("\nb".encodeToByteArray())
        session.receive("\nc".encodeToByteArray())
        clock.advanceUntilIdle()
        assertEquals(second.generation, session.viewports.value.generation)
        assertEquals("a\nb\nc", engine.screenText())

        // The acknowledgement releases ONE frame carrying everything that changed meanwhile.
        session.acknowledge(second.generation)
        clock.advanceUntilIdle()
        val third = session.viewports.value
        assertEquals(listOf(1, 2), third.rows.map { it.index })
        assertEquals("b", third.rowTextOrNull(1))
        assertEquals("c", third.rowTextOrNull(2))
        session.close()
    }

    @Test fun acknowledgingAStaleGenerationDoesNotReleaseAFrame() = terminalTest {
        val engine = RecordingTerminalEngine(size)
        val session = openSession(engine)
        val first = session.viewports.value
        session.acknowledgeCurrent()
        session.receive("a".encodeToByteArray())
        clock.advanceUntilIdle()
        val second = session.viewports.value

        session.acknowledge(first.generation)
        session.receive("b".encodeToByteArray())
        clock.advanceUntilIdle()
        assertEquals(second.generation, session.viewports.value.generation)
        session.close()
    }

    @Test fun hiddenSessionKeepsParsingAndShowingRepublishesAFullFrame() = terminalTest {
        val engine = RecordingTerminalEngine(size)
        val effects = mutableListOf<TerminalEffect>()
        val session = openSession(engine, effects = { effects += it })
        session.acknowledgeCurrent()
        clock.advanceUntilIdle()

        session.setRenderingEnabled(false)
        clock.advanceUntilIdle()
        val hidden = session.viewports.value

        session.receive("hidden\u0007".encodeToByteArray())
        clock.advanceUntilIdle()
        assertEquals(hidden.generation, session.viewports.value.generation, "a hidden view gets no frames")
        assertEquals("hidden", engine.screenText(), "but output is still parsed")
        assertEquals(listOf<TerminalEffect>(TerminalEffect.Bell), effects, "and effects are still delivered")

        session.setRenderingEnabled(true)
        clock.advanceUntilIdle()
        val shown = session.viewports.value
        assertNotEquals(hidden.generation, shown.generation)
        assertTrue(shown.full, "a re-shown view cannot be patched, it must be redrawn")
        assertEquals("hidden", shown.rowTextOrNull(0))
        session.close()
    }

    // ------------------------------------------------------------------ synchronized output ----

    @Test fun aHeldFrameIsBrokenOnlyAfterTheHoldTimeout() = terminalTest {
        val engine = RecordingTerminalEngine(size)
        val session = openSession(
            engine,
            TerminalSessionConfig(holdTimeout = 1000.milliseconds, holdPollInterval = 50.milliseconds),
        )
        session.acknowledgeCurrent()
        session.receive("live".encodeToByteArray())
        clock.advanceUntilIdle()
        session.acknowledgeCurrent()

        session.receive((RecordingTerminalEngine.SYNC_BEGIN + "\nheld").encodeToByteArray())
        clock.runCurrent()
        val held = session.viewports.value
        assertTrue(held.held, "the frame the program wants shown is published as held")
        assertEquals("", held.rowTextOrNull(1), "what was written behind the hold is not shown")
        assertEquals(0, engine.breakHoldReads)
        session.acknowledge(held.generation)

        clock.advanceTimeBy(900)
        clock.runCurrent()
        assertEquals(0, engine.breakHoldReads, "the hold must survive until the timeout")
        assertEquals(held.generation, session.viewports.value.generation)

        clock.advanceTimeBy(200)
        clock.runCurrent()
        assertEquals(1, engine.breakHoldReads, "the engine has no clock: the owner loop breaks the hold")
        val live = session.viewports.value
        assertFalse(live.held)
        assertEquals("held", live.rowTextOrNull(1))
        session.close()
    }

    @Test fun aHoldTheProgramEndsItselfIsNeverBroken() = terminalTest {
        val engine = RecordingTerminalEngine(size)
        val session = openSession(
            engine,
            TerminalSessionConfig(holdTimeout = 1000.milliseconds, holdPollInterval = 50.milliseconds),
        )
        session.acknowledgeCurrent()

        session.receive((RecordingTerminalEngine.SYNC_BEGIN + "held").encodeToByteArray())
        clock.runCurrent()
        session.acknowledgeCurrent()
        clock.advanceTimeBy(200)
        clock.runCurrent()

        session.receive(RecordingTerminalEngine.SYNC_END.encodeToByteArray())
        clock.advanceUntilIdle()
        assertEquals(0, engine.breakHoldReads)
        assertFalse(session.viewports.value.held)
        assertEquals("held", session.viewports.value.rowTextOrNull(0))
        session.close()
    }

    // ------------------------------------------------------------------ bounds ----

    @Test fun receiveBackpressuresWhenTheOutputBudgetIsSpent() = terminalTest {
        val engine = RecordingTerminalEngine(size)
        val session = openSession(engine, TerminalSessionConfig(maxPendingOutputBytes = 4096))
        val chunk = ByteArray(1024) { 'x'.code.toByte() }

        repeat(4) { session.receive(chunk) }
        val blocked = launch(start = CoroutineStart.UNDISPATCHED) { session.receive(chunk) }
        assertTrue(blocked.isActive, "receive must suspend once the pending-output budget is spent")
        assertEquals(0, engine.calls.count { it.startsWith("feed(") }, "the owner has not run yet")

        clock.advanceUntilIdle()
        assertTrue(blocked.isCompleted, "draining the mailbox gives the budget back")
        assertEquals(5, engine.calls.count { it.startsWith("feed(") })
        session.close()
    }

    @Test fun inputEnqueueIsRejectedInsteadOfBlockingWhenTheInputBudgetIsSpent() = terminalTest {
        val engine = RecordingTerminalEngine(size)
        val session = openSession(engine, TerminalSessionConfig(maxPendingInputBytes = 1024))
        val key = TerminalKey(TerminalKeys.A, "x".repeat(240), Modifiers.NONE, KeyAction.PRESS)

        val results = List(6) { session.key(key) }
        assertEquals(4, results.count { it.accepted })
        assertEquals(EnqueueResult.Rejected(RejectionReason.QUEUE_FULL), results.last())

        clock.advanceUntilIdle()
        assertTrue(session.key(key).accepted, "the budget is returned once the events ran")

        session.close()
        assertEquals(EnqueueResult.Rejected(RejectionReason.CLOSED), session.key(key))
    }

    @Test fun theMailboxIsBoundedByCount() = terminalTest {
        val engine = RecordingTerminalEngine(size)
        val session = openSession(engine, TerminalSessionConfig(mailboxCapacity = 4))
        val mouse = TerminalMouse(0, 0, MouseButton.LEFT, Modifiers.NONE, MouseAction.PRESS)

        val results = List(8) { session.mouse(mouse) }
        assertEquals(4, results.count { it.accepted })
        assertTrue(results.drop(4).all { it == EnqueueResult.Rejected(RejectionReason.QUEUE_FULL) })
        clock.advanceUntilIdle()
        session.close()
    }

    @Test fun aBurstLargerThanOneBatchIsFedInOrderAcrossYields() = terminalTest {
        val engine = RecordingTerminalEngine(size)
        val session = openSession(
            engine,
            TerminalSessionConfig(maxCommandsPerBatch = 4, maxBytesPerBatch = 16, mailboxCapacity = 64),
        )
        repeat(20) { session.receive("$it;".encodeToByteArray()) }
        clock.advanceUntilIdle()

        assertEquals(List(20) { "feed(LIVE,$it;)" }, engine.mutations())
        session.close()
    }

    // ------------------------------------------------------------------ lifecycle ----

    @Test fun cancellingOpenClosesTheEngineAgain() = terminalTest {
        val engines = mutableListOf<RecordingTerminalEngine>()
        val opening = arrayOfNulls<Job>(1)
        val job = launch {
            TerminalSession.open(
                size = size,
                limits = TerminalLimits(),
                colors = null,
                config = TerminalSessionConfig(),
                context = clock,
                effects = {},
                onEngineError = {},
                engineFactory = { grid, _ ->
                    // Cancel the opener while the owner coroutine is still starting up.
                    opening[0]?.cancel()
                    RecordingTerminalEngine(grid).also { engines += it }
                },
                timeSource = clock,
            )
        }
        opening[0] = job
        clock.advanceUntilIdle()

        assertTrue(job.isCancelled)
        assertEquals(1, engines.size)
        assertTrue(engines.single().isClosed, "a cancelled open must not leak the native engine")
    }

    @Test fun closeIsIdempotentAndClosesTheEngineExactlyOnce() = terminalTest {
        val engine = RecordingTerminalEngine(size)
        val session = openSession(engine)
        session.receive("queued".encodeToByteArray())

        session.close()
        session.close()

        assertEquals(1, engine.closeCount)
        assertTrue(engine.mutations().contains("feed(LIVE,queued)"), "accepted output is drained before closing")
    }

    @Test fun closingNeverEmitsAnEventOfItsOwn() = terminalTest {
        val effects = mutableListOf<TerminalEffect>()
        val engine = RecordingTerminalEngine(size)
        val session = openSession(engine, effects = { effects += it })
        session.receive("a\u0007".encodeToByteArray())
        session.reset()
        session.receive("b\u0007".encodeToByteArray())
        assertTrue(session.key(TerminalKey(TerminalKeys.A, "a", Modifiers.NONE, KeyAction.PRESS)).accepted)
        clock.advanceUntilIdle()

        assertEquals(
            listOf(TerminalEffect.Bell, TerminalEffect.Bell, TerminalEffect.Input("a".encodeToByteArray())),
            effects,
            "effects must survive a reset between them",
        )

        val before = effects.size
        session.close()
        assertEquals(before, effects.size, "the local engine stopping is not a terminal event")
    }

    @Test fun suspendingCallsAfterCloseFail() = terminalTest {
        val engine = RecordingTerminalEngine(size)
        val session = openSession(engine)
        session.close()
        val failure = runCatching { session.receive("x".encodeToByteArray()) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException, "got $failure")
    }

    // ------------------------------------------------------------------ replies ----

    @Test fun pasteReturnsTheEnginesAnswerAndSelectedTextRunsOnTheOwner() = terminalTest {
        val engine = RecordingTerminalEngine(size)
        val effects = mutableListOf<TerminalEffect>()
        val session = openSession(engine, effects = { effects += it })

        assertFalse(session.paste("a\nb"), "an unsafe paste sends nothing")
        assertTrue(session.paste("a\nb", allowUnsafe = true))
        assertContentEquals(
            listOf(TerminalEffect.Input("a\nb".encodeToByteArray())),
            effects,
        )

        assertTrue(session.select(TerminalSelection(TerminalPoint(0, 0), TerminalPoint(0, 3))).accepted)
        assertEquals("0:0-0:3", session.selectedText())
        session.close()
    }

    @Test fun anEffectConsumerMayEnqueueBackIntoTheSession() = terminalTest {
        val engine = RecordingTerminalEngine(size)
        lateinit var session: TerminalSession
        val echoed = mutableListOf<EnqueueResult>()
        session = openSession(engine, effects = { effect ->
            // Responses are what a host writes back to its transport; enqueuing from the consumer
            // must never re-enter the engine, only queue for the next batch.
            if (effect is TerminalEffect.Bell) {
                echoed += session.key(TerminalKey(TerminalKeys.B, "b", Modifiers.NONE, KeyAction.PRESS))
            }
        })

        session.receive("\u0007".encodeToByteArray())
        clock.advanceUntilIdle()

        assertEquals(listOf<EnqueueResult>(EnqueueResult.Accepted), echoed)
        assertEquals(listOf("feed(LIVE,\\a)", "key(5,b)"), engine.mutations())
        session.close()
    }

    // ------------------------------------------------------------------ engine failures ----

    @Test fun anUnrecoverableEngineFailureFailsQueuedRepliesInsteadOfHanging() = terminalTest {
        val engine = RecordingTerminalEngine(size)
        val errors = mutableListOf<Throwable>()
        val session = openSession(engine, onEngineError = { errors += it })

        var pasted: Result<Boolean>? = null
        var selected: Result<String>? = null
        // Queued BEFORE the fatal feed: this one still runs and answers.
        launch(start = CoroutineStart.UNDISPATCHED) { pasted = runCatching { session.paste("ok") } }
        engine.failingCalls += "feed"
        session.receive("boom".encodeToByteArray())
        // Queued AFTER it: nothing will ever execute it, so teardown must fail it.
        launch(start = CoroutineStart.UNDISPATCHED) { selected = runCatching { session.selectedText() } }
        clock.advanceUntilIdle()

        assertEquals(true, pasted?.getOrNull())
        val selectedOutcome = assertNotNull(selected, "selectedText() must fail, not hang, when the owner loop is gone")
        assertTrue(selectedOutcome.isFailure, "got $selectedOutcome")
        assertTrue(engine.isClosed, "the engine is closed even on an unexpected failure")
        assertTrue(errors.isEmpty(), "an unrecoverable failure is not an onEngineError report")

        assertEquals(
            EnqueueResult.Rejected(RejectionReason.CLOSED),
            session.key(TerminalKey(TerminalKeys.A, "a", Modifiers.NONE, KeyAction.PRESS)),
        )
        val afterwards = runCatching { session.receive("x".encodeToByteArray()) }.exceptionOrNull()
        assertTrue(afterwards is IllegalStateException, "got $afterwards")
        val closeFailure = runCatching { session.close() }.exceptionOrNull()
        assertTrue(closeFailure is IllegalStateException, "close() rethrows what stopped the loop, got $closeFailure")
    }

    @Test fun aReceiveWaitingForBudgetFailsWhenTheOwnerDies() = terminalTest {
        val engine = RecordingTerminalEngine(size)
        val session = openSession(engine, TerminalSessionConfig(maxPendingOutputBytes = 4096))
        val chunk = ByteArray(1024) { 'x'.code.toByte() }
        repeat(4) { session.receive(chunk) }

        engine.failingCalls += "feed"
        var blockedResult: Result<Unit>? = null
        val blocked = launch(start = CoroutineStart.UNDISPATCHED) {
            blockedResult = runCatching { session.receive(chunk) }
        }
        assertTrue(blocked.isActive, "the budget is spent, so this receive is waiting")

        clock.advanceUntilIdle()
        val outcome = assertNotNull(blockedResult, "a receive waiting for budget must not hang when the owner dies")
        assertTrue(outcome.isFailure, "got $outcome")
        assertTrue(engine.isClosed)
    }

    @Test fun anEngineThatThrowsOnCloseStillReleasesWaitingCallers() = terminalTest {
        val engine = RecordingTerminalEngine(size)
        val session = openSession(engine)
        engine.failingCalls += "feed"
        engine.failingCalls += "close"

        var selected: Result<String>? = null
        session.receive("boom".encodeToByteArray())
        launch(start = CoroutineStart.UNDISPATCHED) { selected = runCatching { session.selectedText() } }
        clock.advanceUntilIdle()

        val outcome = assertNotNull(selected, "a failing engine.close() must not swallow the mailbox teardown")
        assertTrue(outcome.isFailure, "got $outcome")
    }

    @Test fun aHostErrorCallbackThatThrowsDoesNotStopTheSession() = terminalTest {
        val engine = RecordingTerminalEngine(size)
        var reported = 0
        val session = openSession(engine, onEngineError = { reported++; throw IllegalStateException("host bug") })
        session.acknowledgeCurrent()

        // The recoverable kind: the engine reports a limit, the session carries on.
        engine.injectedFailure = { TerminalNativeException(-5, "injected ST_ERR_LIMIT") }
        engine.failingCalls += "feed"
        session.receive("dropped".encodeToByteArray())
        clock.advanceUntilIdle()
        assertEquals(1, reported)

        engine.failingCalls.clear()
        session.receive("kept".encodeToByteArray())
        clock.advanceUntilIdle()
        assertEquals("kept", engine.screenText())
        assertEquals("kept", session.viewports.value.rowTextOrNull(0))
        session.close()
    }

    @Test fun anEffectConsumerThatThrowsCostsNeitherTheNextEffectNorTheSession() = terminalTest {
        val engine = RecordingTerminalEngine(size)
        val seen = mutableListOf<TerminalEffect>()
        val errors = mutableListOf<Throwable>()
        val session = openSession(
            engine,
            effects = { effect -> seen += effect; if (effect is TerminalEffect.Bell) throw IllegalStateException("host bug") },
            onEngineError = { errors += it },
        )
        session.acknowledgeCurrent()

        session.receive("\u0007a".encodeToByteArray())
        assertTrue(session.key(TerminalKey(TerminalKeys.B, "b", Modifiers.NONE, KeyAction.PRESS)).accepted)
        clock.advanceUntilIdle()

        assertEquals(
            listOf<TerminalEffect>(TerminalEffect.Bell, TerminalEffect.Input("b".encodeToByteArray())),
            seen,
        )
        assertEquals(1, errors.size)
        session.close()
    }

    @Test fun theFailureFlowPublishesWhatStoppedTheOwnerLoop() = terminalTest {
        val engine = RecordingTerminalEngine(size)
        val session = openSession(engine)
        session.acknowledgeCurrent()
        assertNull(session.failure.value, "a healthy session has no failure")

        engine.failingCalls += "feed"
        session.receive("boom".encodeToByteArray())
        clock.advanceUntilIdle()

        // A renderer that only uses the non-blocking calls sees the failure HERE and nowhere else:
        // key() only says "closed", and it never awaits anything that could throw.
        val published = assertNotNull(session.failure.value, "the fatal failure must be observable")
        assertTrue(published is IllegalStateException, "got $published")
        assertEquals(
            EnqueueResult.Rejected(RejectionReason.CLOSED),
            session.key(TerminalKey(TerminalKeys.A, "a", Modifiers.NONE, KeyAction.PRESS)),
        )
        assertSame(published, runCatching { session.close() }.exceptionOrNull())
    }

    @Test fun anOrdinaryCloseLeavesTheFailureFlowEmpty() = terminalTest {
        val engine = RecordingTerminalEngine(size)
        val session = openSession(engine)
        session.acknowledgeCurrent()
        session.receive("hi".encodeToByteArray())
        clock.advanceUntilIdle()
        session.close()
        assertNull(session.failure.value, "close() is not a failure")
    }

    // ------------------------------------------------------------------ full-frame requests ----

    @Test fun requestFullFrameRepublishesEveryRowForARendererThatAttachedMidStream() = terminalTest {
        val engine = RecordingTerminalEngine(size)
        val session = openSession(engine)
        session.acknowledgeCurrent()

        // Two acknowledged feeds: the second frame is PARTIAL (only the rows that changed), which
        // is all a renderer attaching now would see.
        session.receive("first\n".encodeToByteArray())
        clock.advanceUntilIdle()
        session.acknowledgeCurrent()
        session.receive("second".encodeToByteArray())
        clock.advanceUntilIdle()
        val partial = session.viewports.value
        assertFalse(partial.full, "the mid-stream frame is partial")
        session.acknowledgeCurrent()

        // Nothing changes the screen afterwards: only the request may produce the next frame.
        session.requestFullFrame()
        clock.advanceUntilIdle()
        val full = session.viewports.value
        assertTrue(full.full, "requestFullFrame() must publish a full frame")
        assertEquals(size.rows, full.rows.size)
        assertEquals("first", full.rowTextOrNull(0))
        assertEquals("second", full.rowTextOrNull(1))

        // And the cadence is intact: the full frame is acknowledgeable and later output flows again.
        session.acknowledgeCurrent()
        session.receive("third".encodeToByteArray())
        clock.advanceUntilIdle()
        assertEquals("secondthird", session.viewports.value.rowTextOrNull(1))
        session.close()
    }
}
