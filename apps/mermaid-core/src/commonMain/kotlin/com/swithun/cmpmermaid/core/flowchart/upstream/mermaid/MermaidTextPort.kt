package com.swithun.cmpmermaid.core.flowchart.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.CssColorParser
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidPreprocessor
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.SceneTextBaselineShift
import com.swithun.cmpmermaid.core.SceneTextFontFamily
import com.swithun.cmpmermaid.core.SceneTextSpan
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.flowchart.upstream.marked.MarkedLexer
import com.swithun.cmpmermaid.core.flowchart.upstream.marked.MarkedToken
import com.swithun.cmpmermaid.core.flowchart.upstream.marked.MarkedTokenType

/**
 * Translation of Mermaid 12.0.0 createText.ts and
 * handle-markdown-text.ts. Markdown tokenization is delegated to the
 * translated Marked 16.4.2 lexer, matching Mermaid's resolved dependency.
 */
internal object MermaidTextPort {
    // Browser HTML labels compute the user-agent sub/sup "smaller" size as 5/6.
    private const val HTML_SCRIPT_FONT_SIZE_SCALE = 5f / 6f

    fun render(
        source: String,
        labelType: FlowLabelType,
        config: MermaidRenderOptions,
    ): GMResult<MermaidRenderedText, MermaidError> {
        val parserDecoded = MermaidPreprocessor.decodeEntities(source)
        if (KATEX.containsMatchIn(parserDecoded)) {
            return unsupported("KaTeX label")
        }
        if (config.htmlLabels && FONT_AWESOME_ICON.containsMatchIn(parserDecoded)) {
            return unsupported("FontAwesome label icon")
        }
        return when (labelType) {
            FlowLabelType.Markdown -> renderMarkdown(source, config)
            FlowLabelType.String,
            FlowLabelType.Text,
            -> renderNonMarkdown(source, config)
        }
    }

    /**
     * Mermaid.js 12.0.0: diagrams/common/common.ts -> sanitizeText.
     *
     * This boundary preserves entity references because callers such as the
     * Ishikawa SVG renderer assign the sanitized result through textContent.
     */
    fun sanitizeText(source: String): GMResult<String, MermaidError> =
        sanitizeSvgLiteral(source)

    private fun renderNonMarkdown(
        source: String,
        config: MermaidRenderOptions,
    ): GMResult<MermaidRenderedText, MermaidError> {
        val normalized = source
            .replace(LINE_BREAK, "\n")
            .replace("\\n", "\n")
        if (config.htmlLabels) {
            return renderHtmlFragment(normalized)
        }
        return renderSvgNonMarkdown(normalized)
    }

    private fun renderMarkdown(
        source: String,
        config: MermaidRenderOptions,
    ): GMResult<MermaidRenderedText, MermaidError> {
        val markedSource = if (config.htmlLabels) {
            source
        } else {
            val sanitized = when (val result = sanitizeSvgLiteral(source)) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            preprocessMarkdown(
                MermaidHtmlEntityDecoder.decode(
                    MermaidPreprocessor.decodeEntities(sanitized),
                ),
            )
        }
        val tokens = when (val result = MarkedLexer.lex(markedSource)) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        return if (config.htmlLabels) {
            renderHtmlLabel(tokens, config.markdownAutoWrap)
        } else {
            renderSvgLabel(tokens)
        }
    }

    private fun renderSvgNonMarkdown(
        source: String,
    ): GMResult<MermaidRenderedText, MermaidError> {
        val sanitized = when (val result = sanitizeSvgLiteral(source)) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val decoded = MermaidHtmlEntityDecoder.decode(
            MermaidPreprocessor.decodeEntities(sanitized),
        )
        val output = SvgLineBuilder()
        decoded.split(SVG_LINE_BREAK).forEachIndexed { lineIndex, line ->
            if (lineIndex != 0) {
                output.newLine()
            }
            SVG_WORD.findAll(line.trim()).forEach { match ->
                output.appendWord(match.value, SvgWordStyle.Normal)
            }
        }
        return GMResult.Ok(output.build())
    }

