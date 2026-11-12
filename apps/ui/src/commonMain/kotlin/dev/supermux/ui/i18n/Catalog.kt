// #123: the string catalogs. Compose resources' generated accessors cost ~150 B of wasm per key (a lazy delegate
// each), which put the web bundle over its ceiling at 675 keys. So keys are generated as inline getters that compile
// to a string literal ([Str], [Plurals] — no code per key), and each language is a set of plain XML files under
// composeResources/files/i18n/, read once: English at start, another language only when it is chosen.
package dev.supermux.ui.i18n

import kotlin.jvm.JvmInline

/** A string's key in the catalogs. Made only by the generated [Str]: a key always exists in English. */
@JvmInline
value class StringKey(val name: String)

/** A count's key in the catalogs. Made only by the generated [Plurals]. */
@JvmInline
value class PluralKey(val name: String)

/** One language's strings: plain ones by key, plurals by key then CLDR quantity ("one", "other"). */
class Catalog(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>) {
    operator fun plus(other: Catalog) = Catalog(strings + other.strings, plurals + other.plurals)

    companion object {
        val EMPTY = Catalog(emptyMap(), emptyMap())
    }
}

/**
 * Reads one catalog file: `<string name="k">text</string>` and `<plurals name="k"><item quantity="one">…</item>…`.
 * Text is unescaped as Compose resources did: XML entities, then `\n`, `\t`, `\uXXXX` and a backslash before any
 * other character.
 */
fun parseCatalog(xml: String): Catalog {
    val body = xml.replace(COMMENT, "")
    val strings = STRING.findAll(body).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
    val plurals = PLURALS.findAll(body).associate { p ->
        p.groupValues[1] to ITEM.findAll(p.groupValues[2]).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
    }
    return Catalog(strings, plurals)
}

private val COMMENT = Regex("<!--[\\s\\S]*?-->")
private val STRING = Regex("<string\\s+name=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</string>")
private val PLURALS = Regex("<plurals\\s+name=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</plurals>")
private val ITEM = Regex("<item\\s+quantity=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</item>")
private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);")

internal fun unescape(raw: String): String {
    val xml = ENTITY.replace(raw) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> if (e.startsWith("#x")) e.drop(2).toInt(16).toChar().toString() else e.drop(1).toInt().toChar().toString()
        }
    }
    if ('\\' !in xml) return xml
    val out = StringBuilder(xml.length)
    var i = 0
    while (i < xml.length) {
        val c = xml[i]
        if (c == '\\' && i + 1 < xml.length) {
            val n = xml[i + 1]
            when {
                n == 'n' -> { out.append('\n'); i += 2 }
                n == 't' -> { out.append('\t'); i += 2 }
                n == 'u' && i + 5 < xml.length -> { out.append(xml.substring(i + 2, i + 6).toInt(16).toChar()); i += 6 }
                else -> { out.append(n); i += 2 }
            }
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

/**
 * What a key reads as: [chosen] first, then English. English always has the key (the keys are generated from it),
 * so neither a blank nor a raw key is ever shown; [humanized] is the last resort if even English failed to load.
 */
fun lookup(name: String, chosen: Catalog?, english: Catalog?): String =
    chosen?.strings?.get(name)?.takeIf { it.isNotEmpty() || english?.strings?.get(name).isNullOrEmpty() }
        ?: english?.strings?.get(name)
        ?: humanized(name)

/** A count's text: [chosen]'s form for its category, then English's form, then English's "other". */
fun lookupPlural(name: String, language: String, count: Int, chosen: Catalog?, english: Catalog?): String {
    val forms = chosen?.plurals?.get(name)
    val category = pluralCategory(language, count)
    return forms?.get(category) ?: forms?.get("other")
        ?: english?.plurals?.get(name)?.let { it[pluralCategory("en", count)] ?: it["other"] }
        ?: humanized(name)
}

/** "settings_title" → "Settings title": readable if every catalog failed, never a raw snake_case key. */
internal fun humanized(name: String): String =
    name.substringAfter('_').replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
