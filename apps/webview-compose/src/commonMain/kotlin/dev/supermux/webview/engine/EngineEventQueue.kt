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
 * - Never dropped: [EngineEvent.UrlChanged], [EngineEvent.HistoryChanged], [EngineEvent.FirstPaint],
 *   [EngineEvent.Error], [EngineEvent.Crashed], [EngineEvent.FocusChanged], and a page's `$ready`, recognized the way the
 *   bridge recognizes it: at most [WebBridge.READY_MAX_CHARS] characters, starting with [WebBridge.READY_PREFIX].
 * - Coalesced to the latest, never dropped entirely: [EngineEvent.TitleChanged], [EngineEvent.Progress] and
 *   [EngineEvent.ContentSize] replace a queued event of their kind unless a commit, a paint, an error or a `$ready` is
 *   queued after it, so the order of documents holds; and a `$ready` right behind a `$ready` of the same document
 *   (the `document` an engine passes, compared by identity) replaces it. Repeats can't grow the queue.
 * - Other page messages and console output are dropped once [floodLimit] of them are queued; [dropped] counts them.
 */
internal class EngineEventQueue(private val floodLimit: Int = 256) {
    private class Entry(val epoch: Int, var event: EngineEvent, val document: Any?)

    private val entries = ArrayDeque<Entry>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var floodable = 0

    /** The epoch events are offered in now. */
    var epoch: Int = 0
        private set

    /** Page messages and console output dropped under a flood. */
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
        floodable = 0
        return epoch
    }

    /** Queues [event] in the current epoch. [document] identifies the document a page message came from. */
    fun offer(event: EngineEvent, document: Any? = null) {
        when {
            isLatestValue(event) -> {
                val queued = latestOfKind(event)
                if (queued != null) queued.event = event else entries.addLast(Entry(epoch, event, null))
            }
            isReady(event) -> {
                val last = entries.lastOrNull()
                if (document != null && last != null && last.epoch == epoch && last.document === document && isReady(last.event)) {
                    last.event = event
                } else {
                    entries.addLast(Entry(epoch, event, document))
                }
            }
            isFloodable(event) -> {
                if (floodable >= floodLimit) {
                    dropped++
                    return
                }
                floodable++
                entries.addLast(Entry(epoch, event, document))
            }
            else -> entries.addLast(Entry(epoch, event, document))
        }
        wake.trySend(Unit)
    }

    /** The next event to deliver, or null when none is queued. */
    fun poll(): EngineEvent? {
        while (true) {
            val entry = entries.removeFirstOrNull() ?: return null
            if (isFloodable(entry.event)) floodable--
            if (entry.epoch == epoch || isEngines(entry.event)) return entry.event
        }
    }

    /** What [poll] gives, as a flow, for the engine's one subscriber at a time. */
    val events: Flow<EngineEvent> = flow {
        while (true) {
            val next = poll()
            if (next != null) emit(next) else wake.receive()
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

    private fun isFloodable(event: EngineEvent): Boolean =
        event is EngineEvent.Console || (event is EngineEvent.PageMessage && !isReady(event))

    private fun isReady(event: EngineEvent): Boolean =
        event is EngineEvent.PageMessage && event.json.length <= WebBridge.READY_MAX_CHARS &&
            event.json.startsWith(WebBridge.READY_PREFIX)
}
