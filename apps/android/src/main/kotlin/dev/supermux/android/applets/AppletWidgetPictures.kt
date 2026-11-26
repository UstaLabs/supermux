// #165: the pictures in applet widgets, drawn on the phone. An applet's widget.html is loaded offscreen with no network
// at all (one self-contained page), at each size a picture appears in, light and dark, and kept as a PNG beside the
// snapshot; the widget shows it, and the block's native "alt" until it exists. Glance can't host a web view, so this
// runs in the app's process: after a snapshot changes, on a push, after an action.
package dev.supermux.android.applets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.supermux.net.WidgetDoc
import dev.supermux.net.WidgetSize
import dev.supermux.net.widgetFileKey
import dev.supermux.net.widgetPictureFile
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal object AppletWidgetPictures {
    private const val TAG = "AppletWidgets"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    /** The widget width (dp) of each layout, as a phone's launcher draws it; a picture's share is taken from it. */
    private fun widthDp(size: WidgetSize): Int = when (size) {
        WidgetSize.Slim, WidgetSize.Small, WidgetSize.Tall -> 180
        WidgetSize.XLarge -> 680
        else -> 360
    }

    /** Draws what [hostId]'s widgets need, in the background, one host at a time. */
    fun schedule(context: Context, hostId: String) {
        val app = context.applicationContext
        scope.launch { lock.withLock { runCatching { render(app, hostId) }.onFailure { Log.w(TAG, "pictures for $hostId failed", it) } } }
    }

    private suspend fun render(context: Context, hostId: String) {
        val host = AppletWidgetStore.host(context, hostId) ?: return
        for (a in host.applets) {
            val doc = WidgetDoc.parse(a.widget)
            val html = a.widgetHtml
            val slots = if (doc == null || html == null) emptyList() else WidgetSize.entries.flatMap { s -> doc.pictureSlots(s).map { Triple(s, it.first, it.second) } }
            if (slots.isEmpty()) {
                if (a.pictures.isNotEmpty()) AppletWidgetStore.putPictures(context, hostId, a.name, emptyMap())
                continue
            }
            val page = withTimeoutOrNull(15_000) {
                HttpClient(CIO).use { http ->
                    val api = AppletWidgetStore.hostApi(context, hostId, http) ?: return@use null
                    runCatching { api.authedGetText(html!!) }.getOrNull()
                }
            } ?: continue
            // Drawn again only when the page or the document changed (the pictures are kept otherwise).
            val sig = widgetFileKey("r2\u0000" + page + "\u0000" + a.widget.toString())
            val sigFile = File(AppletWidgetStore.dir(context), "pics-${widgetFileKey("$hostId/${a.name}")}.sig")
            if (a.pictures.isNotEmpty() && runCatching { sigFile.readText() }.getOrNull() == sig) continue
            val out = mutableMapOf<String, String>()
            for ((size, pic, share) in slots) {
                for (dark in listOf(false, true)) {
                    val wDp = ((widthDp(size) - 32) * share).toInt().coerceAtLeast(40)
                    val hDp = (pic.height - if (pic.caption != null) 17 else 0).coerceAtLeast(16)
                    val bmp = withContext(Dispatchers.Main) { snapshot(context, page, size, pic.part, wDp, hDp, dark) } ?: continue
                    val name = widgetPictureFile(hostId, a.name, size, pic.part, dark)
                    File(AppletWidgetStore.dir(context), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bmp.recycle()
                    out["${size.key}/${pic.part}/${if (dark) "dark" else "light"}"] = name
                }
            }
            sigFile.writeText(sig)
            AppletWidgetStore.putPictures(context, hostId, a.name, out)
        }
    }

    /** One picture: [page] at [wDp]×[hDp], with its query saying which part, size and look. Main thread. */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun snapshot(context: Context, page: String, size: WidgetSize, part: String, wDp: Int, hDp: Int, dark: Boolean): Bitmap? {
        val density = context.resources.displayMetrics.density
        val w = (wDp * density).toInt()
        val h = (hDp * density).toInt()
        val ready = CompletableDeferred<Unit>()
        val loaded = CompletableDeferred<Unit>()
        val view = WebView(context)
        try {
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            view.settings.javaScriptEnabled = true
            view.settings.blockNetworkLoads = true
            view.settings.allowFileAccess = false
            view.settings.allowContentAccess = false
            view.setInitialScale((density * 100).toInt())
            view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            view.addJavascriptInterface(object {
                @JavascriptInterface fun widgetReady() { ready.complete(Unit) }
            }, "supermux")
            view.webViewClient = object : WebViewClient() {
                // Nothing leaves the phone: a widget page is one self-contained file.
                // The page itself is the data handed in (never fetched); everything else is refused.
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? =
                    if (r.isForMainFrame) null else WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                override fun onPageFinished(v: WebView, url: String?) { loaded.complete(Unit) }
            }
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
            val q = "size=${size.key}&w=$wDp&h=$hDp&scale=${"%.2f".format(java.util.Locale.ROOT, density)}&theme=${if (dark) "dark" else "light"}&platform=android&part=${java.net.URLEncoder.encode(part, "UTF-8")}"
            view.loadDataWithBaseURL("https://widget.supermux.invalid/widget.html?$q", page, "text/html", "utf-8", null)
            if (withTimeoutOrNull(5_000) { loaded.await() } == null) return null
            // Its own "drawn" signal, or 2 s after load; 5 s at most.
            withTimeoutOrNull(2_000) { ready.await() }
            delay(50)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bmp))
            return bmp
        } catch (e: Throwable) {
            Log.w(TAG, "widget picture $part failed: ${e.message}")
            return null
        } finally {
            view.destroy()
        }
    }
}
