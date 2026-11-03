package dev.supermux.util

import dev.supermux.proto.ClientFrame
import dev.supermux.proto.ClientLogEntry
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.Volatile
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * Load-time trace (card #54). Every event carries a trace id — ONE per app launch ([launchId]) and
 * ONE per chat open ([startChat]) — and the ms since this process started. Events sit in a small
 * ring the on-screen Load Timings panel reads; a [dev.supermux.state.HostStore] whose broker has
 * tracing on also ships them over the socket (`client_logs`, category "perf"), where they join the
 * broker's own timings (`bun scripts/perf-timeline.ts`). Recording is a lock and a list append, so
 * it stays on in every build.
 */
object PerfTrace {
    class Event(val seq: Long, val t: Long, val name: String, val trace: String, val fields: Map<String, Any?>)

    private val origin = TimeSource.Monotonic.markNow()
    private val lock = SynchronizedObject()
    private val ring = PerfRing(4_000)
    private val seen = HashSet<String>()
    private var reqSeq = 0
    private var chatSeq = 0
    private var chat: Triple<String, String, Long>? = null

    /** One id per launch: "L" + 6 random letters/digits. */
    val launchId: String = "L" + randomId(6)

    /** Print every event as it happens (desktop: SUPERMUX_PERF=1). Summaries print regardless. */
    var echo: Boolean = false

    /** A broker asked for the trace (its snapshot said `perf: true`): requests then carry `x-mux-trace`. */
    @Volatile var shipping: Boolean = false
    private val shippingHosts = HashSet<String>()

    /** [host]'s broker turned tracing on or off; [shipping] is true while any one has it on. */
    fun setShipping(host: String, on: Boolean) = synchronized(lock) {
        if (on) shippingHosts.add(host) else shippingHosts.remove(host)
        shipping = shippingHosts.isNotEmpty()
    }

    /** The newest chat-open trace while it is fresh (a minute): its media downloads join it. */
    fun freshChatTrace(): String? = synchronized(lock) { chat?.takeIf { elapsedMs() - it.third < 60_000 }?.first }

    private val _version = MutableStateFlow(0L)
    /** Moves on every event, so an open panel knows to redraw. */
    val version: StateFlow<Long> = _version.asStateFlow()

    /** Is the Load Timings panel showing (desktop View ▸ Load Timings). */
    val panelVisible = MutableStateFlow(false)

    fun elapsedMs(): Long = origin.elapsedNow().inWholeMilliseconds
    fun elapsedMicros(): Long = origin.elapsedNow().inWholeMicroseconds

    fun event(name: String, vararg fields: Pair<String, Any?>, trace: String = launchId) {
        val t = elapsedMs()
        val map = if (fields.isEmpty()) emptyMap() else fields.toMap()
        synchronized(lock) { ring.add { seq -> Event(seq, t, name, trace, map) } }
        _version.update { it + 1 }
        if (echo) println("[perf] +${t}ms $trace $name ${map.entries.joinToString(" ") { "${it.key}=${it.value}" }}")
    }

    /** [event] that also prints a `[startup] +<ms> name k=v` line, as the launch milestones always have. */
    fun milestone(name: String, vararg fields: Pair<String, Any?>, trace: String = launchId) {
        println("[startup] +${elapsedMs()}ms $name ${fields.joinToString(" ") { "${it.first}=${it.second}" }}".trimEnd())
        event(name, *fields, trace = trace)
    }

    /** [event], but only the first time [name] happens under [trace]. */
    fun once(name: String, vararg fields: Pair<String, Any?>, trace: String = launchId) {
        val first = synchronized(lock) { seen.add("$trace|$name") }
        if (first) event(name, *fields, trace = trace)
    }

    /** [milestone], but only the first time [name] happens under [trace]. */
    fun firstMilestone(name: String, vararg fields: Pair<String, Any?>, trace: String = launchId) {
        val first = synchronized(lock) { seen.add("$trace|$name") }
        if (first) milestone(name, *fields, trace = trace)
    }

    fun cursor(): Long = synchronized(lock) { ring.lastSeq() }

    fun since(cursor: Long, max: Int = Int.MAX_VALUE): List<Event> = synchronized(lock) { ring.since(cursor, max) }

    /** Every event of [trace], oldest first. */
    fun events(trace: String): List<Event> = synchronized(lock) { ring.since(0).filter { it.trace == trace } }

    fun nextReq(): String = synchronized(lock) { "r${++reqSeq}" }

    /** A new chat-open trace for [sessionId]; the history fetch it causes carries it. */
    fun startChat(sessionId: String): String = synchronized(lock) {
        val trace = "C${launchId.drop(1)}.${++chatSeq}"
        chat = Triple(trace, sessionId, elapsedMs())
        trace
    }

    /** The open chat's trace while it is [sessionId]'s and fresh (a minute), else null. */
    fun chatTrace(sessionId: String): String? = synchronized(lock) {
        chat?.takeIf { it.second == sessionId && elapsedMs() - it.third < 60_000 }?.first
    }

    /** The newest chat-open traces, newest last. */
    fun chatTraces(max: Int = 5): List<String> = synchronized(lock) {
        ring.since(0).filter { it.name == "chat.open" }.map { it.trace }.distinct().takeLast(max)
    }

    // ── Per-type counters: since launch (the panel) and since the last [takeStats] (shipping) ──

    private class Agg { var n = 0; var chars = 0L; var micros = 0L; var maxMicros = 0L }
    private val framesTotal = LinkedHashMap<String, Agg>()
    private val framesWindow = LinkedHashMap<String, Agg>()
    private val reduceTotal = LinkedHashMap<String, Agg>()
    private val reduceWindow = LinkedHashMap<String, Agg>()
    private val recomposeTotal = LinkedHashMap<String, Int>()
    private val recomposeWindow = LinkedHashMap<String, Int>()

    private fun MutableMap<String, Agg>.add(type: String, chars: Int, micros: Long) {
        val a = getOrPut(type) { Agg() }
        a.n++; a.chars += chars; a.micros += micros
        if (micros > a.maxMicros) a.maxMicros = micros
    }

    /** One WebSocket frame of [type]: its size and the time to decode it. */
    fun countFrame(type: String, chars: Int, decodeMicros: Long) = synchronized(lock) {
        framesTotal.add(type, chars, decodeMicros)
        framesWindow.add(type, chars, decodeMicros)
    }

    /** The time the reducer spent folding one frame of [type] into the state. */
    fun countReduce(type: String, micros: Long) = synchronized(lock) {
        reduceTotal.add(type, 0, micros)
        reduceWindow.add(type, 0, micros)
    }

    /** One recomposition of a watched part of the UI ("shell", "sidebar", "chat"). */
    fun countRecompose(scope: String) = synchronized(lock) {
        recomposeTotal[scope] = (recomposeTotal[scope] ?: 0) + 1
        recomposeWindow[scope] = (recomposeWindow[scope] ?: 0) + 1
    }

    private fun ms1(micros: Long): Double = (micros / 100.0).roundToLong() / 10.0

    private fun frameRows(m: Map<String, Agg>) = m.mapValues { (_, a) -> mapOf("n" to a.n, "chars" to a.chars, "decodeMs" to ms1(a.micros), "maxDecodeMs" to ms1(a.maxMicros)) }
    private fun reduceRows(m: Map<String, Agg>) = m.mapValues { (_, a) -> mapOf("n" to a.n, "ms" to ms1(a.micros), "maxMs" to ms1(a.maxMicros)) }

    /** What happened since the last call, as one event's fields; null when nothing did. */
    fun takeStats(): Map<String, Any?>? = synchronized(lock) {
        if (framesWindow.isEmpty() && reduceWindow.isEmpty() && recomposeWindow.isEmpty()) return@synchronized null
        val out = mapOf("frames" to frameRows(framesWindow), "reduce" to reduceRows(reduceWindow), "recompose" to recomposeWindow.toMap())
        framesWindow.clear(); reduceWindow.clear(); recomposeWindow.clear()
        out
    }

    /** Totals since launch, for the panel. */
    class Totals(val frames: Map<String, Map<String, Any?>>, val reduce: Map<String, Map<String, Any?>>, val recompose: Map<String, Int>)

    fun totals(): Totals = synchronized(lock) { Totals(frameRows(framesTotal), reduceRows(reduceTotal), recomposeTotal.toMap()) }

    private fun randomId(n: Int): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return buildString { repeat(n) { append(alphabet[Random.nextInt(alphabet.length)]) } }
    }
}

