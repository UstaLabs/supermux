package dev.supermux.net

/** A `Content-Range: bytes <first>-<last>/<total>` value. */
internal data class ContentRange(val first: Long, val last: Long, val total: Long)

private val SATISFIED = Regex("^bytes (\\d+)-(\\d+)/(\\d+)$")
private val UNSATISFIED = Regex("^bytes \\*/(\\d+)$")

internal fun parseContentRange(value: String?): ContentRange? {
    val m = SATISFIED.find(value?.trim() ?: return null) ?: return null
    return ContentRange(m.groupValues[1].toLong(), m.groupValues[2].toLong(), m.groupValues[3].toLong())
}

/** The size a 416 reports — its `Content-Range` is `bytes`, an asterisk, then `/<size>` — or null. */
internal fun parseUnsatisfiedRangeTotal(value: String?): Long? =
    UNSATISFIED.find(value?.trim() ?: return null)?.groupValues?.get(1)?.toLong()
