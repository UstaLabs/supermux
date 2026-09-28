package dev.supermux.editor.compose

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.supermux.editor.core.GutterMarker

/**
 * Everything the surface paints with. Plugins never see this: they describe text with semantic
 * CLASS names on editor-core decorations, and this theme resolves them.
 *
 * - [tokens]: the `tok-*` syntax classes (editor-syntax's vocabulary, `TokenClasses.ALL`); every one
 *   of them has a style in [light] and [dark].
 * - [classStyles]: any other mark class (`search-match`, `diff-add`, ...), from plugins.
 * - [lineClassBackgrounds]: `Decoration.LineStyle` classes, painted as a full-line background.
 *
 * When several marks cover the same text, their styles merge in precedence order and the later one
 * wins per attribute (see [LineLayouts]).
 */
@Immutable
data class EditorTheme(
    val background: Color,
    val foreground: Color,
    val selection: Color,
    val cursor: Color,
    val currentLine: Color,
    val gutterForeground: Color,
    val gutterBackground: Color,
    val tokens: Map<String, SpanStyle>,
    val classStyles: Map<String, SpanStyle> = emptyMap(),
    val lineClassBackgrounds: Map<String, Color> = emptyMap(),
    val fontFamily: FontFamily,
    val fontSizeSp: Float = 13f,
    val lineHeightFactor: Float = 1.45f,
    /** The gutter's number for the line the main cursor is on. */
    val gutterActiveForeground: Color = foreground,
    /** The touch selection handles (the theme's accent, like the caret, by default). */
    val selectionHandle: Color = cursor,
    /**
     * Each gutter marker column's width, by [GutterMarker.column] id; a column not listed is one
     * cell wide. The line numbers are a built-in column of their own, left of these.
     */
    val gutterColumns: Map<String, Dp> = DEFAULT_GUTTER_COLUMNS,
    /** How each marker kind ([GutterMarker.kind]) is drawn; a kind not listed draws nothing. */
    val gutterMarkers: Map<String, GutterMarkerStyle> = emptyMap(),
    /** A drawn placeholder chip (a fold's "⋯", an inline widget without content): its fill... */
    val widgetChipBackground: Color = selection,
    /** ...and its glyph. */
    val widgetChipForeground: Color = foreground,
) {
    /** The style of mark class [cls], or null when the theme does not draw it. */
    fun styleOf(cls: String): SpanStyle? = tokens[cls] ?: classStyles[cls]

    /**
     * This theme with [palette]'s colours (a light/dark switch): the palette's colours, tokens,
     * marker styles and its own classes, this theme's font, size, line height and gutter columns,
     * and every class of this theme the palette does not define (a host's `diff-add`, `search-match`)
     * kept as it is.
     */
    fun withPalette(palette: EditorTheme): EditorTheme = palette.copy(
        fontFamily = fontFamily,
        fontSizeSp = fontSizeSp,
        lineHeightFactor = lineHeightFactor,
        gutterColumns = gutterColumns,
        classStyles = classStyles + palette.classStyles,
        lineClassBackgrounds = lineClassBackgrounds + palette.lineClassBackgrounds,
        gutterMarkers = gutterMarkers + palette.gutterMarkers,
    )

    companion object {
        /** The class the surface puts on text an IME is still composing (an underline). */
        const val COMPOSITION_CLASS = "ime-composition"

        /** basics' active line (a `LineStyle`): painted in [currentLine]. */
        const val ACTIVE_LINE_CLASS = "active-line"

        /**
         * The line and mark classes of the M4a plugins, given [currentLine] and the accents: basics'
         * active line, bracket matching and selection matches.
         */
        fun pluginClasses(currentLine: Color, bracket: Color, nonmatching: Color, selectionMatch: Color): Pair<Map<String, Color>, Map<String, SpanStyle>> =
            mapOf(ACTIVE_LINE_CLASS to currentLine) to mapOf(
                "matching-bracket" to SpanStyle(background = bracket),
                "nonmatching-bracket" to SpanStyle(color = nonmatching, background = nonmatching.copy(alpha = 0.18f)),
                "selection-match" to SpanStyle(background = selectionMatch),
            )

        /**
         * The search plugin's marks: `search-match` on every match on screen, `search-match-selected`
         * (with `search-match`, later so it wins) on the current one.
         */
        fun searchClasses(match: Color, selected: Color): Map<String, SpanStyle> = mapOf(
            "search-match" to SpanStyle(background = match),
            "search-match-selected" to SpanStyle(background = selected),
        )

        /** The M4 plugins' columns: diff bars, lint dots, comment bubbles, fold arrows. */
        val DEFAULT_GUTTER_COLUMNS: Map<String, Dp> = mapOf("diff" to 6.dp, "lint" to 12.dp, "comment" to 16.dp, "fold" to 14.dp)

        /** Marker kinds drawn by [dark] and [light], with the diff, lint and comment accents given. */
        fun markerStyles(add: Color, remove: Color, change: Color, error: Color, warning: Color, comment: Color, fold: Color): Map<String, GutterMarkerStyle> = mapOf(
            "diff-add" to GutterMarkerStyle(add, GutterMarkerShape.BAR),
            "diff-remove" to GutterMarkerStyle(remove, GutterMarkerShape.BAR),
            "diff-change" to GutterMarkerStyle(change, GutterMarkerShape.BAR),
            "lint-error" to GutterMarkerStyle(error, GutterMarkerShape.DOT),
            "lint-warning" to GutterMarkerStyle(warning, GutterMarkerShape.DOT),
            "comment" to GutterMarkerStyle(comment, GutterMarkerShape.BUBBLE),
            "fold-open" to GutterMarkerStyle(fold, GutterMarkerShape.OPEN),
            "fold-closed" to GutterMarkerStyle(fold, GutterMarkerShape.CLOSED),
        )

        /** Tuned to supermux's dark palette: its near-black code tone, the teal accent, One Dark tokens. */
        fun dark(font: FontFamily): EditorTheme = pluginClasses(
            currentLine = Color(0xFF151713), bracket = Color(0x47BAD0F8), nonmatching = Color(0xFFE06C75), selectionMatch = Color(0x33AAFE66),
        ).let { (lines, marks) -> EditorTheme(
            lineClassBackgrounds = lines,
            classStyles = marks + searchClasses(match = Color(0x4DE5C07B), selected = Color(0x99D19A66)),
            background = Color(0xFF0A0B09),
            foreground = Color(0xFFD8DED3),
            selection = Color(0x664BBAA7),
            cursor = Color(0xFF4BBAA7),
            currentLine = Color(0xFF151713),
            gutterForeground = Color(0xFF5E6359),
            gutterBackground = Color(0xFF0A0B09),
            gutterActiveForeground = Color(0xFFB9BFB3),
            gutterMarkers = markerStyles(
                add = Color(0xFF6BBF59), remove = Color(0xFFE06C75), change = Color(0xFF61AFEF),
                error = Color(0xFFE06C75), warning = Color(0xFFE5C07B), comment = Color(0xFF4BBAA7), fold = Color(0xFF8A9084),
            ),
            tokens = tokenStyles(
                keyword = Color(0xFFC678DD), string = Color(0xFF98C379), special = Color(0xFF56B6C2),
                number = Color(0xFFD19A66), comment = Color(0xFF7F848E), function = Color(0xFF5CC8B4),
                method = Color(0xFF61AFEF), type = Color(0xFFE5C07B), variable = Color(0xFFD8DED3),
                red = Color(0xFFE06C75), parameter = Color(0xFFE5A36C), punctuation = Color(0xFFA3AAA0),
                link = Color(0xFF61AFEF),
            ),
            fontFamily = font,
        ) }

        /** Tuned to supermux's light palette: its paper code tone, the deep teal accent, One Light tokens. */
        fun light(font: FontFamily): EditorTheme = pluginClasses(
            currentLine = Color(0xFFF0F1EB), bracket = Color(0x52328C82), nonmatching = Color(0xFFBB5555), selectionMatch = Color(0x5599FF77),
        ).let { (lines, marks) -> EditorTheme(
            lineClassBackgrounds = lines,
            classStyles = marks + searchClasses(match = Color(0x66FFD54A), selected = Color(0x99FF9F1C)),
            background = Color(0xFFFEFEFB),
            foreground = Color(0xFF1F221C),
            selection = Color(0x4D007368),
            cursor = Color(0xFF007368),
            currentLine = Color(0xFFF0F1EB),
            gutterForeground = Color(0xFF9A9E94),
            gutterBackground = Color(0xFFFEFEFB),
            gutterActiveForeground = Color(0xFF3C4038),
            gutterMarkers = markerStyles(
                add = Color(0xFF2E9A3E), remove = Color(0xFFD13438), change = Color(0xFF2F6FD6),
                error = Color(0xFFD13438), warning = Color(0xFFB88600), comment = Color(0xFF007368), fold = Color(0xFF6E7268),
            ),
            tokens = tokenStyles(
                keyword = Color(0xFFA626A4), string = Color(0xFF50A14F), special = Color(0xFF0184BC),
                number = Color(0xFF986801), comment = Color(0xFF8A8F87), function = Color(0xFF00796B),
                method = Color(0xFF4078F2), type = Color(0xFFC18401), variable = Color(0xFF1F221C),
                red = Color(0xFFE45649), parameter = Color(0xFFB35A1F), punctuation = Color(0xFF55594F),
                link = Color(0xFF4078F2),
            ),
            fontFamily = font,
        ) }

        /** [dark] or [light] after the system setting, with the packaged face. */
        @Composable
        fun default(): EditorTheme {
            val font = packagedEditorFontFamily()
            val isDark = isSystemInDarkTheme()
            return remember(font, isDark) { if (isDark) dark(font) else light(font) }
        }

        private fun tokenStyles(
            keyword: Color, string: Color, special: Color, number: Color, comment: Color, function: Color,
            method: Color, type: Color, variable: Color, red: Color, parameter: Color, punctuation: Color,
            link: Color,
        ): Map<String, SpanStyle> = mapOf(
            "tok-keyword" to SpanStyle(color = keyword),
            "tok-string" to SpanStyle(color = string),
            "tok-string-special" to SpanStyle(color = special),
            "tok-number" to SpanStyle(color = number),
            "tok-constant" to SpanStyle(color = number),
            "tok-constant-builtin" to SpanStyle(color = number),
            "tok-comment" to SpanStyle(color = comment, fontStyle = FontStyle.Italic),
            "tok-function" to SpanStyle(color = function),
            "tok-function-builtin" to SpanStyle(color = special),
            "tok-method" to SpanStyle(color = method),
            "tok-type" to SpanStyle(color = type),
            "tok-type-builtin" to SpanStyle(color = type),
            "tok-variable" to SpanStyle(color = variable),
            "tok-variable-builtin" to SpanStyle(color = red),
            "tok-parameter" to SpanStyle(color = parameter),
            "tok-property" to SpanStyle(color = red),
            "tok-operator" to SpanStyle(color = special),
            "tok-punctuation" to SpanStyle(color = punctuation),
            "tok-tag" to SpanStyle(color = red),
            "tok-attribute" to SpanStyle(color = number),
            "tok-namespace" to SpanStyle(color = type),
            "tok-label" to SpanStyle(color = keyword),
            "tok-escape" to SpanStyle(color = special),
            "tok-regexp" to SpanStyle(color = special),
            "tok-markup-heading" to SpanStyle(color = red, fontWeight = FontWeight.Bold),
            "tok-markup-emphasis" to SpanStyle(color = keyword, fontStyle = FontStyle.Italic),
            "tok-markup-link" to SpanStyle(color = link, textDecoration = TextDecoration.Underline),
            "tok-diff-plus" to SpanStyle(color = string),
            "tok-diff-minus" to SpanStyle(color = red),
        )
    }
}

/** How a gutter marker kind is drawn: its [shape] in its [color]. */
@Immutable
data class GutterMarkerStyle(val color: Color, val shape: GutterMarkerShape)

/** The shapes a gutter marker can take (drawn, not glyphs: the web has no emoji font). */
enum class GutterMarkerShape {
    /** A thin bar the height of the line (diff). */
    BAR,
    /** A dot in the line's first row (lint). */
    DOT,
    /** A speech bubble (a review comment). */
    BUBBLE,
    /** A downward arrow (a fold that can be folded). */
    OPEN,
    /** A rightward arrow (a folded region). */
    CLOSED,
}
