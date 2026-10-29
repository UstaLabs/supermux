package dev.supermux.webview.engine

import dev.supermux.webview.bridge.WebBridge
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EngineEventQueueTest {
    private val origin = "https://a.test"

    private fun ready(nonce: String) =
        EngineEvent.PageMessage(origin, """{"t":"event","n":"${'$'}ready","d":{"nonce":"$nonce"},"from":"$nonce"}""")

    private fun post(n: Int) = EngineEvent.PageMessage(origin, """{"t":"event","n":"x","d":$n,"from":"n1"}""")

    private fun EngineEventQueue.drain(): List<EngineEvent> = generateSequence { poll() }.toList()

    @Test fun eventsComeOutInTheOrderTheyWentIn() {
        val q = EngineEventQueue()
        q.offer(EngineEvent.UrlChanged("https://a.test/"))
        q.offer(EngineEvent.Progress(0.5f))
        q.offer(EngineEvent.FirstPaint)
        assertEquals(
            listOf(EngineEvent.UrlChanged("https://a.test/"), EngineEvent.Progress(0.5f), EngineEvent.FirstPaint),
            q.drain(),
        )
    }

    @Test fun aNewEpochDropsWhatEarlierOnesLeftButTheEnginesOwnEvents() {
        val q = EngineEventQueue()
        q.offer(EngineEvent.TitleChanged("old page"))
        q.offer(EngineEvent.Crashed)
        q.offer(EngineEvent.FocusChanged(true))
        q.offer(post(1))
        q.advance()
        assertEquals(listOf(EngineEvent.Crashed, EngineEvent.FocusChanged(true)), q.drain())
    }

    @Test fun aResetEpochDropsQueuedFocusTooButNeverACrash() {
        val q = EngineEventQueue()
        q.offer(EngineEvent.FocusChanged(true))
        q.offer(EngineEvent.TitleChanged("the last lease's page"))
        q.offer(EngineEvent.Crashed)
        q.offer(EngineEvent.FocusChanged(false))
        q.advance(reset = true)
        assertEquals(listOf<EngineEvent>(EngineEvent.Crashed), q.drain(), "the last lease's focus reaches no one")
        // What the engine reports after the reset is the next lease's.
        q.offer(EngineEvent.FocusChanged(true))
        assertEquals(listOf<EngineEvent>(EngineEvent.FocusChanged(true)), q.drain())
    }

    @Test fun latestValueEventsCoalesceButNeverAcrossACommit() {
        val q = EngineEventQueue()
        q.offer(EngineEvent.TitleChanged("a"))
        q.offer(EngineEvent.Progress(0.1f))
        q.offer(EngineEvent.TitleChanged("b"))
        q.offer(EngineEvent.Progress(0.9f))
        assertEquals(listOf(EngineEvent.TitleChanged("b"), EngineEvent.Progress(0.9f)), q.drain())
        q.offer(EngineEvent.TitleChanged("old"))
        q.offer(EngineEvent.UrlChanged("https://a.test/next"))
        q.offer(EngineEvent.TitleChanged("new"))
        assertEquals(
            listOf(EngineEvent.TitleChanged("old"), EngineEvent.UrlChanged("https://a.test/next"), EngineEvent.TitleChanged("new")),
            q.drain(),
        )
    }

    @Test fun aReadyRightBehindAReadyOfTheSameDocumentReplacesIt() {
        val q = EngineEventQueue()
        val document = Any()
        q.offer(ready("a"), document)
        q.offer(ready("b"), document)
        assertEquals(listOf<EngineEvent>(ready("b")), q.drain())
    }

    @Test fun readiesOfOtherDocumentsOrWithMessagesBetweenThemAreAllKept() {
        val q = EngineEventQueue()
        val one = Any()
        val two = Any()
        q.offer(ready("a"), one)
        q.offer(ready("b"), two)
        q.offer(post(1), two)
        q.offer(ready("c"), two)
        assertEquals(listOf(ready("a"), ready("b"), post(1), ready("c")), q.drain())
    }

    @Test fun aFloodDropsOrdinaryMessagesButNeverTheOnesThatMatter() {
        val q = EngineEventQueue(floodLimit = 2)
        val document = Any()
        q.offer(post(1))
        q.offer(post(2))
        q.offer(post(3))
        q.offer(EngineEvent.Console(dev.supermux.webview.ConsoleMessage(dev.supermux.webview.ConsoleLevel.Log, "x")))
        q.offer(EngineEvent.UrlChanged("https://a.test/"))
        q.offer(ready("a"), document)
        q.offer(EngineEvent.FirstPaint)
        q.offer(EngineEvent.Error(dev.supermux.webview.WebError.Network("https://a.test/", "down")))
        assertEquals(
            listOf(
                post(1),
                post(2),
                EngineEvent.UrlChanged("https://a.test/"),
                ready("a"),
                EngineEvent.FirstPaint,
                EngineEvent.Error(dev.supermux.webview.WebError.Network("https://a.test/", "down")),
            ),
            q.drain(),
        )
        assertEquals(2L, q.dropped)
    }

    @Test fun roomComesBackAsTheSubscriberTakesEvents() {
        val q = EngineEventQueue(floodLimit = 1)
        q.offer(post(1))
        assertEquals(post(1), q.poll())
        q.offer(post(2))
        assertEquals(listOf<EngineEvent>(post(2)), q.drain())
        assertEquals(0L, q.dropped)
    }

    @Test fun aMessageTooLongToBeAReadyIsAnOrdinaryOneEvenWithItsPrefix() {
        val q = EngineEventQueue(floodLimit = 0)
        q.offer(EngineEvent.PageMessage(origin, WebBridge.READY_PREFIX + "x".repeat(WebBridge.READY_MAX_CHARS)), Any())
        assertEquals(emptyList(), q.drain())
        assertEquals(1L, q.dropped)
    }

    @Test fun theFlowDeliversWhatIsOfferedWhileItWaits() = runTest {
        val q = EngineEventQueue()
        q.offer(EngineEvent.UrlChanged("https://a.test/"))
        val got = async { q.events.take(3).toList() }
        runCurrent()
        q.offer(EngineEvent.FirstPaint)
        q.offer(EngineEvent.Progress(null))
        assertEquals(
            listOf(EngineEvent.UrlChanged("https://a.test/"), EngineEvent.FirstPaint, EngineEvent.Progress(null)),
            got.await(),
        )
    }
}