    private fun renderHtmlFragment(source: String): GMResult<MermaidRenderedText, MermaidError> {
        val builder = StyledTextBuilder()
        val styles = HtmlStyleStack()
        return when (
            val result = styles.consume(
                raw = MermaidPreprocessor.decodeEntities(source),
                output = builder,
                inheritedStyle = TextStyle(),
            )
        ) {
            is GMResult.Ok -> GMResult.Ok(builder.build())
            is GMResult.Err -> result
        }
    }

    /**
     * Translation of Mermaid markdownToHTML(). Compose receives styled text
     * instead of an HTML string, so HTML formatting tags are projected to the
     * equivalent SceneText spans at this platform boundary.
     */
    private fun renderHtmlLabel(
        tokens: List<MarkedToken>,
        markdownAutoWrap: Boolean,
    ): GMResult<MermaidRenderedText, MermaidError> {
        val builder = StyledTextBuilder()
        val htmlStyles = HtmlStyleStack()
        when (
            val result = appendHtmlTokens(
                tokens = tokens,
                inheritedStyle = TextStyle(),
                markdownAutoWrap = markdownAutoWrap,
                htmlStyles = htmlStyles,
                output = builder,
            )
        ) {
            is GMResult.Err -> return result
            is GMResult.Ok -> Unit
        }
        return GMResult.Ok(builder.build())
    }

    private fun appendHtmlTokens(
        tokens: List<MarkedToken>,
        inheritedStyle: TextStyle,
        markdownAutoWrap: Boolean,
        htmlStyles: HtmlStyleStack,
        output: StyledTextBuilder,
    ): GMResult<Unit, MermaidError> {
        tokens.forEach { token ->
            when (token.type) {
                MarkedTokenType.Text -> {
                    val text = if (markdownAutoWrap) {
                        token.text.replace(HTML_TEXT_NEWLINE, "\n")
                    } else {
                        token.text
                            .replace(HTML_TEXT_NEWLINE, "\n")
                            .replace(" ", "\u00A0")
                    }
                    if (!htmlStyles.isSuppressingContent()) {
                        output.appendHtml(text, inheritedStyle.merge(htmlStyles.current()))
                    }
                }
                MarkedTokenType.Strong -> {
                    when (
                        val nested = appendHtmlTokens(
                            tokens = token.tokens,
                            inheritedStyle = inheritedStyle.copy(
                                weight = SceneTextWeight.Bold,
                            ),
                            markdownAutoWrap = markdownAutoWrap,
                            htmlStyles = htmlStyles,
                            output = output,
                        )
                    ) {
                        is GMResult.Err -> return nested
                        is GMResult.Ok -> Unit
                    }
                }
                MarkedTokenType.Emphasis -> {
                    when (
                        val nested = appendHtmlTokens(
                            tokens = token.tokens,
                            inheritedStyle = inheritedStyle.copy(italic = true),
                            markdownAutoWrap = markdownAutoWrap,
                            htmlStyles = htmlStyles,
                            output = output,
                        )
                    ) {
                        is GMResult.Err -> return nested
                        is GMResult.Ok -> Unit
                    }
                }
                MarkedTokenType.Paragraph -> {
                    when (
                        val nested = appendHtmlTokens(
                            tokens = token.tokens,
                            inheritedStyle = inheritedStyle,
                            markdownAutoWrap = markdownAutoWrap,
                            htmlStyles = htmlStyles,
                            output = output,
                        )
                    ) {
                        is GMResult.Err -> return nested
                        is GMResult.Ok -> Unit
                    }
                }
                MarkedTokenType.Space -> Unit
                MarkedTokenType.Html -> {
                    when (val html = htmlStyles.consume(token.text, output, inheritedStyle)) {
                        is GMResult.Err -> return html
                        is GMResult.Ok -> Unit
                    }
                }
                MarkedTokenType.Escape -> {
                    if (!htmlStyles.isSuppressingContent()) {
                        output.appendHtml(
                            token.text,
                            inheritedStyle.merge(htmlStyles.current()),
                        )
                    }
                }
                else -> {
                    if (!htmlStyles.isSuppressingContent()) {
                        output.appendHtml(
                            token.raw,
                            inheritedStyle.merge(htmlStyles.current()),
                        )
                    }
                }
            }
        }
        return GMResult.Ok(Unit)
    }

