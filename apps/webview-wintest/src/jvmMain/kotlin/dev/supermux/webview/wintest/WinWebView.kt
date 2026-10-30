package dev.supermux.webview.wintest

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.awt.Cursor
import java.awt.EventQueue
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** A native event with the System.nanoTime it reached the JVM at. */
data class LoggedEvent(val atNs: Long, val event: NativeEvent)

/**
 * The test's one WebView2: starts it, routes native events into Compose state and into the log the scenarios read, and
 * calls the page only through the receiver installed at document creation (receiver.js).
 */
class WinWebView(private val options: TestOptions) {
    val buffer = FrameBuffer()
    /** Signalled (conflated) by the native capture thread whenever a frame was copied. */
    val frameSignal = Channel<Unit>(Channel.CONFLATED)
    val log = ConcurrentLinkedQueue<LoggedEvent>()
    var readyInfo by mutableStateOf<String?>(null)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    var webFocused by mutableStateOf(false)
        private set
    var cursor: Cursor by mutableStateOf(Cursor.getDefaultCursor())
        private set
    /** Completes with the page's clock once the page reports ready. */
    val pageReady = CompletableDeferred<Double>()
    @Volatile var navigationCompletedNs = 0L
        private set
    @Volatile var captureStartedText = ""
        private set
    /** On the UI thread, when the page asks to move focus out (Tab past its last or Shift+Tab past its first element). */
    var onMoveFocusRequested: (reason: Int) -> Unit = {}

    private val pending = ConcurrentHashMap<Long, CompletableDeferred<String>>()
    private val nextRequest = AtomicLong(1)
    private val receiverJs = resource("/wv2test/receiver.js")
    private val pageHtml = resource("/wv2test/page.html")

    fun start(canvasHwnd: Long) {
        WinNative.onFrameListener = { frameSignal.trySend(Unit) }
        WinNative.onCursorListener = { image ->
            val custom = customCursor(image)
            EventQueue.invokeLater { cursor = custom }
        }
        WinNative.onEventListener = ::handle
        WinNative.start(
            canvasHwnd, options.parentMode, options.userDataFolder, options.browserArgs,
            options.dirtyRegions, options.compensateOffset,
        )
    }

    private fun handle(e: NativeEvent) {
        log += LoggedEvent(System.nanoTime(), e)
        when (e.kind) {
            WinNative.EV_READY -> if (e.a == 0L) {
                WinNative.addDocumentScript(receiverJs)
                EventQueue.invokeLater { readyInfo = e.text }
            } else {
                val hr = java.lang.Long.toHexString(e.a and 0xffffffffL)
                EventQueue.invokeLater { failure = "WebView2 failed (hr=0x$hr): ${e.text}" }
            }
            WinNative.EV_DOC_SCRIPT_ADDED -> WinNative.navigateToString(pageHtml)
            WinNative.EV_NAV_COMPLETED -> navigationCompletedNs = System.nanoTime()
            WinNative.EV_FOCUS -> EventQueue.invokeLater { webFocused = e.a == 1L }
            WinNative.EV_CURSOR -> {
                val next = cursorFor(e.a.toInt())
                EventQueue.invokeLater { cursor = next }
            }
            WinNative.EV_SCRIPT_RESULT -> pending.remove(e.a)?.complete(if (e.b == 0L) e.text else "null")
            WinNative.EV_WEB_MESSAGE -> onPageMessage(e.text)
            WinNative.EV_MOVE_FOCUS_REQUESTED -> EventQueue.invokeLater { onMoveFocusRequested(e.a.toInt()) }
            WinNative.EV_CAPTURE_STARTED -> captureStartedText = "${e.a}x${e.b} ${e.text}"
            WinNative.EV_LOG -> System.err.println("wv2 native: ${e.text}")
            WinNative.EV_PROCESS_FAILED -> System.err.println("wv2 native: WebView2 process failed, kind ${e.a}")
        }
    }

    private fun onPageMessage(json: String) {
        val message = runCatching { Json.parseToJsonElement(json) }.getOrNull()
        if (message.field("t").str() == "ready") pageReady.complete(message.field("at").double())
    }

    /** Runs [script] in the page (ExecuteScript): its JSON result, or null on failure or after [timeoutMs]. */
    suspend fun eval(script: String, timeoutMs: Long = 5_000): JsonElement? {
        val id = nextRequest.getAndIncrement()
        val result = CompletableDeferred<String>()
        pending[id] = result
        WinNative.executeScript(id, script)
        val text = withTimeoutOrNull(timeoutMs) { result.await() }
        if (text == null) {
            pending.remove(id)
            return null
        }
        return runCatching { Json.parseToJsonElement(text) }.getOrNull()
    }

    /** `api[name](...args)` in the page, through window.__wv2test (receiver.js): its value, or null. */
    suspend fun call(name: String, vararg args: Any?): JsonElement? {
        val arguments = args.joinToString(",") { jsLiteral(it) }
        return eval("window.__wv2test.call(${jsLiteral(name)}, [$arguments])").field("value")
    }

    fun events(): List<LoggedEvent> = log.toList()

    private fun cursorFor(idc: Int): Cursor {
        CursorMapping.awtType(idc)?.let { return Cursor.getPredefinedCursor(it) }
        val packed = WinNative.systemCursorImage(idc) ?: return Cursor.getDefaultCursor()
        return customCursor(CustomCursorImage.fromPacked(packed))
    }

    private fun customCursor(image: CustomCursorImage): Cursor {
        val awtImage = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        awtImage.setRGB(0, 0, image.width, image.height, image.argb, 0, image.width)
        return Toolkit.getDefaultToolkit().createCustomCursor(awtImage, Point(image.hotX, image.hotY), "wv2-cursor")
    }
}

/** A JS literal for a Kotlin value: numbers and booleans as they are, anything else as a JSON string. */
fun jsLiteral(value: Any?): String = when (value) {
    null -> "null"
    is Number, is Boolean -> value.toString()
    else -> JsonPrimitive(value.toString()).toString()
}

fun JsonElement?.str(): String = (this as? JsonPrimitive)?.contentOrNull ?: ""
fun JsonElement?.double(): Double = (this as? JsonPrimitive)?.doubleOrNull ?: Double.NaN
fun JsonElement?.int(): Int = (this as? JsonPrimitive)?.intOrNull ?: -1
fun JsonElement?.list(): List<JsonElement> = (this as? JsonArray) ?: emptyList()
fun JsonElement?.field(name: String): JsonElement? = (this as? JsonObject)?.get(name)

private fun resource(path: String): String =
    checkNotNull(WinWebView::class.java.getResource(path)) { "missing resource $path" }.readText()