/** A bounded, sequence-numbered event buffer (callers hold their own lock). */
class PerfRing(private val capacity: Int) {
    private val items = ArrayDeque<PerfTrace.Event>()
    private var seq = 0L

    fun add(make: (seq: Long) -> PerfTrace.Event) {
        items.addLast(make(++seq))
        while (items.size > capacity) items.removeFirst()
    }

    /** Events after [cursor], oldest first, at most [max]. */
    fun since(cursor: Long, max: Int = Int.MAX_VALUE): List<PerfTrace.Event> {
        val out = ArrayList<PerfTrace.Event>()
        for (e in items) {
            if (e.seq <= cursor) continue
            out.add(e)
            if (out.size >= max) break
        }
        return out
    }

    fun lastSeq(): Long = seq
}

/** [events] as the broker's `client_logs` frame (category "perf"; `ts` = the app's ms-since-launch). */
fun perfClientLogs(events: List<PerfTrace.Event>, meta: Map<String, Any?>): ClientFrame.ClientLogs =
    ClientFrame.ClientLogs(
        entries = events.map { e ->
            ClientLogEntry(category = "perf", event = e.name, ts = e.t, data = perfJson(mapOf("trace" to e.trace) + e.fields) as JsonObject)
        },
        meta = perfJson(meta) as JsonObject,
    )

private fun perfJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is String -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.filter { it.value != null }.associate { it.key.toString() to perfJson(it.value) })
    is Iterable<*> -> JsonArray(v.map { perfJson(it) })
    else -> JsonPrimitive(v.toString())
}

/** The broker's own time for a response, from its `server-timing: app;dur=<ms>` header. */
fun serverTimingMs(header: String?): Double? {
    if (header == null) return null
    for (part in header.split(',')) {
        val metrics = part.trim().split(';').map { it.trim() }
        if (metrics.firstOrNull() != "app") continue
        return metrics.firstOrNull { it.startsWith("dur=") }?.removePrefix("dur=")?.toDoubleOrNull()
    }
    return null
}