    /**
     * Translation of Mermaid markdownToLines(). Marked inline token types not
     * handled by Mermaid are omitted inside paragraphs and retained as raw text
     * when they occur at block level.
     */
    private fun renderSvgLabel(
        tokens: List<MarkedToken>,
    ): GMResult<MermaidRenderedText, MermaidError> {
        val output = SvgLineBuilder()
        tokens.forEach { token ->
            when (token.type) {
                MarkedTokenType.Paragraph -> appendSvgInlineTokens(
                    tokens = token.tokens,
                    style = SvgWordStyle.Normal,
                    output = output,
                )
                MarkedTokenType.Html -> output.appendWord(token.text, SvgWordStyle.Normal)
                else -> output.appendWord(token.raw, SvgWordStyle.Normal)
            }
        }
        return GMResult.Ok(output.build())
    }

    private fun appendSvgInlineTokens(
        tokens: List<MarkedToken>,
        style: SvgWordStyle,
        output: SvgLineBuilder,
    ) {
        tokens.forEach { token ->
            when (token.type) {
                MarkedTokenType.Text -> {
                    token.text.split('\n').forEachIndexed { index, line ->
                        if (index != 0) {
                            output.newLine()
                        }
                        line.split(' ').forEach { word ->
                            if (word.isNotEmpty()) {
                                output.appendWord(word.replace("&#39;", "'"), style)
                            }
                        }
                    }
                }
                MarkedTokenType.Strong -> appendSvgInlineTokens(
                    token.tokens,
                    SvgWordStyle.Strong,
                    output,
                )
                MarkedTokenType.Emphasis -> appendSvgInlineTokens(
                    token.tokens,
                    SvgWordStyle.Emphasis,
                    output,
                )
                MarkedTokenType.Html -> output.appendWord(token.text, SvgWordStyle.Normal)
                else -> Unit
            }
        }
    }

    private fun preprocessMarkdown(source: String): String {
        val withoutBreaks = source.replace("<br/>", "\n")
        val withoutMultipleNewlines = withoutBreaks.replace(MULTIPLE_NEWLINES, "\n")
        return dedent(withoutMultipleNewlines)
    }

    /**
     * Direct string-input path from ts-dedent 2.2.0, the version resolved by
     * Mermaid 12.0.0.
     */
    private fun dedent(source: String): String {
        var result = source.replace(TRAILING_INDENTED_NEWLINE, "")
        val indentLengths = DEDENT_LINE.findAll(result).map { match ->
            val indentation = match.groupValues[1]
            if (indentation.firstOrNull()?.isWhitespace() == true) indentation.length else 0
        }.toList()
        if (indentLengths.isNotEmpty()) {
            val commonIndent = indentLengths.minOrNull() ?: 0
            result = result.replace(
                Regex("""\n[\t ]{$commonIndent}"""),
                "\n",
            )
        }
        return result.replace(LEADING_NEWLINE, "")
    }

    private fun sanitizeSvgLiteral(source: String): GMResult<String, MermaidError> {
        val tokens = when (val result = MermaidHtmlFragmentTokenizer.tokenize(source)) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val output = StringBuilder(source.length)
        val suppressedTags = mutableListOf<String>()
        tokens.forEach { token ->
            when (token) {
                is MermaidHtmlFragmentToken.Text -> {
                    if (suppressedTags.isEmpty()) {
                        output.append(token.value)
                    }
                }
                is MermaidHtmlFragmentToken.Tag -> {
                    if (token.name in FORBIDDEN_CONTENT_TAGS) {
                        updateSuppressedTags(suppressedTags, token)
                    } else if (suppressedTags.isEmpty()) {
                        output.append(token.toSanitizedLiteral())
                    }
                }
            }
        }
        return GMResult.Ok(output.toString())
    }

