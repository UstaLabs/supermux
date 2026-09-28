package com.swithun.cmpmermaid.core.flowchart.upstream.mermaid

/**
 * Kotlin translation of @braintree/sanitize-url 7.1.2, the version resolved by
 * Mermaid 12.0.0.
 */
internal object MermaidUrlSanitizer {
    fun sanitize(source: String): String {
        if (source.isEmpty()) {
            return BLANK_URL
        }
        var decoded = decodeUriComponent(source.trim())
        do {
            val previous = decoded
            decoded = decodeHtmlCharacters(decoded)
                .replace(HTML_CONTROL_ENTITY, "")
                .replace(CONTROL_CHARACTERS, "")
                .replace(WHITESPACE_ESCAPE_CHARACTERS, "")
                .trim()
            decoded = decodeUriComponent(decoded)
        } while (decoded != previous && containsEncodedCharacters(decoded))

        if (decoded.isEmpty()) {
            return BLANK_URL
        }
        if (decoded.first() in RELATIVE_FIRST_CHARACTERS) {
            return decoded
        }
        val trimmed = decoded.trimStart()
        val scheme = URL_SCHEME.find(trimmed)?.value?.lowercase()?.trim()
            ?: return decoded
        if (INVALID_PROTOCOL.containsMatchIn(scheme)) {
            return BLANK_URL
        }
        val slashNormalized = trimmed.replace('\\', '/')
        if (scheme == "mailto:" || "://" in scheme) {
            return slashNormalized
        }
        if (scheme == "http:" || scheme == "https:") {
            return slashNormalized.takeIf(::isValidHttpUrl) ?: BLANK_URL
        }
        return slashNormalized
    }

    private fun decodeHtmlCharacters(source: String): String =
        HTML_ENTITY.replace(CONTROL_CHARACTERS.replace(source, "")) { match ->
            val codeUnit = match.groupValues[1].toIntOrNull()?.and(0xFFFF) ?: 0
            codeUnit.toChar().toString()
        }

    private fun decodeUriComponent(source: String): String {
        val output = StringBuilder(source.length)
        var cursor = 0
        while (cursor < source.length) {
            if (source[cursor] != '%') {
                output.append(source[cursor])
                cursor++
                continue
            }
            val bytes = mutableListOf<Byte>()
            while (cursor < source.length && source[cursor] == '%') {
                if (cursor + 2 >= source.length) {
                    return source
                }
                val high = source[cursor + 1].digitToIntOrNull(16) ?: return source
                val low = source[cursor + 2].digitToIntOrNull(16) ?: return source
                bytes += ((high shl 4) or low).toByte()
                cursor += 3
            }
            val decoded = try {
                bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
            } catch (_: Exception) {
                return source
            }
            output.append(decoded)
        }
        return output.toString()
    }

    private fun containsEncodedCharacters(source: String): Boolean =
        CONTROL_CHARACTERS.containsMatchIn(source) ||
            HTML_ENTITY.containsMatchIn(source) ||
            HTML_CONTROL_ENTITY.containsMatchIn(source) ||
            WHITESPACE_ESCAPE_CHARACTERS.containsMatchIn(source)

    private fun isValidHttpUrl(source: String): Boolean {
        val separator = source.indexOf("://")
        if (separator < 0 || separator + 3 >= source.length) {
            return false
        }
        val authority = source
            .substring(separator + 3)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        return authority.isNotEmpty() && authority.none(Char::isWhitespace)
    }

    private const val BLANK_URL = "about:blank"
    private val RELATIVE_FIRST_CHARACTERS = setOf('.', '/')
    private val INVALID_PROTOCOL = Regex(
        pattern = """^([^\w]*)(javascript|data|vbscript)""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )
    private val HTML_ENTITY = Regex("""&#(\w+)(?:;)?""")
    private val HTML_CONTROL_ENTITY = Regex(
        pattern = """&(newline|tab);""",
        option = RegexOption.IGNORE_CASE,
    )
    private val CONTROL_CHARACTERS = Regex(
        pattern = """[\u0000-\u001F\u007F-\u009F\u2000-\u200D\uFEFF]""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )
    private val URL_SCHEME = Regex(
        pattern = """^.+(:|&colon;)""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )
    private val WHITESPACE_ESCAPE_CHARACTERS = Regex(
        pattern = """(\\|%5[cC])((%(6[eE]|72|74))|[nrt])""",
    )
}
