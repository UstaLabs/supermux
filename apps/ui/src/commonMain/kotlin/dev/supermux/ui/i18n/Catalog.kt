// #123: the string catalogs. Compose resources' generated accessors cost ~150 B of wasm per key, which put the web
// bundle over its ceiling at 675 keys; even a key's name carried as a string costs ~5 B gzipped. So :ui:generateI18n
// numbers every key: `Str.<key>` / `Plurals.<key>` are inline getters returning that number (no code per key), and
// each language is ONE compact file, files/i18n/<lang>.txt, compiled from the XML in apps/ui/i18n.
package dev.supermux.ui.i18n

import kotlin.jvm.JvmInline

/** A string's number in the catalogs. Made only by the generated [Str]: a key always exists in English. */
@JvmInline
value class StringKey(val id: Int)

/** A count's number in the catalogs. Made only by the generated [Plurals]. */
@JvmInline
value class PluralKey(val id: Int)

/** One language's strings by number; plurals by number, then CLDR quantity ("one", "other"). Gaps are null. */
class Catalog(val strings: Array<String?>, val plurals: Array<Map<String, String>?>) {
    fun text(key: StringKey): String? = strings.getOrNull(key.id)
}

/**
 * Reads a compact catalog: one entry per line, tab-separated — `s <id> <text>` or `p <id> <quantity> <text>` — with
 * `\\`, `\n` and `\t` escaped in the text.
 */
fun parseCatalog(text: String, strings: Int = I18nFiles.STRINGS, plurals: Int = I18nFiles.PLURALS): Catalog {
    val s = arrayOfNulls<String>(strings)
    val p = arrayOfNulls<MutableMap<String, String>>(plurals)
    for (line in text.lineSequence()) {
        if (line.length < 4) continue
        val a = line.indexOf('\t')
        val b = line.indexOf('\t', a + 1)
        if (a < 0 || b < 0) continue
        val id = line.substring(a + 1, b).toIntOrNull() ?: continue
        when (line[0]) {
            's' -> if (id in s.indices) s[id] = unescapeCompact(line.substring(b + 1))
            'p' -> {
                val c = line.indexOf('\t', b + 1)
                if (c > 0 && id in p.indices) {
                    (p[id] ?: mutableMapOf<String, String>().also { p[id] = it })[line.substring(b + 1, c)] =
                        unescapeCompact(line.substring(c + 1))
                }
            }
        }
    }
    @Suppress("UNCHECKED_CAST")
    return Catalog(s, p as Array<Map<String, String>?>)
}

internal fun unescapeCompact(raw: String): String {
    if ('\\' !in raw) return raw
    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '\\' && i + 1 < raw.length) {
            out.append(
                when (val n = raw[i + 1]) {
                    'n' -> '\n'
                    't' -> '\t'
                    else -> n
                },
            )
            i += 2
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}

/** `%1$s` / `%2$d` filled from [args] (1-based), as Compose resources formatted; any other `%` stays as written. */
fun formatCatalogText(text: String, args: Array<out Any>): String {
    if (args.isEmpty() || '%' !in text) return text
    return PLACEHOLDER.replace(text) { m ->
        val i = m.groupValues[1].toInt() - 1
        if (i in args.indices) args[i].toString() else m.value
    }
}

private val PLACEHOLDER = Regex("%(\\d+)\\$[ds]")

/**
 * The CLDR plural category of [count] in [language]. English and Turkish both have exactly "one" (n = 1) and
 * "other"; a language added later with more categories (few, many) adds its rule here.
 */
@Suppress("UNUSED_PARAMETER")
fun pluralCategory(language: String, count: Int): String = if (count == 1) "one" else "other"

/** Shown only if even English could not be read: never a blank, never a raw key. */
internal const val UNREADABLE = "…"

/**
 * What a key reads as: [chosen] first, then English. English always has the key (the keys are generated from it), so
 * neither a blank nor a raw key is ever shown. An empty translation of a non-empty English text falls back too.
 */
fun lookup(key: StringKey, chosen: Catalog?, english: Catalog?): String {
    val en = english?.text(key)
    val own = chosen?.text(key)
    return when {
        own != null && (own.isNotEmpty() || en.isNullOrEmpty()) -> own
        en != null -> en
        else -> UNREADABLE
    }
}

/** A count's text: [chosen]'s form for its category, then English's form, then English's "other". */
fun lookupPlural(key: PluralKey, language: String, count: Int, chosen: Catalog?, english: Catalog?): String {
    val forms = chosen?.plurals?.getOrNull(key.id)
    return forms?.get(pluralCategory(language, count)) ?: forms?.get("other")
        ?: english?.plurals?.getOrNull(key.id)?.let { it[pluralCategory("en", count)] ?: it["other"] }
        ?: UNREADABLE
}