    private fun updateSuppressedTags(
        suppressedTags: MutableList<String>,
        tag: MermaidHtmlFragmentToken.Tag,
    ) {
        if (tag.closing) {
            val index = suppressedTags.indexOfLast { name -> name == tag.name }
            if (index >= 0) {
                suppressedTags.subList(index, suppressedTags.size).clear()
            }
        } else if (!tag.selfClosing) {
            suppressedTags += tag.name
        }
    }

    private fun MermaidHtmlFragmentToken.Tag.toSanitizedLiteral(): String {
        if (closing) {
            return "</$name>"
        }
        val safeAttributes = attributes
            .asSequence()
            .filterNot { (attributeName) ->
                attributeName.startsWith("on") ||
                    attributeName in FORBIDDEN_HTML_ATTRIBUTES
            }
            .mapNotNull { (attributeName, value) ->
                val safeValue = when (attributeName) {
                    "href", "src", "xlink:href" -> MermaidUrlSanitizer
                        .sanitize(value)
                        .takeUnless { it == "about:blank" }
                    else -> value
                } ?: return@mapNotNull null
                if (safeValue.isEmpty()) {
                    attributeName
                } else {
                    """$attributeName="${safeValue.escapeHtmlAttribute()}""""
                }
            }
            .joinToString(separator = " ", prefix = " ")
            .takeUnless { it == " " }
            .orEmpty()
        return "<$name$safeAttributes${if (selfClosing) "/" else ""}>"
    }

    private fun String.escapeHtmlAttribute(): String = replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun <T> unsupported(feature: String): GMResult<T, MermaidError> =
        GMResult.Err(
            MermaidError.UnsupportedFeature(
                feature = feature,
                message = "Native Mermaid has not translated Mermaid $feature rendering",
            ),
        )

    private class HtmlStyleStack {
        private val frames = mutableListOf<HtmlStyleFrame>()
        private val suppressedTags = mutableListOf<String>()

        fun current(): TextStyle = frames.fold(TextStyle()) { style, frame ->
            style.merge(frame.style)
        }

        fun isSuppressingContent(): Boolean = suppressedTags.isNotEmpty()

        fun consume(
            raw: String,
            output: StyledTextBuilder,
            inheritedStyle: TextStyle,
        ): GMResult<Unit, MermaidError> {
            val tokens = when (val result = MermaidHtmlFragmentTokenizer.tokenize(raw)) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            tokens.forEach { token ->
                when (token) {
                    is MermaidHtmlFragmentToken.Text -> {
                        if (!isSuppressingContent()) {
                            output.appendHtml(
                                token.value,
                                inheritedStyle.merge(current()),
                            )
                        }
                    }
                    is MermaidHtmlFragmentToken.Tag -> {
                        when (val result = consumeTag(token, output, inheritedStyle)) {
                            is GMResult.Ok -> Unit
                            is GMResult.Err -> return result
                        }
                    }
                }
            }
            return GMResult.Ok(Unit)
        }

        private fun consumeTag(
            tag: MermaidHtmlFragmentToken.Tag,
            output: StyledTextBuilder,
            inheritedStyle: TextStyle,
        ): GMResult<Unit, MermaidError> {
            if (tag.name in FORBIDDEN_CONTENT_TAGS) {
                updateSuppressedTags(suppressedTags, tag)
                return GMResult.Ok(Unit)
            }
            if (isSuppressingContent()) {
                return GMResult.Ok(Unit)
            }
            if (tag.closing) {
                closeFrame(tag.name)
                return GMResult.Ok(Unit)
            }
            when (tag.name) {
                "br" -> {
                    output.append("\n", inheritedStyle.merge(current()))
                    return GMResult.Ok(Unit)
                }
                "img" -> return unsupported("inline HTML image label")
                "a" -> return unsupported("inline HTML link label")
                "svg" -> return unsupported("inline HTML SVG label")
                "math" -> return unsupported("inline HTML MathML label")
                in UNSUPPORTED_LAYOUT_TAGS ->
                    return unsupported("HTML label tag '<${tag.name}>'")
            }
            if (tag.name in BLOCK_TAGS && output.hasText()) {
                output.ensureLineBreak(inheritedStyle.merge(current()))
            }
            val style = when (val result = styleFor(tag)) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            if (!tag.selfClosing && tag.name !in VOID_TAGS) {
                frames += HtmlStyleFrame(tag.name, style)
            }
            return GMResult.Ok(Unit)
        }

