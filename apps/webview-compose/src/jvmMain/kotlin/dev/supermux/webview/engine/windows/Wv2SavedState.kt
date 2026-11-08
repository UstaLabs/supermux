package dev.supermux.webview.engine.windows

import dev.supermux.webview.engine.webkit.SavedWebKitState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * What a Windows engine's `saveState` returns (D11): WebView2 has no session-state API, so the page's URL and scroll, in
 * the WebKit engines' envelope (`SavedWebKitState`: the content's key first, so another content's state is refused).
 * In memory only, as every engine's state.
 */
internal object Wv2SavedState {
    class Saved(val url: String, val scroll: Pair<Double, Double>)

    fun encode(contentKey: String, url: String, scroll: Pair<Double, Double>): ByteArray = SavedWebKitState.encode(
        contentKey,
        buildJsonObject {
            put("v", 1)
            put("url", url)
            put("x", scroll.first)
            put("y", scroll.second)
        }.toString().encodeToByteArray(),
    )

    fun decode(bytes: ByteArray, contentKey: String): Saved? {
        val payload = SavedWebKitState.decode(bytes, contentKey) ?: return null
        val json = runCatching { Json.parseToJsonElement(payload.decodeToString()).jsonObject }.getOrNull() ?: return null
        if (json["v"]?.jsonPrimitive?.intOrNull != 1) return null
        val url = json["url"]?.jsonPrimitive?.contentOrNull ?: return null
        return Saved(url, (json["x"]?.jsonPrimitive?.doubleOrNull ?: 0.0) to (json["y"]?.jsonPrimitive?.doubleOrNull ?: 0.0))
    }
}
