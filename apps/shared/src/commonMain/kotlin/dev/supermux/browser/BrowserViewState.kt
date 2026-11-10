package dev.supermux.browser

import io.ktor.http.Url
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Browser tab's synced state (#105): the page it shows, an agent page (#104's doc), or nothing yet. The same rule as
 * the broker's browserStateError, so the app never sends a state the broker refuses: http/https pages only, at most
 * 4096 characters, a doc a short id.
 */
sealed class BrowserViewState {
    data class Page(val url: String) : BrowserViewState()

    data class Doc(val id: String) : BrowserViewState()

    data object Empty : BrowserViewState()

    fun toJson(): JsonObject = when (this) {
        is Page -> JsonObject(mapOf("url" to JsonPrimitive(url)))
        is Doc -> JsonObject(mapOf("doc" to JsonPrimitive(id)))
        Empty -> JsonObject(emptyMap())
    }

    companion object {
        private const val MAX_URL = 4096
        private val DOC_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

        fun page(url: String): Page? {
            if (url.length > MAX_URL) return null
            val parsed = runCatching { Url(url) }.getOrNull() ?: return null
            return if ((parsed.protocol.name == "http" || parsed.protocol.name == "https") && url.contains("://")) Page(url) else null
        }

        fun doc(id: String): Doc? = if (DOC_ID.matches(id)) Doc(id) else null

        /** What the broker stored, read back; anything this rule refuses opens as a new tab. */
        fun from(state: JsonObject): BrowserViewState {
            val url = runCatching { state["url"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            val doc = runCatching { state["doc"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            return url?.let(::page) ?: doc?.let(::doc) ?: Empty
        }
    }
}