        private fun closeFrame(name: String) {
            val index = frames.indexOfLast { frame -> frame.tag == name }
            if (index >= 0) {
                frames.subList(index, frames.size).clear()
            }
        }

        private fun styleFor(
            tag: MermaidHtmlFragmentToken.Tag,
        ): GMResult<TextStyle, MermaidError> {
            val tagStyle = when (tag.name) {
                "strong", "b" -> TextStyle(weight = SceneTextWeight.Bold)
                "em", "i", "cite", "var" -> TextStyle(italic = true)
                "u", "ins" -> TextStyle(underline = true)
                "s", "strike", "del" -> TextStyle(lineThrough = true)
                "code", "kbd", "samp", "tt" ->
                    TextStyle(fontFamily = SceneTextFontFamily.Monospace)
                "sub" -> TextStyle(
                    baselineShift = SceneTextBaselineShift.Subscript,
                    fontSizeScale = HTML_SCRIPT_FONT_SIZE_SCALE,
                )
                "sup" -> TextStyle(
                    baselineShift = SceneTextBaselineShift.Superscript,
                    fontSizeScale = HTML_SCRIPT_FONT_SIZE_SCALE,
                )
                "small" -> TextStyle(fontSizeScale = 0.8f)
                "mark" -> TextStyle(background = SceneColor(0xFFFFFF00))
                "font" -> {
                    val colorSource = tag.attributes["color"]
                    if (colorSource == null) {
                        TextStyle()
                    } else {
                        val color = CssColorParser.parse(colorSource)
                            ?: return unsupported("HTML font color '$colorSource'")
                        TextStyle(color = color)
                    }
                }
                in SUPPORTED_NEUTRAL_TAGS -> TextStyle()
                else -> return unsupported("HTML label tag '<${tag.name}>'")
            }
            val inlineStyle = when (val result = parseInlineStyle(tag.attributes["style"])) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            return GMResult.Ok(tagStyle.merge(inlineStyle))
        }

