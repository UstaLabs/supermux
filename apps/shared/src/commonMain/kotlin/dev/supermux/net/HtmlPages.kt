package dev.supermux.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Html cards' pages by id (#104), kept in memory: a card scrolled back in, or shown again, doesn't fetch its page again.
 * The latest [max] are kept; a page the broker didn't have is asked for again next time.
 */
class HtmlPages(private val fetch: suspend (id: String) -> String?, private val max: Int = 32) {
    private val kept = MutableStateFlow<Map<String, String>>(emptyMap())

    /** The page if it is kept, without a fetch. */
    fun peek(id: String): String? = kept.value[id]

    suspend fun get(id: String): String? {
        peek(id)?.let { return it }
        val html = fetch(id) ?: return null
        kept.update { pages -> (pages - id + (id to html)).let { if (it.size > max) it - it.keys.first() else it } }
        return html
    }
}
