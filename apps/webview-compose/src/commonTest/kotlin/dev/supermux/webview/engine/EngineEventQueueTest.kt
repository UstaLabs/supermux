package dev.supermux.webview.engine

import dev.supermux.webview.ConsoleLevel
import dev.supermux.webview.ConsoleMessage
import dev.supermux.webview.WebError
import dev.supermux.webview.bridge.WebBridge
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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

    /**
     * A subscriber that is attached but has fallen behind: it heard what was queued before it, and hears the rest only
     * when the test lets it run ([runCurrent]). Events offered meanwhile are offered to a subscribed queue.
     */
    private fun TestScope.behind(q: EngineEventQueue, heard: MutableList<EngineEvent>): Job =
        launch(start = CoroutineStart.UNDISPATCHED) { q.events.collect { heard += it } }

    @Test fun eventsComeOutInTheOrderTheyWentIn() = runTest {
        val q = EngineEventQueue()
        val heard = mutableListOf<EngineEvent>()
        val subscriber = behind(q, heard)
        q.offer(EngineEvent.UrlChanged("https://a.test/"))
        q.offer(EngineEvent.Progress(0.5f))
        q.offer(EngineEvent.FirstPaint)
        runCurrent()
        assertEquals(
            listOf(EngineEvent.UrlChanged("https://a.test/"), EngineEvent.Progress(0.5f), EngineEvent.FirstPaint),
            heard,
        )
        subscriber.cancel()
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

    @Test fun latestValueEventsCoalesceButNeverAcrossACommit() = runTest {
        val q = EngineEventQueue()
        val heard = mutableListOf<EngineEvent>()
        val subscriber = behind(q, heard)
        q.offer(EngineEvent.TitleChanged("a"))
        q.offer(EngineEvent.Progress(0.1f))
        q.offer(EngineEvent.TitleChanged("b"))
        q.offer(EngineEvent.Progress(0.9f))
        runCurrent()
        assertEquals(listOf(EngineEvent.TitleChanged("b"), EngineEvent.Progress(0.9f)), heard)
        heard.clear()
        q.offer(EngineEvent.TitleChanged("old"))
        q.offer(EngineEvent.UrlChanged("https://a.test/next"))
        q.offer(EngineEvent.TitleChanged("new"))
        runCurrent()
        assertEquals(
            listOf(EngineEvent.TitleChanged("old"), EngineEvent.UrlChanged("https://a.test/next"), EngineEvent.TitleChanged("new")),
            heard,
        )
        subscriber.cancel()
    }

    @Test fun aReadyRightBehindAReadyOfTheSameDocumentReplacesIt() {
        val q = EngineEventQueue()
        val document = Any()
        q.offer(ready("a"), document)
        q.offer(ready("b"), document)
        assertEquals(listOf<EngineEvent>(ready("b")), q.drain())
    }

    @Test fun readiesOfOtherDocumentsOrWithMessagesBetweenThemAreAllKept() = runTest {
        val q = EngineEventQueue()
        val heard = mutableListOf<EngineEvent>()
        val subscriber = behind(q, heard)
        val one = Any()
        val two = Any()
        q.offer(ready("a"), one)
        q.offer(ready("b"), two)
        q.offer(post(1), two)
        q.offer(ready("c"), two)
        runCurrent()
        assertEquals(listOf<EngineEvent>(ready("a"), ready("b"), post(1), ready("c")), heard)
        subscriber.cancel()
    }

    @Test fun aFloodDropsOrdinaryMessagesButNeverTheOnesThatMatter() = runTest {
        val q = EngineEventQueue(floodLimit = 2)
        val heard = mutableListOf<EngineEvent>()
        val subscriber = behind(q, heard)
        val document = Any()
        q.offer(post(1))
        q.offer(post(2))
        q.offer(post(3))
        q.offer(EngineEvent.Console(ConsoleMessage(ConsoleLevel.Log, "x")))
        q.offer(EngineEvent.UrlChanged("https://a.test/"))
        q.offer(ready("a"), document)
        q.offer(EngineEvent.FirstPaint)
        q.offer(EngineEvent.Error(WebError.Network("https://a.test/", "down")))
        runCurrent()
        assertEquals(
            listOf(
                post(1),
                post(2),
                EngineEvent.UrlChanged("https://a.test/"),
                ready("a"),
                EngineEvent.FirstPaint,
                EngineEvent.Error(WebError.Network("https://a.test/", "down")),
            ),
            heard,
        )
        assertEquals(2L, q.dropped)
        subscriber.cancel()
    }

    /** A subscriber that fell behind holds no more than [EngineEventQueue]'s character budget of messages either. */
    @Test fun aFloodOfLongMessagesIsDroppedByItsSizeToo() = runTest {
        val q = EngineEventQueue(maxChars = 100)
        val heard = mutableListOf<EngineEvent>()
        val subscriber = behind(q, heard)
        val long = EngineEvent.PageMessage(origin, "x".repeat(60))
        q.offer(long)
        q.offer(long)
        q.offer(post(1))
        runCurrent()
        assertEquals(listOf<EngineEvent>(long, post(1)), heard)
        assertEquals(1L, q.dropped)
        subscriber.cancel()
    }

    @Test fun roomComesBackAsTheSubscriberTakesEvents() = runTest {
        val q = EngineEventQueue(floodLimit = 1)
        val heard = mutableListOf<EngineEvent>()
        val subscriber = behind(q, heard)
        q.offer(post(1))
        runCurrent()
        q.offer(post(2))
        runCurrent()
        assertEquals(listOf<EngineEvent>(post(1), post(2)), heard)
        assertEquals(0L, q.dropped)
        subscriber.cancel()
    }

    @Test fun aMessageTooLongToBeAReadyIsAnOrdinaryOneEvenWithItsPrefix() = runTest {
        val q = EngineEventQueue(floodLimit = 0)
        val heard = mutableListOf<EngineEvent>()
        val subscriber = behind(q, heard)
        q.offer(EngineEvent.PageMessage(origin, WebBridge.READY_PREFIX + "x".repeat(WebBridge.READY_MAX_CHARS)), Any())
        runCurrent()
        assertEquals(emptyList<EngineEvent>(), heard)
        assertEquals(1L, q.dropped)
        subscriber.cancel()
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

    // --- While nobody subscribes (a parked page), the queue keeps what the next subscriber needs, and is bounded ---

    /**
     * A parked demo that writes its state into its URL, at Chromium's limit of about 20 a second for its 10 minutes:
     * one commit and one history change are kept, the latest, not 24,000 of each.
     */
    @Test fun whileNobodySubscribesOnlyTheLatestOfEachStateEventIsKept() {
        val q = EngineEventQueue()
        repeat(24_000) {
            q.offer(EngineEvent.UrlChanged("https://a.test/#$it"))
            q.offer(EngineEvent.HistoryChanged(canGoBack = true, canGoForward = false))
            q.offer(EngineEvent.TitleChanged("step $it"))
            q.offer(EngineEvent.Progress(null))
            q.offer(EngineEvent.ContentSize(100f, it.toFloat()))
            q.offer(EngineEvent.Error(WebError.Network("https://a.test/#$it", "down")))
            q.offer(EngineEvent.FocusChanged(it % 2 == 0))
        }
        assertEquals(
            listOf(
                EngineEvent.UrlChanged("https://a.test/#23999"),
                EngineEvent.HistoryChanged(canGoBack = true, canGoForward = false),
                EngineEvent.TitleChanged("step 23999"),
                EngineEvent.Progress(null),
                EngineEvent.ContentSize(100f, 23_999f),
                EngineEvent.Error(WebError.Network("https://a.test/#23999", "down")),
                EngineEvent.FocusChanged(false),
            ),
            q.drain(),
        )
    }

    /** The iOS review's probe: a page's focus and blur, 20,000 of them while parked, leave one, the latest. */
    @Test fun focusChangesWhileNobodySubscribesLeaveTheLatest() {
        val q = EngineEventQueue()
        repeat(20_000) { q.offer(EngineEvent.FocusChanged(it % 2 == 0)) }
        assertEquals(listOf<EngineEvent>(EngineEvent.FocusChanged(false)), q.drain())
    }

    /**
     * A first paint means the page was drawn in its place, and a page nobody subscribes to has none (a parked page whose
     * view let go): it isn't kept for the next subscriber, whose first paint is the one [WebEngine.replayState] gives.
     */
    @Test fun aFirstPaintWhileNobodySubscribesIsNotKept() {
        val q = EngineEventQueue()
        q.offer(EngineEvent.UrlChanged("https://a.test/"))
        q.offer(EngineEvent.FirstPaint)
        assertEquals(listOf<EngineEvent>(EngineEvent.UrlChanged("https://a.test/")), q.drain())
    }

    /** Nor one a subscriber left unheard when it went: it was the paint of the place that view gave up. */
    @Test fun aFirstPaintALeavingSubscriberLeftUnheardIsNotKept() = runTest {
        val q = EngineEventQueue()
        val heard = mutableListOf<EngineEvent>()
        val subscriber = behind(q, heard)
        q.offer(EngineEvent.UrlChanged("https://a.test/"))
        q.offer(EngineEvent.FirstPaint)
        subscriber.cancel()
        runCurrent()
        assertEquals(listOf<EngineEvent>(EngineEvent.UrlChanged("https://a.test/")), q.drain())
    }

    /**
     * What remains comes in the order it last happened: a page that failed and then went elsewhere is heard failing
     * first, so its later commit clears the error, as it did on the page. What it said meanwhile isn't kept.
     */
    @Test fun theNextSubscriberHearsWhatRemainsInTheOrderItLastHappened() = runTest {
        val q = EngineEventQueue()
        val failed = EngineEvent.Error(WebError.Network("https://a.test/broken", "down"))
        q.offer(EngineEvent.UrlChanged("https://a.test/broken"))
        q.offer(EngineEvent.TitleChanged("broken"))
        q.offer(post(1))
        q.offer(failed)
        q.offer(EngineEvent.UrlChanged("https://a.test/fine"))
        q.offer(EngineEvent.TitleChanged("fine"))
        q.offer(EngineEvent.Progress(null))
        val heard = mutableListOf<EngineEvent>()
        val subscriber = behind(q, heard)
        assertEquals(
            listOf(failed, EngineEvent.UrlChanged("https://a.test/fine"), EngineEvent.TitleChanged("fine"), EngineEvent.Progress(null)),
            heard,
        )
        subscriber.cancel()
    }

    /**
     * The final review's probe, at the queue: a parked demo that talks steadily for its 10 minutes, 2 posts a second
     * and a log line every 100 ms, says 1,200 posts and 6,000 console lines that nobody hears. They are dropped as they
     * come, and counted: nothing of them is held, so no bound is ever reached and the page stays adoptable.
     */
    @Test fun whileNobodySubscribesWhatThePageSaysIsDroppedAsItComes() {
        val q = EngineEventQueue()
        q.offer(EngineEvent.UrlChanged("https://a.test/"))
        repeat(1_200) { q.offer(post(it)) }
        repeat(6_000) { q.offer(EngineEvent.Console(ConsoleMessage(ConsoleLevel.Log, "tick $it"))) }
        assertEquals(7_200L, q.dropped)
        assertEquals(listOf<EngineEvent>(EngineEvent.UrlChanged("https://a.test/")), q.drain())
    }

    /**
     * Of a parked page's `$ready`s only the latest is kept, where it last happened: a page that starts document after
     * document by itself, or asks itself to announce itself again and again, says one each time, and only the current
     * document's matters to the next subscriber.
     */
    @Test fun whileNobodySubscribesOnlyTheLatestReadyIsKept() {
        val q = EngineEventQueue()
        val same = Any()
        repeat(5_000) {
            q.offer(EngineEvent.UrlChanged("https://a.test/$it"))
            q.offer(ready("n$it"), Any())
            q.offer(EngineEvent.TitleChanged("page $it"))
            q.offer(ready("again"), same)
        }
        q.offer(EngineEvent.TitleChanged("last"))
        assertEquals(
            listOf(EngineEvent.UrlChanged("https://a.test/4999"), ready("again"), EngineEvent.TitleChanged("last")),
            q.drain(),
        )
        assertEquals(0L, q.dropped, "a later \$ready replaces an earlier one: none is dropped")
    }

    /**
     * Whatever a parked page does, and however long, its queue holds at most one event of each kind: its state, its
     * latest `$ready`, a lost paint and its crash. What the next subscriber needs, and no more.
     */
    @Test fun aParkedPageHoldsAtMostOneEventOfEachKind() {
        val q = EngineEventQueue()
        val document = Any()
        repeat(2_000) {
            q.offer(EngineEvent.UrlChanged("https://a.test/#$it"))
            q.offer(EngineEvent.TitleChanged("step $it"))
            q.offer(EngineEvent.HistoryChanged(canGoBack = true, canGoForward = false))
            q.offer(EngineEvent.Progress(null))
            q.offer(EngineEvent.ContentSize(100f, it.toFloat()))
            q.offer(EngineEvent.Error(WebError.Network("https://a.test/#$it", "down")))
            q.offer(EngineEvent.FocusChanged(it % 2 == 0))
            q.offer(EngineEvent.FirstPaint)
            q.offer(EngineEvent.PaintLost)
            q.offer(ready("n$it"), document)
            q.offer(post(it))
            q.offer(EngineEvent.Console(ConsoleMessage(ConsoleLevel.Log, "x")))
        }
        q.offer(EngineEvent.Crashed)
        q.offer(EngineEvent.Crashed)
        assertEquals(
            listOf(
                EngineEvent.UrlChanged("https://a.test/#1999"),
                EngineEvent.TitleChanged("step 1999"),
                EngineEvent.HistoryChanged(canGoBack = true, canGoForward = false),
                EngineEvent.Progress(null),
                EngineEvent.ContentSize(100f, 1_999f),
                EngineEvent.Error(WebError.Network("https://a.test/#1999", "down")),
                EngineEvent.FocusChanged(false),
                EngineEvent.PaintLost,
                ready("n1999"),
                EngineEvent.Crashed,
            ),
            q.drain(),
        )
    }

    /**
     * What a subscriber left unheard when it went (a view that parked its page) is cut down the same way: nothing the
     * page said stays, but for its latest `$ready`. The page messages and console lines it drops are counted.
     */
    @Test fun aSubscriberThatLeavesLeavesNothingThePageSaid() = runTest {
        val q = EngineEventQueue()
        val heard = mutableListOf<EngineEvent>()
        val subscriber = behind(q, heard)
        q.offer(EngineEvent.UrlChanged("https://a.test/"))
        repeat(3) { q.offer(post(it)) }
        q.offer(ready("a"), Any())
        q.offer(EngineEvent.Console(ConsoleMessage(ConsoleLevel.Log, "x")))
        subscriber.cancel()
        runCurrent()
        assertEquals(emptyList<EngineEvent>(), heard, "the subscriber left before it caught up")
        assertEquals(listOf(EngineEvent.UrlChanged("https://a.test/"), ready("a")), q.drain())
        assertEquals(4L, q.dropped)
    }

    /** What a subscriber left unheard when it went (a view that parked its page) is cut down the same way. */
    @Test fun aSubscriberThatLeavesLeavesOnlyWhatTheNextOneNeeds() = runTest {
        val q = EngineEventQueue()
        val heard = mutableListOf<EngineEvent>()
        val subscriber = behind(q, heard)
        repeat(1_000) {
            q.offer(EngineEvent.UrlChanged("https://a.test/#$it"))
            q.offer(EngineEvent.HistoryChanged(canGoBack = true, canGoForward = false))
        }
        subscriber.cancel()
        runCurrent()
        assertEquals(emptyList<EngineEvent>(), heard, "the subscriber left before it caught up")
        assertEquals(
            listOf(EngineEvent.UrlChanged("https://a.test/#999"), EngineEvent.HistoryChanged(canGoBack = true, canGoForward = false)),
            q.drain(),
        )
    }

    /** A `$ready` the parked page sends is the one the next subscriber hears, even after a long, chatty park. */
    @Test fun aSubscriberAfterAParkHearsThePagesStateAndItsLatestReady() = runTest {
        val q = EngineEventQueue()
        val document = Any()
        q.offer(EngineEvent.UrlChanged("https://a.test/"))
        repeat(10_000) { q.offer(post(it)) }
        q.offer(ready("b"), document)
        q.offer(EngineEvent.TitleChanged("still here"))
        val heard = mutableListOf<EngineEvent>()
        val subscriber = behind(q, heard)
        assertEquals(listOf(EngineEvent.UrlChanged("https://a.test/"), ready("b"), EngineEvent.TitleChanged("still here")), heard)
        // From then on, the subscriber hears what the page says.
        q.offer(post(1))
        runCurrent()
        assertEquals(post(1), heard.last())
        subscriber.cancel()
    }
}
