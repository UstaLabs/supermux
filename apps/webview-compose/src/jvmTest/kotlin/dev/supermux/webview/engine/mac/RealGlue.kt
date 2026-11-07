package dev.supermux.webview.engine.mac

import com.sun.net.httpserver.HttpServer
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.PageSetup
import dev.supermux.webview.engine.webkit.WebKitScripts
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import java.net.InetSocketAddress
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The real glue and WebKit in a test process, on a Mac. AppKit's main thread is the one the java launcher parks in a
 * run loop, so the glue's work runs there; WebKit loads pages without a window or a GUI session. The engine's own
 * thread is [thread], which the glue's upcalls are handed to ([MacPages.executor]) as they are to the EDT in an app.
 */
internal class RealGlue : AutoCloseable {
    private val thread: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "real-glue-main").apply { isDaemon = true } }
    private val previous: Executor = MacPages.executor
    val dispatcher: CoroutineDispatcher = thread.asCoroutineDispatcher()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)

    init {
        assumeTrue("the glue runs on a Mac only", MacPlatform.isMac)
        check(MacNativeLibrary.ensureLoaded() == null) { "the packaged glue doesn't load" }
        MacPages.executor = thread
    }

    /** Runs [block] on the engine's thread, within [millis]. */
    fun <T> run(millis: Long = 60_000, block: suspend CoroutineScope.() -> T): T =
        runBlocking(dispatcher) { withTimeout(millis) { block() } }

    /** A warm engine, as the factory makes one, its upcalls on [thread]. On the engine's thread. */
    suspend fun engine(profile: WebProfile = WebProfile.Ephemeral): MacWebEngine = engineAndGlue(profile).first

    /** [engine], and its glue, for a test that asks the glue itself. */
    suspend fun engineAndGlue(profile: WebProfile = WebProfile.Ephemeral): Pair<MacWebEngine, NativePageGlue> {
        val config = EngineConfig(profile)
        val scripts = WebKitScripts.random()
        val glue = checkNotNull(NativePageGlue.create(config, scripts)) { "WebKit made no page" }
        val engine = MacWebEngine(
            config = config,
            glue = glue,
            scripts = scripts,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            windowHandle = { 0L },
            onReleased = { MacPages.unregister(glue.token) },
        )
        MacPages.register(glue.token, engine::onGlueEvent)
        check(engine.warmUp()) { "the engine's floor didn't load" }
        return engine to glue
    }

    override fun close() {
        MacPages.executor = previous
        scope.cancel()
        thread.shutdown()
    }
}

/** [engine]'s events as they come, for [RealGlue] tests: subscribed at once, as the runtime subscribes before a load. */
internal class Events(engine: MacWebEngine, scope: CoroutineScope) {
    val seen = mutableListOf<EngineEvent>()
    private val incoming = Channel<EngineEvent>(Channel.UNLIMITED)

    init {
        scope.launch { engine.events.collect { seen += it; incoming.send(it) } }
    }

    /** The next event that [matches] within [millis], skipping the others; fails with what came. */
    suspend fun next(what: String, millis: Long = 15_000, matches: (EngineEvent) -> Boolean): EngineEvent {
        val found = withTimeoutOrNull(millis) {
            var event = incoming.receive()
            while (!matches(event)) event = incoming.receive()
            event
        }
        return found ?: throw AssertionError("no $what within $millis ms; saw $seen")
    }
}

/** A client that blocks every navigation it is asked about, and records them. */
internal class BlockingClient : dev.supermux.webview.engine.EngineClient {
    val navigations = mutableListOf<dev.supermux.webview.NavigationRequest>()

    override fun onNavigation(request: dev.supermux.webview.NavigationRequest): dev.supermux.webview.NavigationDecision {
        navigations += request
        return dev.supermux.webview.NavigationDecision.Block
    }

    override suspend fun onDialog(dialog: dev.supermux.webview.WebDialog): dev.supermux.webview.DialogResult =
        dev.supermux.webview.DialogResult.Dismissed

    override suspend fun onFileChooser(request: dev.supermux.webview.FileChooserRequest): List<String>? = null

    override suspend fun onPermission(request: dev.supermux.webview.PermissionRequest): Boolean = false

    override suspend fun onDownload(request: dev.supermux.webview.DownloadRequest): Boolean = false

    override fun onNewWindow(request: dev.supermux.webview.NewWindowRequest): dev.supermux.webview.NewWindowDecision =
        dev.supermux.webview.NewWindowDecision.Block
}

/** The setup a lease gives a page with JavaScript, as the runtime would. */
internal val realSetup = PageSetup(
    javaScriptEnabled = true,
    inspectable = false,
    dark = false,
    transparentBackground = false,
    userAgentSuffix = null,
    bridgeOrigins = emptySet(),
)

/** A loopback HTTP server: pages and redirects, which can change while it runs. */
internal class TestServer : AutoCloseable {
    private val routes = java.util.concurrent.ConcurrentHashMap<String, (String) -> Triple<Int, Map<String, String>, ByteArray>>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val route = routes[exchange.requestURI.path]
            val (status, headers, body) = route?.invoke(origin) ?: Triple(404, emptyMap(), ByteArray(0))
            for ((name, value) in headers) exchange.responseHeaders.add(name, value)
            exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        start()
    }
    val port: Int get() = server.address.port
    val origin: String get() = "http://127.0.0.1:$port"

    fun page(path: String, title: String) {
        routes[path] = { _ ->
            Triple(200, mapOf("Content-Type" to "text/html; charset=utf-8", "Cache-Control" to "no-store"), "<!doctype html><title>$title</title><p>$title".encodeToByteArray())
        }
    }

    /** [path] answers [status] to [location]: a path on this server, or a whole URL. */
    fun redirect(path: String, status: Int, location: String) {
        routes[path] = { origin ->
            Triple(status, mapOf("Location" to if (location.startsWith("/")) origin + location else location), ByteArray(0))
        }
    }

    override fun close() = server.stop(0)
}