        private fun parseInlineStyle(
            source: String?,
        ): GMResult<TextStyle, MermaidError> {
            if (source.isNullOrBlank()) {
                return GMResult.Ok(TextStyle())
            }
            var result = TextStyle()
            source.split(';').forEach { declaration ->
                val separator = declaration.indexOf(':')
                if (separator < 0) {
                    return@forEach
                }
                val property = declaration.substring(0, separator).trim().lowercase()
                val value = declaration
                    .substring(separator + 1)
                    .replace(IMPORTANT, "")
                    .trim()
                val style = when (property) {
                    "color" -> {
                        val color = CssColorParser.parse(value)
                            ?: return unsupported("HTML label CSS '$property: $value'")
                        TextStyle(color = color)
                    }
                    "background", "background-color" -> {
                        val color = CssColorParser.parse(value)
                            ?: return unsupported("HTML label CSS '$property: $value'")
                        TextStyle(background = color)
                    }
                    "font-weight" -> when (value.lowercase()) {
                        "bold", "bolder", "600", "700", "800", "900" ->
                            TextStyle(weight = SceneTextWeight.Bold)
                        "normal", "lighter", "100", "200", "300", "400", "500" ->
                            TextStyle(weight = SceneTextWeight.Normal)
                        else -> return unsupported("HTML label CSS '$property: $value'")
                    }
                    "font-style" -> when (value.lowercase()) {
                        "italic", "oblique" -> TextStyle(italic = true)
                        "normal" -> TextStyle(italic = false)
                        else -> return unsupported("HTML label CSS '$property: $value'")
                    }
                    "text-decoration", "text-decoration-line" -> {
                        val decorations = value.lowercase().split(WHITESPACE)
                        if (
                            decorations.any { decoration ->
                                decoration !in setOf("none", "underline", "line-through")
                            }
                        ) {
                            return unsupported("HTML label CSS '$property: $value'")
                        }
                        TextStyle(
                            underline = "underline" in decorations,
                            lineThrough = "line-through" in decorations,
                        )
                    }
                    "font-family" -> {
                        if ("monospace" !in value.lowercase()) {
                            return unsupported("HTML label CSS '$property: $value'")
                        }
                        TextStyle(fontFamily = SceneTextFontFamily.Monospace)
                    }
                    "font-size" -> {
                        val scale = parseFontSizeScale(value)
                            ?: return unsupported("HTML label CSS '$property: $value'")
                        TextStyle(fontSizeScale = scale)
                    }
                    "vertical-align" -> when (value.lowercase()) {
                        "sub" -> TextStyle(
                            baselineShift = SceneTextBaselineShift.Subscript,
                        )
                        "super" -> TextStyle(
                            baselineShift = SceneTextBaselineShift.Superscript,
                        )
                        "baseline" -> TextStyle()
                        else -> return unsupported("HTML label CSS '$property: $value'")
                    }
                    "" -> TextStyle()
                    else -> return unsupported("HTML label CSS '$property'")
                }
                result = result.merge(style)
            }
            return GMResult.Ok(result)
        }

        private fun parseFontSizeScale(source: String): Float? {
            val normalized = source.trim().lowercase()
            return when {
                normalized == "smaller" -> 0.8f
                normalized == "larger" -> 1.2f
                normalized.endsWith("%") ->
                    normalized.dropLast(1).toFloatOrNull()?.div(100f)
                normalized.endsWith("em") ->
                    normalized.dropLast(2).toFloatOrNull()
                else -> null
            }?.takeIf { scale -> scale > 0f && scale.isFinite() }
        }
    }

    private class StyledTextBuilder {
        private val text = StringBuilder()
        private val spans = mutableListOf<SceneTextSpan>()

        fun appendHtml(
            value: String,
            style: TextStyle,
        ) {
            append(MermaidHtmlEntityDecoder.decode(value), style)
        }

        fun append(
            value: String,
            style: TextStyle,
        ) {
            if (value.isEmpty()) {
                return
            }
            val start = text.length
            text.append(value)
            if (!style.isDefault()) {
                spans += SceneTextSpan(
                    start = start,
                    end = text.length,
                    weight = style.weight,
                    italic = style.italic == true,
                    underline = style.underline,
                    lineThrough = style.lineThrough,
                    color = style.color,
                    background = style.background,
                    fontFamily = style.fontFamily,
                    baselineShift = style.baselineShift,
                    fontSizeScale = style.fontSizeScale,
                )
            }
        }

        fun hasText(): Boolean = text.isNotEmpty()

        fun ensureLineBreak(style: TextStyle) {
            if (text.isNotEmpty() && text.last() != '\n') {
                append("\n", style)
            }
        }

        fun build(): MermaidRenderedText =
            MermaidRenderedText(text = text.toString(), spans = spans)
    }

    private class SvgLineBuilder {
        private val text = StringBuilder()
        private val spans = mutableListOf<SceneTextSpan>()
        private var lineHasWord = false

        fun appendWord(
            value: String,
            style: SvgWordStyle,
        ) {
            if (value.isEmpty()) {
                return
            }
            if (lineHasWord) {
                text.append(' ')
            }
            val start = text.length
            text.append(value)
            if (style != SvgWordStyle.Normal) {
                spans += SceneTextSpan(
                    start = start,
                    end = text.length,
                    weight = if (style == SvgWordStyle.Strong) SceneTextWeight.Bold else null,
                    italic = style == SvgWordStyle.Emphasis,
                )
            }
            lineHasWord = true
        }

