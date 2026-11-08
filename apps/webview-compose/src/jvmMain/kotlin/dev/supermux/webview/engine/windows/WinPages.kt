package dev.supermux.webview.engine.windows

import dev.supermux.webview.bridge.WebBridge
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor

/**
 * The live pages, by token, and the hand-over of every upcall to the EDT (the macOS engine's `MacPages`).
 * - The glue's threads only decode and hand over to [executor]: `Dispatchers.Main`, which runs them in the order posted.
 * - Page messages on their way to the EDT are bounded ([MESSAGE_BOUND] messages, [MESSAGE_CHARS] characters): past it
 *   they are dropped, never a `$ready` and never anything else (L19).
 * - [decide] and [keys] are read on the glue's UI thread: concurrent maps.
 * - Everything else runs on the EDT.
 */
internal object WinPages {
    const val MESSAGE_BOUND: Int = 1_024
    const val MESSAGE_CHARS: Long = 1L shl 20

    @Volatile var executor: Executor = Dispatchers.Main.asExecutor()

    /** The process's own events: memory pressure, the browser process gone, the environment opened. */
    @Volatile var onMemory: () -> Unit = {}
    @Volatile var onBrowserExited: () -> Unit = {}

    private val handlers = HashMap<Long, (WinEvent) -> Unit>()
    private val deciders = ConcurrentHashMap<Long, (Int, Int, String?) -> Int>()
    private val keyOffers = ConcurrentHashMap<Long, (Int, Int, Int) -> Boolean>()
    private val creating = HashMap<Long, CompletableDeferred<WinEvent.Created>>()

    /** Concurrent: the runtime asks for the environment off the EDT (`Wv2Runtime.start`), its answer comes on the EDT. */
    private val environments = ConcurrentHashMap<Long, CompletableDeferred<WinEvent.Environment>>()
    private val nextToken = AtomicLong(1)
    private var pending = 0
    private var pendingChars = 0L
    private val lock = Any()

    init {
        attach()
    }

    private fun attach() {
        WinGlue.sink = WinGlue.Sink { page, type, epoch, id, flags, a, b, c, data ->
            val event = WinEvent.decode(type, epoch, id, flags, a, b, c, data) ?: return@Sink
            if (!admit(event)) return@Sink
            executor.execute {
                done(event)
                dispatch(page, event)
            }
        }
        WinGlue.decider = { page, epoch, flags, url -> deciders[page]?.invoke(epoch, flags, url) ?: WinGlue.REFUSE }
        WinGlue.keyOffer = { page, vk, scan, flags -> keyOffers[page]?.invoke(vk, scan, flags) ?: false }
    }

    private fun dispatch(page: Long, event: WinEvent) {
        when (event) {
            is WinEvent.Created -> creating.remove(page)?.complete(event)
            is WinEvent.Environment -> environments.remove(event.request)?.complete(event)
            WinEvent.Memory -> onMemory()
            WinEvent.BrowserExited -> onBrowserExited()
            is WinEvent.Log -> System.err.println("webview-windows: ${event.line}")
            else -> handlers[page]?.invoke(event)
        }
    }

    /** A token for a page about to be made, and the wait for its `CREATED`. EDT. */
    fun newPage(): Pair<Long, CompletableDeferred<WinEvent.Created>> {
        val token = nextToken.getAndIncrement()
        return token to CompletableDeferred<WinEvent.Created>().also { creating[token] = it }
    }

    /** A request id for the environment's opening, and the wait for its answer. Any thread. */
    fun newEnvironmentRequest(): Pair<Long, CompletableDeferred<WinEvent.Environment>> {
        val request = nextToken.getAndIncrement()
        return request to CompletableDeferred<WinEvent.Environment>().also { environments[request] = it }
    }

    fun listen(page: Long, handler: (WinEvent) -> Unit) {
        handlers[page] = handler
    }

    fun decide(page: Long, decider: (epoch: Int, flags: Int, url: String?) -> Int) {
        deciders[page] = decider
    }

    fun keys(page: Long, offer: (vk: Int, scan: Int, flags: Int) -> Boolean) {
        keyOffers[page] = offer
    }

    fun forget(page: Long) {
        handlers.remove(page)
        deciders.remove(page)
        keyOffers.remove(page)
        creating.remove(page)
    }

    /** Whether [event] goes to the EDT; the glue's threads. */
    private fun admit(event: WinEvent): Boolean {
        val size = floodable(event) ?: return true
        synchronized(lock) {
            if (pending + 1 > MESSAGE_BOUND || pendingChars + size > MESSAGE_CHARS) return false
            pending++
            pendingChars += size
        }
        return true
    }

    private fun done(event: WinEvent) {
        val size = floodable(event) ?: return
        synchronized(lock) {
            pending--
            pendingChars -= size
        }
    }

    /** A page message's size, if a flood may drop it: never a `$ready`, nor the script's own signals. */
    private fun floodable(event: WinEvent): Int? {
        val body = (event as? WinEvent.Message)?.body ?: return null
        val json = body.substringAfter(" msg ", missingDelimiterValue = "").takeIf { it.isNotEmpty() } ?: return null
        return if (json.length <= WebBridge.READY_MAX_CHARS && json.startsWith(WebBridge.READY_PREFIX)) null else json.length
    }

    /** For tests: everything forgotten, upcalls run on [executor]. */
    internal fun resetForTests(executor: Executor = Dispatchers.Main.asExecutor()) {
        this.executor = executor
        handlers.clear()
        deciders.clear()
        keyOffers.clear()
        creating.clear()
        environments.clear()
        synchronized(lock) {
            pending = 0
            pendingChars = 0
        }
        onMemory = {}
        onBrowserExited = {}
        attach()
    }
}
