package dev.supermux.webview.wpetest

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A native event, with the System.nanoTime it reached the JVM at. */
data class LabEvent(val atNs: Long, val kind: Int, val a: Long, val b: Long, val s1: String?, val s2: String?)

/** One delivered frame, from the native ring: sequence, marker pixel, arrival, damaged pixels, changed pixels outside the damage. */
data class FrameRecord(val seq: Long, val marker: Long, val arrivalNs: Long, val dirtyPx: Long, val outsidePx: Long)

/**
 * The lab's one WebKit: loads libwvlab.so, starts the WebKit thread, routes events to their views, and serves the two
 * schemes the rules check (the engine's bundle scheme and the reset floor) from the jar's resources.
 */
class LabHost private constructor(val options: LabOptions) {
    /** Where the lab answers WebKit (decisions, scheme requests): off the WebKit thread, one thing at a time. */
    val lab: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val views = ConcurrentHashMap<Long, LabView>()
    var version: String = "?"
        private set
    /** System.nanoTime just before the library was loaded, and how long loading and starting WebKit took. */
    var initStartNs: Long = 0
        private set
    var initMs: Double = 0.0
        private set

    fun create(width: Int, height: Int, scale: Double, profile: Int = WpeNative.PROFILE_EPHEMERAL): LabView {
        val id = WpeNative.createView(profile, null, true, width, height, scale)
        check(id > 0) { "createView failed" }
        return LabView(this, id).also { views[id] = it }
    }

    internal fun forget(id: Long) {
        views.remove(id)
    }

    private fun event(view: Long, kind: Int, a: Long, b: Long, s1: String?, s2: String?) {
        val at = System.nanoTime()
        if (kind == WpeNative.SCHEME) {
            lab.launch { serve(a, s1 ?: "") }
            return
        }
        views[view]?.onNative(LabEvent(at, kind, a, b, s1, s2))
    }

    private fun serve(request: Long, uri: String) {
        val path = when {
            uri.startsWith("webview-floor:") -> "wpetest/floor.html"
            uri.startsWith(BUNDLE) -> "wpetest/bundle/" + uri.removePrefix(BUNDLE).substringBefore('?').substringBefore('#')
            else -> null
        }
        val bytes = path?.let { LabHost::class.java.classLoader.getResourceAsStream(it)?.use { s -> s.readBytes() } }
        if (path == null || bytes == null) WpeNative.finishScheme(request, 404, null, null)
        else WpeNative.finishScheme(request, 200, mimeOf(path), bytes)
    }

    companion object {
        const val BUNDLE = "webview-bundle://demo.invalid/"

        fun start(options: LabOptions): LabHost {
            val host = LabHost(options)
            host.initStartNs = System.nanoTime()
            System.load(options.nativeLib)
            WpeNative.sink = WpeNative.Sink { view, kind, a, b, s1, s2 -> host.event(view, kind, a, b, s1, s2) }
            val home = System.getProperty("user.home")
            check(WpeNative.init(options.initFlags, "$home/wpelab-data", "$home/wpelab-cache", null) == 0) { "WebKit didn't start" }
            host.initMs = (System.nanoTime() - host.initStartNs) / 1e6
            host.version = WpeNative.version()
            WpeNative.registerScheme("webview-bundle", true)
            WpeNative.registerScheme("webview-floor", true)
            return host
        }

        fun mimeOf(path: String): String = when (path.substringAfterLast('.')) {
            "html" -> "text/html"
            "js" -> "text/javascript"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }

        fun resource(path: String): String =
            checkNotNull(LabHost::class.java.classLoader.getResource(path)) { "no resource $path" }.readText()
    }
}

/** One web view in the lab. Events arrive on the WebKit thread and are only queued here; scenarios read them. */
class LabView internal constructor(val host: LabHost, val id: Long) {
    val log = ConcurrentLinkedQueue<LabEvent>()
    val frameSignal = Channel<Unit>(Channel.CONFLATED)
    val pageMessages = Channel<String>(Channel.UNLIMITED)
    val records = ConcurrentLinkedQueue<FrameRecord>()
    internal val relay: WebKitRelay = WebKitRelay.random()

    /** Answers WebKit's policy decisions (navigations and responses); the lab allows everything unless a scenario says otherwise. */
    @Volatile var policy: (LabEvent) -> Int = { WpeNative.USE }

    /** The relay's token for the current document. */
    @Volatile var document: String? = null
        private set
    @Volatile var paints = 0
        private set

    private val pending = ConcurrentHashMap<Long, CompletableDeferred<String?>>()
    private val requests = AtomicLong(1)

