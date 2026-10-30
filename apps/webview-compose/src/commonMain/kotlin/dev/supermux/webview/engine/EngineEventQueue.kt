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
 *   subscriber needs, and no more:
 *   - Of each kind of state event (commit, title, history, progress, size, error and focus) only the latest, where it
 *     happened among the rest: the next subscriber hears what remains in the order it last happened.
 *     [WebEngine.replayState] tells it what the page is now anyway.
 *   - No [EngineEvent.FirstPaint]: it means the page was drawn in its place, and a page nobody subscribes to has none
 *     (its view let go of it). The next subscriber's first paint is the one [WebEngine.replayState] gives, once the page
 *     is drawn in its new place.
 *   - Page messages (a `$ready` among them) and console output, up to [parkedLimit] of them and [maxChars] characters.
 *     One more overflows: they are all dropped, and counted, and [onOverflow] is told. Engines pass their crash path,
 *     so a page that floods while parked is discarded, never adopted with what it said cut short.
 *   - When the last subscriber leaves, what it left unheard is cut down the same way, its first paint dropped too: that
 *     was the paint of the place its view gave up.
 *
 * @param floodLimit how many page messages and console lines may wait for a subscriber that fell behind.
 * @param parkedLimit how many may wait while no subscriber is attached.
 * @param maxChars how many characters of them may wait, either way: as many as the bridge takes in one burst.
 * @param onOverflow told when page messages and console output overflow while no subscriber is attached, inside the
 *   [offer] that overflowed, or as the last subscriber leaves. Engines pass their crash path ([WebEngine.isUsable] false
 *   from then on). Without one, an overflow only drops them.
 */
internal class EngineEventQueue(
    private val floodLimit: Int = 256,
    private val parkedLimit: Int = 1_024,
    private val maxChars: Int = 1 shl 20,
    private val onOverflow: (() -> Unit)? = null,
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
    var epoch: Int = 0
        private set

    /** Page messages and console output dropped: under a flood, or in an overflow. */
    var dropped: Long = 0
        private set

    /**
     * Starts a new epoch: what earlier ones left is dropped now, but for [EngineEvent.Crashed] and, unless the epoch
     * starts a [reset], focus changes. A load, reload or restore keeps the focus the view's page has; a reset
     * ([WebEngine.reset]) must pass [reset], so the last lease's focus reaches no later subscriber.
     */
    fun advance(reset: Boolean = false): Int {
        epoch++
        val kept = entries.filter { it.event == EngineEvent.Crashed || (!reset && it.event is EngineEvent.FocusChanged) }
        entries.clear()
        entries.addAll(kept)
        messages = 0
        messageChars = 0
        return epoch
    }

    /** Queues [event] in the current epoch. [document] identifies the document a page message came from. */
    fun offer(event: EngineEvent, document: Any? = null) {
        // Drawn in no place: nobody subscribes.
        if (subscribers == 0 && event == EngineEvent.FirstPaint) return
        val kind = kindOf(event)
        when {
            isMessage(event) -> if (!hold(event, document)) return
            subscribers == 0 && kind >= 0 -> {
                removeQueued(kind)
                entries.addLast(Entry(epoch, event, null, 0))
            }
            isLatestValue(event) -> {
                val queued = latestOfKind(event)
                if (queued != null) queued.event = event else entries.addLast(Entry(epoch, event, null, 0))
            }
            else -> entries.addLast(Entry(epoch, event, document, 0))
        }
        wake.trySend(Unit)
    }

    /** The next event to deliver, or null when none is queued. */
    fun poll(): EngineEvent? {
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
    val events: Flow<EngineEvent> = flow {
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
     * Queues the page message or console line [event], or drops it: under a flood while a subscriber is attached (never
     * a `$ready`), or in an overflow while none is. True if it was queued.
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
        val parked = subscribers == 0
        if (messages + 1 > (if (parked) parkedLimit else floodLimit) || messageChars + chars > maxChars) {
            if (parked) {
                overflow(dropping = 1)
                return false
            }
            if (!ready) {
                dropped++
                return false
            }
        }
        messages++
        messageChars += chars
        entries.addLast(Entry(epoch, event, document, chars))
        return true
    }

    /**
     * The last subscriber left: of each kind of state event, only the latest stays, its first paint goes, and messages
     * past what a parked page may hold overflow.
     */
    private fun unsubscribed() {
        val seen = BooleanArray(KINDS)
        val kept = ArrayDeque<Entry>(entries.size)
        for (i in entries.indices.reversed()) {
            val entry = entries[i]
            if (entry.event == EngineEvent.FirstPaint) continue
            val kind = kindOf(entry.event)
            if (kind >= 0) {
                if (seen[kind]) continue
                seen[kind] = true
            }
            kept.addFirst(entry)
        }
        entries.clear()
        entries.addAll(kept)
        if (messages > parkedLimit || messageChars > maxChars) overflow(dropping = 0)
    }

    /**
     * Page messages and console output overflowed with no subscriber attached: the queued ones and [dropping] more are
     * dropped, and the engine is told, last, so what it offers then (its crash) is queued as usual.
     */
    private fun overflow(dropping: Int) {
        val before = entries.size
        entries.removeAll { isMessage(it.event) }
        dropped += before - entries.size + dropping
        messages = 0
        messageChars = 0
        onOverflow?.invoke()
    }

    /** Removes the queued state event of [kind]: with no subscriber attached, there is at most one. */
    private fun removeQueued(kind: Int) {
        for (i in entries.indices.reversed()) {
            if (kindOf(entries[i].event) == kind) {
                entries.removeAt(i)
                return
            }
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

    /** The kind of a state event, of which a parked page keeps only the latest; -1 for other events. */
    private fun kindOf(event: EngineEvent): Int = when (event) {
        is EngineEvent.UrlChanged -> 0
        is EngineEvent.TitleChanged -> 1
        is EngineEvent.HistoryChanged -> 2
        is EngineEvent.Progress -> 3
        is EngineEvent.ContentSize -> 4
        is EngineEvent.Error -> 5
        is EngineEvent.FocusChanged -> 6
        else -> -1
    }

    private companion object {
        /** How many kinds [kindOf] tells apart. */
        const val KINDS = 7
    }
}
