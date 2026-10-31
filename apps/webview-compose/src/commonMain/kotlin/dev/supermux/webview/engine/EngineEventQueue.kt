package dev.supermux.webview.engine

import dev.supermux.webview.bridge.WebBridge
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The buffer between an engine's callbacks and its one subscriber at a time: the [WebEngine] contract's **Buffering**
 * and **Documents**. Engines [offer] events as they happen and expose [events]. Main thread only.
 * - Every event belongs to the [epoch] it was offered in. [advance] starts a new one, at every load, reload, restore
 *   and reset: from then on nothing of an earlier epoch is delivered, even what is still queued, except the engine's own
 *   [EngineEvent.Crashed] and [EngineEvent.FocusChanged], which no document caused.
 * - **Engines must drop queued focus changes at [WebEngine.reset]**, by starting its epochs with `advance(reset = true)`:
 *   the focus is the last lease's, and the next lease's subscriber would take it for its own (its view would take the
 *   window's focus, from the view that has it, for a page nobody touched). Only [EngineEvent.Crashed] outlives a reset.
 * - While a subscriber is attached, whom the queue holds events for only until it catches up:
 *   - Never dropped: [EngineEvent.UrlChanged], [EngineEvent.HistoryChanged], [EngineEvent.FirstPaint],
 *     [EngineEvent.Error], [EngineEvent.Crashed], [EngineEvent.FocusChanged], and a page's `$ready`, recognized the way
 *     the bridge recognizes it: at most [WebBridge.READY_MAX_CHARS] characters, starting with [WebBridge.READY_PREFIX].
 *   - Coalesced to the latest, never dropped entirely: [EngineEvent.TitleChanged], [EngineEvent.Progress] and
 *     [EngineEvent.ContentSize] replace a queued event of their kind unless a commit, a paint, an error or a `$ready` is
 *     queued after it, so the order of documents holds; and a `$ready` right behind a `$ready` of the same document
 *     (the `document` an engine passes, compared by identity) replaces it. Repeats can't grow the queue.
 *   - Other page messages and console output are dropped once [floodLimit] page messages and console lines, or
 *     [maxChars] characters of them, are queued; [dropped] counts them.
 * - **While no subscriber is attached** (a parked page, for up to 10 minutes, or a spare), the queue keeps what the next
 *   subscriber needs, and no more: at most one event of each kind, however long and whatever the page does.
 *   - Of each kind of state event (commit, title, history, progress, size, error, focus, a lost paint and the crash)
 *     only the latest, where it happened among the rest: the next subscriber hears what remains in the order it last
 *     happened. [WebEngine.replayState] tells it what the page is now anyway.
 *   - Of the page's `$ready`s, only the latest too: a `$ready` only says which document is there, and only the current
 *     one's matters to the next subscriber.
 *   - No [EngineEvent.FirstPaint]: it means the page was drawn in its place, and a page nobody subscribes to has none
 *     (its view let go of it). The next subscriber's first paint is the one [WebEngine.replayState] gives, once the page
 *     is drawn in its new place.
 *   - No other page message and no console output: what the page says while nobody listens reaches no one
 *     ([WebEngine]'s **Parking**), so it is dropped as it comes, and counted in [dropped]. However much a parked page
 *     says, it holds nothing, and it stays adoptable: a demo that posts twice a second, or logs every 100 ms, for its
 *     whole 10 minutes is adopted live like a quiet one.
 *   - When the last subscriber leaves, what it left unheard is cut down the same way, its first paint dropped too: that
 *     was the paint of the place its view gave up.
 *
 * Part of the engine SPI: every engine of the library buffers its events here, and an engine of another module (the
 * testing module's fake, say) keeps the contract by doing the same.
 *
 * @param floodLimit how many page messages and console lines may wait for a subscriber that fell behind.
 * @param maxChars how many characters of them may wait for it: as many as the bridge takes in one burst.
 */
@WebEngineSpi
public class EngineEventQueue(
    private val floodLimit: Int = 256,
    private val maxChars: Int = 1 shl 20,
) {
    /** A queued event; [chars] is what a page message or console line counts against [maxChars]. */
    private class Entry(val epoch: Int, var event: EngineEvent, val document: Any?, var chars: Int)

    private val entries = ArrayDeque<Entry>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** The page messages and console lines queued, and their characters. */
    private var messages = 0
    private var messageChars = 0L

    /** Collectors of [events]: more than one only while a cancelled one is still finishing. */
    private var subscribers = 0

    /** The epoch events are offered in now. */
    public var epoch: Int = 0
        private set

    /**
     * Page messages and console output dropped: under a flood, while a subscriber is attached, and everything but the
     * latest `$ready` while none is.
     */
    public var dropped: Long = 0
        private set

    /**
     * Starts a new epoch: what earlier ones left is dropped now, but for [EngineEvent.Crashed] and, unless the epoch
     * starts a [reset], focus changes. A load, reload or restore keeps the focus the view's page has; a reset
     * ([WebEngine.reset]) must pass [reset], so the last lease's focus reaches no later subscriber.
     */
    public fun advance(reset: Boolean = false): Int {
        epoch++
        val kept = entries.filter { it.event == EngineEvent.Crashed || (!reset && it.event is EngineEvent.FocusChanged) }
        entries.clear()
        entries.addAll(kept)
        messages = 0
        messageChars = 0
        return epoch
    }

    /** Queues [event] in the current epoch. [document] identifies the document a page message came from. */
    public fun offer(event: EngineEvent, document: Any? = null) {
        if (subscribers == 0) {
            if (!keepUnheard(event, document)) return
        } else {
            when {
                isMessage(event) -> if (!hold(event, document)) return
                isLatestValue(event) -> {
                    val queued = latestOfKind(event)
                    if (queued != null) queued.event = event else entries.addLast(Entry(epoch, event, null, 0))
                }
                else -> entries.addLast(Entry(epoch, event, document, 0))
            }
        }
        wake.trySend(Unit)
    }

    /** The next event to deliver, or null when none is queued. */
    public fun poll(): EngineEvent? {
        while (true) {
            val entry = entries.removeFirstOrNull() ?: return null
            if (isMessage(entry.event)) {
                messages--
                messageChars -= entry.chars
            }
            if (entry.epoch == epoch || isEngines(entry.event)) return entry.event
        }
    }

    /** What [poll] gives, as a flow, for the engine's one subscriber at a time. */
    public val events: Flow<EngineEvent> = flow {
        subscribers++
        try {
            while (true) {
                val next = poll()
                if (next != null) emit(next) else wake.receive()
            }
        } finally {
            if (--subscribers == 0) unsubscribed()
        }
    }

    /**
     * With no subscriber attached: queues [event] as the latest of its kind, in place of the one queued, or drops it (a
     * first paint, a page message but a `$ready`, console output). True if it was queued.
     */
    private fun keepUnheard(event: EngineEvent, document: Any?): Boolean {
        val kind = kindOf(event)
        if (kind < 0) {
            // What the page said reaches no one; a first paint, drawn in no place, means nothing to the next subscriber.
            if (isMessage(event)) dropped++
            return false
        }
        for (i in entries.indices.reversed()) {
            if (kindOf(entries[i].event) == kind) {
                take(i)
                break
            }
        }
        val chars = charsOf(event)
        if (isMessage(event)) {
            messages++
            messageChars += chars
        }
        entries.addLast(Entry(epoch, event, document, chars))
        return true
    }

    /**
     * Queues the page message or console line [event] for the attached subscriber, or drops it under a flood (never a
     * `$ready`). True if it was queued.
     */
    private fun hold(event: EngineEvent, document: Any?): Boolean {
        val ready = isReady(event)
        val chars = charsOf(event)
        if (ready) {
            val last = entries.lastOrNull()
            if (document != null && last != null && last.epoch == epoch && last.document === document && isReady(last.event)) {
                messageChars += chars - last.chars
                last.event = event
                last.chars = chars
                return true
            }
        }
        if (!ready && (messages + 1 > floodLimit || messageChars + chars > maxChars)) {
            dropped++
            return false
        }
        messages++
        messageChars += chars
        entries.addLast(Entry(epoch, event, document, chars))
        return true
    }

    /**
     * The last subscriber left: of each kind, only the latest stays; its first paint goes, and so does what the page
     * said but its latest `$ready`, counted in [dropped].
     */
    private fun unsubscribed() {
        val seen = BooleanArray(KINDS)
        val kept = ArrayDeque<Entry>(minOf(entries.size, KINDS))
        for (i in entries.indices.reversed()) {
            val entry = entries[i]
            val kind = kindOf(entry.event)
            if (kind < 0) {
                if (isMessage(entry.event)) dropped++
                continue
            }
            if (seen[kind]) continue
            seen[kind] = true
            kept.addFirst(entry)
        }
        entries.clear()
        entries.addAll(kept)
        messages = 0
        messageChars = 0
        for (entry in entries) {
            if (!isMessage(entry.event)) continue
            messages++
            messageChars += entry.chars
        }
    }

    /** Removes the queued event at [index], and what it counted. */
    private fun take(index: Int) {
        val entry = entries.removeAt(index)
        if (isMessage(entry.event)) {
            messages--
            messageChars -= entry.chars
        }
    }

    /** The queued event of [event]'s kind in this epoch with nothing that orders documents after it, or null. */
    private fun latestOfKind(event: EngineEvent): Entry? {
        for (i in entries.indices.reversed()) {
            val entry = entries[i]
            if (entry.epoch != epoch) return null
            if (entry.event::class == event::class) return entry
            if (isBarrier(entry.event)) return null
        }
        return null
    }

    private fun isLatestValue(event: EngineEvent): Boolean =
        event is EngineEvent.TitleChanged || event is EngineEvent.Progress || event is EngineEvent.ContentSize

    private fun isBarrier(event: EngineEvent): Boolean =
        event is EngineEvent.UrlChanged || event is EngineEvent.HistoryChanged || event == EngineEvent.FirstPaint ||
            event is EngineEvent.Error || isReady(event)

    private fun isEngines(event: EngineEvent): Boolean = event == EngineEvent.Crashed || event is EngineEvent.FocusChanged

    /** Page messages, `$ready`s among them, and console output: what the page says, as opposed to what it is. */
    private fun isMessage(event: EngineEvent): Boolean = event is EngineEvent.Console || event is EngineEvent.PageMessage

    private fun isReady(event: EngineEvent): Boolean =
        event is EngineEvent.PageMessage && event.json.length <= WebBridge.READY_MAX_CHARS &&
            event.json.startsWith(WebBridge.READY_PREFIX)

    /** What a page message or console line counts against [maxChars]. */
    private fun charsOf(event: EngineEvent): Int = when (event) {
        is EngineEvent.PageMessage -> event.json.length
        is EngineEvent.Console -> event.message.message.length + (event.message.source?.length ?: 0)
        else -> 0
    }

    /**
     * The kind of an event of which a queue with no subscriber keeps only the latest: a state event, a lost paint, the
     * crash or a `$ready`. -1 for what it keeps none of: a first paint, other page messages and console output.
     */
    private fun kindOf(event: EngineEvent): Int = when (event) {
        is EngineEvent.UrlChanged -> 0
        is EngineEvent.TitleChanged -> 1
        is EngineEvent.HistoryChanged -> 2
        is EngineEvent.Progress -> 3
        is EngineEvent.ContentSize -> 4
        is EngineEvent.Error -> 5
        is EngineEvent.FocusChanged -> 6
        EngineEvent.PaintLost -> 7
        EngineEvent.Crashed -> 8
        is EngineEvent.PageMessage -> if (isReady(event)) 9 else -1
        else -> -1
    }

    private companion object {
        /** How many kinds [kindOf] tells apart. */
        const val KINDS = 10
    }
}
