package com.swithun.cmpmermaid.core.flowchart.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError

/**
 * Small platform-independent tokenizer for the HTML fragments that Mermaid
 * passes to DOMPurify and HTMLElement.innerHTML for flowchart labels.
 */
internal object MermaidHtmlFragmentTokenizer {
    fun tokenize(source: String): GMResult<List<MermaidHtmlFragmentToken>, MermaidError> {
        val tokens = mutableListOf<MermaidHtmlFragmentToken>()
        var cursor = 0
        while (cursor < source.length) {
            val tagStart = source.indexOf('<', cursor)
            if (tagStart < 0) {
                tokens += MermaidHtmlFragmentToken.Text(source.substring(cursor))
                break
            }
            if (tagStart > cursor) {
                tokens += MermaidHtmlFragmentToken.Text(source.substring(cursor, tagStart))
            }
            if (source.startsWith("<!--", tagStart)) {
                val commentEnd = source.indexOf("-->", tagStart + 4)
                cursor = if (commentEnd < 0) source.length else commentEnd + 3
                continue
            }
            val tagEnd = findTagEnd(source, tagStart + 1)
                ?: return parseError(source, tagStart, "Unclosed HTML label tag")
            val rawBody = source.substring(tagStart + 1, tagEnd)
            val parsed = when (val result = parseTag(rawBody, tagStart)) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            if (parsed == null) {
                tokens += MermaidHtmlFragmentToken.Text(source.substring(tagStart, tagEnd + 1))
            } else {
                tokens += parsed
            }
            cursor = tagEnd + 1
        }
        return GMResult.Ok(tokens)
    }

    private fun findTagEnd(
        source: String,
        start: Int,
    ): Int? {
        var quote: Char? = null
        var cursor = start
        while (cursor < source.length) {
            val character = source[cursor]
            if (quote == null && character == '>') {
                return cursor
            }
            if (character == '"' || character == '\'') {
                quote = when {
                    quote == null -> character
                    quote == character -> null
                    else -> quote
                }
            }
            cursor++
        }
        return null
    }

    private fun parseTag(
        rawBody: String,
        sourceOffset: Int,
    ): GMResult<MermaidHtmlFragmentToken.Tag?, MermaidError> {
        var cursor = 0
        while (rawBody.getOrNull(cursor)?.isWhitespace() == true) {
            cursor++
        }
        if (rawBody.getOrNull(cursor) in setOf('!', '?')) {
            return GMResult.Ok(null)
        }
        val closing = rawBody.getOrNull(cursor) == '/'
        if (closing) {
            cursor++
            while (rawBody.getOrNull(cursor)?.isWhitespace() == true) {
                cursor++
            }
        }
        val nameStart = cursor
        while (rawBody.getOrNull(cursor)?.isHtmlNameCharacter() == true) {
            cursor++
        }
        if (cursor == nameStart) {
            return GMResult.Ok(null)
        }
        val name = rawBody.substring(nameStart, cursor).lowercase()
        val attributes = linkedMapOf<String, String>()
        var selfClosing = false
        while (cursor < rawBody.length) {
            while (rawBody.getOrNull(cursor)?.isWhitespace() == true) {
                cursor++
            }
            if (cursor >= rawBody.length) {
                break
            }
            if (rawBody[cursor] == '/') {
                selfClosing = true
                cursor++
                continue
            }
            val attributeStart = cursor
            while (
                rawBody.getOrNull(cursor)?.let { character ->
                    !character.isWhitespace() && character !in setOf('=', '/', '>')
                } == true
            ) {
                cursor++
            }
            if (cursor == attributeStart) {
                return parseError(rawBody, sourceOffset + cursor, "Invalid HTML label attribute")
            }
            val attributeName = rawBody.substring(attributeStart, cursor).lowercase()
            while (rawBody.getOrNull(cursor)?.isWhitespace() == true) {
                cursor++
            }
            var value = ""
            if (rawBody.getOrNull(cursor) == '=') {
                cursor++
                while (rawBody.getOrNull(cursor)?.isWhitespace() == true) {
                    cursor++
                }
                val quote = rawBody.getOrNull(cursor)
                if (quote == '"' || quote == '\'') {
                    cursor++
                    val valueStart = cursor
                    while (cursor < rawBody.length && rawBody[cursor] != quote) {
                        cursor++
                    }
                    if (cursor >= rawBody.length) {
                        return parseError(
                            rawBody,
                            sourceOffset + valueStart,
                            "Unclosed HTML label attribute '$attributeName'",
                        )
                    }
                    value = rawBody.substring(valueStart, cursor)
                    cursor++
                } else {
                    val valueStart = cursor
                    while (
                        rawBody.getOrNull(cursor)?.let { character ->
                            !character.isWhitespace() && character !in setOf('/', '>')
                        } == true
                    ) {
                        cursor++
                    }
                    value = rawBody.substring(valueStart, cursor)
                }
            }
            attributes[attributeName] = MermaidHtmlEntityDecoder.decode(value)
        }
        return GMResult.Ok(
            MermaidHtmlFragmentToken.Tag(
                name = name,
                closing = closing,
                selfClosing = selfClosing,
                attributes = attributes,
            ),
        )
    }

    private fun Char.isHtmlNameCharacter(): Boolean =
        isLetterOrDigit() || this in setOf('-', ':')

    private fun <T> parseError(
        source: String,
        offset: Int,
        message: String,
    ): GMResult<T, MermaidError> {
        val line = source.take(offset.coerceAtMost(source.length)).count { it == '\n' } + 1
        val lineStart = source.lastIndexOf('\n', (offset - 1).coerceAtLeast(0))
        return GMResult.Err(
            MermaidError.Parse(
                line = line,
                column = offset - lineStart,
                message = message,
            ),
        )
    }
}

internal sealed interface MermaidHtmlFragmentToken {
    data class Text(
        val value: String,
    ) : MermaidHtmlFragmentToken

    data class Tag(
        val name: String,
        val closing: Boolean,
        val selfClosing: Boolean,
        val attributes: Map<String, String>,
    ) : MermaidHtmlFragmentToken
}