        fun newLine() {
            text.append('\n')
            lineHasWord = false
        }

        fun build(): MermaidRenderedText =
            MermaidRenderedText(text = text.toString(), spans = spans)
    }

    private data class TextStyle(
        val weight: SceneTextWeight? = null,
        val italic: Boolean? = null,
        val underline: Boolean = false,
        val lineThrough: Boolean = false,
        val color: SceneColor? = null,
        val background: SceneColor? = null,
        val fontFamily: SceneTextFontFamily? = null,
        val baselineShift: SceneTextBaselineShift? = null,
        val fontSizeScale: Float = 1f,
    ) {
        fun merge(other: TextStyle): TextStyle = TextStyle(
            weight = other.weight ?: weight,
            italic = other.italic ?: italic,
            underline = underline || other.underline,
            lineThrough = lineThrough || other.lineThrough,
            color = other.color ?: color,
            background = other.background ?: background,
            fontFamily = other.fontFamily ?: fontFamily,
            baselineShift = other.baselineShift ?: baselineShift,
            fontSizeScale = fontSizeScale * other.fontSizeScale,
        )

        fun isDefault(): Boolean = this == TextStyle()
    }

    private enum class SvgWordStyle {
        Normal,
        Strong,
        Emphasis,
    }

    private val LINE_BREAK = Regex("""</?br\s*/?>""", RegexOption.IGNORE_CASE)
    private val SVG_LINE_BREAK = Regex("""\\n|\n|<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val SVG_WORD = Regex("""<[^>]+>|[^\s<>]+""")
    private val MULTIPLE_NEWLINES = Regex("""\n{2,}""")
    private val HTML_TEXT_NEWLINE = Regex("""\n *""")
    private val KATEX = Regex("""(?:\$\$?[^$]+\$\$?|\\\(|\\\[)""")
    private val FONT_AWESOME_ICON = Regex("""fa[bklrs]?:fa-[\w-]+""")
    private val TRAILING_INDENTED_NEWLINE = Regex("""\r?\n([\t ]*)${'$'}""")
    private val DEDENT_LINE = Regex("""\n([\t ]+|[^\s])""")
    private val LEADING_NEWLINE = Regex("""^\r?\n""")
    private val WHITESPACE = Regex("""\s+""")
    private val IMPORTANT = Regex("""\s*!important\s*$""", RegexOption.IGNORE_CASE)
    private val FORBIDDEN_CONTENT_TAGS = setOf(
        "embed",
        "iframe",
        "noscript",
        "object",
        "script",
        "style",
        "template",
    )
    private val FORBIDDEN_HTML_ATTRIBUTES = setOf(
        "srcdoc",
    )
    private val BLOCK_TAGS = setOf(
        "article",
        "div",
        "footer",
        "header",
        "p",
        "section",
    )
    private val VOID_TAGS = setOf(
        "br",
        "wbr",
    )
    private val SUPPORTED_NEUTRAL_TAGS = setOf(
        "abbr",
        "article",
        "div",
        "footer",
        "header",
        "p",
        "section",
        "span",
        "wbr",
    )
    private val UNSUPPORTED_LAYOUT_TAGS = setOf(
        "audio",
        "blockquote",
        "button",
        "canvas",
        "details",
        "fieldset",
        "form",
        "h1",
        "h2",
        "h3",
        "h4",
        "h5",
        "h6",
        "hr",
        "input",
        "li",
        "ol",
        "picture",
        "pre",
        "select",
        "table",
        "textarea",
        "ul",
        "video",
    )

    private data class HtmlStyleFrame(
        val tag: String,
        val style: TextStyle,
    )
}

internal data class MermaidRenderedText(
    val text: String,
    val spans: List<SceneTextSpan> = emptyList(),
)