    internal fun onNative(e: LabEvent) {
        log += e
        when (e.kind) {
            WpeNative.FRAME -> frameSignal.trySend(Unit)
            WpeNative.EVAL -> pending.remove(e.a)?.complete(if (e.b == 1L) e.s1 ?: "null" else null)
            WpeNative.POLICY, WpeNative.RESPONSE -> host.lab.launch { WpeNative.decide(id, e.a, policy(e)) }
            WpeNative.RELAY -> e.s1?.let(::onRelay)
        }
    }

    private fun onRelay(body: String) {
        val message = RelayMessage.parse(body) ?: return
        if (message is RelayMessage.Start) {
            document = message.document
            return
        }
        if (message.document != document) return
        when (message) {
            is RelayMessage.Paint -> paints++
            is RelayMessage.Page -> pageMessages.trySend(message.json)
            else -> Unit
        }
    }

    /** The relay (in [WORLD]) for documents of [origins], the lab shim in the page world, page scripts on, a white background. */
    fun configure(origins: Set<String> = setOf(LAB_ORIGIN)) =
        WpeNative.configure(id, relay.relay(origins), relay.labShim(), WORLD, relay.handlerName, WpeNative.CONF_PAGE_SCRIPTS, 0xFFFFFFFF.toInt(), null)

    fun loadLab(base: String = "$LAB_ORIGIN/") = WpeNative.load(id, WpeNative.LOAD_HTML, LabHost.resource("wpetest/lab.html"), base, null)
    fun loadHtml(html: String, base: String) = WpeNative.load(id, WpeNative.LOAD_HTML, html, base, null)
    fun loadUri(uri: String) = WpeNative.load(id, WpeNative.LOAD_URI, uri, null, null)

    /** [script]'s result as JSON ("null" for undefined), in [world] (the page's when null); null if it failed or timed out. */
    suspend fun eval(script: String, world: String? = null, timeoutMs: Long = 5_000): String? =
        request(timeoutMs) { WpeNative.evaluate(id, it.toInt(), script, world) }

    /** Runs an async function [body] (a promise it returns is awaited) with string [args], in [world]. */
    suspend fun call(body: String, world: String? = null, args: Map<String, String> = emptyMap(), timeoutMs: Long = 5_000): String? =
        request(timeoutMs) { WpeNative.callAsync(id, it.toInt(), body, world, args.flatMap { (k, v) -> listOf(k, v) }.toTypedArray()) }

    private suspend fun request(timeoutMs: Long, send: (Long) -> Unit): String? {
        val request = requests.getAndIncrement()
        val done = CompletableDeferred<String?>()
        pending[request] = done
        send(request)
        return withTimeoutOrNull(timeoutMs) { done.await() }.also { pending.remove(request) }
    }

    /** Host → page as a WebKit engine delivers it: the delivery body, in the script world, checks the document's origin. */
    fun deliver(json: String, target: String) =
        WpeNative.callAsync(id, 0, relay.deliveryBody, WORLD, arrayOf("json", json, "target", target, "name", relay.inEvent))

    fun events(kind: Int, sinceNs: Long = 0): List<LabEvent> = log.filter { it.kind == kind && it.atNs >= sinceNs }

    suspend fun awaitEvent(kind: Int, sinceNs: Long, timeoutMs: Long, match: (LabEvent) -> Boolean = { true }): LabEvent? =
        withTimeoutOrNull(timeoutMs) {
            var found: LabEvent? = null
            while (found == null) {
                found = log.firstOrNull { it.kind == kind && it.atNs >= sinceNs && match(it) }
                if (found == null) delay(5)
            }
            found
        }

    fun loadFinished(url: String, sinceNs: Long = 0): Boolean =
        events(WpeNative.LOAD, sinceNs).any { it.a == WpeNative.LOAD_FINISHED && it.s1 == url }

    /** Moves the native frame records into [records]; returns the new ones. */
    fun drain(): List<FrameRecord> {
        val out = LongArray(5 * 4096)
        val got = ArrayList<FrameRecord>()
        while (true) {
            val n = WpeNative.drainFrames(id, out)
            for (i in 0 until n) {
                val o = i * 5
                got += FrameRecord(out[o], out[o + 1], out[o + 2], out[o + 3], out[o + 4])
            }
            if (n < 4096) break
        }
        records.addAll(got)
        return got
    }

    /** The first frame delivered since [sinceNs] that shows the page's marker: content, not a bare background. */
    suspend fun firstContentFrame(sinceNs: Long, timeoutMs: Long): FrameRecord? = withTimeoutOrNull(timeoutMs) {
        var found: FrameRecord? = null
        while (found == null) {
            drain()
            found = records.firstOrNull { it.arrivalNs >= sinceNs && Markers.counter(it.marker) != null }
            if (found == null) delay(2)
        }
        found
    }

    fun destroy() {
        host.forget(id)
        WpeNative.destroyView(id)
    }

    companion object {
        const val WORLD = "webview"
        const val LAB_ORIGIN = "https://lab.invalid"
    }
}
